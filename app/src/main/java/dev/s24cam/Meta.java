package dev.s24cam;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import java.util.Arrays;

/** Plain-text dump of the metadata a DNG writer needs (one key=value per line). */
final class Meta {
    private Meta() {}

    static String describe(CameraCharacteristics c, CaptureResult r) {
        StringBuilder sb = new StringBuilder();
        line(sb, "cfa", c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT));
        line(sb, "whiteLevel", c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
        BlackLevelPattern bl = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if (bl != null) {
            int[] v = new int[4];
            bl.copyTo(v, 0);
            line(sb, "blackLevelPattern", Arrays.toString(v));
        }
        line(sb, "illuminant1", c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1));
        line(sb, "illuminant2", c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2));
        line(sb, "colorTransform1", cst(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)));
        line(sb, "colorTransform2", cst(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)));
        line(sb, "forwardMatrix1", cst(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)));
        line(sb, "forwardMatrix2", cst(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)));
        line(sb, "calibration1", cst(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)));
        line(sb, "calibration2", cst(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)));
        line(sb, "sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION));
        line(sb, "pixelArray", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE));
        line(sb, "pixelArrayMaxRes", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION));
        line(sb, "focalLengths", Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)));
        line(sb, "apertures", Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)));

        Rational[] neutral = r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        line(sb, "asShotNeutral", neutral == null ? null : Arrays.toString(neutral));
        RggbChannelVector g = r.get(CaptureResult.COLOR_CORRECTION_GAINS);
        line(sb, "wbGains", g == null ? null : g.getRed() + "," + g.getGreenEven() + "," + g.getGreenOdd() + "," + g.getBlue());
        line(sb, "ccm", cst(r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)));
        line(sb, "dynamicBlackLevel", Arrays.toString(r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)));
        line(sb, "dynamicWhiteLevel", r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL));
        line(sb, "exposureTimeNs", r.get(CaptureResult.SENSOR_EXPOSURE_TIME));
        line(sb, "iso", r.get(CaptureResult.SENSOR_SENSITIVITY));
        line(sb, "postRawBoost", r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST));
        line(sb, "aperture", r.get(CaptureResult.LENS_APERTURE));
        line(sb, "focalLength", r.get(CaptureResult.LENS_FOCAL_LENGTH));
        line(sb, "sensorPixelMode", r.get(CaptureResult.SENSOR_PIXEL_MODE));
        line(sb, "timestampNs", r.get(CaptureResult.SENSOR_TIMESTAMP));
        return sb.toString();
    }

    private static void line(StringBuilder sb, String k, Object v) {
        sb.append(k).append('=').append(v).append('\n');
    }

    private static String cst(ColorSpaceTransform t) {
        if (t == null) return null;
        Rational[] v = new Rational[9];
        t.copyElements(v, 0);
        return Arrays.toString(v);
    }
}
