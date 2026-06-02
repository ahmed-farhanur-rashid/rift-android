#!/usr/bin/env python3
"""
Expert 3 — Interference Predictor Training
===========================================
Dataset: Physics-derived synthetic generation.
  Labels are computed from SINR (Signal-to-Interference-plus-Noise Ratio)
  estimates. The SINR model is standard 802.11 interference analysis:
  - Co-channel interference degrades linearly with number of APs
  - Adjacent-channel interference (2.4 GHz) adds ~20 dB attenuation
  - RSSI of co-channel APs is the dominant interference source

  Labels:
    0 = LOW       (SINR > 20 dB — excellent conditions)
    1 = MEDIUM    (SINR 10–20 dB — acceptable)
    2 = HIGH      (SINR 5–10 dB — noticeably degraded)
    3 = SEVERE    (SINR < 5 dB — effectively unusable)

Model: sklearn.ensemble.RandomForestClassifier
  Input dim:  6
  Output:     4-class label {LOW=0, MEDIUM=1, HIGH=2, SEVERE=3}

Run:
  python 03_interference_train.py
Produces:
  interference_predictor.onnx  (fp32, ~300 KB)
  interference_predictor.pkl
"""

import numpy as np
import pickle
from pathlib import Path
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

# ── Feature indices ───────────────────────────────────────────────────────────
# 0  channel_occupancy        APs on same channel / total APs  [0,1]
# 1  avg_rssi_on_channel      mean RSSI of co-channel APs, normalised [0,1]
# 2  max_rssi_on_channel      strongest co-channel competitor, normalised [0,1]
# 3  adjacent_channel_count   APs on ±1 channel / total (2.4 GHz partial overlap) [0,1]
# 4  band                     0.0 = 2.4 GHz, 1.0 = 5/6 GHz
# 5  total_ap_count_norm      total visible APs / 50 [0,1]

N_FEATURES = 6
N_SAMPLES  = 40_000

rng = np.random.default_rng(42)

# SINR attenuation constants (empirical 802.11n/ac indoor)
CO_CHANNEL_DB_PER_AP  = 3.0    # dB degradation per additional co-channel AP
ADJ_CHANNEL_ATTEN_DB  = 20.0   # 2.4 GHz adjacent channel isolation


def sinr_estimate(channel_occ, max_rssi_norm, adj_count, band):
    """
    Rough SINR estimate in dB from observable features.
    max_rssi_norm re-scaled back to dBm range for calculation.
    """
    max_rssi_dbm = -95 + max_rssi_norm * 65   # approximately [-95, -30]
    noise_floor   = -95.0                       # thermal noise floor (dBm)
    signal_dbm    = max_rssi_dbm

    # Co-channel interference: sum of other APs' power
    # Approximate: channel_occ × 10 APs max on channel → n competing APs
    n_co_aps = int(channel_occ * 10)
    co_interference_dbm = signal_dbm - CO_CHANNEL_DB_PER_AP * n_co_aps

    # Adjacent channel interference (2.4 GHz only)
    adj_interference_dbm = -999 if band > 0.5 else (signal_dbm - ADJ_CHANNEL_ATTEN_DB - (1 - adj_count) * 10)

    # SINR = signal / (co_interference + adj_interference + noise)
    # In log domain: SINR ≈ signal - max_interference
    worst_interference = max(co_interference_dbm, adj_interference_dbm, noise_floor)
    sinr_db = signal_dbm - worst_interference
    return sinr_db


def sinr_to_label(sinr_db: float) -> int:
    if sinr_db > 20:   return 0  # LOW interference
    elif sinr_db > 10: return 1  # MEDIUM
    elif sinr_db > 5:  return 2  # HIGH
    else:              return 3  # SEVERE


def generate_samples(n: int) -> tuple[np.ndarray, np.ndarray]:
    channel_occ  = rng.beta(2, 3, n)        # skewed low — most channels lightly used
    avg_rssi     = rng.beta(3, 3, n)
    max_rssi     = np.clip(avg_rssi + rng.normal(0.05, 0.05, n), 0, 1)
    adj_count    = rng.beta(1.5, 4, n)
    band         = rng.choice([0.0, 1.0], n, p=[0.55, 0.45])   # 2.4 slightly more common
    total_ap     = rng.beta(2, 5, n)        # most environments have < 15 APs

    X = np.column_stack([channel_occ, avg_rssi, max_rssi, adj_count, band, total_ap]).astype(np.float32)

    y = np.array([
        sinr_to_label(sinr_estimate(channel_occ[i], max_rssi[i], adj_count[i], band[i]))
        for i in range(n)
    ], dtype=np.int64)

    return X, y


# ── Generate dataset ──────────────────────────────────────────────────────────
print(f"Generating {N_SAMPLES:,} physics-derived samples…")
X, y = generate_samples(N_SAMPLES)
print(f"Label distribution: {np.bincount(y)}")
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)

# ── Train model ───────────────────────────────────────────────────────────────
clf = RandomForestClassifier(
    n_estimators=120,
    max_depth=8,
    min_samples_leaf=4,
    n_jobs=-1,
    random_state=42
)
print("Training InterferencePredictor…")
clf.fit(X_train, y_train)

# ── Evaluate ──────────────────────────────────────────────────────────────────
y_pred = clf.predict(X_test)
print("\nClassification report:")
print(classification_report(y_test, y_pred, target_names=["LOW", "MEDIUM", "HIGH", "SEVERE"]))

accuracy = (y_pred == y_test).mean()
print(f"Accuracy: {accuracy:.4f}")

# ── Save checkpoint ───────────────────────────────────────────────────────────
with open("interference_predictor.pkl", "wb") as f:
    pickle.dump(clf, f)
print("Saved: interference_predictor.pkl")

# ── Export to ONNX ────────────────────────────────────────────────────────────
initial_type = [("float_input", FloatTensorType([None, N_FEATURES]))]
onnx_model = convert_sklearn(clf, initial_types=initial_type, target_opset=17)

with open("interference_predictor.onnx", "wb") as f:
    f.write(onnx_model.SerializeToString())
print("Exported: interference_predictor.onnx")
print(f"  Size: {Path('interference_predictor.onnx').stat().st_size / 1024:.1f} KB")
print("\nRun export_all.py to quantize → int8 and copy to app assets.")
