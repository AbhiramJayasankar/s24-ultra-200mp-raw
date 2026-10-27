"""Side-by-side detail comparison of 12MP / 50MP / 200MP DNGs of the same scene.

Usage: python tools/compare.py <dir with main_12mp.dng main_50mp.dng main_200mp.dng>

Every file is developed with identical LibRaw settings (camera white balance, AHD demosaic,
fixed brightness, no auto-brightening). The 12MP and 50MP images are aligned to the 200MP
one by phase correlation, then crops are cut at the 200MP's native scale; the lower
resolutions are upscaled (Lanczos) to the same size so the eye compares real detail.
"""
import os
import sys

import numpy as np
import rawpy
from PIL import Image, ImageDraw, ImageFont

D = sys.argv[1]
NAMES = [("12mp", "12MP binned", 4), ("50mp", "50MP", 2), ("200mp", "200MP", 1)]
CROP = 560  # px at 200MP scale


def signal(path):
    """Median raw level above black, used to equalise brightness between files."""
    with rawpy.imread(path) as r:
        return float(np.median(r.raw_image[::5, ::5])) - float(np.mean(r.black_level_per_channel))


def develop(path, bright):
    with rawpy.imread(path) as r:
        return r.postprocess(use_camera_wb=True, no_auto_bright=True, bright=bright,
                             demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD, output_bps=8)


def gray(a):
    return a.astype(np.float32).mean(axis=2)


def shift(ref, img):
    """(dy, dx) that moves img onto ref, by phase correlation."""
    f = np.fft.fft2(ref - ref.mean()) * np.conj(np.fft.fft2(img - img.mean()))
    c = np.fft.ifft2(f / (np.abs(f) + 1e-9)).real
    dy, dx = np.unravel_index(np.argmax(c), c.shape)
    h, w = c.shape
    return (dy - h if dy > h // 2 else dy), (dx - w if dx > w // 2 else dx)


imgs = {}
ref_signal = signal(os.path.join(D, "main_200mp.dng"))
for key, _, _ in NAMES:
    path = os.path.join(D, f"main_{key}.dng")
    bright = float(os.environ.get("BRIGHT", "2.2")) * ref_signal / signal(path)
    imgs[key] = develop(path, bright)
    print(key, imgs[key].shape, "bright", round(bright, 2))

big = imgs["200mp"]
H, W = big.shape[:2]
# Align on a 1/8-scale grid of the 200MP frame.
ref = gray(np.asarray(Image.fromarray(big).resize((W // 8, H // 8), Image.BILINEAR)))
offsets = {"200mp": (0, 0)}
for key, _, f in NAMES[:2]:
    a = imgs[key]
    small = gray(np.asarray(Image.fromarray(a).resize((W // 8, H // 8), Image.BILINEAR)))
    dy, dx = shift(ref, small)
    offsets[key] = (dy * 8 / f, dx * 8 / f)  # in the image's own pixels
    print(key, "offset (own px)", offsets[key])

# Overview with crop boxes.
regions = [tuple(map(int, s.split(","))) for s in (sys.argv[2:] or [])]
if not regions:
    regions = [(int(H * 0.40), int(W * 0.30)), (int(H * 0.55), int(W * 0.62)), (int(H * 0.22), int(W * 0.52))]
ov = Image.fromarray(big).resize((W // 10, H // 10), Image.BILINEAR)
d = ImageDraw.Draw(ov)
for i, (y, x) in enumerate(regions):
    d.rectangle([x // 10, y // 10, (x + CROP) // 10, (y + CROP) // 10], outline=(217, 164, 65), width=3)
    d.text((x // 10 + 4, y // 10 + 4), str(i + 1), fill=(217, 164, 65))
ov.save(os.path.join(D, "overview.jpg"), quality=90)

try:
    font = ImageFont.truetype("arial.ttf", 26)
except OSError:
    font = ImageFont.load_default()
for i, (y, x) in enumerate(regions):
    tiles = []
    for key, label, f in NAMES:
        oy, ox = offsets[key]
        a = imgs[key]
        y0, x0 = int(round(y / f - oy)), int(round(x / f - ox))
        c = a[max(0, y0):y0 + CROP // f, max(0, x0):x0 + CROP // f]
        t = Image.fromarray(c).resize((CROP, CROP), Image.LANCZOS if f > 1 else Image.NEAREST)
        canvas = Image.new("RGB", (CROP, CROP + 40), (0, 0, 0))
        canvas.paste(t, (0, 40))
        ImageDraw.Draw(canvas).text((8, 6), label + (f"  (upscaled {f}x)" if f > 1 else "  (100%)"),
                                    fill=(236, 232, 223), font=font)
        tiles.append(canvas)
    row = Image.new("RGB", (CROP * 3 + 16, CROP + 40), (0, 0, 0))
    for j, t in enumerate(tiles):
        row.paste(t, (j * (CROP + 8), 0))
    row.save(os.path.join(D, f"compare_{i + 1}.png"))
    print("wrote", f"compare_{i + 1}.png")
