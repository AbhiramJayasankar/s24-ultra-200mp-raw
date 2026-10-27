"""Fine search around RCT 40 + previous-channel properties from combination screen."""
import csv
from make_jxl_sweep import HEADER

rows = []
def add(name, e=4, p=3, l=2, a=0, b=0, c=0, patches=-1):
    rows.append([name, e, p, 40, l, 2, 1, a, b, c, 0, patches])

rows.append(["e2", 2, -1, -1, -1, -1, -1, -1, -1, -1, 0, -1])
for e in [4, 5, 6, 7]:
    for l in [0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 20, 50]:
        add(f"fine-e{e}-p3-l{l}", e=e, l=l)
    for p in [0, 3, 4, 5, 6, 13, 14, 15]:
        add(f"fine-e{e}-p{p}-l5", e=e, p=p, l=5)
    add(f"fine-e{e}-no-patches", e=e, l=5, patches=0)
    for a, b, c in [(95, 80, 0), (0, 0, 1024), (95, 0, 0)]:
        add(f"fine-e{e}-pal{a}-{b}-{c}", e=e, l=5, a=a, b=b, c=c)
with open("bench/jxl-fine.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(HEADER + ["patches"])
    w.writerows(rows)
print(f"{len(rows)} fine trials")
rows.clear()
rows.append(["e2", 2, -1, -1, -1, -1, -1, -1, -1, -1, 0, -1])
for e in [4, 5, 6, 7]:
    for p in [5, 14]:
        for l in [0.5, 1, 2, 5, 10, 20]:
            add(f"budget-e{e}-p{p}-l{l}", e=e, p=p, l=l, patches=0)
for e in [6, 7]:
    for l in [0.5, 1, 2]:
        add(f"budget-e{e}-p15-l{l}", e=e, p=15, l=l, patches=0)
with open("bench/jxl-budget.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(HEADER + ["patches"])
    w.writerows(rows)
print(f"{len(rows)} budget trials (patch search off)")
