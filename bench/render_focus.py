"""Renders the focused book crop (200MP at 1.56 dioptres) with a chosen algorithm and
writes pixel-level and same-size comparison crops against 50MP.

Usage: python bench/render_focus.py algo [algo ...]   -> captures/book/focus/<algo>*.png
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "algos"))
import importlib  # noqa: E402

os.environ.setdefault("ALGO", "dir_port")
import render_book as rb  # noqa: E402

OUT = os.path.join(rb.CAP, "book", "focus")
N50 = "cam56_sm40_1793045871818_crop3440_2600_840x1120_rs1050.raw10"
N200 = os.environ.get("N200", "cam20_sm39_1793046511176_crop6880_5200_1680x2240_rs2100.raw10")
# (x0, y0, x1, y1) in upright 200MP-crop coordinates of the 1.56 shot; 50MP offset (+8, +5) at 50MP scale.
CROPS = {"fsc": (540, 1040, 840, 1240), "price": (1050, 1220, 1250, 1320), "logo": (1380, 30, 1700, 330),
         "quote": (700, 760, 1100, 960), "red": (690, 290, 1090, 450), "carton": (60, 120, 460, 420)}
SHIFT50 = (8, 5)


def ref50():
    from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007
    raw, m = rb.load(N50), rb.meta(N50)
    rgb = demosaicing_CFA_Bayer_Menon2007(np.clip(raw, 0, None), "GBRG").astype(np.float32)
    wb = 1/np.array(m["as_shot_neutral"], np.float32)
    return rgb, m, 0.9/float(np.percentile((rgb*wb).mean(-1), 99))


def main(algos):
    os.makedirs(OUT, exist_ok=True)
    rgb50, m50, gain = ref50()
    img50 = rb.render(rgb50, m50, gain)
    raw, m = rb.load(N200), rb.meta(N200)
    k = (m50["exp"]*m50["iso"])/(m["exp"]*m["iso"])
    font = ImageFont.truetype("arial.ttf", 28)
    for name in algos:
        algo = importlib.import_module(name)
        img = rb.render(algo.reconstruct(raw, m)*k, m50, gain)
        name = name + os.environ.get("TAG", "")
        img.save(os.path.join(OUT, f"{name}_full.png"))
        for cname, (x0, y0, x1, y1) in CROPS.items():
            # Pixel view: 3x nearest so each 200MP pixel is visible; 50MP shown at the same
            # field of view (6x nearest) and Lanczos-upscaled (how a viewer would scale it).
            a = img.crop((x0, y0, x1, y1)).resize(((x1-x0)*3, (y1-y0)*3), Image.Resampling.NEAREST)
            sx, sy = SHIFT50
            b50 = img50.crop((x0//2+sx, y0//2+sy, x1//2+sx, y1//2+sy))
            b = b50.resize(a.size, Image.Resampling.LANCZOS)
            tiles = [(f"200MP {os.environ.get('LABEL', name)} (3x pixels)", a), ("50MP (Lanczos to same size)", b)]
            sheet = Image.new("RGB", (a.width*2+10, a.height+40), (20, 20, 20))
            for i, (label, t) in enumerate(tiles):
                sheet.paste(t, (i*(a.width+10), 40))
                ImageDraw.Draw(sheet).text((i*(a.width+10)+8, 6), label, fill=(240, 240, 240), font=font)
            sheet.save(os.path.join(OUT, f"{name}_{cname}.png"))
        print("done", name)


if __name__ == "__main__":
    main(sys.argv[1:])
