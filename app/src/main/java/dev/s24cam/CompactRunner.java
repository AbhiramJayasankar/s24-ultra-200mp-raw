package dev.s24cam;

import android.content.*;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.system.*;
import android.util.Log;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import dev.s24cam.dng.*;

/** MediaStore adapter and one-file-at-a-time run loop, shared by job and probe. */
final class CompactRunner {
    private static final ReentrantLock RUN = new ReentrantLock();
    // Claim/current URI publication and library deletion are atomic with respect to each other.
    static final Object FILES=new Object();
    private static volatile AtomicBoolean activeCancellation;
    private static final long MIN_AGE_MS = 120_000;
    private static final String TAG = SamsungCam.TAG;
    private CompactRunner() {}

    static final class Item {
        final long id,modified,bytes,takenMs; final boolean pending; final String name; final Uri uri;
        Item(long id,long modified,boolean pending,String name,Uri uri,long bytes,long takenMs) {
            this.id=id;this.modified=modified;this.pending=pending;this.name=name;this.uri=uri;
            this.bytes=bytes;this.takenMs=takenMs;
        }
    }
    static List<Item> items(Context context) {
        ContentResolver resolver=context.getContentResolver();
        Uri collection=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] projection={MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,MediaStore.Images.Media.IS_PENDING,MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_TAKEN,MediaStore.Images.Media.DATE_ADDED};
        String where=MediaStore.Images.Media.RELATIVE_PATH+"=? AND "+MediaStore.Images.Media.OWNER_PACKAGE_NAME+"=?";
        Bundle args=new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,where);
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{DngSaver.FOLDER+"/",context.getPackageName()});
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
        args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,MediaStore.Images.Media._ID+" ASC");
        List<Item> result=new ArrayList<>();
        try(Cursor c=resolver.query(collection,projection,args,null)) {
            if(c!=null) while(c.moveToNext()) {
                String name=c.getString(1);
                if(name!=null && name.toLowerCase(Locale.ROOT).endsWith(".dng")) {
                    long id=c.getLong(0);
                    long taken=c.getLong(5);
                    result.add(new Item(id,c.getLong(2)*1000,c.getInt(3)!=0,name,ContentUris.withAppendedId(collection,id),
                            c.getLong(4),taken>0?taken:c.getLong(6)*1000));
                }
            }
        }
        return result;
    }
    static boolean hasEligible(Context context) throws IOException {
        if(new File(context.getFilesDir(),"compact/journal.json").exists()) return true;
        for(Item item:items(context)) {
            if(item.pending || DngSaver.isWriting(item.uri)) continue;
            try(FileChannel source=read(context.getContentResolver(),item.uri)) {
                if(DngCompactor.inspect(source).skipReason==null) return true;
            } catch(FileNotFoundException e) {
                if(new MediaStoreAdapter(context).exists(item.uri.toString())) throw e;
            }
        }
        return false;
    }
    /** Returns true if a later charging run should retry. */
    static void stop(Context context,AtomicBoolean cancelled) {
        cancelled.set(true);
        if(activeCancellation==cancelled) CompactStatus.phase(context,CompactStatus.Phase.PAUSED_STOPPED,"Paused: compression stopped");
    }
    static boolean run(Context context,AtomicBoolean cancelled) {
        if(!RUN.tryLock()) { log("skip: another compaction run is active"); return true; }
        boolean retry=false;
        try {
            activeCancellation=cancelled;
            CompactStatus.start(context);
            SafeDngReplace transaction=new SafeDngReplace(new File(context.getFilesDir(),"compact"),new MediaStoreAdapter(context));
            // Recovery is deliberately unconditional: restoring a partial original takes priority.
            synchronized(FILES) { transaction.recover(); }
            if(!JxlNative.available()) {
                CompactStatus.phase(context,CompactStatus.Phase.ERROR,"JPEG XL compression unavailable");retry=true;return true;
            }
            PowerManager power=context.getSystemService(PowerManager.class);
            DngCompactor.Check check=()-> {
                if(cancelled.get() || Thread.currentThread().isInterrupted()) throw new DngCompactor.Stopped("cancelled");
                if(power.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_MODERATE)
                    throw new DngCompactor.Stopped("thermal status MODERATE or higher; retry later");
            };
            DngCompactor.TileCodec codec=DngCompactor.nativeCodec();
            List<Item> items=items(context);
            int left=0;
            for(Item item:items) {
                check.check();
                if(item.pending || DngSaver.isWriting(item.uri)) continue;
                try(FileChannel source=read(context.getContentResolver(),item.uri)) {
                    if(DngCompactor.inspect(source).skipReason==null) left++;
                } catch(FileNotFoundException e) {
                    if(new MediaStoreAdapter(context).exists(item.uri.toString())) throw e;
                } catch(IOException e) { log(item.name+" header unavailable: "+e.getMessage()); }
            }
            CompactStatus.left(context,left);
            for(Item item:items) {
                check.check();
                if(item.pending || DngSaver.isWriting(item.uri)) { log(item.name+" skip: pending/currently being saved"); retry=true; continue; }
                if(System.currentTimeMillis()-item.modified<MIN_AGE_MS) { log(item.name+" skip: modified less than 2 minutes ago"); retry=true; continue; }
                File temp=new File(context.getFilesDir(),"compact/"+item.id+".tmp");
                long wall=System.nanoTime(),cpu=android.os.Process.getElapsedCpuTime();
                synchronized(FILES) {
                    // A library deletion may have happened since the initial query.
                    if(!ready(context,item)) { retry|=new MediaStoreAdapter(context).exists(item.uri.toString());continue; }
                    CompactStatus.file(context,item.name,item.uri);
                }
                try(FileChannel original=read(context.getContentResolver(),item.uri)) {
                    String reason=DngCompactor.skipReason(original);
                    if(reason!=null) { log(item.name+" skip: "+reason); continue; }
                    long size=original.size(),free=new StatFs(context.getFilesDir().getPath()).getAvailableBytes();
                    if(!SafeDngReplace.enoughSpace(free,size)) {
                        log(item.name+" skip: free space needs original size + 512 MB");
                        CompactStatus.result(context,0,"Not enough free space: need original size + 512 MB");retry=true;continue;
                    }
                    String originalHash=SafeDngReplace.hash(original);
                    DngCompactor.Stats stats=DngCompactor.compact(original,temp,codec,check,
                            Math.max(1,Math.min(8,Runtime.getRuntime().availableProcessors())),
                            (done,total)->CompactStatus.progress(context,done,total));
                    if(stats.after>=stats.before) {
                        log(item.name+" skip: replacement is not smaller");
                        CompactStatus.result(context,0,item.name+": already at smallest size");continue;
                    }
                    // Requery before publication; an external change must never be overwritten.
                    if(!ready(context,item)) {
                        log(item.name+" abort: MediaStore item changed/pending/recent");
                        retry|=new MediaStoreAdapter(context).exists(item.uri.toString());continue;
                    }
                    transaction.replace(item.uri.toString(),temp,size,originalHash,codec,check);
                    CompactStatus.result(context,stats.before-stats.after,String.format(Locale.US,"%s %.0f MB -> %.0f MB",
                            item.name,stats.before/1e6,stats.after/1e6));
                    Log.i(TAG,String.format(Locale.US,"Compact: %s %.2f -> %.2f MB, %.2f s, %d tiles verified twice, CPU %.2f s",
                            item.name,stats.before/1e6,stats.after/1e6,(System.nanoTime()-wall)/1e9,stats.tiles,
                            (android.os.Process.getElapsedCpuTime()-cpu)/1000.0));
                } catch(DngCompactor.Stopped e) { throw e; }
                catch(FileNotFoundException e) {
                    if(new MediaStoreAdapter(context).exists(item.uri.toString())) {
                        CompactStatus.phase(context,CompactStatus.Phase.ERROR,item.name+": cannot open DNG");retry=true;
                    } else CompactStatus.result(context,0,item.name+": already deleted");
                    if(transaction.hasJournal()) { retry=true;return true; }
                }
                catch(IOException|RuntimeException|LinkageError e) {
                    Log.e(TAG,"Compact: "+item.name+" abort: "+e.getMessage(),e); retry=true;
                    CompactStatus.phase(context,CompactStatus.Phase.ERROR,item.name+": "+e.getMessage());
                    // A journal means the source might be partial. Never touch temp or start another file.
                    if(transaction.hasJournal()) return true;
                } finally {
                    if(!transaction.hasJournal() && temp.exists() && !temp.delete()) log("could not remove unjournaled temp "+temp);
                    synchronized(FILES) { CompactStatus.file(context,null,null); }
                }
            }
        } catch(DngCompactor.Stopped e) {
            retry=true;log("abort: "+e.getMessage());
            boolean warm=e.getMessage()!=null && e.getMessage().startsWith("thermal");
            CompactStatus.phase(context,warm?CompactStatus.Phase.PAUSED_WARM:CompactStatus.Phase.PAUSED_STOPPED,
                    warm?"Paused: phone is warm":"Paused: compression stopped");
        } catch(IOException|RuntimeException|LinkageError e) {
            retry=true;Log.e(TAG,"Compact: recovery/run aborted; preserving journal and temp",e);
            CompactStatus.phase(context,CompactStatus.Phase.ERROR,"Compression failed: "+e.getMessage());
        } finally {
            activeCancellation=null;
            try { CompactStatus.finish(context,retry); } finally { RUN.unlock(); }
        }
        return retry;
    }
    private static boolean ready(Context context,Item item) {
        for(Item now:items(context)) if(now.id==item.id) return !now.pending && !DngSaver.isWriting(now.uri)
                && now.modified==item.modified && System.currentTimeMillis()-now.modified>=MIN_AGE_MS;
        return false;
    }
    static FileChannel read(ContentResolver resolver,Uri uri) throws IOException {
        ParcelFileDescriptor fd=resolver.openFileDescriptor(uri,"r");
        if(fd==null) throw new IOException("MediaStore refused read "+uri);
        return new ParcelFileDescriptor.AutoCloseInputStream(fd).getChannel();
    }
    private static void log(String reason) { Log.i(TAG,"Compact: "+reason); }

    private static final class MediaStoreAdapter implements SafeDngReplace.Store {
        final Context context; final ContentResolver resolver;
        MediaStoreAdapter(Context context) { this.context=context;resolver=context.getContentResolver(); }
        public boolean exists(String value) throws IOException {
            Bundle args=new Bundle();
            args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
            args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,MediaStore.MATCH_INCLUDE);
            try(Cursor c=resolver.query(uri(value),new String[]{MediaStore.Images.Media._ID},args,null)) {
                if(c==null) throw new IOException("Cannot confirm whether MediaStore item exists");
                return c.moveToFirst();
            }
        }
        private Uri uri(String value) throws IOException {
            Uri uri=Uri.parse(value);
            if(!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()) || ContentUris.parseId(uri)<=0)
                throw new IOException("Invalid journal MediaStore URI");
            return uri;
        }
        public FileChannel read(String value) throws IOException { return CompactRunner.read(resolver,uri(value)); }
        public void overwrite(String value,File temp) throws IOException {
            ParcelFileDescriptor fd=resolver.openFileDescriptor(uri(value),"rwt");
            if(fd==null) throw new IOException("MediaStore refused overwrite");
            try(ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                FileInputStream input=new FileInputStream(temp)) {
                byte[] buffer=new byte[1<<20]; int n;
                while((n=input.read(buffer))!=-1) out.write(buffer,0,n);
                out.flush();out.getFD().sync();
            }
        }
        public void refresh(String value,long size) throws IOException {
            // DATA is readable for our own item under scoped storage. scanFile takes a filesystem path.
            String path=null;
            try(Cursor c=resolver.query(uri(value),new String[]{MediaStore.Images.Media.DATA},null,null,null)) {
                if(c!=null && c.moveToFirst()) path=c.getString(0);
            }
            if(path==null) throw new IOException("Cannot find own item path for media scan; retain journal");
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
            AtomicBoolean scanned=new AtomicBoolean();
            MediaScannerConnection.scanFile(context,new String[]{path},new String[]{"image/x-adobe-dng"},
                    (p,u)->{scanned.set(u!=null);done.countDown();});
            try {
                if(!done.await(30,java.util.concurrent.TimeUnit.SECONDS) || !scanned.get())
                    throw new IOException("Media scan incomplete; retain journal");
            } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException("Media scan interrupted; retain journal",e); }
            try(Cursor c=resolver.query(uri(value),new String[]{MediaStore.Images.Media.SIZE},null,null,null)) {
                if(c==null || !c.moveToFirst() || c.getLong(0)!=size) throw new IOException("MediaStore size not refreshed; retain journal");
            }
        }
        public void syncDirectory(File dir) throws IOException {
            try {
                java.io.FileDescriptor fd=Os.open(dir.getPath(),OsConstants.O_RDONLY,0);
                try { Os.fsync(fd); } finally { Os.close(fd); }
            } catch(ErrnoException e) { throw new IOException("Directory fsync failed",e); }
        }
        public void log(String reason) { CompactRunner.log(reason); }
    }
}
