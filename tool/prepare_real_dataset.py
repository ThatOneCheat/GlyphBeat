#!/usr/bin/env python3
"""
Turn the IDMT-SMT-Drums dataset into labelled (feature, class) pairs for the drum classifier.

This is the "real data" half of the pipeline. It reuses the EXACT feature extraction from
tool/train_drum_classifier.py (imported, not copied), so every example here is described by the
same 29-dim vector the on-phone Java computes -- no parity risk.

Why IDMT-SMT-Drums is a great fit:
  * 44.1 kHz mono 16-bit WAV -- identical to the engine's capture/analysis format.
  * Every drum loop ("#MIX.wav") has an XML transcription: each event has <onsetSec> + <instrument>
    (KD / SD / HH).
  * Each loop ALSO ships isolated per-instrument stems ("#KD#train.wav", "#SD#train.wav",
    "#HH#train.wav") with the SAME timing. So we can window the *isolated* stem at each annotated
    onset to get a perfectly clean, unambiguously-labelled example (no polyphonic bleed) -- which is
    exactly what helps the snare-vs-hat confusion -- and ALSO window the MIX for realistic in-context
    examples.

Dataset: IDMT-SMT-Drums (CC BY-NC-ND 4.0, Fraunhofer IDMT). NOT redistributed here -- only the
learned weights ship in the app. Download it yourself (see --root / the README).

Output: tool/_cache/real_features.npz  with arrays X (N,29) float32, y (N,) int64, src (N,) tags.

Run:  python tool/prepare_real_dataset.py --root C:/Users/Beast/glyphbeat_data/IDMT
"""

import argparse
import os
import sys
import wave
import xml.etree.ElementTree as ET
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train_drum_classifier as T   # exact feature code + constants (no main() runs on import)

CLASS_OF = {"KD": 0, "SD": 1, "HH": 2}          # kick / snare / hi-hat
CLASS_NAMES = ["KICK", "SNARE", "HIHAT"]
# Where the aligned transient peak sits inside the 1024-sample window (light augmentation). The
# engine classifies a frame with the attack somewhere in it, so a couple of positions generalize.
PEAK_OFFSETS = [48, 128]
MIX_ISOLATION_MS = 35.0                         # only window the MIX where one onset stands alone
ENERGY_GATE_FRAC = 0.04                         # skip onsets weaker than this fraction of the file's
                                                # strongest hit (kills silent / bleed-only windows)


def read_wav_mono(path):
    with wave.open(path, "rb") as w:
        nch, sw, sr, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    if sw != 2:
        raise ValueError(f"expected 16-bit PCM, got {sw*8}-bit: {path}")
    data = np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768.0
    if nch > 1:
        data = data.reshape(-1, nch).mean(axis=1)
    return data, sr


def window_at(samples, start, length=None):
    """Extract a window of `length` samples starting at `start` (zero-padded at edges)."""
    if length is None:
        length = T.FFT_SIZE
    frame = np.zeros(length, dtype=np.float64)
    src_lo = max(0, start)
    src_hi = min(len(samples), start + length)
    if src_hi > src_lo:
        frame[src_lo - start: src_hi - start] = samples[src_lo:src_hi]
    return frame


def find_peak(samples, onset_sample, search_back=48, search_fwd=300, hop=16, win=256):
    """Snap to the strongest short-term-energy point near the annotated onset, so windows line up on
    the real transient (not on silence just before it). Returns (peak_sample, peak_energy)."""
    lo = max(0, onset_sample - search_back)
    hi = min(max(0, len(samples) - win), onset_sample + search_fwd)
    best_e, best_i = -1.0, min(onset_sample, max(0, len(samples) - 1))
    i = lo
    while i <= hi:
        seg = samples[i:i + win]
        e = float(np.dot(seg, seg))
        if e > best_e:
            best_e, best_i = e, i
        i += hop
    return best_i, best_e


