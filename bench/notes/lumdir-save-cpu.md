# CPU work reduction from v0.5.0

Worktree: `s24cam-speed`, branch `speed`. Nothing committed. The existing
profileable manifest entry and the original `local.properties` are preserved.

## Changes

* Median: keep the 16,384 byte counts, 256 occupancy words, rank 72,
  serpentine windows and exact-tail rule. Remove the second occupancy hierarchy
  from every add/remove; search adjacent bitmap words when the current word is
  exhausted. There is always an occupied bin in the requested direction.
* Gather original phase floats into bounded worker arrays. Quantization,
  histogram selection, unquantization and exact-tail quickselect now share one
  JNI call. The tail quickselect uses the original floats and the original
  partition algorithm. The histogram range and number of fallbacks are unchanged.
  Hoist reflected phase-column indices out of the row loop in both backends.
* Directional: store moments, coefficients and their rings as channel planes.
  Separate horizontal sliding reductions from vertical moment updates, borrowing
  each stripe's idle bilateral scratch for the row moments. This enables SIMD
  across pixels without changing a pixel's reduction order or allocating arenas.
* Bilateral: describe the disjoint inputs/output with restricted pointers; use
  contiguous interior taps and scalar mirrored edge columns. The compiler can
  vectorize independent pixels while preserving all seven tap additions.
* RGB fit and chroma ratio conversion use independent pixel loops. Ratio
  clamping before truncation preserves Java's result, including NaN -> 819.
  Native code conversion was evaluated but did not show a reliable CPU reduction
  with bounded JNI copies, so RGB code conversion and the noise curve remain in Java.

All native sources retain `-ffp-contract=off -fno-fast-math`. There are no NEON
intrinsics or CPU-specific arithmetic paths. NDK Clang confirms vectorization
across pixels in bilateral (width 4), coefficient computation (width 4 with
mixed double/float lanes), vertical moment updates, final fits, and chroma ratio
conversion (width 8). Horizontal sliding reductions remain sequential. The
compiler remarks are in `build/vectorization.log`.

## Full-frame measurements

| Metric | v0.5.0 | Modified |
|---|---:|---:|
| Mean process CPU seconds, 3 alternating trials | 108.188 | 95.641 |
| Median wall seconds | 9.985 | 8.938 |
| Median phase elapsed ns/count | 20.35 | 12.40 |
| Maximum sampled heap, decimal MB | 254.62 | 254.53 |

CPU work falls **11.6%** (12.547 CPU-seconds); median wall falls **10.5%**.

| Trial | Before CPU / wall s | After CPU / wall s |
|---|---:|---:|
| 1 | 107.641 / 9.985 | 94.469 / 8.842 |
| 2 | 107.688 / 9.723 | 96.875 / 8.970 |
| 3 | 109.234 / 10.159 | 95.578 / 8.938 |

| Stage elapsed median, s | Before | After |
|---|---:|---:|
| Directional (overlapping) | 7.285 | 6.833 |
| Chroma/store (overlapping) | 0.956 | 0.860 |
| Relative/load | 0.670 | 0.677 |
| Phase median | 4.066 | 2.477 |
| RGB codes (unchanged Java loop) | 0.544 | 0.658 |

Logs: `build/final-before-{1,2,3}.log`, `build/final-after-{1,2,3}.log`, `build/full-frame-compare.log`.


Fresh JBR 21.0.7 processes, eight compute/Directional/encoder workers,
`-Xmx280m -XX:MaxDirectMemorySize=1g`, the same 16320x12240 stride-20400 RAW10.
The original classes/DLL were saved before kernel changes in `build/baseline`.
Alternating before/after trials use those original classes/DLL and the current
classes/DLL. Process CPU includes all Java, native and encoder workers.
The host uses the app factory's LJ92 fallback because Android JXL cannot load
on this JVM. Both DNGs contain 293,789,690 bytes.

The stage ledger is elapsed time, including contention, not exclusive CPU work.
Colour production overlaps phase processing, so these stages must not be added
to obtain total wall time. The median ns/count figure includes phase setup,
JNI and scattering, with eight workers; it is not single-thread kernel latency.

The host main retains v0.5.0's neutral `[.5,1,.55]`. The phone probe reads the
neutral from `meta.json` (`raw200.as_shot_neutral`), as it does black/white levels.
Compare old/new on the same platform and metadata, not host versus phone DNGs.

## Correctness and memory

* `lumdir16Bench`: compare all 599,270,400 unpadded codes and the linearization
  tables with `bench/compare_linear_dng_codes.py --exact`: passed, zero changed
  codes, maximum difference zero, and identical tables. Both the alternating
  trials and the final Gradle task output pass.
