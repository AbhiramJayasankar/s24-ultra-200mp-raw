# Lossless JPEG XL tile tuning

## Decision (0.4.1): the app stays on effort 1

Measured on the phone (S24 Ultra), every decoded sample verified identical.
Tiles: 240 tiles of the keeper frame, requantized to the 0.4.0 curve (0.5 sigma).

| Setting | Size vs effort 1 | X4 encode ms/MP |
|---|---:|---:|
| effort 1 (fast lossless, app) | 1.00 | 10 |
| effort 2, palette off | 0.92 | 65 |
| effort 2 | 0.77 | 203 |
| effort 7 tuned (below) | 0.69 | 344 |

Whole-app A/B, cool start, alternating builds, same scene: effort 1 saved 222 MB in
26.3 / 27.4 s; effort 2 saved 176 / 178 MB in 47.7 / 42.2 s (process CPU 131-152 s
vs 247-258 s). The encoder runs beside the reconstruction, but the phone has no spare
power for 2x the CPU, so effort 2 costs ~16-21 s per shot for 20% smaller files.
Effort 1 stays. Tighter compression would have to run after the shot (for example
re-encoding in the background while charging); the effort 7 profile below is the
candidate for that. libjxl 0.12.0 gives the same effort-1/2 sizes as 0.11.1.

The host study below assumed effort 2 as the baseline; its "recommended"
profile was not adopted in the app.

Worktree: `63a10a5` (v0.3.3). libjxl API version 11001 (0.11.1), source
checkout `../libjxl`, source revision `794a5dc`. No source modifications to
libjxl. Measurements use pixels, not RGB channel samples; MB means 1,000,000
bytes. Host: Ryzen 9 8945HS, Windows x64, MSVC 19.44, Release `/O2`.

## Final full-frame results

All rows encode and verify the entire padded frame. DNG MB adds the original metadata allowance to the measured bare tile payloads. Encode and size ratios use the full-frame effort-2 row.

| Tile / settings | DNG MB | Encode ms/MP | Decode ms/MP | Encode x | Size x |
|---|---:|---:|---:|---:|---:|
| 256 / effort 2 baseline | 244.71 | 263.3 | 36.4 | 1.00 | 1.000 |
| 256 / recommended (1% learning) | 194.37 | 394.8 | 156.5 | 1.50 | 0.794 |
| 256 / max (20% learning) | 189.49 | 668.5 | 182.2 | 2.54 | 0.774 |
| 512 / best tested max (20% learning, group 512) | 185.69 | 686.4 | 193.3 | 2.61 | 0.759 |

All tuned/max rows share RCT 40, predictor 5, two previous channels, palettes/responsive/lossy palette/patches off, distance 0 and 16-bit codestreams. The max-256 row has group setting 1; max-512 has group setting 2. Only learning and group/tile size differ from the recommended row.

The best tested maximum-compression setting is **512-pixel DNG tiles, effort 7, gradient predictor, RCT 40, prev=2, 20% learning, 512-pixel modular groups, palettes/patches/responsive/lossy palette off**. Its full-frame estimate is **185.69 MB** at **2.61x** effort-2 encode time. Applying the supplied phone calibration gives **169..196 ms/MP**, approximately **34.1..39.4 encoder CPU seconds** per frame. The estimated wall increase over effort 2 is about **2.1..4.9 seconds**. This is a benchmark option; the app retains 256 tiles and 1% learning.

Compared with the original stored effort-1 file (293.66 MB), the recommended profile saves about 99.29 MB. The final full-frame results are not identical to the short-screen estimates, especially for 512 tiles; use these full-frame values.

Raw measurements: [broad sweep](jxl-sweep-host.csv), [combinations](jxl-refine-host.csv), [fast decode](jxl-fastdecode-host.csv), [fine search](jxl-fine-host.csv), [patch-off budgets](jxl-budget-host.csv), [256 finalists](jxl-candidates256-host.csv), [512 finalists](jxl-candidates512-host.csv), [512 learning boundary](jxl-boundary512-host.csv), [three-repeat production sample](jxl-final-repeat256-host.csv), [full 256 frame](jxl-full256-host.csv), [full 512 frame](jxl-full512-host.csv).


