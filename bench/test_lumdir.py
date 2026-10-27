"""Exactness checks for filter rank, boundaries, tile seams and worker counts.

Run: python -m unittest bench.test_lumdir
The large saved-image checks live in bench/time_lumdir.py.
"""
import os
import unittest
from unittest.mock import patch

import numpy as np
from scipy.ndimage import gaussian_filter, median_filter, uniform_filter

from bench.algos import dir_core, dir_port, lumdir, lumratio


class ExactFilters(unittest.TestCase):
    def setUp(self):
        self.rng = np.random.default_rng(736)

    def test_upper_median_rank_and_reflection(self):
        for dtype in (np.float32, np.float64):
            # Ties and unique values exercise both partition paths. Non-square
            # inputs expose transposition and even-size origin mistakes.
            for ties in (False, True):
                grid = self.rng.random((19, 23)).astype(dtype)
                if ties:
                    grid = np.round(grid, 1)
                for size in (1, 2, 3, 11, 12, 13, 16, 17):
                    with self.subTest(dtype=dtype, ties=ties, size=size):
                        out = np.empty_like(grid)
                        lumratio._phase_median(grid, size, out)
                        np.testing.assert_array_equal(
                            out, median_filter(grid, size, mode='reflect'))

    def test_phase_reduction_order_and_setting(self):
        y = self.rng.random((136, 152), dtype=np.float32)
        # This is the original serial definition, used as the numerical oracle.
        rel = y / np.maximum(uniform_filter(y, 8), 1e-3)
        phases = rel.reshape(17, 8, 19, 8).transpose(1, 3, 0, 2)
        for size in (8, 16, 88, 96, 104, 136):
            smooth = np.empty_like(phases)
            for a in range(8):
                for b in range(8):
                    smooth[a, b] = median_filter(
                        np.ascontiguousarray(phases[a, b]), size//8, mode='reflect')
            smooth /= smooth.mean(axis=(0, 1), keepdims=True)
            expected = y / smooth.transpose(2, 0, 3, 1).reshape(y.shape)
            for threads in ('1', '4'):
                with patch.dict(os.environ, LUMDIR_THREADS=threads), \
                        patch.object(lumratio, 'PHASE_WINDOW', size):
                    np.testing.assert_array_equal(lumratio.phase_flatten(y), expected)

    def test_affine_tile_seams_and_direct_rgb_output(self):
        guide = self.rng.random((280, 136), dtype=np.float32)
        sample = self.rng.random(guide.shape, dtype=np.float32)
        masks = dir_core.masks(guide.shape)
        expected = [dir_core.masked_fit(guide, sample, m) for m in masks]
        for threads in ('1', '4'):
            with patch.dict(os.environ, LUMDIR_THREADS=threads):
                output = np.empty((*guide.shape, 3), np.float32)
                actual = dir_port._fit_channels(guide, sample, masks, output=output)
                for c in range(3):
                    np.testing.assert_array_equal(actual[c], expected[c])
                    np.testing.assert_array_equal(output[..., c], expected[c])

    def test_chroma_tile_halos_and_settings(self):
        ref = self.rng.random((280, 136, 3), dtype=np.float32)
        mean = np.maximum(ref.mean(-1), 1e-4)
        for sigma in (0, 1, 1.125, 2, (.75, 1.25)):
            denominator = np.maximum(gaussian_filter(mean, sigma), 1e-4)
            expected = [np.clip(gaussian_filter(ref[..., c], sigma) / denominator,
                                .05, 4) for c in range(3)]
            for threads in ('1', '4'):
                with patch.dict(os.environ, LUMDIR_THREADS=threads), \
                        patch.object(lumdir, 'CHROMA_SIGMA', sigma):
                    for actual, reference in zip(lumdir._colour_ratios(ref), expected):
                        np.testing.assert_array_equal(actual, reference)

    def test_bilateral_worker_determinism(self):
        guide = self.rng.random((280, 136), dtype=np.float32)
        masks = dir_core.masks(guide.shape)
        params = np.array([[.001, .0001], [.002, .0002], [.003, .0003]], np.float32)
        with patch.object(dir_port, 'NOISE_SCALE', .5):
            with patch.dict(os.environ, LUMDIR_THREADS='1'):
                expected = dir_port.clean(guide, masks, params)
            with patch.dict(os.environ, LUMDIR_THREADS='4'):
                np.testing.assert_array_equal(dir_port.clean(guide, masks, params), expected)


if __name__ == '__main__':
    unittest.main()
