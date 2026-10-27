"""12 vs 50 vs 200MP comparison of the book crop, each at its own best focus.

All three are rendered with one pipeline at equal brightness, scaled to the same field of
view with Lanczos (no blocky pixels for any of them), aligned to the 200MP frame by phase
correlation, and cut into the same detail crops.

Usage: python bench/compare_sizes.py <raw12> <raw50> <raw200>   (names in captures/)
Writes captures/book/sizes/*.png
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "algos"))
os.environ.setdefault("ALGO", "dir_port")
import render_book as rb  # noqa: E402
import dir_port  # noqa: E402
import lumdir  # noqa: E402
from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007  # noqa: E402
from skimage.registration import phase_cross_correlation  # noqa: E402

lumdir.CHROMA_SIGMA = 1.0
OUT = os.path.join(rb.CAP, "book", os.environ.get("SIZES_OUT", "sizes"))
ZOOM = 2  # display zoom over 200MP pixels
CROPS = {"fsc": (540, 1040, 840, 1240), "price": (1000, 1200, 1300, 1330), "logo": (1380, 30, 1700, 330),
         "quote": (680, 760, 1100, 960), "red": (690, 290, 1090, 450), "carton": (60, 120, 460, 420)}


def bin50(raw):
    """2x2 same-colour binning of a Bayer frame: what the sensor does on-chip for 12MP."""
    h, w = raw.shape
    out = np.empty((h//2, w//2), np.float32)
    for py in (0, 1):
        for px in (0, 1):
            plane = raw[py::2, px::2]
            hh, ww = plane.shape[0]//2*2, plane.shape[1]//2*2
            out[py::2, px::2] = plane[:hh, :ww].reshape(hh//2, 2, ww//2, 2).mean(axis=(1, 3))
    return out


def bayer(name):
    if name.startswith("BIN50:"):
        name = name[6:]
        raw, m = bin50(rb.load(name)), rb.meta(name)
        return demosaicing_CFA_Bayer_Menon2007(np.clip(raw, 0, None), "GBRG").astype(np.float32), m
    raw, m = rb.load(name), rb.meta(name)
    return demosaicing_CFA_Bayer_Menon2007(np.clip(raw, 0, None), "GBRG").astype(np.float32), m


def gray(img):
    return np.asarray(img.convert("L"), np.float32)


def main(n12, n50, n200, n12b=None):
    os.makedirs(OUT, exist_ok=True)
    rgb50, m50 = bayer(n50)
    wb = 1/np.array(m50["as_shot_neutral"], np.float32)
    gain = 0.9/float(np.percentile((rgb50*wb).mean(-1), 99))
    imgs = {}
    raw200, m200 = rb.load(n200), rb.meta(n200)
    k = lambda m: (m50["exp"]*m50["iso"])/(m["exp"]*m["iso"])
    imgs["200MP new"] = rb.render(lumdir.reconstruct(raw200, m200)*k(m200), m50, gain)
    imgs["200MP old"] = rb.render(dir_port.reconstruct(raw200, m200)*k(m200), m50, gain)
    size = imgs["200MP new"].size
    extra = (("12MP binned", n12b, 4),) if n12b else ()
    for label, name, f in (("50MP", n50, 2), ("12MP", n12, 4)) + extra:
        rgb, m = bayer(name)
        im = rb.render(rgb*k(m), m50, gain).resize(size, Image.Resampling.LANCZOS)
        shift, _, _ = phase_cross_correlation(gray(imgs["200MP new"]), gray(im), upsample_factor=4)
        dy, dx = (float(v) for v in shift)
        im = im.transform(size, Image.Transform.AFFINE, (1, 0, -dx, 0, 1, -dy), Image.Resampling.BICUBIC)
        imgs[label] = im
        print(label, "aligned by", round(dx, 1), round(dy, 1))
    order = ["12MP", "50MP", "200MP old", "200MP new"] + (["12MP binned"] if n12b else [])
    for name in order:
        imgs[name].save(os.path.join(OUT, name.replace(" ", "_")+".png"))
    font = ImageFont.truetype("arial.ttf", 30)
    for cname, (x0, y0, x1, y1) in CROPS.items():
        w, h = (x1-x0)*ZOOM, (y1-y0)*ZOOM
        tiles = [imgs[n].crop((x0, y0, x1, y1)).resize((w, h), Image.Resampling.LANCZOS) for n in order]
        wide = w > 700
        cols = 2 if wide else 4
        rows = (len(order)+cols-1)//cols
        sheet = Image.new("RGB", (cols*(w+10), rows*(h+44)), (20, 20, 20))
        for i, (n, t) in enumerate(zip(order, tiles)):
            x, y = (i % cols)*(w+10), (i//cols)*(h+44)
            sheet.paste(t, (x, y+44))
            ImageDraw.Draw(sheet).text((x+8, y+6), n, fill=(240, 240, 240), font=font)
        sheet.save(os.path.join(OUT, f"sizes_{cname}.png"))


if __name__ == "__main__":
    main(*sys.argv[1:5])
