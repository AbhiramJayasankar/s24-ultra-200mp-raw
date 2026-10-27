# Native JPEG XL tiles

`JxlTileEncoder` implements `TileEncoder` (TIFF Compression 52546). It accepts
interleaved unsigned camera codes in Java `short[]`, with one or three channels
and 1–16 bits per sample. Settings getters are `getDistance()`, `getEffort()`,
and `getDecodingSpeed()`. The DNG writer can record those settings in its tags.
The DNG writer selects `JxlTileEncoder.forLinearRaw()` when native JPEG XL is
available. The three-argument constructor retains libjxl's effort defaults for
callers and `JxlSelfTest`. The named profile changes only reversible lossless
coding options; `usesLinearRawTuning()` distinguishes it from the generic API.

The camera profile uses effort 7, decode speed 0, gradient prediction, RCT 40,
two previous-channel properties, 1% MA tree learning, 256-pixel modular groups,
and disables global/group/color palettes, patch search, responsive mode and
lossy palettes. This is much less work than default effort 7. Both JNI and the
benchmark's `app` row use `jxl_raw_settings.h`; native code rejects inconsistent
Java effort/decode-speed metadata. See [the tuning report](../bench/notes/jxl-tuning.md)
for measured size, encode/decode costs, scene limitations and phone commands.

Distance zero uses `JxlEncoderSetFrameLossless`, modular coding, the original
profile, and linear sRGB metadata. `JXL_BIT_DEPTH_FROM_CODESTREAM` avoids scaling
9–12-bit input to 16 bits. Each call owns an encoder and runs synchronously with
no parallel runner; callers may encode tiles concurrently. Input is released
with `JNI_ABORT` immediately after libjxl consumes it. Samples below 9 bits need
byte packing because libjxl's unscaled integer input requires `UINT8` for them.

Every returned array is a bare codestream. libjxl automatically emits a container
for lossless samples above 12 bits (level 10), even with
`JxlEncoderUseContainer(false)`. In that case JNI returns the single `jxlc`
payload directly from the output buffer, discarding container headers.

## Android build

Install NDK `30.0.16248370`, CMake `4.1.2`, and SDK platform 36. In the ignored
root `local.properties`, set:

```properties
sdk.dir=<path-to-android-sdk>
# Optional; defaults to the third_party/libjxl submodule.
libjxl.dir=third_party/libjxl
```

The libjxl 0.11.1 tree must include its submodules. CMake uses it in place via
`add_subdirectory` with a separate binary directory; dependency provisioning is
disabled. Nothing is generated in the source checkout.

```powershell
.\gradlew.bat --offline assembleRelease testDebugUnitTest
```

The APK is `app/build/outputs/apk/release/app-release.apk`. The only packaged
native library is `lib/arm64-v8a/libs24jxl.so`. Both libjxl and the C++ runtime
are linked statically. Release uses CMake `Release`, `-O3`, function/data sections,
linker garbage collection, hidden static-library symbols, and an export map
containing only the JNI entry point. It links `jxl`, Highway, Brotli, and skcms;
there is no `jxl_threads` runner. Other upstream targets are excluded from the
default build, and Gradle requests only `s24jxl`.

Tools, tests, fuzzers, benchmarks, examples, documentation, manpages, upstream
JNI, jpegli, sjpeg, OpenEXR, external image codecs, JPEG transcoding, box decoding,
viewers, plugins, and tcmalloc are disabled. ARM code uses NEON with
`-mtune=cortex-x4`, retaining the arm64 ABI baseline. libjxl 0.11.1's experimental
SVE/RVV implementation is disabled: upstream notes that it does not pass tests.
It does not require SVE/SVE2 instructions on the phone.

