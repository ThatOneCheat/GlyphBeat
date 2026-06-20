package com.glyphvisualizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

/**
 * Behavioural sanity tests for the onset detector: silence must stay dark, and a
 * sudden broadband transient rising out of a low noise floor must register as an
 * onset. Pure JVM tests; run via {@code ./gradlew :app:testDebugUnitTest}.
 */
public class BeatDetectorTest {

    private static final int N = 1024;
    private static final int SAMPLE_RATE = 44100;

    private final FFT fft = new FFT(N);
    private final float[] real = new float[N];
    private final float[] imag = new float[N];
    private final float[] mag = new float[N / 2];

    /** A detector with a driveable clock so the onset refractory gate is deterministic. */
    private static final class FakeClockDetector extends BeatDetector {
        private long millis = 1_000_000L;

        void advance(long ms) {
            millis += ms;
        }

        @Override
        protected long now() {
            return millis;
        }
    }

    /** Runs one time-domain frame through the FFT and the detector, mirroring the service pipeline. */
    private BeatDetector.BeatResult analyze(BeatDetector detector, float[] signal) {
        System.arraycopy(signal, 0, real, 0, N);
        Arrays.fill(imag, 0f);
        fft.forward(real, imag);
        fft.magnitude(real, imag, mag);
        return detector.detect(real, imag, mag, SAMPLE_RATE, 1.0, true);
    }

    @Test
    public void silenceNeverOnsets() {
        BeatDetector detector = new BeatDetector();
        float[] silence = new float[N];

        for (int frame = 0; frame < 50; frame++) {
            BeatDetector.BeatResult r = analyze(detector, silence);
            assertFalse("silence must not trigger an onset", r.isOnset);
            assertTrue("silence must not be classified as a beat", r.type == BeatDetector.BeatType.NONE);
        }
    }

    @Test
    public void suddenTransientOnsets() {
        FakeClockDetector detector = new FakeClockDetector();

        // Establish a low noise floor (and fill the flux history) with quiet, ever-changing noise,
        // advancing the clock a realistic hop each frame so the refractory gate behaves normally.
        float[] bed = new float[N];
        Random noise = new Random(7);
        for (int frame = 0; frame < 48; frame++) {
            for (int j = 0; j < N; j++) {
                bed[j] = (float) (noise.nextGaussian() * 0.03);
            }
            detector.advance(50);
            analyze(detector, bed);
        }

        // A loud broadband burst rising well above that floor must register as an onset.
        float[] burst = new float[N];
        Random rng = new Random(42);
        for (int j = 0; j < N; j++) {
            burst[j] = (float) (rng.nextDouble() * 2.0 - 1.0);
        }
        detector.advance(50);
        boolean onsetAtBurst = analyze(detector, burst).isOnset;

        // Peak-picking confirms a transient one frame later (it has to see the flux fall back down),
        // so feed a quiet decay frame and accept the onset on either frame.
        float[] decay = new float[N];
        Random decayNoise = new Random(11);
        for (int j = 0; j < N; j++) {
            decay[j] = (float) (decayNoise.nextGaussian() * 0.03);
        }
        detector.advance(50);
        boolean onsetAfterBurst = analyze(detector, decay).isOnset;

        assertTrue("a sudden broadband transient should register as an onset within one frame",
                onsetAtBurst || onsetAfterBurst);
    }

    @Test
    public void estimatesTempoFromSteadyBeat() {
        final int targetBpm = 120;
        final double beatMs = 60000.0 / targetBpm; // 500ms
        final int framesPerBeat = 10;
        final long frameMs = (long) (beatMs / framesPerBeat);

        FakeClockDetector detector = new FakeClockDetector();
        Random noise = new Random(7);
        Random burstRng = new Random(99);
        float[] bed = new float[N];
        float[] burst = new float[N];

        double lastBpm = 0;
        for (int beat = 0; beat < 16; beat++) {
            for (int f = 0; f < framesPerBeat; f++) {
                detector.advance(frameMs);
                float[] frame;
                if (f == 0) {
                    for (int j = 0; j < N; j++) {
                        burst[j] = (float) (burstRng.nextDouble() * 2.0 - 1.0);
                    }
                    frame = burst;
                } else {
                    for (int j = 0; j < N; j++) {
                        bed[j] = (float) (noise.nextGaussian() * 0.03);
                    }
                    frame = bed;
                }
                lastBpm = analyze(detector, frame).bpm;
            }
        }

        assertTrue("BPM estimate should converge near the target tempo (got " + lastBpm + ")",
                lastBpm >= 108 && lastBpm <= 132);
    }
}
