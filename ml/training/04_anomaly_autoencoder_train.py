#!/usr/bin/env python3
"""
Expert 4 — Anomaly Detector Autoencoder Training
=================================================
Dataset: Self-supervised from user's own scan data.
  The autoencoder learns what THIS user's RF environment looks like
  during the first 10 baseline scans (collected by AnomalyDetector.kt).
  It is then used to detect deviations from that baseline.

  This script trains on synthetic baseline-like data to produce a
  reasonable starting-point model for the app assets. The AnomalyDetector
  calibrates its reconstruction error threshold per-user at runtime, so
  the model weights are secondary to the threshold calibration.

Architecture: 12 → 6 → 3 → 6 → 12 symmetric autoencoder
  - Input: 12-feature aggregate environment vector (see AnomalyDetector.kt)
  - Encoder: Linear(12,6) → ReLU → Linear(6,3) → ReLU
  - Decoder: Linear(3,6) → ReLU → Linear(6,12) → Sigmoid
  - Loss: MSE (reconstruction error)

Target: reconstruction error on normal samples < 0.01 MSE
        reconstruction error on anomalous samples > 3x normal

Run:
  python 04_anomaly_autoencoder_train.py
Produces:
  anomaly_autoencoder.onnx   (fp32, ~20 KB)
  anomaly_autoencoder.pt     (PyTorch weights checkpoint)
"""

import numpy as np
import torch
import torch.nn as nn
from pathlib import Path

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"Using device: {DEVICE}")

# ── Feature indices (must match AnomalyDetector.kt buildAggregateFeatures) ──
# 0   mean_rssi_norm        [0,1]
# 1   rssi_variance         [0,1]
# 2   ap_count_norm         total_aps / 50
# 3   open_ratio            fraction of open APs
# 4   wep_ratio
# 5   wpa_ratio
# 6   wpa2_ratio
# 7   wpa3_ratio
# 8   wps_ratio
# 9   ghz24_ratio
# 10  ghz5_ratio
# 11  known_bssid_ratio

N_FEATURES = 12
N_NORMAL   = 30_000
N_ANOMALOUS = 5_000  # used only for evaluation, not training

rng = np.random.default_rng(42)


def generate_normal_baseline(n: int) -> np.ndarray:
    """Generate realistic normal RF environment feature vectors."""
    mean_rssi = rng.beta(3, 2, n)          # typically -60 to -75 dBm range
    rssi_var  = rng.beta(1, 8, n)          # low variance in stable environments
    ap_count  = rng.beta(2, 4, n)          # most environments: 5–15 APs → /50

    # Encryption mix: realistic 2024 consumer environment
    wpa3_r  = rng.beta(1.5, 8, n)
    wpa2_r  = rng.beta(6, 2, n) * (1 - wpa3_r)
    wpa_r   = rng.beta(1, 10, n) * (1 - wpa3_r - wpa2_r)
    wep_r   = rng.beta(0.5, 20, n) * (1 - wpa3_r - wpa2_r - wpa_r)
    open_r  = np.clip(1 - wpa3_r - wpa2_r - wpa_r - wep_r, 0, 1)

    wps_r   = rng.beta(2, 6, n)
    ghz24_r = rng.beta(4, 3, n)
    ghz5_r  = 1 - ghz24_r
    known_r = rng.beta(8, 1, n)    # in baseline phase, all BSSIDs are known

    X = np.column_stack([
        mean_rssi, rssi_var, ap_count,
        open_r, wep_r, wpa_r, wpa2_r, wpa3_r,
        wps_r, ghz24_r, ghz5_r, known_r
    ])
    return X.astype(np.float32)


