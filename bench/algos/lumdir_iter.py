"""lumdir, then alternate (Bayer-style): colour guided by the sharp brightness, then
brightness rebuilt from each pixel's own reading with that colour.

Start: lumdir (colour from dir_port, brightness = own reading / own colour ratio).
Each pass:
  1. colour: for each channel, fit "channel = a * brightness + b" over a local window
     using only that channel's real samples (dir_core.masked_fit), with lumdir's sharp
     brightness as the guide, so colour edges land where brightness edges are;
  2. brightness: own reading / own channel's new colour ratio (as in lumdir).
Neutral areas are unchanged (ratios stay ~1); only colour edges move.
"""
import numpy as np

try:
    import bench.algos.lumdir as lumdir
    from bench.algos.dir_core import masks, masked_fit
    from bench.algos.dir_port import calibration
    from bench.algos.lumratio import phase_flatten
except ModuleNotFoundError:
    import lumdir
    from dir_core import masks, masked_fit
    from dir_port import calibration
    from lumratio import phase_flatten

PASSES = 2
RADIUS = 6           # fit window (13x13 holds samples of every colour at any position)


def camera_rgb(raw, meta):
    raw = np.asarray(raw, np.float32)
    h, w = raw.shape
    neutral = np.asarray(meta['as_shot_neutral'], np.float32)
    start = lumdir.camera_rgb(raw, meta)/neutral
    y = start.mean(-1)
    gains, offsets = calibration(raw)
    corrected = ((raw.reshape(h//8, 8, w//8, 8)-offsets[None, :, None, :])
                 / gains[None, :, None, :]).reshape(h, w)
    mm = masks(raw.shape)
    balanced = corrected/sum(mm[c].astype(np.float32)*neutral[c] for c in range(3))
    rat = [start[..., c]/np.maximum(y, 1e-4) for c in range(3)]
    for _ in range(PASSES):
        fits = [masked_fit(y, balanced, mm[c], radius=RADIUS) for c in range(3)]
        mean = np.maximum((fits[0]+fits[1]+fits[2])/3, 1e-4)
        rat = [np.clip(f/mean, 0.05, 4) for f in fits]
        y = balanced/sum(mm[c]*rat[c] for c in range(3))
        if lumdir.PHASE_WINDOW > 0:
            y = phase_flatten(y, lumdir.PHASE_WINDOW)
    return np.maximum(np.stack([y*rat[c] for c in range(3)], -1)*neutral, 0).astype(np.float32)


def reconstruct(raw, meta):
    return camera_rgb(raw, meta)
