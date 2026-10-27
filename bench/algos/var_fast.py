"""Reduced joint solve: 2x16 luminance iterations, 2x5 chroma PCG, tiled."""
try:
    from .var_best import run
except ImportError:
    from algos.var_best import run


def reconstruct(raw, meta):
    return run(raw, meta, rounds=2, steps=16, chroma_steps=5,
               tile=(256, 512), halo=48)