def parse_events(xml_path):
    root = ET.parse(xml_path).getroot()
    mix_name = root.findtext(".//audioFileName")
    events = []
    for ev in root.findall(".//transcription/event"):
        instr = (ev.findtext("instrument") or "").strip()
        onset = ev.findtext("onsetSec")
        if instr in CLASS_OF and onset is not None:
            events.append((float(onset), instr))
    events.sort()
    return mix_name, events


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="C:/Users/Beast/glyphbeat_data/IDMT",
                    help="dataset root containing audio/ and annotation_xml/")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                  "_cache", "real_features.npz"))
    args = ap.parse_args()

    audio_dir = os.path.join(args.root, "audio")
    xml_dir = os.path.join(args.root, "annotation_xml")
    if not os.path.isdir(xml_dir):
        sys.exit(f"annotation_xml/ not found under {args.root} -- download the dataset first "
                 f"(zenodo.org/records/7544164, see prepare_real_dataset.py header).")

    X, y, src, loop = [], [], [], []
    wav_cache = {}

    def get_wav(name):
        if name not in wav_cache:
            path = os.path.join(audio_dir, name)
            wav_cache[name] = read_wav_mono(path) if os.path.isfile(path) else (None, None)
        return wav_cache[name]

    xml_files = sorted(f for f in os.listdir(xml_dir) if f.endswith(".xml"))
    print(f"Parsing {len(xml_files)} annotated loops from {audio_dir} ...")
    n_loops = 0
    for xf in xml_files:
        mix_name, events = parse_events(os.path.join(xml_dir, xf))
        if not mix_name or not events:
            continue
        n_loops += 1
        onset_times = [t for t, _ in events]
        mix_samples, mix_sr = get_wav(mix_name)

        # --- clean examples from the isolated stems, grouped per instrument so we can peak-align and
        #     gate each onset against that stem's strongest hit (drops silent / bleed-only windows) ---
        by_instr = {}
        for onset_sec, instr in events:
            by_instr.setdefault(instr, []).append(onset_sec)
        for instr, onsets in by_instr.items():
            cls = CLASS_OF[instr]
            stem_samples, stem_sr = get_wav(mix_name.replace("#MIX.wav", f"#{instr}#train.wav"))
            if stem_samples is None:
                continue
            peaks = [find_peak(stem_samples, int(round(t * stem_sr))) for t in onsets]
            max_e = max((e for _, e in peaks), default=0.0)
            gate = ENERGY_GATE_FRAC * max_e
            for peak_i, peak_e in peaks:
                if peak_e < gate:
                    continue
                for off in PEAK_OFFSETS:
                    feat = T.extract_features(T.power_spectrum(window_at(stem_samples, peak_i - off)), stem_sr)
                    X.append(feat); y.append(cls); src.append("stem"); loop.append(mix_name)

        # --- realistic in-mix examples, only where this onset stands alone in time (peak-aligned) ---
        if mix_samples is not None:
            mix_peaks = [find_peak(mix_samples, int(round(t * mix_sr)))[1] for t, _ in events]
            mix_gate = ENERGY_GATE_FRAC * (max(mix_peaks) if mix_peaks else 0.0)
            for i, (onset_sec, instr) in enumerate(events):
                prev_gap = onset_sec - onset_times[i - 1] if i > 0 else 9.0
                next_gap = onset_times[i + 1] - onset_sec if i + 1 < len(onset_times) else 9.0
                if min(prev_gap, next_gap) * 1000.0 < MIX_ISOLATION_MS:
                    continue
                peak_i, peak_e = find_peak(mix_samples, int(round(onset_sec * mix_sr)))
                if peak_e < mix_gate:
                    continue
                for off in PEAK_OFFSETS:
                    feat = T.extract_features(T.power_spectrum(window_at(mix_samples, peak_i - off)), mix_sr)
                    X.append(feat); y.append(CLASS_OF[instr]); src.append("mix"); loop.append(mix_name)

    X = np.asarray(X, dtype=np.float32)
    y = np.asarray(y, dtype=np.int64)
    src = np.asarray(src)
    loop = np.asarray(loop)

    print(f"\n{n_loops} loops -> {len(y)} examples ({T.FEATURE_DIM} features each)")
    for c in range(3):
        m = y == c
        print(f"  {CLASS_NAMES[c]:5s} {int(m.sum()):6d}  "
              f"(stem {int((m & (src=='stem')).sum())}, mix {int((m & (src=='mix')).sum())})")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    np.savez_compressed(args.out, X=X, y=y, src=src, loop=loop)
    print(f"\nwrote {args.out}")


if __name__ == "__main__":
    main()
