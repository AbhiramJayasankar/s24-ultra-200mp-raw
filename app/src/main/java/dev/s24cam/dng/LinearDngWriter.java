package dev.s24cam.dng;

import java.io.*;
import java.nio.*;
import java.nio.channels.SeekableByteChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/** One source pass, two bounded tile arenas, completion-order tile writes.
 * Space for known-size IFDs/thumbnail is reserved before the tiles; metadata is
 * written there once at the end. Android can then inspect a small file prefix
 * instead of skipping the entire 200MP payload to find IFD0. No counting pass.
 */
public final class LinearDngWriter {
    public static final int TILE=256;
    private static final long LIMIT=0xffffffffL;
    private LinearDngWriter() {}
    public static TileEncoder createTileEncoder() {
        // Effort 1 is the fast lossless path. Keep the codestream at 16 bits:
        // Lightroom scales samples according to that depth, even for small codes.
        // The tuned profile (JxlTileEncoder.forLinearRaw) is too slow at shot time.
        return JxlNative.available()?new JxlTileEncoder(0,1,0):new Ljpeg92TileEncoder();
    }
    public static final class Options {
        public float jxlDistance=0;
        public int jxlEffort=3,jxlDecodeSpeed=4;
        /** Two buffers overlap next-band reconstruction with previous-band compression. */
        public int inFlightBands=2;
        public int encoderThreads=Math.max(1,Math.min(8,Math.min(Runtime.getRuntime().availableProcessors(),Integer.getInteger("s24cam.dng.encoderThreads",8))));
    }
    /** Main-thread wall buckets sum to total. Encoder-worker time is overlapping
     * work, explicitly excluded from that sum. Includes buffer flush and pool exit. */
    public static final class Timings {
        long source,packing,waiting,writing,metadata,overhead,total,writeCalls;
        final AtomicLong encoderWork=new AtomicLong();
        public double[] wallSeconds(){return new double[]{source/1e9,packing/1e9,waiting/1e9,writing/1e9,metadata/1e9,overhead/1e9,total/1e9};}
        public double encoderWorkerSeconds(){return encoderWork.get()/1e9;}
        public long writeCalls(){return writeCalls;}
        @Override public String toString(){double[] s=wallSeconds();return String.format(java.util.Locale.US,
                "source %.3f pack %.3f encode-wait %.3f write %.3f metadata %.3f other %.3f = %.3f; encoder-worker %.3f (overlaps); writes %d",
                s[0],s[1],s[2],s[3],s[4],s[5],s[6],encoderWorkerSeconds(),writeCalls);}
    }
    public static void write(SeekableByteChannel out,RowSource source,DngParams p,int[] table,TileEncoder encoder)throws IOException {
        writeWithTimings(out,source,p,table,encoder,new Options(),null);
    }
    public static void write(SeekableByteChannel out,RowSource source,DngParams p,int[] table,TileEncoder encoder,Options options,IntConsumer progress)throws IOException {
        writeWithTimings(out,source,p,table,encoder,options,progress);
    }
    public static Timings writeWithTimings(SeekableByteChannel out,RowSource source,DngParams p,int[] table,TileEncoder encoder,Options options,IntConsumer progress)throws IOException {
        long started=System.nanoTime();Timings stats=new Timings();
        DngWriter.validate(p);
        if(source.width()!=p.width||source.height()!=p.height||source.channels()!=3)throw new IllegalArgumentException("RGB geometry mismatch");
        if(encoder.compression()!=7&&encoder.compression()!=52546)throw new IllegalArgumentException("Unsupported compression");
        if(table==null||table.length<2||table.length>65536||table[table.length-1]!=65535)throw new IllegalArgumentException("Invalid curve");
        int prev=-1;for(int value:table){if(value<prev||value>65535)throw new IllegalArgumentException("Invalid curve");prev=value;}
        if(options==null||!Float.isFinite(options.jxlDistance)||options.jxlDistance<0||options.jxlEffort<1||options.jxlDecodeSpeed<0
                ||options.inFlightBands<1||options.inFlightBands>2||options.encoderThreads<1||options.encoderThreads>8)throw new IllegalArgumentException("Invalid options");
        int across=(p.width+TILE-1)/TILE,down=(p.height+TILE-1)/TILE;
        long[] offsets=new long[across*down],counts=new long[offsets.length];
        Thumbnail thumb=new Thumbnail(p,table);
        Layout layout=new Layout(p,table,encoder,options,thumb,offsets,counts);
        Sink sink=new Sink(out,stats);out.position(0);out.truncate(0);
        long t=System.nanoTime();
        sink.write(new byte[]{'I','I',42,0,0,0,0,0});
        sink.write(new byte[Math.toIntExact(layout.dataStart-8)]);
        stats.metadata+=System.nanoTime()-t;
        int threads=options.encoderThreads;
        ExecutorService pool=Executors.newFixedThreadPool(threads,r->{Thread thread=new Thread(r,"DNG tile");thread.setDaemon(true);return thread;});
        CompletionService<Encoded> done=new ExecutorCompletionService<>(pool);
        int slots=Math.min(down,options.inFlightBands);short[][][] arenas=new short[slots][across][TILE*TILE*3];
        int[] pending=new int[slots];short[] row=new short[p.width*3];
        int active=0;
        try {
            for(int ty=0;ty<down;ty++) {
                int slot=ty%slots;
                // Preparing the first row performs the next source band while old
                // tiles encode. Arena reuse waits only after that useful work.
                t=System.nanoTime();source.row(ty*TILE,row);stats.source+=System.nanoTime()-t;
                while(pending[slot]>0){Encoded tile=receive(done,stats);writeTile(tile,sink,offsets,counts,pending,stats);active--;}
                short[][] tiles=arenas[slot];
                for(int y=0;y<TILE;y++) {
                    int gy=Math.min(p.height-1,ty*TILE+y);
                    if(y>0&&ty*TILE+y<p.height){t=System.nanoTime();source.row(gy,row);stats.source+=System.nanoTime()-t;}
                    t=System.nanoTime();if(ty*TILE+y<p.height)thumb.row(gy,row);
                    for(int tx=0;tx<across;tx++) {
                        int valid=Math.min(TILE,p.width-tx*TILE),at=y*TILE*3,src=tx*TILE*3;
                        System.arraycopy(row,src,tiles[tx],at,valid*3);
                        if(valid<TILE)for(int x=valid;x<TILE;x++)System.arraycopy(row,src+(valid-1)*3,tiles[tx],at+x*3,3);
                    }
                    stats.packing+=System.nanoTime()-t;
                }
                for(int tx=0;tx<across;tx++) {
                    final int index=ty*across+tx,s=slot;final short[] samples=tiles[tx];
                    done.submit(()->{long begin=System.nanoTime();byte[] bytes=encoder.encode(samples,TILE,TILE,3,16);
                        stats.encoderWork.addAndGet(System.nanoTime()-begin);return new Encoded(index,s,bytes);});
                    pending[slot]++;active++;
                }
                Future<Encoded> ready;
                while((ready=done.poll())!=null){writeTile(result(ready),sink,offsets,counts,pending,stats);active--;}
                if(progress!=null)progress.accept(Math.min(99,(ty+1)*99/down));
            }
            while(active>0){writeTile(receive(done,stats),sink,offsets,counts,pending,stats);active--;}
        } finally {
            // There are no idle waits: success drains every submitted tile first.
            // Failure also waits for native encoders before input arenas can die.
            pool.shutdown();boolean interrupted=false;
            while(!pool.isTerminated())try{pool.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();
        }
        t=System.nanoTime();sink.align();sink.flush();stats.writing+=System.nanoTime()-t;
        t=System.nanoTime();long end=sink.position;
        out.position(8);writeDirect(out,layout.bytes(),stats);
        out.position(4);writeDirect(out,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(8).array(),stats);
        out.position(end);stats.metadata+=System.nanoTime()-t;
        if(progress!=null)progress.accept(100);
        stats.total=System.nanoTime()-started;
        stats.overhead=stats.total-stats.source-stats.packing-stats.waiting-stats.writing-stats.metadata;
        return stats;
    }
    private static Encoded receive(CompletionService<Encoded> done,Timings stats)throws IOException {
        long t=System.nanoTime();try{return result(done.take());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted encoding",e);}
        finally{stats.waiting+=System.nanoTime()-t;}
    }
    private static Encoded result(Future<Encoded> f)throws IOException {
        try{return f.get();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted encoding",e);}
        catch(ExecutionException e){throw new IOException("Tile encoder failed",e.getCause());}
    }
    private static void writeTile(Encoded tile,Sink sink,long[] offsets,long[] counts,int[] pending,Timings stats)throws IOException {
        long t=System.nanoTime();sink.align();offsets[tile.index]=sink.position;counts[tile.index]=tile.bytes.length;
        sink.write(tile.bytes);pending[tile.slot]--;stats.writing+=System.nanoTime()-t;
    }
    private static void writeDirect(SeekableByteChannel out,byte[] bytes,Timings stats)throws IOException {
        if(out.position()+bytes.length>LIMIT)throw new IOException("Image exceeds classic TIFF's 4 GiB limit");
        ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining()){out.write(b);stats.writeCalls++;}
    }
    private static final class Sink {
        final SeekableByteChannel out;final Timings stats;final ByteBuffer buffer=ByteBuffer.allocate(1<<20);long position;
        Sink(SeekableByteChannel out,Timings stats){this.out=out;this.stats=stats;}
        void align()throws IOException{if((position&1)!=0){if(position>=LIMIT)throw new IOException("Image exceeds classic TIFF's 4 GiB limit");if(!buffer.hasRemaining())flush();buffer.put((byte)0);position++;}}
        void write(byte[] bytes)throws IOException {
            if(position+bytes.length>LIMIT)throw new IOException("Image exceeds classic TIFF's 4 GiB limit");
            for(int at=0;at<bytes.length;){if(!buffer.hasRemaining())flush();int n=Math.min(buffer.remaining(),bytes.length-at);buffer.put(bytes,at,n);at+=n;}
            position+=bytes.length;
        }
        void flush()throws IOException{buffer.flip();while(buffer.hasRemaining()){out.write(buffer);stats.writeCalls++;}buffer.clear();}
    }
    private static final class Encoded {final int index,slot;final byte[] bytes;Encoded(int index,int slot,byte[] bytes){this.index=index;this.slot=slot;this.bytes=bytes;}}
    private static final class Layout {
        final DngWriter.Directory root,raw,exif;final Thumbnail thumb;final long[] offsets,counts;final long rootAt=8,rawAt,exifAt,dataStart;
        Layout(DngParams p,int[] table,TileEncoder encoder,Options options,Thumbnail thumb,long[] offsets,long[] counts) {
            this.thumb=thumb;this.offsets=offsets;this.counts=counts;boolean jxl=encoder.compression()==52546;
        root=new DngWriter.Directory();raw=new DngWriter.Directory();exif=new DngWriter.Directory();
        DngWriter.cameraMetadata(root,exif,p);
        if(jxl){root.bytes(50706,1,7,0,0);root.bytes(50707,1,7,0,0);}
        root.longs(254,1);root.longs(256,thumb.width);root.longs(257,thumb.height);
        root.shorts(258,8,8,8);root.shorts(259,1);root.shorts(262,2);
        root.longs(273,0);root.shorts(277,3);root.longs(278,thumb.height);
        root.longs(279,thumb.rgb.length);root.shorts(284,1);root.longs(330,0);
        raw.longs(254,0);raw.longs(256,p.width);raw.longs(257,p.height);
        raw.shorts(258,16,16,16);raw.shorts(259,encoder.compression());raw.shorts(262,34892);
        raw.shorts(277,3);raw.shorts(284,1);raw.longs(322,TILE);raw.longs(323,TILE);
        raw.longs(324,offsets);raw.longs(325,counts);
        raw.shorts(50712,table);raw.shorts(50714,0,0,0);raw.longs(50717,65535,65535,65535);
        raw.longs(50719,0,0);raw.longs(50720,p.width,p.height);raw.longs(50829,0,0,p.height,p.width);
        if(jxl){
            // Record the encoder's real settings when it exposes them.
            JxlTileEncoder j=encoder instanceof JxlTileEncoder?(JxlTileEncoder)encoder:null;
            raw.floats(52553,j!=null?j.getDistance():options.jxlDistance);
            raw.longs(52554,j!=null?j.getEffort():options.jxlEffort);
            raw.longs(52555,j!=null?j.getDecodingSpeed():options.jxlDecodeSpeed);
        }

            rawAt=rootAt+root.size();exifAt=rawAt+raw.size();long thumbnailOffset=exifAt+exif.size();
            dataStart=(thumbnailOffset+thumb.rgb.length+1)&~1L;
            root.longs(273,thumbnailOffset);root.longs(330,rawAt);if(!exif.entries.isEmpty())root.longs(34665,exifAt);
        }
        byte[] bytes()throws IOException {
            raw.longs(324,offsets);raw.longs(325,counts);
            ByteArrayOutputStream b=new ByteArrayOutputStream();root.write(b,rootAt);raw.write(b,rawAt);exif.write(b,exifAt);b.write(thumb.rgb);
            if((b.size()&1)!=0)b.write(0);
            if(b.size()!=dataStart-8)throw new IOException("Metadata reservation mismatch");return b.toByteArray();
        }
    }
    private static final class Thumbnail {
        final int width,height;final byte[] rgb;final DngParams p;final int[] table,sampleY,sampleX;
        Thumbnail(DngParams p,int[] table){this.p=p;this.table=table;double scale=Math.min(1,256.0/Math.max(p.width,p.height));
            width=Math.max(1,(int)Math.round(p.width*scale));height=Math.max(1,(int)Math.round(p.height*scale));rgb=new byte[width*height*3];
            sampleY=new int[height];for(int y=0;y<height;y++)sampleY[y]=Math.min(p.height-1,(int)((y+.5)*p.height/height));
            sampleX=new int[width];for(int x=0;x<width;x++)sampleX[x]=Math.min(p.width-1,(int)((x+.5)*p.width/width));}
        void row(int y,short[] samples){
            int ty=java.util.Arrays.binarySearch(sampleY,y);if(ty<0)return;
            for(int x=0;x<width;x++)for(int c=0;c<3;c++) {
                int code=samples[sampleX[x]*3+c]&65535;if(code>=table.length)throw new IllegalArgumentException("Code outside curve");
                double v=table[code]/65535.0/(p.asShotNeutral==null?1:p.asShotNeutral[c]);
                rgb[(ty*width+x)*3+c]=(byte)Math.round(255*Math.pow(Math.max(0,Math.min(1,v)),1/2.2));
            }
        }
    }
}
