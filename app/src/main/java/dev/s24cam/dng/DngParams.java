package dev.s24cam.dng;

/** Metadata for one raw image. All matrix arrays are 3x3, row-major. */
public final class DngParams {
    public int width, height;
    /** Android CFA arrangement: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. */
    public int cfaPattern;
    /**
     * Optional non-Bayer colour filter layout, row-major colour indices (0 R, 1 G, 2 B) of a
     * {@code cfaRows} x {@code cfaCols} repeat, e.g. the 8x8 16-cell layout. Overrides
     * {@link #cfaPattern}; all four black levels must then be equal.
     */
    public int[] cfaCustom;
    public int cfaRows = 2, cfaCols = 2;
    /** Row-major positions in the CFA's 2 by 2 block, not R/G/G/B channel order. */
    public int[] blackLevel = {0, 0, 0, 0};
    public int whiteLevel = 65535;
    /** XYZ to camera, as reported by Android SENSOR_COLOR_TRANSFORM1/2. */
    public double[] colorMatrix1, colorMatrix2;
    public double[] forwardMatrix1, forwardMatrix2;
    public double[] cameraCalibration1, cameraCalibration2;
    public int calibrationIlluminant1, calibrationIlluminant2;
    public double[] asShotNeutral = {1, 1, 1};
    public int orientation = 1;
    public String make, model, uniqueCameraModel, software;
    public double exposureTimeSec;
    public int iso;
    public double fNumber, focalLengthMm;
    /** Wall-clock Unix milliseconds; zero omits TIFF and EXIF date/time tags. */
    public long captureTimeMillis;
    public boolean losslessJpeg;
}
