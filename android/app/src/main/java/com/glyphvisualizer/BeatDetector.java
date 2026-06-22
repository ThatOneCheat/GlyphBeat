package com.glyphvisualizer;

import java.util.Arrays;

public class BeatDetector {

    // 86 frames keeps the adaptive median/MAD window at ~0.5s of wall-clock even though the
    // 256-sample hop now drives ~172 analysis frames/sec (was 43 frames @ ~86Hz). Holding the
    // time span constant stops the threshold from adapting twice as fast and rejecting dense rolls.
    private static final int SPECTRAL_FLUX_HISTORY = 86;
    private static final double DEFAULT_SENSITIVITY = 0.6;
    public static final int NUM_BANDS = 5;

    // Peak-picking margin. An onset is a local MAXIMUM of the flux (rose into it, falls out of it) —
    // scale-invariant, so it catches hits at any loudness where height-based thresholds saturate in
    // dense sections. To suppress ripple/hiss that are technically tiny local maxima, the peak must
    // also exceed the recent running level by this factor. 1.0 = pure peak; higher = stricter.
    private static final double PEAK_MARGIN = 1.15;

    private final float[] spectralFluxHistory = new float[SPECTRAL_FLUX_HISTORY];
    private final float[] totalEnergyHistory = new float[SPECTRAL_FLUX_HISTORY];
    private final float[][] multiBandFluxHistory = new float[NUM_BANDS][SPECTRAL_FLUX_HISTORY];
    private int fluxHistoryIndex = 0;
    private float[] previousMagnitude;
    private long lastBeatTime = 0;
    private long lastSubBeatTime = 0;
    // Per-band onset refractory so each drum runs on its own clock — a kick (low band) firing must
    // not gate a snare/hat (other bands) in the same window. Replaces the old shared-lastBeatTime gate.
    private final long[] lastBandOnsetTime = new long[NUM_BANDS];
    private final float[] previousBandEnergy = new float[3];
    private boolean hasPreviousBandEnergy = false;
    // Complex spectra of the previous two hops, kept per-bin so the phase-rotation prediction
    // can be done with complex arithmetic instead of per-bin atan2/sin/cos.
    private float[] previousReal;
    private float[] previousImag;
    private float[] previousPreviousReal;
    private float[] previousPreviousImag;

    // Pre-allocated reusable objects to eliminate per-frame GC pressure
    private final float[] bandComplexFluxBuf = new float[NUM_BANDS];
    private final int[] bandStartBinsBuf = new int[NUM_BANDS];
    private final int[] bandEndBinsBuf = new int[NUM_BANDS];
    // Peak-picking state: the previous two flux values per band (to spot a local max at t-1) plus a
    // slow running baseline (~7-frame EMA) used only for the anti-ripple margin.
    private final float[] bandFluxPrev1 = new float[NUM_BANDS];
    private final float[] bandFluxPrev2 = new float[NUM_BANDS];
    private final float[] bandFastBaseline = new float[NUM_BANDS];
    // Broadband counterparts for the main isOnset (which drives the on-screen visualizer).
    private float fluxPrev1 = 0;
    private float fluxPrev2 = 0;
    private float fastFluxBaseline = 0;
    private final RobustStats reusableStats = new RobustStats(0, 0);
    private final float[] statsWorkValues = new float[SPECTRAL_FLUX_HISTORY];
    private final float[] statsWorkAbsDeviations = new float[SPECTRAL_FLUX_HISTORY];

    // Tempo (BPM) estimation from inter-onset intervals.
    private static final int IOI_HISTORY = 16;
    private final double[] ioiHistory = new double[IOI_HISTORY];
    private final double[] ioiScratch = new double[IOI_HISTORY];
    private int ioiCount = 0;
    private int ioiIndex = 0;
    private long lastOnsetTimeForTempo = 0;
    private double smoothedBpm = 0;

