"""Independently decode all JxlSelfTest/host files using imagecodecs and numpy.

Usage: python tools/jxl-host/verify_jxl.py <directory containing .jxl/.raw files> [jxl_tile_bench.exe]
"""
import csv
import io
from pathlib import Path
import re
import sys
import subprocess

import imagecodecs
import numpy as np


def verify(directory, native_bench=None):
    text = (directory / "results.txt").read_text()
    rows = {row["name"]: row for row in csv.DictReader(io.StringIO(text[text.index("name,width,"):]))}
    assert len(rows) == 48, f"Incomplete benchmark summary: {len(rows)} rows"
    for name, row in rows.items():
        for suffix, size_key in ((".raw", "raw_bytes"), (".jxl", "jxl_bytes")):
            path = directory / (name + suffix)
            assert path.stat().st_size == int(row[size_key]), f"Missing or truncated file: {path}"
    checked = lossy = 0
    for path in sorted(directory.glob("*.jxl")):
        encoded = path.read_bytes()
        assert encoded[:2] == b"\xff\x0a", f"Not a bare codestream: {path}"
        if path.stem.startswith("lossy_"):
            decoded = imagecodecs.jpegxl_decode(encoded)
            assert decoded.shape == (61, 97, 3), path
            lossy += 1
            continue
        if path.stem in rows:
            row = rows[path.stem]
            width, height, channels, bits = (int(row[k]) for k in ("width", "height", "channels", "bits"))
        else:
            match = re.fullmatch(r"(?:edge|tuned)_(\d+)x(\d+)_c(\d+)_b(\d+)_e\d+", path.stem)
            assert match, f"Unknown test case: {path}"
            width, height, channels, bits = map(int, match.groups())
        decoded = imagecodecs.jpegxl_decode(encoded)
        expected = np.fromfile(path.with_suffix(".raw"), dtype="<u2")
        shape = (height, width, channels) if channels == 3 else (height, width)
        assert decoded.shape == shape, (path, decoded.shape, shape)
        # imagecodecs returns integer samples at the codestream's native bit depth.
        assert np.max(decoded) < (1 << bits), path
        np.testing.assert_array_equal(decoded.reshape(-1), expected, err_msg=str(path))
        if path.stem.startswith("tuned_") and native_bench:
            # UINT16 output alone cannot distinguish a 12-bit and a 16-bit
            # codestream. Read BasicInfo too: Lightroom needs precisely 16.
            subprocess.run([str(native_bench), "--check", str(path), str(path.with_suffix(".raw")),
                            str(width), str(height), str(channels)], check=True, capture_output=True)
        checked += 1
    assert checked >= 48, f"Expected at least 48 lossless cases, found {checked}"
    print(f"PASS: {checked} exact integer round trips; {lossy} lossy decodes; all bare codestreams")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]) if len(sys.argv) > 2 else None)
