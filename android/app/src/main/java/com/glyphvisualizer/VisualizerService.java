package com.glyphvisualizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.nothing.ketchum.Common;
import com.nothing.ketchum.Glyph;
import com.nothing.ketchum.GlyphManager;

public class VisualizerService extends Service {

    private static final String TAG = "VisualizerService";
    private static final String CHANNEL_ID = "GlyphVisualizerChannel";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_STOP = "STOP";
    private static final String ACTION_CYCLE_SENSITIVITY = "CYCLE_SENSITIVITY";
    private static final String ACTION_SET_CAPTURE_MODE = "SET_CAPTURE_MODE";
    private static final String ACTION_SET_FOREGROUND = "SET_FOREGROUND";

    private static final String SOURCE_IDLE = "IDLE";
    private static final String SOURCE_SYSTEM = "SYSTEM";
    private static final String SOURCE_MIC = "MIC";

    private static final double[] SENSITIVITY_PRESETS = {0.75, 1.0, 1.3, 1.7};
    private static final String[] SENSITIVITY_PRESET_LABELS = {"LOW", "STD", "HIGH", "MAX"};

    private static final long GLYPH_UPDATE_INTERVAL_MS = 16;
    // UI telemetry posting rate. Post ~every DSP frame (capped ~120Hz) while the app is on-screen so
    // the dashboard tracks the 120Hz panel; drop to ~30Hz when backgrounded since nothing is watching.
    // Effective rate is min(this, DSP rate): ~48Hz on SYSTEM, up to ~90Hz on MIC. Flipped by the
    // Flutter app-lifecycle observer via ACTION_SET_FOREGROUND.
    private static final long FLUTTER_EVENT_INTERVAL_FG_MS = 8;
    private static final long FLUTTER_EVENT_INTERVAL_BG_MS = 33;
    private volatile long flutterEventIntervalMs = FLUTTER_EVENT_INTERVAL_FG_MS;

    private AudioCapture audioCapture;
    private SystemVisualizerCapture systemVisualizerCapture;
    private FFT fft;
    private BeatDetector beatDetector;
    private GlyphController glyphController;
    private GlyphManager glyphManager;
    private boolean useInternalAudio = false;

    private float[] real;
    private float[] imag;
    private float[] magnitude;
    private float[] hannWindow;
    private short[] frameAccumulator;
    private int frameAccumulatorFill = 0;

    public static boolean isServiceRunning = false;
    private volatile boolean isRunning = false;

    private volatile double sensitivity = 1.0;
    private volatile int currentModel = 4;
    private volatile String currentCaptureSource = SOURCE_IDLE;
    private volatile int visualizerSampleRate = 44100;

    private java.util.concurrent.ArrayBlockingQueue<short[]> dspQueue;
    private Thread dspThread;
    private volatile boolean dspRunning = false;
    private final short[][] dspBufferPool = new short[3][];
    private int poolIndex = 0;

    private final android.os.Handler mainHandler = new android.os.Handler(Looper.getMainLooper());
    private android.os.PowerManager.WakeLock wakeLock;
    private long lastGlyphUpdate = 0;
    private final java.util.Map<String, Object> flutterEventMap = new java.util.HashMap<>(32);
    private final java.util.List<Double> flutterMagList = new java.util.ArrayList<>(64);
    private long lastFlutterPostAt = 0;
    private long lastDspFrameAt = 0;
    private volatile double dspFrameRateHz = 0;
    private long lastNotificationRefresh = 0;
    private static final long NOTIFICATION_DEBOUNCE_MS = 500;

    // Physical rendering state (Nothing Phone LEDs)
    private int physicalActiveDrum = 0;

    // UI event state (Flutter Dashboard)
    private volatile int activeDrum = 0; // 0 = none, 1 = kick, 2 = snare, 3 = hat
    private volatile long activeDrumAt = 0; // epoch milliseconds
    private boolean pendingKickOnset = false;
    private boolean pendingSnareOnset = false;
    private boolean pendingHatOnset = false;
    // Last-rendered on/off state (-1 = unknown), so we emit a frame only when it flips and stop
    // flooding the Nothing glyph service with identical frames while idle.
    private int lastDrumState = -1;
    // Peak-follower over total spectral energy. Normalizes the per-band energies for the "BEAT"
    // overdrive flashing so it adapts to loudness instead of a fixed scale.
    private double runningMaxEnergy = 0.01;
    // Minimum on-hold per zone. Once a band flashes, its zone stays lit at least this long so brief
    // frame-to-frame energy dips can't strobe it on/off. Tuned per drum (kick longest, hat shortest).
    private long bassLitUntil = 0, midLitUntil = 0, trebleLitUntil = 0;
    private static final long HOLD_BASS_MS = 50;
    private static final long HOLD_MID_MS = 42;
    private static final long HOLD_TREBLE_MS = 35;


