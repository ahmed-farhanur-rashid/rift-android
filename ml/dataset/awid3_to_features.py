#!/usr/bin/env python3
"""
AWID3 -> RIFT Evil Twin Feature Converter
==========================================
Converts raw AWID3 packet-level CSV captures into the 5-feature format
expected by 01_evil_twin_train.py.

AWID3 evil twin captures contain raw 802.11 frames with 254 columns.
This script:
  1. Groups packets by BSSID to build per-AP summaries
  2. Identifies AP pairs sharing the same SSID (evil twin pattern)
  3. Computes the 5 ML features for each pair
  4. Adds synthetic legitimate-pair negatives for balanced training

Output columns:
  bssid_similarity, oui_match, encryption_delta, rssi_diff, band_mismatch, label

Usage:
  cd ml/dataset
  python awid3_to_features.py
"""

import sys
import csv
from pathlib import Path
from collections import defaultdict

import numpy as np

SCRIPT_DIR = Path(__file__).parent
DATASET_DIR = SCRIPT_DIR / "awid3"
OUTPUT_FILE = SCRIPT_DIR / "awid3_evil_twin_features.csv"


def normalized_edit_distance(a, b):
    if a == b:
        return 0.0
    if not a or not b:
        return 1.0
    m, n = len(a), len(b)
    dp = list(range(n + 1))
    for i in range(1, m + 1):
        prev, dp[0] = dp[0], i
        for j in range(1, n + 1):
            temp = dp[j]
            if a[i - 1] == b[j - 1]:
                dp[j] = prev
            else:
                dp[j] = 1 + min(prev, dp[j], dp[j - 1])
            prev = temp
    return dp[n] / max(m, n)


def oui_prefix(bssid):
    clean = bssid.replace(":", "").replace("-", "").upper()
    return clean[:6] if len(clean) >= 6 else ""


def freq_to_band(freq):
    try:
        f = int(float(freq))
    except (ValueError, TypeError):
        return "unknown"
    if 2400 <= f <= 2500:
        return "2.4"
    elif 5150 <= f <= 5850:
        return "5"
    elif 5925 <= f <= 7125:
        return "6"
    return "unknown"


def parse_csv_file(filepath):
    """Parse a single AWID3 CSV file. Returns dict of bssid -> ap_info."""
    aps = {}
    print(f"  Parsing {filepath.name}...")

    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
        reader = csv.DictReader(f)

        for row in reader:
            # Use wlan.sa (source address) not wlan.bssid — in beacon/probe frames
            # wlan.bssid only shows one AP, but wlan.sa shows the actual transmitter
            bssid = (row.get("wlan.sa") or row.get("wlan.bssid") or "").strip()
            if not bssid or bssid in ("00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff"):
                continue

            ssid = (row.get("wlan.ssid") or "").strip()

            try:
                rssi = float(row.get("radiotap.dbm_antsignal") or
                             row.get("wlan_radio.signal_dbm") or "-70")
            except (ValueError, TypeError):
                rssi = -70

            try:
                freq = int(float(row.get("wlan_radio.frequency") or
                                 row.get("radiotap.channel.freq") or "2412"))
            except (ValueError, TypeError):
                freq = 2412

            protected = (row.get("wlan.fc.protected") or "0").strip()
            ess = (row.get("wlan.fixed.capabilities.ess") or "0").strip()
            subtype = (row.get("wlan.fc.subtype") or "").strip()

            if bssid not in aps:
                aps[bssid] = {
                    "bssid": bssid,
                    "ssids": set(),
                    "rssi_values": [],
                    "freq": freq,
                    "protected_count": 0,
                    "total_beacons": 0,
                    "packet_count": 0,
                }

            ap = aps[bssid]
            if ssid:
                ap["ssids"].add(ssid)
            ap["rssi_values"].append(rssi)
            ap["packet_count"] += 1
            if protected == "1":
                ap["protected_count"] += 1
            if ess == "1" or subtype == "8":  # beacon frame subtype
                ap["total_beacons"] += 1

    print(f"    Found {len(aps)} unique BSSIDs")
    return aps


def estimate_encryption_score(ap):
    """Estimate encryption score from packet-level flags."""
    total = max(ap["packet_count"], 1)
    protected_ratio = ap["protected_count"] / total

    if protected_ratio > 0.8:
        return 0.75  # WPA2-PSK equivalent
    elif protected_ratio > 0.5:
        return 0.50  # WPA2-TKIP or mixed
    elif protected_ratio > 0.1:
        return 0.30  # WPA1
    else:
        return 0.0   # Open network


