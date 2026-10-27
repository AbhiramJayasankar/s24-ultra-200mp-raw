"""Explicit four-direction colour-difference interpolation ablation."""
try:
    from bench.algos.dir_core import reconstruct_impl
except ModuleNotFoundError:
    from algos.dir_core import reconstruct_impl


def reconstruct(raw, meta):
    return reconstruct_impl(raw, meta, method='directional')
