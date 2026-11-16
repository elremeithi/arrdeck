package com.arrdeck;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** One configured *arr server. */
final class Svc {
    static final String RADARR = "radarr", SONARR = "sonarr", PROWLARR = "prowlarr";
    final String id;
    /** Which server profile this connection belongs to (0 = the original / first server). */
    int server;
    String url = "", key = "", user = "", pass = "";
    boolean trustAll;

    Svc(String id) { this.id = id; }

    Svc(String id, int server) { this.id = id; this.server = server; }

    /** Pref key for this service on this server; server 0 keeps the pre-multi-server key names. */
    String p(String suffix) { return (server == 0 ? id : "s" + server + "_" + id) + suffix; }

    // ---------------- server profiles

    static int active(Context c) {
        int a = prefs(c).getInt("active_server", 0);
        return servers(c).contains(a) ? a : servers(c).get(0);
    }

    static void setActive(Context c, int n) { prefs(c).edit().putInt("active_server", n).apply(); }

    static java.util.List<Integer> servers(Context c) {
        java.util.List<Integer> l = new java.util.ArrayList<>();
        for (String s : prefs(c).getString("servers", "0").split(","))
            try { l.add(Integer.parseInt(s.trim())); } catch (Exception ignored) {}
        if (l.isEmpty()) l.add(0);
        return l;
    }

    static void setServers(Context c, java.util.List<Integer> l) {
        StringBuilder b = new StringBuilder();
        for (int n : l) b.append(b.length() > 0 ? "," : "").append(n);
        prefs(c).edit().putString("servers", b.toString()).apply();
    }

    static String serverName(Context c, int n) {
        String d = "Server " + (servers(c).indexOf(n) + 1);
        String s = prefs(c).getString("server_name_" + n, "");
        return s.trim().isEmpty() ? d : s;
    }

    static void setServerName(Context c, int n, String name) { prefs(c).edit().putString("server_name_" + n, name.trim()).apply(); }

    static int addServer(Context c, String name) {
        java.util.List<Integer> l = servers(c);
        int n = 0;
        for (int x : l) n = Math.max(n, x + 1);
        l.add(n);
        setServers(c, l);
        setServerName(c, n, name);
        return n;
    }

    static void deleteServer(Context c, int n) {
        java.util.List<Integer> l = servers(c);
        if (l.size() <= 1) return;
        l.remove(Integer.valueOf(n));
        setServers(c, l);
        SharedPreferences.Editor e = prefs(c).edit().remove("server_name_" + n);
        String pre = n == 0 ? null : "s" + n + "_";
        for (String k : prefs(c).getAll().keySet()) {
            boolean mine = pre != null ? k.startsWith(pre)
                    : k.startsWith(RADARR + "_") || k.startsWith(SONARR + "_") || k.startsWith(PROWLARR + "_");
            if (mine) e.remove(k);
        }
        e.apply();
        if (prefs(c).getInt("active_server", 0) == n) setActive(c, l.get(0));
    }

    /** True if any service on any server is configured. */
    static boolean anyConfigured(Context c) {
        for (int n : servers(c))
            for (String id : new String[]{RADARR, SONARR, PROWLARR}) if (load(c, id, n).ok()) return true;
        return false;
    }

    String name() { return id.equals(RADARR) ? "Radarr" : id.equals(SONARR) ? "Sonarr" : "Prowlarr"; }

    int color() { return id.equals(RADARR) ? Ui.RADARR : id.equals(SONARR) ? Ui.SONARR : Ui.PROWLARR; }

    boolean ok() { return url.length() > 0 && key.length() > 0; }

    String api() { return id.equals(PROWLARR) ? "/api/v1" : "/api/v3"; }

