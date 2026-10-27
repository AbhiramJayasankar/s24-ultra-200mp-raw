"""More native fine energy at the cost of grain; no sharpening is applied."""
try:
    from bench.algos.dir_core import reconstruct_impl
except ModuleNotFoundError:
    from algos.dir_core import reconstruct_impl


def reconstruct(raw, meta):
    return reconstruct_impl(raw, meta, clean=1.3)
