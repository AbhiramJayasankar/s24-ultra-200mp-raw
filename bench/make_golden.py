"""Generate the Directional16 JVM golden without changing any captured inputs.

Run from the repository root: python bench/make_golden.py
"""
import json
import sys
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bench.algos.dir_port import bayer


def main():
    data = ROOT / 'bench' / 'data'
    meta = json.loads((data / 'meta.json').read_text())['raw200']
    raw = (np.load(data / 'raw200.npy').astype(np.float32)-meta['black'])/(meta['white']-meta['black'])
    output = data / 'golden_dir_port_bayer.npy'
    np.save(output, bayer(raw, meta).astype(np.float32))
    print(output)


if __name__ == '__main__':
    main()
