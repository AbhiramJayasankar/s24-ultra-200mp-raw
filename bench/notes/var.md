Recommend **`var_best`** for the text/barcode compromise, and **`var_fast`** for a phone prototype. The main improvement is cleaner, more neutral barcode edges and much less grain. This experiment does **not** establish extra real resolution beyond the 50MP frame: the smallest print remains soft, the fine-detail-minus-noise score is essentially zero, and residual grid energy remains above the 50MP reference.

All five retained entry points are in `bench/algos/var_*.py`. They use only their `raw` and `meta` arguments. No reference-derived calibration, crop coordinates, reference images, sharpening filter, neural model, or GPU is used inside an algorithm. `var_best.py` contains the shared implementation. The other four files select parameters. The benchmark, data, app code and baseline implementation were not edited; nothing was committed.

**Final benchmark, unchanged harness.** Timings include calibration, interpolation and reconstruction on this Windows host, Python 3.14.7 / NumPy 2.5.4 / SciPy 1.18.1. They exclude rendering and metric computation, as prescribed by the harness.

| Algorithm | psnr50 | detail | noise | det-noi | mid | fringe | grid | seconds |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 50MP upscaled 2x | 45.79 | 0.044 | 0.037 | 0.007 | 1.645 | 0.0257 | 4.74 | — |
| ratio_baseline | 26.83 | 0.874 | 0.789 | 0.085 | 3.795 | 0.0453 | 6.99 | 1.71 |
| **var_best** | **29.59** | **0.089** | **0.090** | **-0.001** | **2.684** | **0.0152** | **7.79** | **8.88** |
| var_fast | 29.78 | 0.070 | 0.097 | -0.027 | 2.282 | 0.0172 | 7.78 | 3.80 |
| var_detail | 29.52 | 0.198 | 0.228 | -0.030 | 3.492 | 0.0154 | 9.56 | 8.97 |
| var_soft | 29.62 | 0.047 | 0.039 | 0.008 | 1.929 | 0.0151 | 7.20 | 8.93 |
| var_tv | 29.87 | 0.177 | 0.215 | -0.038 | 1.614 | 0.0145 | 10.14 | 10.72 |

Reproduce the table and all three comparison-sheet regions with:

```text
python bench/harness.py ratio_baseline var_best var_fast var_detail var_soft var_tv
```

The exact results are also in [the harness JSON](../out/results_ratio_baseline_var_best_var_fast_var_detail_var_soft_var_tv.json). I opened and inspected the generated sheets throughout tuning, including [the final harness sheet](../out/sheet_ratio_baseline_var_best_var_fast_var_detail_var_soft_var_tv.png), [50MP / best / fast](../out/var_review_sheet.png), and [50MP / best / soft](../out/var_tradeoff_sheet.png).

**Forward model and objective.** For sensor position `p`, let `c(p)` be its native colour, `g[p]` its estimated 8x8 gain, and `d[p] = raw[p] / g[p]`. Parameterise camera RGB as

```text
x_c[p] = n_c * L[p] * q_c[p],   q_G = 1,
n = meta.as_shot_neutral.
```

`L` is a shared green-normalised intensity field, not a rendered Rec.709 luma. `q_R` and `q_B` describe colour ratios after neutral normalisation. The returned image includes `n` again, so it is linear camera RGB, not white-balanced RGB. The sensor prediction is `g[p] * n_c * L[p] * q_c[p]`, sampled only at `c(p)`.

The retained curvature variants approximately minimise

```text
0.5 * sum_p P[p] * (n_c * L[p] * q_c[p] - d[p])^2
  + sum_p lambda[p] * Huber_delta(Laplacian(L)[p])
  + 0.5 * sum_{c=R,B} kappa_c * ||gradient(q_c)||^2.
```

Here `Huber_delta(t)` is `t^2/(2*delta)` for `abs(t) <= delta`, otherwise `abs(t)-delta/2`. All derivatives use Neumann image boundaries. The prior is a Huber **Laplacian**, not a full Hessian or TGV penalty. It suppresses noise while preserving ramps better than first-order TV in these trials.

`P` is a relative inverse variance in corrected RAW units: a common frame scale times `g^2 / (a*pilot_signal+b)`. The signal estimate comes from a smooth per-colour pilot, rather than the noisy observation itself. Samples at or above 0.995 receive 2% weight; this is an approximate treatment of clipping, not a full censored-noise likelihood. The pilot also fixes `lambda[p] = lambda_base * sqrt(initial_luma_precision[p])`. The colour strengths are fixed for the frame, using `0.25 * colour_sigma^2 * median(relative_precision * d^2)` separately at red and blue sites; for this crop they are 0.18485 and 0.20255. These normalisers are shared by every tile.

Ratio smoothness also enforces cross-channel edge alignment. Since `gradient(x_c) = n_c * (q_c*gradient(L) + L*gradient(q_c))`, suppressing the second term makes neutral intensity edges coincide across channels. An achromatic barcode can retain a sharp shared edge without introducing separate R/G/B steps. Genuine very fine colour structure is consequently suppressed too.

