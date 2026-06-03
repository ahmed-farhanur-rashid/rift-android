# RIFT — ML Training Scripts

This directory contains the training pipeline for RIFT's four on-device ML experts.
All inference runs locally on Android via ONNX Runtime Mobile (int8 quantized).
**No model weights are trained on-device; the `.onnx` files are trained here and committed.**

## Directory Structure

```
ml/
├── dataset/              ← Datasets & feature converters
│   ├── awid3/            ← Raw AWID3 CSV captures (gitignored)
│   ├── awid3_to_features.py
│   └── awid3_evil_twin_features.csv
├── models/               ← Trained model artifacts (gitignored)
│   ├── *.pkl             ← sklearn checkpoints
│   ├── *.onnx            ← fp32 ONNX exports
│   └── *.pt              ← PyTorch checkpoints
└── training/             ← Training scripts (this folder)
    ├── 01_evil_twin_train.py
    ├── 02_risk_scorer_train.py
    ├── 03_interference_train.py
    ├── 04_anomaly_autoencoder_train.py
    └── export_all.py
```

## Setup

```bash
cd ml/training
python -m venv venv
source venv/bin/activate   # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

Requires Python ≥ 3.10. GPU optional (Expert 4 trains in < 2 min on CPU).

## Expert Scripts

| Script | Expert | Framework | Dataset |
|--------|--------|-----------|---------|
| `01_evil_twin_train.py` | EvilTwinDetector | scikit-learn GBC | AWID3 + synthetic |
| `02_risk_scorer_train.py` | RiskScorer | scikit-learn GBR | Synthetic |
| `03_interference_train.py` | InterferencePredictor | scikit-learn RFC | Physics-derived |
| `04_anomaly_autoencoder_train.py` | AnomalyDetector | PyTorch autoencoder | Synthetic baseline |

Run each script independently:
```bash
python 01_evil_twin_train.py
python 02_risk_scorer_train.py
python 03_interference_train.py
python 04_anomaly_autoencoder_train.py
```

## Expert 1 — AWID3 Dataset (Optional but Recommended)

The evil twin detector uses synthetic data by default but significantly improves
with real labeled data from the AWID3 dataset.

1. Register at https://icsdweb.aegean.gr/awid/awid3 (free, academic use)
2. Download Evil Twin capture files to `ml/dataset/awid3/`
3. Run `python ml/dataset/awid3_to_features.py` to extract features
4. The training script auto-detects and uses the generated CSV

Without AWID3, accuracy on synthetic data is ~95% but real-world performance
on unseen environments may be lower. The Kotlin fallback rule-based logic
provides the same accuracy on the training distribution.

## Quantize and Deploy

After all four scripts complete:

```bash
python export_all.py
```

This will:
1. Read `.onnx` files from `ml/models/`
2. Quantize all to int8 via `quantize_dynamic`
3. Verify accuracy within tolerance (< 5% max delta vs fp32)
4. Save `_int8.onnx` files to `ml/models/` and copy to `app/src/main/assets/models/`
5. Print a size summary (target: < 800 KB total)

## Asset Placement

```
app/src/main/assets/
├── models/
│   ├── evil_twin_detector_int8.onnx
│   ├── risk_scorer_int8.onnx
│   ├── interference_predictor_int8.onnx
│   └── anomaly_autoencoder_int8.onnx
└── oui.csv
```

## Performance Targets

| Metric | Target |
|--------|--------|
| Per-expert inference | < 5ms (Cortex-A55) |
| Total MoE evaluation | < 20ms per 1.5s scan cycle |
| Model asset total | < 800 KB |
| Evil twin accuracy | ≥ 90% (with AWID3) |
| Risk scorer MSE | < 0.005 |
| Autoencoder separation | ≥ 3x anomalous/normal MSE ratio |

## Fault Tolerance

All four experts are wrapped in try/catch in `MoEGate.kt`. If any expert throws
(including if the `.onnx` asset is missing), the app continues fully functional
using the rule-based fallbacks built into each Kotlin expert class.

This means you can ship the app before training is complete — the Kotlin fallbacks
produce sensible results while you iterate on the models.

## Licensing

All training dependencies are MIT/BSD/Apache-2.0 licensed and **not** shipped in the APK.
Only the `.onnx` model files enter the APK. ONNX Runtime Android is MIT licensed.
