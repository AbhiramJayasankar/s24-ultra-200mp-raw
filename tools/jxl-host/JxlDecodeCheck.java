import dev.s24cam.dng.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Optional real JNI decoder + compactor integration check; separate from codec-free unit tests. */
public final class JxlDecodeCheck {
    public static void main(String[] args) throws Exception {
        if(!JxlNative.available()) throw new AssertionError("Host JNI library unavailable");
        Path dir=Path.of(args[0]);Files.createDirectories(dir);int cases=0;
        for(int channels:new int[]{1,3}) for(int range:new int[]{2,1024,65536}) for(int size:new int[]{1,37,256}) {
            // Full-range 256-square inputs below are supplied by an independent encoder.
            // Upstream effort 1 in this MSVC host build crashes encoding those inputs.
            if(range==65536 && size==256) continue;
            int height=size==37?23:size;short[] samples=new short[size*height*channels];
            Random rng=new Random(range+size+channels);for(int i=0;i<samples.length;i++)samples[i]=(short)rng.nextInt(range);
            samples[0]=(short)(range-1);
            for(JxlTileEncoder encoder:new JxlTileEncoder[]{new JxlTileEncoder(0,1,0),JxlTileEncoder.forLinearRaw()}) {
                System.out.printf("check c%d range%d %dx%d effort%d%n",channels,range,size,height,encoder.getEffort());
                byte[] encoded=encoder.encode(samples,size,height,channels,16);
                if(!Arrays.equals(samples,JxlNative.decode(encoded,size,height,channels))) throw new AssertionError("Unscaled round trip failed");
                String stem="decode_c"+channels+"_r"+range+"_"+size+"_e"+encoder.getEffort();
                Files.write(dir.resolve(stem+".jxl"),encoded);
                byte[] raw=new byte[samples.length*2];ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
                Files.write(dir.resolve(stem+".raw"),raw);cases++;
            }
        }
        for(int channels:new int[]{1,3}) {
            Path external=dir.resolve("external_c"+channels);
            if(Files.exists(Path.of(external+".jxl"))) {
                short[] samples=JxlNative.decode(Files.readAllBytes(Path.of(external+".jxl")),256,256,channels);
                byte[] actual=new byte[samples.length*2];ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
                if(!Arrays.equals(actual,Files.readAllBytes(Path.of(external+".raw"))))throw new AssertionError("External full-range decoder case failed");
                cases++;
            }
        }
        byte[] tile=JxlNative.encode(new short[37*23*3],37,23,3,16,0,1,0);
        rejects(()->JxlNative.decode(tile,38,23,3));rejects(()->JxlNative.decode(tile,37,23,1));
        rejects(()->JxlNative.decode(Arrays.copyOf(tile,tile.length-1),37,23,3));
        rejects(()->JxlNative.decode(Arrays.copyOf(tile,tile.length+1),37,23,3));
        rejects(()->JxlNative.decode(new byte[]{(byte)255,10,0,0},37,23,3));
        for(int bits:new int[]{1,9,12}) {
            byte[] low=JxlNative.encode(new short[37*23*3],37,23,3,bits,0,1,0);
            rejects(()->JxlNative.decode(low,37,23,3));
        }
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Callable<short[]>> calls=new ArrayList<>();for(int i=0;i<32;i++)calls.add(()->JxlNative.decode(tile,37,23,3));
            for(Future<short[]> f:pool.invokeAll(calls)) if(!Arrays.equals(new short[37*23*3],f.get())) throw new AssertionError();
        } finally {pool.shutdown();}
        Path before=dir.resolve("native-before.dng"),after=dir.resolve("native-after.dng");Files.deleteIfExists(after);
        DngParams p=new DngParams();p.width=280;p.height=264;p.make="samsung";p.model="SM-S928B";p.software="S24 HighRes host check";
        p.iso=100;p.captureTimeMillis=1792954136655L;p.exposureTimeSec=.01;
        int[] table=new int[1024];for(int i=0;i<table.length;i++)table[i]=(int)Math.round(i*65535.0/1023);
        RowSource rows=new RowSource() {
            public int width(){return p.width;}public int height(){return p.height;}public int channels(){return 3;}
            public int sample(int x,int y){throw new AssertionError();}
            public void row(int y,int[] out){for(int x=0;x<p.width;x++)for(int c=0;c<3;c++)out[x*3+c]=(x*3+y*5+c*11)%1024;}
        };
        try(FileChannel out=FileChannel.open(before,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            LinearDngWriter.write(out,rows,p,table,new JxlTileEncoder(0,1,0));
        }
        DngCompactor.Stats stats;
        try(FileChannel in=FileChannel.open(before,StandardOpenOption.READ)) {
            stats=DngCompactor.compact(in,after.toFile(),DngCompactor.nativeCodec(),()->{},8);
            try(FileChannel out=FileChannel.open(after,StandardOpenOption.READ)) {DngCompactor.verify(in,out,DngCompactor.nativeCodec(),()->{});}
        }
        System.out.printf(Locale.ROOT,"JNI: %d exact round trips, 8 malformed/depth rejects, 32 concurrent decodes; DNG %d -> %d bytes, %d tiles verified twice%n",cases,stats.before,stats.after,stats.tiles);
    }
    static void rejects(Runnable call) {
        try {call.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}
        throw new AssertionError("Malformed codestream accepted");
    }
}
