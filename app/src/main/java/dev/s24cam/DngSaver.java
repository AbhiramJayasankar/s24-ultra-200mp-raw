package dev.s24cam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dev.s24cam.dng.DngParams;
import dev.s24cam.dng.DngParamsFactory;
import dev.s24cam.dng.DngWriter;
import dev.s24cam.dng.Lumdir16;
import dev.s24cam.dng.LinearDngWriter;
import dev.s24cam.dng.RowSource;

/** Writes one captured RAW frame as a DNG into Pictures/S24 HighRes. */
final class DngSaver {
    static final String FOLDER = Environment.DIRECTORY_PICTURES + "/S24 HighRes";
    private static final java.util.Set<Uri> WRITING=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static boolean isWriting(Uri uri) { return WRITING.contains(uri); }

    interface Progress {
        void onProgress(int percent);
    }

    static final class Saved {
        final Uri uri;
        final String name;
        final long bytes;
        Saved(Uri uri, String name, long bytes) {
            this.uri = uri;
            this.name = name;
            this.bytes = bytes;
        }
    }

    private DngSaver() {}

    /** Free bytes on the shared storage volume. */
    static long freeBytes() {
        StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        return fs.getAvailableBytes();
    }

    /** Colour layout of the 200MP native frame: 4x4 blocks, G B / R G (0 R, 1 G, 2 B). */
    static int[] sixteenCellPattern() {
        int[] p = new int[64];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int by = y >> 2, bx = x >> 2;
                p[y * 8 + x] = by == bx ? 1 : by == 0 ? 2 : 0;
            }
        return p;
    }

    /**
     * Saves a full-colour lumdir LinearRaw DNG for 200MP; optionally also preserves
     * the untouched 8x8-CFA readout as "_sensor.dng".
     */
    static Saved save(ContentResolver resolver, Mode mode, Image raw, CaptureResult result,
                      CameraCharacteristics chars, boolean keepSensorRaw, Progress progress) throws IOException {
        SaveTimes times=new SaveTimes();
        Image.Plane plane = raw.getPlanes()[0];
        int w = raw.getWidth(), h = raw.getHeight(), stride = plane.getRowStride();
        int orientation = exifOrientation(chars.get(CameraCharacteristics.SENSOR_ORIENTATION));

        // Camera buffers are slow for scattered CPU reads; one bulk copy into ordinary
        // memory makes the conversion and encoder several times faster.
        long t0 = android.os.SystemClock.elapsedRealtime();
        // Android backs direct ByteBuffers with the Java heap (512 MB cap), so frame-sized
        // buffers live in shared memory instead.
        java.nio.ByteBuffer src = plane.getBuffer();
        android.os.SharedMemory pixelMemory = sharedMemory("raw", src.remaining());
        android.os.SharedMemory[] workMemory = new android.os.SharedMemory[4];
        java.nio.ByteBuffer[] workMaps = new java.nio.ByteBuffer[4];
        java.nio.ByteBuffer pixels;
        try {
            pixels = pixelMemory.mapReadWrite();
        } catch (android.system.ErrnoException e) {
            pixelMemory.close();
            throw new IOException("Could not map frame memory: " + e.getMessage(), e);
        }
        try {
        pixels.put(src.duplicate()).flip();
        long t1 = android.os.SystemClock.elapsedRealtime();
        times.copy=System.nanoTime()-times.started;long sensorStart=System.nanoTime();

        String base = "S24HR_" + mode.figure + "MP_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        long sensorBytes = 0;
        if (mode.sixteenCell && keepSensorRaw) {
            DngParams np = params(chars, result, w, h, orientation);
            np.cfaCustom = sixteenCellPattern();
            np.cfaRows = 8;
            np.cfaCols = 8;
            sensorBytes = write(resolver, base + "_sensor.dng", new PackedRaw10(pixels, stride, w, h), np,
                    new CountingSource(null, 2L * h, progress, 0, 35)).bytes;
        }
        long t2 = android.os.SystemClock.elapsedRealtime();
        times.sensor=System.nanoTime()-sensorStart;long setupStart=System.nanoTime();
        DngParams p = params(chars, result, w, h, orientation);
        int from = mode.sixteenCell && keepSensorRaw ? 35 : 0;
        Saved saved;
        if (mode.sixteenCell) {
            float[] neutral = new float[3];
            for (int c = 0; c < 3; c++) neutral[c] = (float) p.asShotNeutral[c];
            for (int i=0;i<4;i++) {
                workMemory[i]=sharedMemory("lumdir-"+i,Lumdir16.storeBytes(w,h,i));
                try { workMaps[i]=workMemory[i].mapReadWrite(); }
                catch(android.system.ErrnoException e) { throw new IOException("Could not map lumdir memory",e); }
            }
            Lumdir16 source=new Lumdir16(pixels,stride,w,h,p.blackLevel[0],p.whiteLevel,neutral,workMaps);
            times.setup=System.nanoTime()-setupStart;
            try {
                saved=writeLinear(resolver,base+".dng",source,p,progress,from,times);
                double[] st=source.stageSeconds();
                double[] pipeline=source.pipelineStats();
                android.util.Log.i(SamsungCam.TAG,String.format(Locale.US,
                        "Lumdir scheduling: pipeline=%s stripe-DAG=%s compute=%d directional=%d encoders=%d; producer work %.3f s (overlaps), wait %.3f s, jobs %.0f",
                        source.pipelined(),source.nativeDag(),source.computeThreads(),source.directionalThreads(),new LinearDngWriter.Options().encoderThreads,pipeline[0],pipeline[1],pipeline[2]));
                android.util.Log.i(SamsungCam.TAG,String.format(Locale.US,
                        "Lumdir work s (overlapping): dir %.3f chroma %.3f relative %.3f median %.3f codes %.3f; median %.2f ns; native median=%s directional=%s chroma=%s, tails %d; arrays %.1f MiB native-work %.1f MiB rings %.1f MiB; dir wall [stats,guide,update-complete,bilateral-tail,RGB-tail] %s",
                        st[0],st[1],st[2],st[3],st[4],st[3]*1e9/source.medianCount(),source.nativeMedians(),source.nativeDirectional(),source.nativeChroma(),source.medianFallbacks(),
                        source.peakWorkingBytes()/1048576.,source.nativeWorkingBytes()/1048576.,source.storeBytes()/1048576.,java.util.Arrays.toString(source.directionalSeconds())));
            } finally {long t=System.nanoTime();source.close();times.sourceClose=System.nanoTime()-t;}

        } else {
            saved = write(resolver, base + ".dng", new PackedRaw10(pixels, stride, w, h), p,
                    new CountingSource(null, 2L * h, progress, 0, 100));
        }
        android.util.Log.i(SamsungCam.TAG, "save timing: copy " + (t1 - t0) + " ms, sensor file " + (t2 - t1)
                + " ms, convert+write " + (android.os.SystemClock.elapsedRealtime() - t2) + " ms");
        return new Saved(saved.uri, saved.name, saved.bytes + sensorBytes);
        } finally {
            long cleanupStart=System.nanoTime();
            android.os.SharedMemory.unmap(pixels);
            pixelMemory.close();
            for(int i=0;i<4;i++) {
                if(workMaps[i]!=null)android.os.SharedMemory.unmap(workMaps[i]);
                if(workMemory[i]!=null)workMemory[i].close();
            }
            times.unmap=System.nanoTime()-cleanupStart;
            times.log();
        }
    }

    private static android.os.SharedMemory sharedMemory(String name, int size) throws IOException {
        try {
            return android.os.SharedMemory.create("s24cam-" + name, size);
        } catch (android.system.ErrnoException e) {
            throw new IOException("Not enough memory for the frame: " + e.getMessage(), e);
        }
    }

    private static DngParams params(CameraCharacteristics chars, CaptureResult result, int w, int h, int orientation) {
        DngParams p = DngParamsFactory.from(chars, result, w, h, orientation);
        p.losslessJpeg = true;
        p.software = "S24 HighRes " + BuildConfig.VERSION_NAME;
        return p;
    }

    /** Writes one DNG into the gallery folder; deletes it again if anything fails. */
    private static Saved write(ContentResolver resolver, String name, RowSource source, DngParams p,
                               CountingSource counter) throws IOException {
        counter.inner = source;
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("The gallery refused a new file");
        WRITING.add(uri);
        try {
        long bytes;
        try (OutputStream os = resolver.openOutputStream(uri, "w");
             CountingStream out = new CountingStream(new BufferedOutputStream(os, 1 << 20))) {
            DngWriter.write(out, counter, p);
            out.flush();
            bytes = out.count;
        } catch (IOException | RuntimeException e) {
            resolver.delete(uri, null, null); // never leave a half-written file behind
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, v, null, null);
        return new Saved(uri, name, bytes);
        } finally { WRITING.remove(uri); }
    }

    private static Saved writeLinear(ContentResolver resolver,String name,Lumdir16 source,DngParams p,
                                     Progress progress,int from,SaveTimes times) throws IOException {
        ContentValues v=new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME,name);v.put(MediaStore.Images.Media.MIME_TYPE,"image/x-adobe-dng");
        v.put(MediaStore.Images.Media.RELATIVE_PATH,FOLDER);v.put(MediaStore.Images.Media.IS_PENDING,1);
        v.put(MediaStore.Images.Media.WIDTH,p.width);v.put(MediaStore.Images.Media.HEIGHT,p.height);
        if(p.captureTimeMillis>0)v.put(MediaStore.Images.Media.DATE_TAKEN,p.captureTimeMillis);
        long t=System.nanoTime();Uri uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
        times.insert=System.nanoTime()-t;if(uri==null)throw new IOException("The gallery refused a new file");
        WRITING.add(uri);
        try {
        long bytes,closeStart=0,pfdCloseStart=0;
        try {
            t=System.nanoTime();
            try(android.os.ParcelFileDescriptor fd=resolver.openFileDescriptor(uri,"rw")) {
                if(fd==null)throw new IOException("The gallery refused a seekable file");
                try(java.io.FileOutputStream stream=new java.io.FileOutputStream(fd.getFileDescriptor())) {
                    java.nio.channels.FileChannel channel=stream.getChannel();times.open=System.nanoTime()-t;
                    times.writer=LinearDngWriter.writeWithTimings(channel,source,p,source.linearizationTable(),LinearDngWriter.createTileEncoder(),
                            new LinearDngWriter.Options(),pct->progress.onProgress(from+pct*(100-from)/100));
                    bytes=channel.size();
                    android.util.Log.i(SamsungCam.TAG,"LinearDNG writer wall s: "+times.writer+", "+bytes+" bytes");
                    closeStart=System.nanoTime();
                }
                times.streamClose=System.nanoTime()-closeStart;pfdCloseStart=System.nanoTime();
            }
            times.pfdClose=System.nanoTime()-pfdCloseStart;
            android.util.Log.i(SamsungCam.TAG,String.format(Locale.US,"MediaStore close s: stream %.3f pfd %.3f",times.streamClose/1e9,times.pfdClose/1e9));
            v.clear();v.put(MediaStore.Images.Media.IS_PENDING,0);
            // AOSP MediaProvider runs a blocking metadata scan on publication by
            // default. This S+ provider extra queues the scan; the file is already
            // completely written/closed. Gallery indexing may finish afterwards.
            // https://android.googlesource.com/platform/packages/providers/MediaProvider/+/master/src/com/android/providers/media/MediaProvider.java
            android.os.Bundle extras=new android.os.Bundle();extras.putBoolean("android:query-arg-defer-scan",true);
            android.util.Log.i(SamsungCam.TAG,"MediaStore publish begin (deferred metadata scan requested)");
            t=System.nanoTime();
            try {resolver.update(uri,v,extras);times.deferred=true;}
            catch(IllegalArgumentException e) {
                android.util.Log.w(SamsungCam.TAG,"Provider rejected deferred scan; publishing synchronously",e);
                resolver.update(uri,v,null,null);
            }
            times.publish=System.nanoTime()-t;
            android.util.Log.i(SamsungCam.TAG,String.format(Locale.US,"MediaStore publish s: %.3f defer-request-accepted=%s",times.publish/1e9,times.deferred));
        } catch(IOException|RuntimeException|Error e) {
            resolver.delete(uri,null,null);
            if(e instanceof Error)throw (Error)e;
            throw e instanceof IOException?(IOException)e:new IOException(e.getMessage(),e);
        }
        return new Saved(uri,name,bytes);
        } finally { WRITING.remove(uri); }
    }
    private static final class SaveTimes {
        final long started=System.nanoTime();
        final long cpuStarted=android.os.Process.getElapsedCpuTime();
        long copy,sensor,setup,insert,open,streamClose,pfdClose,publish,sourceClose,unmap;
        boolean deferred;LinearDngWriter.Timings writer;
        void log() {
            if(writer==null)return;
            double[] w=writer.wallSeconds();double total=(System.nanoTime()-started)/1e9;
            double outside=(copy+sensor+setup+insert+open+streamClose+pfdClose+publish+sourceClose+unmap)/1e9;
            double other=total-outside-w[6];
            android.util.Log.i(SamsungCam.TAG,String.format(Locale.US,
                    "Save wall s: copy/map %.3f sensor %.3f setup %.3f insert/open %.3f source %.3f pack %.3f encode-wait %.3f write %.3f metadata %.3f writer-other %.3f stream-close %.3f pfd-close %.3f publish %.3f source-close %.3f unmap %.3f other %.3f = total %.3f",
                    copy/1e9,sensor/1e9,setup/1e9,(insert+open)/1e9,w[0],w[1],w[2],w[3],w[4],w[5],streamClose/1e9,pfdClose/1e9,
                    publish/1e9,sourceClose/1e9,unmap/1e9,other,total));
            double cpu=(android.os.Process.getElapsedCpuTime()-cpuStarted)/1000.;
            int cores=Runtime.getRuntime().availableProcessors();
            android.util.Log.i(SamsungCam.TAG,String.format(Locale.US,
                    "Save CPU: process %.3f s / wall %.3f s = %.2f busy cores (%.0f%% top); utilisation %.1f%% of %d cores",
                    cpu,total,cpu/total,100*cpu/total,100*cpu/total/cores,cores));
        }
    }

    /** Phone held upright: the sensor's mounting angle becomes the EXIF rotation. */
    private static int exifOrientation(Integer sensorOrientation) {
        int o = sensorOrientation == null ? 90 : sensorOrientation;
        switch (o) {
            case 90: return 6;
            case 180: return 3;
            case 270: return 8;
            default: return 1;
        }
    }

    /** Plain RAW10 frame as a row source. */
    private static final class PackedRaw10 implements RowSource {
        private final java.nio.ByteBuffer buf;
        private final int stride, width, height;
        PackedRaw10(java.nio.ByteBuffer buf, int stride, int width, int height) {
            this.buf = buf.duplicate().slice();
            this.stride = stride;
            this.width = width;
            this.height = height;
        }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public int sample(int x, int y) {
            int g = y * stride + (x >> 2) * 5;
            return ((buf.get(g + (x & 3)) & 255) << 2) | ((buf.get(g + 4) >> ((x & 3) * 2)) & 3);
        }
        @Override public void row(int y, int[] out) {
            for (int x = 0, g = y * stride; x < width; x += 4, g += 5) {
                int low = buf.get(g + 4) & 255;
                out[x] = ((buf.get(g) & 255) << 2) | (low & 3);
                out[x + 1] = ((buf.get(g + 1) & 255) << 2) | ((low >> 2) & 3);
                out[x + 2] = ((buf.get(g + 2) & 255) << 2) | ((low >> 4) & 3);
                out[x + 3] = ((buf.get(g + 3) & 255) << 2) | ((low >> 6) & 3);
            }
        }
    }

    /** Counts rows pulled by the writer and maps them onto [from, from + span] percent. */
    private static final class CountingSource implements RowSource {
        RowSource inner;
        private final long total;
        private final Progress progress;
        private final int from, span;
        private final java.util.concurrent.atomic.AtomicLong rows = new java.util.concurrent.atomic.AtomicLong();
        private volatile int lastPercent = -1;
        CountingSource(RowSource inner, long total, Progress progress, int from, int span) {
            this.inner = inner;
            this.total = total;
            this.progress = progress;
            this.from = from;
            this.span = span;
        }
        @Override public int width() { return inner.width(); }
        @Override public int height() { return inner.height(); }
        @Override public int sample(int x, int y) { return inner.sample(x, y); }
        @Override public void row(int y, int[] out) {
            inner.row(y, out);
            int percent = (int) Math.min(99, from + rows.incrementAndGet() * span / total);
            if (percent != lastPercent) {
                lastPercent = percent;
                progress.onProgress(percent);
            }
        }
    }

    private static final class CountingStream extends java.io.FilterOutputStream {
        long count;
        CountingStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
