# RIFT — RF Intelligence & Threat Framework

An Android application for indoor RF environment mapping with on-device ML-powered threat analysis.

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white) ![Python](https://img.shields.io/badge/Python-3776AB?logo=python&logoColor=white) ![Gradle](https://img.shields.io/badge/build-Gradle-02303A?logo=gradle&logoColor=white) ![Android](https://img.shields.io/badge/Android-API%2029%2B-3DDC84?logo=android&logoColor=white) ![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white) ![Hilt](https://img.shields.io/badge/DI-Hilt-brightgreen) ![Room](https://img.shields.io/badge/DB-Room-orange) ![ONNX Runtime](https://img.shields.io/badge/ML-ONNX%20Runtime-005CED?logo=onnx&logoColor=white) ![GitHub Actions](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?logo=githubactions&logoColor=white) ![License](https://img.shields.io/badge/license-MIT-brightgreen)

---

## What it does

RIFT combines pedestrian dead-reckoning (PDR) indoor positioning with passive WiFi scanning to build spatial heatmaps of signal strength and RF risk. While walking a floor plan, the app collects WiFi scan data at each position, renders a live heatmap overlay on a blueprint, and runs a **Mixture-of-Experts (MoE)** ML pipeline to surface security observations about the RF environment.

## Architecture

```
RIFT
├── core/
│   ├── data/          Room database — sessions, scan points, AP readings, threat reports
│   ├── ml/            MoE ML layer
│   │   ├── ThreatReport.kt        — data classes for all expert outputs
│   │   ├── CapabilitiesParser.kt  — IEEE 802.11 capabilities string parser
│   │   ├── RssiTracker.kt         — per-BSSID RSSI ring buffer (size 10)
│   │   ├── FeatureVector.kt       — normalised [0,1] feature extraction
│   │   ├── OuiLookup.kt           — IEEE OUI vendor name resolution (CSV asset)
│   │   ├── OnnxModel.kt           — base class for ONNX Runtime sessions
│   │   ├── MoEGate.kt             — routes scans to experts, assembles ThreatReport
│   │   └── experts/
│   │       ├── EvilTwinDetector.kt     — duplicate-SSID impersonation detection
│   │       ├── RiskScorer.kt           — per-AP continuous risk score [0,1]
│   │       ├── InterferencePredictor.kt — channel interference severity (4-class)
│   │       └── AnomalyDetector.kt      — self-supervised baseline + deviation detection
│   ├── positioning/   PDR engine + IDW heatmap renderer (signal & risk palettes)
│   └── scanner/       WifiScanEngine + foreground service
├── ui/
│   ├── scanning/      Live scanning screen — heatmap + shield threat indicator
│   └── results/       Post-session heatmap viewer — signal/risk map toggle
├── di/                Hilt DI modules (Database, ML auto-wired)
└── ml/training/       Python training scripts for all four ONNX experts
```

## ML Layer — Mixture of Experts

Each scan cycle (~1.5 s) the `MoEGate` routes the observed AP list through up to four experts:

| Expert | Gate condition | Model | Fallback |
|--------|----------------|-------|----------|
| EvilTwinDetector | ≥2 APs share an SSID | GradientBoostingClassifier | Rule-based MAC/encryption check |
| RiskScorer | Always | GradientBoostingRegressor | Weighted encryption/WPS formula |
| InterferencePredictor | Always | RandomForestClassifier | SINR threshold rules |
| AnomalyDetector | Always; returns null until 10-scan baseline | PyTorch autoencoder + z-score | Z-score only |

All models run locally via **ONNX Runtime Mobile** (int8 quantized, MIT licensed). Total asset budget < 800 KB. Per-expert inference < 5 ms on Cortex-A55.

### Training

```bash
cd ml/training
pip install -r requirements.txt
python 01_evil_twin_train.py
python 02_risk_scorer_train.py
python 03_interference_train.py
python 04_anomaly_autoencoder_train.py
python export_all.py         # quantizes to int8, copies to assets/
```

See `ml/training/README.md` for AWID3 dataset integration instructions (Expert 1).

## Building

```bash
./gradlew assembleDebug
```

Minimum SDK: 26 (Android 8.0). Target SDK: 35. Java 17.

The app ships with **rule-based fallbacks for all four experts** — it is fully functional without the trained `.onnx` model files. The fallbacks use the same logic as the training data generators and produce consistent results.

## OUI Vendor Lookup

`app/src/main/assets/oui.csv` ships with the top ~45 consumer AP vendors. For full IEEE OUI coverage (~35,000 entries), replace with the official file:

```bash
curl -L https://standards-oui.ieee.org/oui/oui.csv \
  -o app/src/main/assets/oui.csv
```

## Threat Panel

During a scan session, tap the **shield icon** (top-right) to open the live threat analysis panel. The shield tint reflects the current overall risk level:

- Grey — no analysis yet
- Green — SAFE
- Yellow — CAUTION (anomalies or moderate interference)
- Orange — WARNING (high-risk AP or high-confidence evil twin)
- Red — CRITICAL (encryption downgrade or high-confidence evil twin ≥ 80%)

## Risk Map

In the Results screen, toggle between **Signal** and **Risk Map** views. The risk map renders the same IDW spatial interpolation as the signal heatmap, but colours locations by the MoE risk scores of APs visible from that position (green = low risk, red = high risk).

## Database Schema

Room database `rift.db` at schema version 2:

| Table | Description |
|-------|-------------|
| `sessions` | Scanning sessions with blueprint URI and metadata |
| `scan_points` | GPS-free positions (PDR x/y in meters + pixel coords) |
| `ap_readings` | Raw WiFi scan results per scan point |
| `threat_reports` | Summarised MoE output per scan point |

## Permissions

| Permission | Purpose |
|-----------|---------|
| `ACCESS_FINE_LOCATION` | Required for WiFi scanning on Android 9+ |
| `NEARBY_WIFI_DEVICES` | Android 13+ alternative to location for WiFi |
| `ACTIVITY_RECOGNITION` | Step counter for PDR |
| `FOREGROUND_SERVICE_LOCATION` | Background scan continuity |

## Licence

MIT. ONNX Runtime Mobile: MIT. scikit-learn, PyTorch, skl2onnx: BSD/Apache. AWID3 dataset: academic use only (see dataset licence).
