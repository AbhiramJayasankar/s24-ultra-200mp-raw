"""Benchmark harness for 200MP 16-cell reconstruction on the carton scene.

An algorithm is a module bench/algos/<name>.py with

    def reconstruct(raw, meta) -> np.ndarray   # float32 (H, W, 3), camera RGB, linear

where `raw` is the 200MP crop as float32 with black level removed and scaled so that
1.0 = white level (values may be slightly negative from noise), and `meta` is
meta.json["raw200"] plus meta["layout"]. Output must be the same H x W as the input,
in camera RGB (NOT white-balanced, NOT colour-corrected): the harness renders every
result with one fixed pipeline so only the reconstruction differs.

Usage:  python bench/harness.py ratio_baseline my_algo ...    (writes bench/out/)
"""
import importlib.util
import json
import os
import sys
import time

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
OUT = os.path.join(HERE, "out")
META = json.load(open(os.path.join(DATA, "meta.json")))

# Regions in 200MP crop coordinates (y0, y1, x0, x1), sensor orientation (text runs vertically).
REGIONS = {
    "text": (180, 700, 640, 1600),      # small print on the carton
    "barcode": (250, 660, 1600, 1840),  # black/white bars: any colour here is an artifact
    "flat": (770, 890, 120, 1800),      # floor below the carton: noise and grid patterns
    "carton": (100, 760, 60, 1900),
}
SHEET_CROPS = {"text": (300, 620, 900, 1220), "barcode": (300, 620, 1560, 1880), "logo": (520, 760, 220, 460)}


def load(key):
    m = META[key]
    a = np.load(os.path.join(DATA, key + ".npy")).astype(np.float32)
    return (a - m["black"]) / (m["white"] - m["black"])


def bayer_reference(key):
    """Menon (2007) demosaic of a GBRG Bayer crop, camera RGB linear."""
    from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007
    a = load(key)
    return demosaicing_CFA_Bayer_Menon2007(np.clip(a, 0, None), "GBRG").astype(np.float32)


_WB = 1.0 / np.array(META["raw50"]["as_shot_neutral"], dtype=np.float32)
_CCM = np.array(META["raw50"]["ccm"], dtype=np.float32).reshape(3, 3)


def to_srgb_linear(rgb_cam, gain=1.0):
    # Clip in white-balanced camera space first so blown highlights render white, not pink.
    x = np.clip(rgb_cam * _WB * gain, 0, 1)
    return np.clip(x @ _CCM.T, 0, 1)


def encode(lin):
    return np.where(lin <= 0.0031308, 12.92 * lin, 1.055 * np.power(lin, 1 / 2.4) - 0.055)


def exposure_gain(rgb_cam, target=0.18):
    """Brightness so the floor (outside the carton) renders mid-grey; fixed per frame type."""
    y0, y1, x0, x1 = REGIONS["flat"]
    s = to_srgb_linear(rgb_cam[y0:y1, x0:x1])
    return target / max(float(np.median(s.mean(axis=2))), 1e-6)


def luma(lin):
    return lin @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)