def generate_anomalous(n: int) -> np.ndarray:
    """Generate obviously anomalous feature vectors for evaluation."""
    samples = generate_normal_baseline(n)
    anomaly_type = rng.integers(0, 4, n)

    for i in range(n):
        t = anomaly_type[i]
        if t == 0:  # RSSI spike: new AP with very strong signal
            samples[i, 0] = rng.uniform(0.85, 1.0)  # unusually high RSSI norm
        elif t == 1:  # Encryption downgrade: open AP appears
            samples[i, 3] = rng.uniform(0.5, 0.9)   # open_ratio spikes
            samples[i, 6] = max(0, samples[i, 6] - 0.4)  # wpa2 drops
        elif t == 2:  # Unknown AP: known_bssid_ratio drops
            samples[i, 11] = rng.uniform(0.0, 0.3)
        elif t == 3:  # AP count drop: environment suddenly sparse
            samples[i, 2] = rng.uniform(0.0, 0.1)   # ap_count_norm very low
    return samples.astype(np.float32)


# ── Autoencoder definition ────────────────────────────────────────────────────

class Autoencoder(nn.Module):
    def __init__(self, input_dim: int = N_FEATURES):
        super().__init__()
        self.encoder = nn.Sequential(
            nn.Linear(input_dim, 6),
            nn.ReLU(),
            nn.Linear(6, 3),
            nn.ReLU()
        )
        self.decoder = nn.Sequential(
            nn.Linear(3, 6),
            nn.ReLU(),
            nn.Linear(6, input_dim),
            nn.Sigmoid()
        )

    def forward(self, x):
        return self.decoder(self.encoder(x))


# ── Dataset ───────────────────────────────────────────────────────────────────
X_normal    = torch.tensor(generate_normal_baseline(N_NORMAL)).to(DEVICE)
X_anomalous = torch.tensor(generate_anomalous(N_ANOMALOUS)).to(DEVICE)

dataset     = torch.utils.data.TensorDataset(X_normal)
loader      = torch.utils.data.DataLoader(dataset, batch_size=256, shuffle=True)

# ── Training ──────────────────────────────────────────────────────────────────
model     = Autoencoder().to(DEVICE)
optimizer = torch.optim.Adam(model.parameters(), lr=1e-3)
criterion = nn.MSELoss()

print("Training anomaly autoencoder…")
for epoch in range(80):
    model.train()
    total_loss = 0.0
    for (batch,) in loader:
        optimizer.zero_grad()
        reconstructed = model(batch)
        loss = criterion(reconstructed, batch)
        loss.backward()
        optimizer.step()
        total_loss += loss.item()
    if epoch % 10 == 0 or epoch == 79:
        avg = total_loss / len(loader)
        print(f"  Epoch {epoch:3d}: loss = {avg:.6f}")

# ── Evaluation ────────────────────────────────────────────────────────────────
model.eval()
with torch.no_grad():
    normal_recon    = model(X_normal[:1000])
    normal_mse      = criterion(normal_recon, X_normal[:1000]).item()

    anomalous_recon = model(X_anomalous)
    anomalous_mse   = criterion(anomalous_recon, X_anomalous).item()

print(f"\nNormal reconstruction MSE:    {normal_mse:.6f}")
print(f"Anomalous reconstruction MSE: {anomalous_mse:.6f}")
ratio = anomalous_mse / (normal_mse + 1e-9)
print(f"Separation ratio:             {ratio:.2f}x  (target ≥ 3x)")
if ratio < 3.0:
    print("WARNING: separation ratio < 3x — consider more training epochs or stronger anomalies.")

# ── Save PyTorch checkpoint ───────────────────────────────────────────────────
torch.save(model.state_dict(), "anomaly_autoencoder.pt")
print("Saved: anomaly_autoencoder.pt")

# ── Export to ONNX ────────────────────────────────────────────────────────────
model.eval()
dummy_input = torch.zeros(1, N_FEATURES).to(DEVICE)
torch.onnx.export(
    model,
    dummy_input,
    "anomaly_autoencoder.onnx",
    input_names=["env_features"],
    output_names=["reconstruction"],
    dynamic_axes={
        "env_features":  {0: "batch_size"},
        "reconstruction": {0: "batch_size"}
    },
    opset_version=17
)
print("Exported: anomaly_autoencoder.onnx")
print(f"  Size: {Path('anomaly_autoencoder.onnx').stat().st_size / 1024:.1f} KB")
print("\nRun export_all.py to quantize → int8 and copy to app assets.")
