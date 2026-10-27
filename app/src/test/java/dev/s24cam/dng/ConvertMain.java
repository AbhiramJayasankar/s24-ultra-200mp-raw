package dev.s24cam.dng;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a probe dump (..._WxH_rsSTRIDE.raw10|raw16 plus its _meta.txt) to a lossless DNG.
 * args = dump meta out.dng; 16320-wide RAW10 dumps are remosaiced from the 16-cell layout.
 */
public final class ConvertMain {
    public static void main(String[] args) throws Exception {
        Matcher m = Pattern.compile("_(\\d+)x(\\d+)_rs(\\d+)\\.(raw10|raw16)$").matcher(args[0]);
        if (!m.find()) throw new IllegalArgumentException("unexpected file name " + args[0]);
        int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2)), stride = Integer.parseInt(m.group(3));
        boolean raw16 = m.group(4).equals("raw16");
        Properties meta = new Properties();
        try (FileInputStream in = new FileInputStream(args[1])) { meta.load(in); }
        ByteBuffer buf;
        try (FileChannel ch = FileChannel.open(Path.of(args[0]))) {
            buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
        DngParams p = DngSampleMain.fromMetadata(meta, w, h);
        p.losslessJpeg = true;
        try (OutputStream out = new FileOutputStream(args[2])) {
            if (args.length > 3 && args[3].equals("native")) {
                int[] cfa = new int[64];
                for (int i = 0; i < 64; i++) {
                    int by = (i / 8) >> 2, bx = (i % 8) >> 2;
                    cfa[i] = by == bx ? 1 : by == 0 ? 2 : 0;
                }
                p.cfaCustom = cfa;
                p.cfaRows = 8;
                p.cfaCols = 8;
                DngWriter.writeRaw10(out, buf, stride, p);
            } else if (w == 16320 && !raw16) {
                p.cfaPattern = 2;
                DngWriter.write(out, new Remosaic16(buf, stride, w, h, p.blackLevel[0], p.whiteLevel), p);
            } else if (raw16) {
                DngWriter.writeRaw16(out, buf, stride, p);
            } else {
                DngWriter.writeRaw10(out, buf, stride, p);
            }
        }
        System.out.println("wrote " + args[2]);
    }
}
