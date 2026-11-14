package com.mg4.store;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Store MG4: griglia delle app adatte all'auto, con Installa / Aggiorna / Installata.
 * L'auto si registra da sola al Worker (nessun codice da digitare).
 */
public final class StoreActivity extends Activity {
    private static final int S_LOADING = 0, S_READY = 1, S_NOT_MG4 = 2, S_BLOCKED = 3, S_OFFLINE = 4, S_ERROR = 5;

    /** Schermo intero come nel launcher: niente barre di sistema (riappaiono con uno scorrimento e si richiudono). */
    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private Theme t;
    private Api api;
    private String fw = "";
    private int state = S_LOADING;
    private List<Item> items = new ArrayList<>();
    private boolean installing;
    /** Motivo tecnico dell'ultimo errore, mostrato in piccolo sotto il messaggio. */
    private String lastError = "";

    private FrameLayout root;
    private FrameLayout body;
    private FrameLayout overlay;
    private TextView subtitle;
    private Item detail;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("store", MODE_PRIVATE);
        api = new Api(this);
        L.init(this, prefs.getString("lang", L.AUTO));
        UpdateJob.schedule(this);
        UpdateJob.clear(this);
        build();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        goFullscreen();
        if (state == S_READY && !installing) {
            for (Item it : items) it.refresh(getPackageManager());
            showBody();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    private void goFullscreen() {
        View d = getWindow().getDecorView();
        if (d.getSystemUiVisibility() != IMMERSIVE) d.setSystemUiVisibility(IMMERSIVE);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        L.init(this, prefs.getString("lang", L.AUTO));
        build();
    }

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) closeOverlay();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- dati

    private void load() {
        state = S_LOADING;
        lastError = "";
        showBody();
        net.execute(() -> {
            fw = Firmware.kind(this);
            int st;
            List<Item> list = new ArrayList<>();
            if (!Firmware.isMg4()) {
                st = S_NOT_MG4;
            } else {
                try {
                    api.register(fw);
                    JSONObject cat = api.catalog();
                    list = Item.parse(cat, fw, getPackageManager());
                    st = S_READY;
                } catch (Api.Fail f) {
                    lastError = (f.code > 0 ? "HTTP " + f.code + " " : "") + (f.getMessage() == null ? "" : f.getMessage());
                    if (f.offline()) st = S_OFFLINE;
                    else if (f.code == 403 && f.getMessage() != null && f.getMessage().contains("supportato")) st = S_NOT_MG4;
                    else if (f.code == 403) st = S_BLOCKED;
                    else st = S_ERROR;
                } catch (Throwable e) {
                    lastError = e.toString();
                    st = S_ERROR;
                }
            }
            final int fst = st;
            final List<Item> fl = list;
            main.post(() -> {
                state = fst;
                items = fl;
                showBody();
            });
            if (!fl.isEmpty() && Icons.load(this, api, fl)) main.post(this::refreshViews);
        });
    }

    private void act(final Item it) {
        if (it.state == Item.INSTALLED) {
            Intent i = it.pkg.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(it.pkg);
            if (i != null) startActivity(i);
            return;
        }
        if (installing) return;
        installing = true;
        installOne(it, () -> installing = false);
    }

    /** Aggiorna, una dopo l'altra, tutte le app con una versione nuova; lo Store per ultimo (si riavvia). */
    private void updateAll() {
        if (installing) return;
        final List<Item> queue = new ArrayList<>();
        Item self = null;
        for (Item it : items) {
            if (it.state != Item.UPDATE) continue;
            if (it.isSelf()) self = it;
            else queue.add(it);
        }
        if (self != null) queue.add(self);
        if (queue.isEmpty()) return;
        installing = true;
        for (Item it : queue) it.busy = "…";
        refreshViews();
        next(queue);
    }

    private void next(final List<Item> queue) {
        if (queue.isEmpty()) {
            installing = false;
            refreshViews();
            return;
        }
        Item it = queue.remove(0);
        installOne(it, () -> next(queue));
    }

