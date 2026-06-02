#!/usr/bin/env python3
"""
Expert 1 — Evil Twin Detector Training
=======================================
Dataset: AWID3 (https://icsdweb.aegean.gr/awid/awid3)
  Register for free access. Extract beacon-level features from the
  "Evil Twin" labelled attack capture files. The AWID3 README explains
  the CSV column schema.

  This script also generates synthetic samples (supplementary) using
  the feature distributions documented in the AWID3 paper §IV-B:
  "Evil Twin APs randomise the OUI prefix, clone the SSID verbatim,
  and are positioned to match the legitimate AP's RSSI."

Model: sklearn.ensemble.GradientBoostingClassifier
  Input dim:  5
  Output:     binary class (0 = legitimate pair, 1 = evil twin)
  Target:     ≥ 90% accuracy on AWID3 test split

Run:
  python 01_evil_twin_train.py
Produces:
  evil_twin_detector.onnx  (fp32, ~150 KB)
  evil_twin_detector.pkl   (optional sklearn checkpoint)
"""

import numpy as np
import pickle
from pathlib import Path
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

# ── Feature indices ───────────────────────────────────────────────────────────
# 0  bssid_similarity   Levenshtein(mac_A, mac_B) / 12, range [0,1]
# 1  oui_match          1 if same OUI prefix, 0 if not
# 2  encryption_delta   |encScore_A - encScore_B|
# 3  rssi_diff          |rssi_A - rssi_B| / 65, range [0,1]
# 4  band_mismatch      1 if different bands, 0 if same

N_FEATURES = 5
N_SAMPLES  = 60_000  # synthetic supplement; replace/augment with real AWID3 data

# ── Synthetic dataset generation ──────────────────────────────────────────────

rng = np.random.default_rng(42)

def generate_samples(n: int, label: int) -> np.ndarray:
    """Generate feature vectors matching the label's empirical distribution."""
    if label == 0:  # Legitimate AP pair (same vendor, adjacent MACs)
        bssid_sim = rng.beta(1.5, 8, n)           # low similarity (MACs differ widely)
        oui_match = rng.binomial(1, 0.85, n)       # usually same vendor OUI
        enc_delta = rng.beta(1, 20, n)             # same or very similar encryption
        rssi_diff = rng.beta(2, 5, n)              # RSSI differs — APs in different rooms
        band_mis  = rng.binomial(1, 0.30, n)       # band mismatch common (2.4 + 5 GHz pair)
    else:            # Evil twin (spoofed MAC, encryption downgrade, planted RSSI match)
        bssid_sim = rng.beta(6, 3, n)              # similar-looking MAC (spoofed nearby)
        oui_match = rng.binomial(1, 0.15, n)       # different vendor OUI (random MAC)
        enc_delta = rng.beta(5, 3, n)              # encryption downgraded (open or WEP)
        rssi_diff = rng.beta(1.5, 8, n)            # planted to match legitimate AP RSSI
        band_mis  = rng.binomial(1, 0.45, n)       # more likely to be on unusual band
    return np.column_stack([bssid_sim, oui_match, enc_delta, rssi_diff, band_mis]).astype(np.float32)

# Balanced dataset: 50% legitimate, 50% evil twin
n_each = N_SAMPLES // 2
X_legit = generate_samples(n_each, label=0)
X_evil  = generate_samples(n_each, label=1)
X = np.vstack([X_legit, X_evil])
y = np.hstack([np.zeros(n_each, dtype=np.int64), np.ones(n_each, dtype=np.int64)])

# Shuffle
idx = rng.permutation(len(X))
X, y = X[idx], y[idx]

# ── AWID3 integration point ───────────────────────────────────────────────────
awid3_path = Path("awid3_evil_twin_features.csv")
if awid3_path.exists():
    import pandas as pd
    df = pd.read_csv(awid3_path)
    # Expected columns: bssid_similarity,oui_match,encryption_delta,rssi_diff,band_mismatch,label
    X_real = df[["bssid_similarity","oui_match","encryption_delta","rssi_diff","band_mismatch"]].values.astype(np.float32)
    y_real = df["label"].values.astype(np.int64)
    X = np.vstack([X, X_real])
    y = np.hstack([y, y_real])
    print(f"[AWID3] Loaded {len(X_real)} real samples. Total: {len(X)}")
else:
    print("[INFO] awid3_evil_twin_features.csv not found — using synthetic data only.")
    print("       See https://icsdweb.aegean.gr/awid/awid3 for the real dataset.")

# ── Train / test split ────────────────────────────────────────────────────────
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)

# ── Train model ───────────────────────────────────────────────────────────────
clf = GradientBoostingClassifier(
    n_estimators=100,
    max_depth=4,
    learning_rate=0.1,
    subsample=0.8,
    random_state=42
)
print("Training EvilTwinDetector…")
clf.fit(X_train, y_train)

# ── Evaluate ──────────────────────────────────────────────────────────────────
y_pred = clf.predict(X_test)
print("\nClassification report:")
print(classification_report(y_test, y_pred, target_names=["Legitimate", "Evil Twin"]))

accuracy = (y_pred == y_test).mean()
print(f"Accuracy: {accuracy:.4f}")
if accuracy < 0.90:
    print("WARNING: accuracy < 90% target. Augment with AWID3 real data for production.")

# ── Save sklearn checkpoint ───────────────────────────────────────────────────
with open("evil_twin_detector.pkl", "wb") as f:
    pickle.dump(clf, f)
print("Saved: evil_twin_detector.pkl")

# ── Export to ONNX ────────────────────────────────────────────────────────────
initial_type = [("float_input", FloatTensorType([None, N_FEATURES]))]
onnx_model = convert_sklearn(clf, initial_types=initial_type, target_opset=17)

with open("evil_twin_detector.onnx", "wb") as f:
    f.write(onnx_model.SerializeToString())
print("Exported: evil_twin_detector.onnx")
print(f"  Size: {Path('evil_twin_detector.onnx').stat().st_size / 1024:.1f} KB")
print("\nRun export_all.py to quantize → int8 and copy to app assets.")
