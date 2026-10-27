"""CPU timing and exact-output checks for lumdir's saved reconstruction references.

Run from the repository root: python bench/time_lumdir.py
Set LUMDIR_THREADS=1 to check the serial path. Loading and comparison are untimed.
"""
import argparse
from collections import defaultdict
from contextlib import contextmanager
import functools
import json
import os
from pathlib import Path
import sys
from time import perf_counter

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bench.algos import dir_port, lumdir


@contextmanager
def stage_timers():
    """Wrap stage boundaries, including the unoptimized implementation.

    Parallel fits are measured around their dispatch/join, not by adding worker
    times. All reported stages are disjoint; 'other' is the remaining wall time.
    """
    times = defaultdict(float)
    originals = []

    def wrap(module, name, label):
        original = getattr(module, name)
        originals.append((module, name, original))

        @functools.wraps(original)
        def timed(*args, **kwargs):
            start = perf_counter()
            try:
                return original(*args, **kwargs)
            finally:
                times[label] += perf_counter() - start

        setattr(module, name, timed)

    for name in ('calibration', 'initial', 'noise_model', 'clean'):
        wrap(dir_port, name, name)
    wrap(dir_port, '_fit_channels' if hasattr(dir_port, '_fit_channels') else 'masked_fit',
         'masked_fit')
    wrap(lumdir, '_colour_ratios' if hasattr(lumdir, '_colour_ratios') else 'gaussian_filter',
         'chroma')
    wrap(lumdir, 'phase_flatten', 'phase_flatten')
    try:
        yield times
    finally:
        for module, name, original in reversed(originals):
            setattr(module, name, original)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path,
                        default=Path(__file__).resolve().parent / 'data' / 'lumdir_ref')
    parser.add_argument('--case', choices=('book', 'tile', 'both'), default='both')
    args = parser.parse_args()
    print(f"LUMDIR_THREADS={os.environ.get('LUMDIR_THREADS', os.cpu_count())}", flush=True)
    failed = False
    for name in ('book', 'tile') if args.case == 'both' else (args.case,):
        raw = np.load(args.data / f'{name}_raw.npy')
        reference = np.load(args.data / f'{name}_out.npy', mmap_mode='r')
        meta = json.loads((args.data / f'{name}_meta.json').read_text())
        runs = 2 if name == 'book' else 1
        best = float('inf')
        for run in range(runs):
            with stage_timers() as stages:
                start = perf_counter()
                output = lumdir.camera_rgb(raw, meta)
                elapsed = perf_counter() - start
            # Count spatial pixels with any changed channel, and channel values too.
            different = output != reference
            pixels = np.count_nonzero(np.any(different, axis=-1))
            values = np.count_nonzero(different)
            max_diff = float(np.max(np.abs(output - reference)))
            bitwise_equal = (output.dtype == reference.dtype and
                             np.array_equal(output.view(np.uint8), reference.view(np.uint8)))
            failed |= not np.isfinite(max_diff) or max_diff > 1e-5
            if elapsed < best:
                best, best_stages = elapsed, dict(stages)
            print(f'{name} {raw.shape}: run {run+1}/{runs} {elapsed:.3f} s; '
                  f'max_abs_diff={max_diff:.9g}; differing_pixels={pixels}; '
                  f'differing_values={values}; bitwise_equal={bitwise_equal}', flush=True)
            del output, different
        best_stages['other'] = best - sum(best_stages.values())
        print(f'{name} best: {best:.3f} s; stages (wall seconds): ' +
              ', '.join(f'{key}={value:.3f}' for key, value in best_stages.items()), flush=True)
    return int(failed)


if __name__ == '__main__':
    sys.exit(main())
