"""Separate desktop compression estimate, without changing the Java DNG.
Decode one tile band at a time, then time 8 single-threaded libjxl encoders.
imagecodecs/libjxl may differ from the Android build: print the version.
Usage: python bench/estimate_lumdir_jxl.py [build/lumdir200.dng] [effort]
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import struct
import sys
import time
import imagecodecs
import tifffile

def bare(data):
    if data[:2] == b'\xff\x0a':
        return data
    at = 0
    while at < len(data):
        size, kind = struct.unpack_from('>I4s', data, at)
        header = 8
        if size == 1:
            size = struct.unpack_from('>Q', data, at+8)[0]
            header = 16
        elif size == 0:
            size = len(data)-at
        if kind == b'jxlc':
            return data[at+header:at+size]
        at += size
    raise ValueError('Missing JXL codestream')

EFFORT = int(sys.argv[2]) if len(sys.argv)>2 else 1

def encode(tile):
    return bare(imagecodecs.jpegxl_encode(tile, lossless=True, effort=EFFORT, decodingspeed=0,
                                         bitspersample=16, usecontainer=False, numthreads=1))

def main():
    path = Path(sys.argv[1] if len(sys.argv)>1 else 'build/lumdir200.dng')
    wall = time.perf_counter()
    encoding = preparation = 0.0
    size = 0
    with tifffile.TiffFile(path) as tif, open(path, 'rb') as stream, ThreadPoolExecutor(8) as pool:
        raw = tif.pages[0].pages[0]
        offsets, counts = raw.dataoffsets, raw.databytecounts
        across = (raw.imagewidth+255)//256
        for first in range(0, len(offsets), across):
            t = time.perf_counter()
            tiles = []
            for i in range(first, min(len(offsets), first+across)):
                stream.seek(offsets[i])
                tiles.append(imagecodecs.jpegsof3_decode(stream.read(counts[i])))
            preparation += time.perf_counter()-t
            t = time.perf_counter()
            encoded = list(pool.map(encode, tiles))
            encoding += time.perf_counter()-t
            size += sum((len(data)+1)&~1 for data in encoded)
            if first == 0:
                import numpy as np
                np.testing.assert_array_equal(imagecodecs.jpegxl_decode(encoded[0]), tiles[0])
        # Three new inline JXL tags add 36 bytes to the reserved prefix.
        estimate = min(offsets)+36+size
        print(imagecodecs.jpegxl_version(), f'lossless effort={EFFORT} decode-speed=0 bits=16, 8 x 1 threads')
        print(f'compression only {encoding:.3f} s; LJ92 decode/preparation {preparation:.3f} s; total estimator wall {time.perf_counter()-wall:.3f} s')
        print(f'estimated JXL DNG {estimate:,} bytes; LJ92 DNG {path.stat().st_size:,} bytes; version/defaults differ from the Android JNI encoder')

if __name__ == '__main__':
    main()