    // Learned kick/snare/hat classifier. When enabled it replaces the band-energy-diff heuristic
    // for deciding *what* an onset was (the onset *detection* above is unchanged). Allocation-free
    // per call; see DrumClassifier. Defaults on — it's the whole point of the AI mode.
    private final DrumClassifier drumClassifier = new DrumClassifier();
    // Written from the service/binder thread (setUseMlClassifier), read on the DSP thread — volatile.
    private volatile boolean useMlClassifier = true;

    public void setUseMlClassifier(boolean enabled) {
        this.useMlClassifier = enabled;
    }



    public enum BeatType {
        NONE, KICK, SNARE, HIHAT, SUB_BASS
    }

    public static class BeatResult {
        public BeatType type = BeatType.NONE;
        public double confidence = 0;
        public double bassEnergy = 0;
        public double midEnergy = 0;
        public double trebleEnergy = 0;
        public boolean isOnset = false;

        // Parallel multi-band SuperFlux flags for the new Randomizer visualizer matrix
        public boolean[] multiBandOnsets = new boolean[NUM_BANDS];

        // The 'Champion' band index (0-4) for exclusive mapping mode
        public int strongestBandIndex = -1;
        
        // Spatial Audio Pan: -1 (Left), 0 (Center), +1 (Right)
        public double spatialPan = 0;

        // Diagnostics for calibration/threshold tuning
        public double spectralFlux = 0;
        public double adaptiveThreshold = 0;
        public double noiseFloor = 0;
        public double fluxVariance = 0;

        // Tempo estimate derived from inter-onset intervals (0 until enough beats are seen).
        public double bpm = 0;

        // ML classifier diagnostics: predicted class (0=kick,1=snare,2=hat; -1 if heuristic/none)
        // and its softmax confidence for the onset that fired this frame.
        public int mlClass = -1;
        public double mlConfidence = 0;
    }

