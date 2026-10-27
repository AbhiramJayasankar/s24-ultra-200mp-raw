"""Compare every unpadded code of two tiled LinearRaw DNGs, with bounded memory.

Requires numpy, tifffile and imagecodecs. Accepts LJ92 or JPEG XL tiles.
Example: python bench/compare_linear_dng_codes.py build/java.dng build/native.dng
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import mmap
import time

import imagecodecs
import numpy as np
import tifffile


def compare(first, second, exact=False):
    started = time.perf_counter()
    with ExitStack() as stack:
        files = [stack.enter_context(open(path, "rb")) for path in (first, second)]
        maps = [stack.enter_context(mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)) for f in files]
        tiffs = [stack.enter_context(tifffile.TiffFile(path)) for path in (first, second)]
        pages = [t.pages[0].pages[0] for t in tiffs]
        a, b = pages
        assert a.shape == b.shape and a.tilewidth == b.tilewidth and a.tilelength == b.tilelength
        assert a.samplesperpixel == b.samplesperpixel == 3 and a.photometric == b.photometric == 34892
        if exact:
            assert np.array_equal(a.tags[50712].value, b.tags[50712].value), "Linearization tables differ"
        decoders = {7: imagecodecs.jpegsof3_decode, 52546: imagecodecs.jpegxl_decode}
        across = (a.imagewidth + a.tilewidth - 1) // a.tilewidth

        def tile(index):
            samples = [decoders[int(p.compression)](m[p.dataoffsets[index]:p.dataoffsets[index]+p.databytecounts[index]])
                       for m, p in zip(maps, pages)]
            height = min(a.tilelength, a.imagelength - index // across * a.tilelength)
            width = min(a.tilewidth, a.imagewidth - index % across * a.tilewidth)
            diff = np.abs(samples[0][:height, :width].astype(np.int32) - samples[1][:height, :width].astype(np.int32))
            return np.bincount(diff.ravel())

        histogram = np.zeros(65536, dtype=np.int64)
        with ThreadPoolExecutor(8) as pool:
            # Bound completed-result storage even if a tile is unusually slow.
            for first_tile in range(0, len(a.dataoffsets), 32):
                for counts in pool.map(tile, range(first_tile, min(first_tile + 32, len(a.dataoffsets)))):
                    histogram[:len(counts)] += counts
        count = int(histogram.sum())
        changed = count - int(histogram[0])
        maximum = int(np.flatnonzero(histogram)[-1])
        mean = float(np.dot(np.arange(65536, dtype=np.float64), histogram) / count)
        percentile = int(np.searchsorted(histogram.cumsum(), np.ceil(count * .999)))
        print(f"{count:,} RGB samples: changed {changed:,} ({changed/count:.9%}), "
              f"mean |diff| {mean:.12g}, p99.9 {percentile}, max {maximum}; {time.perf_counter()-started:.3f} s")
        if exact and changed:
            raise SystemExit("Bit identity failed: changed samples must be zero")
        if maximum > 1 or changed / count >= .0001:
            raise SystemExit("Native/Java tolerance exceeded (max 1, fewer than 0.01% changed samples)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("java_dng")
    parser.add_argument("native_dng")
    parser.add_argument("--exact", action="store_true", help="Require zero changed samples and identical linearization tables")
    args = parser.parse_args()
    compare(args.java_dng, args.native_dng, args.exact)
