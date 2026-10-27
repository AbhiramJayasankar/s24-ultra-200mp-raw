"""Less luma denoising: retain more fine structure at appreciably higher noise."""
if __package__:
    from .lc_core import run_pipeline
else:
    from algos.lc_core import run_pipeline


def reconstruct(raw, meta):
    return run_pipeline(raw, meta, denoise='sqrt_detail', chroma_guide=True,
                        offset_correction=True)
