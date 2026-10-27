package dev.s24cam.dng;

import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Full external capture is mapped in place. Gradle lumdir16Bench, -Xmx280m. */
public final class Lumdir16Main {
    public static void main(String[] args)throws Exception {
        // --java disables native Directional/chroma only, so the median/encoder stay identical.
        String outputName=null;for(String arg:args){if(arg.equals("--java"))System.setProperty("s24cam.lumdir.java","true");
            else if(arg.equals("--native"))System.clearProperty("s24cam.lumdir.java");
            else if(arg.equals("--serial"))System.setProperty("s24cam.lumdir.serial","true");
            else if(arg.equals("--pipeline"))System.clearProperty("s24cam.lumdir.serial");
            else if(arg.equals("--serial-setup"))System.setProperty("s24cam.lumdir.serialSetup","true");
            else if(arg.equals("--barriers"))System.setProperty("s24cam.lumdir.barriers","true");
            else if(arg.startsWith("--compute="))System.setProperty("s24cam.lumdir.computeThreads",arg.substring(10));
            else if(arg.startsWith("--directional="))System.setProperty("s24cam.lumdir.directionalThreads",arg.substring(14));
            else if(arg.startsWith("--encoders="))System.setProperty("s24cam.dng.encoderThreads",arg.substring(11));
            else if(arg.startsWith("--"))throw new IllegalArgumentException(arg);else outputName=arg;}
        int w=16320,h=12240;Path input=Path.of("bench/data/full_16320x12240_rs20400.raw10");
        Path output=outputName==null?DngSampleMain.projectRoot().resolve("build/lumdir200.dng"):Path.of(outputName);
        Files.createDirectories(output.toAbsolutePath().getParent());
        AtomicBoolean running=new AtomicBoolean(true);AtomicLong peakHeap=new AtomicLong();
        Thread monitor=new Thread(()->{while(running.get()) {
            peakHeap.accumulateAndGet(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),Math::max);
            try{Thread.sleep(5);}catch(InterruptedException e){break;}
        }},"Heap monitor");monitor.setDaemon(true);monitor.start();
        long cpuStarted=cpuNanos(),started=System.nanoTime();
        try(FileChannel inputChannel=FileChannel.open(input)) {
            ByteBuffer raw=inputChannel.map(FileChannel.MapMode.READ_ONLY,0,inputChannel.size());
            ByteBuffer[] stores=new ByteBuffer[4];for(int i=0;i<4;i++)stores[i]=ByteBuffer.allocateDirect(Lumdir16.storeBytes(w,h,i));
            long allocated=System.nanoTime();
            Lumdir16 source=new Lumdir16(raw,20400,w,h,64,1023,new float[]{.5f,1,.55f},stores);
            long prepared=System.nanoTime();
            try {
                DngParams p=new DngParams();p.width=w;p.height=h;p.asShotNeutral=new double[]{.5,1,.55};
                p.make="samsung";p.model="SM-S928B";p.uniqueCameraModel="samsung S24 Ultra";p.software="S24Cam lumdir";
                p.colorMatrix1=new double[]{.697265625,-.176757812,-.075195312,-.353515625,1.202148438,.123046875,-.106445312,.30078125,.494140625};p.calibrationIlluminant1=21;
                TileEncoder encoder=LinearDngWriter.createTileEncoder();
                System.out.println("Tile encoder "+encoder.getClass().getSimpleName()+" compression="+encoder.compression()+"; codestream bits=16; native median="+source.nativeMedians()
                        +" directional="+source.nativeDirectional()+" chroma="+source.nativeChroma());
                SeekableByteChannel channel=Files.newByteChannel(output,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);
                LinearDngWriter.Timings writer;long closeNs;
                try{writer=LinearDngWriter.writeWithTimings(channel,source,p,source.linearizationTable(),encoder,new LinearDngWriter.Options(),null);}
                finally{long t=System.nanoTime();channel.close();closeNs=System.nanoTime()-t;}
                long stop=System.nanoTime();double[] stages=source.stageSeconds();
                double cpu=(cpuNanos()-cpuStarted)/1e9,wall=(stop-started)/1e9;
                int cores=Runtime.getRuntime().availableProcessors();
                System.out.printf(Locale.US,"CPU: process %.3f s / wall %.3f s = %.2f busy logical cores; utilisation %.1f%% of %d logical cores%n",
                        cpu,wall,cpu/wall,100*cpu/wall/cores,cores);
                System.out.println("Source work [directional, chroma/store, relative/load, median, codes] s="+Arrays.toString(stages));
                System.out.println("Directional [stats, guide, update completion, bilateral tail, RGB tail] s="+Arrays.toString(source.directionalSeconds())+"; stripe DAG="+source.nativeDag());
                System.out.println("Writer wall "+writer);
                System.out.println("Scheduling: pipeline="+source.pipelined()+" compute="+source.computeThreads()+" directional="+source.directionalThreads()
                        +" encoders="+new LinearDngWriter.Options().encoderThreads+"; producer [work s, wait s, jobs]="+Arrays.toString(source.pipelineStats()));
                double allocation=(allocated-started)/1e9,setup=(prepared-allocated)/1e9,total=(stop-started)/1e9,write=writer.wallSeconds()[6],close=closeNs/1e9;
                System.out.printf(Locale.US,"Wall s: map/allocate %.3f setup %.3f writer %.3f close %.3f other %.3f = %.3f%n",
                        allocation,setup,write,close,total-allocation-setup-write-close,total);
                System.out.printf(Locale.US,"Medians %,d: %.2f ns/median; exact tail fallbacks %,d (%.6f%%)%n",source.medianCount(),stages[3]*1e9/source.medianCount(),source.medianFallbacks(),100.0*source.medianFallbacks()/source.medianCount());
                System.out.printf(Locale.US,"DNG %,d bytes; peak sampled heap %.2f MB (cap %.2f MB); working primitive payload %.2f MB; caller rings %.2f MB; input mapping %.2f MB%n",
                        Files.size(output),peakHeap.get()/1e6,Runtime.getRuntime().maxMemory()/1e6,source.peakWorkingBytes()/1e6,source.storeBytes()/1e6,inputChannel.size()/1e6);
                System.out.printf(Locale.US,"curve a=%.12g b=%.12g entries=%d; JVM %s%n",source.noiseCurve().a,source.noiseCurve().b,source.linearizationTable().length,System.getProperty("java.runtime.version"));
                System.out.printf(Locale.US,"Native reconstruction/chroma workspace %.2f MB (persistent bounded bands); phase stacks <1 MiB across eight workers%n",source.nativeWorkingBytes()/1e6);
            }finally{long t=System.nanoTime();source.close();System.out.printf(Locale.US,"Source close %.4f s%n",(System.nanoTime()-t)/1e9);}
        }finally{running.set(false);monitor.interrupt();monitor.join();}
    }
    /** Reflective: unit tests compile against android.jar, which lacks ProcessHandle. */
    private static long cpuNanos(){
        try{
            Class<?> handle=Class.forName("java.lang.ProcessHandle");
            Object info=handle.getMethod("info").invoke(handle.getMethod("current").invoke(null));
            Object cpu=((java.util.Optional<?>)Class.forName("java.lang.ProcessHandle$Info").getMethod("totalCpuDuration").invoke(info)).orElseThrow();
            return ((java.time.Duration)cpu).toNanos();
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
}
