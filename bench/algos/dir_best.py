"""Recommended balance: runtime phase calibration and native-detail residual RGB.

Two masked local-affine guide updates use actual R/B detail inside the holes
in green. A bounded, noise-scaled bilateral filter reduces luma noise; local
affine colour residual interpolation reconstructs full camera RGB directly.
No sharpening, file I/O, reference frames, or persistent learned parameters.
"""
try:
    from bench.algos.dir_core import reconstruct_impl
except ModuleNotFoundError:
    from algos.dir_core import reconstruct_impl


def reconstruct(raw, meta):
    return reconstruct_impl(raw, meta, bilateral=2., spatial=1.5)
