"""Quick look at a packed RAW10 / RAW16 dump from the probe.

Usage: python tools/raw_preview.py <file.raw10|file.raw16> [--cfa GBRG] [--black 64] [--white 1023]

The file name carries WxH and the row stride (..._8160x6120_rs10240.raw10).
Writes <file>.preview.jpg (half-resolution demosaic, gray-world white balance, gamma 2.2)
and prints per-channel statistics.
"""
import argparse
import re
import sys

import numpy as np
from PIL import Image


def load(path):
    m = re.search(r"_(\d+)x(\d+)_rs(\d+)\.(raw10|raw16)$", path)
    if not m:
        sys.exit("file name must end with _WxH_rsSTRIDE.raw10|raw16")
    w, h, stride, kind = int(m[1]), int(m[2]), int(m[3]), m[4]
    buf = np.fromfile(path, dtype=np.uint8)
    rows = buf[: stride * h].reshape(h, stride)
    if kind == "raw16":
        return rows.view("<u2")[:, :w].astype(np.uint16)
    # RAW10: every 5 bytes hold 4 pixels; bytes 0-3 are the high 8 bits, byte 4 the low 2 bits each.
    packed = rows[:, : w * 5 // 4].reshape(h, w // 4, 5).astype(np.uint16)
    out = np.empty((h, w // 4, 4), dtype=np.uint16)
    for i in range(4):
        out[:, :, i] = (packed[:, :, i] << 2) | ((packed[:, :, 4] >> (2 * i)) & 3)
    return out.reshape(h, w)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--cfa", default="GBRG")
    ap.add_argument("--black", type=float, default=64)
    ap.add_argument("--white", type=float, default=1023)
    a = ap.parse_args()

    img = load(a.path).astype(np.float32)
    print(f"{a.path}: {img.shape[1]}x{img.shape[0]}, min {img.min():.0f} max {img.max():.0f} mean {img.mean():.1f}")
    planes = {}
    for idx, ch in enumerate(a.cfa):
        y, x = divmod(idx, 2)
        planes.setdefault(ch, []).append(img[y::2, x::2])
    r = planes["R"][0]
    g = (planes["G"][0] + planes["G"][1]) / 2
    b = planes["B"][0]
    rgb = np.stack([r, g, b], axis=-1)
    rgb = np.clip((rgb - a.black) / (a.white - a.black), 0, 1)
    means = rgb.reshape(-1, 3).mean(axis=0)
    print("channel means (R,G,B) after black level:", np.round(means, 4))
    rgb *= means[1] / np.maximum(means, 1e-6)
    rgb *= 0.25 / max(np.percentile(rgb[..., 1], 50), 1e-6)
    rgb = np.clip(rgb, 0, 1) ** (1 / 2.2)
    out = Image.fromarray((rgb * 255).astype(np.uint8))
    out.thumbnail((2048, 2048))
    dst = a.path + ".preview.jpg"
    out.save(dst, quality=90)
    print("wrote", dst, out.size)


if __name__ == "__main__":
    main()
