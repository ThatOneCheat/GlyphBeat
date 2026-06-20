package com.glyphvisualizer;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Microphone capture with graceful negotiation: it tries a couple of sample rates and a
 * few audio sources (preferring the unprocessed signal) until one initializes, reports the
 * sample rate it actually got, and surfaces a hard read failure via {@link CaptureErrorListener}
 * so the service can recover.
 */
public class AudioCapture {

    private static final String TAG = "AudioCapture";
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static final int[] CANDIDATE_SAMPLE_RATES = {44100, 48000};
    private static final int[] CANDIDATE_SOURCES = {
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
    };

    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private volatile int actualSampleRate = 44100;
    private Thread captureThread;

    private final AudioDataListener listener;
    private final CaptureErrorListener errorListener;

    public interface AudioDataListener {
        void onAudioData(short[] buffer, int bytesRead);
    }

    public interface CaptureErrorListener {
        void onError(String reason);
    }

    public AudioCapture(AudioDataListener listener, CaptureErrorListener errorListener) {
        this.listener = listener;
        this.errorListener = errorListener;
    }

    public int getSampleRate() {
        return actualSampleRate;
    }

    public int getFFTSize() {
        return 1024;
    }

    public boolean start() {
        if (isRecording) {
            return true;
        }

        // Read one hop (half the FFT window) per pull, not a whole window: fresh audio reaches
        // the analyzer about every ~12ms instead of ~23ms. The accumulator still assembles full
        // 1024-sample windows at 50% overlap, so the spectral analysis (and every mode that
        // consumes it) is unchanged — only the latency drops.
        int readChunkBytes = getFFTSize() * 2; // one hop: 512 stereo frames * 2 bytes/sample

        for (int rate : CANDIDATE_SAMPLE_RATES) {
            int minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBuf <= 0) {
                continue; // rate/config unsupported on this device
            }
            // Keep the input ring buffer near the device minimum (a couple of read chunks as a
            // floor) so audio doesn't pool and we stay on the low-latency capture path.
            int bufBytes = Math.max(minBuf, readChunkBytes * 2);

            for (int source : CANDIDATE_SOURCES) {
                AudioRecord candidate = null;
                try {
                    candidate = new AudioRecord(source, rate, CHANNEL_CONFIG, AUDIO_FORMAT, bufBytes);
                    if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                        candidate.release();
                        continue;
                    }
                    candidate.startRecording();
                    audioRecord = candidate;
                    actualSampleRate = rate;
                    isRecording = true;
                    startCaptureThread();
                    Log.d(TAG, "Mic capture started at " + rate + "Hz, source " + source);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "Mic init failed at " + rate + "Hz source " + source + ": " + e.getMessage());
                    if (candidate != null) {
                        try {
                            candidate.release();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        Log.e(TAG, "No mic configuration could be initialized");
        return false;
    }

    private void startCaptureThread() {
        captureThread = new Thread(() -> {
            // Pull one hop per read (half a window); processAudioData reassembles the
            // overlapped 1024-sample windows from the stream.
            short[] buffer = new short[getFFTSize()];
            while (isRecording) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    if (listener != null) {
                        listener.onAudioData(buffer, bytesRead);
                    }
                } else if (bytesRead == 0 || bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    // Transient: no data yet, back off briefly.
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        isRecording = false;
                        break;
                    }
                } else {
                    // ERROR / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT: the session is gone.
                    Log.e(TAG, "Fatal mic read error: " + bytesRead);
                    isRecording = false;
                    if (errorListener != null) {
                        errorListener.onError("mic read error " + bytesRead);
                    }
                    break;
                }
            }
        }, "AudioCaptureThread");
        captureThread.start();
    }

    public void stop() {
        isRecording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {
            }
        }
        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                captureThread.interrupt();
            }
            captureThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
    }
}
