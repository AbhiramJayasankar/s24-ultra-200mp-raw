package dev.s24cam.dng;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

public class DngWriterTest {
    @Test public void raw10PaddingAllCfaAndCompressionModes() throws Exception {
        for (int cfa = 0; cfa < 4; cfa++) for (boolean compressed : new boolean[]{false, true}) {
            DngParams p = params(cfa, compressed);
            int[] samples = samples(p.width * p.height, 1023);
            ByteBuffer input = packed(samples, p.width, p.height, 89);
            roundTrip(p, input, 89, samples, true);
        }
    }

    @Test public void raw16PaddingAllCfaAndCompressionModes() throws Exception {
        for (int cfa = 0; cfa < 4; cfa++) for (boolean compressed : new boolean[]{false, true}) {
            DngParams p = params(cfa, compressed);
            p.whiteLevel = 65535;
            int[] samples = samples(p.width * p.height, 65535);
            ByteBuffer input = raw16(samples, p.width, p.height, 137);
            roundTrip(p, input, 137, samples, false);
        }
    }

    private static void roundTrip(DngParams p, ByteBuffer input, int stride, int[] samples,
                                   boolean packed) throws Exception {
        int position = input.position(), limit = input.limit();
        input.mark();
        ByteOrder order = input.order();
        byte[] first = write(p, input, stride, packed);
        byte[] second = write(p, input, stride, packed);
        assertArrayEquals(first, second);
        assertEquals(position, input.position()); assertEquals(limit, input.limit());
        assertEquals(order, input.order()); input.reset(); assertEquals(position, input.position());
        TiffTestReader tiff = new TiffTestReader(first);
        checkTags(tiff, p);
        assertArrayEquals(samples, tiff.raw());
    }

    @Test public void raw10KnownMipiGroupsAndTruncatedLastRowPadding() throws Exception {
        DngParams p = params(2, false); p.width = 4; p.height = 2;
        // High bytes then low bits in increasing pixel order. Last row has no padding.
        ByteBuffer b = ByteBuffer.wrap(new byte[]{0, 1, (byte) 128, (byte) 255, (byte) 0xe4,
                99, 99, 99, (byte) 255, 0, 2, 3, 0x1b});
        TiffTestReader tiff = new TiffTestReader(write(p, b, 8, true));
        assertArrayEquals(new int[]{0, 5, 514, 1023, 1023, 2, 9, 12}, tiff.raw());
    }

    @Test public void nullableTagsAndUnknownExifAreOmitted() throws Exception {
        DngParams p = new DngParams(); p.width = 4; p.height = 2;
        p.exposureTimeSec = Double.NaN; p.fNumber = Double.NaN; p.focalLengthMm = Double.NaN;
        p.asShotNeutral = null;
        TiffTestReader tiff = new TiffTestReader(write(p, ByteBuffer.allocate(16), 8, false));
        Map<Integer, TiffTestReader.Tag> root = tiff.root();
        for (int tag : new int[]{271, 272, 305, 306, 34665, 50721, 50722, 50723, 50724,
                50728, 50778, 50779, 50964, 50965}) assertFalse("tag " + tag, root.containsKey(tag));
        assertArrayEquals(new int[8], tiff.raw());
    }

    @Test public void rejectsInvalidInputBeforeWriting() throws Exception {
        DngParams p = params(0, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> DngWriter.writeRaw10(out, ByteBuffer.allocate(100), 80, p));
        assertThrows(IllegalArgumentException.class, () -> DngWriter.writeRaw16(out, ByteBuffer.allocate(10000), 127, p));
        p.cfaPattern = 4;
        assertThrows(IllegalArgumentException.class, () -> DngWriter.writeRaw16(out, ByteBuffer.allocate(10000), 128, p));
        assertEquals(0, out.size());
    }

