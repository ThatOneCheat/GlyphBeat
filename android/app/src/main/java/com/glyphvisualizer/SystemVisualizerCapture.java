package com.glyphvisualizer;

import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * System-audio capture via the legacy global {@link Visualizer} on the output-mix session (0).
 *
 * <p>Unlike MediaProjection ({@code AudioPlaybackCapture}), this path is NOT subject to an app's
 * {@code ALLOW_CAPTURE_BY_NONE} opt-out on this hardware, so it still sees Spotify — verified on the
 * Nothing Phone (3a) Pro / Android 16. The trade-off is signal quality: the waveform is <b>8-bit</b>.
 *
 * <p><b>Capture rate:</b> we drive this in <b>polling mode</b> from our own thread, calling
 * {@link Visualizer#getWaveForm(byte[])} on a fixed cadence — NOT the {@code setDataCaptureListener}
 * callback. {@link Visualizer#getMaxCaptureRate()} caps only the <i>callback</i> at ~20 Hz; the
 * polling getters have no such cap and return the most recently captured buffer on each call. A
 * 1024-sample window covers {@code 1024 / sampleRate} s (~23 ms at 44.1 kHz), so polling every
 * {@link #POLL_INTERVAL_MS} ms hands the detector a fresh, essentially back-to-back window at
 * <b>~43 Hz</b> — roughly double the 20 Hz callback. Each snapshot is still fed to the detector as a
 * single analysis frame (no overlapped reassembly). Requires {@code RECORD_AUDIO} +
 * {@code MODIFY_AUDIO_SETTINGS}.
 *
 * <p>Silence is delivered as-is (it just means nothing is playing yet) — the source is chosen
 * explicitly via the in-app MIC/SYSTEM toggle, so this path never auto-switches to the mic.
 */
public class SystemVisualizerCapture {

    private static final String TAG = "SystemVisualizerCapture";

    // Target one 1024-sample window per poll to match the service's 1024-point FFT.
    private static final int TARGET_CAPTURE_SIZE = 1024;

    // Poll interval for getWaveForm(). A 1024-sample window refills every ~21 ms at 48 kHz
    // (~23 ms at 44.1 kHz), so ~21 ms is the practical floor — it grabs essentially every fresh
    // window (~48 Hz, the realistic max-detection rate here). Polling faster only re-reads the same
    // snapshot (the Visualizer buffer turns over at the capture cadence, so there's no true overlap
    // to gain). Raise it toward 50 ms to fall back near the old 20 Hz callback cadence.
    private static final long POLL_INTERVAL_MS = 21;

    private final WaveformListener listener;
    private final CaptureErrorListener errorListener;

    private Visualizer visualizer;
    private Thread pollThread;
    private volatile boolean isRecording = false;
    private volatile int actualSampleRate = 44100;

    public interface WaveformListener {
        /** @param waveform unsigned 8-bit PCM snapshot; @param samplingRateHz output mix rate in Hz */
        void onWaveform(byte[] waveform, int samplingRateHz);
    }

    public interface CaptureErrorListener {
        void onError(String reason);
    }

    public SystemVisualizerCapture(WaveformListener listener, CaptureErrorListener errorListener) {
        this.listener = listener;
        this.errorListener = errorListener;
    }

    public boolean start() {
        if (isRecording) {
            return true;
        }
        try {
            visualizer = new Visualizer(0); // 0 = AUDIO_SESSION_OUTPUT_MIX (global mix)
            if (visualizer.getEnabled()) {
                visualizer.setEnabled(false); // capture size can only be set while disabled
            }

            int[] range = Visualizer.getCaptureSizeRange();
            final int captureSize = Math.max(range[0], Math.min(TARGET_CAPTURE_SIZE, range[1]));
            visualizer.setCaptureSize(captureSize);

            // Polling mode gives no per-call sampling rate, so read the mix rate once up front.
            int rateMilliHz = visualizer.getSamplingRate(); // mHz
            actualSampleRate = rateMilliHz > 0 ? rateMilliHz / 1000 : 44100;

            visualizer.setEnabled(true);
            isRecording = true;

            pollThread = new Thread(() -> pollLoop(captureSize), "VisualizerPoll");
            pollThread.start();

            Log.d(TAG, "Visualizer polling started: captureSize=" + captureSize
                    + " sr=" + actualSampleRate + "Hz interval=" + POLL_INTERVAL_MS + "ms (~"
                    + (1000 / POLL_INTERVAL_MS) + "Hz)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Visualizer(0) init failed: " + e.getMessage());
            cleanup();
            if (errorListener != null) {
                errorListener.onError("Visualizer init failed: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Pulls fresh waveform snapshots on a fixed cadence. The consumer
     * ({@code VisualizerService.processVisualizerFrame}) copies the bytes synchronously into its own
     * pooled frame before returning, so reusing a single buffer here is safe and allocation-free.
     */
    private void pollLoop(int captureSize) {
        byte[] waveform = new byte[captureSize];
        while (isRecording) {
            long startNs = System.nanoTime();
            Visualizer v = visualizer;
            if (v == null) {
                break;
            }
            try {
                int status = v.getWaveForm(waveform);
                if (status == Visualizer.SUCCESS && isRecording && listener != null) {
                    listener.onWaveform(waveform, actualSampleRate);
                }
            } catch (IllegalStateException e) {
                // Visualizer was disabled/released mid-poll during teardown — stop quietly.
                break;
            } catch (Exception e) {
                Log.e(TAG, "getWaveForm failed: " + e.getMessage());
                break;
            }

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            long sleepMs = POLL_INTERVAL_MS - elapsedMs;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    break; // stop() interrupted us
                }
            }
        }
    }

    public void stop() {
        isRecording = false;
        Thread t = pollThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            pollThread = null;
        }
        cleanup();
        Log.d(TAG, "Visualizer capture stopped");
    }

    private void cleanup() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Exception ignored) {
                // best-effort teardown
            }
            visualizer = null;
        }
    }
}
