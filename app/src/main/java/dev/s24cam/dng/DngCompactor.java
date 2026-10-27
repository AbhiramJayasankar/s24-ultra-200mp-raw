package dev.s24cam.dng;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;

/** Strict reader/serializer for LinearDngWriter's TIFF dialect. Never writes the source. */
public final class DngCompactor {
    private static final int TILE = 256, SAMPLES = TILE * TILE * 3;
    private static final long LIMIT = 0xffffffffL;
    private static final int MAX_TILE = 8 << 20, MAX_METADATA = 16 << 20;
    private DngCompactor() {}

    /** Implementations must allow concurrent calls; each call owns its buffers. */
    public interface TileCodec {
        short[] decode(byte[] bytes, int width, int height, int channels) throws IOException;
        byte[] encode(short[] samples, int width, int height, int channels) throws IOException;
    }
    public static TileCodec nativeCodec() {
        return new TileCodec() {
            final JxlTileEncoder encoder = JxlTileEncoder.forLinearRaw();
            public short[] decode(byte[] bytes, int w, int h, int c) { return JxlNative.decode(bytes,w,h,c); }
            public byte[] encode(short[] samples, int w, int h, int c) { return encoder.encode(samples,w,h,c,16); }
        };
    }
    /** Called between tiles, on all workers, and immediately before the transaction. */
    public interface Check { void check() throws IOException; }
    public static final class Stopped extends IOException {
        public Stopped(String reason) { super(reason); }
    }
    public static final class Skipped extends IOException {
        public Skipped(String reason) { super(reason); }
    }
    public static final class Stats {
        public final long before, after;
        public final int tiles;
        public final double seconds;
        Stats(long before, long after, int tiles, double seconds) {
            this.before=before; this.after=after; this.tiles=tiles; this.seconds=seconds;
        }
    }

    /** Header-only eligibility, without loading a codec or preview/tile data. Full validation is repeated by compact. */
    public static String skipReason(FileChannel source) throws IOException {
        try { Layout.read(source, false,true); return null; } catch (Skipped e) { return e.getMessage(); }
    }

    /** Header-only library inspection: no preview, tile payload or padding reads, no codec. */
    public static final class Header {
        public final long effort;
        public final String skipReason;
        Header(long effort,String skipReason) { this.effort=effort;this.skipReason=skipReason; }
    }
    public static Header inspect(FileChannel source) throws IOException {
        long effort;
        try { effort=effort(source); }
        catch(Skipped e) { return new Header(-1,e.getMessage()); }
        if(effort>=7) return new Header(effort,"Already compacted");
        return new Header(effort,skipReason(source));
    }
    /** JXLEffort in the raw SubIFD, or -1 when absent. Reads IFDs only. */
    public static long effort(FileChannel source) throws IOException {
        Directory root=headerDirectory(source,rootOffset(source),set(330));
        Tag sub=root.tags.get(330);
        if(sub==null || sub.type!=4 || sub.count!=1) return -1;
        Tag effort=headerDirectory(source,sub.first(),set(52554)).tags.get(52554);
        return effort!=null && effort.type==4 && effort.count==1?effort.first():-1;
    }
    public static final class Preview {
        public final int width,height,orientation;
        public final byte[] rgb;
        Preview(int width,int height,int orientation,byte[] rgb) {
            this.width=width;this.height=height;this.orientation=orientation;this.rgb=rgb;
        }
    }
    /** This app's uncompressed RGB IFD0 preview; reads just the IFD and its one strip. */
    public static Preview preview(FileChannel source) throws IOException {
        Directory r=headerDirectory(source,rootOffset(source),set(256,257,258,259,262,273,274,277,278,279,284));
        r.expect(259,3,1);r.expect(262,3,2);r.expect(277,3,3);r.expect(284,3,1);
        require(Arrays.equals(r.tag(258,3,3).bytes,new byte[]{8,0,8,0,8,0}),"Preview sample depth");
        long w=r.value(256,4),h=r.value(257,4);
        require(w>0 && w<=256 && h>0 && h<=256 && r.value(278,4)==h && r.value(279,4)==w*h*3,"Preview geometry");
        int orientation=r.tags.containsKey(274)?(int)r.value(274,3):1;
        require(orientation>=1 && orientation<=8,"Preview orientation");
        return new Preview((int)w,(int)h,orientation,readBytes(source,r.value(273,4),(int)(w*h*3)));
    }
    private static long rootOffset(FileChannel source) throws IOException {
        byte[] h=readBytes(source,0,8);
        require(h[0]=='I' && h[1]=='I' && u16(h,2)==42,"Not little-endian classic TIFF");
        return u32(h,4);
    }
    private static Directory headerDirectory(FileChannel source,long at,Set<Integer> wanted) throws IOException {
        require(at>=8 && (at&1)==0,"Invalid IFD offset");
        int n=u16(readBytes(source,at,2),0);require(n>0 && n<=100,"Unexpected IFD entry count");
        byte[] entries=readBytes(source,at,6+12*n);Directory result=new Directory();
        for(int i=0;i<n;i++) {
            int p=2+12*i,id=u16(entries,p),type=u16(entries,p+2);long count=u32(entries,p+4);
            if(!wanted.contains(id)) continue;
            int unit=type==3?2:type==4?4:0;
            require(unit!=0 && count>0 && count<=16,"Invalid header tag " + id);
            int length=(int)count*unit;
            byte[] data=length<=4?Arrays.copyOfRange(entries,p+8,p+8+length):readBytes(source,u32(entries,p+8),length);
            require(result.tags.put(id,new Tag(id,type,count,data))==null,"Duplicate header tag");
        }
        return result;
    }

