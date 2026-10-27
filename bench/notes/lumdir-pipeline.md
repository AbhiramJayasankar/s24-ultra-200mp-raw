# Band overlap and CPU accounting (v0.3.2 follow-up)

Baseline is main `8f8e896` (v0.3.2). Changes are uncommitted.

## Scheduling

- One bounded colour-producer future reconstructs the next needed 192-row
  colour band while the current 128-row output band runs relative luminance,
  phase medians and code conversion. It shares the existing Directional native
  workspace; there is no second RGB reconstruction context or output arena.
- Current luminance is copied into the existing Java band array before the
  producer starts. Current ratio rows remain reserved in the 576-row rings
  until code conversion reads them. A modulo-ring collision check suppresses
  prefetch if a candidate would overwrite those rows. This also protects large
  output bands and reverse/random row requests.
- Native Gaussian rows use the Directional pool. Sharing the median pool would
  queue the Gaussian behind an entire median band and unnecessarily stall the
  colour producer. Mean and Gaussian still retain their original rounding.
- Native update fits, bilateral and RGB fits use dependency-ready stripes
  instead of two whole-band barriers. A task may advance when both row parts
  of its own and adjacent columns have completed the preceding stage. Every
  column is at least 16 pixels wide; the widest read halo is 12 pixels. These
  dependencies protect both input halos and the old arenas overwritten by the
  following stage.
- Fit stripe boundaries, row parts, reflection, coefficient rings and floating
  point accumulation order remain unchanged. Per-task input/output pointers
  replace global buffer swaps while stages overlap. The original serial native
  path and Java fallback remain available.
- Four independent calibration phases run concurrently, each retaining its
  original reduction order and writing 16 disjoint gain/offset entries.
  Progress callbacks stay on the caller thread. Close drains a live producer
  before native arenas or borrowed shared memory can be released.

Default concurrency remains eight Directional, eight Java phase and eight
encoder workers (on a device with at least eight CPUs). The only extra thread
is the colour orchestrator, which mostly waits for its worker pool. There are
no idle-pool timeout waits.

## Measurements

Fresh JBR 21.0.7 JVMs, `-Xmx280m`, host DLL built with
`tools/build-lumdir-host.cmd`. The original and modified versions were run
alternately three times on this 16-logical-CPU desktop. The baseline used
original Java classes and an original native DLL compiled from HEAD's C++
sources in a separate ignored build folder. Only CPU reporting was added to
the baseline main. Android JXL cannot load on the host, so both use the app's
factory fallback, LJ92. The original full RAW10 file is mapped in place.

| Metric | v0.3.2 | Modified |
|---|---:|---:|
| Wall seconds, three runs | 11.842, 11.508, 11.729 | 9.809, 9.798, 9.758 |
| **Median wall seconds** | **11.729** | **9.798** |
| Mean process CPU seconds | 94.11 | 108.27 |
| Mean busy logical cores | 8.05 | 11.06 |
| Mean utilisation of 16 logical CPUs | 50.3% | 69.1% |
| Sampled heap peak across these runs, decimal MB | 254.02 | 270.86 |
| Source Java primitive payload, MB | 77.38 | 77.38 |
| Native reconstruction/chroma payload, MB | 119.84 | 119.84 |
| Caller rings, MB | 94.00 | 94.00 |
| Borrowed input mapping, MB | 249.70 | 249.70 |

Wall time drops about 16.5% (1.20x). CPU seconds increase about 15% while using
previously idle logical CPUs. This is a throughput tradeoff, not an energy
reduction. Phone results still need measurement; the desktop has SMT and the
phone has eight heterogeneous cores.

All runs produced a 363,217,896-byte LJ92 DNG. Full-frame decoded comparison
of original versus final output: **599,270,400 samples, zero changed, mean 0,
p99.9 0, max 0**. Padding is excluded by the existing comparison script.

Worker-count trials (before the final Gaussian-pool refinement) showed that
reducing compute/Directional pools did not help this desktop: 8/8/2 took
9.96 s, 6/6/2 11.38 s, 4/6/2 11.69 s, 4/4/2 12.94 s and 8/4/2 11.54 s.
With dependency scheduling enabled, 2/4/8 encoders measured 9.70/10.11/9.58 s;
the encoder-count differences were small and noisy. Eight remains the default.
No claim is made that this tuning proves the best setting for the phone.

