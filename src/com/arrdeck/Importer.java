package com.arrdeck;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Best-effort reader for nzb360 backups (and similar app setting exports).
 * nzb360's format is undocumented, so this does not depend on exact key names: it unwraps
 * zip / gzip / base64, reads JSON or Android shared-prefs XML (or loose key=value text),
 * then looks for groups of settings that mention radarr / sonarr / prowlarr and contain a
 * host/URL and an API key.
 */
final class Importer {

    static final class Found {
        final String svc;
        String url = "", key = "", user = "", pass = "";
        int score;
        boolean local;
        String where;

        Found(String svc) { this.svc = svc; }

        String label() {
            String k = key.length() > 4 ? "\u2026" + key.substring(key.length() - 4) : key;
            return Character.toUpperCase(svc.charAt(0)) + svc.substring(1) + (local ? " (local)" : "") + "  " + url + "  (key " + k + ")";
        }
    }

    static final class Result {
        final List<Found> found = new ArrayList<>();
        final List<String> keys = new ArrayList<>(); // key names seen (no values), for diagnostics
    }

    static final String[] SERVICES = {Svc.RADARR, Svc.SONARR, Svc.PROWLARR};

    /** A bag of fields that belong together (one JSON object, or one prefs key group). */
    private static final class Group {
        final String context;
        final Map<String, String> f = new LinkedHashMap<>();

        Group(String ctx) { context = norm(ctx); }
    }

    static Result parse(byte[] data) {
        Result r = new Result();
        List<Group> groups = new ArrayList<>();
        collect(data, groups, r, 0);
        for (String svc : SERVICES) {
            List<Found> list = new ArrayList<>();
            for (Group g : groups) {
                List<Found> got = new ArrayList<>();
                extract(svc, g, got);
                for (Found f : got) {
                    boolean dup = false;
                    for (Found o : list)
                        if (o.url.equalsIgnoreCase(f.url) && o.key.equals(f.key)) {
                            dup = true;
                            if (f.score > o.score) { o.score = f.score; o.local = f.local; }
                            break;
                        }
                    if (!dup) list.add(f);
                }
            }
            // best first
            java.util.Collections.sort(list, new java.util.Comparator<Found>() {
                public int compare(Found a, Found b) { return b.score - a.score; }
            });
            r.found.addAll(list);
        }
        return r;
    }

    // ---------------------------------------------------------------- unwrapping