    public BeatResult detect(
            float[] real,
            float[] imag,
            float[] magnitude,
            int sampleRate,
            double sensitivity,
            boolean computeAdvancedBands
    ) {
        BeatResult result = new BeatResult();
        result.type = BeatType.NONE;
        result.confidence = 0;
        result.bassEnergy = 0;
        result.midEnergy = 0;
        result.trebleEnergy = 0;
        result.isOnset = false;
        result.strongestBandIndex = -1;
        result.spatialPan = 0;
        result.spectralFlux = 0;
        result.adaptiveThreshold = 0;
        result.noiseFloor = 0;
        result.fluxVariance = 0;
        result.mlClass = -1;
        result.mlConfidence = 0;
        for (int b = 0; b < NUM_BANDS; b++) {
            result.multiBandOnsets[b] = false;
        }
        int numBins = magnitude.length;
        double sensitivityGateScale = Math.pow(Math.max(0.15, sensitivity), 0.7);

        // Tightened the bass boundary from 150Hz to 100Hz to aggressively filter out deep vocals and synths
        int bassEnd = frequencyToBin(100, sampleRate, numBins);
        int subBassEnd = frequencyToBin(60, sampleRate, numBins);
        int midEnd = frequencyToBin(2000, sampleRate, numBins);

        float bassEnergy = computeBandEnergy(magnitude, 0, bassEnd);
        float subBassEnergy = computeBandEnergy(magnitude, 0, subBassEnd);
        float midEnergy = computeBandEnergy(magnitude, bassEnd, midEnd);
        float trebleEnergy = computeBandEnergy(magnitude, midEnd, numBins);
        float totalEnergy = bassEnergy + midEnergy + trebleEnergy;

        totalEnergyHistory[fluxHistoryIndex] = totalEnergy;
        RobustStats energyStats = computeRobustStats(totalEnergyHistory);
        double dynamicEnergyGate = Math.max(
                0.08,
                (energyStats.median * 0.55 + energyStats.mad * 2.4) / sensitivityGateScale
        );
        if (totalEnergy < dynamicEnergyGate) {
            totalEnergy = 0;
            bassEnergy = 0;
            midEnergy = 0;
            trebleEnergy = 0;
        }

        result.bassEnergy = bassEnergy;
        result.midEnergy = midEnergy;
        result.trebleEnergy = trebleEnergy;

        if (previousReal == null) {
            previousReal = new float[numBins];
            previousImag = new float[numBins];
            previousPreviousReal = new float[numBins];
            previousPreviousImag = new float[numBins];
        }

        float complexSpectralDifference = 0;
        float[] bandComplexSpectralDifference = bandComplexFluxBuf;
        int[] bandStartBins = bandStartBinsBuf;
        int[] bandEndBins = bandEndBinsBuf;

        if (computeAdvancedBands) {
            for (int b = 0; b < NUM_BANDS; b++) bandComplexSpectralDifference[b] = 0;
            int[] bandLimits = {20, 60, 250, 2000, 6000, 16000};
            for (int b = 0; b < NUM_BANDS; b++) {
                bandStartBins[b] = frequencyToBin(bandLimits[b], sampleRate, numBins);
                bandEndBins[b] = Math.max(bandStartBins[b] + 1, frequencyToBin(bandLimits[b + 1], sampleRate, numBins));
            }
        }

        for (int i = 0; i < numBins; i++) {
            float curReal = real[i];
            float curImag = imag[i];

            if (previousMagnitude != null) {
                // We only care about positive energy changes (transients appearing, not disappearing)
                if (magnitude[i] > previousMagnitude[i]) {
                    // Predict the current bin assuming its phase keeps rotating at the same rate as
                    // the previous hop. Done entirely in the complex plane to avoid per-bin
                    // atan2/sin/cos: the angle-based prediction |X[t-1]|*exp(i*(2*phi[t-1]-phi[t-2]))
                    // equals  X[t-1] * unit( X[t-1] * conj(X[t-2]) )  — same magnitude (|X[t-1]|) and
                    // same phase, but with a single sqrt instead of three transcendental calls.
                    float pR = previousReal[i];
                    float pI = previousImag[i];
                    float ppR = previousPreviousReal[i];
                    float ppI = previousPreviousImag[i];

                    // c = X[t-1] * conj(X[t-2]); arg(c) is the inter-hop phase increment.
                    float cR = pR * ppR + pI * ppI;
                    float cI = pI * ppR - pR * ppI;
                    float cMag = (float) Math.sqrt(cR * cR + cI * cI);

                    float expectedReal;
                    float expectedImag;
                    if (cMag > 1e-9f) {
                        // Rotate X[t-1] by the unit phase-increment vector c/|c|.
                        float uR = cR / cMag;
                        float uI = cI / cMag;
                        expectedReal = pR * uR - pI * uI;
                        expectedImag = pR * uI + pI * uR;
                    } else {
                        // Degenerate (a silent previous hop): no phase to extrapolate, predict zero.
                        expectedReal = 0f;
                        expectedImag = 0f;
                    }

                    // Euclidean distance in the complex domain
                    float diffReal = curReal - expectedReal;
                    float diffImag = curImag - expectedImag;

                    float distance = (float) Math.sqrt(diffReal * diffReal + diffImag * diffImag);
                    float finalDistance = distance;

                    // Surgical Vocal Notch Filter (300Hz - 800Hz)
                    // If a frequency falls right in the core human singing voice, we crush its energy by 90%.
                    // This physically blinds the visualizer to vocals, but drums/synths have energy OUTSIDE this notch!
                    int freq = Math.min((int) Math.round((double) i * sampleRate / (2.0 * numBins)), 24000);
                    if (freq >= 300 && freq <= 800) {
                        finalDistance *= 0.10f; // Suppress vocal core
                    }

                    complexSpectralDifference += finalDistance; // Distance encapsulates both mag and phase deviation

                    if (computeAdvancedBands) {
                        for (int b = 0; b < NUM_BANDS; b++) {
                            if (i >= bandStartBins[b] && i < bandEndBins[b]) {
                                bandComplexSpectralDifference[b] += finalDistance;
                                break;
                            }
                        }
                    }
                }
            }

            // Shift the complex history: X[t-2] <- X[t-1] <- current. Stored for every bin so the
            // phase-rotation prediction above is available next hop without recomputing angles.
            previousPreviousReal[i] = previousReal[i];
            previousPreviousImag[i] = previousImag[i];
            previousReal[i] = curReal;
            previousImag[i] = curImag;
        }

        float spectralFlux = complexSpectralDifference;

        spectralFluxHistory[fluxHistoryIndex] = spectralFlux;
        if (computeAdvancedBands) {
            for (int b = 0; b < NUM_BANDS; b++) {
                multiBandFluxHistory[b][fluxHistoryIndex] = bandComplexSpectralDifference[b];
            }
        }
        
        fluxHistoryIndex = (fluxHistoryIndex + 1) % SPECTRAL_FLUX_HISTORY;

        RobustStats fluxStats = computeRobustStats(spectralFluxHistory);
        double medianFlux = fluxStats.median;
        double madFlux = fluxStats.mad;

        // MAD is inherently smaller than StdDev (~0.67x), so we scale the multiplier explicitly to ~3.0x for robust transient isolation
        double adaptiveThreshold = medianFlux + ((DEFAULT_SENSITIVITY * 3.0) * madFlux) / sensitivity;
        double spectralNoiseGate = Math.max(
                0.00012,
                (medianFlux * 1.35 + madFlux * 0.85) / sensitivityGateScale
        );
        result.spectralFlux = spectralFlux;
        result.adaptiveThreshold = adaptiveThreshold;
        result.noiseFloor = medianFlux;
        result.fluxVariance = madFlux;
        long now = now();
        // Lower interval to 40ms to capture 32nd notes in fast 150BPM trap/phonk (hi-hat rolls)
        long minBeatInterval = 40;
        long minSubBeatInterval = 20;

        // Process parallel MAD tracking for the 5 Multi-Band layers
        if (computeAdvancedBands) {
            double maxExcitement = -1;
            int champion = -1;

            for (int b = 0; b < NUM_BANDS; b++) {
                RobustStats bandStats = computeRobustStats(multiBandFluxHistory[b]);
                double bMedian = bandStats.median;
                double bMad = bandStats.mad;

                double bandMultiplier = 3.0; // was 3.5 — relaxed so dense kick/bass sections aren't skipped
                if (b == 2) bandMultiplier = 4.0; // Mid (snare): was 6.0 — relaxed; dense snare runs were being rejected
                if (b == 3 || b == 4) bandMultiplier = 3.5; // Highs (hats): was 4.5 — relaxed to catch fast hi-hats

                double bThresh = bMedian + ((DEFAULT_SENSITIVITY * bandMultiplier) * bMad) / sensitivity;
                double flux = bandComplexSpectralDifference[b];

                // A low absolute floor (plus per-band hiss floors for snare/hats) keeps room noise out;
                // the peak-shape test below does the real discrimination, so this stays LOW and does NOT
                // scale with the median — that's what let dense sections saturate before.
                double absFloor = 0.00012 / sensitivityGateScale;
                if (b == 3 || b == 4) absFloor = Math.max(absFloor, 0.00055 / sensitivityGateScale);
                if (b == 2) absFloor = Math.max(absFloor, 0.00040 / sensitivityGateScale);
                double peakFloor = Math.max(absFloor, bandFastBaseline[b] * PEAK_MARGIN);

                // Peak-pick: the previous frame is an onset if flux rose into it and now falls out of
                // it (local max), and it clears the floor. Scale-invariant — fires on every distinct
                // hit in a dense roll. One frame (~6ms) late. Shift history + update baseline after.
                float prev1 = bandFluxPrev1[b];
                float prev2 = bandFluxPrev2[b];
                boolean isPeak = prev1 > prev2 && prev1 >= flux && prev1 > peakFloor;
                bandFluxPrev2[b] = prev1;
                bandFluxPrev1[b] = (float) flux;
                bandFastBaseline[b] = (float) (bandFastBaseline[b] * 0.85 + flux * 0.15);

                // Gate each band on ITS OWN last onset, not the shared broadband beat — otherwise a
                // kick resets one timer and locks out a simultaneous snare/hat, killing the kit feel.
                result.multiBandOnsets[b] = isPeak && (now - lastBandOnsetTime[b]) > minSubBeatInterval;
                if (result.multiBandOnsets[b]) {
                    lastBandOnsetTime[b] = now;
                }

                if (isPeak) {
                    double excitement = prev1 / Math.max(0.0001, bThresh);
                    if (excitement > maxExcitement) {
                        maxExcitement = excitement;
                        champion = b;
                    }
                }
            }
            result.strongestBandIndex = champion;
        }

        // Same peak-picking on the broadband flux, so the on-screen visualizer (which keys off
        // isOnset) catches the dense beats the median threshold missed — matching the glyph. The
        // floor stays low and median-independent so it can't saturate in dense sections.
        double broadFloor = Math.max(0.00012 / sensitivityGateScale, fastFluxBaseline * PEAK_MARGIN);
        boolean isOnset = fluxPrev1 > fluxPrev2 && fluxPrev1 >= spectralFlux && fluxPrev1 > broadFloor
                       && (now - lastBeatTime) > minBeatInterval;
        fluxPrev2 = fluxPrev1;
        fluxPrev1 = spectralFlux;
        fastFluxBaseline = (float) (fastFluxBaseline * 0.85 + spectralFlux * 0.15);

        if (hasPreviousBandEnergy) {
            double bassDiff = bassEnergy - previousBandEnergy[0];
            double midDiff = midEnergy - previousBandEnergy[1];
            double trebleDiff = trebleEnergy - previousBandEnergy[2];

            // Scale the diffs to combat the natural 1/f frequency energy dropoff
            double scaledBassDiff = bassDiff;
            double scaledMidDiff = midDiff * 1.3; 
            double scaledTrebleDiff = trebleDiff * 2.5;

            if (isOnset) {
                // Decide the drum. ML path (default): the learned classifier reads the spectral
                // envelope at this onset frame. Heuristic fallback: scaled band-energy diffs.
                // Both feed the same low-end refinement below so SUB_BASS behaviour is preserved.
                boolean classifiedKick;
                if (useMlClassifier) {
                    int cls = drumClassifier.classify(magnitude, sampleRate);
                    result.mlClass = cls;
                    result.mlConfidence = drumClassifier.lastConfidence;
                    if (cls == DrumClassifier.SNARE) {
                        result.type = BeatType.SNARE;
                        classifiedKick = false;
                    } else if (cls == DrumClassifier.HIHAT) {
                        result.type = BeatType.HIHAT;
                        classifiedKick = false;
                    } else {
                        classifiedKick = true;
                    }
                } else if (scaledBassDiff > scaledMidDiff && scaledBassDiff > scaledTrebleDiff) {
                    classifiedKick = true;
                } else if (scaledMidDiff > scaledBassDiff && scaledMidDiff > scaledTrebleDiff) {
                    result.type = BeatType.SNARE;
                    classifiedKick = false;
                } else {
                    result.type = BeatType.HIHAT;
                    classifiedKick = false;
                }

                // Shared low-end refinement: a kick dominated by sub-bass energy and spaced from the
                // last sub hit is reported as SUB_BASS (it still drives the kick glyph zone).
                if (classifiedKick) {
                    if (subBassEnergy > bassEnergy * 0.6 && (now - lastSubBeatTime) > minSubBeatInterval) {
                        result.type = BeatType.SUB_BASS;
                        lastSubBeatTime = now;
                    } else {
                        result.type = BeatType.KICK;
                    }
                }

                result.confidence = Math.min(1.0, spectralFlux / (adaptiveThreshold * 2));
                result.isOnset = true;
                lastBeatTime = now;
            }
        }

        // --- Tempo (BPM) estimation from inter-onset intervals ---
        if (result.isOnset) {
            if (lastOnsetTimeForTempo > 0) {
                double ioi = now - lastOnsetTimeForTempo;
                // Keep only musically plausible gaps between onsets.
                if (ioi >= 200 && ioi <= 1500) {
                    ioiHistory[ioiIndex] = ioi;
                    ioiIndex = (ioiIndex + 1) % IOI_HISTORY;
                    if (ioiCount < IOI_HISTORY) ioiCount++;
                    recomputeTempo();
                }
            }
            lastOnsetTimeForTempo = now;
        }
        result.bpm = smoothedBpm;

        if (previousMagnitude == null) {
            previousMagnitude = new float[numBins];
        }
        System.arraycopy(magnitude, 0, previousMagnitude, 0, numBins);

        previousBandEnergy[0] = bassEnergy;
        previousBandEnergy[1] = midEnergy;
        previousBandEnergy[2] = trebleEnergy;
        hasPreviousBandEnergy = true;

        return result;
    }

