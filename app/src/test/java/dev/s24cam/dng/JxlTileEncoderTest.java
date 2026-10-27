package dev.s24cam.dng;

import org.junit.Test;

import static org.junit.Assert.*;

public class JxlTileEncoderTest {
    @Test public void settingsAndCompressionTag() {
        JxlTileEncoder encoder = new JxlTileEncoder(0, 7, 2);
        assertEquals(52546, encoder.compression());
        assertEquals(0, encoder.getDistance(), 0);
        assertEquals(7, encoder.getEffort());
        assertEquals(2, encoder.getDecodingSpeed());
    }

    @Test public void rejectsInvalidSettingsWithoutNativeLibrary() {
        for (float distance : new float[]{-1, 26, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new JxlTileEncoder(distance, 1, 0));
        }
        for (int effort : new int[]{0, 11}) {
            assertThrows(IllegalArgumentException.class, () -> new JxlTileEncoder(0, effort, 0));
        }
        for (int speed : new int[]{-1, 5}) {
            assertThrows(IllegalArgumentException.class, () -> new JxlTileEncoder(0, 1, speed));
        }
    }

    @Test public void linearRawProfileIsExplicitAndLossless() {
        JxlTileEncoder raw = JxlTileEncoder.forLinearRaw();
        assertTrue(raw.usesLinearRawTuning());
        assertEquals(52546, raw.compression());
        assertEquals(0, raw.getDistance(), 0);
        assertEquals(7, raw.getEffort());
        assertEquals(0, raw.getDecodingSpeed());
        // Existing callers and JxlSelfTest still request upstream effort defaults.
        assertFalse(new JxlTileEncoder(0, 2, 0).usesLinearRawTuning());
        assertThrows(IllegalArgumentException.class, () -> raw.encode(new short[3], 1, 1, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> raw.encode(new short[2], 1, 1, 3, 12));
    }

    @Test public void rejectsMalformedTilesBeforeNativeCall() {
        JxlTileEncoder encoder = new JxlTileEncoder(0, 1, 0);
        assertThrows(NullPointerException.class, () -> encoder.encode(null, 1, 1, 1, 12));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new short[3], 0, 1, 3, 12));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new short[2], 1, 1, 2, 12));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new short[3], 1, 1, 3, 17));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new short[3], 1, 1, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new short[2], 1, 1, 3, 12));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new short[3],
                Integer.MAX_VALUE, Integer.MAX_VALUE, 3, 16));
    }

    @Test public void availabilityIsSafeOnDesktop() {
        // The Android arm64 library cannot load in this desktop unit-test process.
        assertFalse(JxlNative.available());
        assertThrows(UnsatisfiedLinkError.class,
                () -> JxlNative.encode(new short[3], 1, 1, 3, 12, 0, 1, 0));
    }

    @Test public void decoderValidatesInputWithoutLoadingNativeLibrary() {
        assertThrows(NullPointerException.class, () -> JxlNative.decode(null,256,256,3));
        assertThrows(IllegalArgumentException.class, () -> JxlNative.decode(new byte[1],256,256,3));
        assertThrows(IllegalArgumentException.class, () -> JxlNative.decode(new byte[2],0,256,3));
        assertThrows(IllegalArgumentException.class, () -> JxlNative.decode(new byte[2],256,256,4));
        assertThrows(IllegalArgumentException.class, () -> JxlNative.decode(new byte[2],Integer.MAX_VALUE,Integer.MAX_VALUE,3));
        assertThrows(UnsatisfiedLinkError.class, () -> JxlNative.decode(new byte[2],256,256,3));
    }
}
