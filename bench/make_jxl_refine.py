"""Generate combination trials and smaller spatially spread screening inputs.

Short screens guide selection; candidates must then be rerun on the 240-tile
sample and full frame. Does not read or modify keeper.dng.
"""
import csv
import struct
from pathlib import Path

from make_jxl_sweep import HEADER


def subset(source, dest, count):
    with open(source, "rb") as f, open(dest, "wb") as out:
        header = list(struct.unpack("<8sIIIIQQQ", f.read(48)))
        size, total = header[1:3]
        header[2] = count
        out.write(struct.pack("<8sIIIIQQQ", *header))
        tile_bytes = size * size * 6
        for i in range(count):
            # Even spacing across the complete spatially stratified sample.
            j = i * (total - 1) // (count - 1)
            f.seek(48 + j * tile_bytes)
            data = f.read(tile_bytes)
            assert len(data) == tile_bytes
            out.write(data)


def main():
    rows = []
    def add(name, e=4, p=3, r=-1, l=2, n=0, g=1, a=0, b=0, c=0, d=0, patches=-1):
        rows.append([name, e, p, r, l, n, g, a, b, c, d, patches])

    add("e2", e=2, p=-1, r=-1, l=-1, n=-1, g=-1, a=-1, b=-1, c=-1)
    # Joint effort/predictor/RCT/learning search. Palettes disabled in this branch.
    for e in [4, 5, 6, 7, 8]:
        for p in [3, 13]:
            for r in [-1, 6, 17, 40]:
                for l in [1, 5]:
                    add(f"e{e}-p{p}-r{r}-l{l}-off", e=e, p=p, r=r, l=l)
    for e in [4, 5, 6]:
        for r in [-1, 6, 17, 40]:
            for l in [1, 5]:
                add(f"e{e}-p3-r{r}-l{l}-n2-off", e=e, r=r, l=l, n=2)
    for e in [4, 5, 6, 7]:
        add(f"e{e}-p13-r17-l5-n2-off", e=e, p=13, r=17, l=5, n=2)
    # Channel order matters to the previous-channel MA properties.
    for r in range(42):
        add(f"joint-r{r}", r=r, n=2)
    # Isolate palette controls, keeping the other tuning knobs fixed.
    for a in [0, 25, 50, 95, 100]:
        add(f"pal-global{a}", a=a, b=80, c=1024)
    for b in [0, 25, 50, 80, 100]:
        add(f"pal-local{b}", a=95, b=b, c=1024)
    for c in [0, 256, 1024, 4096, 65536]:
        add(f"pal-colors{c}", a=95, b=80, c=c)
    for n in [0, 1, 2, 3]:
        add(f"prev{n}", n=n)
    for p in range(16):
        add(f"pred{p}", p=p)
    for g in [0, 1, 2, 3]:
        add(f"group{g}", g=g)
    for e in [4, 5, 6, 7]:
        for d in [1, 2]:
            add(f"e{e}-decode{d}", e=e, d=d)
        add(f"e{e}-patches0", e=e, patches=0)
    with open("bench/jxl-refine.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(HEADER + ["patches"])
        w.writerows(rows)
    rows.clear()
    add("e2", e=2, p=-1, r=-1, l=-1, n=-1, g=-1, a=-1, b=-1, c=-1)
    for e in [4, 5, 6, 7, 8]:
        for r in [-1, 6, 17, 40]:
            for d in [1, 2]:
                add(f"fast-e{e}-r{r}-d{d}", e=e, p=-1, r=r, l=2, a=95, b=80, c=1024, d=d)
    for e in [2, 3, 4]:
        for a, b, c in [(95, 80, 0), (95, 0, 0), (0, 80, 0)]:
            add(f"pal-e{e}-g{a}-l{b}-c{c}", e=e, p=-1, l=2, a=a, b=b, c=c)
    with open("bench/jxl-fastdecode.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(HEADER + ["patches"])
        w.writerows(rows)
    for size, count in [(256, 32), (512, 8)]:
        dest = f"build/jxl-tuning/screen{size}.raw"
        if not Path(dest).exists():
            subset(f"build/jxl-tuning/tiles{size}.raw", dest, count)
    print("202 combinations + 50 decode/palette trials; 12.6 MB screening inputs")


if __name__ == "__main__":
    main()
