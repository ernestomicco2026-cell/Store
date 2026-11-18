package com.mg4.store;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Dialogo con il Worker "store": registrazione dell'auto, catalogo e download degli APK. */
public final class Api {
    public static final String BASE = "https://store.ernesto-micco2026.workers.dev";

    /** Errore con il codice HTTP (0 = rete assente) e il messaggio del Worker. */
    public static final class Fail extends Exception {
        public final int code;

        Fail(int code, String msg) {
            super(msg);
            this.code = code;
        }

        public boolean offline() {
            return code == 0;
        }
    }

    public interface Progress {
        void on(long done, long total);
    }

    private final String id;

    public Api(Context c) {
        id = deviceId(c);
    }

    /**
     * Identificativo dell'auto: impronta di ANDROID_ID (resta uguale anche se l'app viene reinstallata).
     * Se manca, uno casuale salvato nelle preferenze.
     */
    @SuppressLint("HardwareIds")
    static String deviceId(Context c) {
        String raw = null;
        try {
            raw = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (raw == null || raw.isEmpty() || "9774d56d682e549c".equals(raw)) {
            android.content.SharedPreferences p = c.getSharedPreferences("store", Context.MODE_PRIVATE);
            raw = p.getString("rnd_id", null);
            if (raw == null) {
                raw = java.util.UUID.randomUUID().toString();
                p.edit().putString("rnd_id", raw).apply();
            }
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("mg4store:" + raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return raw.replaceAll("[^A-Za-z0-9]", "");
        }
    }

    /** L'auto si presenta al Worker, che la ricorda. */
    public void register(String software) throws Fail {
        try {
            JSONObject b = new JSONObject();
            b.put("id", id);
            b.put("manufacturer", Build.MANUFACTURER);
            b.put("model", Build.MODEL);
            b.put("sdk", Build.VERSION.SDK_INT);
            b.put("software", software);
            request("POST", "/registra", b.toString().getBytes(StandardCharsets.UTF_8), null);
        } catch (Fail f) {
            throw f;
        } catch (Exception e) {
            throw new Fail(-1, "DATI-1");
        }
    }

    public JSONObject catalog() throws Fail {
        try {
            return new JSONObject(new String(request("GET", "/catalogo", null, null), StandardCharsets.UTF_8));
        } catch (Fail f) {
            throw f;
        } catch (Exception e) {
            throw new Fail(-1, "DATI-1");
        }
    }

    public byte[] download(String file, Progress p) throws Fail {
        try {
            return request("GET", "/scarica?file=" + URLEncoder.encode(file, "UTF-8"), null, p);
        } catch (Fail f) {
            throw f;
        } catch (Exception e) {
            throw new Fail(-1, "DATI-1");
        }
    }

    private byte[] request(String method, String path, byte[] body, Progress p) throws Fail {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("X-Store-Key", AppKey.get());
            c.setRequestProperty("X-Auto-Id", id);
            c.setRequestProperty("User-Agent", "MG4Store/" + BuildInfo.VERSION);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                String msg = "";
                try (InputStream es = c.getErrorStream()) {
                    if (es != null) msg = new JSONObject(new String(readAll(es, -1, null), StandardCharsets.UTF_8)).optString("errore");
                } catch (Exception ignored) {
                }
                throw new Fail(code, msg);
            }
            try (InputStream in = c.getInputStream()) {
                return readAll(in, c.getContentLength(), p);
            }
        } catch (Fail f) {
            throw f;
        } catch (IOException e) {
            throw new Fail(0, netCode(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Codice breve per gli errori di collegamento (al posto del testo grezzo di Android, che e' lungo e
     * mostra l'indirizzo del server). RETE-0 = nessuna rete sull'auto, gli altri = rete presente ma il server
     * non si raggiunge.
     */
    static String netCode(IOException e) {
        if (e instanceof java.net.UnknownHostException) return "RETE-1";
        if (e instanceof java.net.SocketTimeoutException) return "RETE-2";
        if (e instanceof javax.net.ssl.SSLException) return "RETE-3";
        if (e instanceof java.net.ConnectException) return "RETE-4";
        return "RETE-5";
    }

    /** true se l'auto ha una rete attiva (non garantisce che il server risponda). */
    @SuppressWarnings("deprecation")
    static boolean online(Context c) {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;   // nel dubbio si prova
        }
    }

    /** Codice breve di un errore: "HTTP 502", "RETE-1", "DATI-1". */
    static String code(Fail f) {
        return f.code > 0 ? "HTTP " + f.code : (f.getMessage() == null || f.getMessage().isEmpty() ? "RETE-5" : f.getMessage());
    }

    private static byte[] readAll(InputStream in, long total, Progress p) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(total > 0 ? (int) total : 8192);
        byte[] b = new byte[16384];
        int n;
        long done = 0, last = 0;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            done += n;
            if (p != null && done - last > 65536) {
                last = done;
                p.on(done, total);
            }
        }
        if (p != null) p.on(done, total);
        return bo.toByteArray();
    }
}
