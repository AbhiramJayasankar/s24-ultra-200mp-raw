package dev.s24cam.dng;

import java.util.Objects;

/** Bare JPEG XL codestreams with unscaled unsigned integer input samples. */
public final class JxlNative {
    static final int LINEAR_RAW_EFFORT = 7;
    static final int LINEAR_RAW_DECODING_SPEED = 0;
    private static final boolean AVAILABLE;
    static {
        boolean loaded;
        try {
            System.loadLibrary("s24jxl");
            loaded = true;
        } catch (LinkageError | SecurityException e) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private JxlNative() {}

    /** Safe to call on a desktop JVM or a device without the native library. */
    public static boolean available() { return AVAILABLE; }

    /** Decodes exactly one 16-bit integer tile, without rescaling camera codes. */
    public static short[] decode(byte[] codestream, int width, int height, int channels) {
        Objects.requireNonNull(codestream, "codestream");
        if (codestream.length < 2 || width <= 0 || height <= 0
                || (channels != 1 && channels != 3)
                || (long) width * height > Integer.MAX_VALUE / channels) {
            throw new IllegalArgumentException("Invalid JPEG XL tile dimensions or codestream");
        }
        if (!AVAILABLE) throw new UnsatisfiedLinkError("s24jxl is unavailable on this platform");
        return decodeNative(codestream, width, height, channels);
    }

    private static native short[] decodeNative(byte[] codestream, int width, int height, int channels);

    /**
     * Encodes one tile; channels must be 1 or 3, bits 1..16, distance 0..25,
     * effort 1..10, and decodingSpeed 0..4. Distance zero preserves every sample.
     * Throws IllegalArgumentException for invalid input, IllegalStateException for
     * an encoder error, or UnsatisfiedLinkError if the library is unavailable.
     */
    public static byte[] encode(short[] samples, int width, int height, int channels,
                                int bits, float distance, int effort, int decodingSpeed) {
        return encode(samples, width, height, channels, bits, distance, effort, decodingSpeed, false);
    }

    static byte[] encodeLinearRaw(short[] samples, int width, int height, int channels) {
        return encode(samples, width, height, channels, 16, 0,
                LINEAR_RAW_EFFORT, LINEAR_RAW_DECODING_SPEED, true);
    }

    private static byte[] encode(short[] samples, int width, int height, int channels,
                                 int bits, float distance, int effort, int decodingSpeed,
                                 boolean linearRaw) {
        Objects.requireNonNull(samples, "samples");
        validateSettings(distance, effort, decodingSpeed);
        if (width <= 0 || height <= 0 || (channels != 1 && channels != 3)
                || bits < 1 || bits > 16
                || (long) width * height > Integer.MAX_VALUE / channels
                || (long) width * height * channels != samples.length) {
            throw new IllegalArgumentException("Invalid tile dimensions, channels, bits, or sample count");
        }
        if (!AVAILABLE) throw new UnsatisfiedLinkError("s24jxl is unavailable on this platform");
        // Native code validates sample ranges while holding the input array once.
        return encodeNative(samples, width, height, channels, bits, distance, effort, decodingSpeed, linearRaw);
    }

    static void validateSettings(float distance, int effort, int decodingSpeed) {
        if (!Float.isFinite(distance) || distance < 0 || distance > 25
                || effort < 1 || effort > 10 || decodingSpeed < 0 || decodingSpeed > 4) {
            throw new IllegalArgumentException("Expected distance 0..25, effort 1..10, decodingSpeed 0..4");
        }
    }

    private static native byte[] encodeNative(short[] samples, int width, int height,
                                              int channels, int bits, float distance,
                                              int effort, int decodingSpeed, boolean linearRaw);
}
