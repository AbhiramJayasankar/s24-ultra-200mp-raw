# Native Directional and chroma, 2026-10-27

The subsequent band-overlap and CPU-utilisation work is documented in
[lumdir-pipeline.md](lumdir-pipeline.md). Measurements below describe v0.3.2.

Starting point: main `e03cfe7` (`v0.3.1`), whose measured S24 Ultra save was
34.5 seconds. These changes are uncommitted. Phone results for this revision
have not yet been measured.

## Implementation

`libs24lumdir` now contains `lumdir_directional.cc` alongside the existing
median kernel. It ports RAW10 lookup/unpacking, all six coarse Menon passes,
native-resolution guide interpolation, masked guide-update regressions,
separable bilateral filtering, final RGB regressions, chroma mean, radius-four
Gaussian, ratio quantization and corrected luminance/ring stores. Calibration
and noise statistics remain in Java with their original reduction order.

The Java implementation remains the fallback. The legacy Bayer Directional16
path is unchanged; native reconstruction is selected for LinearRaw RGB mode.
The native context keeps persistent band arrays, coefficient/moment rings and
worker scratch. Java retains its existing dynamic chunk scheduler and barriers:
32 initial-guide chunks, 16 fit/bilateral stripes and individual chroma rows,
claimed by up to eight workers. There are no native thread pools or idle-pool
waits. All submitted workers drain before borrowed buffers can be unmapped.

Band geometry and floating-point order match Java: fixed 192-row colour bands
plus eight aligned Gaussian-halo rows, Directional work halo 32 and RAW halo
64. Existing phase dependencies remain [-52,+43] pixels. Image edges retain
half-sample reflect or whole-sample mirror as appropriate. Native RGB0 reuses
the unused fit-output arena; after RGB fitting, chroma mean overwrites the raw
arena. Earlier in that same pass, the eight coarse Menon planes also borrow
the fit-output arena until initial-guide interpolation completes. Java omits
colour-only arrays when native chroma is active.

Production reads the borrowed direct RAW buffer and writes the four direct
caller rings directly. There are no pinned Java band arrays or frame-sized
native allocations. Heap RAW inputs used by tests copy only a band plus its
64-row halos with `GetByteArrayRegion`; explicit RGB array reads use
`SetFloatArrayRegion`. Median JNI copies remain bounded at 23/16 KB. Constant
size stores support unaligned caller ring slices as well as aligned shared
memory. Native allocations are freed by `close()`.

Android compilation uses `-O3 -ffp-contract=off -fno-fast-math` and
`-mtune=cortex-x4`; MSVC host builds use `/O2 /fp:strict`. NDK Clang's
vectorization remarks confirm SIMD for both Gaussian axes, chroma mean, final
fit evaluation/coefficient accumulation, and bilateral noise/blur loops.
Scalar sliding reductions retain their original accumulation order.

JPEG XL settings and its required 16-bit codestreams are unchanged.

## Reproduce and verify

From a Windows shell in the repository root, with the existing SDK configured:

```powershell
.\tools\build-lumdir-host.cmd
.\gradlew.bat assembleRelease testDebugUnitTest
.\gradlew.bat testDebugUnitTest -PlumdirNativeTests
.\gradlew.bat lumdir16Bench --args="--java build/lumdir-kernels-java.dng"
.\gradlew.bat lumdir16Bench --args="--native build/lumdir-kernels-native.dng"
python bench/compare_linear_dng_codes.py build/lumdir-kernels-java.dng build/lumdir-kernels-native.dng
```

`--java` / `-Ds24cam.lumdir.java=true` forces Java Directional and chroma,
retaining the established native median to isolate the newly ported stages.
`--native` clears that override. Without the library, both kernels and medians
automatically use Java. Bench output prints each selected backend. The native
test option requires the host DLL and fails if it cannot load; normal desktop
tests exercise the Java fallback. Both Gradle suites passed: **35 tests, zero
failures, zero skipped**. The two new native tests compare RGB exactly at true
edges and arbitrary band boundaries, heap/direct RAW with padding and offsets,
ring wrap, reverse requests, tiny bands, and unaligned direct ring origins.

The managed sandbox needed temporary workspace/SDK drive aliases and
`--offline --no-problems-report`; the optional Gradle problems-report file was
not writable. `local.properties` is restored to exactly
`sdk.dir=<path-to-android-sdk>`, with no drive aliases.

The original 16320x12240, stride-20400 RAW10 capture is mapped in place from
`bench/data/full_16320x12240_rs20400.raw10`.
It is not copied. Both runs use neutral [0.5,1,0.55] and the app's encoder
factory. The JXL DLL is unavailable on this desktop JVM, so these runs use
LJ92, 256x256 interleaved RGB tiles. Each DNG is **363,217,896 bytes**.

## Desktop measurements

Fresh JBR 21.0.7 JVMs, eight reconstruction/encoder workers, `-Xmx280m`.
Final requested Gradle benchmark logs are `build/kernels-java-final.log` and
`build/kernels-native-final.log` (ignored build artifacts).

