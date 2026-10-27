package dev.s24cam.dng;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-frame benchmark. Run with -Xmx600m; --write/--compare use uint16 LE DN; --profile samples workers. */
public final class Directional16Bench {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path input = Path.of("bench/data/full_16320x12240_rs20400.raw10");
        Path write = null, compare = null;
        boolean profile = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input": input = Path.of(args[++i]); break;
                case "--write": write = Path.of(args[++i]); break;
                case "--compare": compare = Path.of(args[++i]); break;
                case "--profile": profile = true; break;
                default: throw new IllegalArgumentException("Usage: [--input raw10] [--write uint16le] [--compare uint16le] [--profile]");
            }
        }
        int width = 16320, height = 12240;
        AtomicBoolean sampling = new AtomicBoolean(profile);
        Map<String, Integer> samples = new ConcurrentHashMap<>();
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                Thread.getAllStackTraces().forEach((thread, stack) -> {
                    if (thread.getName().equals("Directional16") && thread.getState() == Thread.State.RUNNABLE
                            && stack.length != 0) samples.merge(stack[0].toString(), 1, Integer::sum);
                });
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        });
        sampler.setDaemon(true);
        if (profile) sampler.start();
        try (FileChannel channel = FileChannel.open(input);
             FileChannel output = write == null ? null : FileChannel.open(write,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             FileChannel reference = compare == null ? null : FileChannel.open(compare)) {
            ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            ByteBuffer saved = output == null ? null : ByteBuffer.allocate(width * 2).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer expected = reference == null ? null : reference.map(FileChannel.MapMode.READ_ONLY,
                    0, reference.size()).order(ByteOrder.LITTLE_ENDIAN);
            if (expected != null && expected.remaining() != (long) width * height * 2)
                throw new IllegalArgumentException("Reference must contain one uint16 LE DN per pixel");
            long hash = 0xcbf29ce484222325L, absolute = 0, different = 0;
            int max = 0;
            int[] row = new int[width];
            long begin = System.nanoTime();
            try (Directional16 source = new Directional16(mapped, 20400, width, height,
                    64, 1023, new float[]{.48242f, 1, .59277f})) {
                for (int y = 0; y < height; y++) {
                    source.row(y, row);
                    for (int v : row) {
                        hash = (hash ^ v) * 0x100000001b3L;
                        if (saved != null) saved.putShort((short) v);
                        if (expected != null) {
                            int delta = Math.abs(v - (expected.getShort() & 65535));
                            absolute += delta; max = Math.max(max, delta);
                            if (delta != 0) different++;
                        }
                    }
                    if (saved != null) {
                        saved.flip();
                        while (saved.hasRemaining()) output.write(saved);
                        saved.clear();
                    }
                }
                double wall = (System.nanoTime() - begin) / 1e9;
                System.out.printf("wall %.3f s; checksum %016x; workers <= 8; JVM %s; max heap %,d%n",
                        wall, hash, System.getProperty("java.version"), Runtime.getRuntime().maxMemory());
                System.out.println("stages [stats, guide, update, bilateral, output] s=" + Arrays.toString(source.stageSeconds()));
                System.out.printf("peakArrayBytes %,d; cachedBytes %,d; bands %d%n",
                        source.peakArrayBytes(), source.cachedBytes(), source.computedBands());
                if (expected != null) System.out.printf("baseline difference: max %d DN; mean %.12f DN; changed %,d / %,d%n",
                        max, absolute / ((double) width * height), different, (long) width * height);
            }
        }
        sampling.set(false);
        if (profile) {
            sampler.join();
            samples.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(25).forEach(e -> System.out.println(e.getValue() + " samples: " + e.getKey()));
        }
    }
}
