package dev.s24cam;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Size;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.*;

import dev.s24cam.dng.DngCompactor;
import dev.s24cam.dng.SafeDngReplace;

/** Own MediaStore DNGs. Run list, delete, thumbnail and freeBytes on a background thread. */
public final class PhotoLibrary {
    public enum State { SAVING, COMPRESSING, COMPRESSED, WAITING, ORIGINAL }
    public static final class Photo {
        public final long id;
        public final Uri uri;
        public final String name;
        public final long bytes,takenMs;
        public final String mode;
        public final State state;
        Photo(CompactRunner.Item item,State state) {
            id=item.id;uri=item.uri;name=item.name;bytes=item.bytes;takenMs=item.takenMs;
            mode=mode(name);this.state=state;
        }
    }
    public static final class DeleteResult {
        public final int deleted;
        public final long freedBytes;
        public final List<String> skipped;
        DeleteResult(int deleted,long freedBytes,List<String> skipped) {
            this.deleted=deleted;this.freedBytes=freedBytes;
            this.skipped=Collections.unmodifiableList(new ArrayList<>(skipped));
        }
    }
    private PhotoLibrary() {}
    static String mode(String name) {
        String n=name==null?"":name.toUpperCase(Locale.ROOT);
        if(n.startsWith("S24HR_200MP_")) return "200MP";
        if(n.startsWith("S24HR_50MP_")) {
            // Historical main and tele saves share the same name. Only explicit markers identify 5x.
            return n.matches(".*(?:_|-)5X(?:_|-|\\.).*")?"50MP 5x":"50MP";
        }
        return "DNG";
    }
    /** Pure precedence logic, also used by desktop tests with real writer headers. */
    static State state(boolean pending,boolean writing,boolean compressing,long effort,String skipReason) {
        if(pending || writing) return State.SAVING;
        if(compressing) return State.COMPRESSING;
        if(effort>=7) return State.COMPRESSED;
        return skipReason==null?State.WAITING:State.ORIGINAL;
    }
    public static List<Photo> list(Context c) {
        List<Photo> photos=new ArrayList<>();
        Uri current=CompactStatus.get(c).currentUri;
        for(CompactRunner.Item item:CompactRunner.items(c)) {
            boolean writing=DngSaver.isWriting(item.uri);
            boolean compressing=item.uri.equals(current);
            long effort=-1;String reason="Cannot read DNG header";
            if(!item.pending && !writing && !compressing) {
                try(FileChannel source=CompactRunner.read(c.getContentResolver(),item.uri)) {
                    DngCompactor.Header header=DngCompactor.inspect(source);
                    effort=header.effort;reason=header.skipReason;
                } catch(IOException|RuntimeException e) { /* A disappearing/unreadable item is ORIGINAL for this snapshot. */ }
            }
            photos.add(new Photo(item,state(item.pending,writing,compressing,effort,reason)));
        }
        photos.sort(Comparator.comparingLong(PhotoLibrary::captureMs).reversed()
                .thenComparing(Comparator.comparingLong((Photo p)->p.id).reversed()));
        return photos;
    }
    public static long freeBytes() { return DngSaver.freeBytes(); }