| Stage/work, seconds | Java Directional/chroma | Native Directional/chroma |
|---|---:|---:|
| Statistics/setup inside Directional | 0.921 | 0.614 |
| Initial guide, including coarse Menon | 1.544 | 0.709 |
| Guide update fits | 2.177 | 1.655 |
| Bilateral | 1.987 | 1.588 |
| Final RGB fits | 1.577 | 1.319 |
| Entire Directional, including statistics/barriers | 8.242 | 5.898 |
| Chroma Gaussian, ratios and ring stores | 1.070 | 0.921 |
| Relative luminance and ring loads | 0.591 | 0.515 |
| Existing native phase median | 3.513 | 3.255 |
| RGB codes | 0.436 | 0.427 |
| Median ns/pixel | 17.59 | 16.30 |
| **Total reconstruction + encoding + file I/O** | **14.501** | **11.467** |

Total is 1.26x faster (21% less wall time). Two earlier pairs measured
14.10/11.28 and 14.47/11.46 seconds, respectively. The statistics algorithm is
unchanged; native setup avoids Java band/scratch allocation. Concurrent encoding
and GC contention also affect individual stage times; small changes in the
untouched median/relative stages are not new algorithms.

The complete main-thread wall ledger is below. Encoder-worker seconds are
aggregate CPU-worker wall time and overlap source work; never add them to total.

| Wall seconds | Java | Native |
|---|---:|---:|
| Map/allocate caller rings | 0.040 | 0.032 |
| Source construction/setup | 0.982 | 0.645 |
| Writer: source reads | 12.975 | 10.464 |
| Writer: tile packing | 0.112 | 0.111 |
| Writer: encoding waits | 0.021 | 0.023 |
| Writer: writes | 0.327 | 0.148 |
| Writer: metadata | 0.001 | 0.001 |
| Writer: other | 0.040 | 0.022 |
| Channel close | <0.001 | <0.001 |
| Other outside writer | 0.003 | 0.019 |
| Total (rounding applies) | 14.501 | 11.467 |
| Overlapping encoder workers | 25.259 | 15.176 |

The source-work table includes statistics performed in construction; do not
add it to the writer/setup ledger. The existing `Save wall` log additionally
accounts for Android insert/open, stream/PFD close, publish and unmapping.

## Accuracy

The independent `tifffile`/`imagecodecs` comparison decodes every LJ92 tile and
excludes padding at the frame's right/bottom edges. Full-frame Java versus
native: **599,270,400 samples, zero changed, mean 0, p99.9 0, max 0**. The
comparison tool also handles JXL and enforces max 1 on fewer than 0.01% of
samples. No reference files were regenerated to obtain this result.

Python golden results are unchanged on the native path:

| Input | Mean code absolute error | p99.9 | Max | Linear RGB max absolute error |
|---|---:|---:|---:|---:|
| book, 2240 rows x 1680 columns | 0.0105789 | 1 | 1 | 0.000264764 |
| tile, 4096x4096 | 0.0012506 | 1 | 1 | 0.000118852 |

## Memory and phone follow-up

| Memory, decimal MB | Java | Native |
|---|---:|---:|
| Sampled Java heap peak | 286.30 | 252.21 |
| JVM heap cap | 293.60 | 293.60 |
| Source Java primitive payload | 204.68 | 77.38 |
| Persistent native reconstruction/chroma workspace | 0 | 119.84 |
| Combined source primitive payload | 204.68 | 197.22 |
| Caller rings | 94.00 | 94.00 |
| Borrowed input mapping | 249.70 | 249.70 |

The writer still has two reusable tile arenas (about 50 MB). Native median
stacks add at most about 0.20 MB across eight workers. These primitive bounds
exclude JVM metadata, thread stacks, codec temporaries and unused committed
heap. Native workspace size depends on width and the fixed band height,
not frame height.

Supplemental Windows process-memory runs measured peak working set
695.17 MB (Java) versus 780.40 MB (native), and peak sampled private commit
528.38 versus 600.27 MB. Native live arenas are now outside the JVM heap, and
unused/garbage heap storage can remain committed: the lower source payload
does not guarantee lower process RSS. The Java heap stays below 300 MB and
native workspace is bounded, but total phone RSS still needs measurement.

A cautious phone expectation is roughly 6-12 seconds saved, or about 22-28
seconds total from the current 34.5 seconds. This is an estimate: ART's
relative slowness suggests more benefit than C2 Java on the desktop, but ARM
memory bandwidth, SIMD behavior, thermal state and encoder contention still
need a real release-build measurement.

Install `app/build/outputs/apk/release/app-release.apk`. Look for
`native median=true directional=true chroma=true` in `Lumdir work` and for
the guide/update/bilateral/RGB-fit components of `dir [...]` and `chroma` to
shrink. Calibration/noise statistics remain Java. The native median should
stay near the existing ~7 seconds rather than disappearing. `native-work`
should be about 114 MiB, `arrays` about 74 MiB, and `rings` about 90 MiB.
Use the complete `Save wall` total to assess the phone improvement; encoder
worker seconds continue to overlap and must not be summed into it.
