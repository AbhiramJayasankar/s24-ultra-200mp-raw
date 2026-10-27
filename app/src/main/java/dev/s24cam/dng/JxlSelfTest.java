package dev.s24cam.dng;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Random;

/** On-device encode benchmark; matching raw files permit independent lossless decode checks. */
public final class JxlSelfTest {
    private JxlSelfTest() {}

    /** Writes 48 RGB codestream/raw pairs and returns the contents of results.txt. */
    public static String run(File dir) throws IOException {
        if (dir == null) throw new IOException("External files directory is unavailable");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        if (!JxlNative.available()) throw new IllegalStateException("s24jxl is unavailable");
        StringBuilder summary = new StringBuilder(
                "JPEG XL lossless; distance=0; decodingSpeed=0; channels=3; raw=uint16 little-endian\n"
                + "Times cover encode only (no file I/O), one measurement after warmup.\n"
                + "name,width,height,channels,bits,effort,raw_bytes,jxl_bytes,bits_per_sample,ms,ms_per_megapixel\n");
        // Warm up class loading and native dispatch, outside measurements.
        JxlNative.encode(new short[32 * 32 * 3], 32, 32, 3, 12, 0, 1, 0);
        File results = new File(dir, "results.txt");
        Files.write(results.toPath(), summary.toString().getBytes(StandardCharsets.UTF_8));
        for (int size : new int[]{256, 512}) {
            for (int pattern = 0; pattern < 6; pattern++) {
                int bits = pattern < 4 ? 9 + pattern : 12;
                String name = pattern < 4 ? "noise" : pattern == 4 ? "gradient" : "scene";
                short[] samples = samples(size, bits, pattern);
                byte[] raw = new byte[samples.length * 2];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
                for (int effort : new int[]{1, 2, 3, 7}) {
                    String stem = name + "_" + size + "_b" + bits + "_e" + effort;
                    long start = System.nanoTime();
                    byte[] jxl = new JxlTileEncoder(0, effort, 0).encode(samples, size, size, 3, bits);
                    double ms = (System.nanoTime() - start) / 1_000_000.0;
                    if (jxl.length < 2 || (jxl[0] & 255) != 0xff || (jxl[1] & 255) != 0x0a) {
                        throw new IOException("Expected bare JXL codestream: " + stem);
                    }
                    Files.write(new File(dir, stem + ".raw").toPath(), raw);
                    Files.write(new File(dir, stem + ".jxl").toPath(), jxl);
                    String row = String.format(Locale.ROOT,
                            "%s,%d,%d,3,%d,%d,%d,%d,%.4f,%.3f,%.3f%n",
                            stem, size, size, bits, effort, raw.length, jxl.length,
                            jxl.length * 8.0 / samples.length, ms, ms * 1_000_000.0 / (size * size));
                    summary.append(row);
                    // Preserve completed results if a later tile fails or the process stops.
                    Files.write(results.toPath(), row.getBytes(StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.APPEND);
                }
            }
        }
        return summary.toString();
    }

    private static short[] samples(int size, int bits, int pattern) {
        int max = (1 << bits) - 1;
        Random random = new Random(0x524157L + size * 31L + pattern);
        short[] samples = new short[size * size * 3];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            double nx = x / (double) (size - 1), ny = y / (double) (size - 1);
            for (int c = 0; c < 3; c++) {
                int value;
                if (pattern < 4) {
                    value = random.nextInt(max + 1);
                } else if (pattern == 4) {
                    value = (int) Math.round(max * (c == 0 ? nx : c == 1 ? ny : (nx + ny) * 0.5));
                } else {
                    // Vignetted illumination, channel gains, textured detail, edges and shot noise.
                    double illumination = 0.15 + 0.5 * nx + 0.15 * ny;
                    double texture = 0.045 * Math.sin(x * 0.21) * Math.cos(y * 0.17);
                    double object = nx > 0.25 && nx < 0.65 && ny > 0.3 && ny < 0.8 ? 0.16 : 0;
                    double vignette = 1 - 0.5 * ((nx - 0.5) * (nx - 0.5) + (ny - 0.5) * (ny - 0.5));
                    double signal = (illumination + texture + object) * vignette * (c == 0 ? 0.8 : c == 1 ? 1 : 0.65);
                    value = (int) Math.round(64 + (max - 64) * signal + random.nextGaussian() * Math.sqrt(max * signal) * 0.3);
                }
                samples[(y * size + x) * 3 + c] = (short) Math.max(0, Math.min(max, value));
            }
        }
        return samples;
    }
}
