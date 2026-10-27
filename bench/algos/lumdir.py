"""Hybrid: brightness detail straight from the sensor (as lumratio), colour from dir_port.

dir_port gets colour right (red and blue print) but its full-resolution brightness has
zipper/staircase edges. Here its colour ratios (channel / mean, white-balanced) are
smoothed by CHROMA_SIGMA, which cancels its alternating edge fringes but keeps the colour
of strokes a few pixels wide. Each sensor pixel is divided by its own channel's ratio to
get brightness, and RGB = brightness x ratios.
"""
import numpy as np
from scipy.ndimage import gaussian_filter

try:
    from bench.algos._parallel import parallel_map, row_tiles
    from bench.algos import dir_port
    from bench.algos.dir_core import masks
    from bench.algos.lumratio import phase_flatten
    import bench.algos.lumratio as lumratio
except ModuleNotFoundError:
    from _parallel import parallel_map, row_tiles
    import dir_port
    from dir_core import masks
    import lumratio
    from lumratio import phase_flatten

CHROMA_SIGMA = 1.0
PHASE_WINDOW = 96
DENOISE = 0.0


def _colour_ratios(ref):
    mean = np.maximum(ref.mean(-1), 1e-4)
    # The denominator is identical for all three channels.
    h, w = mean.shape
    sigma_y = float(np.asarray(CHROMA_SIGMA).flat[0])
    halo = int(4*sigma_y + 0.5) if sigma_y > 1e-15 else 0
    tiles = row_tiles(h)
    denominator = np.empty_like(mean)

    def blur_mean(bounds):
        lo, hi = bounds
        start, stop = max(0, lo-halo), min(h, hi+halo)
        denominator[lo:hi] = gaussian_filter(mean[start:stop], CHROMA_SIGMA)[lo-start:hi-start]

    parallel_map(blur_mean, tiles)
    np.maximum(denominator, 1e-4, out=denominator)
    del mean
    ratios = [np.empty((h, w), np.float32) for _ in range(3)]

    def channel(task):
        c, lo, hi = task
        start, stop = max(0, lo-halo), min(h, hi+halo)
        blurred = gaussian_filter(ref[start:stop, :, c], CHROMA_SIGMA)
        ratio = ratios[c][lo:hi]
        np.divide(blurred[lo-start:hi-start], denominator[lo:hi], out=ratio)
        np.clip(ratio, 0.05, 4, out=ratio)

    parallel_map(channel, [(c, lo, hi) for c in range(3) for lo, hi in tiles])
    return ratios


def camera_rgb(raw, meta):
    raw = np.asarray(raw, np.float32)
    neutral = np.asarray(meta['as_shot_neutral'], np.float32)
    # Reuse the calibrated, balanced samples already needed by dir_port.
    ref, balanced, mm = dir_port._camera_rgb(raw, meta, return_working=True)
    ref /= neutral                                      # white-balanced
    rat = _colour_ratios(ref)
    del ref
    y = balanced/sum(mm[c]*rat[c] for c in range(3))
    del balanced, mm
    if PHASE_WINDOW > 0:
        y = phase_flatten(y, PHASE_WINDOW)
    if DENOISE > 0:
        from skimage.restoration import denoise_nl_means
        y = denoise_nl_means(y, h=DENOISE*0.02, patch_size=5, patch_distance=6, fast_mode=True).astype(np.float32)
    rgb = np.empty((*raw.shape, 3), np.float32)
    for c in range(3):
        np.multiply(y, rat[c], out=rgb[..., c])
    rgb *= neutral
    np.maximum(rgb, 0, out=rgb)
    return rgb


def reconstruct(raw, meta):
    return camera_rgb(raw, meta)
