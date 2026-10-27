# Directional16 Java port

Implemented in `app/src/main/java/dev/s24cam/dng/Directional16.java`, with the
Python specification in `bench/algos/dir_port.py`. No application UI or existing
remosaicer/writer changes are needed to use this RowSource. The caller owns the
RAW10 buffer, must keep it unchanged during conversion, and should close the
source afterward to release its daemon worker pool.

`camera_rgb(raw, meta)` returns the portable reconstruction before Bayer
sampling. `bayer(raw, meta)` samples GBRG, clips to the DNG white level, and
returns normalized camera units. `reconstruct` runs the same Bayer/Menon round
trip as `dir_bayer`, so the harness evaluates the DNG interface. Java adds black,
rounds to integer DN, and clamps to [0, white].

## Quality

Command: `python bench/harness.py dir_best dir_bayer dir_port` (unmodified harness).

| Algorithm | psnr50 | detail | noise | det-noi | mid | fringe | grid | seconds |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| dir_best | 29.75 | 0.167 | 0.166 | 0.001 | 2.808 | 0.0139 | 6.83 | 3.75 |
| dir_bayer | 29.77 | 0.155 | 0.123 | 0.032 | 2.788 | 0.0174 | 7.00 | 5.35 |
| dir_port | 29.80 | 0.197 | 0.168 | 0.029 | 2.551 | 0.0186 | 6.57 | 4.32 |

PSNR is 0.03 dB higher and fringe is 6.9% higher than dir_bayer, within the
requested limits. The [comparison sheet](../out/sheet_dir_best_dir_bayer_dir_port.png)
was inspected as an image. Text, barcode digits and logo contours are comparable;
there is slightly more fine texture and slightly less mid-band energy. No new
visible seams or loss of legibility was apparent. This is a crop/scene quality
check, not a claim of additional resolved detail over the real 50MP capture.

## Portable choices

- Keep the robust 8x8 phase affine calibration, native-sample masked affine
  regressions (radius 6, epsilon 1e-4), true coarse block-centre coordinates,
  temporary white balance, and camera-space output.
- Calibration visits a regular grid of super-cells, with step
  `ceil(sqrt(number_of_supercells / 32768))`, at least one. All 16 phases of
  each of the four blocks are observed. Neighbouring block means supply slopes.
- Omit Menon's optional coarse refinement. Plain bilinear coarse demosaicing
  was tried and rejected because barcode fringe exceeded the limit.
- Use one guide update instead of two: six regressions instead of nine.
  Counts and four moments are fused into running-sum boxes. Coefficient boxes
  have an explicit worker barrier because their windows cross worker stripes.
- Estimate the per-colour Haar noise model from the initial guide, once in the
  constructor. Sample 2x2 squares on an odd-stride grid near 65,536 samples.
  The odd stride is essential: an even stride can alias the native CFA and
  miss red/blue on a full frame. The crop uses step 3, the full frame step 29.
- Use a separable joint bilateral: seven horizontal taps followed by seven
  vertical taps, both using the original range reference and variance field.
  Its range denominator is `3 * variance + 1e-8` (the initial-guide variance
  requires less strength than the original post-update estimate). Spatial
  sigma stays 1.5. A 5x5 variance box replaces the sigma-1.5 Gaussian, and the
  sigma-0.7 range Gaussian is truncated to radius 1. These changes remove most
  of the denoiser cost while retaining the harness limits.
- Interpolate an `exp(-t)` table at spacing 1/256, capped at t=16. The bounded
  tail error is negligible compared with integer-DN rounding. Python uses the
  same table. Float arithmetic and different box accumulation orders are
  checked against the golden rather than assumed bit-identical across SciPy
  and the JVM.

## Bands and memory

Default output bands are 96 rows, aligned to eight; the final band is 48 rows.
One 32-row working halo is sufficient for the local pipeline:
12 (guide regression) + 5 (composed denoiser) + 12 (output regression) = 29.
The coarse Menon guide adds at most 30 native rows of RAW support, so the
conservative total RAW halo is 64 rows. Coarse bands include eight block rows
of overlap. Image edges use the same distinct reflection conventions as
SciPy's box/Gaussian and NumPy's reflect padding. Interior halo rows are
discarded as the pipeline proceeds.

