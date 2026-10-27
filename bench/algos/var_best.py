"""Joint 16-cell inverse reconstruction; only the supplied RAW and metadata are used.

See bench/notes/var.md for the objective, calibration and benchmark results.
"""
import time

import numpy as np
from scipy.ndimage import gaussian_filter
from scipy.optimize import nnls


def colour_map(shape, origin=(0, 0)):
    y = ((np.arange(shape[0]) + origin[0]) // 4)[:, None] & 1
    x = ((np.arange(shape[1]) + origin[1]) // 4)[None, :] & 1
    return np.where(y == x, 1, np.where(y == 0, 2, 0)).astype(np.uint8)


def interpolate(raw, colours, sigma):
    out = []
    for c in range(3):
        mask = (colours == c).astype(np.float32)
        den = gaussian_filter(mask, sigma, mode="reflect")
        out.append(gaussian_filter(raw * mask, sigma, mode="reflect") /
                   np.maximum(den, 1e-5))
    return np.stack(out, axis=-1)


def calibrate(raw, colours):
    """Robust 8x8 relative gains, then an affine shot/read variance fit.

    Flat blocks are selected using low-pass colour gradients rather than small
    observed noise residuals. Plane residuals are adjusted for their 3 fitted DOF.
    """
    h, w = raw.shape
    hh, ww = h // 8 * 8, w // 8 * 8
    # An 8x8 phase can be compared with the same colour's smooth prediction.
    low = interpolate(raw, colours, 6.0)
    pred = np.take_along_axis(low, colours[..., None], axis=2)[..., 0]
    # Use gradient of each separately interpolated colour (avoid CFA edges).
    grad = sum(np.hypot(*np.gradient(low[..., c])) for c in range(3)) / 3
    sel = (grad < np.quantile(grad, .45)) & (pred > .025) & (pred < .8)
    table = np.ones((8, 8), np.float32)
    for y in range(8):
        for x in range(8):
            valid = sel[y:hh:8, x:ww:8]
            ratio = raw[y:hh:8, x:ww:8] / np.maximum(pred[y:hh:8, x:ww:8], .01)
            if np.count_nonzero(valid) > 32:
                table[y, x] = np.median(ratio[valid])
    c8 = colour_map((8, 8))
    for c in range(3):
        table[c8 == c] /= np.mean(table[c8 == c])
    table = np.clip(table, .90, 1.10)
    gains = np.tile(table, ((h + 7) // 8, (w + 7) // 8))[:h, :w]
    corrected = raw / gains
    blocks = corrected[:hh, :ww].reshape(hh // 4, 4, ww // 4, 4).transpose(0, 2, 1, 3)
    mean = blocks.mean(axis=(-2, -1))
    coord = np.arange(4, dtype=np.float32) - 1.5
    sy = (blocks * coord[None, None, :, None]).sum(axis=(-2, -1)) / 20
    sx = (blocks * coord[None, None, None, :]).sum(axis=(-2, -1)) / 20
    res = blocks - mean[..., None, None] - sy[..., None, None] * coord[:, None] - sx[..., None, None] * coord[None, :]
    variance = np.sum(res * res, axis=(-2, -1)) / 13
    flat = sel[:hh, :ww].reshape(hh // 4, 4, ww // 4, 4).mean(axis=(1, 3)) > .75
    fitx, fity, count = [], [], []
    for lo, hi in zip(np.linspace(.025, .8, 17)[:-1], np.linspace(.025, .8, 17)[1:]):
        m = flat & (mean >= lo) & (mean < hi)
        if m.sum() >= 30:
            fitx.append(float(np.median(mean[m])))
            # chi-square median / mean for 13 DOF is approximately .949.
            fity.append(float(np.median(variance[m])) / .949)
            count.append(min(int(m.sum()), 500))
    if len(fitx) >= 3:
        weight = np.sqrt(np.asarray(count))
        ab = nnls(np.column_stack([fitx, np.ones(len(fitx))]) * weight[:, None],
                  np.asarray(fity) * weight)[0]
        a, b = float(ab[0]), max(float(ab[1]), 1e-7)
    else:
        a, b = .001, 1e-5
    return corrected, gains, {"gain8": table.tolist(), "a": a, "b": b,
                              "flat_blocks": int(flat.sum()),
                              "noise_bins": list(zip(fitx, fity))}


def scalar_pdhg(obs, weight, initial, lam, delta, steps, adaptive=False, directional=0):
    """WLS + Huber TV, optionally transformed by a pilot tensor (Neumann edges)."""
    u = initial.copy()
    ext = u.copy()
    py = np.zeros_like(u)
    px = np.zeros_like(u)
    tau, sigma = .30, .40  # tau*sigma*||gradient||^2 < 1
    denom = 1 + tau * weight
    if adaptive:
        lam = lam * np.sqrt(weight)
    ay = ax = 1.0
    axy = 0.0
    if directional:
        pilot = gaussian_filter(obs, 1.2)
        gy, gx = np.gradient(pilot)
        yy = gaussian_filter(gy * gy, 2)
        xx = gaussian_filter(gx * gx, 2)
        xy = gaussian_filter(gx * gy, 2)
        # Across a coherent edge regularise less; along it regularise more.
        noise2 = np.median((obs - pilot) ** 2) * 2.2
        gap = np.sqrt((xx - yy) ** 2 + 4 * xy * xy)
        coherence = gap / (xx + yy + noise2 * .15 + 1e-8)
        ny2 = .5 * (1 - (xx - yy) / (gap + 1e-8))
        nx2 = 1 - ny2
        ay = 1 - directional * coherence * ny2
        ax = 1 - directional * coherence * nx2
        axy = -directional * coherence * xy / (gap + 1e-8)
    for _ in range(steps):
        dy = np.zeros_like(u)
        dx = np.zeros_like(u)
        dy[:-1] = ext[1:] - ext[:-1]
        dx[:, :-1] = ext[:, 1:] - ext[:, :-1]
        py += sigma * (ay * dy + axy * dx)
        px += sigma * (axy * dy + ax * dx)
        py /= 1 + sigma * delta / lam
        px /= 1 + sigma * delta / lam
        scale = np.maximum(1, np.hypot(py, px) / lam)
        py /= scale
        px /= scale
        fy = ay * py + axy * px
        fx = axy * py + ax * px
        fy[-1] = 0
        fx[:, -1] = 0
        div = fy + fx
        div[1:] -= fy[:-1]
        div[:, 1:] -= fx[:, :-1]
        new = (u + tau * div + tau * weight * obs) / denom
        ext = 2 * new - u
        u = new
    return u


def laplace(u):
    out = np.zeros_like(u)
    d = u[1:] - u[:-1]
    out[:-1] += d
    out[1:] -= d
    d = u[:, 1:] - u[:, :-1]
    out[:, :-1] += d
    out[:, 1:] -= d
    return out


def curvature_pdhg(obs, weight, initial, lam, delta, steps, adaptive=True):
    """Second-order Huber prior. tau*sigma*||Laplacian||^2 = .896 < 1."""
    u = initial.copy()
    ext = u.copy()
    p = np.zeros_like(u)
    tau, sigma = .70, .02
    if adaptive:
        lam = lam * np.sqrt(weight)
    denom = 1 + tau * weight
    for _ in range(steps):
        p += sigma * laplace(ext)
        p /= 1 + sigma * delta / lam
        np.clip(p, -lam, lam, out=p)
        new = (u - tau * laplace(p) + tau * weight * obs) / denom
        ext = 2 * new - u
        u = new
    return u


def variational_ratios(raw, luma, colours, neutral, previous, camera_weight,
                       strength, steps=12):
    """Two SPD screened-Poisson systems, solved with diagonal-preconditioned CG.

    This minimises the same sensor data term as the luma block plus quadratic
    smoothness of R/G and B/G. Green ratio is one to fix the factorisation gauge.
    """
    d, rhs = [], []
    for c in (0, 2):
        a = neutral[c] * np.maximum(luma, .005)
        w = camera_weight * (colours == c)
        diag = w * a * a
        d.append(diag)
        rhs.append(w * a * raw)
    d = np.stack(d, -1)
    rhs = np.stack(rhs, -1)
    strength = np.maximum(np.asarray(strength, np.float32), 1e-5)
    u = previous[..., [0, 2]].copy()
    degree = np.full(raw.shape, 4, np.float32)
    degree[[0, -1]] -= 1
    degree[:, [0, -1]] -= 1
    precond = d + degree[..., None] * strength

    def operator(x):
        return d * x - strength * laplace(x)

    residual = rhs - operator(u)
    z = residual / np.maximum(precond, 1e-5)
    p = z.copy()
    rz = np.sum(residual * z, axis=(0, 1), dtype=np.float64)
    for _ in range(steps):
        ap = operator(p)
        pap = np.sum(p * ap, axis=(0, 1), dtype=np.float64)
        alpha = (rz / np.maximum(pap, 1e-25)).astype(np.float32)
        u += p * alpha
        residual -= ap * alpha
        z = residual / np.maximum(precond, 1e-5)
        newrz = np.sum(residual * z, axis=(0, 1), dtype=np.float64)
        beta = (newrz / np.maximum(rz, 1e-25)).astype(np.float32)
        p = z + p * beta
        rz = newrz
    result = np.ones_like(previous)
    result[..., [0, 2]] = np.clip(u, .2, 4)
    return result


LAST_INFO = {}


def _solve(data, gain, saturated, neutral, info, origin, *, rounds, steps, lam,
           delta, adaptive, directional, regularizer, chroma_steps,
           variance_scale, colour_strength):
    colours = colour_map(data.shape, origin)
    init = interpolate(data, colours, 3.0) / neutral
    ratios = np.clip(init / np.maximum(init[..., 1:2], .015), .2, 4)
    for c in range(3):
        ratios[..., c] = gaussian_filter(ratios[..., c], 3)
    luma = np.mean(init / ratios, axis=2)
    # Freeze noise estimates and prior weights from a smooth pilot. This reduces
    # inverse-variance bias from weighting an observation with its own noise.
    noise = info["a"] * np.maximum(np.take_along_axis(init * neutral, colours[..., None], 2)[..., 0], .01) + info["b"]
    camera_weight = variance_scale * gain * gain / noise
    camera_weight[saturated] *= .02
    initial_sensitivity = neutral[colours] * np.take_along_axis(ratios, colours[..., None], 2)[..., 0]
    initial_weight = camera_weight * initial_sensitivity ** 2
    if adaptive:
        lam = lam * np.sqrt(initial_weight)
    for _ in range(rounds):
        sensitivity = neutral[colours] * np.take_along_axis(ratios, colours[..., None], 2)[..., 0]
        obs = data / np.maximum(sensitivity, .05)
        weight = camera_weight * sensitivity * sensitivity
        if regularizer == 'curvature':
            luma = curvature_pdhg(obs, weight, luma, lam, delta, steps, False)
        else:
            luma = scalar_pdhg(obs, weight, luma, lam, delta, steps, False, directional)
        ratios = variational_ratios(data, luma, colours, neutral, ratios,
                                     camera_weight, colour_strength, chroma_steps)
    result = np.maximum(luma[..., None] * ratios * neutral, 0).astype(np.float32)
    return result


def run(raw, meta, *, rounds=3, steps=30, lam=.020, delta=.060,
        colour_sigma=3.5, calibration=True, tile=None, halo=48, adaptive=True,
        directional=0, regularizer='curvature', chroma_steps=12):
    """Reconstruct linear camera RGB. Tiling never recalibrates individual tiles."""
    start = time.perf_counter()
    raw = np.asarray(raw, dtype=np.float32)
    colours = colour_map(raw.shape)
    if calibration:
        data, gain, info = calibrate(raw, colours)
    else:
        data, gain, info = raw, np.ones_like(raw), {"a": .001, "b": 1e-5}
    neutral = np.asarray(meta.get("as_shot_neutral", [.54, 1, .55]), np.float32)
    # Shared frame-wide normalisers: independent tile medians would change the
    # objective at tile boundaries. These need only scalar streaming statistics.
    variance = info['a'] * np.maximum(data, .01) + info['b']
    variance_scale = float(np.median(variance / (gain * neutral[colours]) ** 2))
    precision = variance_scale * gain * gain / variance
    colour_strength = np.asarray([
        .25 * colour_sigma ** 2 * np.median((precision * data * data)[colours == c])
        for c in (0, 2)], np.float32)
    del variance, precision, colours
    calibration_seconds = time.perf_counter() - start
    saturated = raw >= .995
    options = dict(rounds=rounds, steps=steps, lam=lam, delta=delta,
                   adaptive=adaptive,
                   directional=directional, regularizer=regularizer,
                   chroma_steps=chroma_steps, variance_scale=variance_scale,
                   colour_strength=colour_strength)
    if tile is None:
        result = _solve(data, gain, saturated, neutral, info, (0, 0), **options)
        tiles = 1
    else:
        h, w = raw.shape
        th, tw = tile
        if th % 8 or tw % 8 or halo % 8:
            raise ValueError('Tile sizes and halo must preserve the 8x8 phase')
        result = np.empty(raw.shape + (3,), np.float32)
        tiles = 0
        for y in range(0, h, th):
            y1 = min(y + th, h)
            a, b = max(0, y - halo), min(h, y1 + halo)
            for x in range(0, w, tw):
                x1 = min(x + tw, w)
                c, d = max(0, x - halo), min(w, x1 + halo)
                rgb = _solve(data[a:b, c:d], gain[a:b, c:d], saturated[a:b, c:d],
                             neutral, info, (a, c), **options)
                result[y:y1, x:x1] = rgb[y-a:y1-a, x-c:x1-c]
                tiles += 1
    LAST_INFO.clear()
    LAST_INFO.update(info, rounds=rounds, steps_per_round=steps, iterations=rounds * steps,
                     chroma_cg_iterations=rounds * chroma_steps,
                     lam=lam, delta=delta, colour_sigma=colour_sigma,
                     variance_scale=variance_scale, colour_strength=colour_strength.tolist(),
                     calibration_seconds=calibration_seconds, tile=tile, halo=halo,
                     tiles=tiles, seconds=time.perf_counter() - start)
    return result


def reconstruct(raw, meta):
    return run(raw, meta)
