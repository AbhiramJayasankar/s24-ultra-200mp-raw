"""First-order directional Huber-TV ablation of the joint solver."""
try:
    from .var_best import run
except ImportError:
    from algos.var_best import run


def reconstruct(raw, meta):
    return run(raw, meta, regularizer='tv', directional=.8, steps=18,
               lam=.025, delta=.03)