    private static void collect(byte[] data, List<Group> groups, Result r, int depth) {
        if (data == null || data.length == 0 || depth > 4) return;
        try {
            if (data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
                collect(Net.read(new GZIPInputStream(new ByteArrayInputStream(data))), groups, r, depth + 1);
                return;
            }
            if (JavaSer.looksLike(data)) {
                for (Object o : JavaSer.read(data)) walk(o, "", groups, r, depth + 1);
                return;
            }
            if (data.length > 4 && data[0] == 'P' && data[1] == 'K') {
                ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data));
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = z.read(buf)) > 0) b.write(buf, 0, n);
                    collect(b.toByteArray(), groups, r, depth + 1);
                }
                return;
            }
        } catch (Exception ignored) {}

        String text = new String(data, java.nio.charset.Charset.forName("UTF-8")).trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String compact = text.replaceAll("\\s", "");
        if (compact.length() > 16 && compact.matches("[A-Za-z0-9+/=_-]+") && !text.contains("{")) {
            try {
                byte[] dec = android.util.Base64.decode(compact, android.util.Base64.DEFAULT | android.util.Base64.URL_SAFE);
                if (dec.length > 0) { collect(dec, groups, r, depth + 1); return; }
            } catch (Exception ignored) {}
        }
        parseText(text, groups, r, depth);
    }

    private static void parseText(String text, List<Group> groups, Result r, int depth) {
        String t = text.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                walk(new JSONTokener(t).nextValue(), "", groups, r, depth);
                return;
            } catch (Exception ignored) {}
        }
        Map<String, String> flat = new LinkedHashMap<>();
        if (t.contains("<map") || t.contains("<string") || t.contains("<boolean")) {
            Matcher m = Pattern.compile("<(string|int|long|float|boolean)\\s+name=\"([^\"]+)\"\\s*(?:value=\"([^\"]*)\"\\s*/>|>([^<]*)</\\1>)", Pattern.DOTALL).matcher(t);
            while (m.find()) flat.put(unxml(m.group(2)), unxml(m.group(3) != null ? m.group(3) : m.group(4)));
        }
        if (flat.isEmpty()) {
            Matcher m = Pattern.compile("\"([^\"\\n]{1,100})\"\\s*[:=]\\s*\"?([^\",}\\]\\n]*)\"?").matcher(t);
            while (m.find()) flat.put(m.group(1), m.group(2).trim());
            Matcher l = Pattern.compile("(?m)^\\s*([A-Za-z0-9_.\\-\\[\\]]{2,100})\\s*[=:]\\s*(.+?)\\s*$").matcher(t);
            while (l.find()) if (!flat.containsKey(l.group(1))) flat.put(l.group(1), l.group(2));
        }
        // a value can itself be serialized JSON (common with prefs-based backups)
        for (Map.Entry<String, String> e : flat.entrySet()) {
            String v = e.getValue().trim();
            if ((v.startsWith("{") || v.startsWith("[")) && v.length() > 2) {
                try { walk(new JSONTokener(v).nextValue(), e.getKey(), groups, r, depth + 1); } catch (Exception ignored) {}
            }
        }
        groupFlat(flat, groups, r);
    }

    private static String unxml(String s) {
        if (s == null) return "";
        return s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#10;", "\n").replace("&amp;", "&");
    }

    /** JSON: every object becomes a group; nested serialized JSON strings are expanded. */
    private static void walk(Object o, String path, List<Group> groups, Result r, int depth) {
        if (depth > 8) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Group g = new Group(path);
            StringBuilder ctx = new StringBuilder(path);
            if (j.optBoolean("IsProwlarr") || j.optBoolean("isProwlarr")) ctx.append(" prowlarr");
            Map<String, String> flatChildren = new LinkedHashMap<>();
            Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = j.opt(k);
                String p = path.isEmpty() ? k : path + "." + k;
                if (v instanceof JSONObject || v instanceof JSONArray) { walk(v, p, groups, r, depth + 1); continue; }
                String sv = v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
                String st = sv.trim();
                if ((st.startsWith("{") || st.startsWith("[")) && st.length() > 2) {
                    try { walk(new JSONTokener(st).nextValue(), p, groups, r, depth + 1); continue; } catch (Exception ignored) {}
                }
                g.f.put(k, sv);
                flatChildren.put(k, sv);
                if (r.keys.size() < 400) r.keys.add(p);
                if (sv.length() < 40) ctx.append(' ').append(sv);
            }
            Group real = new Group(ctx.toString());
            real.f.putAll(g.f);
            groups.add(real);
            // Objects that are really a flat settings map (e.g. {"radarr_ip": ..., "sonarr_ip": ...})
            groupFlat(flatChildren, groups, null);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), path + "[" + i + "]", groups, r, depth + 1);
        }
    }

    /** Flat key/value maps: group keys mentioning a service by the profile number in the key. */
    private static void groupFlat(Map<String, String> flat, List<Group> groups, Result r) {
        Map<String, Group> byId = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : flat.entrySet()) {
            String k = e.getKey();
            if (r != null && r.keys.size() < 400) r.keys.add(k);
            String lk = norm(k);
            for (String svc : SERVICES) {
                if (!lk.contains(svc)) continue;
                String rest = lk.replace(svc, " ");
                Matcher d = Pattern.compile("\\d+").matcher(rest);
                StringBuilder id = new StringBuilder(svc);
                while (d.find()) id.append('#').append(d.group());
                Group g = byId.get(id.toString());
                if (g == null) { g = new Group(svc); byId.put(id.toString(), g); }
                g.f.put(rest, e.getValue());
            }
        }
        groups.addAll(byId.values());
    }

    // ---------------------------------------------------------------- field extraction

    private static final Pattern HEX32 = Pattern.compile("^[a-fA-F0-9]{32}$");

    /** Splits snake_case / camelCase / dotted keys into lowercase tokens. */
    private static Set<String> tokens(String key) {
        Set<String> t = new HashSet<>();
        for (String part : key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase().split("[^a-z0-9]+"))
            if (!part.isEmpty()) t.add(part);
        return t;
    }

    private static boolean any(Set<String> t, String... words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    private static void extract(String svc, Group g, List<Found> out) {
        // Groups must name the service in their path/values; flat key maps are regrouped by groupFlat().
        if (!g.context.contains(svc)) return;
        // If the group mentions *other* services too (a flat settings blob), only use keys naming this one.
        boolean keyFilter = false;
        for (String o : SERVICES) if (!o.equals(svc) && g.context.contains(o)) keyFilter = true;

        String url = null, localUrl = null, host = null, port = null, base = null, key = null, user = null, pass = null;
        Boolean ssl = null;
        int keyScore = 0;
        for (Map.Entry<String, String> e : g.f.entrySet()) {
            String k = norm(e.getKey()), v = e.getValue() == null ? "" : e.getValue().trim();
            if (keyFilter && !k.contains(svc)) continue;
            if (v.isEmpty()) continue;
            Set<String> t = tokens(e.getKey().replaceAll("(?i)nzbdrone|radarr|sonarr|prowlarr", "_"));
            boolean local = any(t, "local", "lan", "internal");
            boolean apiLike = any(t, "api", "apikey", "key", "token") && !any(t, "enabled", "unraid");
            if (apiLike && v.matches("[A-Za-z0-9_\\-]{16,}")) {
                int sc = HEX32.matcher(v).matches() ? 3 : 1;
                if (sc > keyScore) { key = v; keyScore = sc; }
            } else if (v.matches("(?i)^https?://.+")) {
                if (local) { if (localUrl == null) localUrl = v; }
                else if (url == null || any(t, "url", "host", "primary", "connectionstring")) url = v;
            } else if (any(t, "port") && v.matches("\\d{1,5}")) {
                port = v;
            } else if (any(t, "ssl", "https", "secure", "tls")) {
                if (v.equalsIgnoreCase("true") || v.equals("1")) ssl = true;
                else if (v.equalsIgnoreCase("false") || v.equals("0")) ssl = false;
            } else if (any(t, "pass", "password", "pwd")) {
                pass = v;
            } else if (any(t, "user", "username", "login")) {
                user = v;
            } else if (any(t, "urlbase", "base", "webroot", "subdir", "subpath")) {
                if (v.startsWith("/") || v.matches("[A-Za-z][A-Za-z0-9_\\-]*")) base = v;
            } else if (any(t, "url", "host", "hostname", "ip", "address", "server", "domain", "connectionstring")
                    && !any(t, "ssid", "mac", "broadcast")) {
                if (v.matches("[A-Za-z0-9.\\-_\\[\\]:]+(/.*)?") && (v.contains(".") || v.contains(":") || v.equalsIgnoreCase("localhost"))) {
                    if (local) { if (localUrl == null) localUrl = v; } else host = v;
                }
            }
        }
        if (key == null) return;
        String primary = url != null ? url : host != null ? host : null;
        int added = 0;
        for (String u : new String[]{primary, localUrl}) {
            if (u == null) continue;
            String full = build(u, port, base, ssl);
            if (svc.equals(Svc.PROWLARR)) full = full.replaceAll("/\\d+(/api)?/?$", "").replaceAll("/api/?$", "");
            Found f = new Found(svc);
            f.url = Svc.normalize(full);
            f.key = key;
            f.user = user == null ? "" : user;
            f.pass = pass == null ? "" : pass;
            f.score = keyScore * 2 + (url != null ? 2 : 1) + (u == primary ? 2 : 0);
            f.local = u != primary;
            f.where = g.context;
            out.add(f);
            added++;
        }
    }

    private static String build(String u, String port, String base, Boolean ssl) {
        String full;
        if (u.matches("(?i)^https?://.+")) {
            full = u;
            if (port != null && !full.matches("(?i)^https?://[^/]+:\\d+.*")) {
                int slash = full.indexOf('/', full.indexOf("//") + 2);
                full = slash < 0 ? full + ":" + port : full.substring(0, slash) + ":" + port + full.substring(slash);
            }
        } else {
            String scheme = Boolean.TRUE.equals(ssl) ? "https://" : "http://";
            String h = u, path = "";
            int slash = h.indexOf('/');
            if (slash > 0) { path = h.substring(slash); h = h.substring(0, slash); }
            full = scheme + h + (port != null && !h.matches(".*:\\d+$") ? ":" + port : "") + path;
        }
        if (base != null && base.length() > 1 && !full.matches("(?i)^https?://[^/]+/.+"))
            full = full + (base.startsWith("/") ? base : "/" + base);
        return full;
    }

    /** nzb360 (and Sonarr's own history) call Sonarr "NzbDrone". */
    static String norm(String s) { return s.toLowerCase().replace("nzbdrone", "sonarr"); }
}
