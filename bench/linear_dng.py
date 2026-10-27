"""Write full-colour (LinearRaw) DNGs from a reconstructed camera-RGB image.

The reconstruction (e.g. lumdir) already decided every pixel's R, G and B, so the DNG
stores 3 samples per pixel (PhotometricInterpretation LinearRaw) and Lightroom applies
only white balance, colour and tone, no demosaicing of its own.

Storage options:
- curve: 'linear' stores 16-bit linear values. 'noise' stores codes on a square-root
  curve whose step is a fixed fraction (STEP) of the sensor noise at that level, and
  a LinearizationTable maps codes back to linear values. At STEP 0.5 the rounding adds
  2% to the noise variance (1% to its sigma, invisible) and cuts the bits per sample
  roughly in half (0.25 was used up to 0.3.3: ~25% larger files, no visible difference).
- compression: 'ljpeg' (lossless JPEG, DNG 1.1+), 'jxl' (JPEG XL, DNG 1.7; lossless
  unless distance > 0).

Usage: python bench/linear_dng.py <name_out.npy> <out.dng> [curve] [compression] [distance]
(name_out.npy: lumdir.camera_rgb output, 1.0 = sensor white; name_raw.npy and name_meta.json beside it)
"""
import os
import struct
import sys

import numpy as np

sys.path[:0] = [os.path.dirname(os.path.abspath(__file__)), os.path.join(os.path.dirname(os.path.abspath(__file__)), 'algos')]
TILE = 256
STEP = 0.5

# Samsung S24 Ultra main camera colour data, copied from the phone's own DNG metadata.
COLOUR = {
    50721: (10, (357, 512, -44189453, 250000000, -4699707, 62500000, -181, 512, 601074219, 500000000,
                 63, 512, -207901, 1953125, 18737793, 62500000, 253, 512)),
    50722: (10, (825, 512, -525, 512, -7, 256, 16601563, 1000000000, 942382813, 1000000000, 110351563,
                 1000000000, 79101563, 1000000000, 65, 512, 135, 256)),
    50723: (10, (495605469, 500000000, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 15914917, 15625000)),
    50724: (10, (495605469, 500000000, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 15914917, 15625000)),
    50964: (10, (169, 256, 14831543, 62500000, 17, 256, 49560547, 250000000, 472167969, 500000000, -73, 512,
                 1, 64, -99, 256, 1196289063, 1000000000)),
    50965: (10, (190917969, 500000000, 411132813, 1000000000, 85449219, 500000000, -29, 512, 542480469,
                 500000000, -3540039, 125000000, -139648437, 1000000000, -479, 512, 475341797, 250000000)),
    50778: (3, (21,)),
    50779: (3, (17,)),
}


MAX_CODE = 4095


