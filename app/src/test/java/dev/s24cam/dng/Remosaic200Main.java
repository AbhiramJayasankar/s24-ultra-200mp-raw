package dev.s24cam.dng;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Properties;

/** Converts a captured 200MP 16-cell RAW10 dump to a Bayer DNG: args = raw10 meta out.dng */
public final class Remosaic200Main {
    public static void main(String[] args) throws Exception {
        Path raw = Path.of(args[0]);
        Properties meta = new Properties();
        try (FileInputStream in = new FileInputStream(args[1])) { meta.load(in); }
        int w = 16320, h = 12240, stride = 20400;
        ByteBuffer buf;
        try (FileChannel ch = FileChannel.open(raw)) {
            buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
        DngParams p = DngSampleMain.fromMetadata(meta, w, h);
        p.cfaPattern = 2; // GBRG after remosaic
        p.losslessJpeg = args.length < 4 || !args[3].equals("plain");
        long t0 = System.currentTimeMillis();
        Remosaic16 src = new Remosaic16(buf, stride, w, h, p.blackLevel[0], p.whiteLevel);
        long t1 = System.currentTimeMillis();
        try (OutputStream out = new FileOutputStream(args[2])) { DngWriter.write(out, src, p); }
        System.out.println("means " + (t1 - t0) + " ms, write " + (System.currentTimeMillis() - t1) + " ms");
    }
}