def down2(x):
    h, w = x.shape[0] // 2 * 2, x.shape[1] // 2 * 2
    return x[:h, :w].reshape(h // 2, 2, w // 2, 2, *x.shape[2:]).mean(axis=(1, 3))


def up(img, f):
    if f == 1:
        return img
    chans = [np.asarray(Image.fromarray(img[..., c].astype(np.float32), mode="F")
                        .resize((img.shape[1] * f, img.shape[0] * f), Image.LANCZOS)) for c in range(3)]
    return np.stack(chans, axis=-1)


def hf_energy(y, band=(0.25, 0.5)):
    """Mean spectral power of a luma patch between band[0] and band[1] cycles/pixel."""
    y = y - y.mean()
    win = np.hanning(y.shape[0])[:, None] * np.hanning(y.shape[1])[None, :]
    F = np.abs(np.fft.fftshift(np.fft.fft2(y * win))) ** 2
    fy = np.fft.fftshift(np.fft.fftfreq(y.shape[0]))[:, None]
    fx = np.fft.fftshift(np.fft.fftfreq(y.shape[1]))[None, :]
    r = np.hypot(fy, fx)
    return float(F[(r >= band[0]) & (r < band[1])].mean() / y.size)


def grid_score(y):
    """Power at the 16-cell periods (1/8 and 1/4 cycles/px) relative to nearby frequencies."""
    y = y - y.mean()
    F = np.abs(np.fft.fft2(y)) ** 2
    h, w = F.shape
    peaks, around = [], []
    for fy, fx in [(0, 1 / 8), (1 / 8, 0), (0, 1 / 4), (1 / 4, 0), (1 / 8, 1 / 8)]:
        iy, ix = int(round(fy * h)) % h, int(round(fx * w)) % w
        peaks.append(F[iy, ix])
        around.append(np.median(F[(iy - 3) % h:(iy - 3) % h + 7, (ix - 3) % w:(ix - 3) % w + 7]))
    return float(np.mean(np.array(peaks) / (np.array(around) + 1e-12)))


def metrics(rgb200, ref50_lin, gain):
    lin = to_srgb_linear(rgb200, gain)
    out = {}
    # Fidelity: the 200MP result averaged 2x2 should match the real 50MP frame.
    y0, y1, x0, x1 = REGIONS["carton"]
    a = encode(down2(lin)[y0 // 2:y1 // 2, x0 // 2:x1 // 2])
    b = encode(ref50_lin[y0 // 2:y1 // 2, x0 // 2:x1 // 2])
    mse = float(np.mean((a - b) ** 2))
    out["psnr_vs_50mp"] = round(10 * np.log10(1 / max(mse, 1e-12)), 2)
    # Detail: fine-scale luma energy on the text minus the same measure on the plain floor (noise).
    y0, y1, x0, x1 = REGIONS["text"]
    ty = encode(luma(lin[y0:y1, x0:x1]))
    y0, y1, x0, x1 = REGIONS["flat"]
    fy = encode(luma(lin[y0:y1, x0:x1]))
    out["detail_hf"] = round(1e4 * hf_energy(ty), 3)
    out["noise_hf"] = round(1e4 * hf_energy(fy), 3)
    out["detail_minus_noise"] = round(out["detail_hf"] - out["noise_hf"], 3)
    # Mid-frequency detail (0.12-0.25 c/px): beyond what 50MP can resolve after 2x upscaling.
    out["detail_mid"] = round(1e4 * hf_energy(ty, (0.12, 0.25)), 3)
    # Fringing: colour on the black-and-white barcode.
    y0, y1, x0, x1 = REGIONS["barcode"]
    bc = encode(lin[y0:y1, x0:x1])
    out["barcode_chroma"] = round(float(np.mean(np.abs(bc - bc.mean(axis=2, keepdims=True)))), 4)
    out["grid"] = round(grid_score(fy), 2)
    return out


def sheet(named, path):
    font = ImageFont.load_default()
    rows = []
    for cname, (y0, y1, x0, x1) in SHEET_CROPS.items():
        tiles = []
        for label, img in named:
            t = Image.fromarray((img[y0:y1, x0:x1] * 255 + 0.5).astype(np.uint8)).rotate(-90, expand=True)
            t = t.resize((t.width * 2, t.height * 2), Image.NEAREST)
            c = Image.new("RGB", (t.width, t.height + 18), (0, 0, 0))
            c.paste(t, (0, 18))
            ImageDraw.Draw(c).text((4, 3), label, fill=(236, 232, 223), font=font)
            tiles.append(c)
        row = Image.new("RGB", (sum(t.width + 6 for t in tiles), tiles[0].height), (0, 0, 0))
        x = 0
        for t in tiles:
            row.paste(t, (x, 0))
            x += t.width + 6
        rows.append(row)
    s = Image.new("RGB", (max(r.width for r in rows), sum(r.height + 10 for r in rows)), (0, 0, 0))
    y = 0
    for r in rows:
        s.paste(r, (0, y))
        y += r.height + 10
    s.save(path)


def load_algo(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, "algos", name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main(names):
    os.makedirs(OUT, exist_ok=True)
    raw = load("raw200")
    meta = dict(META["raw200"], layout=META["layout"]["raw200"])
    ref50 = bayer_reference("raw50")
    ref12 = bayer_reference("raw12")
    g50, g12 = exposure_gain(up(ref50, 2)), exposure_gain(up(ref12, 4))
    ref50_lin = to_srgb_linear(ref50, g50)
    named = [("12MP x4", encode(to_srgb_linear(up(ref12, 4), g12))),
             ("50MP x2", encode(to_srgb_linear(up(ref50, 2), g50)))]
    print(f"{'algorithm':24s} {'psnr50':>7s} {'detail':>7s} {'noise':>7s} {'det-noi':>8s} {'mid':>7s} {'fringe':>7s} {'grid':>6s} {'time':>6s}")
    m50 = metrics(up(ref50, 2), ref50_lin, g50)
    print(f"{'(50MP upscaled 2x)':24s} {m50['psnr_vs_50mp']:7.2f} {m50['detail_hf']:7.3f} {m50['noise_hf']:7.3f} "
          f"{m50['detail_minus_noise']:8.3f} {m50['detail_mid']:7.3f} {m50['barcode_chroma']:7.4f} {m50['grid']:6.2f}")
    results = {}
    for n in names:
        t0 = time.time()
        rgb = load_algo(n).reconstruct(raw.copy(), meta).astype(np.float32)
        dt = time.time() - t0
        assert rgb.shape == raw.shape + (3,), f"{n}: bad shape {rgb.shape}"
        g = exposure_gain(rgb)
        m = metrics(rgb, ref50_lin, g)
        m["seconds"] = round(dt, 2)
        results[n] = m
        print(f"{n:24s} {m['psnr_vs_50mp']:7.2f} {m['detail_hf']:7.3f} {m['noise_hf']:7.3f} {m['detail_minus_noise']:8.3f} "
              f"{m['detail_mid']:7.3f} {m['barcode_chroma']:7.4f} {m['grid']:6.2f} {dt:6.1f}")
        img = encode(to_srgb_linear(rgb, g))
        Image.fromarray((img * 255 + 0.5).astype(np.uint8)).rotate(-90, expand=True).save(os.path.join(OUT, n + ".png"))
        named.append((n, img))
    sheet(named, os.path.join(OUT, "sheet_" + "_".join(names) + ".png"))
    json.dump(results, open(os.path.join(OUT, "results_" + "_".join(names) + ".json"), "w"), indent=2)


if __name__ == "__main__":
    main(sys.argv[1:])