* Release assembly and all 57 unit tests pass in each Java/native mode, with
  zero failures, errors or skipped tests. Native tests explicitly require the
  host DLL. New tests exercise quantization boundaries, exact upper/lower tails,
  extreme finite values, stripe sizes through 256x32, and 16-pixel-wide images.
* Regenerate both Python goldens: all 10 generated files retain their SHA-256
  hashes. `python -m unittest bench.test_lumdir`: five tests pass.
* Source primitive payload is about 77.57 MB; native persistent arenas remain
  119.84 MB (114.29 MiB), and rings remain 94.00 MB (89.65 MiB). Phase JNI stacks
  are bounded below 1 MiB across eight workers. There are no critical GC pins,
  frame-sized copies across JNI, or extra native band arenas.

The separate final Gradle `lumdir16Bench` measured 95.250 CPU-seconds / 9.133 s
wall, with 273.16 MB sampled heap under the 293.60 MB heap cap. Its strict
comparison is recorded in `build/gradle-full-frame-compare.log`.

The sandbox's Java home and filesystem traversal rules required an isolated
copy at `build/validation-project`, copied Gradle caches and temporary SDK drive
aliases. These are the actual Gradle tasks run there:

```powershell
.\gradlew.bat assembleRelease testDebugUnitTest --offline --no-problems-report
.\gradlew.bat testDebugUnitTest -PlumdirNativeTests --offline --no-problems-report
.\gradlew.bat lumdir16Bench --args="--native W:/build/gradle-host-after.dng" --offline --no-problems-report
```

The original worktree's `local.properties` was never edited. Logs and test XML
are `build/gradle-final-java.log`, `build/gradle-final-native.log`,
`build/gradle-full-frame.log`, `build/gradle-java-results` and
`build/gradle-native-results`. The APKs were signed with the existing user debug
key, certificate SHA-256 `c4d2ea8534d3d6c29c0d014f76489b922bfaa29d869aa9e5391ae27991cf8f62`.
Their JPEG XL libraries are byte-identical. Codec settings, background
compaction and production worker counts are unchanged.

## Phone benchmark

`ProbeActivity --ez lumdirbench true` bypasses camera permissions and opens no
camera. It reads from `getExternalFilesDir(null)` and writes the standard tiled
LinearRaw DNG with the app's `TileEncoder`, default options and worker counts.
RAW/rings use shared memory, as in `DngSaver`. CPU/wall totals include reading
the input, allocating, constructing the source, writing/closing and cleanup.
Stage logs identify overlapping spans and include tail count, backend, worker
counts, memory bounds and output size. `LUMDIRBENCH DONE` indicates completion;
an exception logs `LUMDIRBENCH FAILED`.

Default input is `full_16320x12240_rs20400.raw10`. An optional `--es input NAME`
selects another file in the same directory. Its name must end in
`WIDTHxHEIGHT_rsSTRIDE.raw10`. Metadata is `NAME_WITHOUT_RAW10.json`, falling
back to `meta.json`; either the metadata root or its `raw200` object supplies
`black`, `white`, `as_shot_neutral`, optionally `iso` and `exposure_ns`.
`raw200.shape` describes the extracted crop and is deliberately not used for
full-frame geometry. Output is `NAME.lumdir.dng` and is overwritten each run.

From PowerShell in the worktree, capture the original-kernel result first:

```powershell
$remote = '/sdcard/Android/data/com.samsung.android.ruler/files'
adb install -r build\baseline\app-release.apk
adb shell mkdir -p $remote
adb push bench\data\full_16320x12240_rs20400.raw10 "$remote/full_16320x12240_rs20400.raw10"
adb push bench\data\meta.json "$remote/meta.json"
adb shell am force-stop com.samsung.android.ruler
adb logcat -c
adb shell am start -W -n com.samsung.android.ruler/dev.s24cam.ProbeActivity --ez lumdirbench true --es input full_16320x12240_rs20400.raw10
adb logcat -v time -s S24CAM:I | Tee-Object build\phone-before.log
```

After `LUMDIRBENCH DONE`, press Ctrl+C to stop logcat, then:

```powershell
adb pull "$remote/full_16320x12240_rs20400.raw10.lumdir.dng" build\phone-before.dng
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell am force-stop com.samsung.android.ruler
adb logcat -c
adb shell am start -W -n com.samsung.android.ruler/dev.s24cam.ProbeActivity --ez lumdirbench true --es input full_16320x12240_rs20400.raw10
adb logcat -v time -s S24CAM:I | Tee-Object build\phone-after.log
```

