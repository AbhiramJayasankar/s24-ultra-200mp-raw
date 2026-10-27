# Directional 16-cell reconstruction experiment

Recommend **`dir_best`**, the direct full-resolution RGB output. It makes the
barcode considerably cleaner than `ratio_baseline`, with fewer coloured teeth
on bars and digits. It does **not** demonstrate a convincing resolution gain
over the independently captured real 50MP frame. More native high-frequency
energy mostly brings back grain; stronger suppression softens small print.

All kept algorithms use only the supplied 200MP `raw` array and `meta`. There
are no image reads, reference-frame dependencies, scene coordinates, persistent
calibration tables, learned models, or sharpening stages in them. The 50MP and
12MP data were used by the unmodified harness for comparisons only. No app,
benchmark metric, data, or baseline source was changed. No commits were made.

## Reproduce and compare

From the repository root:

```powershell
python bench/harness.py ratio_baseline dir_calibrated_ratio dir_fit dir_edge dir_detail dir_best dir_bayer
```

The final complete harness run gave the following results. The helper module
`dir_core.py` is shared implementation, not a benchmark entry point. Times are
wall time on this CPU and vary between runs.

| Algorithm | psnr50 | detail | noise | det-noi | mid | fringe | grid | seconds |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 50MP upscaled 2x (reference) | 45.79 | 0.044 | 0.037 | 0.007 | 1.645 | 0.0257 | 4.74 | — |
| ratio_baseline | 26.83 | 0.874 | 0.789 | 0.085 | 3.795 | 0.0453 | 6.99 | 1.6 |
| dir_calibrated_ratio | 26.94 | 0.869 | 0.778 | 0.091 | 3.777 | 0.0445 | 3.56 | 1.7 |
| dir_fit | 29.35 | 0.895 | 1.053 | -0.158 | 3.832 | 0.0137 | 8.31 | 2.7 |
| dir_edge | 29.31 | 0.992 | 1.141 | -0.149 | 4.301 | 0.0134 | 8.32 | 5.6 |
| dir_detail | 29.58 | 0.599 | 0.382 | 0.217 | 3.568 | 0.0136 | 7.82 | 2.8 |
| **dir_best** | **29.75** | **0.167** | **0.166** | **0.001** | **2.808** | **0.0139** | **6.83** | **4.1** |
| dir_bayer | 29.77 | 0.155 | 0.123 | 0.032 | 2.788 | 0.0174 | 7.00 | 5.6 |

Compared with the baseline, `dir_best` gains 2.92 dB, reduces the fringe metric
by 69%, and reduces fine floor energy by 79%. Its grid score is only slightly
better than baseline and remains worse than the real 50MP reference. The
calibration-only ablation improves grid much more, but leaves the baseline's
colour and detail problems. These improvements cannot all be claimed together
as the result of the recommended variant.

Useful harness sheets, inspected as images:

- [Baseline, best direct RGB, and Bayer round trip](../out/sheet_ratio_baseline_dir_best_dir_bayer.png).
- [All kept variants](../out/sheet_ratio_baseline_dir_calibrated_ratio_dir_fit_dir_edge_dir_detail_dir_best_dir_bayer.png).
- [Explicit edge steering versus the faster denoiser](../out/sheet_dir_tensor_dir_smooth.png)
  shows two discarded trials described below; `dir_smooth` is identical to the
  kept `dir_best`.

Every sheet includes the harness's real 12MP x4 and 50MP x2 comparisons.
The JSON for the complete run is
`bench/out/results_ratio_baseline_dir_calibrated_ratio_dir_fit_dir_edge_dir_detail_dir_best_dir_bayer.json`.

## Reconstruction

1. **Runtime phase calibration.** Treat the four 4x4 blocks in an 8x8 cell
   separately. Compute their means and estimate scene slopes from neighbouring
   same-colour block means spaced eight native pixels apart. Subtract the
   corresponding local plane before pooling residuals by pixel position.
   Reject dark, clipped, strongly textured, and steep-gradient blocks. Fit
   median phase residuals in twelve equal-population signal bins to
   `residual = gain_error * signal + offset`. Constrain the mean correction
   within each block to zero, and bound the correction. This avoids using
   calibration to adjust overall colour balance. A frame without sufficient
   qualifying blocks falls back to identity calibration.

   On this frame the estimated multiplicative terms span approximately
   0.9896–1.0167, with additive terms -2.82 to +1.97 DN. Raw phase ratios vary
   roughly 1–3%, especially alternating rows. An offset term helps represent
   the stronger relative pattern at lower signals; the coefficients are
   empirical scene estimates, not a measured permanent sensor calibration.
   Both green blocks retain independent within-block calibration; their
   overall relative means are not forced to agree across scene edges.

