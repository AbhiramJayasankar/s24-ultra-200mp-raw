Directional16 full-frame optimization, 2026-10-27

The supplied 16320 x 12240 frame is reconstructed with **identical integer DN**
over all 199,756,800 samples. The 2x phone target is **not yet verified**.
Host measurements show an improvement, but do not reach 2x. The connected S24
Ultra needs permission for temporary benchmark files outside the worktree before
its ART performance can be measured.

Measurements use Android Studio JBR 21.0.7, eight workers and `-Xmx600m`.
The input is mapped directly from
`bench/data/full_16320x12240_rs20400.raw10`;
no input copy was made in this repository. Levels are 64/1023 and neutral is
`{0.48242f, 1, 0.59277f}`. The constructor is included in wall time.

The first run, before any Directional16 edits, took **10.058 s** while writing
the full baseline uint16 output for subsequent comparisons. Its stage split was
`[1.0095798, 0.8399702, 2.4685290, 2.6566893, 2.2035347]` seconds, checksum
`1882ebb4c6b6463c`, peak payload **446,793,540 bytes**. Output-file writing is
included in this wall time, so it should not be used to claim a speedup over a
run without that write.

Comparable fresh-JVM runs, both using `--compare` against that saved output:

| Measurement | Original | Optimized |
|---|---:|---:|
| Wall time, seconds | 8.281 | 5.718 |
| Statistics | 0.624 | 0.580 |
| Initial guide | 0.749 | 0.539 |
| Guide regression/update | 1.974 | 1.303 |
| Bilateral | 2.536 | 1.449 |
| Output regression/packing | 1.778 | 1.177 |
| Peak `peakArrayBytes()` | 446,793,540 | 406,990,372 |
| Cached output bytes | 249,696,000 | 249,696,000 |
| Bands computed | 32 | 32 |
| Checksum | `1882ebb4c6b6463c` | `1882ebb4c6b6463c` |

This pair is **1.45x faster**. Other optimized comparison runs took
5.399-5.934 seconds. Wall time includes checksum and comparison overhead;
`stageSeconds()` measures reconstruction stages. Peak payload includes the
packed direct output cache, as in the original accounting, and excludes the
borrowed input mapping. The working arrays and worker rings are released when
the full frame is cached. The band size stays 384 and working halo stays 32.

Full-frame comparison: **max 0 DN; mean 0 DN; changed 0 / 199,756,800**.
The checksum is FNV-1a over integer sample values in row order, with 64-bit wrap.

Changes:

- Cache the exact balanced float value for each of 64 native phases and 1024
  input codes. Decode four RAW10 samples together, unpack the band once, and
  reuse it for coarse block means. Noise statistics use 384-row coarse chunks.
- Precompute the repeating interpolation coordinates/weights and reuse the
  green interpolation for native green. Preserve the original mixed float and
  double multiplication order.
- Fuse the colour fits. Each native row needs green plus one other colour, so
  eight moment-ring channels serve all three vertical moment sums. Factor
  counts into native block populations and precompute count reciprocals.
  Stream coefficients through local rings, emit only needed estimates, and
  combine the guide update with its fit. This removes the full coefficient
  arrays and the barriers between colour passes.
- Stream the bilateral through stripe-local noise, reference, variance and
  filtered-row rings. Reuse variance inverses between passes, unroll constant
  taps, and precompute border indices. Keep the original exponent table and all
  seven taps in both passes.
- Use three dynamically claimed row chunks per original horizontal stripe.
  This keeps the original horizontal accumulation order and lets faster cores
  take more work. Coarse demosaicing, unpacking and packing also use smaller
  dynamically claimed chunks. There are still at most eight worker threads.
- Reduce full-band float arrays from six to three. No per-pixel allocation,
  DngWriter changes or Python changes.

All **15 tests passed, zero skipped**, including the golden, tiny-band
equivalence and concurrent random row/sample tests. Golden error is mean
**0.24748 DN**, p99.9 **0.53372 DN**, max **1.56134 DN**. The tests and their
tolerances were not changed.

Run from the repository root:

```powershell
./gradlew.bat testDebugUnitTest
./gradlew.bat :app:directional16Bench
./gradlew.bat :app:directional16Bench --args="--write build/output.u16le"
./gradlew.bat :app:directional16Bench --args="--compare build/directional16-baseline.u16le"
./gradlew.bat :app:directional16Bench --args="--profile"
```

The baseline reference and original compiled classes are retained under ignored
`build/`. The benchmark also accepts `--input` for an alternate location of the
same full-frame RAW10 geometry. The profiler samples runnable worker stacks;
do not compare profiling runs with ordinary timing runs.

In this restricted Windows session, the SDK/JDK JAR close operation hits an
access-denied canonical-path lookup. Gradle verification therefore uses an
ignored compiler launcher and JDK shim under `build/`, leaving installed files
unchanged:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
$env:GRADLE_USER_HOME="$PWD/build/gradle-home"
$env:GRADLE_RO_DEP_CACHE='<gradle-user-home>/caches'
./gradlew.bat testDebugUnitTest directional16Bench --offline --no-daemon `
  -I build/fork-compiler.init.gradle `
  --args="--compare build/directional16-baseline.u16le"
```

Prepared Android DEX artifacts are `build/phone-baseline.jar` and
`build/phone-final.jar`. Neither they nor the input have been copied to the
phone. No files outside this worktree were intentionally modified, and no
commit was made.
