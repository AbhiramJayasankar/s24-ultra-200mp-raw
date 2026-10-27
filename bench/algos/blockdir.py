"""Edge-directed reconstruction at the 16-cell's 4x4-block scale.

Layout (rows of 4x4 blocks): G B G B / R G R G. Every R or B block has G blocks directly
left, right, above and below. Green inside an R/B block is estimated four ways from those
neighbours, as the pixel's own (white-balanced) value plus the colour difference G - X
interpolated across the block, then blended by how smooth each direction is. On neutral
detail the colour difference is ~0, so every pixel keeps its own value (full 200MP detail);
on coloured edges, where X carries no edge information, the direction that runs along
the edge wins. Red and blue are then rebuilt from the full green with a colour difference
spread by a filter that follows green edges, so colour lines up with the sharp green.
"""
import numpy as np
from scipy.ndimage import gaussian_filter, maximum_filter, uniform_filter, median_filter

try:
    import bench.algos.lumdir as lumdir
    from bench.algos.dir_core import masks
    from bench.algos.dir_port import calibration
except ModuleNotFoundError:
    import lumdir
    from dir_core import masks
    from dir_port import calibration

PAD = 16
EPS = 1e-4
CD_SIGMA = 2.0       # px; spread of the colour-difference fill
RANGE = 0.04         # green similarity (white-balanced units) for the colour fill
PHASE_WINDOW = 96    # px; local per-position flattening window (0 = off)
SAT_LO, SAT_HI = 0.04, 0.12   # local colour saturation where the directional fill fades in
DEBUG = None                  # path to save the colour-path weight map
REF_CELLS = 33                # 8 px cells -> ~256 px reference neighbourhood
SAT_BOX = 48                  # px; wide enough that black/white stripes average out


