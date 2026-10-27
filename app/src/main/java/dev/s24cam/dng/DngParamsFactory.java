package dev.s24cam.dng;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.os.Build;
import android.util.Rational;

/** Android-only adapter. DngWriter and DngParams also run on a plain JVM. */
public final class DngParamsFactory {
    private DngParamsFactory() {}

    /** orientation is a TIFF orientation (1..8), not degrees. */
    public static DngParams from(CameraCharacteristics c, CaptureResult r,
                                 int width, int height, int orientation) {
        DngParams p = new DngParams();
        p.width = width;
        p.height = height;
        p.orientation = orientation;
        Integer cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (cfa == null || cfa < 0 || cfa > 3)
            throw new IllegalArgumentException("Only 2x2 Bayer CFA sensors are supported");
        p.cfaPattern = cfa;
        BlackLevelPattern black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if (black != null) black.copyTo(p.blackLevel, 0);
        float[] dynamicBlack = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if (dynamicBlack != null && dynamicBlack.length == 4) {
            // Both Android black-level keys use spatial CFA order.
            for (int i = 0; i < 4; i++)
                p.blackLevel[i] = Math.round(dynamicBlack[i]);
        }
        Integer white = r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        if (white == null) white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if (white != null) p.whiteLevel = white;
        p.colorMatrix1 = matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        p.colorMatrix2 = matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        p.forwardMatrix1 = matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1));
        p.forwardMatrix2 = matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
        p.cameraCalibration1 = matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
        p.cameraCalibration2 = matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));
        Integer light1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        // Android exposes the second illuminant as Byte, unlike the first.
        Byte light2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        if (light1 != null) p.calibrationIlluminant1 = light1;
        if (light2 != null) p.calibrationIlluminant2 = light2 & 255;
        Rational[] neutral = r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if (neutral != null && neutral.length == 3)
            for (int i = 0; i < 3; i++) p.asShotNeutral[i] = neutral[i].doubleValue();
        Long exposure = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
        Integer boost = r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        Float aperture = r.get(CaptureResult.LENS_APERTURE);
        Float focal = r.get(CaptureResult.LENS_FOCAL_LENGTH);
        if (exposure != null) p.exposureTimeSec = exposure / 1e9;
        if (iso != null) p.iso = (int) Math.round(iso * (boost == null ? 1.0 : boost / 100.0));
        if (aperture != null) p.fNumber = aperture;
        if (focal != null) p.focalLengthMm = focal;
        p.make = "samsung";
        p.model = Build.MODEL;
        p.uniqueCameraModel = p.make + " " + p.model;
        p.software = "S24Cam Java DNG";
        // SENSOR_TIMESTAMP is monotonic, not Unix time. Record wall time at adaptation.
        p.captureTimeMillis = System.currentTimeMillis();
        return p;
    }

    private static double[] matrix(ColorSpaceTransform t) {
        if (t == null) return null;
        double[] values = new double[9];
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 3; col++)
                values[row * 3 + col] = t.getElement(col, row).doubleValue();
        return values;
    }
}
