#!/usr/bin/env python3
"""
Train the drum classifier on REAL data (IDMT-SMT-Drums) -- the "make it actually insane" trainer.

Pipeline:
  1. tool/prepare_real_dataset.py  ->  tool/_cache/real_features.npz   (real labelled features)
  2. this script                    ->  DrumClassifierModel.java + DrumClassifierTest.java

Why this beats the pure-synthetic model: the snare-vs-hat confusion (and the synthetic->real gap)
is a *data realism* problem. Real recordings of real kits fix it directly. We keep the SAME 29
features and the SAME 29->96->48->3 net (so on-device inference + latency are unchanged), and just
feed it real onsets.

Honest evaluation: examples are split BY DRUM LOOP -- augmented copies of one hit, and the
isolated-stem + in-mix versions of one onset, never straddle train/test. The reported accuracy is
on entirely unseen loops.

Robustness: IDMT is clean studio audio, but the phone hears saturation, EQ tilt, codec artefacts and
a full music bed. So we mix a dose of the synthetic augmentation (from train_drum_classifier) into
the TRAIN set only -- real timbres grounded by synthetic diversity. The test set stays 100% real.

Uses the RTX 3060 via PyTorch when available; falls back to numpy on CPU (the net is tiny, so CPU is
fine too -- the GPU mostly just makes iteration snappy). Either way the exported Java is identical.

Run:  python tool/train_real.py
"""

import argparse
import os
import sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train_drum_classifier as T

CLASS_NAMES = ["KICK", "SNARE", "HIHAT"]
SEED = 7

try:
    import torch
    import torch.nn as nn
    _HAVE_TORCH = True
except Exception:
    _HAVE_TORCH = False


def loop_split(loop, y, rng, n_test_loops=15, n_val_loops=10):
    """Group split: hold out whole loops so no onset leaks between train/val/test."""
    loops = np.array(sorted(set(loop.tolist())))
    rng.shuffle(loops)
    test_loops = set(loops[:n_test_loops])
    val_loops = set(loops[n_test_loops:n_test_loops + n_val_loops])
    is_test = np.array([l in test_loops for l in loop])
    is_val = np.array([l in val_loops for l in loop])
    is_train = ~(is_test | is_val)
    return is_train, is_val, is_test


def class_weights(y):
    counts = np.bincount(y, minlength=3).astype(np.float64)
    w = counts.sum() / (3.0 * np.maximum(counts, 1.0))
    return w / w.mean()


def confusion_report(name, y_true, y_pred):
    acc = float(np.mean(y_pred == y_true))
    cm = np.zeros((3, 3), dtype=int)
    for t, p in zip(y_true, y_pred):
        cm[t, p] += 1
    print(f"\n{name} accuracy: {acc:.4f}  (n={len(y_true)})")
    print("  confusion (rows=true, cols=pred) [KICK, SNARE, HIHAT]:")
    for i in range(3):
        print(f"    {CLASS_NAMES[i]:5s} {cm[i]}")
    print("  per-class  precision / recall / f1:")
    for i in range(3):
        tp = cm[i, i]
        prec = tp / max(1, cm[:, i].sum())
        rec = tp / max(1, cm[i, :].sum())
        f1 = 2 * prec * rec / max(1e-9, prec + rec)
        print(f"    {CLASS_NAMES[i]:5s} {prec:.4f} / {rec:.4f} / {f1:.4f}")
    return acc


def train_torch(Xtr, ytr, Xval, yval, cw, epochs):
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"  backend: PyTorch on {device.upper()}"
          + (f" ({torch.cuda.get_device_name(0)})" if device == "cuda" else ""))
    torch.manual_seed(SEED)
    model = nn.Sequential(
        nn.Linear(T.FEATURE_DIM, T.HIDDEN1), nn.ReLU(), nn.Dropout(0.10),
        nn.Linear(T.HIDDEN1, T.HIDDEN2), nn.ReLU(), nn.Dropout(0.10),
        nn.Linear(T.HIDDEN2, 3),
    ).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=3e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs)
    lossf = nn.CrossEntropyLoss(weight=torch.tensor(cw, dtype=torch.float32, device=device),
                                label_smoothing=0.03)

    Xtr_t = torch.tensor(Xtr, dtype=torch.float32, device=device)
    ytr_t = torch.tensor(ytr, dtype=torch.long, device=device)
    Xval_t = torch.tensor(Xval, dtype=torch.float32, device=device)
    n, batch = len(Xtr), 512
    best_acc, best_state, since = -1.0, None, 0

    for epoch in range(epochs):
        model.train()
        perm = torch.randperm(n, device=device)
        for s in range(0, n - 1, batch):
            idx = perm[s:s + batch]
            opt.zero_grad()
            loss = lossf(model(Xtr_t[idx]), ytr_t[idx])
            loss.backward()
            opt.step()
        sched.step()
        model.eval()
        with torch.no_grad():
            val_pred = model(Xval_t).argmax(1).cpu().numpy()
        acc = float(np.mean(val_pred == yval))
        if acc > best_acc:
            best_acc, best_state, since = acc, {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}, 0
        else:
            since += 1
        if epoch % 20 == 0 or epoch == epochs - 1:
            print(f"  epoch {epoch:3d}  val={acc:.4f}  best={best_acc:.4f}")
        if since >= 40:
            print(f"  early stop at epoch {epoch}")
            break

    model.load_state_dict(best_state)
    sd = model.state_dict()
    return {
        "W1": sd["0.weight"].cpu().numpy().T.copy(), "b1": sd["0.bias"].cpu().numpy(),
        "W2": sd["3.weight"].cpu().numpy().T.copy(), "b2": sd["3.bias"].cpu().numpy(),
        "W3": sd["6.weight"].cpu().numpy().T.copy(), "b3": sd["6.bias"].cpu().numpy(),
    }, model


