#!/usr/bin/env python3
"""
Train the on-device drum-onset classifier (kick / snare / hi-hat) for GlyphBeat -- "max" edition.

WHY THIS EXISTS
---------------
The native engine already does excellent onset *detection* (complex spectral flux + peak-picking in
BeatDetector.java): it knows precisely *when* a transient hits. The weak link was *classification* --
kick vs snare vs hi-hat -- which used to be a crude "compare scaled band-energy diffs" heuristic.
This trains a small neural net (an MLP) to classify the drum from the *shape* of the spectrum at the
onset frame, which is what the ear actually keys on.

This is the "trained to its limit / near-zero latency" version. The honest limiting factor is data
*realism*, not training duration -- so the heavy lifting here is:
  * many drum archetypes per class (808 / acoustic / punchy kicks; acoustic / clap / electronic
    snares; closed / open / metallic hats),
  * aggressive, realistic augmentation (random EQ tilt, saturation/clipping, codec-style bandlimit,
    a randomized multi-instrument "music bed", wide SNR incl. nearly-buried hits, both sample rates),
  * a richer 29-dim, loudness-invariant feature vector,
  * a regularized 2-hidden-layer MLP (weight decay + dropout + label smoothing + cosine LR + early
    stop),
  * and -- crucially -- a SEPARATE, *harder* held-out test set (different seed, harsher aug) so the
    reported accuracy reflects generalization, not memorization of the synthesizer.

Latency stays negligible because the net is tiny (a few thousand MACs) and runs *only* on the onset
frame -- no temporal buffering, no per-frame inference. DrumClassifierLatencyTest guards that.

It stays self-contained: plain numpy, no TensorFlow / sklearn. It EMITS two Java files:
  * DrumClassifierModel.java -- learned weights, normalization, dims, shared band edges.
  * DrumClassifierTest.java   -- golden vectors locking Python<->Java numeric parity.
The Java side (DrumClassifier.java) re-implements the exact same feature extraction + forward pass.
To retrain on REAL drum recordings, replace the synth_* functions with a loader producing the same
(frame, label) pairs -- everything downstream is unchanged.

Run:  python tool/train_drum_classifier.py
"""

import math
import os
import numpy as np

# ----------------------------------------------------------------------------------------
# Constants -- MUST match the native engine (VisualizerService.java / FFT.java) and the Java
# feature extractor (DrumClassifier.java). Don't change one side without the other.
# ----------------------------------------------------------------------------------------
FFT_SIZE = 1024
NUM_BINS = FFT_SIZE // 2          # FFT.magnitude() emits n/2 = 512 squared-magnitude (power) bins
SAMPLE_RATES = [44100, 48000]

NUM_BANDS = 24                    # log-spaced band energies (fine spectral envelope)
# 25 log-spaced edges 30Hz..~Nyquist. Generated here and emitted to Java so both sides share them.
BAND_EDGES_HZ = sorted(set(int(round(e)) for e in np.geomspace(30, 22050, NUM_BANDS + 1)))
NUM_BANDS = len(BAND_EDGES_HZ) - 1
NUM_EXTRA = 5                     # centroid, spread, rolloff, flatness, low/high log-ratio
FEATURE_DIM = NUM_BANDS + NUM_EXTRA
HIDDEN1 = 96
HIDDEN2 = 48
NUM_CLASSES = 3
CLASS_NAMES = ["KICK", "SNARE", "HIHAT"]
LOG_EPS = 1e-10

N_PER_CLASS = 16000              # train + val
N_TEST_PER_CLASS = 3000          # separate, harder held-out test
VAL_FRAC = 0.12
EPOCHS = 500
PATIENCE = 55
BATCH = 256
LR_MAX = 3e-3
LR_MIN = 1e-4
WEIGHT_DECAY = 1e-4
DROPOUT = 0.10
LABEL_SMOOTH = 0.03
SEED = 1234
TEST_SEED = 99999

_HANN = 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(FFT_SIZE) / (FFT_SIZE - 1)))


# ----------------------------------------------------------------------------------------
# Feature extraction -- exact mirror of DrumClassifier.extractFeatures() in Java.
# 24 loudness-invariant log-band features (mean-log subtracted) + 5 spectral descriptors.
# ----------------------------------------------------------------------------------------
def freq_to_bin(freq_hz, sample_rate):
    b = int(round(freq_hz * 2.0 * NUM_BINS / sample_rate))
    return min(max(b, 0), NUM_BINS - 1)


