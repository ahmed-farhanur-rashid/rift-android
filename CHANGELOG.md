# Changelog

All notable changes to RIFT will be documented in this file.

## [1.1.1] - 2026-06-04

### Fixed

- **WiFi scanning on Android 13+**: Added missing `NEARBY_WIFI_DEVICES` permission that was preventing scan results on modern devices.
- **Passive-only scanning**: Added active `WifiManager.startScan()` requests (throttled to every 30s) to keep the OS scan cache fresh while respecting Android's 4-calls/2-min limit.
- **AnomalyDetector baseline persistence**: Baseline now resets between scanning sessions, preventing stale location data from carrying over and producing incorrect anomaly results.
- **InterferencePredictor filtering**: Fixed redundant condition that was incorrectly filtering out MEDIUM severity results.
- **InterferencePredictor band detection**: Changed from using first AP's band to majority vote across all APs for accurate 2.4/5 GHz classification.
- **RiskScorer array destructuring**: Added bounds check to prevent `IndexOutOfBoundsException` when feature array length is unexpected.
- **Results risk scoring**: Now uses the latest RSSI reading per BSSID instead of the first-encountered, giving more accurate risk assessment.
- **HeatmapRenderer performance**: Activated point binning for scans with >100 points to reduce IDW computation time.

### Added

- **Quick Threat Scan**: Standalone threat assessment mode accessible from the home screen. Scan for 5-60 seconds with selectable experts (Evil Twin, Risk Scoring, Interference Analysis). Shows immediate threat report without requiring a full scanning session.
- **WiFi Network Selection**: Filter which networks to include in threat analysis during scanning. Picker modal shows all detected APs with checkboxes, highlights your connected network, and supports "My Network" / "All" / "None" quick selections.
- **Floor Plan Optional Mode**: Skip floor plan upload during setup. Scanning works with a blank dark canvas with grid overlay. Heatmap renders directly without needing a blueprint image.
- **Multiple WiFi Source Placement**: Place virtual WiFi sources (repeaters, access points) on the map after scanning. Supports two coverage models:
  - **Free-space path loss**: Physics-based signal prediction showing how signal decays with distance from each source.
  - **Hybrid**: Combines real scan data with synthetic predictions in areas you didn't walk.
- **Connected WiFi Info**: `WifiScanEngine` now exposes `getConnectedWifi()` returning SSID, BSSID, RSSI, frequency, and link speed of the currently connected network.
- **SourcePlacementScreen**: New UI for tap-to-place WiFi sources with edit/delete, TX power configuration, and frequency selection.
- **SourcePlacementViewModel**: Manages source CRUD operations with Room database persistence.
- **WifiSourceEntity**: New Room entity for persisting placed WiFi sources with session linkage.
- **WifiSourceDao**: DAO for WiFi source CRUD operations.

### Changed

- **HomeScreen**: Added "Quick Threat Scan" button and updated "How it works" tips to reflect new features.
- **BlueprintSetupScreen**: Floor plan selection now includes "Skip — Use Blank Canvas" option with explanatory text.
- **ScanningScreen**: Top bar now includes WiFi filter button; shows grid background when no floor plan is loaded.
- **ResultsScreen**: Added "Place Sources" button in top bar for accessing source placement.
- **Permission Request Screen**: Now shows per-permission explanations including Android 13+ requirements.

### Database

- Schema version bumped from 2 to 3.
- Added `wifi_sources` table with foreign key to `sessions`.
- Uses `fallbackToDestructiveMigration()` (development mode).

---

## [1.0.0] - 2026-05-15

### Added

- Initial release.
- Floor plan upload and calibration.
- PDR indoor positioning with step detection and heading.
- Live WiFi heatmap overlay on blueprint.
- Mixture-of-Experts ML pipeline (Evil Twin, Risk Scoring, Interference, Anomaly Detection).
- Post-session heatmap viewer with Signal/Risk mode toggle.
- Session history with delete.
- Foreground service for background scanning.
- Heatmap export to PNG.
