# Exact CPU optimization of lumdir

Measured in this worktree on the 16-logical-CPU PC, using Python 3.14.7,
NumPy 2.5.4 and SciPy 1.18.1. No packages were installed. Defaults:
`CHROMA_SIGMA=1`, `PHASE_WINDOW=96`, `DENOISE=0`, `ITERATIONS=1`,
`NOISE_SCALE=.75`. Times exclude reference loading and output comparison.
Book is best of two calls; tile is one call after book (imports already warm).
First book call includes the lazy colour-demosaicing import.

## Results

| Wall seconds | Book before | Book after | Tile before | Tile after |
|---|---:|---:|---:|---:|
| Calibration | 0.120 | 0.091 | 0.201 | 0.105 |
| Initial guide, including coarse RGB | 0.410 | 0.233 | 2.303 | 1.190 |
| Noise model | 0.007 | 0.009 | 0.010 | 0.011 |
| Affine fits (both sets) | 1.495 | 0.562 | 24.379 | 2.961 |
| Bilateral clean | 1.431 | 0.426 | 7.679 | 2.206 |
| Phase flatten | 4.876 | 0.402 | 22.453 | 2.300 |
| Chroma and remaining work | 0.506 | 0.277 | 5.013 | 1.266 |
| **Total** | **8.844** | **2.001** | **62.039** | **10.040** |
| **Speedup** | | **4.42x** | | **6.18x** |

The baseline harness could wrap individual chroma Gaussian calls; the optimized
version exposes the whole chroma stage. They are combined with the remaining
work above so the buckets are comparable. Optimized chroma alone was 0.085 s
for book and 0.363 s for tile. Thread creation/join is included in stage times.
The baseline algorithm was serial even though the harness printed the default
`LUMDIR_THREADS=16` setting.

Both saved references pass with **max absolute difference 0, differing spatial
pixels 0, differing channel values 0, and identical output bytes**. The serial
path also passes; there are no float32 reordering differences to explain.
With `LUMDIR_THREADS=1`, the final harness measured 4.395 s for book and
37.156 s for tile, with identical bytes on both.

## Changes and portability

* Reuse dir_port's calibrated, white-balanced raw samples and masks. Calibration
  and the sample correction/balance now happen once per reconstruction.
* Blur the chroma denominator once rather than three times. Compute Gaussian
  filters in parallel row strips with the exact radius as a halo.
* Select rank 72 of each 12x12 phase window with bounded NumPy partition batches,
  rather than making 64 serial ndimage median calls. Keep offsets -6 through +5,
  half-sample reflect padding, and the upper median. The phase tasks are parallel;
  the original storage layout and phase-mean reduction order are retained.
  Other window settings still work; unusual dtypes, nonfinite values and large
  windows use ndimage. Tiny grids keep the original serial ndimage call order.
* Fit independent channel/row strips using a 12-row halo for the two radius-6
  box filters. The shared dir_core.masked_fit implementation is unchanged.
* Tile each bilateral pass, retaining tap accumulation order and a barrier
  between horizontal and vertical passes. Reuse float32 tile buffers for the
  original operations; retain original dtype promotion for float64 callers.
* Tile coarse-RGB coordinate interpolation and write directly into RGB storage.
  Write the final affine fits directly into RGB too. Avoid full-frame coordinate
  grids, redundant stacks, unnecessary multiply/clamp copies, and retaining
  previous iteration results.

Reuse, halo tiling, independent tasks, exact rank selection and scratch-buffer
reuse all carry over to Java. NumPy's compiled/SIMD partition implementation
contributes to the median speedup; Java should use an efficient exact selection
routine and preserve the specified rank, reflection and phase reduction order.
Actual speedups on a phone will depend on its cores and memory bandwidth.

## Memory and full frame estimate

Window-selection scratch is bounded to roughly 2 MiB per worker (plus each
padded phase grid). Fit, bilateral, interpolation and Gaussian scratch arrays
are strips instead of full frames. Writing final fits directly to RGB avoids
three full-frame float32 channel arrays: **2.40 GB** at 16320x12240. Other working
arrays are released before subsequent stages. Full-frame RGB and other necessary
image planes still consume several GB; this is not a streaming reconstruction.
Calibration's small sampled float64 calculations remain unchanged for exactness.

16320x12240 is 199,756,800 pixels, 11.906 times the tile. Linear scaling from
10.040 s predicts **about 120 s** on this PC with 16 workers; allow roughly
**2–3 minutes** for memory-bandwidth, allocation and cache effects. This is an
estimate, not a measured full-frame run, and assumes sufficient physical RAM.

## Reproduce

```powershell
python bench/time_lumdir.py
$env:LUMDIR_THREADS = '1'
python bench/time_lumdir.py
Remove-Item Env:LUMDIR_THREADS
python -m unittest bench.test_lumdir
```

The harness exits unsuccessfully if either output exceeds 1e-5 max absolute
difference, reports both numeric differences and byte identity, and restores
its temporary stage wrappers. Public camera_rgb/reconstruct/calibration and
phase_flatten signatures remain unchanged. Tests cover upper-median selection,
reflection, odd/even windows, phase reduction order, tile seams, direct RGB
output, chroma sigma settings and deterministic worker counts. Additional
comparisons against the original source passed for changed iteration count,
noise scale, denoise, phase window and chroma sigma, and float64 bilateral inputs.
