#!/usr/bin/env python3
"""
AWID3 -> RIFT Feature Converter
================================
Converts raw AWID3 evil twin capture data into the 5-feature format
expected by 01_evil_twin_train.py.

AWID3 has 254 packet-level features. This script groups packets by BSSID,
identifies AP pairs sharing the same SSID, and computes:

  [0] bssid_similarity   Levenshtein(mac_A, mac_B) / 17, range [0,1]
  [1] oui_match          1 if same OUI prefix, 0 if not
  [2] encryption_delta   |encScore_A - encScore_B|
  [3] rssi_diff          |rssi_A - rssi_B| / 65, range [0,1]
  [4] band_mismatch      1 if different bands, 0 if same
  [5] label              0 = legitimate pair, 1 = evil twin pair

Usage:
  python awid3_to_features.py

Input:
  ml/dataset/awid3/Evil_Twin.csv  (and optionally Normal.csv)

Output:
  ml/training/awid3_evil_twin_features.csv
"""

import sys
import csv
import os
from pathlib import Path
from collections import defaultdict

import numpy as np

SCRIPT_DIR = Path(__file__).parent  # ml/dataset/
DATASET_DIR = SCRIPT_DIR / "awid3"  # ml/dataset/awid3/
OUTPUT_FILE = SCRIPT_DIR.parent / "training" / "awid3_evil_twin_features.csv"  # ml/training/

SSID_CANDIDATES = ["wlan.ssid", "ssid", "wlan_mgt.ssid", "dot11.wlan.ssid"]
BSSID_CANDIDATES = ["wlan.sa", "wlan.bssid", "bssid", "wlan.ta", "dot11.wlan.sa"]
RSSI_CANDIDATES = ["radiotap.dbm_antsignal", "wlan_radio.signal_dbm", "rssi", "signal_dbm"]
FREQ_CANDIDATES = ["radiotap.channel.freq", "wlan_radio.channel.freq", "channel_freq", "channel"]
WPA_CANDIDATES = ["wlan_mgt.rsn.akms", "wlan.rsn.akms", "akms", "wlan.rsn.version"]
WEP_CANDIDATES = ["wlan_mgt.fixed.capabilities.privacy", "wlan.fc.protected"]


def find_column(headers, candidates):
    lower_headers = [h.lower().strip() for h in headers]
    for c in candidates:
        for i, h in enumerate(lower_headers):
            if c.lower() in h:
                return headers[i]
    return None


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
        f = int(freq)
    except (ValueError, TypeError):
        return "unknown"
    if 2400 <= f <= 2500:
        return "2.4"
    elif 5150 <= f <= 5850:
        return "5"
    elif 5925 <= f <= 7125:
        return "6"
    return "unknown"


def encryption_score(row, caps_col, wep_col):
    caps_val = str(row.get(caps_col, "")).upper() if caps_col else ""
    wep_val = str(row.get(wep_col, "")).strip() if wep_col else ""

    if "SAE" in caps_val or "OWE" in caps_val:
        return 1.0
    if "WPA2" in caps_val or "RSN" in caps_val:
        if "TKIP" in caps_val:
            return 0.50
        return 0.75
    if "WPA-" in caps_val and "WPA2" not in caps_val:
        return 0.30
    if "WEP" in caps_val:
        return 0.10
    if wep_val in ("1", "true", "True"):
        return 0.10
    return 0.0


def parse_csv_file(filepath):
    packets_by_bssid = defaultdict(list)
    print(f"  Parsing {filepath.name}...")

    try:
        with open(filepath, "r", encoding="utf-8", errors="replace") as f:
            reader = csv.DictReader(f)
            if reader.fieldnames is None:
                print(f"  WARNING: Empty or unreadable file {filepath.name}")
                return packets_by_bssid

            headers = list(reader.fieldnames)
            ssid_col = find_column(headers, SSID_CANDIDATES)
            bssid_col = find_column(headers, BSSID_CANDIDATES)
            rssi_col = find_column(headers, RSSI_CANDIDATES)
            freq_col = find_column(headers, FREQ_CANDIDATES)
            caps_col = find_column(headers, WPA_CANDIDATES)
            wep_col = find_column(headers, WEP_CANDIDATES)

            print(f"    Columns: ssid={ssid_col}, bssid={bssid_col}, rssi={rssi_col}, "
                  f"freq={freq_col}, caps={caps_col}")

            if not bssid_col:
                print(f"  ERROR: No BSSID column found in {filepath.name}")
                print(f"    Available columns: {headers[:20]}...")
                return packets_by_bssid

            count = 0
            for row in reader:
                bssid = row.get(bssid_col, "").strip()
                if not bssid or bssid == "00:00:00:00:00:00":
                    continue

                ssid = row.get(ssid_col, "").strip() if ssid_col else ""
                try:
                    rssi = int(float(row.get(rssi_col, "-70") or "-70"))
                except (ValueError, TypeError):
                    rssi = -70
                freq = row.get(freq_col, "2412") if freq_col else "2412"
                enc_score = encryption_score(row, caps_col, wep_col)

                packets_by_bssid[bssid].append({
                    "ssid": ssid,
                    "rssi": rssi,
                    "frequencyMhz": freq,
                    "encryptionScore": enc_score,
                })
                count += 1

            print(f"    Read {count} packets, {len(packets_by_bssid)} unique BSSIDs")
    except Exception as e:
        print(f"  ERROR reading {filepath.name}: {e}")

    return packets_by_bssid


