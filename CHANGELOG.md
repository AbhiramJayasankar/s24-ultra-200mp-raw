# Changelog

## 0.6.0 - 2026-10-27

### Added
- Photos screen: tap the thumbnail next to the shutter to see every DNG the app
  has saved, with the total size, free space on the phone, and each photo's date,
  mode, size and compression state. Select photos to delete them (with a
  confirmation showing the space freed); tap a photo to open it in Lightroom or
  another app. Photos being saved or compressed can't be deleted.
- Background compression status on the Photos screen: running (with tile
  progress), waiting for the charger, paused because the phone is warm, or done,
  plus the space saved so far and a "Compress now" button.
- App icon: the sensor's 16-cell colour filter (4x4 green, blue, red, green
  blocks) on black, with a monochrome layer for themed icons.

### Changed
- 200MP auto exposure keeps the lowest ISO the sensor offers (ISO 12) and
  lengthens the shutter first, up to the camera's longest exposure (1/8.5 s);
  only then does ISO go up. The manual ISO dial now starts at ISO 12.
- The 200MP hint now says to hold still until the viewfinder freezes; you can
  move while the photo is processed.

### Fixed
- The viewfinder could come up stretched after leaving and reopening the app.
  Android resets the preview buffer to the view's portrait size whenever it
  rebuilds the viewfinder layer, without telling the app; a camera started after
  that streamed 1440x1920 instead of 1440x1080. The buffer size is now set on the
  camera thread right before the stream is created (checked on the phone: 9 of 9
  reopens via home, recent apps and the Photos screen gave 1440x1080).

## 0.5.3 - 2026-10-27

### Added
- Manual ISO and shutter speed for 200MP. Two dials sit at the bottom of the
  viewfinder: drag sideways to change in third stops (right is brighter), tap to
  switch between Auto and Manual (gold = manual). Set one and the meter keeps the
  other on auto; set both for full manual. ISO 50 up to the sensor maximum,
  shutter 1/8000 to 1 s within the sensor's range. The 50MP modes keep the
  camera's own auto exposure.

## 0.5.2 - 2026-10-27

### Changed
- 200MP saves are about 3 s faster: the viewfinder pauses on its last frame while
  the DNG is written and resumes when it is saved (you cannot take the next photo
  during a save anyway). Streaming the 200MP readout used power the save needs.
  Phone, real shots from a cool start: 18.7-19.3 s instead of 22.0-22.6 s
  (24.7 s before 0.5.1). The photo itself is unchanged.

## 0.5.1 - 2026-10-27

### Changed
- 200MP saves are about 16% faster with the same photo: on the phone a full-frame
  reconstruction + DNG takes 13.4 s instead of 15.9 s and 90 instead of 109
  CPU-seconds (3 runs each from a cool start), so the phone also stays cooler.
  The median and directional filters use cheaper histogram updates and loops the
  compiler vectorises across pixels, with each pixel's arithmetic unchanged; the
  old and new DNGs decode identically (0 of 599,270,400 samples changed).
  Details: bench/notes/lumdir-save-cpu.md.

### Added
- Developer benchmark: `am start -n com.samsung.android.ruler/dev.s24cam.ProbeActivity
  --ez lumdirbench true` runs the exact save pipeline on a RAW10 frame in the app's
  files folder. The app is profileable from adb shell (simpleperf).

## 0.5.0 - 2026-10-27

### Added
- Background re-compression: while the phone charges, the app re-encodes its own DNGs
  with a tuned lossless JPEG XL profile (effort 7) and replaces the original only after
  every tile decodes bit-identical twice, with a crash-safe journal. Shots stay fast
  (effort 1). Phone: 293.7 -> 194.4 MB (-34%) in 63 s while charging; 0 changed samples
  over the full frame, all metadata kept, opens in Lightroom. Stops when the phone gets
  warm. Design: bench/notes/background-compact.md.

## 0.4.1 - 2026-10-27

### Changed
- No change to photos. JPEG XL encoder study recorded in bench/notes/jxl-tuning.md:
  effort 2 makes 200MP DNGs 20% smaller (222 -> 177 MB) but a save takes 42-48 s
  instead of 26-27 s on the phone, so the app keeps the fast effort-1 encoder.
  Version code raised past the test builds.

## 0.4.0 - 2026-10-27

### Changed
- 200MP DNGs are ~20-30% smaller (phone test: 231 MB instead of 275-296 MB for the same scene): the noise-matched
  code curve now steps at 0.5 sigma of the sensor noise instead of 0.25 sigma. The
  rounding adds 1% to the noise (0.07 -> 0.14 sigma of error, no bias); 2x crops of
  text, colour edges and shadows pushed +3 EV are indistinguishable from 0.3.3.

## 0.3.3 - 2026-10-27