2. **Full-resolution common-green guide.** Average each 4x4 native block and
   Menon-demosaic that small GBRG image. Upsample at the true block-centre
   coordinates `(y-1.5)/4, (x-1.5)/4`, avoiding the baseline's approximation of
   two separated green blocks by one super-cell-centred mean. In a temporary
   white-balanced working space, scale every native pixel by the coarse
   green/native-channel ratio. This is a full-resolution guide whose fine
   content comes from the native R, G, or B sample at that exact position.

3. **Two local residual-regression updates.** For each channel, fit
   `channel = a * guide + b` using only that channel's native samples in a
   13x13 window. Smooth the coefficients with a second 13x13 box. Update the
   guide by transferring the actual native value through the predicted
   green/native ratio. Ratio bounds and a dark-signal denominator floor limit
   instability. This uses R/B spatial detail inside the 4x4 holes in green,
   instead of filling those holes solely from distant green measurements.
   It is a masked local affine method inspired by residual interpolation,
   not an implementation or reproduction of published Kiku/Monno MLRI.

4. **Noise/detail trade-off.** `dir_fit` leaves that guide unsmoothed.
   `dir_detail` shrinks its residual relative to a sigma=0.75 Gaussian using
   local variance and a robust whole-frame noise estimate. The residual
   multiplier stays between zero and one. `dir_best` instead estimates a
   per-native-colour `variance = a * signal + b` model from in-block 2x2
   diagonal Haar responses, using binned medians to reject edges. It applies
   a bounded bilateral filter with spatial sigma=1.5, a sigma=0.7 range
   guide, and range denominator `4 * estimated_variance`. Its 7x7 support
   uses 45 taps. The range test preferentially averages along edges.

5. **Direct RGB.** Fit the three native channels against the cleaned guide
   with the same masked affine model. Finally undo the working white balance
   to return float32 linear camera RGB. The harness performs all rendering.

`dir_edge` is the explicit directional alternative to the final regression:
interpolate green-minus-guide and then R-minus-green / B-minus-green along
horizontal, vertical, and both diagonal lines. A triangular 17-sample support
bridges missing blocks; weights favour directions with smaller guide changes.
This tests the classical direction-selection idea directly. It has excellent
barcode chroma, but larger grain and no convincing additional readable detail.

`dir_calibrated_ratio` isolates calibration by running the existing baseline
on calibrated raw. `dir_bayer` samples `dir_best` into a GBRG mosaic and applies
Menon again. It tests a Bayer interface to a downstream demosaicer; it is not
an independent reconstruction from fewer samples.

## What the sheets show

- **Barcode:** The baseline has rainbow bands, a visible 4/8-pixel fabric,
  and coloured zipper teeth on bars and digit edges. Calibration alone removes
  some fabric but does little for the colours. Native-guide variants make bars
  mostly neutral and straighter, and digit contours easier to follow. There
  are still soft/jagged edges at individual pixels; this is not a perfect
  barcode reconstruction. The Bayer round trip reintroduces cyan/coloured
  edge traces, despite slightly higher PSNR and lower noise.
- **Small print:** The new RGB reconstruction avoids much of the false
  coloured texture. Denoising makes faint strokes and glare texture softer.
  I cannot confidently read extra small characters that the real 50MP frame
  cannot resolve. The large mid-band energy is not proof of extra resolution:
  it includes grain and the glossy carton texture. The fixed harness is
  unchanged, including its supplied interpretation of frequency bands.
- **Logo:** Recycling-arrow shapes and the lettering look cleaner with direct
  RGB, with fewer green/cyan outlines. The real 50MP comparison still has
  useful subtle texture and competitive lettering. The reconstruction's
  smooth colour fields can also suppress real fine colour variation.
- **Floor:** `dir_best` is far less grainy than the baseline but still has
  about 4.5 times the reference's fine floor energy. Grid energy is not
  eliminated. The logo sheet is not a substitute for the actual floor metric.
- **Glare:** Clipped pink highlights remain, as in the fixed rendering of the
  reference. This experiment does not recover clipped sensor information.

`dir_detail` has the largest positive det-noi among the kept candidates, but
that does not make it the resolution winner: its residual includes visible
grain, and bright text and dark floor have different noise statistics. The
recommended `dir_best` has almost equal fine text/floor energies. That is an
explicit reason to avoid claiming recovered 200MP fine resolution. There is
no unsharp mask or deconvolution here, but copying native high frequencies
still copies and can amplify noise through channel normalization.

