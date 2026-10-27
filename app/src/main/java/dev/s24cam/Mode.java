package dev.s24cam;

/**
 * Capture modes and the exact Samsung recipe each needs, found by reading logcat from the
 * stock Camera (200MP) and Expert RAW (50MP) apps on an S24 Ultra (One UI 9).
 */
enum Mode {
    /** 200MP main camera: the HAL's AI-high-res dynamic shot returns the 16-cell mosaic. */
    MAIN_200("200", "Main", SamsungCam.CAM_LOGICAL_BACK, SamsungCam.SM_HIGHRES_200MP, 16320, 12240, true,
            "jpeg:16320x12240:0x802,yuv:16320x12240:2,raw10:16320x12240:2,yuv:512x384:4",
            "control.cameraClient=i:0;control.samsungCamera=b:1;sensor.pixelMode=b:1;control.initialZoomRatio=f:1.0;control.adaptivePixelMode=i:1",
            "control.capturePhysicalId=i:56;control.dynamicShotHint=i:11796481;control.dynamicShotExtraInfo=i:8388608;control.dynamicShotDeviceInfo=l:0",
            "raw10,yuv"),
    /** 50MP main camera through Expert RAW's high-resolution RAW path. */
    MAIN_50("50", "Main", SamsungCam.CAM_MAIN, SamsungCam.SM_EXPERT_RAW_HIGHRES, 8160, 6120, false,
            "jpeg:5712x4284:2,yuv:5712x4284:2,raw10:8160x6120:2,yuv:512x384:4", "", "", "raw10"),
    /** 50MP 5x telephoto (IMX854) through the same path. */
    TELE_50("50", "5× tele", SamsungCam.CAM_TELE_5X, SamsungCam.SM_EXPERT_RAW_HIGHRES, 8160, 6120, false,
            "jpeg:5712x4284:2,yuv:5712x4284:2,raw10:8160x6120:2,yuv:512x384:4", "", "", "raw10");

    final String figure, caption, cameraId;
    final int shootingMode, rawWidth, rawHeight;
    /** True when the RAW frame is the sensor's 4x4-cell mosaic and needs a remosaic. */
    final boolean sixteenCell;
    /** Non-preview streams, "format:WxH:option" as in {@link ProbeActivity}. */
    final String streams;
    /** Vendor keys for every request, and extra ones for the still request only. */
    final String keys, stillKeys;
    /** Stream formats the still request targets. */
    final String stillTargets;

    Mode(String figure, String caption, String cameraId, int shootingMode, int rawWidth, int rawHeight,
         boolean sixteenCell, String streams, String keys, String stillKeys, String stillTargets) {
        this.figure = figure;
        this.caption = caption;
        this.cameraId = cameraId;
        this.shootingMode = shootingMode;
        this.rawWidth = rawWidth;
        this.rawHeight = rawHeight;
        this.sixteenCell = sixteenCell;
        this.streams = streams;
        this.keys = keys;
        this.stillKeys = stillKeys;
        this.stillTargets = stillTargets;
    }

    /** Rough size of everything one shot saves, used to check free space first. */
    long expectedDngBytes() {
        long one = (long) rawWidth * rawHeight * 5 / 4;
        return sixteenCell ? 2 * one : one; // 200MP also keeps the native sensor file
    }
}
