package com.mg4.store;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Riconosce l'auto, con lo stesso metodo dei launcher:
 * <ul>
 *   <li>e' un'infotainment MG4 se produttore AUTUS, modello SAIC e Android 9 (come la controlla il Worker);</li>
 *   <li>il software (SWI68, SWI69, SWI132, SWI133) si legge da ro.build.mt2712.version, altrimenti dalle app
 *   originali e dalla cartella in cui sono installate (le MY24 hanno cartelle "..._my24_...").</li>
 * </ul>
 */
public final class Firmware {
    public static final String SWI68 = "SWI68", SWI69 = "SWI69", SWI132 = "SWI132", SWI133 = "SWI133", OTHER = "";

    private static final String[] HMI_APPS = {
            "com.saicmotor.hmi.launcher", "com.saicmotor.hmi.systemsettings", "com.saicmotor.hmi.radio",
            "com.saicmotor.hmi.hvac", "com.saicmotor.hmi.btcall", "com.saicmotor.hmi.aroundview",
            "com.saicmotor.hmi.vehiclesettings"};
    private static final String HMI_HOME = "com.saicmotor.hmi.launcher";
    private static final String MG_HOME = "com.saicmotor.launcher";
    private static final String FEATURE_PROVIDER = "com.saicmotor.caradapter.featureprovider";

    private static String kind;

    private Firmware() {}

    /** Stessa verifica del Worker: AUTUS / SAIC / Android 9. */
    public static boolean isMg4() {
        return "AUTUS".equalsIgnoreCase(Build.MANUFACTURER) && "SAIC".equalsIgnoreCase(Build.MODEL)
                && Build.VERSION.SDK_INT == 28;
    }

    /** SWI68, SWI69, SWI132, SWI133 oppure "" se non riconosciuto. */
    public static synchronized String kind(Context c) {
        if (kind != null) return kind;
        String fromProp = fromProp(prop("ro.build.mt2712.version"));
        if (fromProp != null) return kind = fromProp;

        PackageManager pm = c.getPackageManager();
        int hmi = 0;
        for (String p : HMI_APPS) if (dir(pm, p) != null) hmi++;
        boolean provider = false;
        try {
            provider = pm.resolveContentProvider(FEATURE_PROVIDER, 0) != null;
        } catch (Throwable ignored) {
        }
        String mgDir = dir(pm, MG_HOME);
        if (hmi > 0) kind = my24(dir(pm, HMI_HOME)) ? SWI133 : SWI68;
        else if (mgDir != null || provider) kind = my24(mgDir) ? SWI132 : SWI69;
        else kind = OTHER;
        return kind;
    }

    private static String fromProp(String v) {
        if (v == null) return null;
        String u = v.trim().toUpperCase(Locale.ROOT);
        for (String fw : new String[]{SWI132, SWI133, SWI68, SWI69}) {
            if (u.equals(fw) || u.startsWith(fw + "-") || u.startsWith(fw + "_")) return fw;
        }
        return null;
    }

    private static boolean my24(String dir) {
        return dir != null && dir.toLowerCase(Locale.ROOT).contains("_my24");
    }

    private static String dir(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String p = ai.sourceDir;
            if (p == null) return "";
            int slash = p.lastIndexOf('/');
            return slash > 0 ? p.substring(0, slash) : p;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable e) {
            return "";
        }
    }
}