A single reusable float workspace is partitioned horizontally over eight
workers. A synchronized cache miss owns that workspace; concurrent row/sample
requests can use immutable completed bands safely. There is no per-pixel
allocation, boxing or shared ByteBuffer position mutation. An array-indexed
LRU stores up to 250 MB of packed RAW10 output. The entire 200MP frame fits in
249,696,000 bytes, so thumbnail/sizing/writing requests never recompute it.
The workspace is released automatically after the last band is cached.

Measured peak retained primitive arrays: about **324.59 MB (309.55 MiB)**.
After workspace release: about **254.61 MB (242.82 MiB)**, including cached
output and worker rings. The complete conversion and two-pass JPEG writer
succeeded with `-Xmx350m`; the final GC log reached 335 MiB including JVM
overhead, rounding of large arrays, garbage and the writer's transient buffers
(earlier runs reached 349 MiB). The
249.70 MB input was mapped read-only and is outside these heap figures.

## Verification and full-frame timing

`python bench/make_golden.py` writes the ignored generated output
`bench/data/golden_dir_port_bayer.npy`. The captured input files are unchanged.
Golden comparison on all 1,835,008 crop samples:

- Mean absolute error **0.24748 DN**, 99.9th percentile **0.53372 DN**,
  maximum **1.56134 DN**, including Java's integer rounding.
- 16-row bands match a whole-crop band within 1 DN, including image edges.
- Eight callers in randomized row order match subsequent sequential reads
  exactly, including sparse `sample` checks. Each band is computed once.
- Black/negative/saturated constants, small image edges, input position/limit,
  RAW10 padding, and argument validation are covered.
- All **14 JVM tests** (new and existing DNG tests) pass directly on JBR 21.

`Directional16Main` accepts `[raw10 metadata output.dng [scan]]` and defaults to
the supplied full-frame capture and metadata, writing `build/dir200.dng`.
It maps the input without copying it, pre-reads all rows to separate conversion
and encoding time, then calls the unchanged `DngWriter` with lossless JPEG.
Timing instrumentation is public via `stageSeconds`, `computedBands`,
`cachedBytes`, and `peakArrayBytes`.

PC JVM: Android Studio JBR 21.0.7, eight reconstruction workers, `-Xmx350m`.
Fresh JVM, real 16320x12240 RAW10 (stride 20400), not the crop:

| Stage | Seconds |
|---|---:|
| Constructor (calibration and noise) | 1.204 |
| Coarse demosaic, unpack, initial guide | 1.468 |
| Guide regressions/update | 3.722 |
| Variance/range blur and joint bilateral | 3.650 |
| Output regressions and RAW10 packing | 3.038 |
| Row/cache overhead | 0.108 |
| **Full conversion including constructor** | **13.190** |
| DNG two-pass JPEG encoding/write | 1.133 |
| **End to end** | **14.323** |

Only 128 bands were computed, including both JPEG passes. DNG size:
243,358,455 bytes. `rawpy.imread('build/dir200.dng')` successfully decoded the
12240x16320 image, black [64,64,64,64], white 1023, and GBRG colour pattern.
Using the requested 2–3x PC-to-phone estimate gives **26.4–39.6 seconds** for
reconstruction. The phone target has not been measured on hardware, and the
upper estimate has little headroom. Other full-frame runs ranged from 13.26 to
13.75 seconds (the latter overlapped Gradle compilation); timing variability
can push the 3x estimate above 40 seconds.

Both required offline Gradle tasks passed with Android Studio JBR:
`:app:testDebugUnitTest` (14 tests, no skips/failures) and `:app:assembleRelease`.
The release APK is `app/build/outputs/apk/release/app-release.apk`.

This sandbox denies the JDK's canonical-path lookup when closing SDK JARs
(`core-lambda-stubs.jar` / `core-for-system-modules.jar`). Validation therefore
used an isolated compiler launcher through the public Java compiler API, letting
process exit close JAR handles. The compiler, JBR shim, init script and cached
Gradle distribution link all live under ignored `build/`; no project Gradle
settings or external JDK/SDK files were edited. The requested wrapper commands
use `GRADLE_USER_HOME=$PWD/build/gradle-home` and
`GRADLE_RO_DEP_CACHE=<gradle-user-home>/caches` in this restricted session.
The workspace Gradle home contains the temporary init script. Normal execution
outside this sandbox does not require that file-manager workaround.
