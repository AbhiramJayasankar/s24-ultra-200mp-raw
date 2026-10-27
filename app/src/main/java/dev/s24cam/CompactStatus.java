package dev.s24cam;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide status. Listeners run on the changing thread; marshal to the UI thread yourself. */
public final class CompactStatus {
    public enum Phase { IDLE, WAITING_FOR_CHARGER, RUNNING, PAUSED_WARM, PAUSED_STOPPED, DONE, ERROR }
    public static final class Snapshot {
        public final Phase phase;
        public final String currentName;
        public final Uri currentUri;
        public final int tilesDone,tilesTotal,filesLeft;
        public final long savedBytesTotal;
        public final int compressedTotal;
        public final String lastMessage;
        public final long lastChangeMs;
        Snapshot(Phase phase,String name,Uri uri,int done,int total,int left,long saved,int count,String message,long time) {
            this.phase=phase;currentName=name;currentUri=uri;tilesDone=done;tilesTotal=total;filesLeft=left;
            savedBytesTotal=saved;compressedTotal=count;lastMessage=message;lastChangeMs=time;
        }
    }
    /** Small Android-free persistence seam for desktop transition/restart tests. */
    interface Persistence {
        Saved load();
        void save(Saved saved);
    }
    static final class Saved {
        final Phase phase;final long bytes;final int count;final String message;
        Saved(Phase phase,long bytes,int count,String message) {
            this.phase=phase;this.bytes=bytes;this.count=count;this.message=message;
        }
    }
    static final class Model {
        final Persistence persistence;
        Snapshot value;
        boolean active;
        long progressAt;
        Model(Persistence persistence,boolean pending,long now) {
            this.persistence=persistence;Saved s=persistence.load();
            Phase phase=pending?Phase.WAITING_FOR_CHARGER:s.phase==Phase.RUNNING||s.phase==Phase.WAITING_FOR_CHARGER?Phase.IDLE:s.phase;
            value=new Snapshot(phase,null,null,0,0,0,s.bytes,s.count,s.message,now);
            persist();
        }
        boolean set(Phase phase,String name,Uri uri,int done,int total,int left,long bytes,int count,String message,long now) {
            Snapshot s=value;
            if(s.phase==phase && Objects.equals(s.currentName,name) && Objects.equals(s.currentUri,uri)
                    && s.tilesDone==done && s.tilesTotal==total && s.filesLeft==left && s.savedBytesTotal==bytes
                    && s.compressedTotal==count && Objects.equals(s.lastMessage,message)) return false;
            value=new Snapshot(phase,name,uri,done,total,left,bytes,count,message,now);
            if(s.phase!=phase || s.savedBytesTotal!=bytes || s.compressedTotal!=count || !Objects.equals(s.lastMessage,message)) persist();
            return true;
        }
        void persist() { Snapshot s=value;persistence.save(new Saved(s.phase,s.savedBytesTotal,s.compressedTotal,s.lastMessage)); }
        boolean phase(Phase phase,String message,long now) {
            Snapshot s=value;return set(phase,s.currentName,s.currentUri,s.tilesDone,s.tilesTotal,s.filesLeft,
                    s.savedBytesTotal,s.compressedTotal,message,now);
        }
        boolean waiting(long now) {
            if(active) return false;
            return phase(Phase.WAITING_FOR_CHARGER,value.lastMessage,now);
        }
        boolean start(long now) {
            active=true;Snapshot s=value;
            return set(Phase.RUNNING,null,null,0,0,s.filesLeft,s.savedBytesTotal,s.compressedTotal,s.lastMessage,now);
        }
        boolean file(String name,Uri uri,long now) {
            progressAt=0;Snapshot s=value;
            return set(s.phase,name,uri,0,0,s.filesLeft,s.savedBytesTotal,s.compressedTotal,s.lastMessage,now);
        }
        boolean progress(int done,int total,long now) {
            Snapshot s=value;
            if(s.tilesTotal==total && now-progressAt<250) return false;
            progressAt=now;
            return set(s.phase,s.currentName,s.currentUri,done,total,s.filesLeft,s.savedBytesTotal,s.compressedTotal,s.lastMessage,now);
        }
        boolean left(int left,long now) {
            Snapshot s=value;return set(s.phase,s.currentName,s.currentUri,s.tilesDone,s.tilesTotal,Math.max(0,left),
                    s.savedBytesTotal,s.compressedTotal,s.lastMessage,now);
        }
        boolean result(long saved,String message,long now) {
            Snapshot s=value;
            return set(s.phase,s.currentName,s.currentUri,saved>0?s.tilesTotal:s.tilesDone,s.tilesTotal,Math.max(0,s.filesLeft-(saved>0?1:0)),
                    s.savedBytesTotal+Math.max(0,saved),s.compressedTotal+(saved>0?1:0),message,now);
        }
        boolean finish(boolean retry,long now) {
            active=false;Snapshot s=value;
            Phase phase=s.phase==Phase.RUNNING?(retry?Phase.WAITING_FOR_CHARGER:s.filesLeft==0?Phase.DONE:Phase.IDLE):s.phase;
            return set(phase,null,null,0,0,s.filesLeft,s.savedBytesTotal,s.compressedTotal,
                    s.lastMessage==null?(retry?"Waiting for charger":"Nothing left to compress"):s.lastMessage,now);
        }
    }
    private static final Object LOCK=new Object();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS=new CopyOnWriteArrayList<>();
    private static Model model;
    private CompactStatus() {}
    private static boolean pending(Context c) {
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        return scheduler!=null && scheduler.getPendingJob(CompactJobService.JOB_ID)!=null;
    }
    private static Model model(Context c) {
        if(model==null) {
            SharedPreferences prefs=c.getApplicationContext().getSharedPreferences("compact-status",Context.MODE_PRIVATE);
            model=new Model(new Persistence() {
                public Saved load() {
                    Phase phase;
                    try { phase=Phase.valueOf(prefs.getString("phase",Phase.IDLE.name())); }
                    catch(IllegalArgumentException e) { phase=Phase.IDLE; }
                    return new Saved(phase,prefs.getLong("savedBytesTotal",0),prefs.getInt("compressedTotal",0),prefs.getString("lastMessage",null));
                }
                public void save(Saved s) {
                    prefs.edit().putString("phase",s.phase.name()).putLong("savedBytesTotal",s.bytes)
                            .putInt("compressedTotal",s.count).putString("lastMessage",s.message).apply();
                }
            },pending(c),System.currentTimeMillis());
        }
        return model;
    }
    public static Snapshot get(Context c) {
        boolean changed=false;Snapshot snapshot;
        synchronized(LOCK) {
            Model m=model(c);
            // A finishing job remains pending until jobFinished reaches the main thread.
            // Do not let that old registration erase DONE or a specific pause/error reason.
            if(!m.active && (m.value.phase==Phase.IDLE || m.value.phase==Phase.WAITING_FOR_CHARGER)) {
                if(pending(c)) changed=m.waiting(System.currentTimeMillis());
                else if(m.value.phase==Phase.WAITING_FOR_CHARGER)
                    changed=m.phase(Phase.IDLE,m.value.lastMessage,System.currentTimeMillis());
            }
            snapshot=m.value;
        }
        if(changed) notifyListeners();
        return snapshot;
    }
    public static void addListener(Runnable r) { LISTENERS.addIfAbsent(Objects.requireNonNull(r)); }
    public static void removeListener(Runnable r) { LISTENERS.remove(r); }
    private static void notifyListeners() {
        for(Runnable r:LISTENERS) try { r.run(); }
        catch(RuntimeException e) { android.util.Log.w(SamsungCam.TAG,"Compact status listener failed",e); }
    }
    private interface Change { boolean apply(Model m,long now); }
    private static void change(Context c,Change change) {
        boolean changed;
        synchronized(LOCK) { changed=change.apply(model(c),System.currentTimeMillis()); }
        if(changed) notifyListeners();
    }
    static void scheduled(Context c) { change(c,(m,t)->m.waiting(t)); }
    static void start(Context c) { change(c,(m,t)->m.start(t)); }
    static void file(Context c,String name,Uri uri) { change(c,(m,t)->m.file(name,uri,t)); }
    static void progress(Context c,int done,int total) { change(c,(m,t)->m.progress(done,total,t)); }
    static void left(Context c,int left) { change(c,(m,t)->m.left(left,t)); }
    static void result(Context c,long saved,String message) { change(c,(m,t)->m.result(saved,message,t)); }
    static void phase(Context c,Phase phase,String message) { change(c,(m,t)->m.phase(phase,message,t)); }
    static void finish(Context c,boolean retry) { change(c,(m,t)->m.finish(retry,t)); }
    /** Safe from any thread. Uses the same run path as the probe, with thermal/space checks. */
    public static void runNow(Context c) {
        Context app=c.getApplicationContext();
        new Thread(()-> {
            if(CompactRunner.run(app,new AtomicBoolean())) CompactJobService.schedule(app);
        },"Compact now").start();
    }
}
