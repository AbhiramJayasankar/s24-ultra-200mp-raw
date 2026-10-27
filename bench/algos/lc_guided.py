"""Calibrated luma with self-guided noise reduction and guided chroma."""
if __package__:
    from .lc_core import run_pipeline as _run
else:
    from algos.lc_core import run_pipeline as _run


def reconstruct(raw, meta):
    return _run(raw, meta, denoise='guided', chroma_guide=True)
