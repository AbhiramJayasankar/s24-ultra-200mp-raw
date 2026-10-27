"""Rank completed benchmark CSV rows (also accepts PowerShell UTF-16 output)."""
import argparse
import csv
import io
from pathlib import Path


def read_rows(path):
    data = Path(path).read_bytes()
    return list(csv.DictReader(io.StringIO(data.decode("utf-16" if data.startswith(b"\xff\xfe") else "utf-8-sig"))))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", nargs="+")
    parser.add_argument("--budget", type=float, default=3)
    args = parser.parse_args()
    rows = [row for path in args.csv for row in read_rows(path)]
    baseline = next(r for r in rows if r["name"] == "e2" and r["tile"] == "256")
    time = float(baseline["encode_ms_MP"])
    size = float(baseline["estimated_dng_MB"])
    print("setting / tile / MB / encode ms/MP / decode ms/MP / encode ratio / size ratio")
    for row in sorted(rows, key=lambda r: float(r["estimated_dng_MB"])):
        ratio = float(row["encode_ms_MP"]) / time
        if ratio <= args.budget:
            print(f"{row['name']:24} {row['tile']} {float(row['estimated_dng_MB']):8.3f} "
                  f"{float(row['encode_ms_MP']):8.2f} {float(row['decode_ms_MP']):7.2f} "
                  f"{ratio:5.2f} {float(row['estimated_dng_MB'])/size:6.3f}")