The initial RGB is normalised Gaussian interpolation with sigma 3; initial ratios receive another sigma-3 smoothing pass. Each outer round alternates a luminance solve with a colour solve. Luminance uses proximal primal-dual iterations with `tau=0.70`, `sigma=0.02`; `tau*sigma*||Laplacian||^2 <= 0.896 < 1`. For fixed luminance, each colour-ratio block is a screened-Poisson system, solved with diagonal-preconditioned CG. The R and B systems are evaluated together, with separate CG scalars. Ratios are clipped to [0.2, 4] after each block and RGB is clipped below zero on return. The multiplicative problem is not jointly convex; bounded iterations and post-solve ratio clipping are approximations, not a claim of finding a global minimum.

| Entry point | Luminance prior | Base weight / Huber delta | Outer rounds | Luma iterations per round / total | CG iterations per colour, total | Solve tiles |
|---|---|---|---:|---|---:|---|
| var_best | Huber Laplacian | 0.020 / 0.060 | 3 | 30 / 90 | 36 | Full crop |
| var_fast | Huber Laplacian | 0.020 / 0.060 | 2 | 16 / 32 | 10 | 256x512 cores, 48px halo |
| var_detail | Huber Laplacian | 0.010 / 0.060 | 3 | 30 / 90 | 36 | Full crop |
| var_soft | Huber Laplacian | 0.035 / 0.060 | 3 | 30 / 90 | 36 | Full crop |
| var_tv | Tensor-guided Huber TV | 0.025 / 0.030 | 3 | 18 / 54 | 36 | Full crop |

`colour_sigma=3.5` controls the quadratic colour-prior strength in every retained variant; it is not a final image blur. The TV ablation uses a smoothed structure tensor to reduce smoothing across coherent intensity edges, with directional strength 0.8. Its first-order primal-dual steps are 0.30 and 0.40.

**Calibration estimated from this RAW.** Normalised per-colour interpolation at sigma 6 supplies a smooth prediction. Pixels in the lowest 45% of low-pass colour-gradient magnitudes, with predicted signal between 0.025 and 0.8, supply robust per-phase observed/predicted ratios. Gains are normalised to mean one within each colour to preserve overall colour balance. There is no hardcoded measured gain table. The resulting gains span 0.97096–1.01917, with a visible alternating-row component. `LAST_INFO` records the table and fit diagnostics.

On selected same-colour 4x4 blocks, subtract a fitted plane and estimate residual variance with 13 residual degrees of freedom. Median variance estimates are corrected for the chi-square median bias, binned by signal and fitted to nonnegative `a*signal+b`. On this crop: **a=0.00149643, b=0.0000001**, from 45,874 flat blocks and 11 populated intensity bins. `b` reached the floor; this scene does not identify dark read noise well, so the fitted value should not be treated as a laboratory measurement. The shot component dominates. Gain correction is approximate and scene-derived; it has not eliminated the residual grid.

**What the sheets show.** The barcode is the clearest gain. Most of the baseline's coloured zipper and rainbow grain disappear; narrow bars and the number shapes remain separated. The remaining bar edges have faint stipple/ripple, so this is not an artifact-free barcode reconstruction. Mean barcode chroma falls 66% from baseline in `var_best`. Strong ratio smoothness can also suppress real illumination colour, so its score below the 50MP reference is not proof of more accurate colour.

Small-print strokes look cleaner than baseline, but the smallest low-contrast characters still merge. `var_best` retains more stroke modulation than `var_soft`; `var_soft` has the cleaner background and is a reasonable alternative if noise is the priority. I do not see convincing newly readable character detail beyond the 50MP reference. Bright glare remains unrecoverable where channels clipped. The recycling symbol and its caption lose coloured stair-steps, while their edges become somewhat broader. No sharpening or deconvolution was added; the apparent extra crispness of the weak-prior variant comes with more noise, not demonstrated extra resolution.

`var_best` raises fidelity 2.76 dB and reduces fine floor energy 89% versus baseline. It still has over twice the 50MP reference's fine floor energy, and its grid score is 7.79 versus 6.99 for baseline and 4.74 for 50MP. The higher print mid-band number is also ambiguous: supplemental analysis gives floor mid-band energy 2.252 for best, versus print 2.684. The 50MP values are 1.550 and 1.645. These are diagnostics computed separately with the harness's existing energy function, not replacements for its metrics. In particular, the near-zero/negative fine `det-noi` scores do not support a claim of recovered native-200MP fine resolution.

**Other trials and failures.** First-order isotropic TV with locally regressed, Gaussian-smoothed ratios reached 29.63 dB, but its print/noise energies were 0.130/0.277. Increasing TV strength reduced print mid energy to 0.417 and visibly erased small strokes. Scaling regularisation with noise and adding orientation guidance helped, but the retained `var_tv` still has considerably more floor noise and grid energy than the second-order variants. It has the highest retained PSNR; that does not make its print/barcode appearance the best.

