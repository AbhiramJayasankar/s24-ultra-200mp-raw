package dev.s24cam.dng;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.Assert.*;

public class DngCompactorTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    static final DngCompactor.Check GO=()->{};
    static final int N=256*256*3;
    static class Codec implements DngCompactor.TileCodec {
        final AtomicInteger decodes=new AtomicInteger();
        public short[] decode(byte[] bytes,int w,int h,int c) throws IOException {
            decodes.incrementAndGet();assertEquals(256,w);assertEquals(256,h);assertEquals(3,c);
            InputStream in=new ByteArrayInputStream(bytes,1,bytes.length-1);
            if(bytes[0]==1) in=new InflaterInputStream(in);
            byte[] raw=in.readAllBytes(); if(raw.length!=N*2) throw new IOException("bad fake tile");
            short[] samples=new short[N]; ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
            return samples;
        }
        public byte[] encode(short[] samples,int w,int h,int c) throws IOException {
            byte[] raw=new byte[N*2]; ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
            ByteArrayOutputStream result=new ByteArrayOutputStream(); result.write(1);
            try(DeflaterOutputStream zip=new DeflaterOutputStream(result)) { zip.write(raw); }
            return result.toByteArray();
        }
    }
    File fixture(int width,int height,int effort,boolean exif) throws Exception {
        File f=folder.newFile(); fixture(f,width,height,effort,exif); return f;
    }
    public static void fixture(File f,int width,int height,int effort,boolean exif) throws Exception {
        DngParams p=DngWriterTest.params(2,true);p.width=width;p.height=height;p.software="S24 HighRes 0.4.1";p.iso=100000;
        if(!exif) { p.iso=0;p.exposureTimeSec=p.fNumber=p.focalLengthMm=0;p.captureTimeMillis=0; }
        RowSource source=new RowSource() {
            public int width(){return width;}public int height(){return height;}public int channels(){return 3;}
            public int sample(int x,int y){throw new AssertionError();}
            public void row(int y,int[] row){for(int x=0;x<width;x++)for(int c=0;c<3;c++)row[x*3+c]=(x+y+c)%2;}
        };
        TileEncoder encoder=new TileEncoder() {
            public int compression(){return 52546;}
            public byte[] encode(short[] samples,int w,int h,int c,int bits) {
                byte[] raw=new byte[1+samples.length*2];
                ByteBuffer.wrap(raw,1,raw.length-1).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);return raw;
            }
        };
        LinearDngWriter.Options opts=new LinearDngWriter.Options(); opts.jxlEffort=effort;opts.jxlDecodeSpeed=4;
        try(FileChannel out=FileChannel.open(f.toPath(),StandardOpenOption.WRITE,StandardOpenOption.CREATE)) {
            LinearDngWriter.write(out,source,p,new int[]{0,65535},encoder,opts,null);
        }
    }
    File target() { return new File(folder.getRoot(),UUID.randomUUID()+".dng"); }
    static FileChannel read(File f) throws IOException { return FileChannel.open(f.toPath(),StandardOpenOption.READ); }
    @Test public void allTagsBlobsExifThumbnailAndPaddedSamplesPreserved() throws Exception {
        File original=fixture(280,264,1,true),out=target(); Codec codec=new Codec();
        String hash=SafeDngReplace.hash(original);
        try(FileChannel in=read(original)) {
            DngCompactor.Stats stats=DngCompactor.compact(in,out,codec,GO,8);
            assertEquals(4,stats.tiles);assertTrue(stats.after<stats.before);
            try(FileChannel compact=read(out)) { DngCompactor.verify(in,compact,codec,GO); }
        }
        assertEquals(16,codec.decodes.get()); // original + replacement, independently in both passes
        assertEquals(hash,SafeDngReplace.hash(original));
        TiffTestReader a=new TiffTestReader(Files.readAllBytes(original.toPath())),b=new TiffTestReader(Files.readAllBytes(out.toPath()));
        compare(a,b,a.root(),b.root(),new HashSet<>(Arrays.asList(273,330,34665)));
        Map<Integer,TiffTestReader.Tag> ar=a.ifd(a.root().get(330).first()),br=b.ifd(b.root().get(330).first());
        compare(a,b,ar,br,new HashSet<>(Arrays.asList(324,325,52554,52555)));
        compare(a,b,a.ifd(a.root().get(34665).first()),b.ifd(b.root().get(34665).first()),Collections.emptySet());
        assertEquals(7,br.get(52554).first());assertEquals(0,br.get(52555).first());
        assertArrayEquals(blob(a,a.root().get(273).first(),a.root().get(279).first()),blob(b,b.root().get(273).first(),b.root().get(279).first()));
        // Retain real writer + compacted fixtures for optional independent tifffile inspection.
        Path artifacts=DngSampleMain.projectRoot().resolve("build/compact-fixtures");Files.createDirectories(artifacts);
        Files.copy(original.toPath(),artifacts.resolve("before.dng"),StandardCopyOption.REPLACE_EXISTING);
        Files.copy(out.toPath(),artifacts.resolve("after.dng"),StandardCopyOption.REPLACE_EXISTING);
    }
    static void compare(TiffTestReader a,TiffTestReader b,Map<Integer,TiffTestReader.Tag> left,
                        Map<Integer,TiffTestReader.Tag> right,Set<Integer> changed) {
        assertEquals(left.keySet(),right.keySet());
        for(int id:left.keySet()) {
            TiffTestReader.Tag x=left.get(id),y=right.get(id);assertEquals(x.type,y.type);assertEquals(x.count,y.count);
            if(!changed.contains(id)) { int unit=x.type==1||x.type==2?1:x.type==3?2:x.type==4||x.type==11?4:8;
                assertArrayEquals("tag "+id,blob(a,x.at,x.count*unit),blob(b,y.at,y.count*unit)); }
        }
    }
    static byte[] blob(TiffTestReader t,int at,int n) { return Arrays.copyOfRange(t.bytes,at,at+n); }
    @Test public void singleTileInlineOffsetsAndNoExif() throws Exception {
        File f=fixture(16,16,1,false),out=target();
        try(FileChannel in=read(f)) { DngCompactor.compact(in,out,new Codec(),GO,1);try(FileChannel b=read(out)){DngCompactor.verify(in,b,new Codec(),GO);} }
    }
    @Test public void progressFollowsVerifiedTilesAndPreservesIndependentVerification() throws Exception {
        File source=fixture(280,264,1,true),out=target();Codec codec=new Codec();List<Integer> progress=new ArrayList<>();
        try(FileChannel in=read(source)) {
            DngCompactor.compact(in,out,codec,GO,2,(done,total)-> {
                assertEquals(4,total);assertTrue(codec.decodes.get()>=2*done);progress.add(done);
            });
            try(FileChannel replacement=read(out)) { DngCompactor.verify(in,replacement,codec,GO); }
        }
        assertEquals(Arrays.asList(0,1,2,3,4),progress);assertEquals(16,codec.decodes.get());
    }
    @Test public void libraryInspectionNeverReadsPreviewOrTilePayloads() throws Exception {
        File f=fixture(280,264,1,true);TiffTestReader t=new TiffTestReader(Files.readAllBytes(f.toPath()));
        Map<Integer,TiffTestReader.Tag> raw=t.ifd(t.root().get(330).first());
        List<long[]> forbidden=new ArrayList<>();
        long thumb=t.root().get(273).first();forbidden.add(new long[]{thumb,thumb+t.root().get(279).first()});
        long[] offsets=raw.get(324).integers(),counts=raw.get(325).integers();
        for(int i=0;i<offsets.length;i++) forbidden.add(new long[]{offsets[i],offsets[i]+counts[i]});
        try(FileChannel in=new HeaderChannel(read(f),forbidden)) {
            DngCompactor.Header h=DngCompactor.inspect(in);assertEquals(1,h.effort);assertNull(h.skipReason);
            assertNull(DngCompactor.skipReason(in));
        }
    }
    /** Fail immediately on payload I/O, including reads whose result is discarded. */
    static final class HeaderChannel extends FileChannel {
        final FileChannel delegate;final List<long[]> forbidden;
        HeaderChannel(FileChannel delegate,List<long[]> forbidden) { this.delegate=delegate;this.forbidden=forbidden; }
        public int read(ByteBuffer dst,long pos)throws IOException {
            for(long[] r:forbidden) assertFalse("Payload read at "+pos,pos<r[1] && pos+dst.remaining()>r[0]);
            return delegate.read(dst,pos);
        }
        public long size()throws IOException { return delegate.size(); }
        protected void implCloseChannel()throws IOException { delegate.close(); }
        public int read(ByteBuffer b) { throw new UnsupportedOperationException(); }
        public long read(ByteBuffer[] b,int o,int n) { throw new UnsupportedOperationException(); }
        public int write(ByteBuffer b) { throw new UnsupportedOperationException(); }
        public int write(ByteBuffer b,long p) { throw new UnsupportedOperationException(); }
        public long write(ByteBuffer[] b,int o,int n) { throw new UnsupportedOperationException(); }
        public long position() { throw new UnsupportedOperationException(); }
        public FileChannel position(long p) { throw new UnsupportedOperationException(); }
        public FileChannel truncate(long s) { throw new UnsupportedOperationException(); }
        public void force(boolean m) { throw new UnsupportedOperationException(); }
        public long transferTo(long p,long n,WritableByteChannel c) { throw new UnsupportedOperationException(); }
        public long transferFrom(ReadableByteChannel c,long p,long n) { throw new UnsupportedOperationException(); }
        public MappedByteBuffer map(MapMode m,long p,long s) { throw new UnsupportedOperationException(); }
        public FileLock lock(long p,long s,boolean shared) { throw new UnsupportedOperationException(); }
        public FileLock tryLock(long p,long s,boolean shared) { throw new UnsupportedOperationException(); }
    }
    @Test public void previewsFromBothWritersReadOnlyTheirRgbStrip() throws Exception {
        File linear=fixture(16,16,1,true),cfa=folder.newFile();
        DngParams p=DngWriterTest.params(2,true);
        try(OutputStream out=new FileOutputStream(cfa)) {
            DngWriter.writeRaw16(out,ByteBuffer.allocate(p.width*p.height*2),p.width*2,p);
        }
        for(File f:Arrays.asList(linear,cfa)) {
            TiffTestReader t=new TiffTestReader(Files.readAllBytes(f.toPath()));
            int rawAt=t.root().get(330).first();Map<Integer,TiffTestReader.Tag> raw=t.ifd(rawAt);
            List<long[]> forbidden=new ArrayList<>();forbidden.add(new long[]{rawAt,rawAt+6+12*t.u16(rawAt)});
            long[] offsets=raw.get(324).integers(),counts=raw.get(325).integers();
            for(int i=0;i<offsets.length;i++) forbidden.add(new long[]{offsets[i],offsets[i]+counts[i]});
            try(FileChannel in=new HeaderChannel(read(f),forbidden)) {
                DngCompactor.Preview preview=DngCompactor.preview(in);
                assertEquals(t.root().get(256).first(),preview.width);assertEquals(t.root().get(257).first(),preview.height);
                assertArrayEquals(blob(t,t.root().get(273).first(),t.root().get(279).first()),preview.rgb);
            }
        }
    }
    @Test public void xmpBlobIsPreservedExactly() throws Exception {
        File f=fixture(16,16,1,true);byte[] bytes=Files.readAllBytes(f.toPath());TiffTestReader t=new TiffTestReader(bytes);
        // Replace optional BaselineExposure with self-contained XMP of the same byte length.
        int root=(int)t.u32(4),count=t.u16(root);List<byte[]> entries=new ArrayList<>();
        for(int i=0;i<count;i++) {
            byte[] e=Arrays.copyOfRange(bytes,root+2+12*i,root+14+12*i);
            ByteBuffer b=ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN);
            if((b.getShort(0)&65535)==50730) {
                b.putShort(0,(short)700).putShort(2,(short)1).putInt(4,8);
                System.arraycopy("xmp-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,bytes,b.getInt(8),8);
            }
            entries.add(e);
        }
        entries.sort(Comparator.comparingInt(e->ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN).getShort(0)&65535));
        for(int i=0;i<count;i++)System.arraycopy(entries.get(i),0,bytes,root+2+i*12,12);
        Files.write(f.toPath(),bytes);File out=target();
        try(FileChannel in=read(f)) { DngCompactor.compact(in,out,new Codec(),GO,1);try(FileChannel b=read(out)){DngCompactor.verify(in,b,new Codec(),GO);} }
        TiffTestReader compact=new TiffTestReader(Files.readAllBytes(out.toPath()));
        assertArrayEquals("xmp-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII),blob(compact,compact.root().get(700).at,8));
    }
    @Test public void mismatchAbortsAndLeavesOriginalUntouched() throws Exception {
        File f=fixture(16,16,1,true);String hash=SafeDngReplace.hash(f);
        Codec bad=new Codec() {
            public byte[] encode(short[] samples,int w,int h,int c)throws IOException {
                short[] changed=samples.clone();changed[11]^=1;return super.encode(changed,w,h,c);
            }
        };
        try(FileChannel in=read(f)) { IOException e=assertThrows(IOException.class,()->DngCompactor.compact(in,target(),bad,GO,2));assertTrue(e.getMessage().contains("mismatch")); }
        assertEquals(hash,SafeDngReplace.hash(f));
    }
    @Test public void cancellationAndThermalStopAreCooperative() throws Exception {
        File f=fixture(280,264,1,true);String hash=SafeDngReplace.hash(f);AtomicInteger calls=new AtomicInteger();
        DngCompactor.Check stop=()-> { if(calls.incrementAndGet()>6)throw new DngCompactor.Stopped("thermal MODERATE"); };
        try(FileChannel in=read(f)) { assertThrows(DngCompactor.Stopped.class,()->DngCompactor.compact(in,target(),new Codec(),stop,1)); }
        assertEquals(hash,SafeDngReplace.hash(f));
        try(FileChannel in=read(f)) { assertThrows(DngCompactor.Stopped.class,()->DngCompactor.compact(in,target(),new Codec(),()->{throw new DngCompactor.Stopped("cancelled");},8)); }
    }
    @Test public void rejectsAlreadyCompactedLj92ForeignAndUnexpectedPointers() throws Exception {
        File f=fixture(16,16,7,true);try(FileChannel in=read(f)){assertTrue(DngCompactor.skipReason(in).contains("effort"));}
        for(int mode=0;mode<5;mode++) {
            f=fixture(16,16,1,true);byte[] bytes=Files.readAllBytes(f.toPath());TiffTestReader t=new TiffTestReader(bytes);
            Map<Integer,TiffTestReader.Tag> raw=t.ifd(t.root().get(330).first());
            if(mode==0)t.b.putShort(raw.get(259).at,(short)7);
            if(mode==1)bytes[t.root().get(305).at]='X';
            if(mode==2)t.b.putInt(raw.get(324).at,8); // overlapping tile/IFD
            if(mode==3) { int root=(int)t.u32(4);t.b.putInt(root+2+12*t.u16(root),root); } // next IFD cycle
            if(mode==4) { int root=(int)t.u32(4);t.b.putShort(root+2,(short)253); } // unknown tag
            Files.write(f.toPath(),bytes);
            try(FileChannel in=read(f)){assertNotNull("mode "+mode,DngCompactor.skipReason(in));}
        }
    }
    @Test public void outputCreationIsExclusiveAndDiskPassDetectsCorruption() throws Exception {
        File f=fixture(16,16,1,true),out=target();
        try(FileChannel in=read(f)){DngCompactor.compact(in,out,new Codec(),GO,1);
            assertThrows(FileAlreadyExistsException.class,()->DngCompactor.compact(in,out,new Codec(),GO,1));}
        byte[] bytes=Files.readAllBytes(out.toPath());TiffTestReader t=new TiffTestReader(bytes);
        bytes[t.root().get(305).at]='X';Files.write(out.toPath(),bytes);
        try(FileChannel in=read(f);FileChannel b=read(out)) { assertThrows(IOException.class,()->DngCompactor.verify(in,b,new Codec(),GO)); }
    }
}