def dng_noise(raw, meta):
    """Noise variance a*v + b of the output (1.0 = sensor white), taking the least noisy
    colour so the curve is fine enough for all three: dir_port's per-colour noise model
    (white-balanced units) scaled by the as-shot neutral."""
    try:
        import bench.algos.dir_port as dir_port
        from bench.algos.dir_core import masks
    except ModuleNotFoundError:
        import dir_port
        from dir_core import masks
    raw = np.asarray(raw, np.float32)
    h, w = raw.shape
    gains, offsets = dir_port.calibration(raw)
    corrected = ((raw.reshape(h//8, 8, w//8, 8)-offsets[None, :, None, :])
                 / gains[None, :, None, :]).reshape(h, w).astype(np.float32)
    neutral = np.asarray(meta['as_shot_neutral'], np.float32)
    _, guide = dir_port.initial(corrected, neutral, masks(raw.shape))
    p = dir_port.noise_model(guide).astype(np.float64)
    a = max(float(np.min(neutral*p[:, 0])), 1e-6)
    b = max(float(np.min(neutral.astype(np.float64)**2*p[:, 1])), 1e-8)
    return a, b


def noise_curve(noise_a, noise_b, step=STEP):
    """Codes on sqrt(v + c): step / noise sigma = step everywhere (variance a*v + b).
    At most MAX_CODE + 1 codes (a coarser step only if the noise is implausibly low)."""
    c = noise_b/noise_a
    k = 2/(step*np.sqrt(noise_a))
    k = min(k, MAX_CODE/(np.sqrt(1+c)-np.sqrt(c)))
    top = int(np.ceil(k*(np.sqrt(1+c)-np.sqrt(c))))
    codes = np.arange(top+1)
    table = np.clip(np.round(((codes/k+np.sqrt(c))**2-c)*65535), 0, 65535).astype(np.uint16)
    table[-1] = 65535
    encode = lambda v: np.minimum(np.round(k*(np.sqrt(np.clip(v, 0, 1)+c)-np.sqrt(c))), top).astype(np.uint16)
    return encode, table


def ljpeg(tile):
    """Lossless JPEG (SOF3, predictor 1, 16-bit), components interleaved."""
    h, w, nc = tile.shape
    t = tile.astype(np.int64)
    pred = np.empty_like(t)
    pred[:, 1:] = t[:, :-1]
    pred[1:, 0] = t[:-1, 0]
    pred[0, 0] = 1 << 15
    diff = ((t-pred+32768) % 65536-32768).ravel()
    mag = np.abs(diff)
    cat = np.where(mag == 0, 0, np.floor(np.log2(np.maximum(mag, 1))).astype(np.int64)+1)
    amp = np.where(diff < 0, diff+(1 << cat)-1, diff) & ((1 << cat)-1)
    amp = np.where(cat == 16, 0, amp)
    nbits = 5+np.where(cat == 16, 0, cat)
    value = (cat << (nbits-5)) | amp
    # Expand each code into its bits, most significant first.
    idx = np.repeat(np.arange(value.size), nbits)
    pos = np.arange(idx.size)-np.repeat(np.cumsum(nbits)-nbits, nbits)
    bits = (value[idx] >> (nbits[idx]-1-pos)) & 1
    pad = (-bits.size) % 8
    data = np.packbits(np.concatenate([bits, np.ones(pad, np.int64)]).astype(np.uint8)).tobytes()
    data = data.replace(b'\xff', b'\xff\x00')
    head = b'\xff\xd8'
    head += b'\xff\xc4' + struct.pack('>H', 36) + b'\x00' + bytes(17 if n == 5 else 0 for n in range(1, 17)) + bytes(range(17))
    head += b'\xff\xc3' + struct.pack('>HBHHB', 8+3*nc, 16, h, w, nc) + b''.join(bytes((i+1, 0x11, 0)) for i in range(nc))
    head += b'\xff\xda' + struct.pack('>HB', 6+2*nc, nc) + b''.join(bytes((i+1, 0)) for i in range(nc)) + bytes((1, 0, 0))
    return head + data + b'\xff\xd9'


def jxl(tile, distance, effort):
    import imagecodecs
    if distance > 0:
        return imagecodecs.jpegxl_encode(tile, distance=distance, effort=effort, numthreads=8)
    return imagecodecs.jpegxl_encode(tile, lossless=True, effort=effort, numthreads=8)


def write(path, rgb, neutral, curve='noise', compression='jxl', distance=0.0, effort=3,
          noise=None):
    h, w, _ = rgb.shape
    table = None
    if curve == 'noise':
        encode, table = noise_curve(*noise)  # noise = dng_noise(raw, meta)
        data = encode(rgb)
    else:
        data = np.round(np.clip(rgb, 0, 1)*65535).astype(np.uint16)
    th, tw = -(-h//TILE), -(-w//TILE)
    padded = np.pad(data, ((0, th*TILE-h), (0, tw*TILE-w), (0, 0)), mode='edge')
    tiles = []
    for ty in range(th):
        for tx in range(tw):
            t = np.ascontiguousarray(padded[ty*TILE:(ty+1)*TILE, tx*TILE:(tx+1)*TILE])
            tiles.append(ljpeg(t) if compression == 'ljpeg' else jxl(t, distance, effort))
    tags = {
        254: (4, (0,)), 256: (4, (w,)), 257: (4, (h,)), 258: (3, (16, 16, 16)),
        259: (3, (7 if compression == 'ljpeg' else 52546,)), 262: (3, (34892,)),
        271: (2, b'samsung\0'), 272: (2, b'SM-S928B\0'), 274: (3, (1,)), 277: (3, (3,)), 284: (3, (1,)),
        305: (2, b'S24 HighRes lumdir test\0'),
        322: (4, (TILE,)), 323: (4, (TILE,)), 324: (4, [0]*len(tiles)), 325: (4, [len(t) for t in tiles]),
        50706: (1, (1, 7, 0, 0) if compression == 'jxl' else (1, 4, 0, 0)),
        50707: (1, (1, 7, 0, 0) if compression == 'jxl' else (1, 1, 0, 0)),
        50708: (2, b'samsung SM-S928B\0'),
        50714: (3, (0, 0, 0)), 50717: (4, (65535, 65535, 65535)),
        50728: (5, sum(((int(round(n*1e6)), 1000000) for n in neutral), ())),
        50730: (10, (0, 1)),
        **COLOUR,
    }
    if table is not None:
        tags[50712] = (3, tuple(int(v) for v in table))
    if compression == 'jxl':
        tags[52553] = (11, (float(distance),))
        tags[52554] = (4, (effort,))
        tags[52555] = (4, (4,))
    sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 10: 8, 11: 4}
    fmt = {1: 'B', 3: 'H', 4: 'I', 5: 'I', 10: 'i', 11: 'f'}

    def payload(typ, vals):
        if typ == 2:
            return vals
        return struct.pack('<%d%s' % (len(vals), fmt[typ]), *vals)

    def count(typ, vals):
        return len(vals) if typ == 2 else len(vals)//2 if typ in (5, 10) else len(vals)

    ifd_size = 2+12*len(tags)+4
    extra = sum((len(payload(t, v))+1) & ~1 for t, v in tags.values() if len(payload(t, v)) > 4)
    data_start = 8+ifd_size+extra
    offsets, at = [], data_start
    for t in tiles:
        offsets.append(at)
        at += (len(t)+1) & ~1
    tags[324] = (4, offsets)
    out = bytearray(b'II*\0' + struct.pack('<I', 8))
    out += struct.pack('<H', len(tags))
    blob_at, blobs = 8+ifd_size, bytearray()
    for code in sorted(tags):
        typ, vals = tags[code]
        p = payload(typ, vals)
        out += struct.pack('<HHI', code, typ, count(typ, vals))
        if len(p) <= 4:
            out += p+b'\0'*(4-len(p))
        else:
            out += struct.pack('<I', blob_at+len(blobs))
            blobs += p+(b'\0' if len(p) & 1 else b'')
    out += struct.pack('<I', 0)
    out += blobs
    assert len(out) == data_start
    with open(path, 'wb') as f:
        f.write(out)
        for t in tiles:
            f.write(t)
            if len(t) & 1:
                f.write(b'\0')
    return at


if __name__ == '__main__':
    import json
    src, dst = sys.argv[1], sys.argv[2]
    curve = sys.argv[3] if len(sys.argv) > 3 else 'noise'
    comp = sys.argv[4] if len(sys.argv) > 4 else 'jxl'
    dist = float(sys.argv[5]) if len(sys.argv) > 5 else 0.0
    meta = json.load(open(src.replace('_out.npy', '_meta.json')))
    noise = dng_noise(np.load(src.replace('_out.npy', '_raw.npy')), meta)
    print(write(dst, np.load(src), meta['as_shot_neutral'], curve, comp, dist, noise=noise), 'bytes')