## Input and invariants

The supplied read-only `keeper.dng` is 293,658,826 bytes. Its raw SubIFD is
16320 x 12240 RGB LinearRaw, 3072 tiles of 256 x 256, compression 52546,
BitsPerSample 16. Original tile payloads total 293,483,323 bytes; the remaining
175,503 bytes are metadata, thumbnail, index and padding.

**The file's JPEGXLEffort tag is 1, not 2.** The checked-out writer also selected
effort 1. All decoded samples in this file are in **0..294**, despite the allowed
12-bit code range 0..4095. This is a dark, single-scene workload. Treat results
as measurements of this file, not a universal estimate for daylight captures.
The requested comparison baseline below is a fresh effort-2 encode of these
same decoded samples. Earlier imagecodecs size estimates in the task are not
substituted for measurements of this exact configuration.

Every benchmark frame has distance 0, FrameLossless true, modular 1, original
profile true, linear sRGB metadata, **16 codestream bits**, UINT16 input, and
`JXL_BIT_DEPTH_FROM_CODESTREAM`. Responsive and lossy palette are explicitly
off. No parallel runner is installed. Each tile gets a new encoder and decoder.
The benchmark removes BMFF framing before measuring payload size and decoding,
matching JNI's bare `jxlc` payload. It checks decoded dimensions, RGB channels,
original profile and 16-bit depth, and compares **every padded decoded sample**
to the input. A discrepancy exits with failure.

## Sampling and measurement

`extract_jxl_tiles.py` reads the SubIFD using tifffile and decodes individual
tiles using imagecodecs. It never writes the DNG. All derived raw data and
binaries are in ignored `build/jxl-tuning/`.

The 256 sample selects one seeded random tile in each of 20 x 12 spatial
strata: 240 tiles, 15.72864 MP, **94,371,888 bytes** including its header.
The 512 sample uses 10 x 6 strata: 60 tiles, the same pixel and byte counts.
It assembles adjacent original tiles and replicates the last valid row/column
at image edges. Full inputs contain 3072 / 768 tiles respectively; their padded
pixel counts are both 201.326592 MP (valid image 199.7568 MP).
Manifests alongside the inputs record coordinates, mean, standard deviation,
horizontal absolute difference and code extrema. The 256 sample covers tile
means 3.92..287.67 and spatial differences 3.24..9.81 codes/pixel.

Input file SHA-256:

- `tiles256.raw`: `c2daa85113c21ab7c0fbcfee2b2e935e551e13cd92c5b8d516167b875e1afa77`
- `tiles512.raw`: `9e783918f2dce37d297202d0359251f8eddd1f72d578671c31007fdeec0e8f67`

File I/O and equality comparisons are outside the timers. Encoding includes
encoder setup, color/bit-depth configuration, input ingestion, output allocation
and destruction. Decoding includes decoder setup, output allocation and
destruction. Each setting has an untimed encode/decode of the first actual tile
to warm dispatch/allocators. No other builds run during final timing.
CSV timings are aggregate elapsed time divided by padded megapixels and repeat
count. These are host codec timings, not Lightroom or Snapdragon timings.

Extrapolated DNG MB = (mean bare codestream bytes per tile x full tile count +
175503) / 1e6. For full inputs the payload total is measured exactly; the small
metadata allowance remains the original file's. A 512-tile DNG would have a
slightly smaller tile index, a difference of about 18 KB.

`jxl-sweep.csv` is generated by `make_jxl_sweep.py`; -1 means leave the upstream
default untouched. It covers 138 settings: efforts 1/2/3/4/5/7; every RCT 0..41;
predictors 1/3/4/5/6/13/14/15 at efforts 2..5; learning 0/0.5/2/5/10/20 percent;
previous-channel counts 0..3; palette disabling; group sizes 128..1024; and
decode-speed settings 1/2. The result CSV retains every explicit option.