## Device benchmark and independent round trip

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.samsung.android.ruler/dev.s24cam.ProbeActivity --ez jxltest true
adb logcat -s S24CAM:I
adb pull /sdcard/Android/data/com.samsung.android.ruler/files/jxltest build/jxl-device-results
python tools/jxl-host/verify_jxl.py build/jxl-device-results
```

The probe runs on a background thread, logs the summary with `SamsungCam.TAG`,
then finishes. It needs no camera permission for this mode. Other probe modes
keep their existing behavior. Wait for the complete CSV summary in logcat before
pulling files. The directory contains 48 `.raw`/`.jxl` pairs plus `results.txt`:
noise at 9, 10, 11, and 12 bits; a gradient and a scene-like pattern at 12 bits;
each at 256×256 and 512×512, efforts 1, 2, 3, and 7, RGB, distance 0, decode speed 0.
Raw files are tightly packed little-endian uint16. Summary rows identify the
input bit depth, compressed bits/sample, byte counts, encode milliseconds, and
milliseconds per megapixel (pixels, rather than channel samples). Filesystem I/O
is outside the measurement. These are single measurements after a warmup, not
statistical performance claims. The independent verifier requires Python
`imagecodecs` and `numpy` and compares every integer sample exactly.

## Windows host verification

The same CMake project builds the identical `jxl_jni.cc` as `s24jxl.dll` using
MSVC. With CMake on PATH and `JAVA_HOME` pointing to a JDK:

```powershell
cmake -S app/src/main/cpp -B app/build/jxl-host -G "Visual Studio 17 2022" -A x64 "-DLIBJXL_DIR=third_party/libjxl" "-DJAVA_HOME=$env:JAVA_HOME" "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
cmake --build app/build/jxl-host --config Release --target s24jxl --parallel 6
javac -d build/jxl-host-classes app/src/main/java/dev/s24cam/dng/TileEncoder.java app/src/main/java/dev/s24cam/dng/JxlNative.java app/src/main/java/dev/s24cam/dng/JxlTileEncoder.java app/src/main/java/dev/s24cam/dng/JxlSelfTest.java tools/jxl-host/JxlHostCheck.java
java --enable-native-access=ALL-UNNAMED "-Djava.library.path=app/build/jxl-host/Release" -cp build/jxl-host-classes JxlHostCheck build/jxl-host-results
python tools/jxl-host/verify_jxl.py build/jxl-host-results
```

The host harness adds grayscale/RGB, tiny/non-square tiles, unsigned 16-bit
samples, invalid sample ranges, unchanged inputs, 16 concurrent calls through a
shared `JxlTileEncoder`, and nonzero distance with all five decode-speed tiers.
It produces 108 exact lossless round trips and five lossy decode checks, including
the camera profile at 1/9/10/11/12/16-bit input precision in grayscale and RGB.
Pass the tuning sample raw file as a second Java argument to add a real DNG tile
(109 round trips). Pass `jxl_tile_bench.exe` as a second verifier argument to
also assert the camera profile's 16-bit codestream metadata. Host
timings do not predict Snapdragon performance. Device loading, ARM execution,
throughput, and eventual DNG-reader compatibility still need device validation.

## Original native integration verification (historical)

`assembleRelease testDebugUnitTest` passed offline: 17 tests passed and two
existing Directional16 tests skipped because their golden input was absent.
The host checks above passed (96 exact integer round trips, five lossy decodes,
and concurrent JNI calls). APK ZIP inspection found exactly one native entry,
`lib/arm64-v8a/libs24jxl.so`, byte-identical to the stripped build output.

The packaged library is 3,488,136 bytes (3.33 MiB), and the release APK is
5,702,470 bytes (5.44 MiB). The library is AArch64, exports only
`Java_dev_s24cam_dng_JxlNative_encodeNative`, and needs only `liblog`, `libm`,
`libdl`, and `libc` dynamically. APK signature verification and 16 KiB ZIP/ELF
alignment checks passed. The libjxl source checkout remained clean.

The restricted Windows sandbox cannot canonicalize some paths under the owner's
profile. Verification used temporary `subst` mappings X: (this worktree),
Y: (SDK), and Z: (libjxl), copied offline Gradle caches under ignored `build/`,
and an ignored `build/sandbox-paths.gradle` init script that remaps Java compiler
classpath paths to those drives. This changes only build paths. The command was
`X:/gradlew.bat -p X:/ -I X:/build/sandbox-paths.gradle --offline --no-daemon assembleRelease testDebugUnitTest`.
`local.properties` was restored to the supplied SDK path and the default sibling
libjxl location after verification. Normal builds from the owner's account use
the Android build command above.
