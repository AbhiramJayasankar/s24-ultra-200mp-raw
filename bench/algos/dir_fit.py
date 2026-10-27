"""Native-detail guide with masked local affine colour residual interpolation."""
try:
    from bench.algos.dir_core import reconstruct_impl
except ModuleNotFoundError:
    from algos.dir_core import reconstruct_impl


def reconstruct(raw, meta):
    return reconstruct_impl(raw, meta)
