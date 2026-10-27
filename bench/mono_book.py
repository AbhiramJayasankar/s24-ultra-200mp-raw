"""Black-and-white render of RAW crops without demosaicing (neutral subject only).

Every CFA/16-cell position is divided by its mean over blank paper, which removes colour
filter and per-pixel sensitivity differences. On black print on white paper the result is
the luminance image at full sensor resolution: the most detail the sensor recorded.

Usage: python bench/mono_book.py out.png name period [name period ...]   (files in captures/)
period = 8 for the 200MP 16-cell frame, 2 for Bayer frames. Images are upscaled to the
200MP crop size with nearest neighbour so pixels stay visible.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from render_book import CAP, load  # noqa: E402

# Blank paper in upright display coordinates of the 200MP crop (x0, y0, x1, y1).
PAPER = (250, 1290, 500, 1400)


def mono(name, period, scale):
    raw = load(name)
    up = np.array(Image.fromarray(raw).transpose(Image.Transpose.ROTATE_270))
    x0, y0, x1, y1 = (v // scale for v in PAPER)
    flat = np.ones((period, period), np.float32)
    # Phases are taken in sensor coordinates, so map display indices back first.
    H, W = raw.shape
    ys, xs = np.mgrid[0:H, 0:W]
    py = np.array(Image.fromarray((ys % period).astype(np.uint8)).transpose(Image.Transpose.ROTATE_270))
    px = np.array(Image.fromarray((xs % period).astype(np.uint8)).transpose(Image.Transpose.ROTATE_270))
    patch, pyp, pxp = up[y0:y1, x0:x1], py[y0:y1, x0:x1], px[y0:y1, x0:x1]
    for a in range(period):
        for b in range(period):
            m = (pyp == a) & (pxp == b)
            flat[a, b] = np.median(patch[m])
    y = up / flat[py, px]
    y = y / np.percentile(y, 99)
    s = np.clip(y, 0, 1) ** (1 / 2.2)
    img = Image.fromarray((s * 255 + .5).astype(np.uint8))
    return img.resize((img.width * scale, img.height * scale), Image.Resampling.NEAREST)


if __name__ == "__main__":
    out, args = sys.argv[1], sys.argv[2:]
    for name, period in zip(args[::2], args[1::2]):
        period = int(period)
        scale = {2: 1, 8: 1}[period]
        w = int(name.split("_")[-2].split("x")[0])
        scale = 1680 // w
        im = mono(name, period, scale)
        im.save(os.path.join(CAP, "book", out.replace(".png", "_" + name.split("_")[0] + "_" + name.split("_")[2] + ".png")))
        print("saved", im.size)