The writer's two tile arenas (about 50 MB) are unchanged. No frame/band storage
is added by the pipeline; queues and task metadata are bounded. Parallel
calibration briefly uses four sampled workspaces during setup, before colour
and encoder work starts. The heap cap is 293.60 decimal MB, below 300 MB.
Every observed full-frame run stayed below it. Native median stacks are unchanged.

## Accuracy and tests

Regenerated both references with `python bench/make_lumdir_golden.py` from the
same requantized RAW10 values used by Java. Python golden errors remain:

| Reference | Mean code error | p99.9 | Max | Linear maximum error |
|---|---:|---:|---:|---:|
| book | 0.0105789 | 1 | 1 | 0.000264764 |
| 4096 tile | 0.0012506 | 1 | 1 | 0.000118852 |

Standalone JUnit 4.13.2: **36 tests pass with the host DLL and 36 pass with
native loading disabled**. The new test compares serial versus pipelined
output with 1/2/3/6/8 worker configurations, 128/256-row output bands, multiple
ring wraps and reverse reads. It also checks caller-thread progress delivery
and early/idempotent close. Existing native RGB identity, Python goldens,
small-band equivalence, Bayer regressions, LJ92 round trips and DNG tests pass.

Android Java compiles against API 36. Both arm64 libraries compile in Release
with the installed NDK/CMake and the existing strict floating-point flags.
A release APK was built with SDK javac/D8/aapt2, stripped, 16-KiB aligned and
signed with the existing debug key. APK signature verification passes:
`build/manual-android/app-release.apk` (3.73 MB). This is an SDK-built artifact,
not a claim that Gradle completed.

**Remaining validation gate:** `gradlew.bat assembleRelease testDebugUnitTest`
cannot start because the cached Gradle 9.8.2 distribution directory is empty
and this session has no reachable network to restore it. A local installation
or ZIP is needed to run that exact command. Gradle dependency caches and the
SDK are present. `local.properties` was never changed and still contains
`sdk.dir=<path-to-android-sdk>`.

## Reproduce when Gradle is available

```powershell
python bench/make_lumdir_golden.py
.\tools\build-lumdir-host.cmd
.\gradlew.bat assembleRelease testDebugUnitTest
.\gradlew.bat testDebugUnitTest -PlumdirNativeTests
.\gradlew.bat lumdir16Bench --args="--native build/lumdir-after.dng"
.\gradlew.bat lumdir16Bench --args="--native --serial --barriers --serial-setup build/lumdir-serial.dng"
python bench/compare_linear_dng_codes.py build/lumdir-serial.dng build/lumdir-after.dng
```

The serial switches provide an in-worktree scheduler comparison while keeping
the arithmetic. Exact original-release timings above used the separately
compiled original classes/DLL. Additional switches are `--compute=N`,
`--directional=N` and `--encoders=N`; Directional worker-count changes preserve
the original stripe geometry. `--java` retains the previous meaning of forcing
Java Directional/chroma while retaining the established native median.

`Lumdir16Main` reports process CPU time from `ProcessHandle.Info`, busy logical
cores (`CPU seconds / wall seconds`) and utilisation divided by the machine's
logical-CPU count. Process CPU includes Java, native and encoder worker threads.

## Phone log

Look for:

```text
Lumdir scheduling: pipeline=true stripe-DAG=true compute=8 directional=8 encoders=8; producer work ... (overlaps), wait ..., jobs 63
Save wall s: ... = total ...
Save CPU: process ... s / wall ... s = ... busy cores (...% top); utilisation ...% of 8 cores
```

`Save CPU` uses `android.os.Process.getElapsedCpuTime()` over the complete save,
including setup, writer, close, publish and unmapping. It measures the process,
so concurrent camera/UI activity is included, as in `top`. For example, six
busy cores means approximately 600% in top and 75% of an eight-core phone.

Use the complete wall total and CPU utilisation to assess the gain. Producer
work overlaps the Java phase stages, so their elapsed times must not be added.
The last three Directional fields are exclusive intervals ending at update,
bilateral and RGB completion: the latter two are tails, not the full duration
of those kernels. Individual kernel spans can grow under concurrency while
the complete save becomes faster. Buffer logs should remain about 74 MiB
Java arrays, 114 MiB native workspace and 90 MiB rings.