### Changed
- 200MP save uses the idle CPU: the next band's colour reconstruction runs while the
  current band's medians and codes are computed, the native fit/bilateral stages start
  per column as soon as their neighbours are done (no whole-band barriers), and the
  four calibration phases run in parallel. Phone (S24 Ultra, cool start, A/B):
  200MP save 26.4 s -> 24.2 s; desktop full frame 11.8 s -> 9.9 s. Output identical to
  0.3.2 (0 differences over a full frame).

### Added
- `Save CPU:` and `Lumdir scheduling:` log lines (process CPU time, busy cores).

## 0.3.2 - 2026-10-27

### Changed
- 200MP save 34 s -> 31 s on the phone: the dir_port stages (guide, fits, bilateral) and
  the chroma Gaussian run in native code (`libs24lumdir`) with bounded band buffers
  (~114 MB). Output is identical to the Java path (0 differences over a full frame);
  the Java path remains as fallback.

## 0.3.1 - 2026-10-27

### Changed
- 200MP save is about 4x faster on the phone (129 s -> 34 s on an S24 Ultra), same output
  within 1 code: a native 12x12 sliding-histogram median (35 ns per median on the phone,
  was ~200), bulk array access instead of per-element ByteBuffer reads, JPEG XL tiles
  encoded in parallel with the reconstruction, and the MediaStore metadata scan deferred
  (it was blocking the save for ~26 s).
- Working memory for a 200MP save drops from 3.2 GB of frame-sized stores to ~90 MB of
  bounded rings; Java heap peak ~200-290 MB (was ~500 MB, near the 512 MB cap).
- The save log now accounts for the whole wall time stage by stage.

## 0.2.4 - 2026-10-27

### Changed
- Bench: `lumdir` is about 6x faster on the PC with bit-identical output (book crop
  9.9 s -> 1.6 s, 4096x4096 tile 60 s -> 9.2 s): exact batched upper-median selection
  for the phase flattening, calibrated samples reused from dir_port, shared chroma
  denominator, multi-threaded fits/bilateral/filters (`LUMDIR_THREADS`), fewer temporaries.

### Added
- Bench: `bench/time_lumdir.py` (timing + identity check), `bench/test_lumdir.py`.

## 0.2.3 - 2026-10-27

### Changed
- 200MP conversion (`Directional16`) is about 1.5x faster with bit-identical output:
  fused colour fits, cached per-phase calibration of RAW10 codes, streaming bilateral,
  dynamically scheduled row chunks. Full frame on the PC: 10.9 s -> 6.9 s.

### Added
- `gradlew directional16Bench` full-frame benchmark (`--write` / `--compare` for DN checks).

## 0.2.2 - 2026-10-27

### Added
- Bench: `bench/linear_dng.py` writes full-colour (LinearRaw) DNGs from a reconstruction,
  with a noise-matched code curve (LinearizationTable) and lossless JPEG or JPEG XL
  compression. Lightroom mobile opens them as raw files.

## 0.2.1 - 2026-10-27

### Added
- Probe: `crop=x,y,w,h` saves only a region of each RAW frame (fast focused tests),
  `zoom=` sets the zoom ratio, and a `c` stream flag requests the CROPPED_RAW use case.
- Bench: sensor model notes (`bench/notes/sensor_model.md`), focus/ISO test renderers,
  and the 200MP reconstruction experiments from the book scene, including `lumdir`
  (brightness from each pixel's own reading, colour from the edge-directed reconstruction).

### Changed
- 200MP conversion works in 384-row bands (was 96) and logs per-stage save timings.

## 0.2.0 - 2026-10-26

### Added
- 200MP shots also save `<name>_sensor.dng`: the untouched 16-cell sensor readout
  (8x8 colour pattern, lossless), so the conversion can be redone on a PC.
- Benchmark (`bench/`) for comparing 200MP reconstructions on a fixed scene.

### Changed
- 200MP conversion now uses an edge-directed 16-cell reconstruction with per-position
  sensor calibration and noise-aware smoothing (`Directional16`). On the carton benchmark
  it cuts barcode colour fringing by about 60% and fine noise by about 80% versus 0.1.0.
- The free-space check accounts for the extra sensor file.

## 0.1.0 - 2026-10-26

### Added
- Camera screen with live preview and three modes: 200MP main, 50MP main, 50MP 5x tele.
- RAW capture saved as lossless-JPEG DNG in Pictures/S24 HighRes.
- 200MP: 16-cell sensor readout remosaiced to Bayer; built-in metering because the HAL's
  auto exposure stays parked in this session.
- Torch toggle and a save progress ring around the shutter.
- Free-space check before each shot.
- ADB probe (`ProbeActivity`, `tools/probe.sh`) for testing camera ids, streams and vendor keys.
