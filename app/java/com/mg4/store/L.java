package com.mg4.store;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/** Lingua dello store: "auto" segue la lingua dell'auto (inglese se non tradotta), come nel launcher. */
public final class L {
    public static final String AUTO = "auto";
    public static final String[] CODES = {"it", "en", "es", "de", "pt", "fr", "tr", "pl"};
    public static final String[] NAMES = {"Italiano", "English", "Español", "Deutsch", "Português", "Français", "Türkçe", "Polski"};

    private static Resources res;

    private L() {}

    public static String resolve(String pref) {
        if (pref != null && !AUTO.equals(pref)) return pref;
        Configuration sys = Resources.getSystem().getConfiguration();
        String lang = (sys.getLocales().isEmpty() ? Locale.getDefault() : sys.getLocales().get(0)).getLanguage();
        for (String c : CODES) if (c.equals(lang)) return c;
        return "en";
    }

    public static void init(Context c, String pref) {
        String code = resolve(pref);
        Configuration cfg = new Configuration(c.getApplicationContext().getResources().getConfiguration());
        cfg.setLocale(new Locale(code));
        res = c.getApplicationContext().createConfigurationContext(cfg).getResources();
    }

    public static String s(int id) {
        return res.getString(id);
    }

    public static String f(int id, Object... a) {
        return res.getString(id, a);
    }
}
