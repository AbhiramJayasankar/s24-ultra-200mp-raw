"""Generate the initial, explicit settings sweep used in jxl-tuning.md."""
import csv
from pathlib import Path

HEADER = ["name", "effort", "predictor", "rct", "learning", "prev", "group", "global", "local", "palette", "speed"]


def main():
    rows = []

    def add(name, effort=2, predictor=-1, rct=-1, learning=-1, prev=-1,
            group=-1, global_=-1, local=-1, palette=-1, speed=0):
        rows.append([name, effort, predictor, rct, learning, prev, group, global_, local, palette, speed])

    for e in [1, 2, 3, 4, 5, 7]:
        add(f"e{e}", effort=e)
    # All RCT channel permutations: previous-channel properties can make otherwise
    # equivalent transforms differ. Effort 2 avoids a costly adaptive tree search.
    for rct in range(42):
        add(f"e2-r{rct}", rct=rct)
    for e in [2, 3, 4, 5]:
        for pred in [1, 3, 4, 5, 6, 13, 14, 15]:
            add(f"e{e}-p{pred}", effort=e, predictor=pred)
    for e in [3, 4, 5]:
        for learning in [0, 0.5, 2, 5, 10, 20]:
            add(f"e{e}-l{learning}", effort=e, learning=learning)
        for prev in [0, 1, 2, 3]:
            add(f"e{e}-n{prev}", effort=e, prev=prev)
    for e in [2, 3, 4, 5]:
        add(f"e{e}-no-palette", effort=e, global_=0, local=0, palette=0)
        for group in [0, 1, 2, 3]:
            add(f"e{e}-g{group}", effort=e, group=group)
        for speed in [1, 2]:
            add(f"e{e}-d{speed}", effort=e, speed=speed)
    dest = Path("bench/jxl-sweep.csv")
    with dest.open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(HEADER)
        writer.writerows(rows)
    print(f"{dest}: {len(rows)} settings")


if __name__ == "__main__":
    main()