Effort 2 uses libjxl's predefined gradient tree; effort 3 uses its predefined
weighted tree. Predictor/learning overrides have no useful effect on these
trees. Adaptive learning starts at effort 4. This is visible both in
`lib/jxl/enc_modular.cc` and in identical byte counts in the sweep. Two previous
channels (the meaningful maximum for RGB here) and fixed RCT 40 were the largest
compression improvement. Disabling palettes alone is very fast at effort 2 but
increases size substantially. Combining palette disabling with learned trees
and previous-channel properties recovers much more than that size loss. Patch
search adds encode cost without saving bytes on the tested tuned configurations.

After the 240-tile broad sweep, `make_jxl_refine.py` creates 202 joint trials and
50 decode/palette trials. `make_jxl_fine.py` creates 89 learning/predictor trials
and 55 trials with patch search off. These exploratory screens use 32 evenly
spaced tiles from the 240-tile sample (12.6 MB), **not** a substitute for the
larger sample. `select_jxl_candidates.py` deduplicates settings and selects
17 finalists for all 240 tiles. `--tile512` selects seven representative
settings and tests each with 256- and 512-pixel modular groups on 60 DNG tiles.
The 512 DNG tile size and the modular group size are distinct settings.

Finalists are checked on the full decoded frame, and production settings are
also measured across three repetitions of the large sample. Broad screens are
single exploratory runs; use the final repeated/full-frame numbers for budget
decisions. Some exploratory rows overlap short build/test activity, so small
timing differences in those rows are not significant. Final timed runs have no
concurrent builds/tests. Every run, including exploratory trials, checks exact
sample equality. No timing includes file I/O.

## Reproduction

From the worktree root in PowerShell:

```powershell
./bench/build-jxl-bench.ps1
$keeper = 'path/to/photo.dng'
python bench/extract_jxl_tiles.py $keeper build/jxl-tuning/tiles256.raw
python bench/extract_jxl_tiles.py $keeper build/jxl-tuning/tiles512.raw --size 512
python bench/extract_jxl_tiles.py $keeper build/jxl-tuning/full256.raw --full
python bench/extract_jxl_tiles.py $keeper build/jxl-tuning/full512.raw --size 512 --full
python bench/make_jxl_sweep.py
./build/jxl-tuning/host/Release/jxl_tile_bench.exe build/jxl-tuning/tiles256.raw bench/jxl-sweep.csv 1 | Out-File -Encoding ascii build/jxl-tuning/sweep256.csv
python bench/rank_jxl_results.py build/jxl-tuning/sweep256.csv --budget 1.5
```

The binary input format is documented in `extract_jxl_tiles.py`. The optional
fourth benchmark argument limits tile count for quick smoke checks. No raw data
or executables are tracked. Python needs numpy, tifffile and imagecodecs.
The host and Android benchmark reuse the app CMake project for static libjxl,
Highway, Brotli and skcms. Android uses NDK 30.0.16248370, API 34, arm64-v8a,
Release `-O3 -mtune=cortex-x4`, section garbage collection, supported NEON paths
and static libc++; SVE/RVV are disabled just as in the app. The stripped
4.4 MB executable is an AArch64 PIE and dynamically needs only Android's
liblog/libm/libdl/libc, so no companion codec/runtime libraries need pushing.

## Recommendation and interpretation

The app selects the explicit **LinearRaw** profile: effort **7**, decoding
speed **0**, modular predictor **5** (gradient), RCT **40**, MA learning **1%**,
previous channels **2**, modular group size **1** (256), global/group channel
palette percentages **0**, palette colors **0**, lossy palette **0**, responsive
**0**, patches **0**. The DNG tile stays **256 x 256**. This is a tuned effort-7
configuration, not upstream's much slower default effort 7.

Full-frame re-encoding estimates **194.37 MB**, versus **244.71 MB** for effort 2:
**50.34 MB / 20.57% smaller**. It is approximately **1.50x** the effort-2 encode
time in both the three-repeat sample and the full-frame run. Decode CPU is
approximately **4.3x** baseline in the full-frame run. File size is the priority
for this default; this decode tradeoff is explicit. Lightroom itself was not
timed. Keep the fast-decode alternative below if baseline-like preview CPU is
more important than the extra compression.

