"""Runs MBR variants on the focused book crop and writes zoom sheets of the problem spots.
Usage: python bench/run_mbr.py name LAM_Y LAM_C [ITERS]"""
import os, sys, pickle, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); sys.path.insert(0, os.path.join(HERE, "algos"))
os.environ.setdefault("ALGO", "dir_port")
import numpy as np, render_book as rb, mbr
from PIL import Image, ImageDraw, ImageFont
name, ly, lc = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
mbr.LAM_Y, mbr.LAM_C, mbr.VERBOSE = ly, lc, True
mbr.LAM_HF = float(os.environ.get("LAM_HF", mbr.LAM_HF)); mbr.EPS_Y = float(os.environ.get("EPS_Y", mbr.EPS_Y)); mbr.EPS_C = float(os.environ.get("EPS_C", mbr.EPS_C))
if len(sys.argv) > 4: mbr.ITERS = int(sys.argv[4])
red = os.path.join(rb.CAP, "book", "red")
raw = np.load(os.path.join(red, "raw200.npy")); m, m50, gain = pickle.load(open(os.path.join(red, "meta.pkl"), "rb"))
init = np.load(os.environ["INIT"]) if os.environ.get("INIT") else None
t = time.time(); out = mbr.reconstruct(raw, m, init); print("seconds", round(time.time()-t, 1))
im = rb.render(out, m50, gain); im.save(os.path.join(red, f"mbr_{name}.png"))
views = [("50MP", Image.open(os.path.join(red, "50MP.png"))), ("lumdir", Image.open(os.path.join(red, "200MP_lumdir.png"))),
         ("blockdir v1", Image.open(os.path.join(red, "200MP_blockdir_final.png"))), (f"MBR {name}", im)]
font = ImageFont.truetype("arial.ttf", 22)
for zn, box in {"paper": (1500, 60, 1620, 160), "bars": (1150, 1225, 1230, 1290), "We": (780, 350, 900, 430)}.items():
    z = 6; W, H = (box[2]-box[0])*z, (box[3]-box[1])*z
    sh = Image.new("RGB", (2*W+10, 2*(H+32)), (20, 20, 20))
    for i, (l, v) in enumerate(views):
        x, y = (i % 2)*(W+10), (i//2)*(H+32)
        sh.paste(v.crop(box).resize((W, H), Image.Resampling.NEAREST), (x, y+32)); ImageDraw.Draw(sh).text((x+6, y+4), l, fill=(240, 240, 240), font=font)
    if zn == "paper":
        a = np.asarray(sh, np.float32); a = np.clip((a-a.mean())*4+128, 0, 255); sh = Image.fromarray(a.astype(np.uint8))
    sh.save(os.path.join(red, f"mbr_{name}_{zn}.png"))
