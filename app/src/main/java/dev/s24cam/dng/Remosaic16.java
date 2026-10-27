package dev.s24cam.dng;

import java.nio.ByteBuffer;

/**
 * Turns a 16-cell ("Tetra²") RAW10 frame into a standard 2x2 Bayer image, row by row.
 *
 * The S24 Ultra's 200MP sensor delivers its full-resolution frame with each colour in a
 * 4x4 block: the 8x8 super-cell is G B / R G in 4x4 blocks. The output uses the same
 * colour order at 2x2 pixel level (G B / R G = GBRG).
 *
 * Where a pixel already has the colour the Bayer grid wants, it is kept as is. Otherwise
 * the pixel's own value is scaled by the local ratio between the wanted colour and its
 * native colour. Those ratios come from per-super-cell colour means, interpolated
 * bilinearly, so luminance keeps full 200MP detail while colour is resolved at about
 * 8 pixels. Colour can bleed a few pixels across sharp colour edges.
 */
public final class Remosaic16 implements RowSource {
    private static final int G = 1, R = 0, B = 2;
    /** Colour of each 2x2 position, row-major: G B / R G. */
    private static final int[] PATTERN = {G, B, R, G};
    /**
     * Where each colour's mean sits inside the 8x8 super-cell, indexed by colour (R, G, B):
     * red's block is centred at row 5.5 / column 1.5, blue's at 1.5 / 5.5, and green's two
     * blocks average to the centre.
     */
    private static final float[] CENTRE_Y = {5.5f, 3.5f, 1.5f}, CENTRE_X = {1.5f, 3.5f, 5.5f};

    private final ByteBuffer buffer;
    private final int stride, width, height, black, white;
    private final int cellsX, cellsY;
    /** Mean of (value - black) per super-cell, per colour: [colour][cy * cellsX + cx]. */
    private final float[][] means = new float[3][];
    private final ThreadLocal<int[]> rowBuffer;
    private final ThreadLocal<byte[]> bytes;

    public Remosaic16(ByteBuffer raw10, int rowStride, int width, int height, int black, int white) {
        if (width % 8 != 0 || height % 8 != 0)
            throw new IllegalArgumentException("16-cell frame size must be a multiple of 8");
        if ((long) (height - 1) * rowStride + width * 5L / 4 > raw10.remaining())
            throw new IllegalArgumentException("Truncated RAW10 buffer");
        this.buffer = raw10.duplicate().slice();
        this.stride = rowStride;
        this.width = width;
        this.height = height;
        this.black = black;
        this.white = white;
        cellsX = width / 8;
        cellsY = height / 8;
        rowBuffer = ThreadLocal.withInitial(() -> new int[width]);
        bytes = ThreadLocal.withInitial(() -> new byte[width * 5 / 4]);
        computeMeans();
    }

    private void computeMeans() {
        for (int c = 0; c < 3; c++) means[c] = new float[cellsX * cellsY];
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<?>> jobs = new java.util.ArrayList<>();
            int band = (cellsY + threads - 1) / threads;
            for (int start = 0; start < cellsY; start += band) {
                final int from = start, to = Math.min(cellsY, start + band);
                jobs.add(pool.submit(() -> meansForCellRows(from, to)));
            }
            for (java.util.concurrent.Future<?> f : jobs) f.get();
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Remosaic statistics failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Fills the means of super-cell rows [from, to); bands never share a cell. */
    private void meansForCellRows(int from, int to) {
        int[] raw = new int[width];
        double[] sum = new double[3 * cellsX];
        for (int cy = from; cy < to; cy++) {
            java.util.Arrays.fill(sum, 0);
            for (int y = cy * 8; y < cy * 8 + 8; y++) {
                unpack(y, raw);
                int rowColour = ((y >> 2) & 1) * 2;
                for (int x = 0; x < width; x++) {
                    int colour = PATTERN[rowColour + ((x >> 2) & 1)];
                    sum[colour * cellsX + (x >> 3)] += raw[x] - black;
                }
            }
            // Each super-cell holds 32 green, 16 red and 16 blue pixels.
            for (int c = 0; c < 3; c++) {
                double n = c == G ? 32 : 16;
                for (int cx = 0; cx < cellsX; cx++)
                    means[c][cy * cellsX + cx] = (float) Math.max(sum[c * cellsX + cx] / n, 0.5);
            }
        }
    }

    private void unpack(int y, int[] out) {
        byte[] b = bytes.get();
        ByteBuffer d = buffer.duplicate();
        d.position(y * stride);
        d.get(b, 0, width * 5 / 4);
        for (int x = 0, g = 0; x < width; x += 4, g += 5) {
            int low = b[g + 4] & 255;
            out[x] = ((b[g] & 255) << 2) | (low & 3);
            out[x + 1] = ((b[g + 1] & 255) << 2) | ((low >> 2) & 3);
            out[x + 2] = ((b[g + 2] & 255) << 2) | ((low >> 4) & 3);
            out[x + 3] = ((b[g + 3] & 255) << 2) | ((low >> 6) & 3);
        }
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }

    @Override
    public int sample(int x, int y) {
        int g = y * stride + (x >> 2) * 5;
        int v = ((buffer.get(g + (x & 3)) & 255) << 2) | ((buffer.get(g + 4) >> ((x & 3) * 2)) & 3);
        return convert(x, y, v);
    }

    /** Thread-safe: the DNG writer encodes tiles in parallel. */
    @Override
    public void row(int y, int[] out) {
        int[] raw = rowBuffer.get();
        unpack(y, raw);
        for (int x = 0; x < width; x++) out[x] = convert(x, y, raw[x]);
    }

    private int convert(int x, int y, int value) {
        int nativeColour = PATTERN[((y >> 2) & 1) * 2 + ((x >> 2) & 1)];
        int wanted = PATTERN[(y & 1) * 2 + (x & 1)];
        if (nativeColour == wanted) return value;
        float ratio = mean(wanted, x, y) / mean(nativeColour, x, y);
        int v = Math.round(black + (value - black) * ratio);
        return Math.max(0, Math.min(white, v));
    }

    private float mean(int colour, int x, int y) {
        return interpolate(means[colour], (x - CENTRE_X[colour]) / 8f, (y - CENTRE_Y[colour]) / 8f);
    }

    /** Bilinear lookup in a super-cell grid; (fx, fy) are in cell units, cell centres at integers. */
    private float interpolate(float[] m, float fx, float fy) {
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float ax = fx - x0, ay = fy - y0;
        int x1 = Math.min(Math.max(x0 + 1, 0), cellsX - 1), y1 = Math.min(Math.max(y0 + 1, 0), cellsY - 1);
        x0 = Math.min(Math.max(x0, 0), cellsX - 1);
        y0 = Math.min(Math.max(y0, 0), cellsY - 1);
        float top = m[y0 * cellsX + x0] * (1 - ax) + m[y0 * cellsX + x1] * ax;
        float bottom = m[y1 * cellsX + x0] * (1 - ax) + m[y1 * cellsX + x1] * ax;
        return top * (1 - ay) + bottom * ay;
    }
}