    @Test public void doesNotCloseOutputAndPropagatesIoFailure() throws Exception {
        DngParams p = params(0, true);
        ByteBuffer b = ByteBuffer.allocate(p.width * p.height * 2);
        class Sink extends ByteArrayOutputStream {
            boolean closed;
            @Override public void close() { closed = true; }
        }
        Sink out = new Sink(); DngWriter.writeRaw16(out, b, p.width * 2, p);
        assertFalse(out.closed);
        assertThrows(IOException.class, () -> DngWriter.writeRaw16(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("full"); }
        }, b, p.width * 2, p));
    }

    @Test public void jpegSingleStripTinyImageAndMultipleCompleteStrips() throws Exception {
        for (int height : new int[]{2, 32, 64, 66}) {
            DngParams p = params(3, true); p.width = 4; p.height = height; p.whiteLevel = 65535;
            int[] samples = samples(p.width * p.height, 65535);
            TiffTestReader tiff = new TiffTestReader(write(p, raw16(samples, 4, height, 11), 11, false));
            assertArrayEquals(samples, tiff.raw());
        }
    }

    static DngParams params(int cfa, boolean compressed) {
        DngParams p = new DngParams();
        p.width = 64; p.height = 48; p.cfaPattern = cfa; p.losslessJpeg = compressed;
        p.blackLevel = new int[]{61, 62, 63, 64}; p.whiteLevel = 1023;
        p.colorMatrix1 = new double[]{.7, -.2, -.1, -.3, 1.2, .1, -.1, .3, .5};
        p.colorMatrix2 = new double[]{1.6, -1, -.03, .02, .94, .11, .08, .13, .53};
        p.forwardMatrix1 = new double[]{.66, .24, .07, .2, .94, -.14, .02, -.39, 1.2};
        p.forwardMatrix2 = new double[]{.38, .41, .17, -.06, 1.08, -.03, -.14, -.47, 1.9};
        p.cameraCalibration1 = new double[]{.99, 0, 0, 0, 1, 0, 0, 0, 1.02};
        p.cameraCalibration2 = new double[]{1.01, 0, 0, 0, .98, 0, 0, 0, 1.03};
        p.calibrationIlluminant1 = 21; p.calibrationIlluminant2 = 17;
        p.asShotNeutral = new double[]{.48, 1, .56}; p.orientation = cfa + 5;
        p.make = "samsung"; p.model = "SM-S928B"; p.uniqueCameraModel = "samsung SM-S928B test";
        p.software = "Java DNG test";
        p.exposureTimeSec = 1.0 / 33; p.iso = 1600; p.fNumber = 1.7; p.focalLengthMm = 6.3;
        p.captureTimeMillis = 1792954136655L;
        return p;
    }

    private static void checkTags(TiffTestReader tiff, DngParams p) {
        Map<Integer, TiffTestReader.Tag> root = tiff.root();
        integers(root, 254, 4, 1); integers(root, 256, 4, 32); integers(root, 257, 4, 24);
        integers(root, 258, 3, 8, 8, 8); integers(root, 259, 3, 1); integers(root, 262, 3, 2);
        integers(root, 274, 3, p.orientation); integers(root, 277, 3, 3);
        integers(root, 278, 4, 24); integers(root, 279, 4, 32 * 24 * 3); integers(root, 284, 3, 1);
        rational(root, 282, 5, new double[]{72}); rational(root, 283, 5, new double[]{72});
        integers(root, 296, 3, 2);
        integers(root, 50706, 1, 1, 4, 0, 0); integers(root, 50707, 1, 1, 1, 0, 0);
        assertEquals(p.make, root.get(271).text()); assertEquals(p.model, root.get(272).text());
        assertEquals(p.uniqueCameraModel, root.get(50708).text()); assertEquals(p.software, root.get(305).text());
        String date = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date(p.captureTimeMillis));
        assertEquals(date, root.get(306).text());
        rational(root, 50721, 10, p.colorMatrix1); rational(root, 50722, 10, p.colorMatrix2);
        rational(root, 50723, 10, p.cameraCalibration1); rational(root, 50724, 10, p.cameraCalibration2);
        rational(root, 50964, 10, p.forwardMatrix1); rational(root, 50965, 10, p.forwardMatrix2);
        rational(root, 50728, 5, p.asShotNeutral); rational(root, 50730, 10, new double[]{0});
        integers(root, 50778, 3, 21); integers(root, 50779, 3, 17);
        int thumbAt = root.get(273).first();
        assertTrue(thumbAt > 8 && thumbAt + 32 * 24 * 3 <= tiff.bytes.length);
        boolean hasPreview = false;
        for (int i = thumbAt; i < thumbAt + 32 * 24 * 3; i++) hasPreview |= tiff.bytes[i] != 0;
        assertTrue(hasPreview);
        Map<Integer, TiffTestReader.Tag> raw = tiff.ifd(root.get(330).first());
        integers(raw, 254, 4, 0); integers(raw, 256, 4, 64); integers(raw, 257, 4, 48);
        integers(raw, 258, 3, 16); integers(raw, 259, 3, p.losslessJpeg ? 7 : 1);
        integers(raw, 262, 3, 32803); integers(raw, 277, 3, 1);
        if (p.losslessJpeg) { integers(raw, 322, 4, 64); integers(raw, 323, 4, 32); }
        else integers(raw, 278, 4, 32);
        integers(raw, 284, 3, 1); integers(raw, 33421, 3, 2, 2);
        long[][] patterns = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
        integers(raw, 33422, 1, patterns[p.cfaPattern]); integers(raw, 50710, 1, 0, 1, 2);
        integers(raw, 50711, 3, 1); integers(raw, 50713, 3, 2, 2);
        rational(raw, 50714, 5, new double[]{61, 62, 63, 64});
        integers(raw, 50717, 4, p.whiteLevel); integers(raw, 50719, 4, 0, 0);
        integers(raw, 50720, 4, 64, 48); integers(raw, 50829, 4, 0, 0, 48, 64);
        Map<Integer, TiffTestReader.Tag> exif = tiff.ifd(root.get(34665).first());
        rational(exif, 33434, 5, new double[]{p.exposureTimeSec});
        rational(exif, 33437, 5, new double[]{p.fNumber});
        integers(exif, 34855, 3, p.iso);
        rational(exif, 37386, 5, new double[]{p.focalLengthMm});
        assertEquals(date, exif.get(36867).text());
    }
    private static void integers(Map<Integer, TiffTestReader.Tag> tags, int id, int type, long... values) {
        assertNotNull("tag " + id, tags.get(id)); assertEquals(type, tags.get(id).type);
        assertArrayEquals("tag " + id, values, tags.get(id).integers());
    }
    private static void rational(Map<Integer, TiffTestReader.Tag> tags, int id, int type, double[] values) {
        assertNotNull("tag " + id, tags.get(id)); assertEquals(type, tags.get(id).type);
        assertArrayEquals("tag " + id, values, tags.get(id).rationals(), 1e-8);
    }
    private static byte[] write(DngParams p, ByteBuffer b, int stride, boolean packed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (packed) DngWriter.writeRaw10(out, b, stride, p);
        else DngWriter.writeRaw16(out, b, stride, p);
        return out.toByteArray();
    }
    private static int[] samples(int n, int max) {
        int[] a = new int[n]; Random random = new Random(124);
        for (int i = 0; i < n; i++) a[i] = random.nextInt(max + 1);
        int[] edges = {0, 32768, 65535, 0, 1, 65535, 32767, 32768};
        for (int i = 0; i < edges.length; i++) a[i] = edges[i] & max;
        return a;
    }
    private static ByteBuffer packed(int[] samples, int width, int height, int stride) {
        ByteBuffer b = inputBuffer(stride * height);
        int base = b.position();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x += 4) {
            int at = base + y * stride + x / 4 * 5, low = 0;
            for (int i = 0; i < 4; i++) {
                int v = samples[y * width + x + i]; b.put(at + i, (byte) (v >>> 2));
                low |= (v & 3) << (i * 2);
            }
            b.put(at + 4, (byte) low);
        }
        return b.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
    }
    private static ByteBuffer raw16(int[] samples, int width, int height, int stride) {
        ByteBuffer b = inputBuffer(stride * height); int base = b.position();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int v = samples[y * width + x], at = base + y * stride + x * 2;
            b.put(at, (byte) v); b.put(at + 1, (byte) (v >>> 8));
        }
        return b.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
    }
    private static ByteBuffer inputBuffer(int size) {
        ByteBuffer b = ByteBuffer.allocateDirect(size + 23);
        for (int i = 0; i < b.capacity(); i++) b.put(i, (byte) 0xa5);
        b.position(7); b.limit(size + 7); return b;
    }
}
