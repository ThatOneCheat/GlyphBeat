package com.glyphvisualizer;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Latency guard for the AI drum classifier. The whole point of running the model on the onset
 * frame only (no temporal buffering, no per-frame inference) is that it adds effectively zero
 * latency to the visualizer. This times a full {@link DrumClassifier#classify} call -- feature
 * extraction over all 512 power bins + the {@code 29->96->48->3} forward pass -- and asserts it
 * stays microsecond-scale.
 *
 * <p>Note: this runs on the desktop JVM, not the phone's ART, so the absolute number is indicative
 * rather than exact -- but it reliably catches an algorithmic blow-up (e.g. accidental per-onset
 * allocation or an O(n^2) feature loop). It typically measures well under 5 microseconds/call.
 */
public class DrumClassifierLatencyTest {

    @Test
    public void classifyIsMicrosecondScale() {
        final int numBins = DrumClassifierModel.NUM_BINS;
        final int sampleRate = 48000;

        // A representative snare-ish power spectrum (broadband with a low body bump) so the
        // classifier does real work rather than short-circuiting on an empty spectrum.
        float[] magnitude = new float[numBins];
        java.util.Random rng = new java.util.Random(7);
        for (int i = 0; i < numBins; i++) {
            double body = Math.exp(-Math.pow((i - 4) / 6.0, 2)) * 5.0;   // ~200Hz body
            double noise = 0.2 + 0.8 * rng.nextDouble();                 // broadband
            magnitude[i] = (float) (body + noise);
        }

        DrumClassifier classifier = new DrumClassifier();

        // Warm up the JIT.
        long sink = 0;
        for (int i = 0; i < 50_000; i++) {
            sink += classifier.classify(magnitude, sampleRate);
        }

        final int iters = 300_000;
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            sink += classifier.classify(magnitude, sampleRate);
        }
        long elapsed = System.nanoTime() - start;

        double nsPerCall = (double) elapsed / iters;
        double usPerCall = nsPerCall / 1000.0;
        System.out.printf("DrumClassifier.classify: %.3f us/call (%.0f ns), sink=%d%n",
                usPerCall, nsPerCall, sink);

        // Generous bound: real measurement is ~1us. Anything above 100us means something is
        // structurally wrong (allocation storm, blown-up loop), not just a slow CI box.
        assertTrue("classify() too slow: " + usPerCall + " us/call", usPerCall < 100.0);
    }
}
