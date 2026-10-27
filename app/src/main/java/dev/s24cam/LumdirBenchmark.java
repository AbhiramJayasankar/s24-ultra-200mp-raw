package dev.s24cam;

import android.os.Process;
import android.os.SharedMemory;
import dev.s24cam.dng.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, camera-free production save, driven by ProbeActivity. */
final class LumdirBenchmark {
    private LumdirBenchmark() {}
    static void run(File dir, String name, Consumer<String> log) throws Exception {
        if (dir == null) throw new IllegalStateException("External files directory unavailable");
        if (!name.equals(new File(name).getName())) throw new IllegalArgumentException("input must be a file name");
        File input = new File(dir, name), output = new File(dir, name + ".lumdir.dng");
        File metadata = new File(dir, name.replaceFirst("\\.raw10$", "") + ".json");
        if (!metadata.isFile()) metadata = new File(dir, "meta.json");
        JSONObject root = new JSONObject(new String(Files.readAllBytes(metadata.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        JSONObject rawMeta = root.has("raw200") ? root.getJSONObject("raw200") : root;
        // meta.json's raw200.shape describes the extracted book crop, not the
        // full capture. Full-frame dimensions/stride come from the RAW file name.
        Matcher m = Pattern.compile(".*?(\\d+)x(\\d+)_rs(\\d+)\\.raw10").matcher(name);
        if (!m.matches()) throw new IllegalArgumentException("Expected NAME_WIDTHxHEIGHT_rsSTRIDE.raw10");
        int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2)), stride = Integer.parseInt(m.group(3));
        if (w < 16 || h < 16 || w % 8 != 0 || h % 8 != 0 || stride < w * 5 / 4
                || input.length() < (long)(h - 1) * stride + w * 5 / 4 || input.length() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid RAW10 geometry/length");
        int black = rawMeta.getInt("black"), white = rawMeta.getInt("white");
        JSONArray n = rawMeta.getJSONArray("as_shot_neutral");
        float[] neutral = new float[3];
        DngParams p = new DngParams(); p.width = w; p.height = h;
        for (int c = 0; c < 3; c++) { p.asShotNeutral[c] = n.getDouble(c); neutral[c] = (float)p.asShotNeutral[c]; }
        p.make = "samsung"; p.model = "SM-S928B"; p.uniqueCameraModel = "samsung S24 Ultra";
        p.software = "S24 HighRes " + BuildConfig.VERSION_NAME + " lumdirbench";
        p.colorMatrix1 = new double[]{.697265625,-.176757812,-.075195312,-.353515625,1.202148438,.123046875,-.106445312,.30078125,.494140625};
        p.calibrationIlluminant1 = 21;
        p.exposureTimeSec = rawMeta.optDouble("exposure_ns", 0) / 1e9; p.iso = rawMeta.optInt("iso", 0);
        TileEncoder encoder = LinearDngWriter.createTileEncoder();
        LinearDngWriter.Options options = new LinearDngWriter.Options();
        log.accept("LUMDIRBENCH START input=" + input + " metadata=" + metadata + " geometry=" + w + "x" + h
                + " stride=" + stride + " black=" + black + " white=" + white + " neutral=" + Arrays.toString(neutral)
                + " encoder=" + encoder.getClass().getSimpleName() + " compression=" + encoder.compression());
        SharedMemory pixelsMemory = null;
        SharedMemory[] memory = new SharedMemory[4]; ByteBuffer[] stores = new ByteBuffer[4]; ByteBuffer pixels = null;
        long cpuStart = Process.getElapsedCpuTime(), start = System.nanoTime();
        try {
            // As in DngSaver: RAW and caller rings are outside the Java heap.
            pixelsMemory = SharedMemory.create("lumdirbench-raw", (int)input.length()); pixels = pixelsMemory.mapReadWrite();
            try (FileChannel in = FileChannel.open(input.toPath(), StandardOpenOption.READ)) {
                while (pixels.hasRemaining()) if (in.read(pixels) < 0) throw new java.io.EOFException(name);
            }
            pixels.flip();
            for (int i = 0; i < 4; i++) {
                memory[i] = SharedMemory.create("lumdirbench-ring" + i, Lumdir16.storeBytes(w, h, i));
                stores[i] = memory[i].mapReadWrite();
            }
            long allocated = System.nanoTime();
            try (Lumdir16 source = new Lumdir16(pixels, stride, w, h, black, white, neutral, stores)) {
                long prepared = System.nanoTime(); LinearDngWriter.Timings writer;
                try (FileChannel out = FileChannel.open(output.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    writer = LinearDngWriter.writeWithTimings(out, source, p, source.linearizationTable(), encoder, options, null);
                }
                double[] stages = source.stageSeconds();
                log.accept("LUMDIRBENCH source [directional,chroma,relative,median,codes] s=" + Arrays.toString(stages));
                log.accept("LUMDIRBENCH directional [stats,guide,update-complete,bilateral-tail,RGB-tail] s=" + Arrays.toString(source.directionalSeconds()));
                log.accept("LUMDIRBENCH writer " + writer);
                log.accept(String.format(Locale.US, "LUMDIRBENCH setup: read/allocate %.3f source %.3f; median %.2f ns/count tails %d; pipeline=%s DAG=%s workers=%d/%d/%d; producer=%s",
                        (allocated-start)/1e9, (prepared-allocated)/1e9, stages[3]*1e9/source.medianCount(), source.medianFallbacks(),
                        source.pipelined(), source.nativeDag(), source.computeThreads(), source.directionalThreads(), options.encoderThreads, Arrays.toString(source.pipelineStats())));
                log.accept(String.format(Locale.US, "LUMDIRBENCH native median=%s directional=%s chroma=%s; arrays %.2f MiB native-work %.2f MiB rings %.2f MiB",
                        source.nativeMedians(), source.nativeDirectional(), source.nativeChroma(), source.peakWorkingBytes()/1048576., source.nativeWorkingBytes()/1048576., source.storeBytes()/1048576.));
            }
        } finally {
            if (pixels != null) SharedMemory.unmap(pixels);
            if (pixelsMemory != null) pixelsMemory.close();
            for (int i = 0; i < 4; i++) { if (stores[i] != null) SharedMemory.unmap(stores[i]); if (memory[i] != null) memory[i].close(); }
        }
        double wall = (System.nanoTime()-start)/1e9, cpu = (Process.getElapsedCpuTime()-cpuStart)/1e3;
        log.accept(String.format(Locale.US, "LUMDIRBENCH DONE wall %.3f s process CPU %.3f s busy cores %.3f; output %s bytes %d",
                wall, cpu, cpu/wall, output, output.length()));
    }
}
