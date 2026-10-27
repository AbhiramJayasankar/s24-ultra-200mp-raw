# Luminance/chroma split (`lc_`)

Recommend **`lc_best`** for the next phone prototype. It removes most of the
baseline's coloured barcode zippering and noise while retaining useful text
contrast. `lc_detail` is the noisier alternative when retaining thin strokes
matters more than a clean surface. Neither result establishes that this capture
contains visibly resolved information beyond the real 50MP frame.

## Reproduce

From the repository root, with the supplied Python environment:

```powershell
python bench/harness.py ratio_baseline lc_uncalibrated lc_calibrated lc_guided lc_gaussian lc_detail lc_best
python bench/harness.py ratio_baseline lc_detail lc_best
```

The second command produces the easier-to-inspect comparison:
[reference/baseline/detail/best sheet](../out/sheet_ratio_baseline_lc_detail_lc_best.png).
The first command's
[complete sheet](../out/sheet_ratio_baseline_lc_uncalibrated_lc_calibrated_lc_guided_lc_gaussian_lc_detail_lc_best.png)
and corresponding `results_*.json` contain all retained variants. The harness
also writes an upright full crop for each variant. Outputs are ignored by Git;
rerun the commands to regenerate them.

## Unchanged harness measurements

These are the complete metrics from the final all-variant harness run. Times are
wall-clock reconstruction times on this machine, including the algorithm import;
they vary between runs. The 50MP row is the harness reference, not an algorithm
that uses 200MP data.

| Algorithm | psnr50 | detail | noise | det-noi | mid | fringe | grid | seconds |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 50MP upscaled 2x | 45.79 | 0.044 | 0.037 | 0.007 | 1.645 | 0.0257 | 4.74 | — |
| ratio_baseline | 26.83 | 0.874 | 0.789 | 0.085 | 3.795 | 0.0453 | 6.99 | 1.7 |
| lc_uncalibrated | 28.74 | 1.179 | 1.199 | -0.020 | 5.592 | 0.0153 | 32.16 | 0.3 |
| lc_calibrated | 28.74 | 1.183 | 1.195 | -0.012 | 5.574 | 0.0152 | 21.03 | 0.9 |
| lc_guided | 28.93 | 0.822 | 0.411 | 0.411 | 4.004 | 0.0143 | 17.85 | 2.2 |
| lc_gaussian | 29.04 | 0.182 | 0.159 | 0.023 | 3.368 | 0.0143 | 19.31 | 1.8 |
| lc_detail | 29.02 | 0.253 | 0.200 | 0.053 | 4.405 | 0.0143 | 2.03 | 2.6 |
| **lc_best** | **29.12** | **0.083** | **0.052** | **0.031** | **2.976** | **0.0143** | **2.39** | **2.8** |

For `lc_best` versus the baseline: PSNR increases by 2.29 dB, floor fine-frequency
noise falls about 93%, barcode chroma falls about 68%, and grid score falls about
66%. Its mid-frequency text energy is lower than the baseline, whose noise is
much higher, and about 81% higher than the upscaled 50MP reference. Energy is not
resolution: `detail - noise` is only 0.031, and text/floor do not have identical
brightness or noise variance. Do not use that subtraction as an unbiased estimate
of recovered signal. The self-guided control's high 0.411 subtraction also does
not mean it wins visually; it retains noisy highlight texture.

## Reconstruction

`lc_core.py` is a shared library, not another harness algorithm. The six small
modules expose the required `reconstruct(raw, meta)` entry points.

1. **Estimate a per-frame 64-phase gain table.** For each native colour, normalized
   Gaussian convolution at sigma 8 predicts its slowly varying intensity at every
   pixel. Reject very dark and near-clipped predictions, downweight gradients,
   and fit each phase's raw/prediction ratio with three robust reweighting steps.
   Normalize gains to mean one within each colour and bound them to [0.85, 1.15].
   No phase table is saved or learned from a reference. The measured table on this
   crop ranges approximately from 0.975 to 1.018. It shows the alternating-row
   response seen in the raw averages. In insufficiently supported phases the fit
   falls back to unity.
2. **Estimate colour at block scale.** Average each 4x4 native block, keeping the
   two green blocks as independent samples on a quarter-width/height GBRG grid.
   Run Menon demosaicing on this small grid only. White-balance its RGB using
   `1 / meta['as_shot_neutral']`, smooth with sigma 2 block pixels, and form colour
   ratios `q_c = C_c / (.25 R + .5 G + .25 B)`. Bilinearly interpolate ratios at
   the true block centres, `(4i + 1.5, 4j + 1.5)`. Smooth ratios avoid constant
   per-block multipliers or jumps at supercell boundaries.
