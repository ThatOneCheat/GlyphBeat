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
    // AI drum classifier toggle. When on (default), onsets are typed by the learned MLP
    // (DrumClassifier); when off, the legacy band-energy-diff heuristic is used. Applied to the
    // BeatDetector on init and whenever the setting changes.
    private volatile boolean useMlClassifier = true;
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
    // Beat-locked flash renderer state. A detected + AI-classified drum hit lights exactly ONE zone
    // for a short flash, then the glyph goes dark until the next hit (a new hit shifts the light
    // immediately). This replaced the old raw-energy peak-follower at the user's explicit request.
    private int litZone = -1;            // -1 none, 0 kick->slash, 1 snare->dot, 2 hat->ring
    private long flashUntil = 0;         // elapsedRealtime() ms; the lit zone goes dark once now >= this
    private static final long FLASH_DURATION_MS = 45; // how long one hit stays lit before going dark


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
            if (intent.hasExtra("useMlClassifier")) {
                useMlClassifier = intent.getBooleanExtra("useMlClassifier", true);
                if (beatDetector != null) {
                    beatDetector.setUseMlClassifier(useMlClassifier);
                }
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
        beatDetector.setUseMlClassifier(useMlClassifier);
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
        litZone = -1;
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
        litZone = -1;
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
        litZone = -1;
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

        // Drive the on-screen mirror (glyph_zones) from the SAME rate-gated, AI-classified onset that
        // lights the rear glyph, so the dashboard can't show a different drum than the back of the
        // phone. Map the classified beat type -> kick(1)/snare(2)/hat(3); SUB_BASS counts as kick.
        int uiStruck = 0;
        boolean isNewOnset = false;
        if (beatResult.isOnset) {
            BeatDetector.BeatType t = beatResult.type;
            if (t == BeatDetector.BeatType.KICK || t == BeatDetector.BeatType.SUB_BASS) {
                uiStruck = 1;
            } else if (t == BeatDetector.BeatType.SNARE) {
                uiStruck = 2;
            } else if (t == BeatDetector.BeatType.HIHAT) {
                uiStruck = 3;
            }
            isNewOnset = uiStruck != 0;
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

        updateGlyphs(beatResult);

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
        flutterEventMap.put("mlConfidence", result.mlConfidence);
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

        // "BEAT" (beat-locked flash): driven by detected, AI-classified drum HITS, not by raw band
        // energy. Each hit pops exactly one zone hard-on for FLASH_DURATION_MS, then the glyph goes
        // dark until the next hit; a fresh hit shifts the light to its zone instantly, so the lit bar
        // hops in rhythm. Mapping matches glyph_zones (the on-screen mirror) and flashBeat:
        // kick -> slash (A), snare -> dot (B), hi-hat -> ring (C).
        long now = android.os.SystemClock.elapsedRealtime();

        // Which drum fired this frame, from the latched per-type onsets (set upstream from the
        // rate-gated, classified onset). Normally at most one is pending; if two land in the same
        // frame the louder band wins the tie. No raw energy lights a zone on its own anymore.
        int onsetZone = -1;
        if (pendingKickOnset || pendingSnareOnset || pendingHatOnset) {
            double best = -1.0;
            if (pendingKickOnset  && result.bassEnergy   > best) { best = result.bassEnergy;   onsetZone = 0; }
            if (pendingSnareOnset && result.midEnergy    > best) { best = result.midEnergy;    onsetZone = 1; }
            if (pendingHatOnset   && result.trebleEnergy > best) { best = result.trebleEnergy; onsetZone = 2; }
        }
        pendingKickOnset = false;
        pendingSnareOnset = false;
        pendingHatOnset = false;

        if (onsetZone >= 0) {
            // Fresh hit: shift the light to this zone and (re)start its flash window.
            litZone = onsetZone;
            flashUntil = now + FLASH_DURATION_MS;
        } else if (litZone >= 0 && now >= flashUntil) {
            // Flash elapsed with no new hit: go dark and wait for the next beat.
            litZone = -1;
        }

        // Emit a frame only when the lit zone changes, so a held flash / idle glyph stops re-sending.
        int state = litZone + 1; // -1 dark -> 0; kick/snare/hat -> 1/2/3
        if (state == lastDrumState) {
            return;
        }
        lastDrumState = state;

        glyphController.flashBeat(litZone == 0, litZone == 1, litZone == 2);
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

