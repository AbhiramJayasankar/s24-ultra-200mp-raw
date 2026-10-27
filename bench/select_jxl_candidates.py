"""Select finalists from completed short screens; rerun these on the large samples."""
import csv
import sys
from rank_jxl_results import read_rows
from make_jxl_sweep import HEADER

tile512 = "--tile512" in sys.argv
paths = (["build/jxl-tuning/candidates256.csv"] if tile512 else
         [f"build/jxl-tuning/{name}256.csv" for name in ["refine", "fastdecode", "fine", "budget"]])
rows = [r for path in paths for r in read_rows(path)]
baseline = next(r for r in rows if r["name"] == "e2")
base_time = float(baseline["encode_ms_MP"])
chosen, keys = [], set()

def add(row):
    values = [row.get(k, "-1") for k in HEADER[1:] + ["patches"]]
    key = tuple(float(v) for v in values)
    if key not in keys:
        keys.add(key)
        chosen.append([row["name"]] + values)

add(baseline)
limits = ([(1.2, 1, 1000), (1.6, 3, 1000), (3.1, 2, 1000), (1.6, 1, 40)] if tile512 else
          [(1.1, 2, 1000), (1.6, 6, 1000), (3.1, 5, 1000), (1.6, 3, 40)])
for budget, limit, decode in limits:
    qualifying = [r for r in rows if float(r["encode_ms_MP"]) <= base_time * budget
                  and float(r["decode_ms_MP"]) <= decode]
    # Deduplicate before applying the row limit (fine sweeps include repeats).
    taken = set()
    for r in sorted(qualifying, key=lambda r: float(r["estimated_dng_MB"])):
        key = tuple(float(r.get(k, "-1")) for k in HEADER[1:] + ["patches"])
        if key in taken:
            continue
        taken.add(key)
        add(r)
        if len(taken) == limit:
            break
if tile512:
    chosen = [[str(row[0]) + f"-g{g}"] + row[1:6] + [g] + row[7:]
              for row in chosen for g in [1, 2]]
dest = "bench/jxl-candidates512.csv" if tile512 else "bench/jxl-candidates.csv"
with open(dest, "w", newline="") as f:
    writer = csv.writer(f)
    writer.writerow(HEADER + ["patches"])
    writer.writerows(chosen)
print(f"{len(chosen)} distinct finalists")
