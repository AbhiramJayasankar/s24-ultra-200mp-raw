# Reaching the S24 Ultra's 200MP and 50MP modes from a third-party app

Phone: SM-S928B, One UI 9 (Android 17, build S928BXXU6EZIF), no root, locked bootloader.
All results below were measured on this phone on 2026-10-26.

## What works

| Mode | Camera id | Output | Save time (release build) |
|---|---|---|---|
| 200MP main (S5KHP2) | 20 | 16320 x 12240 DNG, 16-cell readout remosaiced to Bayer | 9 s, ~210 MB |
| 50MP main | 56 | 8160 x 6120 DNG (HAL remosaic) | 1.3 s, ~60 MB |
| 50MP 5x tele (IMX854) | 54 | 8160 x 6120 DNG (HAL remosaic) | 1.4 s, ~62 MB |

All DNGs are lossless-JPEG compressed and decode pixel-exact in LibRaw (rawpy).

## The four gates and how each is passed

1. **Hidden camera ids.** Samsung's cameraserver only opens ids 20/52/54/56/58 for an
   allowlist of package names (`PackagePolicyList`, logged as "this package can access to
   hidden camera ids"). The app uses `com.samsung.android.ruler`, which is on the list and
   not installed on this phone. Public apps only see ids 0-3.
2. **Samsung session parameters.** `CameraDevice.setParameters(String)` is a hidden API, so
   it is called through `/system/framework/scamera_sdk_util.jar`
   (`CustomInterfaceHelper.setSamsungParameter`). The string is the one Samsung's apps send:
   `samsungcamera=true;shootingmode=N;...`. Shooting mode 40 = Expert RAW high-res (50MP),
   39 = stock camera 200MP.
3. **Stream options.** `OutputConfiguration.semCreateOutputConfiguration(-1, surface, 0, option)`
   (public SDK API, it stores option << 16 in the stream use case). Option 1 = preview,
   2 = picture, 4 = thumbnail, 0x802 = 200MP JPEG. The HAL rejects the 50MP pipeline unless
   the YUV + JPEG + thumbnail streams Expert RAW uses are also configured.
4. **200MP dynamic shot.** UniHAL (Samsung's post-processing layer) stays disabled for any
   client whose `pkgName` parameter is set, and cameraserver always sets it for third-party
   apps. The vendor CHI layer underneath still honours these per-request vendor keys, copied
   from the stock app's 200MP still request:
   - session/every request: `control.cameraClient=0`, `control.samsungCamera=1` (byte),
     `sensor.pixelMode=1` (byte), `control.adaptivePixelMode=1`
   - still request: `control.capturePhysicalId=56`, `control.dynamicShotHint=0xB40001`,
     `control.dynamicShotExtraInfo=0x800000`, `control.dynamicShotDeviceInfo=0` (int64)

   Without the dynamic-shot keys the 200MP RAW and YUV frames come back with every other
   8-pixel column block empty.

## What the 200MP RAW actually is

The 16320 x 12240 RAW10 stream is the sensor's native 16-cell ("Tetra²") mosaic: 4x4 blocks
of one colour in a G B / R G super-cell, not a Bayer image. Proof: per-8x8-phase means show
the block structure, and colour ratios per super-cell correlate 0.99 with the 50MP Bayer frame
of the same scene. `Remosaic16` converts it to Bayer row by row (keeps pixels that already
have the wanted colour, scales the rest by local colour ratios), so detail stays at full 200MP
while colour is resolved at about 8 px.

## Known limits (v1)

- 200MP auto exposure does not run in this session; the app meters the viewfinder itself.
- Requesting the 200MP or 50MP **JPEG** output crashes the camera HAL (SIGSEGV in its EXIF
  debug-data step); the camera service restarts it. The app only takes RAW.
- The remosaic is simple; fine colour edges can bleed a few pixels. A gradient-aware
  remosaic is the next quality step.
- DNG orientation assumes the phone is held upright.
- Auto exposure in 200MP mode goes down to 1/8 s; the manual shutter dial goes to 1 s.

## Tools

- `ProbeActivity` + `tools/probe.sh`: run one capture with any camera id, streams, vendor
  keys and parameters from adb; files move to `captures/` and are deleted from the phone.
- `tools/raw_preview.py`: quick look at RAW10/RAW16 dumps.
- `adb shell dumpsys media.camera`: full camera characteristics and vendor tag list.

## Sources

- HyperRamzey/fold5-camera2-research (Galaxy Z Fold 5): allowlist gate, Expert RAW
  50MP path via `scamera_sdk_util.jar` and `semCreateOutputConfiguration`.
- tomppi/fold5-lineageos-50mp: Samsung stream options and HighResolution scenario.
- logcat from the stock Camera and Expert RAW apps on this phone (200MP recipe).