Gaussian local ratio regression was replaced with the joint CG colour block so both blocks use the same pointwise sensor data term. Making colour smoothing much broader (sigma 8 in an early local-regression trial) reduced fringe but lowered PSNR to about 29.04 dB and smeared real colour boundaries. More outer rounds sharpened some modulation but slightly reduced fidelity and increased grid energy. Per-phase affine signal/offset fits barely improved the grid and added poorly constrained parameters, so they were removed. A final pilot-gradient flatness boost reduced noise only modestly while lowering print mid energy from 2.684 to 2.014 at boost 2; it was not retained. `var_detail` records the weak-prior failure mode: mid energy 3.492 is accompanied by noise 0.228 and worse grid, with no convincing extra readable text.

**Reduced solve and convergence.** The fast variant processes 16 overlapping tiles. Tile origins and halos preserve the global 8x8 phase; only tile cores are written. Gains, noise parameters and regularisation normalisers are estimated once, never independently per tile.

Relative to best, fast retains **85% of print mid energy**, increases fine noise from 0.090 to 0.097, increases fringe from 0.0152 to 0.0172, and takes **3.80 instead of 8.88 seconds**, a 2.34x speedup. Its PSNR is 0.19 dB higher, consistent with some additional loss of fine structure. The sheet shows slightly softer print and a little more barcode colour.

To separate fewer iterations from tiling, the identical fast configuration was also run on the full crop. Full versus tiled camera-RGB RMSE was **0.00025487**. Their rendered difference at a common exposure had **69.58 dB PSNR**, with RMSE **0.00033194** (0.085 of an 8-bit code value). Boundary-strip RMSE was 0.00034565 versus 0.00033167 away from boundaries; the 99th-percentile absolute difference was 0.0013373. No visible seams appeared in the inspected crop. CG reductions differ between full and tiled domains, so finite-iteration results are not bit-identical; the 48px halo was checked empirically on this scene.

A longer check with 3x60 luminance steps and 3x24 CG steps per colour took 16.45 seconds: PSNR 29.51, detail 0.099, noise 0.089, mid 2.834, fringe 0.0152, grid 8.12. Camera-RGB RMSE to best was 0.003043. More solve time changed the result slightly but did not establish a worthwhile resolution gain. The retained best is deliberately a bounded solve.

**Validation and Java recommendation.** [var_validate.py](../var_validate.py) reproduces calibration checks, output/input checks, the longer solve, full/tiled comparisons and review sheets:

```text
python -u bench/var_validate.py
```

On a synthetic smooth scene with known random 8x8 gains and signal-dependent noise, mean absolute gain error was 0.002706; known `a=0.0015, b=0.00001` fitted as 0.00151878 and 0.000011923. The Laplacian adjoint check had relative error 3.29e-8, and a fixed-luminance CG colour block reduced its actual quadratic energy from 0.728108 to 0.713029. Real-crop outputs were finite, nonnegative float32 arrays of the required shape and did not mutate RAW. [Diagnostic JSON](../out/var_validation.json) records all measurements. References appear only in the offline validator and harness.

Port `var_fast` first and retain `var_best` as the quality target. Work is linear in pixels for fixed iteration counts: approximately `O(N * (K_luma + 2*K_chroma))`, with local five-point stencils, separable Gaussian passes and CG dot-product reductions. Curvature uses two Laplacian applications per luminance iteration. Best therefore has 180 scalar Laplacian passes plus 72 colour-field Laplacian passes; fast has 64 plus 20, before overlap, interpolation and reductions. Use double accumulators for CG dot products and reuse float buffers. Fixed iteration counts make cost predictable.

The largest fast solve tile is 352x608 including halos, rather than the full 896x2048 crop. A Java implementation can keep only two ratio fields (`q_G=1` is implicit), use 64-entry gain lookup, precompute the periodic interpolation-mask denominators, reuse tile workspaces and stream completed RGB rows. A buffer-reusing implementation should need on the order of tens of MB for its tile solver; that is an implementation estimate, not a measured Android memory figure.

The Python prototype still calibrates with full-crop arrays and returns a full RGB array. It is **not** a fully streaming 200MP implementation. For Java, calibration needs a frame pass over sampled/streamed flat patches with small phase/intensity histograms, followed by shared calibration parameters and tile reconstruction; do not fit gains separately per tile. Median/quantile estimation and the two-parameter nonnegative noise fit need simple equivalents, not SciPy on device.

At the requested approximately 104x total area, literal workstation-Python scaling is about **15.4 minutes for best and 6.6 minutes for fast**. Those are not Java latency predictions. The reduction is useful, but neither Python result establishes acceptable phone capture latency. The best supported outcome here is a cleaner reconstruction with a measurable speed/quality trade-off; native-200MP resolution recovery remains unproven.
