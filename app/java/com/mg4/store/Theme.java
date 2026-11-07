package com.mg4.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** Colori dello store: gli stessi del launcher (giorno e notte), senza colore d'accento. */
public final class Theme {
    public static final String AUTO = "auto", DAY = "day", NIGHT = "night";

    public final boolean day;
    public final int bg, panel, text, sub, chip, iconBg, ok, onText, shade;

    private Theme(boolean day) {
        this.day = day;
        if (day) {
            bg = 0xFFE6E8EB; panel = 0xFFFFFFFF; text = 0xFF111318; sub = 0xFF555C66; chip = 0xFFF1F3F5;
            iconBg = 0xFFE3E8F0; ok = 0xFF1E9E5A; shade = 0x66000000;
        } else {
            bg = 0xFF0A0B0D; panel = 0xFF15171B; text = 0xFFF2F3F5; sub = 0xFF9BA1A9; chip = 0xFF1E2126;
            iconBg = 0xFF1F2A3A; ok = 0xFF34C77B; shade = 0xB3000000;
        }
        onText = day ? 0xFFFFFFFF : 0xFF111318;
    }

    public static Theme resolve(Context c, SharedPreferences p) {
        String mode = p.getString("theme", AUTO);
        if (DAY.equals(mode)) return new Theme(true);
        if (NIGHT.equals(mode)) return new Theme(false);
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return new Theme(night == Configuration.UI_MODE_NIGHT_NO);
    }
}
