"""Luminance straight from the sensor, colour from a smooth ratio map.

Every 16-cell pixel is corrected for its per-position gain and white-balanced. On a
neutral subject that alone is the full-resolution luminance (no interpolation, so no
zipper or staircase). For coloured subjects each pixel is divided by its channel's
local colour ratio (channel mean / luminance mean), estimated over whole 8x8 super-cells
and filled across strong edges from nearby flat areas, where the per-channel sample
positions would otherwise bias it. RGB = luminance x colour ratios.
"""
import numpy as np
from scipy.ndimage import gaussian_filter, median_filter, uniform_filter

try:
    from bench.algos._parallel import parallel_map
    from bench.algos.dir_core import masks
    from bench.algos.dir_port import calibration
except ModuleNotFoundError:
    from algos._parallel import parallel_map
    from algos.dir_core import masks
    from algos.dir_port import calibration

BOX = 16            # two super-cells: every channel sampled evenly at any offset
EDGE_TAU = 0.08     # relative local contrast above which ratios are not trusted
FILL_SIGMA = 10.0   # px; reach of the edge-aware ratio fill
DENOISE = 0.0       # luminance denoise strength (0 = off)
PHASE_WINDOW = 0    # px; >0 enables local per-position flattening (median over this window)


def ratios(balanced, mm):
    means = [uniform_filter(balanced*m, BOX)/uniform_filter(m.astype(np.float32), BOX) for m in mm]
    ylow = (means[0]+means[1]+means[2])/3
    # Local contrast relative to brightness: high on text and edges, where the three
    # channels' different sample positions bias the ratio.
    contrast = np.sqrt(np.maximum(uniform_filter(balanced**2, BOX)-uniform_filter(balanced, BOX)**2, 0))
    rel = contrast/(np.abs(ylow)+.02)
    w = 1/(1+(rel/EDGE_TAU)**4)
    raw_r = [np.clip(m/np.maximum(ylow, 1e-4), 0, 4) for m in means]
    # Multi-scale normalised convolution: use the finest scale that has enough flat
    # area nearby, so dense detail (barcodes, text blocks) borrows colour from the paper
    # around it instead of trusting biased local estimates.
    out = [np.ones_like(balanced) for _ in mm]
    for sigma in (FILL_SIGMA*6, FILL_SIGMA*2.5, FILL_SIGMA):
        den = gaussian_filter(w, sigma)
        use = np.clip((den-.05)/.15, 0, 1)
        for c in range(3):
            est = gaussian_filter(raw_r[c]*w, sigma)/np.maximum(den, 1e-6)
            out[c] = use*est+(1-use)*out[c]
    return out


def phase_flatten(y, size=None):
    """Removes the slowly varying per-position sensitivity left after calibration: for
    each of the 64 positions, its local mean relative to the local mean of all pixels."""
    size = size or PHASE_WINDOW
    h, w = y.shape
    ref = uniform_filter(y, 8)
    rel = y/np.maximum(ref, 1e-3)
    g = rel.reshape(h//8, 8, w//8, 8).transpose(1, 3, 0, 2)
    smooth = np.empty_like(g)

    def phase(ab):
        a, b = ab
        _phase_median(g[a, b], size//8, smooth[a, b])

    phases = [(a, b) for a in range(8) for b in range(8)]
    if size//8 > min(g.shape[-2:]):
        # Retain the original ndimage call order for degenerate tiny inputs.
        # Some ndimage versions behave differently when the footprint spans
        # several entire reflections of the input.
        for a, b in phases:
            smooth[a, b] = median_filter(np.ascontiguousarray(g[a, b]), size=size//8, mode='reflect')
    else:
        parallel_map(phase, phases)
    # Keep the original layout and reduction order: changing the phase storage
    # to contiguous (64, h//8, w//8) changes float32 rounding in this mean.
    smooth /= smooth.mean(axis=(0, 1), keepdims=True)
    return y/smooth.transpose(2, 0, 3, 1).reshape(h, w)


def _phase_median(grid, size, out):
    """Exact reflect-boundary rank filter, with bounded window scratch space.

    ndimage selects rank n//2 (the UPPER median for even footprints), with
    offsets [-size//2, ..., (size-1)//2]. NumPy's symmetric padding implements
    ndimage's half-sample 'reflect'.
    Partition selects the same input value without sorting the whole window.
    """
    if (grid.dtype != np.float32 or not 1 <= size <= min(16, *grid.shape) or
            not np.isfinite(grid).all()):
        # Preserve ndimage's behavior for other dtypes, nonfinite data and
        # unusually large caller-selected windows (avoid quadratic scratch).
        out[...] = median_filter(np.ascontiguousarray(grid), size=size, mode='reflect')
        return
    pad = np.pad(grid, ((size//2, (size-1)//2),)*2, mode='symmetric')
    h, w = grid.shape
    count = size*size
    rows = max(1, min(32, (2*1024*1024)//(w*count*grid.itemsize)))
    work = np.empty((min(rows, h), w, count), dtype=grid.dtype)
    for lo in range(0, h, rows):
        hi = min(lo+rows, h)
        windows = np.lib.stride_tricks.sliding_window_view(pad[lo:hi+size-1], (size, size))
        batch = work[:hi-lo]
        np.copyto(batch.reshape(hi-lo, w, size, size), windows)
        batch.partition(count//2, axis=-1)
        out[lo:hi] = batch[..., count//2]


def camera_rgb(raw, meta):
    raw = np.asarray(raw, np.float32)
    h, w = raw.shape
    gains, offsets = calibration(raw)
    corrected = ((raw.reshape(h//8, 8, w//8, 8)-offsets[None, :, None, :])
                 / gains[None, :, None, :]).reshape(h, w)
    neutral = np.asarray(meta['as_shot_neutral'], np.float32)
    mm = masks(raw.shape)
    native = sum(mm[c].astype(np.float32)*neutral[c] for c in range(3))
    balanced = corrected/native
    rat = ratios(balanced, mm)
    own = sum(mm[c]*rat[c] for c in range(3))
    y = balanced/np.maximum(own, 1e-3)
    if PHASE_WINDOW > 0:
        y = phase_flatten(y)
    if DENOISE > 0:
        from skimage.restoration import denoise_nl_means
        y = denoise_nl_means(y, h=DENOISE*0.02, patch_size=5, patch_distance=6, fast_mode=True).astype(np.float32)
    rgb = np.stack([y*rat[c] for c in range(3)], -1)*neutral
    return np.maximum(rgb, 0).astype(np.float32)


def reconstruct(raw, meta):
    return camera_rgb(raw, meta)
