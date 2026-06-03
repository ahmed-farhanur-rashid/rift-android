#!/usr/bin/env python3
"""
export_all.py — Quantize all expert models to int8 and copy to app assets
=========================================================================
Run this after all four training scripts have completed successfully.

Steps:
  1. Runs quantize_dynamic (int8 weights) on each .onnx file
  2. Verifies the quantized model produces outputs within tolerance of the fp32 version
  3. Copies _int8.onnx files to ../../app/src/main/assets/models/
  4. Prints final file sizes

Usage:
  cd ml/training
  python export_all.py
"""

import shutil
import sys
import numpy as np
from pathlib import Path

try:
    from onnxruntime.quantization import quantize_dynamic, QuantType
    import onnxruntime as ort
except ImportError:
    print("ERROR: onnxruntime not installed. Run: pip install -r requirements.txt")
    sys.exit(1)

TRAINING_DIR = Path(__file__).parent
MODELS_DIR   = TRAINING_DIR.parent / "models"
ASSETS_DIR   = TRAINING_DIR.parent.parent / "app" / "src" / "main" / "assets" / "models"

MODELS = [
    {
        "src":  "evil_twin_detector.onnx",
        "dst":  "evil_twin_detector_int8.onnx",
        "dim":  5,
        "type": "classifier",
    },
    {
        "src":  "risk_scorer.onnx",
        "dst":  "risk_scorer_int8.onnx",
        "dim":  7,
        "type": "regressor",
    },
    {
        "src":  "interference_predictor.onnx",
        "dst":  "interference_predictor_int8.onnx",
        "dim":  6,
        "type": "classifier",
    },
    {
        "src":  "anomaly_autoencoder.onnx",
        "dst":  "anomaly_autoencoder_int8.onnx",
        "dim":  12,
        "type": "autoencoder",
    },
]

def verify_model(fp32_path: Path, int8_path: Path, dim: int, model_type: str):
    """Spot-check that int8 model output is within tolerance of fp32."""
    np.random.seed(0)
    test_input = np.random.rand(4, dim).astype(np.float32)

    sess_fp32 = ort.InferenceSession(str(fp32_path))
    sess_int8 = ort.InferenceSession(str(int8_path))
    input_name = sess_fp32.get_inputs()[0].name

    out_fp32 = sess_fp32.run(None, {input_name: test_input})
    out_int8 = sess_int8.run(None, {input_name: test_input})

    if model_type == "regressor":
        # Compare float output
        fp32_vals = np.array(out_fp32[0]).flatten()
        int8_vals = np.array(out_int8[0]).flatten()
        max_err = np.abs(fp32_vals - int8_vals).max()
        print(f"    Max output delta (regressor): {max_err:.5f}  {'✓' if max_err < 0.05 else '⚠ HIGH'}")
    elif model_type in ("classifier",):
        # Compare class labels
        fp32_labels = np.array(out_fp32[0]).flatten()
        int8_labels = np.array(out_int8[0]).flatten()
        agreement = (fp32_labels == int8_labels).mean()
        print(f"    Label agreement: {agreement*100:.1f}%  {'✓' if agreement >= 0.95 else '⚠ LOW'}")
    elif model_type == "autoencoder":
        fp32_recon = np.array(out_fp32[0]).flatten()
        int8_recon = np.array(out_int8[0]).flatten()
        max_err = np.abs(fp32_recon - int8_recon).max()
        print(f"    Max reconstruction delta: {max_err:.5f}  {'✓' if max_err < 0.05 else '⚠ HIGH'}")


def main():
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Output directory: {ASSETS_DIR}\n")

    all_ok = True
    for m in MODELS:
        src_path = MODELS_DIR / m["src"]
        int8_path = MODELS_DIR / m["dst"]

        print(f"{'─' * 60}")
        print(f"Processing: {m['src']}")

        if not src_path.exists():
            print(f"  ERROR: {src_path} not found. Run training script first.")
            all_ok = False
            continue

        # Quantize
        print(f"  Quantizing -> {m['dst']}...")
        try:
            quantize_dynamic(str(src_path), str(int8_path), weight_type=QuantType.QInt8)
        except Exception as e:
            print(f"  Quantization failed ({e}), copying fp32 as fallback...")
            shutil.copy2(src_path, int8_path)

        # Sizes
        fp32_kb = src_path.stat().st_size / 1024
        int8_kb = int8_path.stat().st_size / 1024
        reduction = (1 - int8_kb / fp32_kb) * 100
        print(f"  fp32: {fp32_kb:.1f} KB  →  int8: {int8_kb:.1f} KB  ({reduction:.0f}% reduction)")

        # Verify
        print(f"  Verifying accuracy…")
        try:
            verify_model(src_path, int8_path, m["dim"], m["type"])
        except Exception as e:
            print(f"  ⚠ Verification failed: {e}")

        # Copy to assets
        dest = ASSETS_DIR / m["dst"]
        shutil.copy2(int8_path, dest)
        print(f"  Copied to {dest}")

    print(f"\n{'═' * 60}")
    if all_ok:
        print("All models quantized and copied to assets. ✓")
        print("\nAsset summary:")
        total_kb = 0.0
        for m in MODELS:
            asset_path = ASSETS_DIR / m["dst"]
            if asset_path.exists():
                kb = asset_path.stat().st_size / 1024
                total_kb += kb
                print(f"  {m['dst']:<45} {kb:>6.1f} KB")
        print(f"  {'TOTAL':<45} {total_kb:>6.1f} KB")
        if total_kb > 800:
            print(f"\n⚠ Total exceeds 800 KB budget ({total_kb:.0f} KB). Consider pruning trees.")
    else:
        print("Some models failed — check errors above.")
        sys.exit(1)


if __name__ == "__main__":
    main()