After the second `LUMDIRBENCH DONE`, press Ctrl+C, then:

```powershell
adb pull "$remote/full_16320x12240_rs20400.raw10.lumdir.dng" build\phone-after.dng
python bench\compare_linear_dng_codes.py --exact build\phone-before.dng build\phone-after.dng
```

Expect `changed 0`, `max 0`; `--exact` fails on any changed code or table entry.
The comparison decoder supports both JPEG XL and LJ92.
No adb commands were run in this session.

A cautious phone estimate is 15-30 fewer CPU-seconds, about 3-6 seconds less wall
time at 5.2 busy cores. This is not a measured phone result. ARM SIMD is expected
to help Directional beyond the host's scalar/strict MSVC result; the real phone
test must establish both the speedup and ARM output identity. The original full
fixture has only 15 tail fallbacks on the host, whereas the profiled phone scene
had 473k, so the tail-heavy native tests are an additional correctness gate.

## Independent native kernel check

`python bench/prepare_native_kernel_bench.py` generates
`build/native-kernel-bench.cc`, containing HEAD's and the working tree's kernels
in separate namespaces. It compares all RGB floats and all four output rings
bitwise, and prints exclusive per-kernel times for a deterministic fixture.
The generated benchmark does not include Java glue, median or encoding.

MSVC `/O2 /fp:strict`, single thread, four repeated fixture passes. These
are synthetic exclusive kernel seconds, not full-frame save stage times:

| Kernel | Before s | After s | After / before |
|---|---:|---:|---:|
| momentRow | 0.132303 | 0.133136 | 1.006 |
| coefficientRow | 0.122223 | 0.149477 | 1.223 |
| bilateralRow | 0.130686 | 0.129968 | 0.995 |
| fit | 0.139916 | 0.109945 | 0.786 |
| bilateral | 0.085919 | 0.085437 | 0.994 |
| chromaRows | 0.152479 | 0.143524 | 0.941 |

Combined guide/kernels: 0.845992 -> 0.834116 s, bitwise identical. This is
approximately flat overall on strict MSVC; coefficient calculation is slower
on this host. The ARM compiler vectorizes the new pixel loops. Its actual
per-kernel speedup remains a phone measurement, not an x86 timing inference.


An arm64 executable built with the app's strict flags and static C++ runtime is
already available as `build/lumdir-kernel-bench-arm64`. It has not been run here:

```powershell
adb push build\lumdir-kernel-bench-arm64 /data/local/tmp/lumdir-kernel-bench
adb shell chmod 755 /data/local/tmp/lumdir-kernel-bench
adb shell /data/local/tmp/lumdir-kernel-bench
```

It must finish with `bitwise identical`. This directly checks ARM floating-point
kernel identity; the old/new phone DNG comparison also covers phase medians,
Java glue, noise-curve lookup and encoding.

To rebuild that executable locally:

```powershell
python bench\prepare_native_kernel_bench.py
$ndk = '$ANDROID_HOME/ndk/30.0.16248370/toolchains/llvm/prebuilt/windows-x86_64'
& "$ndk/bin/clang++.exe" --target=aarch64-linux-android34 -no-canonical-prefixes -O3 -ffp-contract=off -fno-fast-math -mtune=cortex-x4 -static-libstdc++ build/native-kernel-bench.cc -o build/lumdir-kernel-bench-arm64
```

## Phone result (S24 Ultra, 2026-10-27)

`lumdirbench` on the full RAW frame, each run from a cool start (thermal status 0,
battery <= 36.0 C). v0.5.0 = versionCodes 23/25 (v0.5.0 code + benchmark), new =
24/26, run in the order base, base, new, new, base, new.

| Run | v0.5.0 wall / CPU s | New wall / CPU s |
|---|---:|---:|
| 1 | 16.56 / 112.7 | 13.12 / 88.0 |
| 2 | 15.43 / 105.1 | 14.29 / 95.0 |
| 3 | 15.82 / 108.3 | 12.89 / 86.2 |
| Mean | 15.94 / 108.7 | 13.43 / 89.7 |

Wall -16%, CPU -17.5% (19 CPU-seconds per frame). Phase median 6.9 -> 4.2 s
(34-36 -> 20-22 ns/count), directional 11.8 -> 9.9 s. The phone DNGs from the
old and new code decode identically: 599,270,400 samples, changed 0, identical
linearization tables (`compare_linear_dng_codes.py --exact`).
