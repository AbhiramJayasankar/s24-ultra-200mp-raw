"""Small, ordered CPU task dispatch shared by the reconstruction stages."""
from concurrent.futures import ThreadPoolExecutor
import os


def worker_count():
    return max(1, int(os.environ.get('LUMDIR_THREADS', os.cpu_count() or 1)))


def parallel_map(function, items):
    """Join before returning; preserve task order and propagate worker exceptions."""
    items = list(items)
    workers = min(worker_count(), len(items))
    if workers <= 1:
        return [function(item) for item in items]
    with ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(function, items))


def row_tiles(height, rows=128):
    return [(start, min(start + rows, height)) for start in range(0, height, rows)]