    public interface ProgressListener { void onProgress(int tilesDone,int tilesTotal); }

    public static Stats compact(FileChannel source, File target, TileCodec codec, Check check, int workers)
            throws IOException {
        return compact(source,target,codec,check,workers,null);
    }
    /** Progress is called on the coordinator after each first-pass verified tile. */
    public static Stats compact(FileChannel source, File target, TileCodec codec, Check check, int workers,
                                ProgressListener progress) throws IOException {
        long started = System.nanoTime();
        Layout layout = Layout.read(source, false);
        check.check();
        if (workers < 1 || workers > 8) throw new IllegalArgumentException("workers must be 1..8");
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> new Thread(r,"Compact tile"));
        CompletionService<Encoded> done = new ExecutorCompletionService<>(pool);
        // Only 'workers' tasks/results are ever in flight; no full-frame sample arena.
        int next=0, active=0;
        try (FileChannel out = FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long[] offsets = new long[layout.offsets.length], counts = new long[offsets.length];
            if(progress!=null) progress.onProgress(0,offsets.length);
            long pos = layout.metadataSize();
            while (next < offsets.length || active > 0) {
                check.check();
                while (next < offsets.length && active < workers) {
                    final int index = next++;
                    done.submit(() -> {
                        check.check();
                        short[] original = decode(codec, layout.tile(source,index));
                        check.check();
                        byte[] bytes = codec.encode(original,TILE,TILE,3);
                        if (bytes == null || bytes.length == 0 || bytes.length > MAX_TILE)
                            throw new IOException("Invalid encoded tile size");
                        if (!Arrays.equals(original,decode(codec,bytes)))
                            throw new IOException("Tile " + index + " sample mismatch (first verification)");
                        check.check();
                        return new Encoded(index,bytes);
                    });
                    active++;
                }
                Encoded tile;
                try { tile = done.take().get(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Stopped("cancelled"); }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException) throw (IOException)e.getCause();
                    throw new IOException("Tile codec failed",e.getCause());
                }
                active--;
                check.check();
                if ((pos & 1) != 0) { write(out,pos,new byte[1]); pos++; }
                offsets[tile.index]=pos; counts[tile.index]=tile.bytes.length;
                write(out,pos,tile.bytes); pos+=tile.bytes.length;
                if (pos > LIMIT) throw new IOException("Classic TIFF size limit");
                if(progress!=null) progress.onProgress(next-active,offsets.length);
            }
            if ((pos & 1) != 0) { write(out,pos,new byte[1]); pos++; }
            layout.serialize(out,offsets,counts);
            out.truncate(pos);
            check.check();
            out.force(true);
            return new Stats(source.size(),out.size(),offsets.length,(System.nanoTime()-started)/1e9);
        } finally {
            pool.shutdownNow();
            // Native calls cannot be interrupted. Join before the caller closes the input or removes temp.
            boolean interrupted=Thread.interrupted();
            while (!pool.isTerminated()) try { pool.awaitTermination(1,TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted=true; }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Independent disk pass: parse both files anew, compare metadata/blobs and every padded sample. */
    public static void verify(FileChannel original, FileChannel replacement, TileCodec codec, Check check)
            throws IOException {
        Layout a=Layout.read(original,false), b=Layout.read(replacement,true);
        if (a.width!=b.width || a.height!=b.height || a.offsets.length!=b.offsets.length)
            throw new IOException("Verification geometry mismatch");
        a.sameMetadata(b);
        if (!Arrays.equals(a.thumbnail,b.thumbnail)) throw new IOException("Thumbnail changed");
        for (int i=0;i<a.offsets.length;i++) {
            check.check();
            if (!Arrays.equals(decode(codec,a.tile(original,i)),decode(codec,b.tile(replacement,i))))
                throw new IOException("Tile " + i + " sample mismatch (second disk verification)");
        }
        check.check();
    }
    private static short[] decode(TileCodec codec, byte[] bytes) throws IOException {
        short[] samples=codec.decode(bytes,TILE,TILE,3);
        if (samples==null || samples.length!=SAMPLES) throw new IOException("Decoded sample count mismatch");
        return samples;
    }
    private static final class Encoded {
        final int index; final byte[] bytes;
        Encoded(int index,byte[] bytes) { this.index=index; this.bytes=bytes; }
    }
    private static final class Tag {
        final int id,type; final long count; final byte[] bytes;
        Tag(int id,int type,long count,byte[] bytes) { this.id=id; this.type=type; this.count=count; this.bytes=bytes; }
        long first() { return type==3 ? u16(bytes,0) : u32(bytes,0); }
        String text() { return new String(bytes,StandardCharsets.US_ASCII).replace("\0",""); }
    }
    private static final class Directory {
        final Map<Integer,Tag> tags=new LinkedHashMap<>();
        long size() { long n=6+12L*tags.size(); for(Tag t:tags.values()) if(t.bytes.length>4) n+=align(t.bytes.length); return n; }
        Tag tag(int id,int type,long count) throws Skipped {
            Tag t=tags.get(id);
            require(t!=null && t.type==type && t.count==count,"Missing/invalid tag " + id);
            return t;
        }
        long value(int id,int type) throws Skipped { return tag(id,type,1).first(); }
        void expect(int id,int type,long value) throws Skipped { require(value(id,type)==value,"Unexpected tag " + id); }
        void put(int id,long... values) {
            Tag old=tags.get(id); byte[] b=new byte[values.length*4];
            for(int i=0;i<values.length;i++) put32(b,4*i,values[i]);
            tags.put(id,new Tag(id,old.type,old.count,b));
        }
        void write(FileChannel out,long start) throws IOException {
            ByteBuffer b=ByteBuffer.allocate(Math.toIntExact(size())).order(ByteOrder.LITTLE_ENDIAN);
            b.putShort((short)tags.size()); long at=start+6+12L*tags.size();
            for(Tag t:tags.values()) {
                b.putShort((short)t.id).putShort((short)t.type).putInt((int)t.count);
                if(t.bytes.length>4) { b.putInt((int)at); at+=align(t.bytes.length); }
                else { b.put(t.bytes); for(int i=t.bytes.length;i<4;i++) b.put((byte)0); }
            }
            b.putInt(0);
            for(Tag t:tags.values()) if(t.bytes.length>4) { b.put(t.bytes); if((t.bytes.length&1)!=0) b.put((byte)0); }
            DngCompactor.write(out,start,b.array());
        }
    }
    private static final class Layout {
        // All current LinearDngWriter + cameraMetadata tags. 700 is self-contained XMP;
        // unlike MakerNote it cannot hide TIFF-relative pointers. Unknowns fail closed.
        static final Set<Integer> ROOT=set(254,256,257,258,259,262,271,272,273,274,277,278,279,284,
                305,306,330,700,34665,50706,50707,50708,50721,50722,50723,50724,50728,50730,
                50778,50779,50964,50965);
        static final Set<Integer> RAW=set(254,256,257,258,259,262,277,284,322,323,324,325,
                50712,50714,50717,50719,50720,50829,52553,52554,52555);
        static final Set<Integer> EXIF=set(33434,33437,34855,34864,34867,36867,37386);
        final FileChannel channel;
        final long fileSize;
        final List<long[]> extents=new ArrayList<>();
        int metadataBytes;
        Directory root,raw,exif;
        byte[] thumbnail;
        long[] offsets,counts;
        int width,height;
        Layout(FileChannel channel) throws IOException { this.channel=channel;fileSize=channel.size(); }
        static Layout read(FileChannel channel,boolean compacted) throws IOException {
            return read(channel,compacted,false);
        }
        static Layout read(FileChannel channel,boolean compacted,boolean headerOnly) throws IOException {
            Layout l=new Layout(channel); l.range(0,8);
            byte[] h=readBytes(channel,0,8);
            require(h[0]=='I' && h[1]=='I' && u16(h,2)==42,"Not little-endian classic TIFF");
            l.root=l.directory(u32(h,4),ROOT);
            Directory r=l.root;
            require(r.tag(271,2,r.tags.containsKey(271)?r.tags.get(271).count:0).text().equals("samsung"),"Foreign Make");
            Tag software=r.tags.get(305);
            require(software!=null && software.type==2 && (software.text().startsWith("S24 HighRes ")
                    || software.text().equals("S24Cam Java DNG")),"Foreign Software marker");
            Tag model=r.tags.get(272); require(model!=null && model.type==2 && !model.text().isEmpty(),"Missing Model");
            require(Arrays.equals(r.tag(50706,1,4).bytes,new byte[]{1,7,0,0}),"Not DNG 1.7");
            require(Arrays.equals(r.tag(50707,1,4).bytes,new byte[]{1,7,0,0}),"Unexpected DNG backward version");
            r.expect(254,4,1); r.expect(259,3,1); r.expect(262,3,2); r.expect(277,3,3); r.expect(284,3,1);
            require(Arrays.equals(r.tag(258,3,3).bytes,new byte[]{8,0,8,0,8,0}),"Thumbnail sample depth");
            long tw=r.value(256,4),th=r.value(257,4);
            require(tw>0 && tw<=256 && th>0 && th<=256 && r.value(278,4)==th
                    && r.value(279,4)==tw*th*3,"Unexpected thumbnail geometry");
            long thumb=r.value(273,4),length=r.value(279,4);
            l.range(thumb,length); if(!headerOnly) l.thumbnail=readBytes(channel,thumb,(int)length);
            l.raw=l.directory(r.value(330,4),RAW);
            if(r.tags.containsKey(34665)) l.exif=l.directory(r.value(34665,4),EXIF);
            Directory raw=l.raw;
            raw.expect(254,4,0); raw.expect(262,3,34892); raw.expect(259,3,52546);
            raw.expect(277,3,3); raw.expect(284,3,1); raw.expect(322,4,TILE); raw.expect(323,4,TILE);
            require(Arrays.equals(raw.tag(258,3,3).bytes,new byte[]{16,0,16,0,16,0}),"Raw sample depth");
            Tag table=raw.tags.get(50712);
            require(table!=null && table.type==3 && table.count>=2 && table.count<=65536,"Invalid linearization table");
            raw.tag(50714,3,3); raw.tag(50717,4,3); raw.tag(50719,4,2);
            raw.tag(50720,4,2); raw.tag(50829,4,4);
            long w=raw.value(256,4),hh=raw.value(257,4);
            require(w>=2 && w<=65535 && hh>=2 && hh<=65535 && (w&1)==0 && (hh&1)==0,"Invalid raw geometry");
            l.width=(int)w; l.height=(int)hh;
            long effort=raw.value(52554,4);
            require(compacted?effort==7:effort>=1 && effort<7,compacted?"Not compacted":"Already compacted/invalid effort");
            require(u32(raw.tag(52553,11,1).bytes,0)==0,"Non-lossless JXL distance");
            long speed=raw.value(52555,4); require(speed<=4 && (!compacted || speed==0),"Invalid JXL decode speed");
            int n=((l.width+255)/256)*((l.height+255)/256);
            l.offsets=longs(raw.tag(324,4,n)); l.counts=longs(raw.tag(325,4,n));
            for(int i=0;i<n;i++) {
                require(l.counts[i]>0 && l.counts[i]<=MAX_TILE,"Invalid tile byte count");
                l.range(l.offsets[i],l.counts[i]);
            }
            l.coverage(headerOnly);
            return l;
        }
        Directory directory(long at,Set<Integer> allowed) throws IOException {
            require(at>=8 && (at&1)==0,"Invalid IFD offset");
            int n=u16(readBytes(channel,at,2),0); require(n>0 && n<=100,"Unexpected IFD entry count");
            int size=6+n*12; range(at,size);
            byte[] bytes=readBytes(channel,at,size);
            require(u32(bytes,size-4)==0,"Unexpected next IFD");
            Directory d=new Directory(); int previous=-1;
            for(int i=0;i<n;i++) {
                int p=2+i*12,id=u16(bytes,p),type=u16(bytes,p+2);long count=u32(bytes,p+4);
                require(id>previous && allowed.contains(id),"Unknown/duplicate/unsorted tag " + id); previous=id;
                int unit=type==1||type==2||type==7?1:type==3?2:type==4||type==11?4:type==5||type==10?8:0;
                require(unit!=0 && count>0 && count<=MAX_METADATA/unit,"Unsupported tag type/count " + id);
                int len=(int)count*unit; metadataBytes+=len;
                require(metadataBytes<=MAX_METADATA,"Metadata too large");
                byte[] data;
                if(len<=4) data=Arrays.copyOfRange(bytes,p+8,p+8+len);
                else { long offset=u32(bytes,p+8); require((offset&1)==0,"Unaligned tag data"); range(offset,len); data=readBytes(channel,offset,len); }
                if(id==700) require(type==1||type==7,"Unexpected XMP type");
                d.tags.put(id,new Tag(id,type,count,data));
            }
            return d;
        }
        void range(long at,long length) throws IOException {
            require(at>=0 && length>0 && at<=fileSize-length && fileSize<=LIMIT,"TIFF range outside file");
            extents.add(new long[]{at,at+length});
        }
        void coverage(boolean headerOnly) throws IOException {
            extents.sort(Comparator.comparingLong(a->a[0])); long end=0,gaps=0;
            for(long[] e:extents) {
                require(e[0]>=end,"Overlapping TIFF ranges");
                gaps+=e[0]-end; require(gaps<=MAX_METADATA,"Unreferenced TIFF data");
                if(!headerOnly) zeros(end,e[0]); end=e[1];
            }
            require(fileSize-end<=16,"Unexpected TIFF trailer"); if(!headerOnly) zeros(end,fileSize);
        }
        void zeros(long start,long end) throws IOException {
            while(start<end) { int n=(int)Math.min(8192,end-start); byte[] b=readBytes(channel,start,n);
                for(byte v:b) require(v==0,"Unreferenced nonzero TIFF data"); start+=n; }
        }
        byte[] tile(FileChannel ch,int i) throws IOException { return readBytes(ch,offsets[i],(int)counts[i]); }
        long metadataSize() { return align(8+root.size()+raw.size()+(exif==null?0:exif.size())+thumbnail.length); }
        void serialize(FileChannel out,long[] offsets,long[] counts) throws IOException {
            long rawAt=8+root.size(),exifAt=rawAt+raw.size(),thumbAt=exifAt+(exif==null?0:exif.size());
            root.put(330,rawAt); root.put(273,thumbAt); if(exif!=null) root.put(34665,exifAt);
            raw.put(324,offsets); raw.put(325,counts); raw.put(52554,7); raw.put(52555,0);
            write(out,0,new byte[]{'I','I',42,0,8,0,0,0});
            root.write(out,8); raw.write(out,rawAt); if(exif!=null) exif.write(out,exifAt);
            write(out,thumbAt,thumbnail);
            if(((thumbAt+thumbnail.length)&1)!=0) write(out,thumbAt+thumbnail.length,new byte[1]);
        }
        void sameMetadata(Layout b) throws IOException {
            Directory[] left={root,raw,exif},right={b.root,b.raw,b.exif};
            for(int i=0;i<left.length;i++) {
                Directory a=left[i],d=right[i];
                if(a==null || d==null) { if(a!=d) throw new IOException("IFD changed"); continue; }
                if(!a.tags.keySet().equals(d.tags.keySet())) throw new IOException("Tag set changed");
                for(Tag t:a.tags.values()) {
                    Tag u=d.tags.get(t.id);
                    if(t.type!=u.type || t.count!=u.count) throw new IOException("Tag shape changed " + t.id);
                    boolean relocated=i==0 && (t.id==273||t.id==330||t.id==34665)
                            || i==1 && (t.id==324||t.id==325||t.id==52554||t.id==52555);
                    if(!relocated && !Arrays.equals(t.bytes,u.bytes)) throw new IOException("Tag changed " + t.id);
                }
            }
        }
    }
    private static Set<Integer> set(Integer... ids) { return new HashSet<>(Arrays.asList(ids)); }
    private static long align(long n) { return (n+1)&~1L; }
    private static long[] longs(Tag t) { long[] v=new long[(int)t.count]; for(int i=0;i<v.length;i++) v[i]=u32(t.bytes,i*4); return v; }
    private static void require(boolean value,String reason) throws Skipped { if(!value) throw new Skipped(reason); }
    private static int u16(byte[] b,int p) { return (b[p]&255)|((b[p+1]&255)<<8); }
    private static long u32(byte[] b,int p) { return (u16(b,p)&65535L)|((long)u16(b,p+2)<<16); }
    private static void put32(byte[] b,int p,long value) { for(int i=0;i<4;i++) b[p+i]=(byte)(value>>>(i*8)); }
    private static byte[] readBytes(FileChannel ch,long at,int n) throws IOException {
        if(at<0 || n<0 || at>ch.size()-n) throw new Skipped("Truncated TIFF");
        ByteBuffer b=ByteBuffer.allocate(n);
        while(b.hasRemaining()) { int read=ch.read(b,at+b.position()); if(read<0) throw new EOFException(); }
        return b.array();
    }
    private static void write(FileChannel ch,long at,byte[] bytes) throws IOException {
        if(at<0 || at>LIMIT-bytes.length) throw new IOException("Classic TIFF size limit");
        ByteBuffer b=ByteBuffer.wrap(bytes); while(b.hasRemaining()) ch.write(b,at+b.position());
    }
}
