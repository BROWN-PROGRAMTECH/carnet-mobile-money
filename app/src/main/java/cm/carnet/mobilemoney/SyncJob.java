package cm.carnet.mobilemoney;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Sauvegarde en arrière-plan : toutes les heures dès qu'il y a du réseau, et peu après chaque nouveau SMS.
 * Utilise le planificateur intégré d'Android (aucune bibliothèque externe). Les coupures de réseau sont gérées par Android.
 */
public class SyncJob extends JobService {
    static final int PERIODIC_ID = 4201, SOON_ID = 4202;

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!Cloud.configured() || !Cloud.loggedIn(this)) return false;
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean retry = false;
            try {
                Cloud.sync(app);
            } catch (Exception e) {
                retry = params.getJobId() == SOON_ID;
            }
            jobFinished(params, retry);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static JobScheduler scheduler(Context c) {
        return (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    /** Sauvegarde toutes les heures, conservée après redémarrage du téléphone. */
    static void schedule(Context c) {
        if (!Cloud.configured() || !Cloud.loggedIn(c)) return;
        try {
            JobInfo job = new JobInfo.Builder(PERIODIC_ID, new ComponentName(c, SyncJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(60 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            JobScheduler s = scheduler(c);
            if (s != null) s.schedule(job);
        } catch (Exception ignored) {}
    }

    /** Sauvegarde dès que possible (au plus tôt dans 30 secondes, pour regrouper plusieurs SMS). */
    static void soon(Context c) {
        if (!Cloud.configured() || !Cloud.loggedIn(c)) return;
        try {
            JobInfo job = new JobInfo.Builder(SOON_ID, new ComponentName(c, SyncJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(30 * 1000L)
                    .build();
            JobScheduler s = scheduler(c);
            if (s != null) s.schedule(job);
        } catch (Exception ignored) {}
    }

    static void cancel(Context c) {
        JobScheduler s = scheduler(c);
        if (s == null) return;
        s.cancel(PERIODIC_ID);
        s.cancel(SOON_ID);
    }
}