def power_spectrum(frame):
    windowed = frame * _HANN
    spec = np.fft.rfft(windowed, n=FFT_SIZE)[:NUM_BINS]
    return (spec.real ** 2 + spec.imag ** 2).astype(np.float64)


def extract_features(power, sample_rate):
    feat = np.empty(FEATURE_DIM, dtype=np.float64)

    # --- 1..NUM_BANDS: log-band energies, mean-log subtracted (loudness invariant) ---
    logp = np.empty(NUM_BANDS, dtype=np.float64)
    band_power = np.empty(NUM_BANDS, dtype=np.float64)
    for k in range(NUM_BANDS):
        lo = freq_to_bin(BAND_EDGES_HZ[k], sample_rate)
        hi = freq_to_bin(BAND_EDGES_HZ[k + 1], sample_rate)
        if hi <= lo:
            hi = lo + 1
        hi = min(hi, NUM_BINS)
        bp = float(np.sum(power[lo:hi]))
        band_power[k] = bp
        logp[k] = math.log(bp + LOG_EPS)
    mean_log = float(np.mean(logp))
    feat[:NUM_BANDS] = logp - mean_log

    # --- spectral descriptors over the full 512-bin spectrum ---
    idx = np.arange(NUM_BINS, dtype=np.float64)
    freqs = idx * sample_rate / FFT_SIZE
    nyq = sample_rate / 2.0
    total = float(np.sum(power))
    safe_total = total if total > 1e-12 else 1e-12

    centroid = float(np.sum(freqs * power)) / safe_total
    spread = math.sqrt(max(0.0, float(np.sum(((freqs - centroid) ** 2) * power)) / safe_total))

    # 85% spectral rolloff frequency
    cumulative = np.cumsum(power)
    threshold = 0.85 * total
    roll_bin = int(np.searchsorted(cumulative, threshold)) if total > 1e-12 else 0
    roll_bin = min(roll_bin, NUM_BINS - 1)
    rolloff = roll_bin * sample_rate / FFT_SIZE

    # spectral flatness (geometric mean / arithmetic mean of the band powers): tonal (kick) -> low,
    # noisy (hat) -> high. Computed over the 24 band powers, reusing mean_log -- so it costs no extra
    # logs at inference time (the per-bin variant cost 512 Math.log calls per onset).
    arith_mean = float(np.mean(band_power))
    flatness = math.exp(mean_log) / (arith_mean + LOG_EPS)

    low_bin = freq_to_bin(500, sample_rate)
    high_bin = freq_to_bin(2000, sample_rate)
    low_energy = float(np.sum(power[:low_bin]))
    high_energy = float(np.sum(power[high_bin:]))
    low_high_ratio = math.log(low_energy + LOG_EPS) - math.log(high_energy + LOG_EPS)

    feat[NUM_BANDS + 0] = min(centroid / nyq, 1.0)
    feat[NUM_BANDS + 1] = min(spread / nyq, 1.0)
    feat[NUM_BANDS + 2] = min(rolloff / nyq, 1.0)
    feat[NUM_BANDS + 3] = flatness
    feat[NUM_BANDS + 4] = low_high_ratio
    return feat


# ----------------------------------------------------------------------------------------
# Synthesis -- procedurally generate labelled drum hits with realistic spectral signatures and
# multiple archetypes per class, embedded in a randomized music bed.
# ----------------------------------------------------------------------------------------
def _shaped_noise(n, sample_rate, rng, kind, fc):
    spec = np.fft.rfft(rng.standard_normal(n))
    freqs = np.fft.rfftfreq(n, 1.0 / sample_rate)
    if kind == "highpass":
        gain = 1.0 / (1.0 + (fc / np.maximum(freqs, 1.0)) ** 2)
    elif kind == "lowpass":
        gain = 1.0 / (1.0 + (freqs / fc) ** 2)
    else:  # pink-ish
        gain = 1.0 / np.sqrt(np.maximum(freqs, 1.0))
    return np.fft.irfft(spec * gain, n)


def _onset_env(n, off, tau_samples):
    t = np.arange(n, dtype=np.float64)
    tt = np.clip(t - off, 0.0, None)
    mask = (t >= off).astype(np.float64)
    return np.exp(-tt / tau_samples) * mask, tt


