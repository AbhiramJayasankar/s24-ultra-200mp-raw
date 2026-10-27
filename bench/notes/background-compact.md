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
| Journal, MediaStore confirms the original row was deleted | discard journal and temp without recreating the photo |
| Temp missing or hash wrong | keep everything, refuse further work, log |

The temp is never deleted unless the original hashes to the old or the new content,
or MediaStore positively confirms that its row no longer exists. Existence queries
include pending and trashed rows. Query/read/access errors preserve recovery files;
a failed open is never treated as evidence of deletion.

## Photo library data API

All types below are public in `dev.s24cam`. Value fields are `public final`;
constructors are internal. No UI is supplied.

```java
PhotoLibrary.list(Context c)                         // static List<Photo>
PhotoLibrary.freeBytes()                             // static long
PhotoLibrary.delete(Context c, Collection<Uri> uris)  // static DeleteResult
PhotoLibrary.thumbnail(Context c, Photo p, int sizePx)// static Bitmap, nullable

CompactStatus.get(Context c)                         // static Snapshot
CompactStatus.addListener(Runnable r)                // static void
CompactStatus.removeListener(Runnable r)             // static void
CompactStatus.runNow(Context c)                      // static void
```

`PhotoLibrary.Photo`: `long id`, `Uri uri`, `String name`, `long bytes`,
`long takenMs`, `String mode`, `PhotoLibrary.State state`.
`takenMs` uses DATE_TAKEN, falling back to DATE_ADDED * 1000. Photos are ordered
newest first (ID descending breaks timestamp ties). The query includes pending
items and requires both app ownership and the exact gallery folder.

`PhotoLibrary.State`: `SAVING`, `COMPRESSING`, `COMPRESSED`, `WAITING`, `ORIGINAL`,
in precedence order. State inspection reads IFD metadata, with no codec, preview,
tile payload or padding reads. The compactor still performs full structural,
padding, sample and hash verification before replacing any original.

Modes are `200MP`, `50MP`, `50MP 5x`, or `DNG`. Existing main and telephoto 50MP
saves both use `S24HR_50MP_<timestamp>.dng`; those names can only report `50MP`.
An explicit `_5x_` or `_5x.dng` marker is recognized when present. Capture naming
is unchanged, including `_sensor.dng` originals.

`PhotoLibrary.DeleteResult`: `int deleted`, `long freedBytes`, `List<String> skipped`.
The skipped list is immutable and contains `NAME: reason` entries. Deletion
rechecks save/pending status, the current compaction URI, and the journal under
the same lock used to claim a file for compaction and to perform recovery.
Unreadable journals block deletion. Security/approval failures are reported in
the skipped list; the API never launches a permission prompt. Freed bytes come
from the latest MediaStore size of successfully deleted rows.

`CompactStatus.Snapshot`: `Phase phase`, `String currentName`, `Uri currentUri`,
`int tilesDone`, `int tilesTotal`, `int filesLeft`, `long savedBytesTotal`,
`int compressedTotal`, `String lastMessage`, `long lastChangeMs`.
`currentName` and `currentUri` are null between files. `filesLeft` is a best-known
count of eligible headers, including files awaiting the age/free-space checks.

`CompactStatus.Phase`: `IDLE`, `WAITING_FOR_CHARGER`, `RUNNING`, `PAUSED_WARM`,
`PAUSED_STOPPED`, `DONE`, `ERROR`. Totals, last message and last phase are saved
in SharedPreferences. A pending job on process startup reports waiting; an old
RUNNING phase cannot resurrect an active file. During the process, a specific
pause/error or completed result is preserved while an old job registration is
awaiting JobScheduler's completion callback.

Run all `PhotoLibrary` methods off the UI thread. `thumbnail` first asks
MediaStore, then falls back to the writer's single RGB IFD0 preview strip,
applying orientation and requested bounding size. The caller owns the returned
Bitmap. `CompactStatus` methods may be called on any thread. `runNow` immediately
starts a worker using the probe/job run path, ignoring charging but retaining
thermal, age, free-space, single-run and transaction checks. A retry is scheduled
while charging if necessary.

Listeners run synchronously on the changing thread, outside the status lock;
they should return promptly and post UI work to their own thread. Tile updates
are throttled to 250 ms; file/phase/result changes notify immediately. Tile
progress counts the first verified encode pass. The independent disk pass and
durable replacement must also finish before a success is counted. A stopped
job retains its current URI until the worker exits its file transaction, so a
pause cannot release the delete guard early.

The additional public DNG helpers are:

```java
// DngCompactor (all static; all throw IOException)
Header inspect(FileChannel source)
long effort(FileChannel source)
Preview preview(FileChannel source)
Stats compact(FileChannel source, File target, TileCodec codec, Check check,
              int workers, ProgressListener progress)
// ProgressListener
void onProgress(int tilesDone, int tilesTotal)
// SafeDngReplace (static; throws IOException; null when there is no journal)
String journalUri(File directory)
// SafeDngReplace.Store (throws IOException on uncertainty, rather than returning false)
boolean exists(String uri)
```

`Header` contains `public final long effort` (-1 if absent) and
`public final String skipReason` (null when eligible). `Preview` contains
`public final int width, height, orientation` and `public final byte[] rgb`.
The old five-argument `compact` overload remains supported.

## Manual trigger

    adb shell am start -n com.samsung.android.ruler/dev.s24cam.ProbeActivity --ez compact true
    adb logcat -s S24CAM | grep Compact
