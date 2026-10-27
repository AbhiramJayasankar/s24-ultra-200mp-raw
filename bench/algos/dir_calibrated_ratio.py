"""Calibration ablation: existing remosaic after runtime affine phase correction."""
try:
    from bench.algos.dir_core import calibration
    from bench.algos.ratio_baseline import reconstruct as baseline
except ModuleNotFoundError:
    from algos.dir_core import calibration
    from algos.ratio_baseline import reconstruct as baseline


def reconstruct(raw, meta):
    return baseline(calibration(raw), meta)
