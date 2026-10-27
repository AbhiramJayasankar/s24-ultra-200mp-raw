import dev.s24cam.dng.JxlNative;
import dev.s24cam.dng.JxlSelfTest;
import dev.s24cam.dng.JxlTileEncoder;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs the same JNI implementation in a host JVM; decode with verify_jxl.py. */
public final class JxlHostCheck {
    public static void main(String[] args) throws Exception {
        if (!JxlNative.available()) throw new AssertionError("Host s24jxl library did not load");
        File dir = new File(args[0]);
        System.out.print(JxlSelfTest.run(dir));
        // The production profile must preserve all unsigned codes and always use
        // 16 codestream bits, even when its input's nominal code precision is low.
        JxlTileEncoder tuned = JxlTileEncoder.forLinearRaw();
        for (int bits : new int[]{1, 9, 10, 11, 12, 16}) for (int channels : new int[]{1, 3}) {
            int w = 37, h = 23;
            short[] samples = new short[w * h * channels];
            Random rng = new Random(900L + bits * 10L + channels);
            for (int i = 0; i < samples.length; i++) samples[i] = (short) rng.nextInt(1 << bits);
            samples[0] = 0;
            samples[1] = (short) ((1 << bits) - 1);
            short[] before = samples.clone();
            writeTuned(dir, tuned, samples, w, h, channels, bits);
            if (!Arrays.equals(samples, before)) throw new AssertionError("Tuned input modified");
        }
        if (args.length > 1) {
            // First tile from extract_jxl_tiles.py's 48-byte-header sample file.
            ByteBuffer raw = ByteBuffer.allocate(256 * 256 * 6).order(ByteOrder.LITTLE_ENDIAN);
            try (FileChannel input = FileChannel.open(Path.of(args[1]))) {
                input.position(48);
                while (raw.hasRemaining()) if (input.read(raw) < 0) throw new AssertionError("Short tile input");
            }
            short[] samples = new short[256 * 256 * 3];
            raw.flip(); raw.asShortBuffer().get(samples);
            writeTuned(dir, tuned, samples, 256, 256, 3, 12);
        }
        // Exercise grayscale, non-square tiles, tiny tiles, 1-bit samples, and unsigned shorts.
        for (int bits : new int[]{1, 9, 10, 11, 12, 16}) for (int channels : new int[]{1, 3}) {
            for (int effort : new int[]{1, 2, 3, 7}) {
                int w = bits == 1 ? 1 : 37, h = bits == 1 ? 1 : 23;
                short[] samples = new short[w * h * channels];
                Random rng = new Random(bits * 100L + channels);
                for (int i = 0; i < samples.length; i++) samples[i] = (short) rng.nextInt(1 << bits);
                samples[0] = (short) ((1 << bits) - 1);
                String stem = "edge_" + w + "x" + h + "_c" + channels + "_b" + bits + "_e" + effort;
                byte[] raw = new byte[samples.length * 2];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
                Files.write(new File(dir, stem + ".raw").toPath(), raw);
                Files.write(new File(dir, stem + ".jxl").toPath(),
                        JxlNative.encode(samples, w, h, channels, bits, 0, effort, 0));
            }
        }
        expectInvalid(() -> JxlNative.encode(new short[]{512}, 1, 1, 1, 9, 0, 1, 0));
        expectInvalid(() -> JxlNative.encode(new short[]{-1}, 1, 1, 1, 12, 0, 1, 0));
        // One shared Java settings object; no shared native state, even across callers.
        JxlTileEncoder encoder = new JxlTileEncoder(0, 3, 0);
        short[] data = new short[97 * 61 * 3];
        Random rng = new Random(123);
        for (int i = 0; i < data.length; i++) data[i] = (short) rng.nextInt(4096);
        byte[] expected = encoder.encode(data, 97, 61, 3, 12);
        short[] before = data.clone();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<byte[]>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) calls.add(() -> encoder.encode(data, 97, 61, 3, 12));
            for (Future<byte[]> result : pool.invokeAll(calls)) {
                if (!Arrays.equals(expected, result.get())) throw new AssertionError("Parallel encode differs");
            }
        } finally { pool.shutdownNow(); }
        if (!Arrays.equals(before, data)) throw new AssertionError("Input modified");
        // Concurrent production calls through one immutable profile instance.
        pool = Executors.newFixedThreadPool(4);
        byte[] tunedExpected = tuned.encode(data, 97, 61, 3, 12);
        try {
            List<Callable<byte[]>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) calls.add(() -> tuned.encode(data, 97, 61, 3, 12));
            for (Future<byte[]> result : pool.invokeAll(calls)) {
                if (!Arrays.equals(tunedExpected, result.get())) throw new AssertionError("Tuned parallel encode differs");
            }
        } finally { pool.shutdownNow(); }
        if (!Arrays.equals(before, data)) throw new AssertionError("Tuned parallel input modified");
        // Check the nonzero distance API and all decoding-speed settings can encode.
        for (int speed = 0; speed <= 4; speed++) {
            byte[] lossy = JxlNative.encode(data, 97, 61, 3, 12, 1, 1, speed);
            Files.write(new File(dir, "lossy_speed" + speed + ".jxl").toPath(), lossy);
        }
        System.out.println("PASS: host JNI validation, unsigned samples, unchanged input, 16 concurrent calls, lossy API");
    }

    private static void writeTuned(File dir, JxlTileEncoder encoder, short[] samples,
                                   int w, int h, int channels, int bits) throws Exception {
        String stem = "tuned_" + w + "x" + h + "_c" + channels + "_b" + bits + "_e" + encoder.getEffort();
        byte[] raw = new byte[samples.length * 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
        Files.write(new File(dir, stem + ".raw").toPath(), raw);
        Files.write(new File(dir, stem + ".jxl").toPath(), encoder.encode(samples, w, h, channels, bits));
    }

    private static void expectInvalid(Runnable call) {
        try { call.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
}
