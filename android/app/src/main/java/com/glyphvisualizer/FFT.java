package com.glyphvisualizer;

public class FFT {

    private final int n;
    private final int[] bitReversed;
    private final float[] cosTable;
    private final float[] sinTable;

    public FFT(int n) {
        if ((n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a power of 2");
        }
        this.n = n;
        this.bitReversed = new int[n];
        this.cosTable = new float[n];
        this.sinTable = new float[n];
        computeTables();
    }

    private void computeTables() {
        int log2n = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            bitReversed[i] = Integer.reverse(i) >>> (32 - log2n);
        }
        for (int i = 0; i < n; i++) {
            double angle = -2.0 * Math.PI * i / n;
            cosTable[i] = (float) Math.cos(angle);
            sinTable[i] = (float) Math.sin(angle);
        }
    }

    public void forward(float[] real, float[] imag) {
        for (int i = 0; i < n; i++) {
            int j = bitReversed[i];
            if (i < j) {
                float temp = real[i];
                real[i] = real[j];
                real[j] = temp;
                temp = imag[i];
                imag[i] = imag[j];
                imag[j] = temp;
            }
        }

        int m = 2;
        while (m <= n) {
            int halfM = m / 2;
            int step = n / m;
            for (int k = 0; k < n; k += m) {
                for (int jIdx = 0; jIdx < halfM; jIdx++) {
                    int twiddleIdx = jIdx * step;
                    float tReal = cosTable[twiddleIdx] * real[k + jIdx + halfM]
                                 - sinTable[twiddleIdx] * imag[k + jIdx + halfM];
                    float tImag = cosTable[twiddleIdx] * imag[k + jIdx + halfM]
                                 + sinTable[twiddleIdx] * real[k + jIdx + halfM];
                    real[k + jIdx + halfM] = real[k + jIdx] - tReal;
                    imag[k + jIdx + halfM] = imag[k + jIdx] - tImag;
                    real[k + jIdx] += tReal;
                    imag[k + jIdx] += tImag;
                }
            }
            m <<= 1;
        }
    }

    public void magnitude(float[] real, float[] imag, float[] magnitude) {
        int halfN = n / 2;
        for (int i = 0; i < halfN; i++) {
            // Compute squared magnitude directly. Drops expensive Math.sqrt
            magnitude[i] = (real[i] * real[i]) + (imag[i] * imag[i]);
        }
    }
}