    private int frequencyToBin(int frequency, int sampleRate, int numBins) {
        return Math.min((int) Math.round(frequency * 2.0 * numBins / sampleRate), numBins - 1);
    }

    private float computeBandEnergy(float[] magnitude, int start, int end) {
        float energy = 0;
        end = Math.min(end, magnitude.length);
        for (int i = start; i < end; i++) {
            // Magnitude array is ALREADY squared inside FFT.java (Math.sqrt removed).
            energy += magnitude[i];
        }
        return energy / Math.max(1, end - start);
    }

    // Uses exact median + MAD with pre-allocated working buffers to keep behavior stable.
    private RobustStats computeRobustStats(float[] values) {
        int count = 0;
        for (float val : values) {
            if (val > 0) {
                statsWorkValues[count++] = val;
            }
        }

        if (count == 0) {
            reusableStats.median = 0;
            reusableStats.mad = 0;
            return reusableStats;
        }

        Arrays.sort(statsWorkValues, 0, count);
        float median;
        if ((count & 1) == 0) {
            median = (statsWorkValues[(count / 2) - 1] + statsWorkValues[count / 2]) * 0.5f;
        } else {
            median = statsWorkValues[count / 2];
        }

        for (int i = 0; i < count; i++) {
            statsWorkAbsDeviations[i] = Math.abs(statsWorkValues[i] - median);
        }
        Arrays.sort(statsWorkAbsDeviations, 0, count);
        float mad;
        if ((count & 1) == 0) {
            mad = (statsWorkAbsDeviations[(count / 2) - 1] + statsWorkAbsDeviations[count / 2]) * 0.5f;
        } else {
            mad = statsWorkAbsDeviations[count / 2];
        }

        reusableStats.median = median;
        reusableStats.mad = mad;
        return reusableStats;
    }

