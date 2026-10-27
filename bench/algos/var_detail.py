"""Weak luma regularisation ablation."""
try:
    from .var_best import run
except ImportError:
    from algos.var_best import run


def reconstruct(raw, meta):
    return run(raw, meta, lam=.01)