    /** Scarica e installa un'app; alla fine chiama 'then' sul thread principale. */
    private void installOne(final Item it, final Runnable then) {
        it.busy = "0%";
        refreshViews();
        net.execute(() -> {
            try {
                byte[] apk = api.download(it.file, (done, total) -> {
                    if (total > 0) {
                        String pct = (int) Math.min(99, done * 100 / total) + "%";
                        main.post(() -> {
                            it.busy = pct;
                            refreshViews();
                        });
                    }
                });
                main.post(() -> {
                    it.busy = L.s(R.string.installing);
                    refreshViews();
                });
                Installer.install(this, it.pkg, apk, (ok, confirm, msg) -> main.post(() -> {
                    if (confirm) {
                        it.busy = L.s(R.string.installing);
                        toast(L.s(R.string.confirm_needed));
                        refreshViews();
                        return;
                    }
                    finishInstall(it, ok);
                    then.run();
                }));
            } catch (Exception e) {
                main.post(() -> {
                    finishInstall(it, false);
                    then.run();
                });
            }
        });
    }

    private void finishInstall(Item it, boolean ok) {
        it.busy = null;
        it.refresh(getPackageManager());
        if (!ok) toast(L.s(R.string.failed));
        refreshViews();
    }

    // ---------------------------------------------------------------- interfaccia

    private void build() {
        t = Theme.resolve(this, prefs);
        Window w = getWindow();
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        w.getDecorView().setOnSystemUiVisibilityChangeListener(vis -> {
            // se una barra riappare (scorrimento dal bordo), dopo un attimo si richiude
            if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) main.postDelayed(this::goFullscreen, 2500);
        });
        goFullscreen();

