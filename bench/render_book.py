"""Renders the cropped book shots (12/50/200MP, 200MP at ISO 12) with one fixed pipeline.

Usage: python bench/render_book.py <raw12> <raw50> <raw200> <raw200_iso12>  (files in captures/)
Writes captures/book/*.png.
"""
import os
import re
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "algos"))
import importlib  # noqa: E402
ALGO = importlib.import_module(os.environ.get("ALGO", "dir_port"))
from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007  # noqa: E402

CAP = os.path.join(HERE, "..", "captures")
OUT = os.path.join(CAP, "book", os.environ.get("ALGO", "dir_port"))


def load(name):
    m = re.search(r"_(\d+)x(\d+)_rs(\d+)\.(raw10|raw16)$", name)
    w, h, stride, kind = int(m[1]), int(m[2]), int(m[3]), m[4]
    rows = np.fromfile(os.path.join(CAP, name), np.uint8)[: stride * h].reshape(h, stride)
    if kind == "raw16":
        a = rows.view("<u2")[:, :w].astype(np.float32)
    else:
        p = rows[:, : w * 5 // 4].reshape(h, w // 4, 5).astype(np.uint16)
        a = np.stack([(p[..., i] << 2) | ((p[..., 4] >> (2 * i)) & 3) for i in range(4)], -1).reshape(h, w).astype(np.float32)
    return (a - 64) / (1023 - 64)


def meta(name):
    props = {}
    for line in open(os.path.join(CAP, "_".join(name.split("_")[:3]) + "_meta.txt")):
        k, _, v = line.strip().partition("=")
        props[k] = v
    r = lambda s: [eval(x) for x in re.findall(r"-?\d+/\d+", s)]
    return {"as_shot_neutral": r(props["asShotNeutral"]), "ccm": r(props["colorTransform1"]),
            "iso": int(props["iso"]), "exp": int(props["exposureTimeNs"])}


def render(rgb, m, gain):
    wb = 1 / np.array(m["as_shot_neutral"], np.float32)
    x = np.clip(rgb * wb * gain, 0, 1)
    lin = np.clip(x, 0, 1)
    s = np.where(lin <= 0.0031308, 12.92 * lin, 1.055 * np.power(lin, 1 / 2.4) - 0.055)
    img = Image.fromarray((s * 255 + .5).astype(np.uint8))
    return img.transpose(Image.Transpose.ROTATE_270)  # sensor -> upright (sensor orientation 90)


def main(n12, n50, n200, n200l):
    os.makedirs(OUT, exist_ok=True)
    shots = {}
    for key, name in [("12MP", n12), ("50MP", n50), ("200MP_iso100", n200), ("200MP_iso12_1s", n200l)]:
        raw, m = load(name), meta(name)
        rgb = ALGO.reconstruct(raw, m) if key.startswith("200") else \
            demosaicing_CFA_Bayer_Menon2007(np.clip(raw, 0, None), "GBRG").astype(np.float32)
        shots[key] = (raw, rgb, m)
        print(key, raw.shape, "iso", m["iso"], "exp ms", m["exp"] / 1e6, "raw mean", round(float(raw.mean()), 4),
              "p99.9", round(float(np.percentile(raw, 99.9)), 4))
    ref = shots["50MP"]
    wb = 1 / np.array(ref[2]["as_shot_neutral"], np.float32)
    gain = 0.9 / float(np.percentile((ref[1] * wb).mean(-1), 99))
    imgs = {}
    for key, (raw, rgb, m) in shots.items():
        # Equalise brightness by exposure x ISO so only detail and noise differ.
        k = (ref[2]["exp"] * ref[2]["iso"]) / (m["exp"] * m["iso"])
        imgs[key] = render(rgb * k, ref[2], gain)
        imgs[key].save(os.path.join(OUT, key + ".png"))
    W, H = imgs["200MP_iso100"].size
    for key in ("12MP", "50MP"):
        imgs[key].resize((W, H), Image.Resampling.LANCZOS).save(os.path.join(OUT, key + "_up.png"))
    print("200MP size", W, H)


if __name__ == "__main__":
    main(*sys.argv[1:5])