3. **Use every raw pixel as a luminance measurement.** Compute
   `L = corrected_raw / neutral[native] / q_native`. This uses a smooth estimate
   of the native channel's colour weight instead of treating raw white-balanced
   intensity as neutral everywhere. Without denoising or offset correction,
   recombination reproduces the gain-corrected native sample.
4. **Correct residual periodic offsets in `lc_best` and `lc_detail`.** Gain-only
   fitting left a strong residual grid. Compute `L - Gaussian(L, sigma=6)` and
   estimate its mean separately for all 64 phases, with weights from smooth
   brightness and relative gradient. Smooth numerator/weight on each decimated
   phase grid at sigma 8, equivalent to sigma 64 native pixels. Use a small global
   prior when local flat support is weak. Subtract the resulting offset field,
   after forcing its mean over every 8x8 cell to zero. This changes periodic
   response without directly changing the cell's mean luminance. It is empirical
   residual suppression, not proof that every residual is an electronic offset.
5. **Denoise luminance.** Use `sqrt(max(L, 0) + .01)` to reduce brightness dependence
   of the noise. Estimate a global noise scale by MAD of a sigma-1.2 high-pass
   residual, with a fixed approximate white-noise attenuation correction. The
   guide is a sigma-0.8 blur, and the target is the unsmoothed transformed luma.
   Guided linear regression uses radius 3 and epsilon `0.5 * noise_sigma^2`.
   Square and subtract .01 afterward. `lc_detail` uses sigma 0.65, radius 2, and
   epsilon `0.25 * noise_sigma^2`. There is no unsharp mask, deconvolution,
   detail injection, or contrast boost.
6. **Guide chroma and recombine.** Guide each full-size ratio by a sigma-1 blur of
   denoised L, radius 8, epsilon .0025. Renormalize their weighted sum to one.
   Return `max(L, 0) * q_c * neutral[c]`, float32 camera RGB. The final neutral
   multiplication removes the internal white balance; rendering belongs to the
   unchanged harness.

All retained reconstruction code uses only the input `raw` and `meta`. There is
no reference read, external calibration image, hard-coded scene region, or
reference-derived colour/phase table. Spatial scales and denoising strengths were
selected on this benchmark, so this is still a one-scene experiment.

## What the sheets show

I opened the baseline sheet, the initial four-control sheet, exploration sheets,
and the final `ratio_baseline / lc_detail / lc_best` sheet as images.

- **Barcode:** baseline has conspicuous multicolour vertical bands and jagged,
  coloured bar edges. Both final variants give largely grey bars, more continuous
  edges, and coherent digit shapes. `lc_best` gives the cleanest white gaps;
  `lc_detail` retains more granular texture. Broad colour from the nearby coloured
  label remains. Smooth chroma removes much of the false colour without recovering
  additional true colour resolution.
- **Small print:** the split makes stroke shapes more coherent than the baseline's
  speckled, coloured texture. The lighter variant has more thin-stroke contrast
  and more noise. The balanced version softens the finest strokes. Many letters
  remain unreadable in both, and the glossy overexposed band remains washed out.
  I cannot identify a convincing new readable word or separated feature that is
  absent from the 50MP reference.
- **Logo:** the recycling shape and small caption have less coloured contouring
  and a much cleaner surrounding surface. `lc_best` slightly rounds corners and
  smooths the caption. Low-frequency mottling and tiny highlight defects remain;
  a low fine-noise score does not mean all visible noise is removed.
- **Grid:** calibration helps numerically, but gain-only variants retain excessive
  periodic structure. The final additive correction is necessary for the low grid
  scores. A score below the 50MP reference does not establish a perfect sensor
  calibration; it measures suppression of phase-correlated energy in this crop.

## Controls, failures, and limits

`lc_uncalibrated` and `lc_calibrated` isolate the split and gain fit. Their high
fine/mid energy comes with worse floor noise than the baseline. They are useful
ablations, not quality recommendations. The first gain estimator centred its
robust weights at exactly 1.0, which shrank the fitted response differences;
recentring the weights per phase improves the gain fit.

`lc_guided` uses a radius-2 self-guided filter in linear L, followed by guided
chroma. It mistakes isolated noisy pixels and highlight texture for edges.
`lc_gaussian` uses a simple sigma-0.65 luma blur as a control. It reduces noise
but softens fine strokes and leaves grid structure. The final preblurred guide
is a better compromise than either control.