    static String normalize(String u) {
        u = u == null ? "" : u.trim();
        if (u.isEmpty()) return u;
        if (!u.matches("(?i)^https?://.*")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    String origin() {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getAuthority();
        } catch (Exception e) { return url; }
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("arr", Context.MODE_PRIVATE); }

    /** Loads the service for the active server. */
    static Svc load(Context c, String id) { return load(c, id, active(c)); }

    static Svc load(Context c, String id, int server) {
        SharedPreferences p = prefs(c);
        Svc s = new Svc(id, server);
        s.url = p.getString(s.p("_url"), "");
        s.key = p.getString(s.p("_key"), "");
        s.user = p.getString(s.p("_user"), "");
        s.pass = p.getString(s.p("_pass"), "");
        s.trustAll = p.getBoolean(s.p("_trust"), false);
        return s;
    }

    void save(Context c) {
        url = normalize(url);
        prefs(c).edit().putString(p("_url"), url).putString(p("_key"), key.trim())
                .putString(p("_user"), user).putString(p("_pass"), pass)
                .putBoolean(p("_trust"), trustAll).apply();
    }
}

/** Networking, background execution and a small image loader. */
final class Net {
    static final ExecutorService pool = Executors.newFixedThreadPool(4);
    static final ExecutorService imgPool = Executors.newFixedThreadPool(4);
    static final Handler main = new Handler(Looper.getMainLooper());

    interface Ok<T> { void ok(T v); }

    interface Job<T> { T run() throws Exception; }

    static <T> void async(final Main m, final Job<T> job, final Ok<T> ok, final Ok<Exception> err) {
        pool.execute(new Runnable() {
            public void run() {
                try {
                    final T v = job.run();
                    main.post(new Runnable() {
                        public void run() { if (!m.isDestroyed() && ok != null) ok.ok(v); }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        public void run() {
                            if (m.isDestroyed()) return;
                            if (err != null) err.ok(e); else Ui.toast(m, msg(e));
                        }
                    });
                }
            }
        });
    }

    static String msg(Exception e) {
        String s = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException) return "Timed out";
        if (e instanceof java.net.UnknownHostException) return "Unknown host: " + s;
        if (e instanceof java.net.ConnectException) return "Can't connect: " + s;
        return s == null ? e.getClass().getSimpleName() : s;
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static SSLSocketFactory trustAllFactory;
    private static final HostnameVerifier ANY_HOST = new HostnameVerifier() {
        public boolean verify(String h, SSLSession s) { return true; }
    };

    private static synchronized SSLSocketFactory insecure() throws Exception {
        if (trustAllFactory == null) {
            TrustManager[] tm = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            trustAllFactory = ctx.getSocketFactory();
        }
        return trustAllFactory;
    }

    static HttpURLConnection open(Svc s, String fullUrl, String method, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(fullUrl).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(timeout);
        c.setUseCaches(false);
        if (s != null && fullUrl.startsWith(s.origin())) {
            c.setRequestProperty("X-Api-Key", s.key);
            if (s.user.length() > 0) c.setRequestProperty("Authorization", "Basic "
                    + Base64.encodeToString((s.user + ":" + s.pass).getBytes("UTF-8"), Base64.NO_WRAP));
            if (s.trustAll && c instanceof HttpsURLConnection) {
                try {
                    ((HttpsURLConnection) c).setSSLSocketFactory(insecure());
                    ((HttpsURLConnection) c).setHostnameVerifier(ANY_HOST);
                } catch (Exception e) { throw new IOException(e); }
            }
        }
        return c;
    }

    static String req(Svc s, String method, String path, String body, int timeout) throws IOException {
        if (!s.ok()) throw new IOException(s.name() + " is not configured");
        HttpURLConnection c = open(s, s.url + path, method, timeout);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream o = c.getOutputStream();
                o.write(body.getBytes("UTF-8"));
                o.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String txt = in == null ? "" : new String(read(in), "UTF-8");
            if (code == 401) throw new IOException("Unauthorized (check API key / auth)");
            if (code >= 400) throw new IOException("HTTP " + code + errorText(txt));
            return txt;
        } finally { c.disconnect(); }
    }

