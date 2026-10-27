# Background lossless re-compression (0.5.0)

Shots are encoded with JPEG XL effort 1 (fast, ~26 s per 200MP save). While the phone
charges, `CompactJobService` re-encodes the app's own DNGs with the tuned lossless
profile (`JxlTileEncoder.forLinearRaw`: effort 7, predictor 5, RCT 40, 1% learning; see
jxl-tuning.md) and replaces the original. Decoded samples are bit-identical.

## Phone result (S24 Ultra, USB charging, keeper frame)

    Compact: S24HR_200MP_20261027_074342.dng 293.66 -> 194.37 MB, 62.74 s, 3072 tiles verified twice, CPU 211.02 s

Independent PC check against a backup: 599,270,400 samples, 0 changed; only
TileOffsets, TileByteCounts and JXLEffort (1 -> 7) differ; all other tags, EXIF and the
thumbnail are identical; MediaStore reports the new size; Lightroom mobile opens it.

## What runs

- `CompactJobService`: charging + battery not low, persisted, scheduled after each save
  and at app start when a file is eligible. One file at a time; `onStopJob` cancels
  cooperatively before the journal is written.
- `CompactRunner`: finds the app's own `Pictures/S24 HighRes/*.dng` items; skips pending
  items, files being written by `DngSaver`, and files modified < 2 minutes ago; needs
  free space >= original + 512 MB; stops at thermal status MODERATE.
- `DngCompactor`: eligible only for this app's LinearRaw JPEG XL DNGs with JXLEffort < 7.
  Re-serialises every IFD, tag and blob, replacing only tile payloads, offsets, counts
  and the effort tag. Each tile: decode, encode, decode again, compare exactly.
- `SafeDngReplace`: the only code that writes the original (below).

## Replace transaction

1. Temp in `files/compact/<id>.tmp` (not cache), fsync, SHA-256.
2. Original re-hashed (unchanged?), then a second, independent verification pass
   from disk: every tile of the temp decoded and compared with the original's tiles.
3. Original re-hashed again, then the journal (uri, temp, sizes, both hashes) is written
   atomically (temp + rename + directory fsync).
4. Original opened `rwt`, temp copied, fsync; re-read and checked against the new hash.
5. MediaStore rescanned (size must match), then journal and temp deleted.

Recovery runs first on every run:

| State found | Action |
|---|---|
| No journal | delete orphan temps (no overwrite can have started) |
| Journal, original = new hash | refresh MediaStore, clean up |
| Journal, original = old hash | overwrite never started: clean up |
| Journal, anything else (truncated/partial) | re-copy from the verified temp, re-check |
| Temp missing or hash wrong | keep everything, refuse further work, log |

The temp is never deleted unless the original hashes to the old or the new content.

## Manual trigger

    adb shell am start -n com.samsung.android.ruler/dev.s24cam.ProbeActivity --ez compact true
    adb logcat -s S24CAM | grep Compact
