package com.mg4.store;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Controllo in background, ogni 6 ore con la rete: se ci sono aggiornamenti per le app installate
 * mostra una notifica (una volta sola per lo stesso gruppo di versioni).
 */
public final class UpdateJob extends JobService {
    private static final int JOB_ID = 6801;
    private static final int NOTIF_ID = 6801;
    private static final String CHANNEL = "updates";

    private Thread worker;

    /** Programma il controllo periodico (se e' gia' programmato non fa niente). */
    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    /** Toglie la notifica (quando l'utente apre lo store). */
    static void clear(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        worker = new Thread(() -> {
            try {
                check(getApplicationContext());
            } catch (Throwable ignored) {
            }
            jobFinished(params, false);
        }, "store-check");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }

    private static void check(Context c) throws Exception {
        if (!Firmware.isMg4()) return;
        String fw = Firmware.kind(c);
        Api api = new Api(c);
        JSONObject cat;
        try {
            cat = api.catalog();
        } catch (Api.Fail f) {
            if (f.code != 403) return;
            api.register(fw);        // auto non ancora registrata (o archivio svuotato): si ripresenta
            cat = api.catalog();
        }
        List<String> names = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Item it : Item.parse(cat, fw, c.getPackageManager())) {
            if (it.state == Item.UPDATE) {
                names.add(it.name);
                keys.add(it.id + ":" + it.version);
            }
        }
        SharedPreferences p = c.getSharedPreferences("store", Context.MODE_PRIVATE);
        if (names.isEmpty()) {
            p.edit().remove("notified").apply();
            clear(c);
            return;
        }
        Collections.sort(keys);
        String key = String.join(",", keys);
        if (key.equals(p.getString("notified", ""))) return;   // gia' avvisato per queste versioni
        notify(c, p, names);
        p.edit().putString("notified", key).apply();
    }

    private static void notify(Context c, SharedPreferences p, List<String> names) {
        L.init(c, p.getString("lang", L.AUTO));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, L.s(R.string.notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, StoreActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(L.f(R.string.updates_available, names.size()))
                .setContentText(String.join(", ", names))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_ID, n);
    }
}
