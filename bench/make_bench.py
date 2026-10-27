"""Cuts the carton benchmark crops out of the full-frame probe dumps.

Writes bench/data/{raw200,raw50,raw12}.npy (uint16, black level NOT subtracted) and
bench/data/meta.json. Crop origins are multiples of 8 at 200MP scale, so the 16-cell phase
and the 2x2 Bayer phase of the smaller frames are preserved. All three frames are aligned
(same scene, phone on a fixed mount, offsets measured as zero).
"""
import json
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
CAP = os.path.join(HERE, "..", "captures")
OUT = os.path.join(HERE, "data")

FRAMES = {
    "raw200": ("cam20_sm39_1792964542276_16320x12240_rs20400.raw10", 1),
    "raw50": ("cam56_sm40_1792964517851_8160x6120_rs10240.raw10", 2),
    "raw12": ("cam0_sm40_1792964499288_4080x3060_rs8160.raw16", 4),
}
# Crop in 200MP pixels: carton with its text and barcode.
Y0, X0, H, W = 5696, 6976, 896, 2048


def load(path):
    m = re.search(r"_(\d+)x(\d+)_rs(\d+)\.(raw10|raw16)$", path)
    w, h, stride, kind = int(m[1]), int(m[2]), int(m[3]), m[4]
    buf = np.fromfile(path, dtype=np.uint8)
    rows = buf[: stride * h].reshape(h, stride)
    if kind == "raw16":
        return rows.view("<u2")[:, :w].astype(np.uint16)
    packed = rows[:, : w * 5 // 4].reshape(h, w // 4, 5).astype(np.uint16)
    out = np.empty((h, w // 4, 4), dtype=np.uint16)
    for i in range(4):
        out[:, :, i] = (packed[:, :, i] << 2) | ((packed[:, :, 4] >> (2 * i)) & 3)
    return out.reshape(h, w)


def rationals(s):
    return [eval(x) for x in re.findall(r"-?\d+/\d+", s)]


def main():
    os.makedirs(OUT, exist_ok=True)
    meta = {"crop_200mp": [Y0, X0, H, W]}
    for key, (name, f) in FRAMES.items():
        a = load(os.path.join(CAP, name))
        c = a[Y0 // f:(Y0 + H) // f, X0 // f:(X0 + W) // f].copy()
        np.save(os.path.join(OUT, key + ".npy"), c)
        props = {}
        for line in open(os.path.join(CAP, name.split("_" + name.split("_")[3])[0] + "_meta.txt")):
            k, _, v = line.strip().partition("=")
            props[k] = v
        meta[key] = {
            "shape": list(c.shape),
            "black": 64, "white": 1023,
            "exposure_ns": int(props["exposureTimeNs"]), "iso": int(props["iso"]),
            "as_shot_neutral": rationals(props["asShotNeutral"]),
            "ccm": rationals(props["ccm"]),
        }
        print(key, c.shape, c.dtype, "median", int(np.median(c)))
    meta["layout"] = {
        "raw200": "16-cell: 8x8 super-cell, 4x4 blocks G B / R G (rows 0-3: G cols 0-3, B cols 4-7; rows 4-7: R cols 0-3, G cols 4-7)",
        "raw50": "Bayer GBRG (row 0: G B, row 1: R G)",
        "raw12": "Bayer GBRG, 2x2 binned",
    }
    json.dump(meta, open(os.path.join(OUT, "meta.json"), "w"), indent=2)


if __name__ == "__main__":
    sys.exit(main())
