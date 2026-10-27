package dev.s24cam.dng;

/** Immutable settings; each encode call owns a separate single-threaded native encoder. */
public final class JxlTileEncoder implements TileEncoder {
    private final float distance;
    private final int effort;
    private final int decodingSpeed;
    private final boolean linearRaw;

    public JxlTileEncoder(float distance, int effort, int decodingSpeed) {
        this(distance, effort, decodingSpeed, false);
    }

    private JxlTileEncoder(float distance, int effort, int decodingSpeed, boolean linearRaw) {
        JxlNative.validateSettings(distance, effort, decodingSpeed);
        this.distance = distance;
        this.effort = effort;
        this.decodingSpeed = decodingSpeed;
        this.linearRaw = linearRaw;
    }

    /** Lossless camera-code profile measured in bench/notes/jxl-tuning.md. */
    public static JxlTileEncoder forLinearRaw() {
        return new JxlTileEncoder(0, JxlNative.LINEAR_RAW_EFFORT,
                JxlNative.LINEAR_RAW_DECODING_SPEED, true);
    }

    public float getDistance() { return distance; }
    public int getEffort() { return effort; }
    public int getDecodingSpeed() { return decodingSpeed; }
    public boolean usesLinearRawTuning() { return linearRaw; }

    @Override public int compression() { return 52546; }

    /**
     * Always writes a 16-bit codestream: Adobe's DNG reader scales JPEG XL samples to the
     * codestream's bit depth (a 10-bit stream of codes 0..1023 came out 64x too bright in
     * Lightroom), while DNG BitsPerSample is 16. Lossless size grows by about 1%.
     */
    @Override public byte[] encode(short[] samples, int width, int height, int channels, int bits) {
        if (bits < 1 || bits > 16) throw new IllegalArgumentException("bits must be 1..16");
        if (linearRaw) return JxlNative.encodeLinearRaw(samples, width, height, channels);
        return JxlNative.encode(samples, width, height, channels, 16, distance, effort, decodingSpeed);
    }
}