def aggregate_ap(packets):
    ssid = packets[0]["ssid"]
    rssi_values = [p["rssi"] for p in packets]
    freq = packets[0]["frequencyMhz"]
    enc_scores = [p["encryptionScore"] for p in packets]

    return {
        "ssid": ssid,
        "meanRssi": sum(rssi_values) / len(rssi_values) if rssi_values else -70,
        "frequencyMhz": freq,
        "encryptionScore": max(set(enc_scores), key=enc_scores.count),
        "packetCount": len(packets),
    }


def build_pair_features(ap_a, ap_b):
    mac_a = ap_a["bssid"].replace(":", "").replace("-", "").upper()
    mac_b = ap_b["bssid"].replace(":", "").replace("-", "").upper()

    bssid_sim = normalized_edit_distance(mac_a, mac_b)
    oui_match = 1.0 if oui_prefix(ap_a["bssid"]) == oui_prefix(ap_b["bssid"]) else 0.0
    enc_delta = abs(ap_a["encryptionScore"] - ap_b["encryptionScore"])
    rssi_diff = abs(ap_a["meanRssi"] - ap_b["meanRssi"]) / 65.0
    band_a = freq_to_band(ap_a["frequencyMhz"])
    band_b = freq_to_band(ap_b["frequencyMhz"])
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
        print(f"  Download AWID3 evil twin data first:")
        print(f"    kaggle datasets download suumia/awid3-dataset -f Evil_Twin.csv "
              f"-p {DATASET_DIR} --unzip")
        sys.exit(1)

    print(f"Found {len(csv_files)} CSV file(s) in {DATASET_DIR}")
    all_bssid_packets = defaultdict(list)

    for csv_file in csv_files:
        packets = parse_csv_file(csv_file)
        for bssid, pkt_list in packets.items():
            all_bssid_packets[bssid].extend(pkt_list)

    print(f"\nTotal unique BSSIDs across all files: {len(all_bssid_packets)}")

    aps_by_ssid = defaultdict(list)
    for bssid, packets in all_bssid_packets.items():
        ap = aggregate_ap(packets)
        ap["bssid"] = bssid
        aps_by_ssid[ap["ssid"]].append(ap)

    multi_ap_ssids = {ssid: aps for ssid, aps in aps_by_ssid.items() if len(aps) >= 2}
    print(f"SSIDs with 2+ APs (potential evil twin pairs): {len(multi_ap_ssids)}")

    features = []
    for ssid, aps in multi_ap_ssids.items():
        for i in range(len(aps)):
            for j in range(i + 1, len(aps)):
                feat = build_pair_features(aps[i], aps[j])
                feat.append(1)
                features.append(feat)

    if not features:
        print("\nWARNING: No AP pairs found. Generating supplementary synthetic pairs...")
        rng = np.random.default_rng(42)
        for ssid, aps in aps_by_ssid.items():
            if len(aps) == 1:
                ap = aps[0]
                synthetic_mac = ap["bssid"][:8] + ":AA:BB:CC"
                feat = build_pair_features(
                    ap,
                    {
                        "bssid": synthetic_mac,
                        "meanRssi": ap["meanRssi"] + rng.normal(0, 3),
                        "frequencyMhz": ap["frequencyMhz"],
                        "encryptionScore": max(0, ap["encryptionScore"] - 0.3),
                    }
                )
                feat.append(1)
                features.append(feat)

    print(f"Total feature vectors: {len(features)}")

    if not features:
        print("ERROR: No features extracted. Check your AWID3 CSV file format.")
        sys.exit(1)

    rng = np.random.default_rng(42)
    n_legit = max(len(features) // 2, 100)
    legit_features = []
    for _ in range(n_legit):
        bssid_sim = float(rng.beta(1.5, 8))
        oui_match = float(rng.binomial(1, 0.85))
        enc_delta = float(rng.beta(1, 20))
        rssi_diff = float(rng.beta(2, 5))
        band_mis = float(rng.binomial(1, 0.30))
        legit_features.append([bssid_sim, oui_match, enc_delta, rssi_diff, band_mis, 0])

    all_features = features + legit_features
    rng.shuffle(all_features)

    with open(OUTPUT_FILE, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["bssid_similarity", "oui_match", "encryption_delta",
                         "rssi_diff", "band_mismatch", "label"])
        for row in all_features:
            writer.writerow([f"{v:.6f}" for v in row])

    print(f"\nSaved {len(all_features)} feature vectors to {OUTPUT_FILE}")
    print(f"  Real AWID3 pairs: {len(features)}")
    print(f"  Synthetic legitimate pairs: {len(n_legit)}")
    print(f"  Total: {len(all_features)}")
    print(f"\nRun: python 01_evil_twin_train.py")


if __name__ == "__main__":
    main()
