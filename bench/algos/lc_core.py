"""Shared raw-only luminance/chroma experiment. No reference frame is used here.

The 4x4 means retain both green blocks as separate Bayer samples. Chroma is
deliberately band-limited before division into the measured full-resolution luma.
All spatial scales below are in native sensor pixels unless stated otherwise.
"""
import numpy as np
from scipy.ndimage import gaussian_filter, uniform_filter, map_coordinates


def colours(shape):
    y, x = np.indices(shape, sparse=True)
    return np.broadcast_to(np.where(((y // 4) & 1) == ((x // 4) & 1), 1,
                                    np.where(((y // 4) & 1) == 0, 2, 0)), shape)


def calibration(raw, native, affine=False):
    """Fit 64 multiplicative phases to smooth same-colour predictions.

    Robust residual rejection and a low-gradient mask prevent text/colour edges
    being interpreted as pixel sensitivity. Normalize within each native colour:
    this is an intra-colour calibration, not a replacement white balance.
    """
    gain = np.ones((8, 8), np.float32)
    offset = np.zeros((8, 8), np.float32)
    for c in range(3):
        mask = (native == c).astype(np.float32)
        den = gaussian_filter(mask, 8, mode='reflect')
        pred = gaussian_filter(raw * mask, 8, mode='reflect') / np.maximum(den, 1e-6)
        dy, dx = np.gradient(pred)
        weight = mask * (pred > .035) * (pred < .8)
        weight *= np.exp(-((dx * dx + dy * dy) / np.maximum(pred * pred, .001)) / .0003)
        ratio = raw / np.maximum(pred, .02)
        for y in range(8):
            for x in range(8):
                if native[y, x] != c:
                    continue
                w = weight[y::8, x::8]
                r = ratio[y::8, x::8]
                if float(w.sum()) < 16:
                    # An all-black/clipped or tiny crop cannot calibrate this
                    # phase. Leave unity response rather than divide by zero.
                    continue
                centre = np.sum(w * np.clip(r, .5, 1.5)) / max(float(w.sum()), 1)
                for _ in range(3):
                    rw = w * np.maximum(1 - ((r - centre) / .4) ** 2, 0) ** 2
                    centre = np.sum(rw * r) / max(float(rw.sum()), 1)
                gain[y, x] = centre
                if affine:
                    pp, rr = pred[y::8, x::8], raw[y::8, x::8]
                    slope, intercept = centre, 0.0
                    for _ in range(4):
                        err = rr - slope * pp - intercept
                        rw = w * np.maximum(1 - (err / .09) ** 2, 0) ** 2
                        sw = max(float(rw.sum()), 1)
                        mp, mr = float((rw * pp).sum()) / sw, float((rw * rr).sum()) / sw
                        var = float((rw * (pp - mp) ** 2).sum()) / sw
                        cov = float((rw * (pp - mp) * (rr - mr)).sum()) / sw
                        slope = (cov + .0003) / (var + .0003)
                        intercept = mr - slope * mp
                    gain[y, x], offset[y, x] = slope, intercept
        cm = native[:8, :8] == c
        gain[cm] /= gain[cm].mean()
        offset[cm] -= offset[cm].mean()
    if affine:
        return np.clip(gain, .85, 1.15), np.clip(offset, -.02, .02)
    return np.clip(gain, .85, 1.15)


def residual_calibration(luma, spatial=False, sigma=8, shrink=.05):
    """Remove residual phase modulation after smooth colour division.

    Allows phases of different colours to have slightly different correction;
    estimated only in low-gradient regions. A 256px local fit, shrunk toward the
    global fit, can follow slowly changing microlens response.
    """
    smooth = gaussian_filter(luma, 8, mode='reflect')
    dy, dx = np.gradient(smooth)
    weight = np.exp(-(dx * dx + dy * dy) / np.maximum(smooth * smooth, .001) / .0001)
    weight *= (smooth > .04) * (smooth < 1.0)
    ratio = luma / np.maximum(smooth, .02)
    table = np.ones((8, 8), np.float32)
    fields = np.ones(luma.shape, np.float32) if spatial else None
    for y in range(8):
        for x in range(8):
            r, w = ratio[y::8, x::8], weight[y::8, x::8]
            if float(w.sum()) < 16:
                continue
            centre = float(np.sum(w * np.clip(r, .5, 1.5)) / max(float(w.sum()), 1))
            for _ in range(3):
                rw = w * np.maximum(1 - ((r - centre) / .4) ** 2, 0) ** 2
                centre = float(np.sum(rw * r) / max(float(rw.sum()), 1))
            table[y, x] = centre
            if spatial:
                num = gaussian_filter(rw * r, sigma, mode='reflect')
                den = gaussian_filter(rw, sigma, mode='reflect')
                fields[y::8, x::8] = (num + shrink * centre) / (den + shrink)
    table /= table.mean()
    if not spatial:
        fields = np.tile(table, (luma.shape[0] // 8, luma.shape[1] // 8))
    else:
        # Keep the average intensity of each supercell: only periodic response
        # changes, never a slowly varying exposure correction.
        h, w = luma.shape
        blocks = fields.reshape(h // 8, 8, w // 8, 8)
        blocks /= blocks.mean((1, 3))[:, None, :, None]
    return luma / np.clip(fields, .85, 1.15), table


def upsample(a, shape, scale=4):
    y = (np.arange(shape[0], dtype=np.float32) - (scale - 1) * .5) / scale
    x = (np.arange(shape[1], dtype=np.float32) - (scale - 1) * .5) / scale
    return map_coordinates(a, np.broadcast_arrays(y[:, None], x[None, :]),
                           order=1, mode='nearest', prefilter=False)


def guided(guide, target, radius, eps):
    """Scalar guided filter, separable box sums, O(N) regardless of radius."""
    n = 2 * radius + 1
    mean_i = uniform_filter(guide, n, mode='reflect')
    mean_p = uniform_filter(target, n, mode='reflect')
    cov = uniform_filter(guide * target, n, mode='reflect') - mean_i * mean_p
    var = np.maximum(uniform_filter(guide * guide, n, mode='reflect') - mean_i * mean_i, 0)
    a = cov / (var + eps)
    b = mean_p - a * mean_i
    return uniform_filter(a, n, mode='reflect') * guide + uniform_filter(b, n, mode='reflect')


def residual_offsets(luma, sigma=8, threshold=.001, shrink=.01):
    """Slowly varying additive phase residual, fitted only to flat areas.

    Gain-only fitting leaves a brightness-dependent alternating-row residual.
    Estimate each phase's high-pass offset over a broad window (sigma * 8 px),
    with a global prior where there is little flat-area support. Zero-sum within
    each supercell avoids changing the slowly varying brightness field.
    """
    smooth = gaussian_filter(luma, 6, mode='reflect')
    gy, gx = np.gradient(smooth)
    weight = np.exp(-(gx * gx + gy * gy) / np.maximum(smooth * smooth, .001) / threshold)
    weight *= (smooth > .03) * (smooth < 1)
    residual = luma - smooth
    correction = np.empty_like(luma)
    hh, ww = luma.shape
    for y in range(8):
        for x in range(8):
            w, d = weight[y::8, x::8], residual[y::8, x::8]
            global_fit = float((w * d).sum() / max(float(w.sum()), 1))
            correction[y::8, x::8] = ((gaussian_filter(w * d, sigma, mode='reflect') + shrink * global_fit) /
                                        (gaussian_filter(w, sigma, mode='reflect') + shrink))
    correction = correction.reshape(hh // 8, 8, ww // 8, 8)
    correction -= correction.mean((1, 3))[:, None, :, None]
    return luma - correction.reshape(hh, ww)


def denoise_sqrt(luma, detail=False):
    """Variance-stabilized guided regression, with a lightly denoised guide.

    Unlike the self-guided control, individual noisy pixels cannot directly
    become edges in the guide. No unsharp mask or high-frequency boost is used.
    """
    v = np.sqrt(np.maximum(luma, 0) + .01)
    residual = v - gaussian_filter(v, 1.2, mode='reflect')
    noise = float(np.median(np.abs(residual - np.median(residual))) / .67449 / .86)
    gs, radius, mult = (.65, 2, .25) if detail else (.8, 3, .5)
    vv = guided(gaussian_filter(v, gs, mode='reflect'), v, radius,
                max(noise * noise * mult, 1e-6))
    return np.maximum(vv ** 2 - .01, 0)


def prepare(raw, meta, calibrate=True, chroma_sigma=2.0):
    from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007
    raw = np.asarray(raw, np.float32)
    native = colours(raw.shape)
    if calibrate == 'affine':
        gain, offset = calibration(raw, native, affine=True)
        corrected = (raw - np.tile(offset, (raw.shape[0] // 8, raw.shape[1] // 8)))
        corrected /= np.tile(gain, (raw.shape[0] // 8, raw.shape[1] // 8))
    else:
        gain = calibration(raw, native) if calibrate else np.ones((8, 8), np.float32)
        corrected = raw / np.tile(gain, (raw.shape[0] // 8, raw.shape[1] // 8))
    neutral = np.asarray(meta['as_shot_neutral'], np.float32)
    h, w = raw.shape
    blocks = corrected.reshape(h // 4, 4, w // 4, 4).mean((1, 3))
    rgb = demosaicing_CFA_Bayer_Menon2007(np.maximum(blocks, 0), 'GBRG').astype(np.float32)
    rgb = np.maximum(rgb / neutral, .002)
    # Normalize after smoothing: no 4x4 step or per-supercell discontinuity.
    rgb = gaussian_filter(rgb, (chroma_sigma, chroma_sigma, 0), mode='reflect')
    low_l = rgb @ np.array([.25, .5, .25], np.float32)
    ratios = np.maximum(rgb / np.maximum(low_l[..., None], .01), .05)
    full = np.stack([upsample(ratios[..., c], raw.shape) for c in range(3)], -1)
    native_ratio = np.take_along_axis(full, native[..., None], axis=-1)[..., 0]
    luma = corrected / neutral[native] / native_ratio
    return luma, full, neutral, gain, native


def run_pipeline(raw, meta, calibrate=True, denoise='none', chroma_sigma=2.0,
                 chroma_guide=False, offset_correction=False):
    luma, ratios, neutral, _, _ = prepare(raw, meta, calibrate, chroma_sigma)
    if offset_correction:
        luma = residual_offsets(luma)
    if denoise == 'gauss':
        luma = gaussian_filter(luma, .65, mode='reflect')
    elif denoise == 'guided':
        # Control: global robust high-pass noise estimate in linear luma.
        low = gaussian_filter(luma, 1.2, mode='reflect')
        d = (luma - low)[2:-2, 2:-2]
        noise = float(np.median(np.abs(d - np.median(d))) / .67449)
        luma = guided(luma, luma, 2, max(noise * noise * 2.5, 1e-5))
    elif denoise in ('sqrt', 'sqrt_detail'):
        luma = denoise_sqrt(luma, detail=denoise == 'sqrt_detail')
    if chroma_guide:
        guide = gaussian_filter(luma, 1, mode='reflect')
        for c in range(3):
            ratios[..., c] = guided(guide, ratios[..., c], 8, .0025)
        ratios /= np.maximum(ratios @ np.array([.25, .5, .25], np.float32), .05)[..., None]
    return (np.maximum(luma, 0)[..., None] * ratios * neutral).astype(np.float32)
