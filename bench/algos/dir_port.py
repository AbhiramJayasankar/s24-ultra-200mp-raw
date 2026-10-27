"""Portable Directional16 specification (float32, normalized black-subtracted raw).

The coarse Menon refinement is omitted, one guide update is used, and the
bilateral is separable. Noise is estimated from the initial guide with a 0.75
variance multiplier (effective range denominator 3*variance). Statistics use
deterministic, spatially distributed frame samples. The variance blur is a 5x5
box and the range Gaussian has three taps per axis.
The Java implementation uses running sums for the boxes and a linear exp LUT.
reconstruct includes the downstream Bayer/Menon round trip, like dir_bayer.
"""
import numpy as np
from scipy.ndimage import uniform_filter, gaussian_filter, map_coordinates

try:
    from bench.algos._parallel import parallel_map, row_tiles
    from bench.algos.dir_core import masks, masked_fit
except ModuleNotFoundError:
    from algos._parallel import parallel_map, row_tiles
    from algos.dir_core import masks, masked_fit


ITERATIONS = 1
NOISE_SCALE = .75


def calibration(raw):
    h, w = raw.shape
    step = max(1, int(np.ceil(np.sqrt((h//8)*(w//8)/32768))))
    yy, xx = np.meshgrid(np.arange(0, h//8, step), np.arange(0, w//8, step), indexing='ij')
    gains, offsets = np.ones((8, 8), np.float32), np.zeros((8, 8), np.float32)
    p = np.arange(4, dtype=np.float32) - 1.5
    for cy, cx in [(0, 0), (0, 4), (4, 0), (4, 4)]:
        def block(y, x):
            return raw[y[..., None, None]*8+cy+np.arange(4)[None, None, :, None],
                       x[..., None, None]*8+cx+np.arange(4)[None, None, None, :]]
        a = block(yy, xx)
        mean = a.mean(axis=(2, 3))
        ym, yp = np.maximum(yy-1, 0), np.minimum(yy+1, h//8-1)
        xm, xp = np.maximum(xx-1, 0), np.minimum(xx+1, w//8-1)
        dy = (block(yp, xx).mean(axis=(2, 3))-block(ym, xx).mean(axis=(2, 3)))/(yp-ym)
        dx = (block(yy, xp).mean(axis=(2, 3))-block(yy, xm).mean(axis=(2, 3)))/(xp-xm)
        residual = a - (mean[..., None, None]+dy[..., None, None]*p[None, None, :, None]/8
                        + dx[..., None, None]*p[None, None, None, :]/8)
        scatter = np.sqrt(np.mean(residual**2, axis=(2, 3)))
        ok = ((mean > .035) & (mean < .72) & (scatter < .007+.045*mean)
              & (np.hypot(dy, dx) < .01+.08*mean) & (a.max(axis=(2, 3)) < .95))
        if ok.sum() < 128:
            continue
        cuts = np.unique(np.quantile(mean[ok], np.linspace(0, 1, 13)))
        xs, ys, weights = [], [], []
        for lo, hi in zip(cuts[:-1], cuts[1:]):
            take = ok & (mean >= lo) & (mean <= hi)
            if take.sum() >= 48:
                xs.append(np.median(mean[take]))
                ys.append(np.median(residual[take], axis=0))
                weights.append(np.sqrt(take.sum()))
        if len(xs) < 3:
            continue
        design = np.column_stack([xs, np.ones(len(xs))])
        weights = np.asarray(weights)
        fit = np.linalg.lstsq(design*weights[:, None],
                              np.asarray(ys).reshape(-1, 16)*weights[:, None], rcond=None)[0]
        gain, offset = fit.reshape(2, 4, 4)
        gain -= gain.mean()
        offset -= offset.mean()
        gains[cy:cy+4, cx:cx+4] = np.clip(1+gain, .9, 1.1)
        offsets[cy:cy+4, cx:cx+4] = np.clip(offset, -.01, .01)
    return gains, offsets


def coarse_rgb(raw):
    from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007
    h, w = raw.shape
    b = np.maximum(raw.reshape(h//4, 4, w//4, 4).mean(axis=(1, 3)), 0)
    rgb = demosaicing_CFA_Bayer_Menon2007(b, 'GBRG', refining_step=False).astype(np.float32)
    out = np.empty((h, w, 3), np.float32)
    x = (np.arange(w, dtype=np.float32)-1.5)/4

    def tile(bounds):
        lo, hi = bounds
        # Broadcast into a small coordinate buffer, rather than constructing
        # several full-frame coordinate arrays and three channel outputs.
        coords = np.empty((2, hi-lo, w), np.float32)
        coords[0] = ((np.arange(lo, hi, dtype=np.float32)-1.5)/4)[:, None]
        coords[1] = x
        for c in range(3):
            map_coordinates(rgb[..., c], coords, output=out[lo:hi, :, c], order=1, mode='nearest')

    parallel_map(tile, row_tiles(h))
    return out


def initial(raw, neutral, mm):
    balanced = raw / sum(neutral[c]*mm[c] for c in range(3))
    base = coarse_rgb(balanced)
    native = sum(base[..., c]*mm[c] for c in range(3))
    return balanced, balanced*np.clip(base[..., 1]/np.maximum(native, .01), .25, 4)


def noise_model(guide):
    h, w = guide.shape
    step = max(1, int(np.ceil(np.sqrt((h//2)*(w//2)/65536))))
    step |= 1  # An even stride aliases native 2x2 phases (e.g. samples only G).
    y, x = np.meshgrid(np.arange(0, h//2, step)*2, np.arange(0, w//2, step)*2, indexing='ij')
    a, b, c, d = guide[y, x], guide[y, x+1], guide[y+1, x], guide[y+1, x+1]
    hh, mean = (a-b-c+d)*.5, (a+b+c+d)*.25
    colours = np.where((y//4 % 2) == (x//4 % 2), 1, np.where(y//4 % 2, 0, 2))
    params = []
    for colour in range(3):
        use = (colours == colour) & (mean > .03) & (mean < 1.3)
        cuts = np.unique(np.quantile(mean[use], np.linspace(0, 1, 10))) if use.sum() >= 128 else []
        xs, ys = [], []
        for lo, hi in zip(cuts[:-1], cuts[1:]):
            take = use & (mean >= lo) & (mean <= hi)
            if take.sum() > 64:
                xs.append(np.median(mean[take]))
                ys.append((np.median(np.abs(hh[take]))/.67449)**2)
        if len(xs) < 2:
            params.append((0., .0001))
        else:
            a, b = np.linalg.lstsq(np.column_stack([xs, np.ones(len(xs))]), ys, rcond=None)[0]
            params.append((max(a, 0), max(b, 1e-7)))
    return np.asarray(params, np.float32)


def clean(guide, mm, params):
    """Two 7-tap joint bilateral passes, H then V, with a fixed range guide."""
    var = sum(mm[c]*(params[c, 0]*np.maximum(guide, 0)+params[c, 1]) for c in range(3))
    var = uniform_filter(var, 5)
    reference = gaussian_filter(guide, .7, radius=1)
    h, w = guide.shape
    lut = np.exp(-np.arange(4097, dtype=np.float64)/256).astype(np.float32)
    inverse = 1 / ((4*NOISE_SCALE)*var+np.float32(1e-8))
    del var
    rp = np.pad(reference, 3, mode='reflect')
    for dy, dx in [(0, 1), (1, 0)]:
        gp = np.pad(guide, 3, mode='reflect')
        result = np.empty_like(guide)

        def tile(bounds):
            lo, hi = bounds
            ref, inv = reference[lo:hi], inverse[lo:hi]
            out = np.zeros_like(ref)
            total = np.zeros_like(ref)
            # Accumulate taps in the original order, independently per pixel.
            for k in range(-3, 4):
                sl = (slice(3+k*dy+lo, 3+k*dy+hi), slice(3+k*dx, 3+k*dx+w))
                diff = rp[sl]-ref
                if diff.dtype != np.float32 or inv.dtype != np.float32:
                    # Keep NumPy's original dtype promotion for direct callers
                    # using float64 guides or noise parameters.
                    t = np.minimum((np.float32(k*k/4.5)+diff*diff*inv)*256, np.float32(4095.9998))
                    i = t.astype(np.int32)
                    weight = lut[i]+(lut[i+1]-lut[i])*(t-i.astype(np.float32))
                    out += weight*gp[sl]
                    total += weight
                    continue
                np.square(diff, out=diff)
                diff *= inv
                diff += np.float32(k*k/4.5)
                diff *= 256
                np.minimum(diff, np.float32(4095.9998), out=diff)
                i = diff.astype(np.int32)
                weight = lut[i]
                delta = lut[i+1]
                delta -= weight
                diff -= i.astype(np.float32)
                delta *= diff
                weight += delta
                total += weight
                weight *= gp[sl]
                out += weight
            result[lo:hi] = out/total

        parallel_map(tile, row_tiles(h, 32))
        guide = result
    return guide


def camera_rgb(raw, meta):
    return _camera_rgb(raw, meta)


def _fit_channels(guide, balanced, mm, output=None):
    """Cache-sized affine fits with the two box passes' complete halo.

    Two radius-6 filters need 12 rows of input halo. Internal tile edges are
    discarded, so only the real image edges see reflect boundary conditions.
    Each filter keeps ndimage's axis order and float32 intermediate rounding.
    """
    h = guide.shape[0]
    result = ([np.empty_like(guide) for _ in mm] if output is None else
              [output[..., c] for c in range(len(mm))])

    def fit(task):
        c, lo, hi = task
        start, stop = max(0, lo-12), min(h, hi+12)
        tile = masked_fit(guide[start:stop], balanced[start:stop], mm[c][start:stop])
        result[c][lo:hi] = tile[lo-start:hi-start]

    parallel_map(fit, [(c, lo, hi) for c in range(len(mm)) for lo, hi in row_tiles(h)])
    return result


def _camera_rgb(raw, meta, *, return_working=False):
    raw = np.asarray(raw, np.float32)
    if raw.ndim != 2 or any(n < 16 or n % 8 for n in raw.shape):
        raise ValueError('Expected dimensions >=16 and divisible by 8')
    gains, offsets = calibration(raw)
    h, w = raw.shape
    corrected = ((raw.reshape(h//8, 8, w//8, 8)-offsets[None, :, None, :])
                 / gains[None, :, None, :]).reshape(h, w).astype(np.float32)
    mm, neutral = masks(raw.shape), np.asarray(meta['as_shot_neutral'], np.float32)
    balanced, guide = initial(corrected, neutral, mm)
    del corrected
    params = noise_model(guide)
    for _ in range(ITERATIONS):
        fits = _fit_channels(guide, balanced, mm)
        native = sum(fits[c]*mm[c] for c in range(3))
        guide = balanced*np.clip(fits[1]/np.maximum(native, .01), .3, 3)
        del fits, native
    guide = clean(guide, mm, params)
    rgb = np.empty((*raw.shape, 3), np.float32)
    _fit_channels(guide, balanced, mm, output=rgb)
    del guide
    rgb *= neutral
    np.maximum(rgb, 0, out=rgb)
    if return_working:
        return rgb, balanced, mm
    return rgb


def bayer(raw, meta):
    """GBRG, normalized camera units; Java adds black and rounds/clamps to white."""
    rgb = camera_rgb(raw, meta)
    out = rgb[..., 1].copy()
    out[0::2, 1::2] = rgb[0::2, 1::2, 2]
    out[1::2, 0::2] = rgb[1::2, 0::2, 0]
    # The RowSource has a finite white level; specify that same clipping here.
    black = meta.get('black', 64)
    white = meta.get('white', 1023)
    return np.clip(out, -black/(white-black), 1).astype(np.float32)


def reconstruct(raw, meta):
    from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007
    return demosaicing_CFA_Bayer_Menon2007(bayer(raw, meta), 'GBRG').astype(np.float32)
