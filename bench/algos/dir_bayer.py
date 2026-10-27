"""Comparison: remosaic dir_best's direct RGB to Bayer and demosaic again."""
import numpy as np
try:
    from bench.algos.dir_best import reconstruct as direct
except ModuleNotFoundError:
    from algos.dir_best import reconstruct as direct


def reconstruct(raw, meta):
    from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007
    rgb = direct(raw, meta)
    bayer = rgb[..., 1].copy()
    bayer[0::2, 1::2] = rgb[0::2, 1::2, 2]
    bayer[1::2, 0::2] = rgb[1::2, 0::2, 0]
    return demosaicing_CFA_Bayer_Menon2007(bayer, 'GBRG').astype(np.float32)