def synth_kick(sample_rate, rng):
    n = FFT_SIZE
    off = int(rng.uniform(0, 150))
    archetype = rng.integers(0, 3)
    if archetype == 0:        # acoustic / punchy
        f0 = rng.uniform(50, 95)
        pitch_mult = rng.uniform(2.5, 4.5)
        tau = rng.uniform(0.04, 0.10) * sample_rate
        click_gain = rng.uniform(0.2, 0.5)
    elif archetype == 1:      # 808 / sub
        f0 = rng.uniform(32, 55)
        pitch_mult = rng.uniform(1.5, 2.5)
        tau = rng.uniform(0.10, 0.20) * sample_rate
        click_gain = rng.uniform(0.0, 0.2)
    else:                     # tight / electronic
        f0 = rng.uniform(60, 110)
        pitch_mult = rng.uniform(3.0, 5.0)
        tau = rng.uniform(0.02, 0.06) * sample_rate
        click_gain = rng.uniform(0.3, 0.6)
    pitch_tau = rng.uniform(0.006, 0.04) * sample_rate
    env, tt = _onset_env(n, off, tau)
    f_inst = f0 * (1.0 + (pitch_mult - 1.0) * np.exp(-tt / pitch_tau))
    phase = np.cumsum(2.0 * np.pi * f_inst / sample_rate)
    body = np.sin(phase) * env
    click = np.zeros(n)
    clen = max(1, int(rng.uniform(0.001, 0.004) * sample_rate))
    end = min(off + clen, n)
    click[off:end] = rng.standard_normal(end - off) * click_gain
    return body + click


def synth_snare(sample_rate, rng):
    n = FFT_SIZE
    off = int(rng.uniform(0, 150))
    archetype = rng.integers(0, 3)
    if archetype == 0:        # acoustic: body tone + wire noise
        env, tt = _onset_env(n, off, rng.uniform(0.05, 0.16) * sample_rate)
        body = np.zeros(n)
        for _ in range(int(rng.integers(1, 3))):
            fb = rng.uniform(150, 260)
            body += np.sin(2.0 * np.pi * fb * tt / sample_rate) * rng.uniform(0.3, 0.8)
        noise = _shaped_noise(n, sample_rate, rng, "highpass", rng.uniform(150, 350))
        return (body * rng.uniform(0.3, 0.8) + noise * rng.uniform(0.6, 1.2)) * env
    elif archetype == 1:      # clap: a few stacked noise bursts, brighter
        sig = np.zeros(n)
        bursts = int(rng.integers(3, 6))
        for b in range(bursts):
            boff = off + int(b * rng.uniform(0.004, 0.010) * sample_rate)
            if boff >= n:
                break
            env, _ = _onset_env(n, boff, rng.uniform(0.01, 0.03) * sample_rate)
            sig += _shaped_noise(n, sample_rate, rng, "highpass", rng.uniform(800, 1600)) * env
        return sig
    else:                     # electronic: gated noise + low tonal
        env, tt = _onset_env(n, off, rng.uniform(0.03, 0.10) * sample_rate)
        tonal = np.sin(2.0 * np.pi * rng.uniform(180, 240) * tt / sample_rate) * rng.uniform(0.2, 0.5)
        noise = _shaped_noise(n, sample_rate, rng, "highpass", rng.uniform(400, 900))
        return (tonal + noise * rng.uniform(0.7, 1.3)) * env


def synth_hat(sample_rate, rng):
    n = FFT_SIZE
    off = int(rng.uniform(0, 150))
    archetype = rng.integers(0, 3)
    if archetype == 0:        # closed
        tau = rng.uniform(0.006, 0.035) * sample_rate
    elif archetype == 1:      # open
        tau = rng.uniform(0.05, 0.16) * sample_rate
    else:                     # pedal / tight
        tau = rng.uniform(0.004, 0.015) * sample_rate
    env, tt = _onset_env(n, off, tau)
    noise = _shaped_noise(n, sample_rate, rng, "highpass", rng.uniform(3000, 7000))
    metal = np.zeros(n)
    for _ in range(int(rng.integers(2, 6))):
        fm = rng.uniform(6000, 14000)
        metal += np.sin(2.0 * np.pi * fm * tt / sample_rate) * rng.uniform(0.1, 0.4)
    return (noise * rng.uniform(0.7, 1.3) + metal * rng.uniform(0.3, 0.7)) * env


SYNTH = [synth_kick, synth_snare, synth_hat]