        root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(20), dp(22), 0);

        // intestazione: titolo e sottotitolo al centro, pulsanti a destra
        FrameLayout head = new FrameLayout(this);
        head.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(L.s(R.string.app_name), 26, t.text, true);
        title.setGravity(Gravity.CENTER);
        titles.addView(title);
        subtitle = text("", 14, t.sub, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, 0);
        titles.addView(subtitle);
        head.addView(titles, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(iconButton(R.drawable.ic_refresh, v -> { if (!installing) load(); }));
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        buttons.addView(iconButton(R.drawable.ic_gear, v -> showSettings()));
        head.addView(buttons, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        head.setMinimumHeight(dp(52));
        col.addView(head);

        body = new FrameLayout(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1);
        bp.topMargin = dp(16);
        col.addView(body, bp);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(t.shade);
        overlay.setVisibility(View.GONE);
        overlay.setOnClickListener(v -> closeOverlay());
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        showBody();
        if (detail != null) showDetail(detail);
    }

    private void showBody() {
        if (body == null) return;
        body.removeAllViews();
        // lo Store compare nella griglia solo quando c'e' una sua versione nuova, ed e' il primo
        List<Item> shown = new ArrayList<>();
        int updates = 0;
        for (Item it : items) {
            if (it.isSelf() && it.state == Item.INSTALLED && it.busy == null) continue;
            if (it.isSelf()) shown.add(0, it);
            else shown.add(it);
            if (it.state == Item.UPDATE) updates++;
        }
        int apps = 0;
        for (Item it : items) if (!it.isSelf()) apps++;
        String fwName = fw.isEmpty() ? L.s(R.string.unknown_fw) : fw;
        String sub = state == S_READY ? fwName + " · " + L.f(R.string.apps_count, apps)
                + (updates > 0 ? " · " + L.f(R.string.updates_count, updates) : "") : "";
        subtitle.setText(sub);
        subtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);

        switch (state) {
            case S_LOADING:
                body.addView(message(R.drawable.ic_download, L.s(R.string.loading), null, false), center());
                return;
            case S_NOT_MG4:
                body.addView(message(R.drawable.ic_alert, L.s(R.string.not_mg4), L.s(R.string.not_mg4_sub), false), center());
                return;
            case S_BLOCKED:
                body.addView(message(R.drawable.ic_alert, L.s(R.string.blocked), null, true), center());
                return;
            case S_OFFLINE:
                body.addView(message(R.drawable.ic_wifi_off, L.s(R.string.offline), L.s(R.string.offline_sub), true), center());
                return;
            case S_ERROR:
                body.addView(message(R.drawable.ic_alert, L.s(R.string.error), L.s(R.string.error_sub), true), center());
                return;
            default:
                break;
        }
        if (items.isEmpty()) {
            lastError = "software " + (fw.isEmpty() ? "?" : fw);
            body.addView(message(R.drawable.ic_info, L.s(R.string.empty), null, true), center());
            return;
        }

        int widthDp = getResources().getConfiguration().screenWidthDp - 56;
        int cols = Math.max(2, Math.min(5, widthDp / 260));   // riquadri grandi, leggibili alla guida
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        g.setUseDefaultMargins(false);
        for (Item it : shown) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            g.addView(card(it), lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, dp(20));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        if (updates > 0) content.addView(updateBar(updates));
        content.addView(g, new LinearLayout.LayoutParams(-1, -2));
        sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
        body.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    private View updateBar(int n) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(12), dp(12), dp(12));
        bar.setBackground(rounded(t.panel, 18));
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_refresh);
        iv.setColorFilter(t.text, PorterDuff.Mode.SRC_IN);
        bar.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView tx = text(L.f(R.string.updates_available, n), 16, t.text, true);
        tx.setPadding(dp(14), 0, dp(14), 0);
        bar.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        TextView b = text(L.s(R.string.update_all), 15, installing ? t.sub : t.onText, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setBackground(pressable(installing ? t.chip : t.text, 12));
        b.setOnClickListener(v -> updateAll());
        bar.addView(b, new LinearLayout.LayoutParams(-2, dp(44)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        bar.setLayoutParams(lp);
        return bar;
    }

    private void refreshViews() {
        showBody();
        if (detail != null && overlay.getVisibility() == View.VISIBLE) showDetail(detail);
    }

    private View card(final Item it) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(22), dp(22), dp(22), dp(22));
        c.setBackground(pressable(t.panel, 20));
        c.setOnClickListener(v -> showDetail(it));

        c.addView(iconBox(it, 80), new LinearLayout.LayoutParams(dp(80), dp(80)));
        TextView name = text(it.name, 20, t.text, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(16);
        c.addView(name, np);
        TextView ver = text(it.version, 15, t.sub, false);
        ver.setPadding(0, dp(4), 0, 0);
        ver.setSingleLine(true);
        ver.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(ver);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(-1, dp(52));
        btp.topMargin = dp(18);
        c.addView(actionRow(it, 52), btp);
        return c;
    }

    /** Pulsante principale (Installa / Aggiorna / Apri) e, se l'app c'e' sull'auto, il cestino per disinstallarla. */
    private View actionRow(final Item it, int hDp) {
        LinearLayout row = new LinearLayout(this);
        row.addView(actionButton(it), new LinearLayout.LayoutParams(0, -1, 1));
        boolean onCar = it.state == Item.INSTALLED || it.state == Item.UPDATE;
        if (onCar && it.busy == null && !it.isSelf() && !it.pkg.isEmpty()) {
            FrameLayout del = new FrameLayout(this);
            del.setBackground(pressable(t.chip, 12));
            del.setOnClickListener(v -> askUninstall(it));
            del.setContentDescription(L.s(R.string.uninstall));
            ImageView iv = new ImageView(this);
            iv.setImageResource(R.drawable.ic_trash);
            iv.setColorFilter(t.sub, PorterDuff.Mode.SRC_IN);
            del.addView(iv, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(hDp), -1);
            lp.leftMargin = dp(10);
            row.addView(del, lp);
        }
        return row;
    }

    /** Conferma prima di togliere un'app: un tocco sbagliato durante la guida non deve cancellarla. */
    private void askUninstall(final Item it) {
        if (installing) return;
        detail = null;
        overlay.removeAllViews();
        LinearLayout p = sheet();
        p.addView(text(L.f(R.string.uninstall_confirm, it.name), 21, t.text, true));
        TextView sub = text(L.s(R.string.uninstall_sub), 15, t.sub, false);
        sub.setLineSpacing(0, 1.25f);
        sub.setPadding(0, dp(10), 0, 0);
        p.addView(sub);
        LinearLayout row = new LinearLayout(this);
        TextView no = text(L.s(R.string.cancel), 15, t.text, true);
        no.setGravity(Gravity.CENTER);
        no.setBackground(pressable(t.chip, 12));
        no.setOnClickListener(v -> closeOverlay());
        row.addView(no, new LinearLayout.LayoutParams(0, dp(52), 1));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        TextView yes = text(L.s(R.string.uninstall), 15, t.onText, true);
        yes.setGravity(Gravity.CENTER);
        yes.setBackground(pressable(t.text, 12));
        yes.setOnClickListener(v -> {
            closeOverlay();
            uninstall(it);
        });
        row.addView(yes, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(24);
        p.addView(row, rp);
        openOverlay(p, 0.45f, -1);
    }

    private void uninstall(final Item it) {
        if (installing) return;
        installing = true;
        it.busy = L.s(R.string.uninstalling);
        refreshViews();
        Installer.uninstall(this, it.pkg, (ok, confirm, msg) -> main.post(() -> {
            if (confirm) {
                toast(L.s(R.string.confirm_needed));
                return;
            }
            installing = false;
            it.busy = null;
            it.refresh(getPackageManager());
            if (!ok) toast(L.s(R.string.uninstall_failed));
            refreshViews();
        }));
    }

    private TextView actionButton(final Item it) {
        String label;
        boolean filled;
        if (it.busy != null) {
            label = it.busy;
            filled = false;
        } else if (it.state == Item.INSTALLED) {
            boolean canOpen = !it.pkg.isEmpty() && getPackageManager().getLaunchIntentForPackage(it.pkg) != null;
            label = canOpen ? L.s(R.string.open) : L.s(R.string.installed);
            filled = false;
        } else {
            label = it.state == Item.UPDATE ? L.s(R.string.update) : L.s(R.string.install);
            filled = true;
        }
        TextView b = text(label, 15, filled ? t.onText : (it.busy != null ? t.text : t.sub), true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pressable(filled ? t.text : t.chip, 12));
        b.setOnClickListener(v -> act(it));
        return b;
    }

    private void showDetail(final Item it) {
        detail = it;
        overlay.removeAllViews();
        LinearLayout p = sheet();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(iconBox(it, 64), new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(16), 0, 0, 0);
        tt.addView(text(it.name, 21, t.text, true));
        String line = L.f(R.string.version, it.version);
        if (it.size > 0) line += " · " + L.f(R.string.size_mb, String.format(Locale.ROOT, "%.1f", it.size / 1048576.0));
        tt.addView(text(line, 14, t.sub, false));
        if (it.installedVersion != null && it.state != Item.INSTALLED) {
            tt.addView(text(L.f(R.string.installed_ver, it.installedVersion), 14, t.sub, false));
        }
        top.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(iconButton(R.drawable.ic_close, v -> closeOverlay()));
        p.addView(top);

        TextView h = text(L.s(R.string.whats_new), 15, t.text, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(20);
        p.addView(h, hp);
        ScrollView sv = new ScrollView(this);
        TextView notes = text(it.notes.isEmpty() ? L.s(R.string.no_notes) : it.notes, 15, t.sub, false);
        notes.setLineSpacing(0, 1.25f);
        sv.addView(notes);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1);
        sp.topMargin = dp(6);
        p.addView(sv, sp);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(52));
        bp.topMargin = dp(16);
        p.addView(actionRow(it, 52), bp);
        openOverlay(p, 0.55f, 0.82f);
    }

    private void showSettings() {
        detail = null;
        overlay.removeAllViews();
        LinearLayout p = sheet();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(L.s(R.string.settings), 21, t.text, true), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(iconButton(R.drawable.ic_close, v -> closeOverlay()));
        p.addView(top);

        p.addView(section(L.s(R.string.language)));
        String lang = prefs.getString("lang", L.AUTO);
        List<String[]> langs = new ArrayList<>();
        langs.add(new String[]{L.AUTO, L.s(R.string.lang_auto)});
        for (int i = 0; i < L.CODES.length; i++) langs.add(new String[]{L.CODES[i], L.NAMES[i]});
        p.addView(chips(langs, lang, v -> {
            prefs.edit().putString("lang", v).apply();
            L.init(this, v);
            build();
            showSettings();
        }));

        p.addView(section(L.s(R.string.theme)));
        List<String[]> themes = new ArrayList<>();
        themes.add(new String[]{Theme.AUTO, L.s(R.string.theme_auto)});
        themes.add(new String[]{Theme.DAY, L.s(R.string.theme_day)});
        themes.add(new String[]{Theme.NIGHT, L.s(R.string.theme_night)});
        p.addView(chips(themes, prefs.getString("theme", Theme.AUTO), v -> {
            prefs.edit().putString("theme", v).apply();
            build();
            showSettings();
        }));

        TextView ver = text("Store " + BuildInfo.VERSION, 13, t.sub, false);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
        vp.topMargin = dp(24);
        p.addView(ver, vp);
        openOverlay(p, 0.6f, -1);
    }

    private interface Pick {
        void on(String value);
    }

    private View chips(List<String[]> opts, String sel, final Pick pick) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(5);
        for (final String[] o : opts) {
            boolean on = o[0].equals(sel);
            TextView c = text(o[1], 15, on ? t.onText : t.text, on);
            c.setGravity(Gravity.CENTER);
            c.setSingleLine(true);
            c.setPadding(dp(10), 0, dp(10), 0);
            c.setBackground(pressable(on ? t.text : t.chip, 12));
            c.setOnClickListener(v -> pick.on(o[0]));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(46);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            g.addView(c, lp);
        }
        return g;
    }

    private TextView section(String s) {
        TextView v = text(s, 14, t.sub, true);
        v.setPadding(dp(4), dp(20), 0, dp(6));
        return v;
    }

    private LinearLayout sheet() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(24), dp(22), dp(24), dp(22));
        p.setBackground(rounded(t.panel, 22));
        p.setClickable(true);
        return p;
    }

    private void openOverlay(View sheet, float widthFrac, float heightFrac) {
        int w = (int) (getResources().getDisplayMetrics().widthPixels * widthFrac);
        int h = heightFrac > 0 ? (int) (getResources().getDisplayMetrics().heightPixels * heightFrac) : -2;
        overlay.addView(sheet, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        overlay.setVisibility(View.VISIBLE);
    }

    private void closeOverlay() {
        detail = null;
        overlay.removeAllViews();
        overlay.setVisibility(View.GONE);
    }

    private View message(int icon, String title, String sub, boolean retry) {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(t.sub, PorterDuff.Mode.SRC_IN);
        m.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView a = text(title, 19, t.text, true);
        a.setGravity(Gravity.CENTER);
        a.setPadding(0, dp(14), 0, 0);
        m.addView(a);
        if (sub != null) {
            TextView b = text(sub, 15, t.sub, false);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(4), 0, 0);
            m.addView(b);
        }
        if (retry && !lastError.isEmpty()) {
            TextView d = text(lastError, 12, t.sub, false);
            d.setGravity(Gravity.CENTER);
            d.setPadding(dp(40), dp(10), dp(40), 0);
            d.setMaxLines(3);
            m.addView(d);
        }
        if (retry) {
            TextView r = text(L.s(R.string.retry), 15, t.onText, true);
            r.setGravity(Gravity.CENTER);
            r.setPadding(dp(28), 0, dp(28), 0);
            r.setBackground(pressable(t.text, 12));
            r.setOnClickListener(v -> load());
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(46));
            rp.topMargin = dp(20);
            m.addView(r, rp);
        }
        return m;
    }

    private FrameLayout.LayoutParams center() {
        return new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
    }

    /** Icona vera dell'app se scaricata, altrimenti il simbolo predefinito. */
    private View iconBox(Item it, int sizeDp) {
        if (it.bmp == null) return iconBox(it.iconRes, sizeDp);
        // le icone hanno gia' il loro sfondo arrotondato (come sul launcher): si mostrano cosi' come sono
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(it.bmp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    private View iconBox(int res, int sizeDp) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(rounded(t.iconBg, sizeDp / 4f));
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(t.text, PorterDuff.Mode.SRC_IN);
        int s = dp(sizeDp * 0.5f);
        f.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        return f;
    }

    private View iconButton(int res, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(pressable(t.panel, 14));
        f.setOnClickListener(l);
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(t.text, PorterDuff.Mode.SRC_IN);
        f.addView(iv, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        return f;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    private GradientDrawable rounded(int color, float rDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(rDp));
        return d;
    }

    private RippleDrawable pressable(int color, float rDp) {
        int ripple = t.day ? 0x22000000 : 0x33FFFFFF;
        return new RippleDrawable(ColorStateList.valueOf(ripple), rounded(color, rDp), rounded(0xFFFFFFFF, rDp));
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