    private static class RobustStats {
        float median;
        float mad;

        RobustStats(float median, float mad) {
            this.median = median;
            this.mad = mad;
        }
    }

    // Re-estimates tempo from the recent inter-onset intervals. The median IOI is folded
    // into a canonical band so 8th/16th-note onset grids resolve to the underlying beat,
    // then octave-snapped to the running estimate to avoid half/double-time flapping.
    private void recomputeTempo() {
        if (ioiCount < 4) {
            return;
        }
        System.arraycopy(ioiHistory, 0, ioiScratch, 0, ioiCount);
        Arrays.sort(ioiScratch, 0, ioiCount);
        double medianIoi = ((ioiCount & 1) == 0)
                ? (ioiScratch[(ioiCount / 2) - 1] + ioiScratch[ioiCount / 2]) * 0.5
                : ioiScratch[ioiCount / 2];
        if (medianIoi <= 0) {
            return;
        }

        double rawBpm = 60000.0 / medianIoi;
        while (rawBpm < 70) rawBpm *= 2;
        while (rawBpm >= 180) rawBpm /= 2;

        // Snap to the octave closest to the running estimate for stability.
        if (smoothedBpm > 0) {
            double best = rawBpm;
            double bestDist = Math.abs(rawBpm - smoothedBpm);
            double[] octaves = {rawBpm * 0.5, rawBpm * 2.0};
            for (double candidate : octaves) {
                double dist = Math.abs(candidate - smoothedBpm);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidate;
                }
            }
            rawBpm = best;
        }

        smoothedBpm = (smoothedBpm <= 0) ? rawBpm : smoothedBpm * 0.85 + rawBpm * 0.15;
    }

    // Time source for the onset refractory gate. Overridable so tests can drive
    // beat timing deterministically; production always uses the wall clock.
    protected long now() {
        return System.currentTimeMillis();
    }
}
