package dev.s24cam.dng;

import org.junit.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.Assert.*;

public class Lumdir16Test {
    static ByteBuffer[] stores(int w,int h) {
        ByteBuffer[] b=new ByteBuffer[4];for(int i=0;i<4;i++)b[i]=ByteBuffer.allocateDirect(Lumdir16.storeBytes(w,h,i));return b;
    }
    @Test public void bookGolden()throws Exception {golden("book");}
    @Test public void tileGolden()throws Exception {golden("tile");}
    private void golden(String name)throws Exception {
        Path dir=DngSampleMain.projectRoot().resolve("bench/data/lumdir_ref");
        Assume.assumeTrue("Run python bench/make_lumdir_golden.py",Files.exists(dir.resolve(name+"_codes.npy")));
        Npy dn=new Npy(dir.resolve(name+"_dn.npy")),codes=new Npy(dir.resolve(name+"_codes.npy")),linear=new Npy(dir.resolve(name+"_linear.npy"));
        int h=dn.shape[0],w=dn.shape[1];float[] values=new float[w*h];for(int i=0;i<values.length;i++)values[i]=dn.get(i);
        ByteBuffer packed=Directional16Test.pack(values,w,h,7);values=null;
        Matcher m=Pattern.compile("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").matcher(new String(Files.readAllBytes(dir.resolve(name+"_meta.json")),java.nio.charset.StandardCharsets.UTF_8));
        float[] neutral=new float[3];for(int c=0;c<3;c++){assertTrue(m.find());neutral[c]=Float.parseFloat(m.group());}
        long sum=0,count=(long)w*h*3;int max=0;long[] histogram=new long[65536];double linearMax=0;
        try(Lumdir16 source=new Lumdir16(packed,w*5/4+7,w,h,64,1023,neutral,stores(w,h))) {
            int[] row=new int[w*3],table=source.linearizationTable();
            Npy expectedTable=new Npy(dir.resolve(name+"_curve.npy"));
            System.out.printf("%s noise %.15g %.15g table %d vs %d%n",name,source.noiseCurve().a,source.noiseCurve().b,table.length,expectedTable.shape[0]);
            assertEquals(expectedTable.shape[0],table.length);
            for(int i=0;i<table.length;i++)assertEquals(expectedTable.get(i),table[i],1);
            float[] linearRow=new float[w*3];
            for(int y=0;y<h;y++) {
                source.row(y,row);source.linearRow(y,linearRow);
                for(int x=0;x<row.length;x++) {
                    int i=y*w*3+x,e=Math.abs(row[x]-(int)codes.get(i));sum+=e;histogram[e]++;max=Math.max(max,e);
                    linearMax=Math.max(linearMax,Math.abs(linearRow[x]-linear.get(i)));
                }
            }
            System.out.println(name+" stages "+Arrays.toString(source.stageSeconds()));
        }
        long cumulative=0;int p999=0;for(;p999<histogram.length;p999++){cumulative+=histogram[p999];if(cumulative>=Math.ceil(count*.999))break;}
        System.out.printf(Locale.US,"Lumdir %s: mean %.7f codes, p99.9 %d, max %d; linear max %.9f%n",name,sum/(double)count,p999,max,linearMax);
        assertTrue("mean",sum/(double)count<.3);assertTrue("p999="+p999,p999<=2);
    }
    @Test public void tinyBandsAreIdenticalIncludingTrueEdges() {
        int w=80,h=112;float[] raw=new float[w*h];Random rng=new Random(51);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)raw[y*w+x]=180+((x*13+y*17)%300)+rng.nextInt(40);
        ByteBuffer packed=Directional16Test.pack(raw,w,h,3);float[] neutral={.5f,1,.55f};
        int[][] expected=new int[h][w*3];
        try(Lumdir16 full=new Lumdir16(packed,w*5/4+3,w,h,64,1023,neutral,stores(w,h),h)) {
            for(int y=0;y<h;y++)full.row(y,expected[y]);
        }
        try(Lumdir16 small=new Lumdir16(packed,w*5/4+3,w,h,64,1023,neutral,stores(w,h),8)) {
            int[] row=new int[w*3];for(int y=0;y<h;y++){small.row(y,row);assertArrayEquals("row "+y,expected[y],row);}
        }
    }
    @Test public void noiseCurveEndpointsAndBounds() {
        for(double a:new double[]{0,1e-6,.0002,.01})for(double b:new double[]{0,1e-7,.0001}){
            NoiseCurve curve=new NoiseCurve(a,b);int[] t=curve.linearizationTable();
            assertEquals(0,curve.encode(-1));assertTrue(Math.abs(t.length-1-curve.encode(1))<=1);assertEquals(65535,t[t.length-1]);
            assertTrue(t.length<=4096);for(int i=1;i<t.length;i++)assertTrue(t[i]>=t[i-1]);
        }
    }
    @Test public void preparationProgressPreservesBorrowedBuffersAndClosedRowsFail() {
        int w=24,h=16;float[] values=new float[w*h];Arrays.fill(values,250);
        ByteBuffer input=Directional16Test.pack(values,w,h,9);int position=input.position(),limit=input.limit();
        ByteBuffer[] storage=stores(w,h);int[] progress={-1};
        Lumdir16 source=new Lumdir16(input,w*5/4+9,w,h,64,1023,new float[]{1,1,1},storage,p->{
            assertTrue(p>=progress[0]&&p<=100);progress[0]=p;
        });
        try {int[] row=new int[w*3];source.row(0,row);for(int v:row)assertTrue(v>0);}
        finally {source.close();}
        assertEquals(100,progress[0]);assertEquals(position,input.position());assertEquals(limit,input.limit());
        for(ByteBuffer b:storage)assertEquals(0,b.position());
        assertThrows(IllegalStateException.class,()->source.row(0,new int[w*3]));
    }
    @Test public void slidingHistogramMatchesSortedOrderStatisticsIncludingTails() {
        Random random=new Random(17);Lumdir16.Histogram hist=new Lumdir16.Histogram();
        int[] window=new int[144];
        for(float centre:new float[]{1,0,-1,3,-100000,100000}) {
            hist.clear();
            for(int i=0;i<window.length;i++){window[i]=Lumdir16.quantize(centre+random.nextFloat()*.5f);hist.add(window[i],1);}
            for(int step=0;step<1000;step++) {
                int[] sorted=window.clone();Arrays.sort(sorted);assertEquals(sorted[72],hist.median());
                int at=step%144;hist.add(window[at],-1);
                window[at]=Lumdir16.quantize(centre+random.nextFloat()*2-1);hist.add(window[at],1);
            }
        }
    }
    @Test public void compactTailFallbackAndCurveLookupPreserveAccuracy() {
        Random r=new Random(88);
        for(int trial=0;trial<100;trial++) {
            float[] a=new float[144];for(int i=0;i<a.length;i++)a[i]=trial%3==0?-4:r.nextFloat()*30-10;
            float[] sorted=a.clone();Arrays.sort(sorted);assertEquals(sorted[72],Lumdir16.exactMedian(a),0);
        }
        for(double a:new double[]{1e-6,.0001,.01})for(double b:new double[]{1e-8,.0001}) {
            NoiseCurve curve=new NoiseCurve(a,b);
            for(int i=0;i<100000;i++){float v=r.nextFloat();assertTrue(Math.abs(curve.encode(v)-curve.encodeFast(v))<=1);}
        }
    }
    @Test public void nativeMedianMatchesIndependentWindowSortWhenAvailable() {
        if(!LumdirNative.available())return; // Also run explicitly with the host kernel DLL.
        Random random=new Random(109);
        for(int cols:new int[]{1,2,17,256})for(int rows:new int[]{1,2,32}) {
            int pitch=cols+11;short[] values=new short[(rows+11)*pitch],out=new short[rows*cols];
            for(int i=0;i<values.length;i++)values[i]=(short)(i%7==0?0:i%11==0?16383:random.nextInt(16384));
            LumdirNative.median(values,pitch,cols,rows,out);
            int[] sorted=new int[144];
            for(int y=0;y<rows;y++)for(int x=0;x<cols;x++) {
                for(int a=0;a<12;a++)for(int b=0;b<12;b++)sorted[a*12+b]=values[(y+a)*pitch+x+b];
                Arrays.sort(sorted);assertEquals(sorted[72],out[y*cols+x]);
            }
        }
        assertThrows(IllegalArgumentException.class,()->LumdirNative.median(new short[144],11,1,1,new short[1]));
    }
    @Test public void nativeFloatPhaseMatchesOriginalQuantizationAndExactTailSelection() {
        if(!LumdirNative.available())return;
        Random random=new Random(721);
        for(int cols:new int[]{1,2,17,256})for(int rows:new int[]{1,2,32})for(int kind=0;kind<5;kind++) {
            int pitch=cols+11;float[] values=new float[(rows+11)*pitch],out=new float[rows*cols];
            for(int i=0;i<values.length;i++)values[i]=kind==0?random.nextFloat()*5-1:kind==1?-1-random.nextFloat()*8:
                    kind==2?3+random.nextFloat()*50:kind==3?(random.nextInt(16384)-4096+.5f)/4096f:
                    i%2==0?Float.MAX_VALUE:-Float.MAX_VALUE;
            float[] copy=values.clone();long expectedTails=0;
            long tails=LumdirNative.medianFloats(values,pitch,cols,rows,out);
            int[] bins=new int[144];float[] exact=new float[144];
            for(int y=0;y<rows;y++)for(int x=0;x<cols;x++) {
                for(int a=0;a<12;a++)for(int b=0;b<12;b++) {
                    float v=values[(y+a)*pitch+x+b];bins[a*12+b]=Lumdir16.quantize(v);exact[a*12+b]=v;
                }
                Arrays.sort(bins);int q=bins[72];float expected;
                if(q==0||q==16383){expected=Lumdir16.exactMedian(exact);expectedTails++;}
                else expected=Lumdir16.unquantize(q);
                assertEquals("kind "+kind+" x "+x+" y "+y,Float.floatToRawIntBits(expected),Float.floatToRawIntBits(out[y*cols+x]));
            }
            assertEquals(expectedTails,tails);assertArrayEquals(copy,values,0);
        }
        assertThrows(IllegalArgumentException.class,()->LumdirNative.medianFloats(new float[144],11,1,1,new float[1]));
    }
    @Test public void ringWrapAndReverseReadsMatchSequentialBands() {
        int w=24,h=800;float[] raw=new float[w*h];Random r=new Random(81);
        for(int i=0;i<raw.length;i++)raw[i]=100+r.nextInt(600);
        ByteBuffer packed=Directional16Test.pack(raw,w,h,3);float[] neutral={.5f,1,.55f};
        int[][] expected=new int[h][w*3];
        try(Lumdir16 s=new Lumdir16(packed,w*5/4+3,w,h,64,1023,neutral,stores(w,h))){for(int y=0;y<h;y++)s.row(y,expected[y]);}
        try(Lumdir16 s=new Lumdir16(packed,w*5/4+3,w,h,64,1023,neutral,stores(w,h),8)) {
            int[] row=new int[w*3];for(int y=h-1;y>=0;y-=7){s.row(y,row);assertArrayEquals("ring row "+y,expected[y],row);}
        }
        assertTrue(Lumdir16.storeBytes(16320,12240)<40_000_000);
        assertEquals(Lumdir16.Histogram.SIZE-1,Lumdir16.quantize(Float.MAX_VALUE));
        assertEquals(0,Lumdir16.quantize(-Float.MAX_VALUE));
    }
    static final class Npy {
        final int[] shape;final ByteBuffer data;final boolean u2;
        Npy(Path path)throws Exception {
            ByteBuffer b;try(FileChannel f=FileChannel.open(path)){b=f.map(FileChannel.MapMode.READ_ONLY,0,f.size()).order(ByteOrder.LITTLE_ENDIAN);}
            assertEquals((byte)147,b.get());b.position(6);int major=b.get()&255;b.get();
            int n=major==1?b.getShort()&65535:b.getInt();byte[] header=new byte[n];b.get(header);
            String text=new String(header,java.nio.charset.StandardCharsets.US_ASCII);
            Matcher m=Pattern.compile("'shape': \\(([^)]*)\\)").matcher(text);assertTrue(m.find());
            shape=Arrays.stream(m.group(1).split(",")).map(String::trim).filter(s->!s.isEmpty()).mapToInt(Integer::parseInt).toArray();
            assertTrue(text.contains("'fortran_order': False"));u2=text.contains("'<u2'");assertTrue(u2||text.contains("'<f4'"));
            data=b.slice().order(ByteOrder.LITTLE_ENDIAN);
        }
        float get(int index){return u2?data.getShort(index*2)&65535:data.getFloat(index*4);}
    }
}