There is a small size improvement near the 1.5x budget with 512-pixel DNG tiles
and 512-pixel groups, but it doubles the two Java tile arenas from **50.3 to
100.7 MB**, increases per-worker native memory and delays band handoff. At the
near-budget point tested, the sample saving is less than 1 MB and decode cost
also rises. Keep 256 for the phone default. Larger tiles remain a separate
maximum-compression option in the benchmark; no writer memory behavior changes.

Using the supplied effort-2 phone calibration of 65..75 ms/MP, a 1.50x profile
projects **98..112 ms/MP**, or **19.6..22.6 encoder core-seconds** per padded
frame. That is about **6.5..7.5 extra CPU seconds** versus effort 2, approximately
**0.7..1.5 extra wall seconds** under the task's thermal/overlap rule. These are
estimates from host ratios, not device measurements or a guaranteed save time.
The checked-out factory and input file used effort 1; the CPU comparison above
is specifically against the requested effort-2 baseline. The phone commands
below measure both baselines and repeat effort 2 at the end to expose drift.

## Phone benchmark (commands to run yourself)

No adb command was executed during this work. The sample is 94.4 MB; the
executable and one input together are under 100 MB. Run from the worktree root
in PowerShell with the phone unlocked and cool:

```powershell
$adb = 'adb'
& $adb shell mkdir -p /data/local/tmp/s24jxl-tuning
& $adb push build/jxl-tuning/android/jxl_tile_bench /data/local/tmp/s24jxl-tuning/jxl_tile_bench
& $adb push build/jxl-tuning/tiles256.raw /data/local/tmp/s24jxl-tuning/tiles.raw
& $adb push bench/jxl-phone.csv /data/local/tmp/s24jxl-tuning/settings.csv
& $adb shell "cd /data/local/tmp/s24jxl-tuning && chmod 755 jxl_tile_bench && taskset 80 ./jxl_tile_bench tiles.raw settings.csv 3 > phone256.csv"
& $adb pull /data/local/tmp/s24jxl-tuning/phone256.csv build/jxl-tuning/phone256.csv
```

`taskset 80` pins the single codec thread to CPU 7 (the expected X4 on this
phone). Omit it if testing OS scheduling instead; record which mode was used.
Each row checks every decoded code and 16-bit metadata, with I/O outside timers.
The `app` row calls exactly the same C++ settings helper as JNI. Other rows
include upstream efforts 1/2, the 256 maximum-compression option, a fast-decode
option, and a second effort-2 measurement. Host and ARM output sizes may differ
slightly because MA heuristics use floating point; identity is checked locally.
For a quick smoke run use `... settings.csv 1 8` (one repeat, first eight tiles).
For useful timing keep the full input and three repeats.

After allowing the phone to cool, replace the input for the 512 experiment:

```powershell
& $adb push build/jxl-tuning/tiles512.raw /data/local/tmp/s24jxl-tuning/tiles.raw
& $adb push bench/jxl-phone512.csv /data/local/tmp/s24jxl-tuning/settings.csv
& $adb shell "cd /data/local/tmp/s24jxl-tuning && taskset 80 ./jxl_tile_bench tiles.raw settings.csv 3 > phone512.csv"
& $adb pull /data/local/tmp/s24jxl-tuning/phone512.csv build/jxl-tuning/phone512.csv
```

This overwrites the temporary raw input so only one 94.4 MB sample is stored on
the phone. The 512 benchmark uses bounded per-tile memory; it does not model
the app's larger tile arenas or eight simultaneous encoder workers.

## Validation commands

