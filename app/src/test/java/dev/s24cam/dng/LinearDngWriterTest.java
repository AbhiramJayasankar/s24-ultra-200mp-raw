package dev.s24cam.dng;

import org.junit.Test;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LinearDngWriterTest {
    @Test public void lj92RoundTripsAllCategoriesAndComponents() {
        TileEncoder encoder=new Ljpeg92TileEncoder();
        for(int channels:new int[]{1,3,4})for(int width:new int[]{1,7,256}) {
            int height=17;short[] values=new short[width*height*channels];Random rng=new Random(71+channels+width);
            for(int i=0;i<values.length;i++) values[i]=(short)(i%5==0?0:i%5==1?65535:i%5==2?32768:rng.nextInt(65536));
            byte[] encoded=encoder.encode(values,width,height,channels,16);
            int[] decoded=new TiffTestReader.Jpeg(encoded,0,encoded.length).decode(width,height,channels);
            for(int i=0;i<values.length;i++)assertEquals(values[i]&65535,decoded[i]);
        }
        short[] flat=new short[256*256*3];Arrays.fill(flat,(short)257);
        assertTrue(encoder.encode(flat,256,256,3,16).length<flat.length/6);
    }
    @Test public void optimalLengthLimitedHuffmanReservesAllOnes() {
        long[] hist=new long[17];hist[0]=1000;hist[1]=100;hist[2]=10;hist[3]=1;
        Ljpeg92TileEncoder.Huffman h=new Ljpeg92TileEncoder.Huffman(hist);
        assertEquals(1,h.lengths[0]);assertEquals(2,h.lengths[1]);assertEquals(3,h.lengths[2]);assertEquals(4,h.lengths[3]);
        hist[0]=1;hist[1]=1;for(int i=2;i<17;i++)hist[i]=hist[i-1]+hist[i-2];
        h=new Ljpeg92TileEncoder.Huffman(hist);double kraft=0;
        for(int i=0;i<17;i++) {assertTrue(h.lengths[i]>=1&&h.lengths[i]<=16);assertNotEquals((1<<h.lengths[i])-1,h.codes[i]);kraft+=Math.scalb(1,-h.lengths[i]);}
        assertTrue(kraft<1);
    }
    @Test public void streamingDngHasColourMetadataCurvePaddedTilesAndOneReadPass()throws Exception {
        DngParams p=DngWriterTest.params(2,true);p.width=280;p.height=264;
        AtomicInteger rows=new AtomicInteger(),encodes=new AtomicInteger();
        RowSource source=new RowSource(){
            public int width(){return p.width;}public int height(){return p.height;}public int channels(){return 3;}
            public int sample(int x,int y){throw new AssertionError("Thumbnail must be accumulated in the row pass");}
            public void row(int y,int[] out){assertEquals(rows.getAndIncrement(),y);for(int x=0;x<p.width;x++)for(int c=0;c<3;c++)out[x*3+c]=value(x,y,c);}
        };
        NoiseCurve curve=new NoiseCurve(.0001,.00001);
        TileEncoder encoder=new TileEncoder(){
            final Ljpeg92TileEncoder inner=new Ljpeg92TileEncoder();
            public int compression(){return 7;}
            public byte[] encode(short[] v,int w,int h,int c,int b){encodes.incrementAndGet();return inner.encode(v,w,h,c,b);}
        };
        Path path=temp();
        try {
            try(SeekableByteChannel f=Files.newByteChannel(path,StandardOpenOption.WRITE)) {
                LinearDngWriter.write(f,source,p,curve.linearizationTable(),encoder);
                assertTrue(f.isOpen());assertEquals(f.size(),f.position());
            }
            assertEquals(p.height,rows.get());assertEquals(4,encodes.get());
            TiffTestReader t=new TiffTestReader(Files.readAllBytes(path));Map<Integer,TiffTestReader.Tag> root=t.root(),raw=t.ifd(root.get(330).first());
            assertEquals(1,root.get(254).first());assertArrayEquals(new long[]{8,8,8},root.get(258).integers());
            assertArrayEquals(new long[]{1,4,0,0},root.get(50706).integers());assertArrayEquals(new long[]{1,1,0,0},root.get(50707).integers());
            for(int tag:new int[]{50721,50722,50723,50724,50964,50965,50728,50778,50779,34665,271,272,305,274})assertTrue("tag "+tag,root.containsKey(tag));
            assertArrayEquals(p.asShotNeutral,root.get(50728).rationals(),1e-8);
            assertEquals(34892,raw.get(262).first());assertEquals(3,raw.get(277).first());assertArrayEquals(new long[]{16,16,16},raw.get(258).integers());
            assertArrayEquals(new long[]{0,0,0},raw.get(50714).integers());assertArrayEquals(new long[]{65535,65535,65535},raw.get(50717).integers());
            assertFalse(raw.containsKey(33422));assertEquals(curve.linearizationTable().length,raw.get(50712).count);
            long[] offsets=raw.get(324).integers(),counts=raw.get(325).integers();
            long[][] extents=new long[4][2];
            for(int tile=0;tile<4;tile++) {
                assertTrue(offsets[tile]>=8);assertEquals(0,offsets[tile]&1);assertTrue(offsets[tile]>=root.get(273).first()+root.get(279).first());
                assertTrue(offsets[tile]+counts[tile]<=t.bytes.length);
                int[] decoded=new TiffTestReader.Jpeg(t.bytes,(int)offsets[tile],(int)counts[tile]).decode(256,256,3);
                for(int y=0;y<256;y++)for(int x=0;x<256;x++)for(int c=0;c<3;c++)
                    assertEquals(value(Math.min(p.width-1,(tile%2)*256+x),Math.min(p.height-1,(tile/2)*256+y),c),decoded[(y*256+x)*3+c]);
                extents[tile]=new long[]{offsets[tile],offsets[tile]+counts[tile]};
            }
            Arrays.sort(extents,Comparator.comparingLong(a->a[0]));for(int i=1;i<4;i++)assertTrue(extents[i][0]>=extents[i-1][1]);
            assertEquals(8,t.u32(4)); // Metadata is a bounded prefix, before all tile payloads.
        }finally {Files.deleteIfExists(path);}
    }
    private static int value(int x,int y,int c){return (x+y*7+c*11)%200;}
    @Test public void jpegXlVersionAndTypedSettingsAreConditional()throws Exception {
        DngParams p=DngWriterTest.params(0,true);p.width=16;p.height=16;
        RowSource source=new RowSource(){public int width(){return 16;}public int height(){return 16;}public int channels(){return 3;}
            public int sample(int x,int y){return 0;}public void row(int y,int[] out){Arrays.fill(out,0);}};
        TileEncoder encoder=new TileEncoder(){public int compression(){return 52546;}public byte[] encode(short[] s,int w,int h,int c,int b){return new byte[]{1,2,3};}};
        Path path=temp();try{
            LinearDngWriter.Options options=new LinearDngWriter.Options();options.jxlDistance=.5f;options.jxlEffort=4;options.jxlDecodeSpeed=2;
            try(SeekableByteChannel f=Files.newByteChannel(path,StandardOpenOption.WRITE)){LinearDngWriter.write(f,source,p,new int[]{0,65535},encoder,options,null);}
            TiffTestReader t=new TiffTestReader(Files.readAllBytes(path));Map<Integer,TiffTestReader.Tag> root=t.root(),raw=t.ifd(root.get(330).first());
            assertArrayEquals(new long[]{1,7,0,0},root.get(50706).integers());assertArrayEquals(new long[]{1,7,0,0},root.get(50707).integers());
            assertEquals(52546,raw.get(259).first());assertEquals(11,raw.get(52553).type);assertEquals(.5f,t.b.getFloat(raw.get(52553).at),0);
            assertEquals(4,raw.get(52554).type);assertEquals(4,raw.get(52554).first());assertEquals(2,raw.get(52555).first());
        }finally{Files.deleteIfExists(path);}
    }
    @Test public void nextBandIsProducedDuringEncodingAndWallBucketsSum()throws Exception {
        DngParams p=DngWriterTest.params(0,true);p.width=16;p.height=520;
        java.util.concurrent.CountDownLatch encoded=new java.util.concurrent.CountDownLatch(1),advanced=new java.util.concurrent.CountDownLatch(1);
        AtomicInteger rows=new AtomicInteger(),tiles=new AtomicInteger();
        RowSource source=new RowSource(){public int width(){return p.width;}public int height(){return p.height;}public int channels(){return 3;}
            public int sample(int x,int y){throw new AssertionError();}
            public void row(int y,int[] out){assertEquals(rows.getAndIncrement(),y);
                if(y==256){try{assertTrue(encoded.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}advanced.countDown();}
                for(int x=0;x<out.length;x++)out[x]=(y+x)%200;
            }};
        TileEncoder encoder=new TileEncoder(){final Ljpeg92TileEncoder inner=new Ljpeg92TileEncoder();public int compression(){return 7;}
            public byte[] encode(short[] samples,int w,int h,int c,int bits){
                short[] before=samples.clone();encoded.countDown();
                try{assertTrue(advanced.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                assertArrayEquals(before,samples);tiles.incrementAndGet();return inner.encode(samples,w,h,c,bits);
            }};
        Path path=temp();try{
            LinearDngWriter.Timings timing;
            try(SeekableByteChannel out=Files.newByteChannel(path,StandardOpenOption.WRITE)){
                timing=LinearDngWriter.writeWithTimings(out,source,p,new NoiseCurve(.0001,.00001).linearizationTable(),encoder,new LinearDngWriter.Options(),null);
            }
            double[] t=timing.wallSeconds();assertEquals(t[6],t[0]+t[1]+t[2]+t[3]+t[4]+t[5],1e-9);
            assertTrue(timing.writeCalls()<=4);assertEquals(p.height,rows.get());assertEquals(3,tiles.get());
        }finally{Files.deleteIfExists(path);}
    }
    private static Path temp()throws Exception {
        Path build=DngSampleMain.projectRoot().resolve("build");Files.createDirectories(build);return Files.createTempFile(build,"linear-test-",".dng");
    }
}
