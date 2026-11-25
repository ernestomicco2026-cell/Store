package com.mg4.store;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Un'app del catalogo e il suo stato sull'auto. */
public final class Item {
    public static final int INSTALL = 0, UPDATE = 1, INSTALLED = 2;

    public final String id, name, version, file, notes, pkg;
    /** Icona nel catalogo (icone/<id>.png) e sua impronta, per la cache; vuote se non c'e'. */
    public final String icon, iconSha;
    /** Icona scaricata, oppure null (si usa il simbolo predefinito). */
    public android.graphics.Bitmap bmp;
    public final long size, code;
    /** App in prova: nella griglia e nel dettaglio compare l'etichetta BETA accanto al nome. */
    public final boolean beta;
    public final int iconRes;

    public String installedVersion;
    public int state;
    /** Durante l'installazione: testo del pulsante (percentuale, "Installazione..."), altrimenti null. */
    public String busy;

    private Item(JSONObject o) {
        id = o.optString("id");
        name = o.optString("nome", id);
        version = o.optString("versione");
        file = o.optString("file");
        notes = o.optString("note");
        pkg = o.optString("pacchetto");
        size = o.optLong("dimensione");
        code = o.optLong("codice_versione", -1);
        iconRes = icon(id);
        icon = o.optString("icona");
        iconSha = o.optString("icona_sha");
        beta = o.optBoolean("beta", false);
    }

    /** Elenco delle app adatte a questa auto (software e requisiti), con lo stato di installazione. */
    public static List<Item> parse(JSONObject cat, String fw, PackageManager pm) {
        List<Item> out = new ArrayList<>();
        JSONArray a = cat.optJSONArray("apps");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !fits(o, fw, pm)) continue;
            Item it = new Item(o);
            it.refresh(pm);
            out.add(it);
        }
        return out;
    }

    private static boolean fits(JSONObject o, String fw, PackageManager pm) {
        JSONArray per = o.optJSONArray("per");
        if (per != null && per.length() > 0 && !fw.isEmpty()) {
            boolean ok = false;
            for (int i = 0; i < per.length(); i++) if (fw.equalsIgnoreCase(per.optString(i))) ok = true;
            if (!ok) return false;
        }
        JSONObject req = o.optJSONObject("richiede");
        if (req != null) {
            PackageInfo pi = info(pm, req.optString("pacchetto"));
            if (pi == null) return false;
            String v = pi.versionName == null ? "" : pi.versionName;
            String starts = req.optString("versione_inizia");
            String has = req.optString("versione_contiene");
            if (!starts.isEmpty() && !v.startsWith(starts)) return false;
            if (!has.isEmpty() && !v.contains(has)) return false;
        }
        return true;
    }

    /** Ricalcola lo stato confrontando con l'app installata. */
    public void refresh(PackageManager pm) {
        PackageInfo pi = pkg.isEmpty() ? null : info(pm, pkg);
        installedVersion = pi == null ? null : pi.versionName;
        if (pi == null) state = INSTALL;
        else if (version.equals(pi.versionName) || (code >= 0 && (long) pi.versionCode > code)) state = INSTALLED;
        else if (code >= 0 && (long) pi.versionCode < code) state = UPDATE;
        else state = INSTALL;   // stesso numero ma versione diversa (es. Radio Fix sul servizio originale)
    }

    /** E' lo Store stesso? Si aggiorna per ultimo e compare solo quando c'e' una versione nuova. */
    public boolean isSelf() {
        return "store".equals(id);
    }

    static PackageInfo info(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int icon(String id) {
        if (id.startsWith("launcher")) return R.drawable.ic_grid;
        if (id.startsWith("clima")) return R.drawable.ic_fan;
        if (id.startsWith("voice")) return R.drawable.ic_mic;
        if (id.endsWith("remover")) return R.drawable.ic_undo;
        if (id.startsWith("radio")) return R.drawable.ic_radio;
        if (id.startsWith("taskmanager")) return R.drawable.ic_list;
        if (id.equals("store")) return R.drawable.ic_launcher;
        return R.drawable.ic_app;
    }
}