Offline trials are reproducible with `python -m bench.lc_explore`,
`python -m bench.lc_calibration_explore`, and
`python -m bench.lc_additive_explore`. These scripts import the unchanged harness
metrics and load references only for evaluation; they are not reconstruction
modules. Their sweeps have diagnostic labels rather than retained algorithm names.

- **Additional multiplicative luma calibration:** global and spatial raw/smooth
  ratio fits plateaued at grid roughly 12–15 after denoising. Multiplicative
  correction did not describe the remaining phase residual sufficiently well.
- **Global affine raw calibration:** fitting both gain and offset per phase found
  offsets of a few DN, but had essentially unchanged PSNR/noise/grid. Merely
  expanding the global physical-response model did not solve the spatial residual.
  The optional affine and ratio-fit helpers remain in `lc_core.py` only to replay
  these analyses; retained entry points do not call them.
- **Nonlocal means:** a CPU patch-distance experiment in the square-root domain
  tried search radii 3/4 and patches 3x3/5x5. It took roughly 2.6–4.2 s for denoising
  alone, left fine noise around 0.25–0.41 and gave PSNR around 29.0–29.1. It retained
  mottled highlight texture and was a worse noise/cost compromise than the final
  guided filter. It is kept only in the analysis script.
- **Strong luma smoothing:** a sigma-1.2 guide with radius 4 gave noise about
  0.003 but mid energy about 1.16, below the 50MP reference. This removed useful
  strokes along with noise.
- **Tight local phase fits:** sigma 2 on the phase grid could push the grid score
  down, but reduced PSNR and risked fitting real image structure. The selected
  sigma 8 fit has much broader support. Winsorizing residuals reduced offset-fit
  effectiveness substantially on this scene; the chosen fit instead uses the
  broad spatial average and smooth-gradient weights.

The central ambiguity remains: one frame cannot distinguish actual phase-locked
fine scene texture from sensitivity modulation without assumptions. Our flat-area
test uses smoothed gradients; a fine repetitive grating can evade it. The local
offset fit can suppress real texture near the 8-pixel periods, especially if it
occupies a large region. Small saturated or strongly coloured details can also
receive the wrong luma correction because their colour is undersampled. Guided
chroma can spread/desaturate small coloured lettering. The square-root model and
global MAD are approximations, not an ISO-calibrated Poisson/read-noise model.
Additional neutral charts, coloured edges, and a resolution chart are needed
before describing this as recovered 200MP resolution.

## Verification and Java complexity

The exact plain `python bench/harness.py ...` command works without setting
`PYTHONPATH`. All variants return correctly sized float32 arrays under the
harness. Additional numerical checks on `lc_best` used black, constant saturated
camera RGB, and constant non-neutral camera RGB inputs: outputs were finite, and
interior camera-RGB errors were below 4e-7. A synthetic smooth illumination field
with injected alternating-row gains 0.97/1.03 recovered the gains within 0.00015;
reconstruction relative RMSE was about 0.00010. These checks establish numerical
behaviour, not resolution on real photographs.

For fixed filter radii the algorithm is **O(N)** in pixel count. The 64 phase
filters together process N samples per numerator/denominator pass, not 64N.
Chroma demosaicing works on N/16 pixels. Full-size work consists of several
separable Gaussian passes, box-filter guided regressions, interpolation, and
scalar arithmetic. There is no FFT, global solver, model inference, or patch
search in the recommended variant.

The Python prototype materializes full-size arrays and should not be copied
literally into Java. Use a calibration/statistics pass, then block-aligned bands
with shared box sums for the three chroma targets, reusable buffers, and
scanline/ring-buffer filtering. Approximate MAD with a residual histogram or
sampling. The broad phase fit has radius 32 samples on its decimated grid,
equivalent to 256 native pixels on each side; upstream smoothing adds more halo.
Plan bands of hundreds of rows or streaming lookahead, not tiny independent
tiles. Preserve the global native 8x8 phase at band boundaries. Exact Gaussian
kernels can be costly; box-filter approximations need a fresh benchmark before
claiming the same results.

Observed reconstruction is 2.8 s for 1,835,008 pixels, comfortably below the 60 s
crop limit. A full 200MP frame is about 109 times this area. Linear scaling of
this Python implementation alone would be minutes, so these measurements do
not establish acceptable phone latency. The recommendation is to port the
separation and filters with band processing, then measure memory, latency, and
visual quality on the phone before choosing this path for the app.

No harness, benchmark data, or app code was changed. No commit was made.
