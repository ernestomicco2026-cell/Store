package com.mg4.store;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Dopo che lo Store si e' aggiornato da solo (Android chiude l'app durante l'installazione), lo riapre,
 * cosi' l'utente non si ritrova sul launcher senza capire cosa e' successo. Solo se l'aggiornamento
 * l'ha avviato lo Store stesso (flag "relaunch").
 */
public final class SelfUpdated extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        SharedPreferences p = c.getSharedPreferences("store", Context.MODE_PRIVATE);
        if (!p.getBoolean("relaunch", false)) return;
        p.edit().remove("relaunch").apply();
        try {
            c.startActivity(new Intent(c, StoreActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }
}
