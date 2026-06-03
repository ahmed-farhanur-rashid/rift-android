#!/usr/bin/env python3
"""
Expert 2 — Risk Scorer Training
================================
Dataset: Synthetic (rule-assisted generation).
  No labeled "risky AP" dataset exists because risk is context-relative.
  We generate 50,000 samples with ground-truth scores assigned by a
  deterministic rule function, then add Gaussian noise (σ=0.05) to
  encourage generalisation to edge cases the rule function handles cleanly.

  Rule function matches the Kotlin fallback in RiskScorer.kt so the model
  and the fallback are consistent on the training distribution.

Model: sklearn.ensemble.GradientBoostingRegressor
  Input dim:  7
  Output:     float [0,1]

Run:
  python 02_risk_scorer_train.py
Produces:
  risk_scorer.onnx   (fp32, ~200 KB)
  risk_scorer.pkl    (optional sklearn checkpoint)
"""

import numpy as np
import pickle
from pathlib import Path
from sklearn.ensemble import GradientBoostingRegressor
from sklearn.model_selection import train_test_split
from sklearn.metrics import mean_squared_error, mean_absolute_error
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

# ── Feature indices ───────────────────────────────────────────────────────────
# 0  encryption_score    [0.0 open → 1.0 WPA3]
# 1  has_wps             {0, 1}
# 2  uses_tkip           {0, 1}
# 3  vendor_risk_score   [0, 1] from OUI table
# 4  rssi_norm           (rssi + 95) / 65
# 5  rssi_variance       normalised RSSI variance [0,1]
# 6  is_hidden           {0, 1}

N_FEATURES = 7
N_SAMPLES  = 50_000

rng = np.random.default_rng(42)


def rule_score(enc_score, has_wps, uses_tkip, vendor_risk, rssi_norm, rssi_var, is_hidden):
    """
    Deterministic risk score from observable AP properties.
    Matches RiskScorer.kt ruleBasedScore() exactly.
    """
    base = 1.0 - enc_score
    base += has_wps * 0.15
    base += uses_tkip * 0.10
    base += vendor_risk * 0.10
    base += rssi_var * 0.05
    base += is_hidden * 0.05
    return np.clip(base, 0.0, 1.0)


def generate_dataset(n: int) -> tuple[np.ndarray, np.ndarray]:
    # Encryption score: empirical mix — more WPA2 than open/WEP in real scans
    enc_choices = np.array([0.0, 0.1, 0.3, 0.5, 0.75, 1.0])
    enc_weights = np.array([0.10, 0.05, 0.05, 0.15, 0.55, 0.10])
    enc_score = enc_choices[rng.choice(len(enc_choices), n, p=enc_weights)]

    has_wps     = rng.binomial(1, 0.25, n).astype(float)
    uses_tkip   = rng.binomial(1, 0.15, n).astype(float)
    vendor_risk = rng.uniform(0.1, 0.7, n)
    rssi_norm   = rng.beta(4, 2, n)      # most APs between -60 and -75 dBm
    rssi_var    = rng.beta(1.5, 8, n)    # most are stable; rare unstable APs
    is_hidden   = rng.binomial(1, 0.08, n).astype(float)

    X = np.column_stack([enc_score, has_wps, uses_tkip, vendor_risk, rssi_norm, rssi_var, is_hidden])

    # Compute ground-truth scores with per-sample noise (σ=0.05)
    y_clean = rule_score(enc_score, has_wps, uses_tkip, vendor_risk, rssi_norm, rssi_var, is_hidden)
    noise   = rng.normal(0, 0.05, n)
    y       = np.clip(y_clean + noise, 0.0, 1.0)

    return X.astype(np.float32), y.astype(np.float32)


# ── Generate dataset ──────────────────────────────────────────────────────────
print(f"Generating {N_SAMPLES:,} synthetic training samples…")
X, y = generate_dataset(N_SAMPLES)
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

# ── Train model ───────────────────────────────────────────────────────────────
reg = GradientBoostingRegressor(
    n_estimators=150,
    max_depth=4,
    learning_rate=0.05,
    subsample=0.8,
    random_state=42
)
print("Training RiskScorer…")
reg.fit(X_train, y_train)

# ── Evaluate ──────────────────────────────────────────────────────────────────
y_pred = reg.predict(X_test)
mse = mean_squared_error(y_test, y_pred)
mae = mean_absolute_error(y_test, y_pred)
print(f"\nTest MSE: {mse:.5f}   MAE: {mae:.5f}")

# Correlation with rule function (should be > 0.97 — model is learning the rule)
rule_pred = rule_score(X_test[:,0], X_test[:,1], X_test[:,2],
                       X_test[:,3], X_test[:,4], X_test[:,5], X_test[:,6])
corr = np.corrcoef(rule_pred, y_pred)[0, 1]
print(f"Correlation with rule function: {corr:.4f}")
if corr < 0.95:
    print("WARNING: low correlation — model may not have converged. Try more estimators.")

MODELS_DIR = Path(__file__).parent.parent / "models"
MODELS_DIR.mkdir(parents=True, exist_ok=True)

# ── Save sklearn checkpoint ───────────────────────────────────────────────────
pkl_path = MODELS_DIR / "risk_scorer.pkl"
with open(pkl_path, "wb") as f:
    pickle.dump(reg, f)
print(f"Saved: {pkl_path}")

# ── Export to ONNX ────────────────────────────────────────────────────────────
initial_type = [("float_input", FloatTensorType([None, N_FEATURES]))]
onnx_model = convert_sklearn(reg, initial_types=initial_type, target_opset=17)

onnx_path = MODELS_DIR / "risk_scorer.onnx"
with open(onnx_path, "wb") as f:
    f.write(onnx_model.SerializeToString())
print(f"Exported: {onnx_path}")
print(f"  Size: {onnx_path.stat().st_size / 1024:.1f} KB")
print("\nRun export_all.py to quantize → int8 and copy to app assets.")
