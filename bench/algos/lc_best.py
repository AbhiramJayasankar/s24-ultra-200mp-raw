"""Recommended: raw-only phase gain/offset calibration and split reconstruction.

Smooth 4x4-block colour ratios give a neutral full-resolution guide; guided
chroma and variance-stabilized luma denoising suppress false colour/noise without
an unsharp mask. See bench/notes/lc_.md for limits and porting costs.
"""
if __package__:
    from .lc_core import run_pipeline
else:
    from algos.lc_core import run_pipeline


def reconstruct(raw, meta):
    return run_pipeline(raw, meta, denoise='sqrt', chroma_guide=True,
                        offset_correction=True)
