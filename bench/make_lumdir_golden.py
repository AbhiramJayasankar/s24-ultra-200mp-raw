"""Generate ignored Java test goldens from the SAME requantized RAW10 as Java.
Run at the repository root: python bench/make_lumdir_golden.py [book tile]
Each case saves codes, clipped linear RGB, the curve and noise parameters.
"""
import sys
from pathlib import Path
import json
import time
import numpy as np
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bench.algos import lumdir
from bench.linear_dng import dng_noise, noise_curve

def main():
    folder = ROOT / 'bench/data/lumdir_ref'
    for name in sys.argv[1:] or ['book', 'tile']:
        start = time.perf_counter()
        raw = np.load(folder / (name + '_raw.npy')).astype(np.float32)
        meta = json.loads((folder / (name + '_meta.json')).read_text())
        # np.rint is ties-to-even; Java tests load the integer DN array directly.
        dn = np.clip(np.rint(raw * np.float32(959) + np.float32(64)), 0, 1023).astype(np.uint16)
        raw = (dn.astype(np.float32) - np.float32(64)) / np.float32(959)
        np.save(folder / (name + '_dn.npy'), dn)
        rgb = lumdir.camera_rgb(raw, meta)
        noise = dng_noise(raw, meta)
        encode, table = noise_curve(*noise)
        np.save(folder / (name + '_codes.npy'), encode(rgb))
        np.save(folder / (name + '_linear.npy'), rgb)
        np.save(folder / (name + '_curve.npy'), table)
        (folder / (name + '_noise.json')).write_text(json.dumps(noise))
        print(name, raw.shape, 'noise', noise, 'curve', len(table), 'seconds', time.perf_counter()-start, flush=True)

if __name__ == '__main__':
    main()
