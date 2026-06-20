package com.glyphvisualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Sanity tests for the radix-2 FFT. These are pure JVM unit tests (no Android
 * dependencies) and run via {@code ./gradlew :app:testDebugUnitTest}.
 */
public class FFTTest {

    private static final int N = 1024;

    private int argMax(float[] mag) {
        int idx = 0;
        for (int i = 1; i < mag.length; i++) {
            if (mag[i] > mag[idx]) idx = i;
        }
        return idx;
    }

    @Test
    public void cosinePeaksAtItsOwnBin() {
        FFT fft = new FFT(N);
        float[] real = new float[N];
        float[] imag = new float[N];
        float[] mag = new float[N / 2];

        int k = 64;
        for (int i = 0; i < N; i++) {
            real[i] = (float) Math.cos(2 * Math.PI * k * i / N);
        }

        fft.forward(real, imag);
        fft.magnitude(real, imag, mag);

        assertEquals("a pure cosine should peak at its own bin", k, argMax(mag));
    }

    @Test
    public void constantSignalIsPureDc() {
        FFT fft = new FFT(N);
        float[] real = new float[N];
        float[] imag = new float[N];
        float[] mag = new float[N / 2];

        for (int i = 0; i < N; i++) {
            real[i] = 1.0f;
        }

        fft.forward(real, imag);
        fft.magnitude(real, imag, mag);

        assertEquals("a constant signal carries all its energy at DC", 0, argMax(mag));
        assertTrue("DC bin should hold energy", mag[0] > 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPowerOfTwoSize() {
        new FFT(1000);
    }
}