    private GlyphManager.Callback glyphCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;
        createNotificationChannel();
        initGlyphSDK();
        initAudioEngine();

        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "GlyphVisualizer::CaptureWakeLock");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_CYCLE_SENSITIVITY.equals(action)) {
                sensitivity = nextSensitivityPreset(sensitivity);
                refreshNotification();
                return START_STICKY;
            }
            if (ACTION_SET_CAPTURE_MODE.equals(action)) {
                if (intent.hasExtra("useInternalAudio")) {
                    useInternalAudio = intent.getBooleanExtra("useInternalAudio", true);
                }
                // Already a foreground service; re-asserting is harmless and avoids any dangling-FGS
                // edge if the process was just recreated.
                startForeground(NOTIFICATION_ID, buildNotification(), computeForegroundServiceType());
                if (isRunning) {
                    restartCaptureForModeChange();
                }
                return START_STICKY;
            }
            if (ACTION_SET_FOREGROUND.equals(action)) {
                boolean foreground = intent.getBooleanExtra("foreground", true);
                flutterEventIntervalMs = foreground
                        ? FLUTTER_EVENT_INTERVAL_FG_MS
                        : FLUTTER_EVENT_INTERVAL_BG_MS;
                Log.d(TAG, "UI " + (foreground ? "foreground" : "background")
                        + ": event interval=" + flutterEventIntervalMs + "ms");
                return START_STICKY;
            }

            if (intent.hasExtra("sensitivity")) {
                sensitivity = intent.getDoubleExtra("sensitivity", 1.0);
            }
            if (intent.hasExtra("model")) {
                currentModel = intent.getIntExtra("model", 4);
                if (glyphController != null) {
                    glyphController.setModel(currentModel);
                }
            }
            if (intent.hasExtra("useInternalAudio")) {
                useInternalAudio = intent.getBooleanExtra("useInternalAudio", false);
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification(), computeForegroundServiceType());

        if (!isRunning) {
            startVisualization();
        } else {
            refreshNotification();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isServiceRunning = false;
        isRunning = false;
        stopCaptureEngines();
        releaseWakeLock();
        currentCaptureSource = SOURCE_IDLE;

        if (glyphController != null) {
            glyphController.turnOff();
        }
        if (glyphManager != null) {
            try {
                glyphManager.closeSession();
            } catch (Exception e) {
                Log.e(TAG, "Error closing session", e);
            }
            glyphManager.unInit();
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Both capture sources (mic and the global Visualizer) read audio via RECORD_AUDIO, so the
    // foreground service runs as a microphone-type service.
    private int computeForegroundServiceType() {
        return android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
    }

    // A partial wake lock keeps the DSP thread running when the screen is off and the phone
    // is face down — the core use case — instead of stalling under Doze.
    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Glyph Visualizer",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Music visualizer running");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, VisualizerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent presetIntent = new Intent(this, VisualizerService.class);
        presetIntent.setAction(ACTION_CYCLE_SENSITIVITY);
        PendingIntent presetPendingIntent = PendingIntent.getService(
                this,
                2,
                presetIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
                this,
                3,
                mainIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String statusText = "BEAT"
                + " | "
                + currentCaptureSource
                + " | "
                + sensitivityPresetLabel(sensitivity);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Glyph Visualizer")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(mainPendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .addAction(android.R.drawable.ic_menu_manage, "Preset", presetPendingIntent)
                .setOngoing(true)
                .build();
    }

    private void refreshNotification() {
        long now = System.currentTimeMillis();
        if (now - lastNotificationRefresh < NOTIFICATION_DEBOUNCE_MS) return;
        lastNotificationRefresh = now;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void initGlyphSDK() {
        glyphManager = GlyphManager.getInstance(getApplicationContext());
        glyphCallback = new GlyphManager.Callback() {
            @Override
            public void onServiceConnected(android.content.ComponentName componentName) {
                try {
                    if (Common.is20111()) glyphManager.register(Glyph.DEVICE_20111);
                    if (Common.is22111()) glyphManager.register(Glyph.DEVICE_22111);
                    if (Common.is23111()) glyphManager.register(Glyph.DEVICE_23111);
                    if (Common.is23113()) glyphManager.register(Glyph.DEVICE_23113);
                    if (Common.is24111()) glyphManager.register(Glyph.DEVICE_24111);
                    if (Common.is25111()) glyphManager.register(Glyph.DEVICE_25111);

                    glyphManager.openSession();
                    glyphController = new GlyphController(glyphManager);
                    glyphController.setModel(currentModel);
                    Log.d(TAG, "Glyph SDK initialized");
                } catch (Exception e) {
                    Log.e(TAG, "Error opening session", e);
                }
            }

            @Override
            public void onServiceDisconnected(android.content.ComponentName componentName) {
                try {
                    glyphManager.closeSession();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing session on disconnect", e);
                }
            }
        };
        glyphManager.init(glyphCallback);
    }

    private void initAudioEngine() {
        int fftSize = 1024;
        fft = new FFT(fftSize);
        beatDetector = new BeatDetector();
        real = new float[fftSize];
        imag = new float[fftSize];
        magnitude = new float[fftSize / 2];
        frameAccumulator = new short[fftSize * 2];

        // Precompute the Hann window once rather than recomputing Math.cos per bin per frame.
        hannWindow = new float[fftSize];
        for (int i = 0; i < fftSize; i++) {
            hannWindow[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / (fftSize - 1))));
        }

        dspBufferPool[0] = new short[fftSize * 2];
        dspBufferPool[1] = new short[fftSize * 2];
        dspBufferPool[2] = new short[fftSize * 2];
        poolIndex = 0;

        dspQueue = new java.util.concurrent.ArrayBlockingQueue<>(3);

        audioCapture = new AudioCapture(
                this::processAudioData,
                reason -> onCaptureError(SOURCE_MIC, reason));
        systemVisualizerCapture = new SystemVisualizerCapture(
                this::processVisualizerFrame,
                reason -> onCaptureError(SOURCE_SYSTEM, reason));
    }

    // Invoked from a capture thread when a source dies. A Visualizer (SYSTEM) failure falls back to
    // the mic; a mic failure with nothing left stops the service.
    private void onCaptureError(String source, String reason) {
        Log.w(TAG, "Capture error on " + source + ": " + reason);
        mainHandler.post(() -> {
            if (!isRunning) {
                return;
            }
            if (SOURCE_SYSTEM.equals(source)) {
                if (systemVisualizerCapture != null) {
                    systemVisualizerCapture.stop();
                }
                boolean micStarted = audioCapture != null && audioCapture.start();
                currentCaptureSource = micStarted ? SOURCE_MIC : SOURCE_IDLE;
                if (!micStarted) {
                    haltService();
                    return;
                }
                Log.d(TAG, "Fell back to mic after Visualizer error");
                refreshNotification();
            } else {
                haltService();
            }
        });
    }

    private void haltService() {
        currentCaptureSource = SOURCE_IDLE;
        isRunning = false;
        isServiceRunning = false;
        refreshNotification();
        stopSelf();
    }

    private void startVisualization() {
        activeDrum = 0;
        activeDrumAt = 0;
        physicalActiveDrum = 0;
        pendingKickOnset = false;
        pendingSnareOnset = false;
        pendingHatOnset = false;
        lastDrumState = -1;
        frameAccumulatorFill = 0;
        boolean captureStarted = false;

        if (useInternalAudio && systemVisualizerCapture != null && systemVisualizerCapture.start()) {
            // System audio via the global Visualizer — the only path that actually sees Spotify on
            // this hardware, and it needs no screen-record grant. We deliberately do NOT run
            // MediaProjection: an AudioPlaybackCapture session poisons the Visualizer, which then
            // reads ~3s of silence and we'd drop to the mic (verified via on-device probe).
            currentCaptureSource = SOURCE_SYSTEM;
            captureStarted = true;
            Log.d(TAG, "Using system Visualizer capture (internal audio)");
        } else {
            captureStarted = audioCapture != null && audioCapture.start();
            currentCaptureSource = captureStarted ? SOURCE_MIC : SOURCE_IDLE;
        }

        isRunning = captureStarted;
        if (!captureStarted) {
            Log.e(TAG, "Failed to start audio capture");
            refreshNotification();
            isServiceRunning = false;
            stopSelf();
            return;
        }

        startDspThread();
        acquireWakeLock();

        refreshNotification();
        Log.d(TAG, "Visualization started");
    }

    // Switch the active capture source live (MIC <-> SYSTEM) when the user flips the in-app toggle,
    // keeping the DSP thread and glyph session up so it's a quick swap, not a full restart.
    private void restartCaptureForModeChange() {
        physicalActiveDrum = 0;
        activeDrum = 0;
        activeDrumAt = 0;
        pendingKickOnset = false;
        pendingSnareOnset = false;
        pendingHatOnset = false;
        lastDrumState = -1;
        if (glyphController != null) {
            glyphController.turnOff();
        }

        if (audioCapture != null) {
            audioCapture.stop();
        }
        if (systemVisualizerCapture != null) {
            systemVisualizerCapture.stop();
        }
        frameAccumulatorFill = 0;
        dspQueue.clear();

        boolean started;
        if (useInternalAudio && systemVisualizerCapture != null && systemVisualizerCapture.start()) {
            currentCaptureSource = SOURCE_SYSTEM;
            started = true;
            Log.d(TAG, "Capture mode -> SYSTEM (Visualizer)");
        } else {
            started = audioCapture != null && audioCapture.start();
            currentCaptureSource = started ? SOURCE_MIC : SOURCE_IDLE;
            Log.d(TAG, "Capture mode -> MIC");
        }

        if (!started) {
            haltService();
            return;
        }
        lastDrumState = -1; // force the next render to re-emit
        refreshNotification();
    }

    private void stopCaptureEngines() {
        frameAccumulatorFill = 0;
        if (audioCapture != null) {
            audioCapture.stop();
        }
        if (systemVisualizerCapture != null) {
            systemVisualizerCapture.stop();
        }
        stopDspThread();
        activeDrum = 0;
        activeDrumAt = 0;
        physicalActiveDrum = 0;
        pendingKickOnset = false;
        pendingSnareOnset = false;
        pendingHatOnset = false;
        lastDrumState = -1;
    }

    private void startDspThread() {
        if (dspRunning && dspThread != null && dspThread.isAlive()) {
            return;
        }
        dspQueue.clear();
        dspRunning = true;
        dspThread = new Thread(() -> {
            while (dspRunning) {
                try {
                    short[] frame = dspQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        processAssembledFrame(frame);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        dspThread.start();
    }

    private void stopDspThread() {
        dspRunning = false;
        if (dspThread != null) {
            dspThread.interrupt();
            try {
                dspThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dspThread = null;
        }
    }

    private int getActiveSampleRate() {
        if (SOURCE_SYSTEM.equals(currentCaptureSource)) {
            return visualizerSampleRate;
        }
        return audioCapture != null ? audioCapture.getSampleRate() : 44100;
    }

    private void processAudioData(short[] buffer, int bytesRead) {
        if (!isRunning || bytesRead <= 0) {
            return;
        }

        int expectedFrameSize = real.length * 2;
        if (frameAccumulator == null || frameAccumulator.length != expectedFrameSize) {
            frameAccumulator = new short[expectedFrameSize];
            frameAccumulatorFill = 0;
        }

        int availableSamples = Math.min(bytesRead, buffer.length);
        int readOffset = 0;
        while (readOffset < availableSamples) {
            int copyCount = Math.min(frameAccumulator.length - frameAccumulatorFill, availableSamples - readOffset);
            System.arraycopy(buffer, readOffset, frameAccumulator, frameAccumulatorFill, copyCount);
            frameAccumulatorFill += copyCount;
            readOffset += copyCount;

            if (frameAccumulatorFill == frameAccumulator.length) {
                if (dspQueue.remainingCapacity() > 0) {
                    short[] frameCopy = dspBufferPool[poolIndex];
                    System.arraycopy(frameAccumulator, 0, frameCopy, 0, frameAccumulator.length);
                    dspQueue.offer(frameCopy);
                    poolIndex = (poolIndex + 1) % 3;
                }
                // 75% overlap: keep the newest 3/4 of the window and advance by a 256-sample
                // hop (was 512) so the detector re-analyzes every ~5.8ms instead of ~11.6ms.
                // Doubles the analysis rate to ~172Hz so fast hits in dense rolls aren't skipped.
                int hopLength = frameAccumulator.length / 4; // 256 samples/channel (stereo shorts)
                int keepLength = frameAccumulator.length - hopLength;
                System.arraycopy(frameAccumulator, hopLength, frameAccumulator, 0, keepLength);
                frameAccumulatorFill = keepLength;
            }
        }
    }

    // The system Visualizer hands us a whole 1024-sample snapshot ~20x/sec with gaps between
    // snapshots, so — unlike the continuous mic/projection stream — each snapshot IS one analysis
    // frame. Map the 8-bit unsigned waveform to the 16-bit stereo-interleaved layout the DSP expects
    // and push it straight to the queue; we deliberately bypass processAudioData's overlap/hop
    // assembly, which would stitch disjoint snapshots into garbage frames. Runs on the Visualizer
    // callback thread, but the capture sources are mutually exclusive so the buffer pool isn't
    // contended.
    private void processVisualizerFrame(byte[] waveform, int samplingRateHz) {
        if (!isRunning || waveform == null || waveform.length == 0) {
            return;
        }
        visualizerSampleRate = samplingRateHz;
        if (dspQueue.remainingCapacity() <= 0) {
            return;
        }
        short[] frame = dspBufferPool[poolIndex];
        int n = Math.min(waveform.length, real.length);
        for (int i = 0; i < n; i++) {
            short s = (short) (((waveform[i] & 0xFF) - 128) << 8);
            frame[i * 2] = s;
            frame[i * 2 + 1] = s;
        }
        // Zero-pad if a device hands back a smaller window than our FFT size.
        for (int i = n; i < real.length; i++) {
            frame[i * 2] = 0;
            frame[i * 2 + 1] = 0;
        }
        dspQueue.offer(frame);
        poolIndex = (poolIndex + 1) % 3;
    }

    private void processAssembledFrame(short[] frameBuffer) {
        if (!isRunning) {
            return;
        }

        int fftSize = real.length;
        // The beat visualizer always needs the per-band onset detector.
        boolean computeAdvanced = true;

        // Build the mono FFT input while accumulating per-channel energy for a cheap,
        // time-domain stereo pan estimate (no second FFT required).
        double leftEnergy = 0;
        double rightEnergy = 0;
        for (int i = 0; i < fftSize; i++) {
            float leftSample = frameBuffer[i * 2] / 32768.0f;
            float rightSample = frameBuffer[i * 2 + 1] / 32768.0f;
            leftEnergy += leftSample * leftSample;
            rightEnergy += rightSample * rightSample;

            real[i] = (leftSample + rightSample) * 0.5f * hannWindow[i];
            imag[i] = 0;
        }

        fft.forward(real, imag);
        fft.magnitude(real, imag, magnitude);

        int activeSampleRate = getActiveSampleRate();

        BeatDetector.BeatResult beatResult =
                beatDetector.detect(real, imag, magnitude, activeSampleRate, sensitivity, computeAdvanced);

        double totalStereoEnergy = leftEnergy + rightEnergy;
        beatResult.spatialPan = totalStereoEnergy > 1e-9
                ? (rightEnergy - leftEnergy) / totalStereoEnergy
                : 0.0;

        // Latch the single dominant onset across skipped renders so the throttled glyph render can't
        // drop a fast hit. Drive this from the rate-gated primary onset (one drum type per frame, gated
        // by minBeatInterval) rather than the free-firing per-band SuperFlux flags — the latter light
        // every band on every transient and make the overdrive flash strobe far faster than the old app.
        if (beatResult.isOnset) {
            BeatDetector.BeatType t = beatResult.type;
            pendingKickOnset  |= (t == BeatDetector.BeatType.KICK || t == BeatDetector.BeatType.SUB_BASS);
            pendingSnareOnset |= (t == BeatDetector.BeatType.SNARE);
            pendingHatOnset   |= (t == BeatDetector.BeatType.HIHAT);
        }

        long nowMonotonic = android.os.SystemClock.elapsedRealtime();
        long nowEpoch = System.currentTimeMillis();
        long now = nowEpoch;

        int uiStruck = 0;
        boolean isNewOnset = false;
        if (beatResult.multiBandOnsets != null) {
            if (beatResult.multiBandOnsets[0] || beatResult.multiBandOnsets[1]) {
                uiStruck = 1;
                isNewOnset = true;
            } else if (beatResult.multiBandOnsets[2]) {
                uiStruck = 2;
                isNewOnset = true;
            } else if (beatResult.multiBandOnsets[3] || beatResult.multiBandOnsets[4]) {
                uiStruck = 3;
                isNewOnset = true;
            }
        }

        if (isNewOnset) {
            activeDrum = uiStruck;
            activeDrumAt = nowEpoch;
        }

        final int uiActiveDrum = activeDrum;
        final long uiActiveDrumAt = activeDrumAt;

        // Measure the true analysis rate every DSP frame (~86Hz), independent of the
        // throttled UI posting below, so the diagnostics readout reflects real throughput.
        if (lastDspFrameAt > 0) {
            long dspDelta = now - lastDspFrameAt;
            if (dspDelta > 0) {
                dspFrameRateHz = 1000.0 / dspDelta;
            }
        }
        lastDspFrameAt = now;

        if (nowMonotonic - lastGlyphUpdate >= GLYPH_UPDATE_INTERVAL_MS) {
            updateGlyphs(beatResult);
            lastGlyphUpdate = nowMonotonic;
        }

        // Throttle UI events to ~30fps (the dashboard redraws no faster); the glyph hardware
        // and DSP analysis run at their own rates. Build the snapshot only when we actually post.
        if (isNewOnset || now - lastFlutterPostAt >= flutterEventIntervalMs) {
            lastFlutterPostAt = now;
            final int binsToSnapshot = Math.min(magnitude.length, 64);
            final double[] magnitudeSnapshot = new double[binsToSnapshot];
            // magnitude[] holds squared values; take the root to hand linear magnitudes to Flutter.
            for (int i = 0; i < binsToSnapshot; i++) {
                magnitudeSnapshot[i] = Math.sqrt(magnitude[i]);
            }
            final BeatDetector.BeatResult postResult = beatResult;
            mainHandler.post(() -> sendEventToFlutter(postResult, magnitudeSnapshot, now, uiActiveDrum, uiActiveDrumAt));
        }
    }

    private void sendEventToFlutter(BeatDetector.BeatResult result, double[] magnitudeSnapshot, long timestampMs, int uiActiveDrum, long uiActiveDrumAt) {
        io.flutter.plugin.common.EventChannel.EventSink sink = MainActivity.globalEventSink;
        if (sink == null) {
            return;
        }

        // Report the true DSP analysis rate, not the throttled UI post interval.
        double updateRateHz = dspFrameRateHz;

        flutterMagList.clear();
        for (int i = 0; i < magnitudeSnapshot.length; i++) {
            flutterMagList.add(magnitudeSnapshot[i]);
        }

        flutterEventMap.clear();
        flutterEventMap.put("magnitude", flutterMagList);
        flutterEventMap.put("bassEnergy", result.bassEnergy);
        flutterEventMap.put("midEnergy", result.midEnergy);
        flutterEventMap.put("trebleEnergy", result.trebleEnergy);
        flutterEventMap.put("isOnset", result.isOnset);
        flutterEventMap.put("confidence", result.confidence);
        flutterEventMap.put("beatType", result.type.name());
        flutterEventMap.put("strongestBandIndex", result.strongestBandIndex);
        flutterEventMap.put("spatialPan", result.spatialPan);
        flutterEventMap.put("captureSource", currentCaptureSource);
        flutterEventMap.put("updateRateHz", updateRateHz);
        flutterEventMap.put("spectralFlux", result.spectralFlux);
        flutterEventMap.put("adaptiveThreshold", result.adaptiveThreshold);
        flutterEventMap.put("noiseFloor", result.noiseFloor);
        flutterEventMap.put("fluxVariance", result.fluxVariance);
        flutterEventMap.put("currentSensitivity", sensitivity);
        flutterEventMap.put("bpm", result.bpm);
        flutterEventMap.put("activeDrum", uiActiveDrum);
        flutterEventMap.put("activeDrumAt", uiActiveDrumAt);

        try {
            sink.success(flutterEventMap);
        } catch (Exception e) {
            Log.w(TAG, "EventSink stale, skipping frame");
        }
    }

    private void updateGlyphs(BeatDetector.BeatResult result) {
        if (glyphController == null) {
            return;
        }

        // Peak-follower over total spectral energy (bass+mid+treble), used to normalize the
        // per-band energies below so the flashing adapts to overall loudness.
        double totalEnergy = result.bassEnergy + result.midEnergy + result.trebleEnergy;
        if (totalEnergy > runningMaxEnergy) {
            runningMaxEnergy = totalEnergy;
        } else {
            runningMaxEnergy *= 0.999;
        }

        // "BEAT" (overdrive): violent raw-energy tracking, no smooth decay. Each zone flashes when its
        // normalized band energy clears a sensitivity-derived threshold, OR when the matching drum onset
        // fired. Onsets are latched across skipped renders so fast strikes are never dropped, and multiple
        // zones can be lit at once. Mapping (in flashBeat): bass->slash, mid->dot, treble->ring.
        double normBass   = clamp01(result.bassEnergy   / Math.max(runningMaxEnergy * 0.4, 0.01));
        double normMid    = clamp01(result.midEnergy    / Math.max(runningMaxEnergy * 0.3, 0.01));
        double normTreble = clamp01(result.trebleEnergy / Math.max(runningMaxEnergy * 0.2, 0.01));

        // Sensitivity 1.0 -> low threshold (flashes easily); 0.1 -> high threshold (hard to flash).
        double triggerThreshold = 0.85 - (sensitivity * 0.5);

        // Raw per-band trigger this frame: normalized energy over threshold, or a latched onset.
        boolean trigBass   = normBass   > triggerThreshold || pendingKickOnset;
        boolean trigMid    = normMid    > triggerThreshold || pendingSnareOnset;
        boolean trigTreble = normTreble > triggerThreshold || pendingHatOnset;

        pendingKickOnset = false;
        pendingSnareOnset = false;
        pendingHatOnset = false;

        // Minimum on-hold: a fresh trigger relights the zone for its hold window. While energy stays
        // up the zone keeps re-triggering and holds steady; brief dips can't strobe it dark. This is
        // what removes the fast flicker without touching detection.
        long now = android.os.SystemClock.elapsedRealtime();
        if (trigBass)   bassLitUntil   = now + HOLD_BASS_MS;
        if (trigMid)    midLitUntil    = now + HOLD_MID_MS;
        if (trigTreble) trebleLitUntil = now + HOLD_TREBLE_MS;

        boolean flashBass   = now < bassLitUntil;
        boolean flashMid    = now < midLitUntil;
        boolean flashTreble = now < trebleLitUntil;

        // Emit a frame only when the lit combination changes, so an idle glyph stops re-sending.
        int state = (flashBass ? 1 : 0) | (flashMid ? 2 : 0) | (flashTreble ? 4 : 0);
        if (state == lastDrumState) {
            return;
        }
        lastDrumState = state;

        glyphController.flashBeat(flashBass, flashMid, flashTreble);
    }

    private double clamp01(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
    }

    private double nextSensitivityPreset(double currentValue) {
        int closestIndex = 0;
        double closestDistance = Double.MAX_VALUE;
        for (int i = 0; i < SENSITIVITY_PRESETS.length; i++) {
            double distance = Math.abs(SENSITIVITY_PRESETS[i] - currentValue);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestIndex = i;
            }
        }
        return SENSITIVITY_PRESETS[(closestIndex + 1) % SENSITIVITY_PRESETS.length];
    }

    private String sensitivityPresetLabel(double currentValue) {
        int closestIndex = 0;
        double closestDistance = Double.MAX_VALUE;
        for (int i = 0; i < SENSITIVITY_PRESETS.length; i++) {
            double distance = Math.abs(SENSITIVITY_PRESETS[i] - currentValue);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestIndex = i;
            }
        }
        return SENSITIVITY_PRESET_LABELS[closestIndex];
    }
}