def _music_bed(n, sample_rate, rng):
    t = np.arange(n, dtype=np.float64)
    bed = np.zeros(n)
    # sustained tonal content (bass line + chords + the odd vocal-ish formant)
    for _ in range(int(rng.integers(2, 7))):
        f = rng.uniform(50, 8000)
        bed += np.sin(2.0 * np.pi * f * t / sample_rate + rng.uniform(0, 2 * np.pi)) * rng.uniform(0.1, 0.5)
    bed += _shaped_noise(n, sample_rate, rng, "pink", 0) * rng.uniform(0.2, 0.8)
    return bed


def _apply_spectral_shaping(sig, sample_rate, rng, hard):
    """Random EQ tilt + occasional codec-style band-limit, in the frequency domain."""
    spec = np.fft.rfft(sig)
    freqs = np.fft.rfftfreq(len(sig), 1.0 / sample_rate)
    # tilt: linear-in-log-frequency gain, positive = brighten, negative = darken
    tilt = rng.uniform(-0.5, 0.5) * (1.6 if hard else 1.0)
    logf = np.log2(np.maximum(freqs, 20.0) / 1000.0)
    gain = np.power(2.0, tilt * logf)
    # occasional low-pass band-limit (mp3/codec-ish)
    if rng.random() < (0.5 if hard else 0.3):
        fc = rng.uniform(8000, 18000) if not hard else rng.uniform(5000, 16000)
        gain = gain / (1.0 + (freqs / fc) ** 4)
    return np.fft.irfft(spec * gain, len(sig))


def make_example(label, sample_rate, rng, hard=False):
    sig = SYNTH[label](sample_rate, rng)
    sig = sig / (np.max(np.abs(sig)) + 1e-9)

    bed = _music_bed(FFT_SIZE, sample_rate, rng)
    bed = bed / (np.max(np.abs(bed)) + 1e-9)
    snr = rng.uniform(0.0, 0.75 if hard else 0.6)        # higher => more buried in the mix
    mix = sig * (1.0 - snr * 0.5) + bed * snr

    mix = _apply_spectral_shaping(mix, sample_rate, rng, hard)

    # saturation / clipping
    if rng.random() < (0.6 if hard else 0.4):
        drive = rng.uniform(1.5, 6.0 if hard else 4.0)
        mix = np.tanh(mix * drive) / np.tanh(drive)

    # broadband noise floor + random loudness
    mix = mix + _shaped_noise(FFT_SIZE, sample_rate, rng, "pink", 0) * rng.uniform(0.0, 0.15 if hard else 0.08)
    mix = mix * rng.uniform(0.04, 1.0)
    return extract_features(power_spectrum(mix), sample_rate)


def build_dataset(rng, n_per_class, hard=False, mixed=False):
    """mixed=True draws each example's difficulty at random (covers clean studio audio through
    nasty buried/saturated/band-limited audio), so the model is robust across the whole range."""
    X = np.empty((n_per_class * NUM_CLASSES, FEATURE_DIM), dtype=np.float64)
    y = np.empty(n_per_class * NUM_CLASSES, dtype=np.int64)
    i = 0
    for label in range(NUM_CLASSES):
        for _ in range(n_per_class):
            sr = SAMPLE_RATES[int(rng.integers(0, len(SAMPLE_RATES)))]
            ex_hard = (rng.random() < 0.5) if mixed else hard
            X[i] = make_example(label, sr, rng, hard=ex_hard)
            y[i] = label
            i += 1
    return X, y


