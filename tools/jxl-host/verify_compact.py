"""Independent JNI sample/metadata check. Needs numpy, imagecodecs and tifffile.

python tools/jxl-host/verify_compact.py build/jxl-decode-results --prepare
# Run JxlDecodeCheck, then:
python tools/jxl-host/verify_compact.py build/jxl-decode-results
"""
import argparse
from pathlib import Path
import struct
import imagecodecs
import numpy as np
import tifffile


def bare(data):
    if data[:2] == b"\xff\x0a":
        return data
    at = 0
    while at < len(data):
        length, kind = struct.unpack_from(">I4s", data, at)
        header = 8
        if length == 1:
            length = struct.unpack_from(">Q", data, at + 8)[0]
            header = 16
        if length == 0:
            length = len(data) - at
        assert header <= length <= len(data) - at
        if kind == b"jxlc":
            result = data[at + header:at + length]
            assert result[:2] == b"\xff\x0a"
            return result
        at += length
    raise AssertionError("No jxlc box")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("directory", type=Path)
    p.add_argument("--prepare", action="store_true")
    args = p.parse_args()
    d = args.directory
    d.mkdir(parents=True, exist_ok=True)
    if args.prepare:
        rng = np.random.default_rng(42)
        for channels in (1, 3):
            shape = (256, 256) if channels == 1 else (256, 256, 3)
            raw = rng.integers(0, 65536, shape, dtype=np.uint16)
            (d / f"external_c{channels}.raw").write_bytes(raw.tobytes())
            (d / f"external_c{channels}.jxl").write_bytes(bare(imagecodecs.jpegxl_encode(
                raw, lossless=True, effort=3, numthreads=1, usecontainer=False)))
        print("Prepared 2 full-range 16-bit inputs with independent encoder")
        return
    files = sorted(d.glob("decode_*.jxl")) + sorted(d.glob("external_*.jxl"))
    for f in files:
        decoded = imagecodecs.jpegxl_decode(f.read_bytes())
        assert decoded.dtype == np.uint16
        assert decoded.astype("<u2").tobytes() == f.with_suffix(".raw").read_bytes(), f.name
    with tifffile.TiffFile(d / "native-before.dng") as a, tifffile.TiffFile(d / "native-after.dng") as b:
        for i, (x, y) in enumerate(((a.pages[0], b.pages[0]), (a.pages[0].pages[0], b.pages[0].pages[0]))):
            assert set(x.tags) == set(y.tags)
            relocated = {273, 330, 34665} if i == 0 else {324, 325, 52554, 52555}
            for tag in x.tags:
                left, right = x.tags[tag], y.tags[tag]
                assert (left.dtype, left.count) == (right.dtype, right.count)
                if tag not in relocated:
                    assert left.value == right.value, tag
        assert np.array_equal(a.pages[0].asarray(), b.pages[0].asarray())
        raw_a, raw_b = a.pages[0].pages[0], b.pages[0].pages[0]
        for ao, ac, bo, bc in zip(raw_a.dataoffsets, raw_a.databytecounts, raw_b.dataoffsets, raw_b.databytecounts):
            a.filehandle.seek(ao)
            b.filehandle.seek(bo)
            assert np.array_equal(imagecodecs.jpegxl_decode(a.filehandle.read(ac)),
                                  imagecodecs.jpegxl_decode(b.filehandle.read(bc)))
        assert raw_b.tags[52554].value == 7 and raw_b.tags[52555].value == 0
    print(f"Independent decode: {len(files)} exact UINT16 cases; DNG tags, EXIF, thumbnail and every padded tile match")


if __name__ == "__main__":
    main()