The original three-argument Java API keeps upstream effort defaults. Only the
named `forLinearRaw()` factory applies the tuned options, so `JxlSelfTest`
continues to exercise its original settings. Both factory settings and malformed
input handling are covered by desktop unit tests. The native profile requires
lossless 16-bit input and matching effort/decode-speed metadata.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25'
$env:GRADLE_USER_HOME = '<gradle-user-home>'
./gradlew.bat --offline --no-daemon assembleRelease testDebugUnitTest
& "$env:JAVA_HOME/bin/javac.exe" -d build/jxl-host-classes app/src/main/java/dev/s24cam/dng/TileEncoder.java app/src/main/java/dev/s24cam/dng/JxlNative.java app/src/main/java/dev/s24cam/dng/JxlTileEncoder.java app/src/main/java/dev/s24cam/dng/JxlSelfTest.java tools/jxl-host/JxlHostCheck.java
& "$env:JAVA_HOME/bin/java.exe" --enable-native-access=ALL-UNNAMED '-Djava.library.path=build/jxl-tuning/host/app-native/Release' -cp build/jxl-host-classes JxlHostCheck build/jxl-tuning/jni-results build/jxl-tuning/tiles256.raw
python tools/jxl-host/verify_jxl.py build/jxl-tuning/jni-results build/jxl-tuning/host/Release/jxl_tile_bench.exe
```

`local.properties` is unchanged. No commit was made. The Android executable was
cross-built and its ELF dependencies inspected; ARM execution and Lightroom
performance require device/application testing.

## Broad-sweep table

All rows below use 240 tiles of 256 x 256. MB is extrapolated. Ratios use the effort-2 row in this same sweep; final repeated/full-frame timings follow separately.

| Settings (-1/default otherwise) | DNG MB | Encode ms/MP | Decode ms/MP | Encode x | Size x |
|---|---:|---:|---:|---:|---:|
| E1 | 293.51 | 29.4 | 36.9 | 0.12 | 1.199 |
| E2 | 244.86 | 251.7 | 33.2 | 1.00 | 1.000 |
| E3 | 239.86 | 286.3 | 71.7 | 1.14 | 0.980 |
| E4 | 233.16 | 487.4 | 91.0 | 1.94 | 0.952 |
| E5 | 223.53 | 710.1 | 104.1 | 2.82 | 0.913 |
| E7 | 219.29 | 1214.7 | 115.8 | 4.83 | 0.896 |
| E2, RCT 40 | 242.76 | 234.8 | 30.6 | 0.93 | 0.991 |
| E4, prev=2 | 217.18 | 529.4 | 107.4 | 2.10 | 0.887 |
| E5, prev=2 | 207.12 | 757.6 | 112.8 | 3.01 | 0.846 |
| E2, all palettes off | 278.95 | 52.4 | 32.7 | 0.21 | 1.139 |
| E4, decode speed 2 | 241.58 | 324.9 | 29.8 | 1.29 | 0.987 |
| E5, decode speed 2 | 234.39 | 381.2 | 30.7 | 1.51 | 0.957 |

The most useful settings are combinations, rather than these one-variable changes. The large-sample tuned and faster-decode alternatives are recorded in [256 finalists](jxl-candidates256-host.csv), [512 finalists](jxl-candidates512-host.csv), and [fixed-RCT decode-speed trials](jxl-fast-final256-host.csv).

A fast-decode alternative is effort 6, RCT 17, MA learning 2%, prev=0, group=1, global=95%, local=80%, palette=1024, decode speed=2, responsive/lossy palette off (patches default). On 240 tiles it estimates **232.90 MB**, **333.0 encode / 28.0 decode ms/MP**. That is 1.39x the repeated effort-2 encode baseline (239.6 ms/MP), with baseline-like decode CPU. It gives up much of the camera-profile size reduction.

## Completed validation

- `assembleRelease testDebugUnitTest` passed offline: 33 passed, four existing fixture-dependent skips, zero failures/errors. Release APK: `app/build/outputs/apk/release/app-release.apk`.
- All four full-frame configurations passed exact decoded sample comparisons and 16-bit BasicInfo checks (including padding).
- The JNI host harness ran `JxlSelfTest` unchanged, plus nominal 1/9/10/11/12/16-bit camera-profile inputs in grayscale/RGB and a real DNG tile. Independent imagecodecs checks passed **109 exact lossless round trips and five lossy API decodes**, all bare codestreams. The benchmark independently checked the 13 tuned JNI outputs for **16-bit codestream depth** and exact values.
- Both generic and tuned encoders passed 16 concurrent calls each through shared immutable Java encoder objects; inputs remained unchanged.
- Android arm64 executable built successfully; static codec/runtime linking and AArch64 PIE dependencies verified. It was not run on the phone.
- `git diff --check` passed; libjxl checkout remains clean; `local.properties` retains the supplied SDK path; no commit was made.