def predict(params, X):
    a1 = np.maximum(X @ params["W1"] + params["b1"], 0.0)
    a2 = np.maximum(a1 @ params["W2"] + params["b2"], 0.0)
    return np.argmax(a2 @ params["W3"] + params["b3"], axis=1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--real", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                   "_cache", "real_features.npz"))
    ap.add_argument("--synth", type=int, default=9000,
                    help="synthetic examples (mixed difficulty) added to TRAIN for robustness; 0 to disable")
    ap.add_argument("--epochs", type=int, default=300)
    ap.add_argument("--stem-only", action="store_true",
                    help="use only clean isolated-stem examples (unambiguous labels), drop polyphonic mix windows")
    args = ap.parse_args()

    if not os.path.isfile(args.real):
        sys.exit(f"{args.real} not found -- run: python tool/prepare_real_dataset.py first")

    rng = np.random.default_rng(SEED)
    data = np.load(args.real, allow_pickle=True)
    X, y, loop, src = data["X"].astype(np.float64), data["y"], data["loop"], data["src"]
    if args.stem_only:
        keep = src == "stem"
        X, y, loop, src = X[keep], y[keep], loop[keep], src[keep]
        print("stem-only mode: using clean isolated-stem examples")
    print(f"Real examples: {len(y)}  (KICK {int((y==0).sum())}, SNARE {int((y==1).sum())}, "
          f"HIHAT {int((y==2).sum())}) from {len(set(loop.tolist()))} loops")

    is_train, is_val, is_test = loop_split(loop, y, rng)
    Xtr, ytr = X[is_train], y[is_train]
    Xval, yval = X[is_val], y[is_val]
    Xtest, ytest, src_test = X[is_test], y[is_test], src[is_test]
    print(f"Split by loop: train {is_train.sum()} / val {is_val.sum()} / test {is_test.sum()}")

    # Add synthetic augmentation to TRAIN only (real timbres + synthetic diversity). Test stays real.
    if args.synth > 0:
        print(f"Synthesizing {args.synth} augmented examples for the train set...")
        per_class = args.synth // 3
        Xs, ys = T.build_dataset(rng, per_class, mixed=True)
        Xtr = np.concatenate([Xtr, Xs], axis=0)
        ytr = np.concatenate([ytr, ys], axis=0)
        print(f"  train is now {len(ytr)} (real + synthetic)")

    mean = Xtr.mean(axis=0)
    std = Xtr.std(axis=0) + 1e-6
    Xtr_n, Xval_n, Xtest_n = (Xtr - mean) / std, (Xval - mean) / std, (Xtest - mean) / std
    cw = class_weights(ytr)
    print(f"Class weights (kick/snare/hat): {np.round(cw, 3)}")

    print("Training...")
    if _HAVE_TORCH:
        params, _ = train_torch(Xtr_n, ytr, Xval_n, yval, cw, args.epochs)
    else:
        print("  backend: numpy on CPU (PyTorch not available)")
        params = T.train(Xtr_n, ytr, Xval_n, yval, rng)

    confusion_report("Validation (real, unseen loops)", yval, predict(params, Xval_n))
    test_pred = predict(params, Xtest_n)
    test_acc = confusion_report("HELD-OUT TEST (real, unseen loops)", ytest, test_pred)
    # Break the test number down by source: clean isolated stems vs polyphonic mix windows.
    for s in ("stem", "mix"):
        m = src_test == s
        if m.any():
            print(f"  [{s}] accuracy: {float(np.mean(test_pred[m] == ytest[m])):.4f}  (n={int(m.sum())})")

    here = os.path.dirname(os.path.abspath(__file__))
    java_dir = os.path.normpath(os.path.join(here, "..", "android", "app", "src", "main", "java", "com", "glyphvisualizer"))
    test_dir = os.path.normpath(os.path.join(here, "..", "android", "app", "src", "test", "java", "com", "glyphvisualizer"))
    print("\nEmitting Java (same format as the synthetic trainer; golden tests regenerated)...")
    T.emit_model(os.path.join(java_dir, "DrumClassifierModel.java"), params, mean, std)
    T.emit_test(os.path.join(test_dir, "DrumClassifierTest.java"), params, mean, std, rng)
    print(f"\nDone. Real held-out test accuracy: {test_acc:.4f}")


if __name__ == "__main__":
    main()