# ----------------------------------------------------------------------------------------
# A small regularized MLP (F -> 64 -> 32 -> 3): Adam, cosine LR, dropout, weight decay,
# label smoothing, early stopping. Plain numpy, manual backprop.
# ----------------------------------------------------------------------------------------
def train(Xtr, ytr, Xval, yval, rng):
    def he(shape):
        return (rng.standard_normal(shape) * math.sqrt(2.0 / shape[0]))

    p = {"W1": he((FEATURE_DIM, HIDDEN1)), "b1": np.zeros(HIDDEN1),
         "W2": he((HIDDEN1, HIDDEN2)), "b2": np.zeros(HIDDEN2),
         "W3": he((HIDDEN2, NUM_CLASSES)), "b3": np.zeros(NUM_CLASSES)}
    m = {k: np.zeros_like(v) for k, v in p.items()}
    v = {k: np.zeros_like(v) for k, v in p.items()}
    b1m, b2m, eps = 0.9, 0.999, 1e-8

    def forward_eval(X):
        a1 = np.maximum(X @ p["W1"] + p["b1"], 0.0)
        a2 = np.maximum(a1 @ p["W2"] + p["b2"], 0.0)
        return a2 @ p["W3"] + p["b3"]

    def accuracy(X, y):
        return float(np.mean(np.argmax(forward_eval(X), axis=1) == y))

    n = Xtr.shape[0]
    steps_per_epoch = max(1, n // BATCH)
    total_steps = EPOCHS * steps_per_epoch
    step = 0
    best_val, best_p, since_best = -1.0, None, 0

    for epoch in range(EPOCHS):
        perm = rng.permutation(n)
        for s in range(0, n - BATCH + 1, BATCH):
            idx = perm[s:s + BATCH]
            xb, yb = Xtr[idx], ytr[idx]

            z1 = xb @ p["W1"] + p["b1"]
            a1 = np.maximum(z1, 0.0)
            d1 = (rng.random(a1.shape) >= DROPOUT) / (1.0 - DROPOUT)
            a1d = a1 * d1
            z2 = a1d @ p["W2"] + p["b2"]
            a2 = np.maximum(z2, 0.0)
            d2 = (rng.random(a2.shape) >= DROPOUT) / (1.0 - DROPOUT)
            a2d = a2 * d2
            logits = a2d @ p["W3"] + p["b3"]

            logits -= logits.max(axis=1, keepdims=True)
            probs = np.exp(logits)
            probs /= probs.sum(axis=1, keepdims=True)
            target = np.full((len(yb), NUM_CLASSES), LABEL_SMOOTH / NUM_CLASSES)
            target[np.arange(len(yb)), yb] += 1.0 - LABEL_SMOOTH
            g_logits = (probs - target) / len(yb)

            gW3 = a2d.T @ g_logits + WEIGHT_DECAY * p["W3"]
            gb3 = g_logits.sum(axis=0)
            ga2 = (g_logits @ p["W3"].T) * d2 * (z2 > 0)
            gW2 = a1d.T @ ga2 + WEIGHT_DECAY * p["W2"]
            gb2 = ga2.sum(axis=0)
            ga1 = (ga2 @ p["W2"].T) * d1 * (z1 > 0)
            gW1 = xb.T @ ga1 + WEIGHT_DECAY * p["W1"]
            gb1 = ga1.sum(axis=0)
            grads = {"W1": gW1, "b1": gb1, "W2": gW2, "b2": gb2, "W3": gW3, "b3": gb3}

            step += 1
            lr = LR_MIN + 0.5 * (LR_MAX - LR_MIN) * (1.0 + math.cos(math.pi * step / total_steps))
            for k in p:
                m[k] = b1m * m[k] + (1 - b1m) * grads[k]
                v[k] = b2m * v[k] + (1 - b2m) * (grads[k] ** 2)
                mhat = m[k] / (1 - b1m ** step)
                vhat = v[k] / (1 - b2m ** step)
                p[k] -= lr * mhat / (np.sqrt(vhat) + eps)

        val = accuracy(Xval, yval)
        if val > best_val:
            best_val, best_p, since_best = val, {k: v_.copy() for k, v_ in p.items()}, 0
        else:
            since_best += 1
        if epoch % 20 == 0 or epoch == EPOCHS - 1:
            print(f"  epoch {epoch:3d}  train={accuracy(Xtr, ytr):.4f}  val={val:.4f}  best={best_val:.4f}")
        if since_best >= PATIENCE:
            print(f"  early stop at epoch {epoch} (no val gain for {PATIENCE} epochs)")
            break

    return best_p


# ----------------------------------------------------------------------------------------
# Metrics + Java emission.
# ----------------------------------------------------------------------------------------
def report(name, params, X, y):
    a1 = np.maximum(X @ params["W1"] + params["b1"], 0.0)
    a2 = np.maximum(a1 @ params["W2"] + params["b2"], 0.0)
    pred = np.argmax(a2 @ params["W3"] + params["b3"], axis=1)
    acc = float(np.mean(pred == y))
    cm = np.zeros((NUM_CLASSES, NUM_CLASSES), dtype=int)
    for t, pr in zip(y, pred):
        cm[t, pr] += 1
    print(f"\n{name} accuracy: {acc:.4f}")
    print("  confusion (rows=true, cols=pred) [KICK, SNARE, HIHAT]:")
    for i in range(NUM_CLASSES):
        print(f"    {CLASS_NAMES[i]:5s} {cm[i]}")
    print("  per-class  precision / recall / f1:")
    for i in range(NUM_CLASSES):
        tp = cm[i, i]
        prec = tp / max(1, cm[:, i].sum())
        rec = tp / max(1, cm[i, :].sum())
        f1 = 2 * prec * rec / max(1e-9, prec + rec)
        print(f"    {CLASS_NAMES[i]:5s} {prec:.4f} / {rec:.4f} / {f1:.4f}")
    return acc


def jf(x):
    return f"{float(x):.8g}f"


def j1(name, arr):
    return f"    static final float[] {name} = {{{', '.join(jf(x) for x in arr)}}};\n"


def j2(name, arr):
    rows = ",\n        ".join("{" + ", ".join(jf(x) for x in row) + "}" for row in arr)
    return f"    static final float[][] {name} = {{\n        {rows}\n    }};\n"


def emit_model(path, params, mean, std):
    with open(path, "w", encoding="utf-8") as f:
        f.write("package com.glyphvisualizer;\n\n")
        f.write("/**\n * GENERATED FILE -- do not edit by hand.\n")
        f.write(" * Produced by tool/train_drum_classifier.py. Learned weights of the kick/snare/hi-hat\n")
        f.write(f" * drum classifier MLP ({FEATURE_DIM}->{HIDDEN1}->{HIDDEN2}->{NUM_CLASSES}).\n")
        f.write(" * Re-run the trainer to regenerate. See DrumClassifier.java for the inference math.\n */\n")
        f.write("final class DrumClassifierModel {\n")
        f.write(f"    static final int FEATURE_DIM = {FEATURE_DIM};\n")
        f.write(f"    static final int HIDDEN1 = {HIDDEN1};\n")
        f.write(f"    static final int HIDDEN2 = {HIDDEN2};\n")
        f.write(f"    static final int NUM_CLASSES = {NUM_CLASSES};\n")
        f.write(f"    static final int NUM_BANDS = {NUM_BANDS};\n")
        f.write(f"    static final int FFT_SIZE = {FFT_SIZE};\n")
        f.write(f"    static final int NUM_BINS = {NUM_BINS};\n")
        f.write(f"    static final double LOG_EPS = {LOG_EPS};\n")
        f.write(f"    static final int[] BAND_EDGES_HZ = {{{', '.join(str(e) for e in BAND_EDGES_HZ)}}};\n\n")
        f.write(j1("FEATURE_MEAN", mean))
        f.write(j1("FEATURE_STD", std))
        f.write(j2("W1", params["W1"])); f.write(j1("B1", params["b1"]))
        f.write(j2("W2", params["W2"])); f.write(j1("B2", params["b2"]))
        f.write(j2("W3", params["W3"])); f.write(j1("B3", params["b3"]))
        f.write("\n    private DrumClassifierModel() {}\n}\n")
    print(f"  wrote {path}")


def forward_f32(params, mean, std, feat):
    """Reference forward pass: everything Java stores as float is rounded to float32 first, so the
    golden logits match Java's float-weight / double-accumulate arithmetic."""
    mean = mean.astype(np.float32).astype(np.float64)
    std = std.astype(np.float32).astype(np.float64)
    x = (feat.astype(np.float64) - mean) / std
    W1 = params["W1"].astype(np.float32).astype(np.float64); b1 = params["b1"].astype(np.float32).astype(np.float64)
    W2 = params["W2"].astype(np.float32).astype(np.float64); b2 = params["b2"].astype(np.float32).astype(np.float64)
    W3 = params["W3"].astype(np.float32).astype(np.float64); b3 = params["b3"].astype(np.float32).astype(np.float64)
    a1 = np.maximum(x @ W1 + b1, 0.0)
    a2 = np.maximum(a1 @ W2 + b2, 0.0)
    return a2 @ W3 + b3


def emit_test(path, params, mean, std, rng):
    feats = [rng.standard_normal(FEATURE_DIM) for _ in range(6)]
    spec_examples = []
    for _ in range(4):
        label = int(rng.integers(0, NUM_CLASSES))
        sr = SAMPLE_RATES[int(rng.integers(0, len(SAMPLE_RATES)))]
        power = power_spectrum(SYNTH[label](sr, rng)).astype(np.float32)
        feat = extract_features(power.astype(np.float64), sr)
        spec_examples.append((sr, power, feat))

    with open(path, "w", encoding="utf-8") as f:
        f.write("package com.glyphvisualizer;\n\n")
        f.write("import static org.junit.Assert.assertEquals;\n")
        f.write("import org.junit.Test;\n\n")
        f.write("/**\n * GENERATED FILE -- do not edit by hand. Produced by tool/train_drum_classifier.py.\n")
        f.write(" * Golden-vector tests that lock the Java DrumClassifier to the Python trainer's numerics,\n")
        f.write(" * so a model retrain or an inference-math refactor can't silently drift.\n */\n")
        f.write("public class DrumClassifierTest {\n\n")

        f.write("    @Test\n    public void mlpForwardMatchesTrainer() {\n")
        for feat in feats:
            logits = forward_f32(params, mean, std, feat)
            f.write(f"        assertForward(new float[]{{{', '.join(jf(v) for v in feat)}}}, "
                    f"new float[]{{{', '.join(jf(v) for v in logits)}}});\n")
        f.write("    }\n\n")
        f.write("    private void assertForward(float[] rawFeatures, float[] expectedLogits) {\n")
        f.write("        float[] logits = DrumClassifier.forwardForTest(rawFeatures);\n")
        f.write("        for (int i = 0; i < expectedLogits.length; i++) {\n")
        f.write("            assertEquals(expectedLogits[i], logits[i], 2e-3f);\n        }\n    }\n\n")

        f.write("    @Test\n    public void featureExtractionMatchesTrainer() {\n")
        for i, (sr, power, feat) in enumerate(spec_examples):
            f.write(f"        assertFeatures(SPEC_{i}, {sr}, new float[]{{{', '.join(jf(v) for v in feat)}}});\n")
        f.write("    }\n\n")
        f.write("    private void assertFeatures(float[] power, int sampleRate, float[] expected) {\n")
        f.write("        float[] feat = DrumClassifier.extractFeaturesForTest(power, sampleRate);\n")
        f.write("        for (int i = 0; i < expected.length; i++) {\n")
        f.write("            assertEquals(expected[i], feat[i], 2e-3f);\n        }\n    }\n\n")
        for i, (sr, power, feat) in enumerate(spec_examples):
            f.write(f"    private static final float[] SPEC_{i} = {{{', '.join(jf(v) for v in power)}}};\n")
        f.write("}\n")
    print(f"  wrote {path}")


def main():
    rng = np.random.default_rng(SEED)
    here = os.path.dirname(os.path.abspath(__file__))
    java_dir = os.path.normpath(os.path.join(here, "..", "android", "app", "src", "main", "java", "com", "glyphvisualizer"))
    test_dir = os.path.normpath(os.path.join(here, "..", "android", "app", "src", "test", "java", "com", "glyphvisualizer"))

    print(f"Feature dim: {FEATURE_DIM} ({NUM_BANDS} bands + {NUM_EXTRA} descriptors), "
          f"net {FEATURE_DIM}->{HIDDEN1}->{HIDDEN2}->{NUM_CLASSES}")
    print("Synthesizing training data (mixed difficulty: clean -> nasty)...")
    X, y = build_dataset(rng, N_PER_CLASS, mixed=True)
    mean = X.mean(axis=0)
    std = X.std(axis=0) + 1e-6
    Xn = (X - mean) / std

    perm = rng.permutation(len(Xn))
    Xn, y = Xn[perm], y[perm]
    nval = int(len(Xn) * VAL_FRAC)
    Xval, yval, Xtr, ytr = Xn[:nval], y[:nval], Xn[nval:], y[nval:]
    print(f"  {len(Xtr)} train / {len(Xval)} val")

    print("Training...")
    params = train(Xtr, ytr, Xval, yval, rng)

    report("Validation", params, Xval, yval)

    print("\nSynthesizing SEPARATE hard test set (different seed, harsher augmentation)...")
    test_rng = np.random.default_rng(TEST_SEED)
    Xtest, ytest = build_dataset(test_rng, N_TEST_PER_CLASS, hard=True)
    Xtest = (Xtest - mean) / std
    report("HARD held-out test", params, Xtest, ytest)

    print("\nEmitting Java...")
    emit_model(os.path.join(java_dir, "DrumClassifierModel.java"), params, mean, std)
    emit_test(os.path.join(test_dir, "DrumClassifierTest.java"), params, mean, std, rng)
    print("Done.")


if __name__ == "__main__":
    main()
