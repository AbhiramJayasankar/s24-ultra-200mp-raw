package dev.s24cam.dng;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional manual converter, also invoked by DngSampleTest when the sample exists.
 * Run on the test runtime classpath: dev.s24cam.dng.DngSampleMain
 * [raw10-path metadata-path output-directory]. No Android runtime is needed.
 * Input is memory mapped, not copied onto the Java heap.
 */
public final class DngSampleMain {
    static final Path SAMPLE = Path.of("captures/"
            + "cam56_sm40_1792954136655_8160x6120_rs10240.raw10");
    static final Path META = SAMPLE.resolveSibling("cam56_sm40_1792954136655_meta.txt");
    private DngSampleMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 3)
            throw new IllegalArgumentException("Usage: DngSampleMain [raw10 metadata output-dir]");
        Path input = args.length == 0 ? SAMPLE : Path.of(args[0]);
        Path metadata = args.length == 0 ? META : Path.of(args[1]);
        if (!Files.isRegularFile(input) || !Files.isRegularFile(metadata)) {
            System.out.println("Sample absent; skipping " + input);
            return;
        }
        Path output = args.length == 0 ? projectRoot().resolve("build") : Path.of(args[2]);
        Files.createDirectories(output);
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(metadata)) { values.load(reader); }
        Matcher dimensions = Pattern.compile("_(\\d+)x(\\d+)_rs(\\d+)\\.raw10$")
                .matcher(input.getFileName().toString());
        if (!dimensions.find()) throw new IllegalArgumentException("Sample filename needs dimensions/stride");
        DngParams p = fromMetadata(values, Integer.parseInt(dimensions.group(1)),
                Integer.parseInt(dimensions.group(2)));
        Matcher time = Pattern.compile("_(\\d{13})_").matcher(input.getFileName().toString());
        if (time.find()) p.captureTimeMillis = Long.parseLong(time.group(1));
        int stride = Integer.parseInt(dimensions.group(3));
        try (FileChannel channel = FileChannel.open(input, StandardOpenOption.READ)) {
            ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            for (boolean compressed : new boolean[]{false, true}) {
                p.losslessJpeg = compressed;
                File file = output.resolve(compressed ? "sample50mp_ljpeg.dng" : "sample50mp.dng").toFile();
                long start = System.nanoTime();
                DngWriter.writeRaw10(file, buffer, stride, p);
                System.out.printf("%s: %,d bytes (%.2f s)%n", file.getAbsolutePath(), file.length(),
                        (System.nanoTime() - start) / 1e9);
            }
        }
    }

    static Path projectRoot() throws IOException {
        Path at = Path.of("").toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve("gradlew"))) at = at.getParent();
        if (at == null) throw new IOException("Cannot find project root (gradlew)");
        return at;
    }

    static DngParams fromMetadata(Properties v, int width, int height) {
        DngParams p = new DngParams(); p.width = width; p.height = height;
        p.cfaPattern = (int) number(v, "cfa", 0);
        p.whiteLevel = (int) number(v, "dynamicWhiteLevel", number(v, "whiteLevel", 1023));
        double[] black = array(v, "dynamicBlackLevel");
        if (black == null) black = array(v, "blackLevelPattern");
        if (black != null) for (int i = 0; i < 4; i++) p.blackLevel[i] = (int) Math.round(black[i]);
        p.colorMatrix1 = array(v, "colorTransform1"); p.colorMatrix2 = array(v, "colorTransform2");
        p.forwardMatrix1 = array(v, "forwardMatrix1"); p.forwardMatrix2 = array(v, "forwardMatrix2");
        p.cameraCalibration1 = array(v, "calibration1"); p.cameraCalibration2 = array(v, "calibration2");
        p.calibrationIlluminant1 = (int) number(v, "illuminant1", 0);
        p.calibrationIlluminant2 = (int) number(v, "illuminant2", 0);
        double[] neutral = array(v, "asShotNeutral");
        if (neutral != null) p.asShotNeutral = neutral;
        p.exposureTimeSec = number(v, "exposureTimeNs", 0) / 1e9;
        p.iso = (int) Math.round(number(v, "iso", 0) * number(v, "postRawBoost", 100) / 100);
        p.fNumber = number(v, "aperture", 0); p.focalLengthMm = number(v, "focalLength", 0);
        int angle = (int) number(v, "sensorOrientation", 0);
        p.orientation = angle == 90 ? 6 : angle == 180 ? 3 : angle == 270 ? 8 : 1;
        p.make = "samsung"; p.model = "SM-S928B"; p.uniqueCameraModel = "samsung S24 Ultra cam56";
        p.software = "S24Cam Java DNG sample";
        return p;
    }
    private static double number(Properties v, String key, double fallback) {
        String text = v.getProperty(key);
        return text == null || text.equals("null") ? fallback : rational(text);
    }
    private static double[] array(Properties v, String key) {
        String value = v.getProperty(key);
        if (value == null || value.equals("null")) return null;
        String[] parts = value.replace("[", "").replace("]", "").split(",");
        double[] result = new double[parts.length];
        for (int i = 0; i < result.length; i++) result[i] = rational(parts[i].trim());
        return result;
    }
    private static double rational(String value) {
        String[] fraction = value.split("/");
        return Double.parseDouble(fraction[0]) / (fraction.length == 1 ? 1 : Double.parseDouble(fraction[1]));
    }
}
