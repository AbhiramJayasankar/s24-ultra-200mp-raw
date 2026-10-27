package dev.s24cam.dng;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.Random;
import static org.junit.Assert.*;

/** Run again with tools/build-lumdir-host.cmd's DLL on java.library.path. */
public class LumdirNativeTest {
    private static boolean available(){
        if(Boolean.getBoolean("s24cam.lumdir.requireNative"))assertTrue("Host libs24lumdir did not load",LumdirNative.available());
        return LumdirNative.available();
    }
    private static void select(boolean java){System.setProperty("s24cam.lumdir.java",Boolean.toString(java));}
    private static void restore(String previous){if(previous==null)System.clearProperty("s24cam.lumdir.java");else System.setProperty("s24cam.lumdir.java",previous);}
    private static ByteBuffer input(int w,int h,int padding,boolean direct) {
        float[] raw=new float[w*h];Random r=new Random(901+w);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)raw[y*w+x]=(x/8+y/8)%5==0?r.nextInt(100):70+r.nextInt(954);
        ByteBuffer packed=Directional16Test.pack(raw,w,h,padding);
        if(!direct)return packed;
        ByteBuffer b=ByteBuffer.allocateDirect(packed.remaining()+37);b.position(19);b.put(packed.duplicate());b.limit(b.position());b.position(19);return b;
    }
    @Test public void nativeRgbIsBitIdenticalToJavaAtEdgesAndBandBoundaries() {
        if(!available())return;
        String previous=System.getProperty("s24cam.lumdir.java");
        try {
            for(int w:new int[]{16,24,80,256})for(boolean direct:new boolean[]{false,true}) {
                int h=320;ByteBuffer raw=input(w,h,11,direct);float[] neutral={.51f,1,.57f};
                select(true);Directional16 java=new Directional16(raw,w*5/4+11,w,h,64,1023,neutral,80,true);
                select(false);
                try(Directional16 nativeSource=new Directional16(raw,w*5/4+11,w,h,64,1023,neutral,80,true);Directional16 ignored=java) {
                    assertTrue(nativeSource.nativeKernels());assertFalse(java.nativeKernels());
                    for(int first:new int[]{0,8,120,240,296,0}) {
                        int last=Math.min(h,first+80),n=(last-first)*w;
                        float[][] a=new float[3][n],b=new float[3][n];java.rgbBand(first,last,a);nativeSource.rgbBand(first,last,b);
                        for(int c=0;c<3;c++)assertArrayEquals("width "+w+" first "+first+" channel "+c,a[c],b[c],0);
                    }
                    assertTrue(nativeSource.nativeBytes()>0);
                }
            }
        }finally{restore(previous);}
    }
    @Test public void nativeChromaAndCodesMatchJavaAcrossRingWrapAndReverseReads() {
        if(!available())return;
        String previous=System.getProperty("s24cam.lumdir.java");
        try {
            int w=80,h=800;ByteBuffer raw=input(w,h,7,true);float[] neutral={.5f,1,.55f};int[][] expected=new int[h][w*3];
            select(true);
            try(Lumdir16 java=new Lumdir16(raw,w*5/4+7,w,h,64,1023,neutral,Lumdir16Test.stores(w,h))) {
                assertFalse(java.nativeDirectional());for(int y=0;y<h;y++)java.row(y,expected[y]);
            }
            select(false);
            ByteBuffer[] stores=new ByteBuffer[4];for(int i=0;i<4;i++){
                stores[i]=ByteBuffer.allocateDirect(Lumdir16.storeBytes(w,h,i)+11);stores[i].position(3);stores[i].limit(stores[i].capacity()-8);
            }
            try(Lumdir16 nativeSource=new Lumdir16(raw,w*5/4+7,w,h,64,1023,neutral,stores,8)) {
                assertTrue(nativeSource.nativeDirectional());assertTrue(nativeSource.nativeChroma());int[] row=new int[w*3];
                for(int y=0;y<h;y++){nativeSource.row(y,row);assertArrayEquals("forward "+y,expected[y],row);}
                for(int y=h-1;y>=0;y-=7){nativeSource.row(y,row);assertArrayEquals("reverse "+y,expected[y],row);}
            }
            for(ByteBuffer store:stores)assertEquals(3,store.position());
        }finally{restore(previous);}
    }
    @Test public void producerAndStripeDependenciesPreserveCodesAcrossWorkerCounts() {
        if(!available())return;
        String[] keys={"s24cam.lumdir.java","s24cam.lumdir.serial","s24cam.lumdir.barriers","s24cam.lumdir.serialSetup",
                "s24cam.lumdir.computeThreads","s24cam.lumdir.directionalThreads"};
        String[] previous=new String[keys.length];for(int i=0;i<keys.length;i++)previous[i]=System.getProperty(keys[i]);
        try {
            int w=128,h=1536;ByteBuffer raw=input(w,h,9,true);float[] neutral={.5f,1,.55f};int[][] expected=new int[h][w*3];
            select(false);System.setProperty(keys[1],"true");System.setProperty(keys[2],"true");System.setProperty(keys[3],"true");
            try(Lumdir16 serial=new Lumdir16(raw,w*5/4+9,w,h,64,1023,neutral,Lumdir16Test.stores(w,h))) {
                assertFalse(serial.pipelined());assertFalse(serial.nativeDag());
                for(int y=0;y<h;y++)serial.row(y,expected[y]);
            }
            System.clearProperty(keys[1]);System.clearProperty(keys[2]);System.clearProperty(keys[3]);
            Thread caller=Thread.currentThread();
            for(int[] workers:new int[][]{{8,8,128},{1,1,128},{3,2,128},{8,6,256}}) {
                System.setProperty(keys[4],Integer.toString(workers[0]));System.setProperty(keys[5],Integer.toString(workers[1]));
                int[] progress={-1};
                try(Lumdir16 piped=new Lumdir16(raw,w*5/4+9,w,h,64,1023,neutral,Lumdir16Test.stores(w,h),workers[2],p->{
                    assertSame("Progress stays on the caller",caller,Thread.currentThread());assertTrue(p>=progress[0]);progress[0]=p;
                })) {
                    assertTrue(piped.pipelined());assertTrue(piped.nativeDag());int[] row=new int[w*3];
                    for(int y=0;y<h;y++){piped.row(y,row);assertArrayEquals("forward "+y,expected[y],row);}
                    double[] jobs=piped.pipelineStats();assertTrue(jobs[2]>0);assertTrue(jobs[2]<=(h+191)/192);
                    for(int y=h-1;y>=0;y-=13){piped.row(y,row);assertArrayEquals("reverse "+y,expected[y],row);}
                    assertEquals(100,progress[0]);
                }
            }
            // Closing after one row must drain any producer before releasing its native arenas.
            Lumdir16 early=new Lumdir16(raw,w*5/4+9,w,h,64,1023,neutral,Lumdir16Test.stores(w,h));
            early.row(0,new int[w*3]);early.close();early.close();
            assertThrows(IllegalStateException.class,()->early.row(0,new int[w*3]));
        }finally{for(int i=0;i<keys.length;i++){if(previous[i]==null)System.clearProperty(keys[i]);else System.setProperty(keys[i],previous[i]);}}
    }
}
