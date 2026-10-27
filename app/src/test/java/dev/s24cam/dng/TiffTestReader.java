package dev.s24cam.dng;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Independent, deliberately small TIFF + canonical-Huffman SOF3 test decoder. */
final class TiffTestReader {
    final byte[] bytes;
    final ByteBuffer b;
    TiffTestReader(byte[] bytes) {
        this.bytes = bytes;
        b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals('I', bytes[0]); assertEquals('I', bytes[1]);
        assertEquals(42, u16(2));
    }
    int u16(int at) { return b.getShort(at) & 65535; }
    long u32(int at) { return b.getInt(at) & 0xffffffffL; }
    Map<Integer, Tag> root() { return ifd((int) u32(4)); }
    Map<Integer, Tag> ifd(int at) {
        assertEquals(0, at & 1);
        Map<Integer, Tag> tags = new HashMap<>();
        int n = u16(at), previous = -1;
        for (int i = 0; i < n; i++) {
            int entry = at + 2 + i * 12, id = u16(entry), type = u16(entry + 2);
            int count = (int) u32(entry + 4);
            int size = type == 1 || type == 2 ? 1 : type == 3 ? 2 : type == 4 || type == 11 ? 4 : 8;
            int data = count * size <= 4 ? entry + 8 : (int) u32(entry + 8);
            assertTrue(id > previous);
            assertTrue(data >= 0 && data + (long) count * size <= bytes.length);
            if (count * size > 4) assertEquals(0, data & 1);
            tags.put(id, new Tag(type, count, data));
            previous = id;
        }
        assertEquals(0, u32(at + 2 + n * 12));
        return tags;
    }
    final class Tag {
        final int type, count, at;
        Tag(int type, int count, int at) { this.type = type; this.count = count; this.at = at; }
        long[] integers() {
            long[] a = new long[count];
            for (int i = 0; i < count; i++)
                a[i] = type == 1 ? bytes[at + i] & 255 : type == 3 ? u16(at + i * 2) : u32(at + i * 4);
            return a;
        }
        int first() { return (int) integers()[0]; }
        double[] rationals() {
            assertTrue(type == 5 || type == 10);
            double[] a = new double[count];
            for (int i = 0; i < count; i++) {
                long num = type == 10 ? b.getInt(at + i * 8) : u32(at + i * 8);
                long den = type == 10 ? b.getInt(at + i * 8 + 4) : u32(at + i * 8 + 4);
                assertTrue(den > 0);
                a[i] = num / (double) den;
            }
            return a;
        }
        String text() {
            assertEquals(2, type);
            assertEquals(0, bytes[at + count - 1]);
            return new String(bytes, at, count - 1, StandardCharsets.US_ASCII);
        }
    }
    int[] raw() {
        assertEquals(4, root().get(330).type); assertEquals(1, root().get(330).count);
        Map<Integer, Tag> raw = ifd(root().get(330).first());
        int width = raw.get(256).first(), height = raw.get(257).first();
        boolean tiled = raw.get(259).first() == 7;
        if (tiled) {
            // Lossless JPEG is stored as full-width tiles; the last tile is padded.
            assertEquals(width, raw.get(322).first());
            assertTrue(!raw.containsKey(273) && !raw.containsKey(279));
        }
        int rows = raw.get(tiled ? 323 : 278).first();
        int offsetTag = tiled ? 324 : 273, countTag = tiled ? 325 : 279;
        long[] offsets = raw.get(offsetTag).integers(), counts = raw.get(countTag).integers();
        assertEquals(4, raw.get(offsetTag).type); assertEquals(4, raw.get(countTag).type);
        assertEquals((height + rows - 1) / rows, offsets.length);
        assertEquals(offsets.length, counts.length);
        int[] image = new int[width * height];
        for (int strip = 0; strip < offsets.length; strip++) {
            int at = (int) offsets[strip], h = Math.min(rows, height - strip * rows);
            assertEquals(0, at & 1);
            assertTrue(at + counts[strip] <= bytes.length);
            if (strip != 0) assertTrue(offsets[strip] >= offsets[strip - 1] + counts[strip - 1]);
            int[] samples;
            if (!tiled) {
                assertEquals(width * h * 2, counts[strip]);
                samples = new int[width * h];
                for (int i = 0; i < samples.length; i++) samples[i] = u16(at + i * 2);
            } else samples = new Jpeg(bytes, at, (int) counts[strip]).decode(width, rows);
            System.arraycopy(samples, 0, image, strip * width * rows, width * h);
        }
        assertEquals(bytes.length, offsets[offsets.length - 1] + counts[counts.length - 1]);
        return image;
    }