    static String deleteSkipReason(boolean own,boolean pending,boolean writing,boolean compressing,
                                   boolean journaled,boolean journalUnreadable) {
        if(!own) return "not an app-owned DNG in Pictures/S24 HighRes (or already deleted)";
        if(pending || writing) return "currently being saved";
        if(compressing) return "currently being compressed";
        if(journalUnreadable) return "compaction journal cannot be read; recovery required";
        if(journaled) return "referenced by a compaction recovery journal";
        return null;
    }
    /** Rechecks ownership, pending, active-file and journal guards under the compaction claim lock. */
    public static DeleteResult delete(Context c,Collection<Uri> uris) {
        int deleted=0;long freed=0;List<String> skipped=new ArrayList<>();
        for(Uri uri:new LinkedHashSet<>(uris)) {
            synchronized(CompactRunner.FILES) {
                String name=String.valueOf(uri);
                try {
                    CompactRunner.Item item=null;
                    for(CompactRunner.Item candidate:CompactRunner.items(c))
                        if(candidate.uri.equals(uri)) { item=candidate;break; }
                    if(item!=null) name=item.name;
                    String journal=null;boolean unreadable=false;
                    try { journal=SafeDngReplace.journalUri(new File(c.getFilesDir(),"compact")); }
                    catch(IOException|RuntimeException e) { unreadable=true; }
                    String reason=deleteSkipReason(item!=null,item!=null && item.pending,DngSaver.isWriting(uri),
                            uri!=null && uri.equals(CompactStatus.get(c).currentUri),
                            uri!=null && uri.toString().equals(journal),unreadable);
                    if(reason!=null) { skipped.add(name+": "+reason);continue; }
                    String where=MediaStore.Images.Media.OWNER_PACKAGE_NAME+"=? AND "
                            +MediaStore.Images.Media.RELATIVE_PATH+"=? AND "+MediaStore.Images.Media.IS_PENDING+"=0";
                    int count=c.getContentResolver().delete(uri,where,new String[]{c.getPackageName(),DngSaver.FOLDER+"/"});
                    if(count>0) { deleted+=count;freed+=item.bytes; }
                    else skipped.add(name+": item disappeared or changed before deletion");
                } catch(SecurityException e) { skipped.add(name+": permission or user approval required"); }
                catch(RuntimeException e) { skipped.add(name+": deletion failed ("+e.getClass().getSimpleName()+")"); }
            }
        }
        return new DeleteResult(deleted,freed,skipped);
    }
    public static Bitmap thumbnail(Context c,Photo p,int sizePx) {
        if(sizePx<=0) throw new IllegalArgumentException("sizePx must be positive");
        try {
            Bitmap image=c.getContentResolver().loadThumbnail(p.uri,new Size(sizePx,sizePx),null);
            if(image!=null) return image;
        }
        catch(IOException|RuntimeException|OutOfMemoryError e) { /* Fall back to our small IFD0 RGB strip. */ }
        try(FileChannel source=CompactRunner.read(c.getContentResolver(),p.uri)) {
            DngCompactor.Preview preview=DngCompactor.preview(source);
            int[] pixels=new int[preview.width*preview.height];
            for(int i=0;i<pixels.length;i++) pixels[i]=0xff000000|((preview.rgb[3*i]&255)<<16)
                    |((preview.rgb[3*i+1]&255)<<8)|(preview.rgb[3*i+2]&255);
            Bitmap image=Bitmap.createBitmap(pixels,preview.width,preview.height,Bitmap.Config.ARGB_8888);
            Matrix matrix=new Matrix();
            switch(preview.orientation) {
                case 2: matrix.setScale(-1,1);break;
                case 3: matrix.setRotate(180);break;
                case 4: matrix.setScale(1,-1);break;
                case 5: matrix.setRotate(90);matrix.postScale(-1,1);break;
                case 6: matrix.setRotate(90);break;
                case 7: matrix.setRotate(90);matrix.postScale(1,-1);break;
                case 8: matrix.setRotate(270);break;
            }
            float scale=(float)sizePx/Math.max(preview.width,preview.height);
            matrix.postScale(scale,scale);
            Bitmap result=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),matrix,true);
            if(result!=image) image.recycle();
            return result;
        } catch(IOException|RuntimeException|OutOfMemoryError e) { return null; }
    }

    /**
     * When the photo was taken. The file name carries the local capture time;
     * MediaStore's dates move when background compression rewrites the file.
     */
    static long captureMs(Photo p) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("_(\\d{8}_\\d{6})").matcher(p.name);
        if (m.find()) try {
            java.util.Date d = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(m.group(1));
            if (d != null) return d.getTime();
        } catch (java.text.ParseException ignored) { }
        return p.takenMs;
    }
}
