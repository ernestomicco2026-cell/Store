package com.mg4.store;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;

import java.io.OutputStream;

/**
 * Installa un APK con PackageInstaller, come gli aggiornamenti del launcher: lo store e' firmato con la chiave
 * platform del sistema, quindi non serve nessuna conferma. Se il permesso mancasse, Android mostra la sua schermata.
 */
public final class Installer {
    public interface Result {
        void done(boolean ok, boolean needsConfirm, String message);
    }

    private static final String ACTION = "com.mg4.store.INSTALL_RESULT";

    private Installer() {}

    public static void install(final Context ctx, String pkg, byte[] apk, final Result cb) throws Exception {
        final Context app = ctx.getApplicationContext();
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (pkg != null && !pkg.isEmpty()) p.setAppPackageName(pkg);
        p.setSize(apk.length);
        // memoria interna esplicita: il firmware MG blocca le app non di sistema sull'archivio "automatico"
        p.setInstallLocation(PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        // come "adb install -r": INSTALL_REPLACE_EXISTING (0x2) + INSTALL_INTERNAL (0x10)
        try {
            java.lang.reflect.Field f = PackageInstaller.SessionParams.class.getField("installFlags");
            f.setInt(p, f.getInt(p) | 0x2 | 0x10);
        } catch (Throwable ignored) {
        }
        final int id = pi.createSession(p);
        final String action = ACTION + "." + id;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    cb.done(false, true, null);
                    return;
                }
                try {
                    app.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                cb.done(st == PackageInstaller.STATUS_SUCCESS, false, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        };
        app.registerReceiver(r, new IntentFilter(action));
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream os = s.openWrite("base.apk", 0, apk.length)) {
                os.write(apk);
                s.fsync(os);
            }
            PendingIntent pend = PendingIntent.getBroadcast(app, id, new Intent(action).setPackage(app.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        } catch (Exception e) {
            try {
                app.unregisterReceiver(r);
            } catch (Exception ignored) {
            }
            try {
                pi.abandonSession(id);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }
}