    static final class Jpeg {
        final byte[] data;
        final int end;
        int at, precision, width, height, predictor, transform, bitBuffer, bits, channels;
        final Map<Integer, Integer> codes = new HashMap<>();
        Jpeg(byte[] data, int start, int length) { this.data = data; at = start; end = start + length; }
        int next() { assertTrue(at < end); return data[at++] & 255; }
        int word() { return next() * 256 + next(); }
        int[] decode(int expectedWidth, int expectedHeight) {
            return decode(expectedWidth,expectedHeight,1);
        }
        int[] decode(int expectedWidth,int expectedHeight,int expectedChannels) {
            assertEquals(0xffd8, word());
            boolean scan = false;
            while (!scan) {
                assertEquals(255, next());
                int marker = next(), length = word(), stop = at + length - 2;
                if (marker == 0xc4) {
                    assertEquals(0, next());
                    int[] counts = new int[17];
                    for (int i = 1; i <= 16; i++) counts[i] = next();
                    int code = 0;
                    for (int len = 1; len <= 16; len++) {
                        for (int i = 0; i < counts[len]; i++)
                            codes.put((1 << len) | code++, next());
                        code <<= 1;
                    }
                } else if (marker == 0xc3) {
                    precision = next(); height = word(); width = word();
                    channels=next();assertEquals(expectedChannels,channels);
                    for(int c=1;c<=channels;c++) {assertEquals(c,next());assertEquals(0x11,next());assertEquals(0,next());}
                } else if (marker == 0xda) {
                    assertEquals(channels,next());
                    for(int c=1;c<=channels;c++){assertEquals(c,next());assertEquals(0,next());}
                    predictor = next(); assertEquals(0, next()); transform = next(); scan = true;
                } else fail("Unexpected JPEG marker " + marker);
                assertEquals(stop, at);
            }
            assertEquals(expectedWidth, width); assertEquals(expectedHeight, height);
            assertEquals(16, precision); assertEquals(1, predictor); assertEquals(0, transform);
            assertFalse(codes.isEmpty());
            int[] samples = new int[width * height * channels];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) for(int c=0;c<channels;c++) {
                int category = symbol(), diff;
                if (category == 16) diff = -32768;
                else {
                    diff = read(category);
                    if (category > 0 && diff < (1 << (category - 1))) diff -= (1 << category) - 1;
                }
                int prediction = x > 0 ? samples[(y * width + x - 1)*channels+c]
                        : y > 0 ? samples[(y - 1) * width*channels+c] : 1 << (precision - 1);
                samples[(y * width + x)*channels+c] = (prediction + diff) & 65535;
            }
            // JPEG pads the entropy segment with one bits, then EOI.
            assertEquals((1 << bits) - 1, bitBuffer & ((1 << bits) - 1));
            assertEquals(0xffd9, word());
            assertEquals(end, at);
            return samples;
        }
        int symbol() {
            int code = 1;
            for (int length = 1; length <= 16; length++) {
                code = (code << 1) | read(1);
                Integer symbol = codes.get(code);
                if (symbol != null) return symbol;
            }
            throw new AssertionError("Invalid Huffman code");
        }
        int read(int n) {
            int value = 0;
            for (int i = 0; i < n; i++) {
                if (bits == 0) {
                    bitBuffer = next(); bits = 8;
                    if (bitBuffer == 255) assertEquals(0, next());
                }
                value = (value << 1) | ((bitBuffer >>> --bits) & 1);
            }
            return value;
        }
    }
}
