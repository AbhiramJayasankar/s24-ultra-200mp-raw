package dev.s24cam.dng;

import org.junit.Assume;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class Directional16Test {
    private static final float[] NEUTRAL = {.54296875f, 1, .548828125f};

    @Test public void goldenCrop() throws Exception {
        Path data = DngSampleMain.projectRoot().resolve("bench/data");
        Assume.assumeTrue("Run python bench/make_golden.py", Files.isRegularFile(data.resolve("raw200.npy"))
                && Files.isRegularFile(data.resolve("golden_dir_port_bayer.npy")));
        Npy input = Npy.read(data.resolve("raw200.npy"));
        Npy golden = Npy.read(data.resolve("golden_dir_port_bayer.npy"));
        assertEquals(input.width, golden.width); assertEquals(input.height, golden.height);
        float[] errors = new float[input.values.length]; double sum = 0;
        ByteBuffer packed = pack(input.values, input.width, input.height, 7);
        int position = packed.position(), limit = packed.limit();
        try (Directional16 source = new Directional16(packed, input.width*5/4+7,
                input.width, input.height, 64, 1023, NEUTRAL)) {
            int[] row = new int[input.width];
            for (int y=0; y<input.height; y++) {
                source.row(y,row);
                for(int x=0;x<input.width;x++) {
                    int i=y*input.width+x;
                    errors[i]=Math.abs(row[x]-(64+959*golden.values[i])); sum+=errors[i];
                }
            }
            System.out.println("golden stages="+Arrays.toString(source.stageSeconds()));
        }
        assertEquals(position, packed.position()); assertEquals(limit, packed.limit());
        Arrays.sort(errors);
        double mean=sum/errors.length, p999=errors[(int)(errors.length*.999)];
        System.out.printf("Directional16 golden: mean %.5f DN, p99.9 %.5f DN, max %.5f DN%n",
                mean,p999,errors[errors.length-1]);
        assertTrue("mean="+mean,mean<.5); assertTrue("p99.9="+p999,p999<3);
    }

    @Test public void tinyBandsAndConcurrentRandomRowsEqualWholeCrop() throws Exception {
        Path path=DngSampleMain.projectRoot().resolve("bench/data/raw200.npy");
        Assume.assumeTrue(Files.isRegularFile(path));
        Npy input=Npy.read(path);
        ByteBuffer packed=pack(input.values,input.width,input.height,3);
        int[][] expected=new int[input.height][input.width];
        try(Directional16 whole=new Directional16(packed,input.width*5/4+3,input.width,input.height,
                64,1023,NEUTRAL,input.height)) {
            for(int y=0;y<input.height;y++) whole.row(y,expected[y]);
        }
        try(Directional16 bands=new Directional16(packed,input.width*5/4+3,input.width,input.height,
                64,1023,NEUTRAL,16)) {
            int[][] concurrent=new int[input.height][];
            ExecutorService pool=Executors.newFixedThreadPool(8);
            List<Integer> order=new ArrayList<>();
            for(int y=0;y<input.height;y++) order.add(y);
            Collections.shuffle(order,new Random(62403));
            List<Future<?>> jobs=new ArrayList<>();
            try {
                for(int y:order) jobs.add(pool.submit(()->{
                    int[] row=new int[input.width]; bands.row(y,row);
                    concurrent[y]=row;
                    for(int x=0;x<input.width;x++)
                        assertTrue("band boundary x="+x+", y="+y+": "+row[x]+" vs "+expected[y][x],
                                Math.abs(row[x]-expected[y][x])<=1);
                    // A cached arbitrary sample must use exactly the row pipeline.
                    int x=(y*997)%input.width;
                    assertEquals(row[x],bands.sample(x,y));
                }));
                for(Future<?> job:jobs) job.get();
                // A second pass in order must be byte-for-byte deterministic.
                int[] row=new int[input.width];
                for(int y=0;y<input.height;y++) {
                    bands.row(y,row);
                    assertArrayEquals("random threaded read vs sequential row "+y,concurrent[y],row);
                    for(int x=0;x<input.width;x++) assertTrue(Math.abs(row[x]-expected[y][x])<=1);
                }
                assertEquals((input.height+15)/16,bands.computedBands());
                System.out.println("tiny-band/random-thread stages="+Arrays.toString(bands.stageSeconds()));
            } finally { pool.shutdownNow(); }
        }
    }

    @Test public void constantColourBlackSaturationAndSmallEdges() {
        for(int level:new int[]{0,64,250,1023}) {
            int w=24,h=16; float[] raw=new float[w*h]; Arrays.fill(raw,level);
            ByteBuffer b=pack(raw,w,h,11);
            try(Directional16 source=new Directional16(b,w*5/4+11,w,h,64,1023,
                    new float[]{1,1,1},8)) {
                int[] row=new int[w];
                for(int y=0;y<h;y++) {
                    source.row(y,row);
                    for(int v:row) assertEquals(Math.max(64,level),v,1);
                }
            }
        }
    }

    @Test public void rejectsInvalidInput() {
        ByteBuffer b=ByteBuffer.allocate(16*20);
        assertThrows(IllegalArgumentException.class,()->new Directional16(b,20,15,16,64,1023,NEUTRAL));
        assertThrows(IllegalArgumentException.class,()->new Directional16(b,19,16,16,64,1023,NEUTRAL));
        assertThrows(IllegalArgumentException.class,()->new Directional16(b,20,16,16,64,1024,NEUTRAL));
        assertThrows(IllegalArgumentException.class,()->new Directional16(b,20,16,16,64,1023,new float[]{0,1,1}));
        b.limit(319);
        assertThrows(IllegalArgumentException.class,()->new Directional16(b,20,16,16,64,1023,NEUTRAL));
    }

    /** RAW10 with prefix, row padding, and a nonzero input position. */
    static ByteBuffer pack(float[] values,int width,int height,int padding) {
        int stride=width*5/4+padding;
        ByteBuffer b=ByteBuffer.allocate(17+stride*height+9);
        for(int y=0;y<height;y++) for(int x=0;x<width;x+=4) {
            int at=17+y*stride+x*5/4,low=0;
            for(int j=0;j<4;j++) {
                int v=Math.round(values[y*width+x+j]);
                b.put(at+j,(byte)(v>>2)); low|=(v&3)<<(j*2);
            }
            b.put(at+4,(byte)low);
        }
        b.position(17); b.limit(17+stride*height); return b.asReadOnlyBuffer();
    }

    /** Minimal C-order, little-endian 2-D .npy v1/v2/v3 reader for uint16/float32. */
    static final class Npy {
        final int width,height; final float[] values;
        Npy(int width,int height,float[] values) { this.width=width; this.height=height; this.values=values; }
        static Npy read(Path path) throws Exception {
            ByteBuffer b=ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic=new byte[6]; b.get(magic);
            if(!Arrays.equals(magic,new byte[]{(byte)147,'N','U','M','P','Y'})) throw new IllegalArgumentException("npy magic");
            int version=b.get()&255; b.get();
            int length=version==1?b.getShort()&65535:b.getInt();
            byte[] header=new byte[length]; b.get(header);
            String text=new String(header,StandardCharsets.US_ASCII);
            if(!text.contains("'fortran_order': False")) throw new IllegalArgumentException("C-order required");
            Matcher shape=Pattern.compile("'shape': \\((\\d+),\\s*(\\d+)\\s*,?\\)").matcher(text);
            if(!shape.find()) throw new IllegalArgumentException("2D shape required: "+text);
            int h=Integer.parseInt(shape.group(1)),w=Integer.parseInt(shape.group(2));
            float[] values=new float[Math.multiplyExact(w,h)];
            boolean u2=text.contains("'<u2'"),f4=text.contains("'<f4'");
            if(!u2&&!f4) throw new IllegalArgumentException("Unsupported dtype: "+text);
            for(int i=0;i<values.length;i++) values[i]=u2?b.getShort()&65535:b.getFloat();
            return new Npy(w,h,values);
        }
    }
}