def phase_flatten_colour(b, mm, size=PHASE_WINDOW):
    """Removes slowly varying per-position sensitivity, comparing each position only with
    pixels of its own colour so real colour is left alone."""
    h, w = b.shape
    ref = np.zeros_like(b)
    for m in mm:
        mf = m.astype(np.float32)
        local = uniform_filter(b*mf, 8)/np.maximum(uniform_filter(mf, 8), 1e-6)
        ref += local*mf
    rel = b/np.maximum(ref, 1e-3)
    g = rel.reshape(h//8, 8, w//8, 8).transpose(1, 3, 0, 2)
    smooth = np.empty_like(g)
    for a in range(8):
        for c in range(8):
            smooth[a, c] = median_filter(np.ascontiguousarray(g[a, c]), size=max(3, size//8), mode='reflect')
    # Normalise within each 4x4 colour block so colour balance is untouched.
    for a0 in (0, 4):
        for c0 in (0, 4):
            blk = smooth[a0:a0+4, c0:c0+4]
            blk /= blk.mean(axis=(0, 1), keepdims=True)
    return b/smooth.transpose(2, 0, 3, 1).reshape(h, w)


def green(b, gmask):
    h, w = b.shape
    y, x = np.indices((h, w))
    oy, ox = y % 4, x % 4
    bl, br = np.clip(x-ox-1, 0, w-1), np.clip(x-ox+4, 0, w-1)     # nearest G column left/right
    bu, bd = np.clip(y-oy-1, 0, h-1), np.clip(y-oy+4, 0, h-1)     # nearest G row above/below
    xin_l, xin_r = np.clip(x-ox, 0, w-1), np.clip(x-ox+3, 0, w-1)  # block's own edge pixels
    yin_u, yin_d = np.clip(y-oy, 0, h-1), np.clip(y-oy+3, 0, h-1)
    dl, dr = (ox+1).astype(np.float32), (4-ox).astype(np.float32)
    du, dd = (oy+1).astype(np.float32), (4-oy).astype(np.float32)
    # Colour difference G - X at the block boundary on each side.
    Dl = b[y, bl]-b[y, xin_l]
    Dr = b[y, br]-b[y, xin_r]
    Du = b[bu, x]-b[yin_u, x]
    Dd = b[bd, x]-b[yin_d, x]
    gh = b+(Dl*dr+Dr*dl)/(dl+dr)
    gv = b+(Du*dd+Dd*du)/(du+dd)
    # Smoothness of each direction: change in green across the block plus change of the
    # colour difference (an edge crossing that direction makes both jump), pooled over a
    # small neighbourhood along the other axis.
    eh = np.abs(b[y, bl]-b[y, br])+np.abs(Dl-Dr)
    ev = np.abs(b[bu, x]-b[bd, x])+np.abs(Du-Dd)
    eh = uniform_filter(eh, (5, 1))
    ev = uniform_filter(ev, (1, 5))
    wh, wv = 1/(EPS+eh)**2, 1/(EPS+ev)**2
    g = (wh*gh+wv*gv)/(wh+wv)
    return np.where(gmask, b, g)


def channel_means(b, mm):
    """Each colour's local mean over SAT_BOX (whole super-cells) and their average."""
    means = [uniform_filter(b*m, SAT_BOX)/np.maximum(uniform_filter(m.astype(np.float32), SAT_BOX), 1e-6) for m in mm]
    return means, (means[0]+means[1]+means[2])/3


def wide_median(a):
    """~256 px median (on 8 px cells), e.g. the paper's colour or brightness."""
    h, w = a.shape
    small = a[:h//8*8, :w//8*8].reshape(h//8, 8, w//8, 8).mean(axis=(1, 3))
    ref = np.repeat(np.repeat(median_filter(small, size=REF_CELLS, mode='reflect'), 8, 0), 8, 1)
    full = np.full_like(a, float(np.median(small)))
    full[:ref.shape[0], :ref.shape[1]] = ref
    return full


def saturation(b, y):
    """Colour contrast against the wider surroundings, in units of their brightness.

    Each 8x8 cell has two green blocks: Ga shares columns with red and rows with blue,
    Gb shares columns with blue and rows with red. Stripes near the 8 px period alias
    into a fake red/blue difference, but then red matches the green it shares columns
    (or rows) with. Real colour differs from both greens, so each colour's score is the
    smaller of its two comparisons. A lighting cast does not count (compared with the
    256 px surroundings) and dark neutral ink scores ~0 (absolute units)."""
    h, w = b.shape
    yy, xx = np.indices((h, w), sparse=True)
    by, bx = (yy//4) % 2, (xx//4) % 2
    sel = {"R": (by == 1) & (bx == 0), "B": (by == 0) & (bx == 1),
           "Ga": (by == 0) & (bx == 0), "Gb": (by == 1) & (bx == 1)}
    mean = {k: uniform_filter(b*np.broadcast_to(v, b.shape), SAT_BOX)
            / np.maximum(uniform_filter(np.broadcast_to(v, b.shape).astype(np.float32), SAT_BOX), 1e-6)
            for k, v in sel.items()}
    ybright = np.maximum(wide_median(y), 1e-3)
    dev = []
    for c in ("R", "B"):
        pair = []
        for g in ("Ga", "Gb"):
            ref = wide_median(mean[c]/np.maximum(mean[g], 1e-3))
            pair.append(np.abs(mean[c]-mean[g]*ref)/ybright)
        dev.append(np.minimum(*pair))
    return gaussian_filter(maximum_filter(np.max(dev, axis=0), 9), 2)


def colour_fill(diff, known, g, sigma=CD_SIGMA, rng=RANGE):
    """Normalised convolution of a colour difference from its known pixels, with weights
    reduced across green edges (cross-bilateral, separable approximation)."""
    num, den = diff*known, known.astype(np.float32)
    r = int(np.ceil(3*sigma))
    for axis in (0, 1):
        n2, d2 = np.zeros_like(num), np.zeros_like(den)
        for k in range(-r, r+1):
            ws = np.exp(-.5*(k/sigma)**2)
            gs = np.roll(g, k, axis=axis)
            wr = ws*np.exp(-.5*((gs-g)/rng)**2)
            n2 += wr*np.roll(num, k, axis=axis)
            d2 += wr*np.roll(den, k, axis=axis)
        num, den = n2, d2
    return num/np.maximum(den, 1e-6)


def camera_rgb(raw, meta):
    raw = np.asarray(raw, np.float32)
    raw = np.pad(raw, PAD, mode='reflect')
    h, w = raw.shape
    gains, offsets = calibration(raw)
    corrected = ((raw.reshape(h//8, 8, w//8, 8)-offsets[None, :, None, :])
                 / gains[None, :, None, :]).reshape(h, w)
    neutral = np.asarray(meta['as_shot_neutral'], np.float32)
    mm = masks(raw.shape)
    mm = [np.broadcast_to(m, raw.shape) for m in mm]
    b = corrected/sum(mm[c]*neutral[c] for c in range(3))
    if PHASE_WINDOW > 0:
        b = phase_flatten_colour(b, mm)
    means, ymean = channel_means(b, mm)
    wdir = np.clip((saturation(b, ymean)-SAT_LO)/(SAT_HI-SAT_LO), 0, 1)
    # Colour path: edge-directed green, then red/blue aligned to it.
    g = green(b, mm[1])
    gs = gaussian_filter(g, .7)
    r = np.where(mm[0], b, g+colour_fill(b-g, mm[0], gs))
    bl = np.where(mm[2], b, g+colour_fill(b-g, mm[2], gs))
    colour_rgb = np.stack([r, g, bl], -1)[PAD:-PAD, PAD:-PAD]*neutral
    wdir = wdir[PAD:-PAD, PAD:-PAD]
    if DEBUG:
        np.save(DEBUG, wdir)
    # Neutral path: lumdir (each pixel's own value, colour from a smoothed reference),
    # which is clean on black/white detail and blank paper.
    neutral_rgb = lumdir.camera_rgb(raw[PAD:-PAD, PAD:-PAD], meta)
    rgb = wdir[..., None]*colour_rgb+(1-wdir[..., None])*neutral_rgb
    return np.maximum(rgb, 0).astype(np.float32)


def reconstruct(raw, meta):
    return camera_rgb(raw, meta)
