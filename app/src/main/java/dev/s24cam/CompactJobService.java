package dev.s24cam;

import android.app.job.*;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persisted charging-only work; JobScheduler owns wakefulness and retry backoff. */
public final class CompactJobService extends JobService {
    private static final int JOB_ID=240007;
    private static final long DELAY=120_000;
    private Run current;
    private static final class Run {
        final AtomicBoolean cancelled=new AtomicBoolean(); final JobParameters params;
        Run(JobParameters params) { this.params=params; }
    }
    static void schedule(Context context) {
        try {
            JobScheduler scheduler=context.getSystemService(JobScheduler.class);
            if(scheduler.getPendingJob(JOB_ID)!=null) return;
            JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(context,CompactJobService.class))
                    .setRequiresCharging(true).setRequiresBatteryNotLow(true).setPersisted(true)
                    .setMinimumLatency(DELAY).setBackoffCriteria(5*60_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
            if(scheduler.schedule(job)!=JobScheduler.RESULT_SUCCESS) Log.w(SamsungCam.TAG,"Compact: scheduling rejected");
        } catch(RuntimeException e) { Log.w(SamsungCam.TAG,"Compact: scheduling failed",e); }
    }
    static void scheduleOnStart(Context context) {
        Context app=context.getApplicationContext();
        new Thread(()-> {
            try { if(CompactRunner.hasEligible(app)) schedule(app); }
            catch(Exception e) { Log.w(SamsungCam.TAG,"Compact: startup query failed",e); }
        },"Compact startup").start();
    }
    @Override public boolean onStartJob(JobParameters params) {
        Run run=new Run(params); current=run;
        new Thread(()-> {
            boolean retry=CompactRunner.run(getApplicationContext(),run.cancelled);
            new Handler(Looper.getMainLooper()).post(()-> {
                if(current==run) { current=null;jobFinished(run.params,retry); }
            });
        },"Compact job").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if(current!=null && current.params==params) { current.cancelled.set(true);current=null; }
        return true;
    }
}