    private static String errorText(String txt) {
        try {
            Object o = new JSONTokener(txt).nextValue();
            if (o instanceof JSONArray && ((JSONArray) o).length() > 0) o = ((JSONArray) o).get(0);
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                String m = j.optString("errorMessage", j.optString("message", ""));
                if (m.length() > 0) return ": " + m;
            }
        } catch (Exception ignored) {}
        return txt.length() > 0 && txt.length() < 160 ? ": " + txt : "";
    }

    static Object json(Svc s, String method, String path, Object body, int timeout) throws Exception {
        String t = req(s, method, s.api() + path, body == null ? null : body.toString(), timeout).trim();
        if (t.isEmpty()) return null;
        return new JSONTokener(t).nextValue();
    }

    static JSONArray arr(Svc s, String path) throws Exception {
        Object o = json(s, "GET", path, null, 30000);
        if (o instanceof JSONObject && ((JSONObject) o).has("records"))
            return ((JSONObject) o).getJSONArray("records");
        return o instanceof JSONArray ? (JSONArray) o : new JSONArray();
    }

    static JSONObject obj(Svc s, String method, String path, Object body) throws Exception {
        Object o = json(s, method, path, body, 30000);
        return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
    }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    // ---------------- images ----------------

    static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 64L * 1024 * 1024)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    /** Candidate poster URLs for an item, best (fast/local, resized) first. */
    static String[] posters(Svc s, JSONObject item, boolean small) { return covers(s, item, "poster", small); }

    /** Candidate URLs for a cover type ("poster" or "fanart"). */
    static String[] covers(Svc s, JSONObject item, String type, boolean small) {
        boolean poster = type.equals("poster");
        JSONArray imgs = item.optJSONArray("images");
        String local = "", remote = "";
        if (imgs != null) for (int i = 0; i < imgs.length(); i++) {
            JSONObject im = imgs.optJSONObject(i);
            if (im != null && type.equals(im.optString("coverType"))) {
                local = im.optString("url", "");
                remote = im.optString("remoteUrl", "");
            }
        }
        if (poster && remote.isEmpty() && item.has("remotePoster")) remote = item.optString("remotePoster");
        if (remote.contains("image.tmdb.org/t/p/original"))
            remote = remote.replace("/t/p/original", !poster ? "/t/p/w780" : small ? "/t/p/w342" : "/t/p/w500");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (item.optInt("id", 0) > 0 && local.startsWith("/") && local.contains("/MediaCover/")) {
            if (poster) out.add(s.origin() + local.replace("poster.jpg", small ? "poster-250.jpg" : "poster-500.jpg"));
            else out.add(s.origin() + local.replace("fanart.jpg", "fanart-360.jpg"));
            out.add(s.origin() + local);
        }
        if (remote.startsWith("http")) out.add(remote);
        else if (local.startsWith("http")) out.add(local);
        return out.toArray(new String[0]);
    }

    static void image(final ImageView iv, final Svc s, final String[] urls, final int targetW) {
        final String key = urls.length == 0 ? "" : urls[0];
        iv.setTag(key);
        Bitmap b = key.isEmpty() ? null : cache.get(key);
        iv.setImageBitmap(b);
        if (b != null || key.isEmpty()) return;
        imgPool.execute(new Runnable() {
            public void run() {
                if (!key.equals(iv.getTag())) return; // view was recycled while queued
                Bitmap bmp = null;
                for (String u : urls) {
                    try {
                        HttpURLConnection c = open(s, u, "GET", 20000);
                        c.setUseCaches(true);
                        if (c.getResponseCode() >= 400) { c.disconnect(); continue; }
                        byte[] data = read(c.getInputStream());
                        c.disconnect();
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        int sample = 1;
                        while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = sample;
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        if (bmp != null) break;
                    } catch (Exception ignored) {}
                }
                if (bmp == null) return;
                cache.put(key, bmp);
                final Bitmap fb = bmp;
                main.post(new Runnable() {
                    public void run() { if (key.equals(iv.getTag())) iv.setImageBitmap(fb); }
                });
            }
        });
    }
}