def build_pair_features(ap_a, ap_b):
    mac_a = ap_a["bssid"].replace(":", "").replace("-", "").upper()
    mac_b = ap_b["bssid"].replace(":", "").replace("-", "").upper()

    bssid_sim = normalized_edit_distance(mac_a, mac_b)
    oui_match = 1.0 if oui_prefix(ap_a["bssid"]) == oui_prefix(ap_b["bssid"]) else 0.0

    enc_a = estimate_encryption_score(ap_a)
    enc_b = estimate_encryption_score(ap_b)
    enc_delta = abs(enc_a - enc_b)

    mean_rssi_a = np.mean(ap_a["rssi_values"]) if ap_a["rssi_values"] else -70
    mean_rssi_b = np.mean(ap_b["rssi_values"]) if ap_b["rssi_values"] else -70
    rssi_diff = abs(mean_rssi_a - mean_rssi_b) / 65.0

    band_a = freq_to_band(ap_a["freq"])
    band_b = freq_to_band(ap_b["freq"])
    band_mismatch = 0.0 if band_a == band_b else 1.0

    return [
        min(max(bssid_sim, 0.0), 1.0),
        oui_match,
        min(max(enc_delta, 0.0), 1.0),
        min(max(rssi_diff, 0.0), 1.0),
        band_mismatch,
    ]


def main():
    csv_files = sorted(DATASET_DIR.glob("*.csv"))
    if not csv_files:
        print(f"ERROR: No CSV files found in {DATASET_DIR}")
        print(f"  Download evil twin captures first:")
        print(f'  kaggle datasets download suumia/awid3-dataset -f "CSV/12.Evil_Twin/Evil_Twin_0.csv" '
              f'-p {DATASET_DIR} --unzip')
        sys.exit(1)

    print(f"Found {len(csv_files)} CSV files in {DATASET_DIR}")

    all_aps = {}
    for csv_file in csv_files:
        file_aps = parse_csv_file(csv_file)
        for bssid, ap_info in file_aps.items():
            if bssid not in all_aps:
                all_aps[bssid] = ap_info
            else:
                existing = all_aps[bssid]
                existing["rssi_values"].extend(ap_info["rssi_values"])
                existing["ssids"].update(ap_info["ssids"])
                existing["protected_count"] += ap_info["protected_count"]
                existing["total_beacons"] += ap_info["total_beacons"]
                existing["packet_count"] += ap_info["packet_count"]

    print(f"\nTotal unique BSSIDs across all files: {len(all_aps)}")

    aps_by_ssid = defaultdict(list)
    for bssid, ap in all_aps.items():
        for ssid in ap["ssids"]:
            if ssid:
                aps_by_ssid[ssid].append(ap)

    print(f"SSIDs with 2+ APs: {len(aps_by_ssid)}")
    for ssid, aps in aps_by_ssid.items():
        print(f"  '{ssid}': {len(aps)} APs -> {len(aps) * (len(aps) - 1) // 2} pairs")

    evil_twin_features = []
    for ssid, aps in aps_by_ssid.items():
        if len(aps) < 2:
            continue
        for i in range(len(aps)):
            for j in range(i + 1, len(aps)):
                feat = build_pair_features(aps[i], aps[j])
                feat.append(1)  # evil twin label
                evil_twin_features.append(feat)

    print(f"\nEvil twin feature vectors: {len(evil_twin_features)}")

    rng = np.random.default_rng(42)
    n_legit = max(len(evil_twin_features), 200)
    legit_features = []
    for _ in range(n_legit):
        bssid_sim = float(rng.beta(1.5, 8))
        oui_match = float(rng.binomial(1, 0.85))
        enc_delta = float(rng.beta(1, 20))
        rssi_diff = float(rng.beta(2, 5))
        band_mis = float(rng.binomial(1, 0.30))
        legit_features.append([bssid_sim, oui_match, enc_delta, rssi_diff, band_mis, 0])

    all_features = evil_twin_features + legit_features
    rng.shuffle(all_features)

    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_FILE, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["bssid_similarity", "oui_match", "encryption_delta",
                         "rssi_diff", "band_mismatch", "label"])
        for row in all_features:
            writer.writerow([f"{v:.6f}" for v in row])

    print(f"\nSaved {len(all_features)} feature vectors to {OUTPUT_FILE}")
    print(f"  Evil twin (real AWID3): {len(evil_twin_features)}")
    print(f"  Legitimate pairs (synthetic): {len(legit_features)}")
    print(f"\nNext: cd ../training && python 01_evil_twin_train.py")


if __name__ == "__main__":
    main()
