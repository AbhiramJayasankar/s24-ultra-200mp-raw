"""Local, CPU-only helpers for the directional 16-cell experiment.

No frame references, file access, learned scene masks or cached calibration.
All radii are in native sensor pixels.
"""
import numpy as np
from scipy.ndimage import gaussian_filter, uniform_filter, map_coordinates


def masks(shape):
    y, x = np.indices(shape, sparse=True)
    by, bx = (y // 4) % 2, (x // 4) % 2
    return [(by == 1) & (bx == 0), by == bx, (by == 0) & (bx == 1)]


def calibration(raw, affine=True, return_table=False):
    """Robust per-8x8-phase affine correction from locally smooth 4x4 blocks.

    Regress phase-minus-block-mean against block signal using medians in
    signal bins. Same-colour block means at +/-8 estimate scene slopes;
    subtract those before pooling phases. Mean gain/offset corrections are zero
    within each colour block; the calibration cannot set global colour balance.
    Saturated, very dark and strongly structured blocks are excluded.
    """
    h, w = raw.shape
    cells = raw.reshape(h // 8, 8, w // 8, 8)
    gains = np.ones((8, 8), np.float32)
    offsets = np.zeros((8, 8), np.float32)
    p = np.arange(4, dtype=np.float32) - 1.5
    for cy, cx in [(0, 0), (0, 4), (4, 0), (4, 4)]:
        a = cells[:, cy:cy+4, :, cx:cx+4].transpose(0, 2, 1, 3)
        mean = a.mean(axis=(2, 3))
        dy, dx = np.gradient(mean)
        pred = (mean[..., None, None] + dy[..., None, None] * p[None, None, :, None] / 8
                + dx[..., None, None] * p[None, None, None, :] / 8)
        residual = a - pred
        scatter = np.sqrt(np.mean(residual**2, axis=(2, 3)))
        ok = ((mean > .035) & (mean < .72) &
              (scatter < .007 + .045 * mean) &
              (np.hypot(dy, dx) < .01 + .08 * mean) &
              (a.max(axis=(2, 3)) < .95))
        if np.count_nonzero(ok) < 128:
            continue
        if affine:
            # Equal-population bins avoid letting a broad bright region dominate.
            cuts = np.unique(np.quantile(mean[ok], np.linspace(0, 1, 13)))
            xs, ys, weights = [], [], []
            for lo, hi in zip(cuts[:-1], cuts[1:]):
                take = ok & (mean >= lo) & (mean <= hi)
                if take.sum() < 48:
                    continue
                xs.append(float(np.median(mean[take])))
                ys.append(np.median(residual[take], axis=0))
                weights.append(np.sqrt(take.sum()))
            if len(xs) < 3:
                continue
            design = np.column_stack([xs, np.ones(len(xs))])
            ww = np.asarray(weights)
            fit = np.linalg.lstsq(design * ww[:, None],
                                  np.asarray(ys).reshape(-1, 16) * ww[:, None], rcond=None)[0]
            gain = fit[0].reshape(4, 4)
            offset = fit[1].reshape(4, 4)
        else:
            gain = np.median(residual[ok] / mean[ok, None, None], axis=0)
            offset = np.zeros((4, 4))
        gain -= gain.mean()
        offset -= offset.mean()
        gains[cy:cy+4, cx:cx+4] = np.clip(1 + gain, .9, 1.1)
        offsets[cy:cy+4, cx:cx+4] = np.clip(offset, -.01, .01)
    corrected = ((cells - offsets[None, :, None, :]) /
                 gains[None, :, None, :]).reshape(h, w).astype(np.float32)
    if return_table:
        return corrected, gains, offsets
    return corrected


def filt(a, radius):
    return uniform_filter(a, 2 * radius + 1, mode='reflect')


def coarse_rgb(raw):
    """Demosaic the 4x4 means at their true block-centre coordinates."""
    from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007
    h, w = raw.shape
    b = raw.reshape(h//4, 4, w//4, 4).mean(axis=(1, 3))
    rgb = demosaicing_CFA_Bayer_Menon2007(np.maximum(b, 0), 'GBRG').astype(np.float32)
    y, x = np.indices((h, w), dtype=np.float32)
    coords = [(y-1.5)/4, (x-1.5)/4]
    return np.stack([map_coordinates(rgb[..., c], coords, order=1, mode='nearest')
                     for c in range(3)], axis=-1)


def masked_fit(guide, sample, mask, radius=6, eps=.0001):
    """Two-pass local affine residual interpolation using native samples only."""
    mf = mask.astype(np.float32)
    local = lambda a: filt(a, radius)
    n = np.maximum(local(mf), .001)
    mi = local(guide * mf) / n
    mp = local(sample * mf) / n
    var = np.maximum(local(guide * guide * mf) / n - mi * mi, 0)
    cov = local(guide * sample * mf) / n - mi * mp
    a = cov / (var + eps)
    b = mp - a * mi
    return local(a) * guide + local(b)


def directional_fill(residual, mask, guide):
    """Interpolate residual along H, V, and both diagonal directions.

    Direction support is 17 pixels, enough to bridge a missing 4x4 block.
    Weights use the common full-resolution guide, never native colour jumps.
    """
    kernel = np.maximum(0, 1 - np.abs(np.arange(-8, 9))/9).astype(np.float32)
    numerator = residual * mask
    mf = mask.astype(np.float32)
    candidates, weights = [], []
    for dy, dx in [(0, 1), (1, 0), (1, 1), (1, -1)]:
        n = np.zeros_like(guide)
        d = np.zeros_like(guide)
        energy = np.zeros_like(guide)
        pad = np.pad(guide, 9, mode='reflect')
        rp = np.pad(numerator, 9, mode='reflect')
        mp = np.pad(mf, 9, mode='reflect')
        h, w = guide.shape
        for k, kval in zip(range(-8, 9), kernel):
            sl = (slice(9+k*dy, 9+k*dy+h), slice(9+k*dx, 9+k*dx+w))
            n += kval * rp[sl]
            d += kval * mp[sl]
            if k:
                energy += kval * np.abs(pad[sl] - guide)
        weight = (d > .1) / (.005 + energy / kernel.sum())**2
        candidates.append(n / np.maximum(d, .001))
        weights.append(weight)
    total = np.maximum(sum(weights), 1e-8)
    return sum(c * w for c, w in zip(candidates, weights)) / total


def initial_guide(raw, mm):
    base = coarse_rgb(raw)
    native = sum(base[..., c] * mm[c] for c in range(3))
    return raw * np.clip(base[..., 1] / np.maximum(native, .01), .25, 4), base


def noise_model(guide, mm):
    """Estimate var=a*signal+b from in-block 2x2 diagonal Haar responses.

    Only 2x2 squares wholly inside a native block are used; colour changes
    therefore cannot be mistaken for noise. Binned medians reject most edges.
    """
    hh = (guide[0::2, 0::2] - guide[0::2, 1::2] -
          guide[1::2, 0::2] + guide[1::2, 1::2]) * .5
    mean = (guide[0::2, 0::2] + guide[0::2, 1::2] +
            guide[1::2, 0::2] + guide[1::2, 1::2]) * .25
    var = np.zeros_like(guide)
    params = []
    for m in mm:
        use = m[0::2, 0::2] & (mean > .03) & (mean < 1.3)
        cuts = (np.unique(np.quantile(mean[use], np.linspace(0, 1, 10)))
                if use.sum() >= 128 else np.array([]))
        xs, ys = [], []
        for lo, hi in zip(cuts[:-1], cuts[1:]):
            take = use & (mean >= lo) & (mean <= hi)
            if take.sum() > 64:
                xs.append(np.median(mean[take]))
                ys.append((np.median(np.abs(hh[take])) / .67449)**2)
        if len(xs) < 2:
            a, b = 0., .0001
        else:
            a, b = np.linalg.lstsq(np.column_stack([xs, np.ones(len(xs))]), ys, rcond=None)[0]
            a, b = max(a, 0), max(b, 1e-7)
        params.append((float(a), float(b)))
        var += m * (a * np.maximum(guide, 0) + b)
    return var, params


def bilateral_clean(guide, mm, strength=1., spatial=1.2):
    """Noise-scaled joint bilateral using a mildly smoothed range guide."""
    var, _ = noise_model(guide, mm)
    var = gaussian_filter(var, 1.5)
    reference = gaussian_filter(guide, .7)
    support = int(np.ceil(spatial * 2))
    rp = np.pad(reference, support, mode='reflect')
    gp = np.pad(guide, support, mode='reflect')
    h, w = guide.shape
    out, total = np.zeros_like(guide), np.zeros_like(guide)
    for dy in range(-support, support+1):
        for dx in range(-support, support+1):
            distance = (dy*dy + dx*dx) / (spatial*spatial)
            if distance > 6:
                continue
            sl = (slice(support+dy, support+dy+h), slice(support+dx, support+dx+w))
            weight = np.exp(-.5*distance - (rp[sl] - reference)**2 /
                            (2 * strength * var + 1e-8))
            out += weight * gp[sl]
            total += weight
    return out / total


def reconstruct_impl(raw, meta, calibrate=True, clean=0, method='fit',
                     iterations=2, radius=6, eps=.0001,
                     bilateral=0, spatial=1.2):
    raw = np.asarray(raw, dtype=np.float32)
    if raw.ndim != 2 or any(n < 16 or n % 8 for n in raw.shape):
        raise ValueError('Expected a 2D 16-cell crop, dimensions >=16 and divisible by 8')
    raw = calibration(raw) if calibrate else raw
    mm = masks(raw.shape)
    neutral = np.asarray(meta['as_shot_neutral'], np.float32)
    balanced = raw / sum(neutral[c] * mm[c] for c in range(3))
    guide, _ = initial_guide(balanced, mm)
    for _ in range(iterations):
        fits = [masked_fit(guide, balanced, m, radius, eps) for m in mm]
        native = sum(fits[c] * mm[c] for c in range(3))
        # Transfer native sample detail through the local channel-to-green ratio.
        guide = balanced * np.clip(fits[1] / np.maximum(native, .01), .3, 3)
    if clean:
        # Shrink the native residual; no residual coefficient can exceed one.
        smooth = gaussian_filter(guide, .75, mode='reflect')
        detail = guide - smooth
        var = filt(detail*detail, 2)
        p = np.abs(detail)[(guide > .04) & (guide < .8)]
        sigma = np.percentile(p, 45) / .598 if p.size else .01
        floor = clean * sigma*sigma
        keep = np.clip((var - floor) / (var + 1e-8), 0, 1)
        guide = smooth + keep * detail
    if bilateral:
        guide = bilateral_clean(guide, mm, bilateral, spatial)
    if method == 'directional':
        g = guide + directional_fill(balanced-guide, mm[1], guide)
        channels = [g + directional_fill(balanced-g, m, guide) for m in mm]
        channels[1] = g
    else:
        channels = [masked_fit(guide, balanced, m, radius, eps) for m in mm]
    # Undo the working white balance; the harness gets linear CAMERA RGB.
    return np.maximum(np.stack(channels, axis=-1) * neutral, 0).astype(np.float32)