## Discarded trials and why

These trial entry points and unused helper code were removed; their harness
outputs remain under `bench/out/` as experiment records.

| Trial | psnr50 | noise | mid | fringe | grid | seconds |
|---|---:|---:|---:|---:|---:|---:|
| dir_bilateral, weaker range/spatial filtering | 29.67 | 0.253 | 3.356 | 0.0136 | 8.26 | 4.0 |
| dir_equalized, extra common-guide phase fit | 29.74 | 0.167 | 2.803 | 0.0141 | 6.71 | 4.4 |
| dir_diffuse, seven edge-stopped diffusion steps | 29.66 | 0.274 | 3.077 | 0.0139 | 5.90 | 3.4 |
| dir_patch, 7x7 search / 5x5 patches | 29.64 | 0.565 | 3.471 | 0.0136 | 6.72 | 7.2 |
| dir_patch5, 11x11 search / 5x5 patches | 29.66 | 0.521 | 3.391 | 0.0138 | 6.98 | 13.0 |
| dir_tensor, 9x9 edge-steered elliptical kernel | 29.79 | 0.190 | 2.898 | 0.0136 | 7.80 | 7.1 |

The extra phase fit barely helped and added a scene-dependent correction.
Diffusion reduced the grid score but left more grain. Patch filtering was too
expensive for its weak noise reduction; raising its strength sufficiently
blurred print and reduced mid energy substantially. Tensor steering preserved
a little more mid energy but looked close to the faster bilateral candidate,
with higher noise and grid and nearly twice the runtime.

Additional parameter probes used the same harness metric functions without
changing them: final regression radii 3/4/6/8, regularization 0.000025 to
0.0004, Gaussian instead of box coefficient windows, smoothed native residual
correction, no calibration, and a wider/stronger bilateral. Smaller windows
increased colour/grid artifacts. Gaussian windows moved PSNR by only about
0.04 dB at higher cost. Adding residual correction made fringe worse. Omitting
calibration raised grid from 6.83 to 9.78. A wider bilateral reduced noise to
0.126 but reduced mid to 2.309 and further softened print. None justified
replacing the simpler kept balance. These probes were not retained as entry
points.

## Java cost and remaining limits

Everything is CPU-local with fixed radii. For N native pixels, `dir_best`
uses nine masked regressions (three channels across two guide updates and
one output stage), each with seven box filters: 63 box-filter applications,
plus the 45-tap bilateral, a few separable Gaussians and full-resolution
interpolations. Menon runs on only N/16 pixels. Running-sum box filters make
the total **O(N)**. Counts and raw sample means can be reused between guide
iterations; interior CFA mask sums can be precomputed for each phase.

The Python prototype holds full-resolution intermediates and is not ready
to allocate a full 200MP frame's intermediates on a phone. Port it with row
buffers/bands, float32 accumulators, and reused work arrays; use an exponential
lookup table if appropriate after checking its error. Keep band origins
aligned to the 8x8 CFA and supply sufficient halo for the *composition* of
passes, not just a six-pixel halo for one box. Each masked regression has
12-pixel support before composition with earlier guides. The coarse Menon
guide also needs a halo. Exact band size/halo equivalence remains to be
verified during the port.

Calibration and noise estimation are frame-wide robust statistics. Estimate
their small tables once from spatially distributed samples/tiles, then reuse
them for every band. Re-estimating them independently per band risks seams.
This sampling optimization and Java timing were not implemented or benchmarked;
the approximately 4-second result is for this Python crop, not the phone or a
full 200MP frame. Do not extrapolate it as a phone latency estimate.

Keep direct RGB when possible. If the existing downstream API requires Bayer,
`dir_bayer` is a tested interface option, with the measured colour penalty.
For a shipping choice based on this scene alone, retain real 50MP capture as
the quality reference: this experiment establishes artifact reduction, not
newly proven 200MP resolution. More scenes with unclipped, well-lit fine text
and coloured edges are needed before treating these parameters as general
sensor tuning.

Verification: all kept variants completed the unmodified harness well under
60 seconds. Ad hoc constant black/colour/saturated/negative mosaics passed
shape, float32, finite-output, colour preservation, and input-unchanged checks
for the best/detail/explicit-directional paths. These checks do not establish
natural-image resolution or the correctness of an eventual tiled Java port.
