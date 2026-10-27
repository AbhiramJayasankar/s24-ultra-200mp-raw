package dev.s24cam.dng;

import java.io.OutputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PC benchmark/converter: [raw10 metadata output.dng [scan]]. Use -Xmx350m. */
public final class Directional16Main {
    public static void main(String[] args) throws Exception {
        if(args.length!=0&&args.length!=3&&args.length!=4)
            throw new IllegalArgumentException("Usage: Directional16Main [raw10 metadata output.dng [scan]]");
        Path input=args.length==0?Path.of("captures/"
                +"cam20_sm39_1792964542276_16320x12240_rs20400.raw10"):Path.of(args[0]);
        Path meta=args.length==0?input.resolveSibling("cam20_sm39_1792964542276_meta.txt"):Path.of(args[1]);
        Path output=args.length==0?DngSampleMain.projectRoot().resolve("build/dir200.dng"):Path.of(args[2]);
        Matcher m=Pattern.compile("_(\\d+)x(\\d+)_rs(\\d+)\\.raw10$").matcher(input.toString());
        if(!m.find()) throw new IllegalArgumentException("Dimensions/stride required in filename");
        int w=Integer.parseInt(m.group(1)),h=Integer.parseInt(m.group(2)),stride=Integer.parseInt(m.group(3));
        Properties values=new Properties();
        try(Reader r=Files.newBufferedReader(meta)) { values.load(r); }
        DngParams p=DngSampleMain.fromMetadata(values,w,h); p.cfaPattern=2; p.losslessJpeg=true;
        p.uniqueCameraModel="samsung S24 Ultra cam20"; p.software="S24Cam Directional16";
        Matcher timestamp=Pattern.compile("_(\\d{13})_").matcher(input.toString());
        if(timestamp.find()) p.captureTimeMillis=Long.parseLong(timestamp.group(1));
        float[] neutral=new float[3]; for(int c=0;c<3;c++) neutral[c]=(float)p.asShotNeutral[c];
        Files.createDirectories(output.toAbsolutePath().getParent());
        try(FileChannel channel=FileChannel.open(input)) {
            ByteBuffer buffer=channel.map(FileChannel.MapMode.READ_ONLY,0,channel.size());
            long t=System.nanoTime();
            try(Directional16 source=new Directional16(buffer,stride,w,h,p.blackLevel[0],p.whiteLevel,neutral)) {
                long stats=System.nanoTime();
                // Populate the cache separately so encoding and reconstruction timings are unambiguous.
                int[] row=new int[w]; long checksum=0;
                for(int y=0;y<h;y++) { source.row(y,row); checksum+=row[(y*997)%w]; }
                long converted=System.nanoTime();
                System.out.printf("statistics %.3f s; rows %.3f s; conversion total %.3f s; checksum %d%n",
                        (stats-t)/1e9,(converted-stats)/1e9,(converted-t)/1e9,checksum);
                System.out.println("stages [statistics, initial, updates, bilateral, output] s="
                        +Arrays.toString(source.stageSeconds()));
                System.out.printf("arrays %.2f MB / %.2f MiB; cached %.2f MB; bands %d%n",
                        source.arrayBytes()/1e6,source.arrayBytes()/1048576.,source.cachedBytes()/1e6,source.computedBands());
                System.out.printf("peak class arrays %.2f MB / %.2f MiB%n",source.peakArrayBytes()/1e6,
                        source.peakArrayBytes()/1048576.);
                if(args.length<4||!args[3].equals("scan")) {
                    try(OutputStream out=Files.newOutputStream(output)) { DngWriter.write(out,source,p); }
                    System.out.printf("DNG two-pass encoding/write %.3f s; total %.3f s; %,d bytes; bands %d%n",
                            (System.nanoTime()-converted)/1e9,(System.nanoTime()-t)/1e9,
                            Files.size(output),source.computedBands());
                }
            }
        }
    }
}
