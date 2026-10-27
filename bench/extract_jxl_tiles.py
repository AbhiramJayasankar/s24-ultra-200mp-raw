"""Read-only extraction of keeper LinearRaw codes; outputs go in ignored build/.

The binary format is little endian: 8-byte magic S24JXLT1, four uint32
(tile size, tile count, image width, image height), three uint64 (original
tile payload bytes, original file bytes, target grid tile count), then tightly
packed tile-major RGB uint16. All tiles include edge-replicated padding.
"""
import argparse
import csv
import mmap
import struct
from pathlib import Path

import imagecodecs
import numpy as np
import tifffile


def extract(source, output, size, full):
    with tifffile.TiffFile(source) as tif, open(source, "rb") as f:
        p = tif.pages[0].pages[0]
        assert p.tilewidth == p.tilelength == 256 and p.samplesperpixel == 3
        assert int(p.compression) == 52546 and p.bitspersample == 16
        w, h = p.imagewidth, p.imagelength
        nx, ny = (w + size - 1) // size, (h + size - 1) // size
        if full:
            positions = [(y, x) for y in range(ny) for x in range(nx)]
        else:
            # One seeded random tile in each equal-area spatial stratum.
            gx, gy = (20, 12) if size == 256 else (10, 6)
            rng = np.random.default_rng(240200)
            positions = [(int(rng.integers(y * ny // gy, (y + 1) * ny // gy)),
                          int(rng.integers(x * nx // gx, (x + 1) * nx // gx)))
                         for y in range(gy) for x in range(gx)]
        output = Path(output)
        output.parent.mkdir(parents=True, exist_ok=True)
        with mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as mm, \
                open(output, "wb") as out, open(str(output) + ".csv", "w", newline="") as manifest:
            out.write(struct.pack("<8sIIIIQQQ", b"S24JXLT1", size, len(positions), w, h,
                                  sum(p.databytecounts), len(mm), nx * ny))
            rows = csv.writer(manifest)
            rows.writerow(["index", "tile_y", "tile_x", "mean", "std", "spatial_absdiff", "min", "max"])
            old_nx = (w + 255) // 256
            for i, (ty, tx) in enumerate(positions):
                tile = np.empty((size, size, 3), dtype="<u2")
                for dy in range(0, size, 256):
                    for dx in range(0, size, 256):
                        sy = min(ty * size + dy, (h - 1) // 256 * 256) // 256
                        sx = min(tx * size + dx, (w - 1) // 256 * 256) // 256
                        j = sy * old_nx + sx
                        decoded = imagecodecs.jpegxl_decode(mm[p.dataoffsets[j]:p.dataoffsets[j]+p.databytecounts[j]])
                        assert decoded.shape == (256, 256, 3) and decoded.dtype == np.uint16
                        tile[dy:dy+256, dx:dx+256] = decoded
                valid_h, valid_w = min(size, h - ty * size), min(size, w - tx * size)
                # Recreate the writer's padding, including new 512-pixel boundaries.
                tile[valid_h:] = tile[valid_h-1]
                tile[:, valid_w:] = tile[:, valid_w-1:valid_w]
                if size == 256:
                    assert np.array_equal(tile, decoded), "Source padding differs from writer"
                out.write(tile.tobytes())
                a = tile.astype(np.int32)
                rows.writerow([i, ty, tx, float(a.mean()), float(a.std()),
                               float(np.abs(np.diff(a, axis=1)).mean()), int(a.min()), int(a.max())])
        print(f"{output}: {len(positions)} tiles, {output.stat().st_size:,} bytes; {w}x{h}; "
              f"original payload {sum(p.databytecounts):,} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("output")
    parser.add_argument("--size", type=int, choices=[256, 512], default=256)
    parser.add_argument("--full", action="store_true")
    args = parser.parse_args()
    extract(args.source, args.output, args.size, args.full)
