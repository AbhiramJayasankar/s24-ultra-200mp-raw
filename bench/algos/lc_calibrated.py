"""Ablation: phase calibration and full-resolution luma, no luma denoising."""
if __package__:
    from .lc_core import run_pipeline as _run
else:
    from algos.lc_core import run_pipeline as _run


def reconstruct(raw, meta):
    return _run(raw, meta)
