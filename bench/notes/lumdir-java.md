Java lumdir / LinearRaw DNG, 2026-10-27

Historical initial implementation measurements below. Current native
Directional/chroma design, bounded memory and validation are in
[lumdir-native.md](lumdir-native.md).

The 200MP save path now writes full-colour camera RGB through Lumdir16,
NoiseCurve and LinearDngWriter. The 50MP/12MP Bayer writer is retained.
The optional sensor DNG is controlled by save(..., keepSensorRaw, progress);
the app passes false. There is no commit associated with this work.

Reproduction from the repository root:

```powershell
python bench/make_lumdir_golden.py
.\gradlew.bat assembleRelease testDebugUnitTest
.\gradlew.bat lumdir16Bench
```

The benchmark maps the external capture directly at
`bench/data/full_16320x12240_rs20400.raw10`.
It does not copy the input. Geometry is 16320 x 12240, row stride 20400,
black/white 64/1023, neutral [0.5, 1, 0.55]. Output is `build/lumdir200.dng`.
The Gradle benchmark task uses JBR 21.0.7, eight workers, -Xmx512m and
-XX:MaxDirectMemorySize=5g. Direct stores are only for desktop use; the Android
caller creates and unmaps SharedMemory instead.

Streaming and halo design:

- Directional16 exposes its three final fits without Bayer quantization. The
  established Bayer arithmetic is unchanged; LinearRaw uses NumPy's pairwise
  16-value calibration reduction, row-grouped coarse means, and float32 noise
  medians to keep the output noise curve consistent with the reference.
- Colour reconstruction uses fixed 384-row bands, with eight extra aligned
  rows on either side for the Gaussian radius four. Directional's work halo is
  32 rows and its RAW dependency is at most 64 rows (72 including the aligned
  colour extension). Only 16 overlap rows per 384-row band are reconstructed
  again; thumbnail/encoding never repeat the reconstruction.
- Three caller-provided float planes store ratios. The uniform box is [-4,+3]
  pixels, with float32 rounding after each axis, and writes relative luminance
  into a fourth caller plane. All four buffers preserve their position/order.
- The 12 x 12 phase median uses [-6,+5] cells, or [-48,+40] pixels, on each
  independently reflected 8x-downsampled phase grid. A serpentine Huang
  histogram slides 24 samples per horizontal step. A two-level occupancy
  bitset skips empty bins while maintaining the 73rd order statistic. Dense
  bins have width 1e-5; ordered float tails preserve values outside the dense
  range without clipping. There is no per-pixel sort or selection. Phase jobs
  start on separate pixel rows to reduce false sharing and are claimed
  dynamically by up to eight workers.
- Only the current output band's medians and RGB codes stay on the heap.
  Median normalization sums all 64 phases in the reference order. Fixed colour
  and uniform pass boundaries make output-band size independent of floating
  point accumulation. Eight-row versus whole-crop output is exactly identical.
- LinearDngWriter pulls rows once, accumulates an RGB thumbnail during that
  pass, pads edge tiles, compresses a 256-row band in parallel and writes tiles
  in completion order. TIFF tile offsets retain spatial order. It appends the
  thumbnail and directories, then patches the IFD0 offset at byte four. Every
  write and the final directory layout check the classic TIFF 4 GiB limit.
- Ljpeg92TileEncoder uses SOF3 predictor 1, interleaved RGB, category-16 DNG
  semantics and an optimal per-tile canonical DC table. Dynamic programming
  minimizes the histogram-weighted code lengths under the 16-bit limit; a
  zero-weight pseudo symbol reserves the all-ones code. The factory
  LinearDngWriter.createTileEncoder() is the replacement point for JPEG XL.
  JPEG XL compression/version and FLOAT/LONG option tags are covered by tests.

Validation:

`assembleRelease testDebugUnitTest` passed: 25 tests, zero skipped/failures.
Both golden inputs are packed from the saved integer DN arrays, and Python
runs on those same re-quantized values. The generated arrays are git-ignored.

| Input | Mean code absolute error | 99.9th percentile | Maximum | Linear RGB maximum absolute error |
|---|---:|---:|---:|---:|
| book (2240 rows x 1680 columns) | 0.0004063 | 0 | 1 | 0.000018358 |
| tile (4096 x 4096) | 0.0000714 | 0 | 1 | 0.000005722 |

Linear errors compare the reconstructed camera RGB before curve quantization,
without clipping highlights. Curve tables also match within one linear16 unit.
Tests cover arbitrary histogram tails, progress/borrowed buffers, exact band
identity, interleaved LJ92 round trips, bounded canonical lengths, DNG metadata,
padded tile edges, offsets, a single source read pass, and JPEG XL tag types.
The existing Directional16 tests pass. A full-frame Bayer regression run took
5.674 s and retained checksum `1882ebb4c6b6463c` from the optimized baseline;
its peak primitive payload remained 406,990,372 bytes.

Latest full-frame desktop run (fresh JVM, existing OS file cache):

| Stage | Wall seconds |
|---|---:|
| Map input / allocate caller stores | 0.438 |
| Directional statistics | 0.813 |
| Initial guide | 0.930 |
| Guide update | 1.602 |
| Bilateral | 1.772 |
| RGB fits | 1.541 |
| Gaussian chroma ratios | 2.420 |
| Uniform / relative luminance | 0.514 |
| Phase medians | 9.399 |
| RGB / noise-curve codes | 0.630 |
| Tile preparation / encoding / I/O | 2.458 |
| Total, including allocation and unassigned overhead | 22.650 |

The output is 363,218,292 bytes (363.22 MB / 346.39 MiB), 3072 tiles. Noise
parameters are a=0.00677254759484, b=0.000161597679835, with 85 table entries.
The heap cap is 512 MiB: peak sampled used heap was 529.03 MB / 504.52 MiB;
peak working primitive payload bound is 367.23 MB. Caller stores occupy
3196.11 MB and the input mapping 249.70 MB. A Windows process memory monitor
recorded 4042.61 MB peak working set and 3867.87 MB peak sampled private
commit. Memory includes the whole reconstruction, encoder and output pass.

Independent validation with tifffile / imagecodecs decoded the first, middle
and final JPEG tiles as uint16 RGB. LibRaw recognized the full-size DNG as a
16320 x 12240 raw image. The release APK is at
`app/build/outputs/apk/release/app-release.apk`.

The managed Windows sandbox required temporary drive aliases to let Java
canonicalize allowed workspace/SDK paths, and a workspace-local copy of the
read-only Gradle cache. These are build-environment workarounds, not app changes.
local.properties is restored to the actual SDK path after validation.

On-device verification remains: ART wall time and GC/shared-memory pressure
after repeated 200MP shots; MediaStore progress/save behavior; and importing the
Java DNG into Adobe Lightroom mobile with raw controls and Kelvin white balance.
The Python format already passed Lightroom, but the Java output has not yet
been checked there. Desktop timing is not a prediction of phone timing.

Automatic approval review blocked recursive cleanup of the ignored temporary
cache at build/gradle-home, returning only 'blocked by policy'. That cache remains.
