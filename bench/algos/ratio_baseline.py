"""Baseline = what the app ships today (Remosaic16.java) followed by a good Bayer demosaic.

Remosaic: keep a pixel when its native colour is the colour the Bayer grid wants there;
otherwise scale it by the ratio of the local (super-cell, bilinearly interpolated) means of
the wanted and native colours. Then Menon (2007) demosaic of the GBRG result.
"""
import numpy as np
from scipy.ndimage import map_coordinates

R, G, B = 0, 1, 2
CENTRE = {R: (5.5, 1.5), G: (3.5, 3.5), B: (1.5, 5.5)}  # (y, x) of each colour's mean in the super-cell


def native_colour(h, w):
    y, x = np.mgrid[0:h, 0:w]
    by, bx = (y >> 2) & 1, (x >> 2) & 1
    return np.where(by == bx, G, np.where(by == 0, B, R))


def bayer_colour(h, w):
    y, x = np.mgrid[0:h, 0:w]
    return np.where((y & 1) == (x & 1), G, np.where((y & 1) == 0, B, R))


def cell_means(raw):
    h, w = raw.shape
    s = raw.reshape(h // 8, 8, w // 8, 8)
    g = (s[:, :4, :, :4].mean(axis=(1, 3)) + s[:, 4:, :, 4:].mean(axis=(1, 3))) / 2
    b = s[:, :4, :, 4:].mean(axis=(1, 3))
    r = s[:, 4:, :, :4].mean(axis=(1, 3))
    return {R: np.maximum(r, 1e-4), G: np.maximum(g, 1e-4), B: np.maximum(b, 1e-4)}


def mean_at(means, colour, h, w):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    cy, cx = CENTRE[colour]
    return map_coordinates(means[colour], [(y - cy) / 8, (x - cx) / 8], order=1, mode="nearest")


def remosaic(raw):
    h, w = raw.shape
    means = cell_means(raw)
    nat, want = native_colour(h, w), bayer_colour(h, w)
    full = {c: mean_at(means, c, h, w) for c in (R, G, B)}
    out = raw.copy()
    for n in (R, G, B):
        for t in (R, G, B):
            if n == t:
                continue
            m = (nat == n) & (want == t)
            out[m] = raw[m] * full[t][m] / full[n][m]
    return out


def reconstruct(raw, meta):
    from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007
    return demosaicing_CFA_Bayer_Menon2007(np.clip(remosaic(raw), 0, None), "GBRG").astype(np.float32)
