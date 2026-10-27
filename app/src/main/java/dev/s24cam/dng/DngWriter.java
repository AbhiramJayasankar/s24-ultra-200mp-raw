package dev.s24cam.dng;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure Java, classic little-endian TIFF/DNG writer. Caller owns the stream and input.
 * Input starts at the buffer's current position; its position, limit and byte order
 * are preserved. Do not change the buffer or metadata until write returns.
 *
 * Extra storage is O(width + height/32), plus a thumbnail of at most 256x256x3
 * bytes and a 64 KiB output buffer. No full-frame sample or compressed buffer is
 * allocated. Compressed output uses a counting pass followed by an identical
 * encoding pass so even a non-seekable OutputStream needs no temporary file.
 */
public final class DngWriter {
    private static final int STRIP_ROWS = 32;
    private static final int[][] CFA = {
            {0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    private DngWriter() {}

    public static void writeRaw10(OutputStream out, ByteBuffer raw10, int rowStride,
                                  DngParams p) throws IOException {
        write(out, new Source(raw10, rowStride, p, true), p);
    }

    public static void writeRaw16(OutputStream out, ByteBuffer raw16, int rowStride,
                                  DngParams p) throws IOException {
        write(out, new Source(raw16, rowStride, p, false), p);
    }

    public static void writeRaw10(File f, ByteBuffer raw10, int rowStride,
                                  DngParams p) throws IOException {
        // Validate before creating/truncating the destination.
        Source source = new Source(raw10, rowStride, p, true);
        try (OutputStream out = new FileOutputStream(f)) { write(out, source, p); }
    }

    public static void writeRaw16(File f, ByteBuffer raw16, int rowStride,
                                  DngParams p) throws IOException {
        Source source = new Source(raw16, rowStride, p, false);
        try (OutputStream out = new FileOutputStream(f)) { write(out, source, p); }
    }

    /** Writes rows produced by any {@link RowSource}, e.g. a remosaic of a 16-cell frame. */
    public static void write(OutputStream destination, RowSource source, DngParams p)
            throws IOException {
        if (source.width() != p.width || source.height() != p.height)
            throw new IllegalArgumentException("RowSource size does not match params");
        validate(p);
        Objects.requireNonNull(destination, "out");
        Thumbnail thumb = new Thumbnail(source, p);
        int strips = (p.height + STRIP_ROWS - 1) / STRIP_ROWS;
        long[] counts = new long[strips];
        long[] offsets = new long[strips];
        int[] row = new int[p.width];
        if (p.losslessJpeg) {
            jpegTiles(source, strips, (i, tile) -> counts[i] = tile.length);
        } else for (int i = 0; i < strips; i++) {
            counts[i] = (long) p.width * Math.min(STRIP_ROWS, p.height - i * STRIP_ROWS) * 2;
        }

        Directory root = new Directory(), raw = new Directory(), exif = new Directory();
        root.longs(254, 1); // NewSubfileType: reduced-resolution image
        root.longs(256, thumb.width);
        root.longs(257, thumb.height);
        root.shorts(258, 8, 8, 8);
        root.shorts(259, 1);
        root.shorts(262, 2); // RGB
        root.longs(273, 0); // thumbnail strip offset, filled after layout
        root.shorts(277, 3);
        root.longs(278, thumb.height);
        root.longs(279, thumb.rgb.length);
        root.rationals(282, false, new double[]{72});
        root.rationals(283, false, new double[]{72});
        root.shorts(284, 1);
        root.shorts(296, 2); // resolution unit: inch
        root.longs(330, 0); // SubIFDs
        cameraMetadata(root, exif, p);

        raw.longs(254, 0);
        raw.longs(256, p.width);
        raw.longs(257, p.height);
        raw.shorts(258, 16);
        raw.shorts(259, p.losslessJpeg ? 7 : 1);
        raw.shorts(262, 32803);
        // LibRaw only reads compressed DNG data laid out as tiles, so lossless JPEG uses
        // full-width tiles (the last one padded by repeating the final row); uncompressed
        // data stays in strips.
        if (p.losslessJpeg) {
            raw.longs(322, p.width);
            raw.longs(323, STRIP_ROWS);
            raw.longs(324, offsets);
            raw.longs(325, counts);
        } else {
            raw.longs(273, offsets);
            raw.longs(278, STRIP_ROWS);
            raw.longs(279, counts);
        }
        raw.shorts(277, 1);
        raw.shorts(284, 1);
        if (p.cfaCustom != null) {
            raw.shorts(33421, p.cfaRows, p.cfaCols);
            raw.bytes(33422, p.cfaCustom);
        } else {
            raw.shorts(33421, 2, 2);
            raw.bytes(33422, CFA[p.cfaPattern]);
        }
        raw.bytes(50710, 0, 1, 2); // CFAPlaneColor
        raw.shorts(50711, 1); // CFALayout: rectangular
        raw.shorts(50713, 2, 2);
        double[] black = new double[4];
        for (int i = 0; i < 4; i++) black[i] = p.blackLevel[i];
        raw.rationals(50714, false, black);
        raw.longs(50717, p.whiteLevel);
        raw.longs(50719, 0, 0);
        raw.longs(50720, p.width, p.height);
        raw.longs(50829, 0, 0, p.height, p.width);

        long rawIfd = 8 + root.size();
        long exifIfd = rawIfd + raw.size();
        long thumbOffset = exifIfd + exif.size();
        long next = thumbOffset + thumb.rgb.length;
        long rawStart = align(next);
        for (int i = 0; i < strips; i++) {
            offsets[i] = rawStart;
            rawStart += counts[i];
            if (i + 1 < strips) rawStart = align(rawStart);
        }
        // Classic TIFF has unsigned 32-bit offsets. Reject overflow before output.
        if (rawStart > 0xffffffffL) throw new IOException("Image exceeds classic TIFF's 4 GiB limit");
        root.longs(273, thumbOffset);
        root.longs(330, rawIfd);
        if (!exif.entries.isEmpty()) root.longs(34665, exifIfd);
        raw.longs(p.losslessJpeg ? 324 : 273, offsets);

        BufferedOutputStream out = new BufferedOutputStream(destination, 65536);
        out.write('I'); out.write('I'); le16(out, 42); le32(out, 8);
        root.write(out, 8);
        raw.write(out, rawIfd);
        exif.write(out, exifIfd);
        out.write(thumb.rgb);
        if (align(next) != next) out.write(0);
        byte[] bytes = p.losslessJpeg ? null : new byte[p.width * 2];
        if (p.losslessJpeg) {
            final BufferedOutputStream sink = out;
            jpegTiles(source, strips, (t, tile) -> {
                sink.write(tile);
                if (t + 1 < strips && (tile.length & 1) != 0) sink.write(0);
            });
        } else for (int i = 0; i < strips; i++) {
            int y = i * STRIP_ROWS, rows = p.losslessJpeg ? STRIP_ROWS : Math.min(STRIP_ROWS, p.height - y);
            if (p.losslessJpeg) jpeg(out, source, y, rows, row);
            else for (int yy = y; yy < y + rows; yy++) {
                source.row(yy, row);
                for (int x = 0; x < p.width; x++) {
                    bytes[x * 2] = (byte) row[x];
                    bytes[x * 2 + 1] = (byte) (row[x] >>> 8);
                }
                out.write(bytes);
            }
            if (i + 1 < strips && (counts[i] & 1) != 0) out.write(0);
        }
        out.flush(); // Never close the caller's stream.
    }

    static void cameraMetadata(Directory root, Directory exif, DngParams p) {
        root.ascii(271,p.make); root.ascii(272,p.model); root.ascii(305,p.software);
        root.shorts(274,p.orientation);
        String date=p.captureTimeMillis>0 ? new SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US)
                .format(new Date(p.captureTimeMillis)) : null;
        root.ascii(306,date);
        root.bytes(50706, 1, 4, 0, 0);
        // Optional forward/profile matrices can be ignored by 1.1 readers.
        // 1.1 also specifies the category-16 JPEG difference convention we use.
        root.bytes(50707, 1, 1, 0, 0);
        root.ascii(50708, p.uniqueCameraModel != null ? p.uniqueCameraModel
                : (p.make == null ? "" : p.make + " ")
                  + (p.model == null ? "Bayer camera" : p.model));
        root.rationals(50721, true, p.colorMatrix1);
        root.rationals(50722, true, p.colorMatrix2);
        root.rationals(50723, true, p.cameraCalibration1);
        root.rationals(50724, true, p.cameraCalibration2);
        root.rationals(50728, false, p.asShotNeutral);
        root.rationals(50730, true, new double[]{0}); // BaselineExposure
        if (p.calibrationIlluminant1 != 0) root.shorts(50778, p.calibrationIlluminant1);
        if (p.calibrationIlluminant2 != 0) root.shorts(50779, p.calibrationIlluminant2);
        root.rationals(50964, true, p.forwardMatrix1);
        root.rationals(50965, true, p.forwardMatrix2);

        if (positive(p.exposureTimeSec)) exif.rationals(33434, false, new double[]{p.exposureTimeSec});
        if (positive(p.fNumber)) exif.rationals(33437, false, new double[]{p.fNumber});
        if (p.iso > 0) {
            exif.shorts(34855, Math.min(p.iso, 65535));
            if (p.iso > 65535) {
                exif.shorts(34864, 3); // SensitivityType: ISO speed
                exif.longs(34867, p.iso);
            }
        }
        exif.ascii(36867, date);
        if (positive(p.focalLengthMm)) exif.rationals(37386, false, new double[]{p.focalLengthMm});
        if (!exif.entries.isEmpty()) root.longs(34665, 0);

    }

    private interface TileSink {
        void accept(int index, byte[] tile) throws IOException;
    }

    /**
     * Encodes lossless-JPEG tiles on all cores and hands them to {@code sink} in file order.
     * Tiles are independent JPEGs, so only a small window of finished tiles is buffered.
     */
    private static void jpegTiles(RowSource source, int tiles, TileSink sink) throws IOException {
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            ArrayDeque<Future<byte[]>> pending = new ArrayDeque<>();
            int next = 0;
            for (int i = 0; i < tiles; i++) {
                while (next < tiles && pending.size() < threads * 2) {
                    final int y = next++ * STRIP_ROWS;
                    pending.add(pool.submit(() -> {
                        ByteArrayOutputStream b = new ByteArrayOutputStream(source.width() * STRIP_ROWS * 3 / 2);
                        jpeg(b, source, y, STRIP_ROWS, new int[source.width()]);
                        return b.toByteArray();
                    }));
                }
                sink.accept(i, pending.poll().get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while encoding", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new IOException(c);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Absolute byte access deliberately ignores the buffer's byte order. */
    private static final class Source implements RowSource {
        final ByteBuffer buffer;
        final int stride, width, height;
        final boolean packed;
        Source(ByteBuffer input, int stride, DngParams p, boolean packed) {
            validate(p);
            if (packed && p.width % 4 != 0)
                throw new IllegalArgumentException("RAW10 width must be a multiple of four");
            long minimum = packed ? (long) p.width * 5 / 4 : (long) p.width * 2;
            long needed = (long) (p.height - 1) * stride + minimum;
            if (stride < minimum || needed > input.remaining())
                throw new IllegalArgumentException("Truncated input or insufficient rowStride");
            buffer = input.duplicate().slice();
            this.stride = stride;
            width = p.width;
            height = p.height;
            this.packed = packed;
        }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public int sample(int x, int y) {
            int base = y * stride;
            if (packed) {
                int group = base + (x / 4) * 5;
                return ((buffer.get(group + x % 4) & 255) << 2)
                        | ((buffer.get(group + 4) >>> ((x % 4) * 2)) & 3);
            }
            int at = base + x * 2;
            return (buffer.get(at) & 255) | ((buffer.get(at + 1) & 255) << 8);
        }
        @Override public void row(int y, int[] values) {
            for (int x = 0; x < width; x++) values[x] = sample(x, y);
        }
    }

    static void validate(DngParams p) {
        Objects.requireNonNull(p, "params");
        if (p.width < 2 || p.width > 65535 || p.height < 2
                || p.height > 65535 || (p.width & 1) != 0 || (p.height & 1) != 0)
            throw new IllegalArgumentException("Bayer dimensions must be even and in 2..65535");
        if (p.cfaCustom != null && (p.cfaRows < 1 || p.cfaCols < 1 || p.cfaRows > 8 || p.cfaCols > 8
                || p.cfaCustom.length != p.cfaRows * p.cfaCols || p.width % p.cfaCols != 0 || p.height % p.cfaRows != 0))
            throw new IllegalArgumentException("Custom CFA must be at most 8x8 and tile the image");
        if (p.cfaPattern < 0 || p.cfaPattern > 3 || p.orientation < 1 || p.orientation > 8)
            throw new IllegalArgumentException("Invalid CFA or TIFF orientation");
        if (p.blackLevel == null || p.blackLevel.length != 4 || p.whiteLevel < 1 || p.whiteLevel > 65535)
            throw new IllegalArgumentException("Invalid black/white levels");
        for (int black : p.blackLevel) if (black < 0 || black >= p.whiteLevel)
            throw new IllegalArgumentException("Black level must be below white level");
        matrix(p.colorMatrix1); matrix(p.colorMatrix2);
        matrix(p.cameraCalibration1); matrix(p.cameraCalibration2);
        matrix(p.forwardMatrix1); matrix(p.forwardMatrix2);
        if (p.asShotNeutral != null) {
            if (p.asShotNeutral.length != 3) throw new IllegalArgumentException("Neutral needs 3 values");
            for (double v : p.asShotNeutral) if (!positive(v))
                throw new IllegalArgumentException("Neutral must be finite and positive");
        }
    }
    private static void matrix(double[] m) {
        if (m == null) return;
        if (m.length != 9) throw new IllegalArgumentException("Matrix needs 9 values");
        for (double v : m) if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite matrix");
    }
    private static boolean positive(double v) { return v > 0 && Double.isFinite(v); }

    private static final class Thumbnail {
        final int width, height;
        final byte[] rgb;
        Thumbnail(RowSource source, DngParams p) {
            double scale = Math.min(1, 512.0 / Math.max(p.width, p.height));
            width = Math.max(1, (int) Math.round(p.width * scale / 2));
            height = Math.max(1, (int) Math.round(p.height * scale / 2));
            rgb = new byte[width * height * 3];
            double[] channels = new double[3];
            int rows = p.cfaCustom != null ? p.cfaRows : 2, cols = p.cfaCustom != null ? p.cfaCols : 2;
            int[] pattern = p.cfaCustom != null ? p.cfaCustom : CFA[p.cfaPattern];
            double[] perColour = new double[3];
            for (int c : pattern) perColour[c]++;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                // Average one whole CFA repeat so every colour is represented.
                int sx = Math.min(p.width - cols, (int) ((x + .5) * p.width / width) / cols * cols);
                int sy = Math.min(p.height - rows, (int) ((y + .5) * p.height / height) / rows * rows);
                channels[0] = channels[1] = channels[2] = 0;
                for (int i = 0; i < rows * cols; i++) {
                    int c = pattern[i];
                    int black = p.cfaCustom != null ? p.blackLevel[0] : p.blackLevel[i];
                    double v = (source.sample(sx + i % cols, sy + i / cols) - black)
                            / (double) (p.whiteLevel - black);
                    channels[c] += v / perColour[c];
                }
                for (int c = 0; c < 3; c++) {
                    double v = channels[c] / (p.asShotNeutral == null ? 1 : p.asShotNeutral[c]);
                    rgb[(y * width + x) * 3 + c] = (byte) Math.round(
                            255 * Math.pow(Math.max(0, Math.min(1, v)), 1 / 2.2));
                }
            }
        }
    }

    /**
     * T.81 SOF3 (process 14 / LJ92), predictor 1, point transform 0, one
     * component, 16-bit precision. Whole-width 32-row strips avoid tile padding
     * ambiguities, keep predictor state tiny, and fit JPEG's 16-bit dimensions
     * for both 8160- and 16320-pixel rows. Each strip is an independent JPEG.
     * A fixed, incomplete canonical DC table uses five bits for categories 0..16;
     * no all-ones code is assigned. Category 16 means -32768 without amplitude
     * bits, per DNG >=1.1 (also used by dcraw/LibRaw). No Adobe component trick
     * or private decoder extension is needed, and all 16 input bits survive.
     */
    private static void jpeg(OutputStream out, RowSource source, int y, int rows, int[] row)
            throws IOException {
        marker(out, 0xd8);
        marker(out, 0xc4); be16(out, 36); out.write(0); // DC table 0
        for (int length = 1; length <= 16; length++) out.write(length == 5 ? 17 : 0);
        for (int category = 0; category <= 16; category++) out.write(category);
        marker(out, 0xc3); be16(out, 11); out.write(16);
        be16(out, rows); be16(out, source.width());
        out.write(1); out.write(1); out.write(0x11); out.write(0);
        marker(out, 0xda); be16(out, 8);
        out.write(1); out.write(1); out.write(0);
        out.write(1); out.write(0); out.write(0);
        Bits bits = new Bits(out);
        int aboveFirst = 32768;
        for (int yy = y; yy < y + rows; yy++) {
            source.row(Math.min(yy, source.height() - 1), row);
            int previous = aboveFirst;
            aboveFirst = row[0];
            for (int sample : row) {
                // Modulo 2^16 prediction difference, then sign-extend.
                int diff = (short) (sample - previous);
                previous = sample;
                int category = diff == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(diff));
                bits.put(category, 5);
                if (category != 0 && category != 16)
                    bits.put(diff < 0 ? diff + (1 << category) - 1 : diff, category);
            }
        }
        bits.finish();
        marker(out, 0xd9);
    }
    private static final class Bits {
        final OutputStream out;
        int pending, count;
        Bits(OutputStream out) { this.out = out; }
        void put(int value, int n) throws IOException {
            pending = (pending << n) | (value & ((1 << n) - 1));
            count += n;
            while (count >= 8) {
                int b = (pending >>> (count - 8)) & 255;
                out.write(b);
                if (b == 255) out.write(0);
                count -= 8;
            }
        }
        void finish() throws IOException { if (count != 0) put((1 << (8 - count)) - 1, 8 - count); }
    }
    private static void marker(OutputStream out, int marker) throws IOException { out.write(255); out.write(marker); }
    private static void be16(OutputStream out, int v) throws IOException { out.write(v >>> 8); out.write(v); }
    private static void le16(OutputStream out, int v) throws IOException { out.write(v); out.write(v >>> 8); }
    private static void le32(OutputStream out, long v) throws IOException {
        for (int i = 0; i < 4; i++) out.write((int) (v >>> (i * 8)) & 255);
    }
    private static long align(long n) { return (n + 1) & ~1L; }

    private static final class Entry {
        final int type, count;
        final byte[] data;
        Entry(int type, int count, byte[] data) { this.type = type; this.count = count; this.data = data; }
    }
    static final class Directory {
        final TreeMap<Integer, Entry> entries = new TreeMap<>();
        void bytes(int tag, int... values) {
            byte[] b = new byte[values.length];
            for (int i = 0; i < b.length; i++) b[i] = (byte) values[i];
            entries.put(tag, new Entry(1, b.length, b));
        }
        void shorts(int tag, int... values) {
            byte[] b = new byte[values.length * 2];
            for (int i = 0; i < values.length; i++) {
                b[2 * i] = (byte) values[i]; b[2 * i + 1] = (byte) (values[i] >>> 8);
            }
            entries.put(tag, new Entry(3, values.length, b));
        }
        void longs(int tag, long... values) {
            byte[] b = new byte[values.length * 4];
            for (int i = 0; i < values.length; i++) put32(b, i * 4, values[i]);
            entries.put(tag, new Entry(4, values.length, b));
        }
        void floats(int tag,float... values) {
            ByteBuffer b=ByteBuffer.allocate(values.length*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(float value:values)b.putFloat(value);
            entries.put(tag,new Entry(11,values.length,b.array()));
        }
        void ascii(int tag, String value) {
            if (value == null || value.isEmpty()) return;
            byte[] b = (value + '\0').getBytes(StandardCharsets.US_ASCII);
            entries.put(tag, new Entry(2, b.length, b));
        }
        void rationals(int tag, boolean signed, double[] values) {
            if (values == null) return;
            byte[] b = new byte[values.length * 8];
            for (int i = 0; i < values.length; i++) {
                double v = values[i], max = signed ? Integer.MAX_VALUE : 0xffffffffL;
                if (!Double.isFinite(v) || (!signed && v < 0) || Math.abs(v) > max)
                    throw new IllegalArgumentException("Unrepresentable rational for tag " + tag);
                long den = (long) Math.min(1_000_000_000, Math.floor(max / Math.max(1, Math.abs(v))));
                long num = Math.round(v * den), gcd = gcd(Math.abs(num), den);
                put32(b, i * 8, num / gcd); put32(b, i * 8 + 4, den / gcd);
            }
            entries.put(tag, new Entry(signed ? 10 : 5, values.length, b));
        }
        long size() {
            long size = 2 + 12L * entries.size() + 4;
            for (Entry e : entries.values()) if (e.data.length > 4) size += align(e.data.length);
            return size;
        }
        void write(OutputStream out, long start) throws IOException {
            le16(out, entries.size());
            long dataAt = start + 2 + entries.size() * 12L + 4;
            for (Map.Entry<Integer, Entry> item : entries.entrySet()) {
                Entry e = item.getValue();
                le16(out, item.getKey()); le16(out, e.type); le32(out, e.count);
                if (e.data.length <= 4) {
                    out.write(e.data);
                    for (int i = e.data.length; i < 4; i++) out.write(0);
                } else { le32(out, dataAt); dataAt += align(e.data.length); }
            }
            le32(out, 0); // no next IFD
            for (Entry e : entries.values()) if (e.data.length > 4) {
                out.write(e.data);
                if ((e.data.length & 1) != 0) out.write(0);
            }
        }
        private static long gcd(long a, long b) {
            while (b != 0) { long t = a % b; a = b; b = t; }
            return a;
        }
        private static void put32(byte[] b, int at, long value) {
            for (int i = 0; i < 4; i++) b[at + i] = (byte) (value >>> (i * 8));
        }
    }
}
