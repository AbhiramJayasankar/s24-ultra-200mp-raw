"""Control: calibrated luma with mild Gaussian blur, smooth block chroma."""
if __package__:
    from .lc_core import run_pipeline as _run
else:
    from algos.lc_core import run_pipeline as _run


def reconstruct(raw, meta):
    return _run(raw, meta, denoise='gauss', chroma_guide=True)
