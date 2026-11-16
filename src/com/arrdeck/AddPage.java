package com.arrdeck;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search TMDb/TVDb through Radarr / Sonarr lookup and add to the library.
 * Single mode (from the Movies / Shows tabs) searches one service; combined mode (Search tab)
 * queries both at once and mixes the results: yellow = movie (Radarr), blue = show (Sonarr).
 */
final class AddPage extends Page {
    /** Per-service state: connection plus the profile / folder options used by the add dialog. */
    static final class Kind {
        final boolean movies;
        Svc svc;
        JSONArray profiles, roots, langProfiles;
        boolean loading;

        Kind(boolean movies) { this.movies = movies; }

        int color() { return movies ? Ui.RADARR : Ui.SONARR; }
    }

    static final String KIND = "_arrdeckMovie"; // tag stored on each lookup result

    final boolean combined;
    final List<Kind> kinds = new ArrayList<>();
    JAdapter adapter;
    TextView status;
    EditText q;
    int searchSeq;
    String svcConfig = "";

    static final String[] MOVIE_AVAIL = {"announced", "inCinemas", "released"};
    static final String[] MOVIE_AVAIL_L = {"Announced", "In cinemas", "Released"};
    static final String[] MONITOR = {"all", "future", "missing", "existing", "firstSeason", "latestSeason", "pilot", "none"};
    static final String[] MONITOR_L = {"All episodes", "Future episodes", "Missing episodes", "Existing episodes",
            "First season", "Latest season", "Pilot only", "None"};
    static final String[] TYPES = {"standard", "daily", "anime"};

    /** Single-service add page (Movies or Shows tab). */
    AddPage(Main m, boolean movies) {
        super(m);
        combined = false;
        kinds.add(new Kind(movies));
        reloadServices();
    }

    /** Combined movies + shows search, embedded in the Search tab. */
    AddPage(Main m) {
        super(m);
        combined = true;
        kinds.add(new Kind(true));
        kinds.add(new Kind(false));
        reloadServices();
    }

    /** Re-reads connections; returns true if anything changed. */
    boolean reloadServices() {
        StringBuilder cfg = new StringBuilder();
        for (Kind k : kinds) {
            Svc s = Svc.load(m, k.movies ? Svc.RADARR : Svc.SONARR);
            cfg.append(s.url).append(s.key).append('|');
            if (k.svc == null || !s.url.equals(k.svc.url) || !s.key.equals(k.svc.key)) {
                k.profiles = k.roots = k.langProfiles = null;
            }
            k.svc = s;
        }
        boolean changed = !cfg.toString().equals(svcConfig);
        svcConfig = cfg.toString();
        return changed;
    }

    Kind kind(boolean movies) {
        for (Kind k : kinds) if (k.movies == movies) return k;
        return kinds.get(0);
    }

    Kind kindOf(JSONObject o) { return kind(o.optBoolean(KIND, kinds.get(0).movies)); }

    List<Kind> ready() {
        List<Kind> l = new ArrayList<>();
        for (Kind k : kinds) if (k.svc.ok()) l.add(k);
        return l;
    }

    String idleText() {
        if (!combined) return "Search to add " + (kinds.get(0).movies ? "movies" : "shows") + " to " + kinds.get(0).svc.name();
        List<Kind> r = ready();
        if (r.isEmpty()) return "Radarr and Sonarr aren't configured.\nImport your nzb360 backup or add them in Settings.";
        if (r.size() == 1) return "Search " + (r.get(0).movies ? "movies" : "shows") + " to add.\n("
                + (r.get(0).movies ? "Sonarr" : "Radarr") + " isn't configured)";
        return "Search movies and shows at once.\nYellow = movie (Radarr) · Blue = show (Sonarr)";
    }

    @Override View build() {
        LinearLayout root = Ui.col(m);
        Kind first = kinds.get(0);
        if (!combined) root.addView(Ui.header(m, first.movies ? "Add movie" : "Add show", first.color(), true));
        LinearLayout bar = Ui.row(m);
        bar.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(8));
        String hint = combined ? "Movie or show title…"
                : first.movies ? "Movie title, tmdb:123 or imdb:tt…" : "Show title or tvdb:123";
        q = Ui.input(m, hint, "", false);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int a, KeyEvent e) { search(); return true; }
        });
        bar.addView(q, Ui.weight(1));
        bar.addView(Ui.button(m, "Search", combined ? Ui.ACCENT : first.color(), new View.OnClickListener() {
            public void onClick(View v) { search(); }
        }), Ui.margins(-2, -2, 8, 0, 0, 0));
        root.addView(bar);

        FrameLayout body = new FrameLayout(m);
        ListView lv = new ListView(m);
        lv.setDivider(null);
        adapter = new JAdapter(new JAdapter.Binder() {
            public View bind(JSONObject o, View cv, ViewGroup p) { return bindResult(o, cv); }
        });
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) { tap(adapter.items.get(pos)); }
        });
        status = m.status(idleText());
        body.addView(lv);
        body.addView(status, new FrameLayout.LayoutParams(-1, -2));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!combined) {
            q.requestFocus();
            Net.main.postDelayed(new Runnable() {
                public void run() {
                    ((android.view.inputmethod.InputMethodManager) m.getSystemService(Main.INPUT_METHOD_SERVICE)).showSoftInput(q, 0);
                }
            }, 200);
        }
        for (Kind k : ready()) loadOptions(k);
        return root;
    }

    @Override void onShow() {
        if (root == null) return;
        if (reloadServices()) {
            adapter.set(new ArrayList<JSONObject>());
            status.setVisibility(View.VISIBLE);
            status.setText(idleText());
        }
        for (Kind k : ready()) if (k.profiles == null) loadOptions(k);
    }

    void loadOptions(final Kind k) {
        if (k.loading) return;
        k.loading = true;
        final Svc svc = k.svc;
        Net.async(m, new Net.Job<JSONArray[]>() {
            public JSONArray[] run() throws Exception {
                JSONArray lang = null;
                if (!k.movies) try { lang = Net.arr(svc, "/languageprofile"); } catch (Exception ignored) {} // Sonarr v3 only
                return new JSONArray[]{Net.arr(svc, "/qualityprofile"), Net.arr(svc, "/rootfolder"), lang};
            }
        }, new Net.Ok<JSONArray[]>() {
            public void ok(JSONArray[] r) { k.loading = false; k.profiles = r[0]; k.roots = r[1]; k.langProfiles = r[2]; }
        }, new Net.Ok<Exception>() {
            public void ok(Exception e) { k.loading = false; Ui.toast(m, svc.name() + ": couldn't load profiles – " + Net.msg(e)); }
        });
    }

    void search() {
        final String term = q.getText().toString().trim();
        if (term.isEmpty()) return;
        reloadServices();
        final List<Kind> active = ready();
        if (active.isEmpty()) { Ui.toast(m, "Set up Radarr or Sonarr in Settings first"); return; }
        m.hideKeyboard();
        final int seq = ++searchSeq;
        status.setVisibility(View.VISIBLE);
        status.setText("Searching…");
        adapter.set(new ArrayList<JSONObject>());
        final List<List<JSONObject>> parts = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final int[] pending = {active.size()};
        for (final Kind k : active) {
            final List<JSONObject> slot = new ArrayList<>();
            parts.add(slot);
            final Svc svc = k.svc;
            Net.async(m, new Net.Job<JSONArray>() {
                public JSONArray run() throws Exception {
                    return Net.arr(svc, (k.movies ? "/movie/lookup?term=" : "/series/lookup?term=") + Net.enc(term));
                }
            }, new Net.Ok<JSONArray>() {
                public void ok(JSONArray a) {
                    if (seq != searchSeq) return;
                    for (JSONObject o : LibraryPage.toList(a)) {
                        try { o.put(KIND, k.movies); } catch (Exception ignored) {}
                        slot.add(o);
                    }
                    pending[0]--;
                    show(term, parts, errors, pending[0] == 0);
                }
            }, new Net.Ok<Exception>() {
                public void ok(Exception e) {
                    if (seq != searchSeq) return;
                    errors.add(svc.name() + ": " + Net.msg(e));
                    pending[0]--;
                    show(term, parts, errors, pending[0] == 0);
                }
            });
        }
    }

    /** Shows results as they arrive; exact / prefix title matches first, then the services' own order, interleaved. */
    void show(String term, List<List<JSONObject>> parts, List<String> errors, boolean done) {
        final String t = term.toLowerCase(Locale.ROOT);
        List<JSONObject> merged = new ArrayList<>();
        int max = 0;
        for (List<JSONObject> p : parts) max = Math.max(max, p.size());
        for (int i = 0; i < max; i++) for (List<JSONObject> p : parts) if (i < p.size()) merged.add(p.get(i));
        final List<JSONObject> base = new ArrayList<>(merged);
        java.util.Collections.sort(merged, new java.util.Comparator<JSONObject>() {
            int rank(JSONObject o) {
                String title = o.optString("title").toLowerCase(Locale.ROOT);
                return title.equals(t) ? 0 : title.startsWith(t) ? 1 : 2;
            }

            public int compare(JSONObject a, JSONObject b) {
                int r = rank(a) - rank(b);
                return r != 0 ? r : base.indexOf(a) - base.indexOf(b);
            }
        });
        adapter.set(merged);
        if (!merged.isEmpty()) {
            status.setVisibility(View.GONE);
            if (done && !errors.isEmpty()) Ui.toast(m, errors.get(0));
        } else if (done) {
            status.setVisibility(View.VISIBLE);
            status.setText(errors.isEmpty() ? "No results for “" + term + "”" : "Search failed\n" + android.text.TextUtils.join("\n", errors));
        }
    }

    View bindResult(JSONObject o, View cv) {
        Ui.Row r = cv instanceof Ui.Row ? (Ui.Row) cv : new Ui.Row(m, true);
        Kind k = kindOf(o);
        int color = k.color();
        r.bar.setVisibility(View.GONE);
        r.title.setText(o.optString("title") + (o.optInt("year") > 0 ? " (" + o.optInt("year") + ")" : ""));
        String sub;
        if (k.movies) {
            int rt = o.optInt("runtime");
            sub = o.optString("studio") + (rt > 0 ? (o.optString("studio").isEmpty() ? "" : " · ") + rt + " min" : "");
        } else {
            int seasons = o.optInt("seasonCount", o.optJSONArray("seasons") == null ? 0 : o.optJSONArray("seasons").length());
            sub = o.optString("network") + (seasons > 0 ? (o.optString("network").isEmpty() ? "" : " · ") + seasons + " season" + (seasons == 1 ? "" : "s") : "")
                    + (o.optString("status").isEmpty() ? "" : " · " + o.optString("status"));
        }
        Ui.set(r.sub, sub);
        Ui.set(r.sub2, o.optString("overview"));
        boolean have = o.optInt("id") > 0;
        Ui.set(r.r1, have ? "✔" : "＋");
        r.r1.setTextColor(have ? Ui.GREEN : color);
        if (combined) {
            // nzb360-style colour coding: stripe + type label
            r.accent.setVisibility(View.VISIBLE);
            r.accent.setBackground(Ui.round(color, 2));
            Ui.set(r.r2, (k.movies ? "MOVIE" : "SHOW") + (have ? "\nIn library" : ""));
            r.r2.setTextColor(color);
        } else {
            r.accent.setVisibility(View.GONE);
            Ui.set(r.r2, have ? "In library" : "");
            r.r2.setTextColor(Ui.SUB);
        }
        Net.image(r.thumb, k.svc, Net.posters(k.svc, o, true), Ui.dp(46));
        return r;
    }

    void tap(JSONObject o) {
        m.push(new InfoPage(m, this, kindOf(o), o));
    }

    Runnable afterAdd;

    /** Opens the add dialog for a lookup result; {@code done} runs once it has been added. */
    void requestAdd(JSONObject o, Kind k, Runnable done) {
        afterAdd = done;
        if (k.profiles == null || k.roots == null) {
            Ui.toast(m, "Still loading " + k.svc.name() + " profiles…");
            loadOptions(k);
            return;
        }
        if (k.roots.length() == 0) {
            Ui.toast(m, "No root folders set up in " + k.svc.name());
            return;
        }
        showAddDialog(o, k);
    }

    Spinner spinner(String[] items, int sel) {
        Spinner s = new Spinner(m);
        ArrayAdapter<String> a = new ArrayAdapter<>(m, android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(a);
        if (sel >= 0 && sel < items.length) s.setSelection(sel);
        return s;
    }

    static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    void label(LinearLayout l, String s) {
        l.addView(Ui.text(m, s, 12, Ui.SUB, true), Ui.margins(-2, -2, 4, 12, 0, 0));
    }

    void showAddDialog(final JSONObject o, final Kind k) {
        final boolean movies = k.movies;
        final JSONArray profiles = k.profiles, roots = k.roots, langProfiles = k.langProfiles;
        final SharedPreferences p = Svc.prefs(m);
        final String pre = k.svc.p("_add_");
        ScrollView sv = new ScrollView(m);
        LinearLayout l = Ui.col(m);
        l.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), 0);
        sv.addView(l);

        TextView type = Ui.text(m, movies ? "MOVIE · Radarr" : "SHOW · Sonarr", 12, k.color(), true);
        l.addView(type, Ui.margins(-2, -2, 4, 4, 0, 0));

        final String[] profNames = new String[profiles.length()];
        int profSel = 0;
        for (int i = 0; i < profiles.length(); i++) {
            profNames[i] = profiles.optJSONObject(i).optString("name");
            if (profiles.optJSONObject(i).optInt("id") == p.getInt(pre + "profile", -1)) profSel = i;
        }
        final String[] rootNames = new String[roots.length()];
        int rootSel = 0;
        for (int i = 0; i < roots.length(); i++) {
            JSONObject r = roots.optJSONObject(i);
            rootNames[i] = r.optString("path") + (r.has("freeSpace") ? "  (" + Ui.size(r.optLong("freeSpace")) + " free)" : "");
            if (r.optString("path").equals(p.getString(pre + "root", ""))) rootSel = i;
        }
        label(l, "Quality profile");
        final Spinner prof = spinner(profNames, profSel);
        l.addView(prof);
        label(l, "Root folder");
        final Spinner root = spinner(rootNames, rootSel);
        l.addView(root);

        final Spinner extra1, extra2, lang;
        final CheckBox seasonFolder = new CheckBox(m);
        if (movies) {
            label(l, "Minimum availability");
            extra1 = spinner(MOVIE_AVAIL_L, indexOf(MOVIE_AVAIL, p.getString(pre + "avail", "released")));
            l.addView(extra1);
            extra2 = null;
            lang = null;
        } else {
            label(l, "Monitor");
            extra1 = spinner(MONITOR_L, indexOf(MONITOR, p.getString(pre + "monitor", "all")));
            l.addView(extra1);
            label(l, "Series type");
            String guess = o.optString("seriesType", p.getString(pre + "type", "standard"));
            extra2 = spinner(new String[]{"Standard", "Daily", "Anime"}, indexOf(TYPES, guess));
            l.addView(extra2);
            if (langProfiles != null && langProfiles.length() > 0) {
                String[] ln = new String[langProfiles.length()];
                for (int i = 0; i < ln.length; i++) ln[i] = langProfiles.optJSONObject(i).optString("name");
                label(l, "Language profile");
                lang = spinner(ln, 0);
                l.addView(lang);
            } else lang = null;
            seasonFolder.setText("Use season folders");
            seasonFolder.setTextColor(Ui.TEXT);
            seasonFolder.setChecked(p.getBoolean(pre + "seasonFolder", true));
            l.addView(seasonFolder, Ui.margins(-2, -2, 0, 10, 0, 0));
        }
        final CheckBox searchNow = new CheckBox(m);
        searchNow.setText(movies ? "Start search for movie" : "Start search for missing episodes");
        searchNow.setTextColor(Ui.TEXT);
        searchNow.setChecked(p.getBoolean(pre + "search", true));
        l.addView(searchNow);

        new AlertDialog.Builder(m).setTitle(o.optString("title")).setView(sv)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            JSONObject pr = profiles.optJSONObject(prof.getSelectedItemPosition());
                            String rootPath = roots.optJSONObject(root.getSelectedItemPosition()).optString("path");
                            SharedPreferences.Editor ed = p.edit().putInt(pre + "profile", pr.optInt("id"))
                                    .putString(pre + "root", rootPath).putBoolean(pre + "search", searchNow.isChecked());
                            final JSONObject body = new JSONObject(o.toString());
                            body.remove(KIND);
                            body.put("qualityProfileId", pr.optInt("id"));
                            body.put("rootFolderPath", rootPath);
                            body.put("monitored", true);
                            if (movies) {
                                String av = MOVIE_AVAIL[extra1.getSelectedItemPosition()];
                                ed.putString(pre + "avail", av);
                                body.put("minimumAvailability", av);
                                body.put("addOptions", new JSONObject().put("searchForMovie", searchNow.isChecked())
                                        .put("monitor", "movieOnly"));
                            } else {
                                String mon = MONITOR[extra1.getSelectedItemPosition()];
                                String type = TYPES[extra2.getSelectedItemPosition()];
                                ed.putString(pre + "monitor", mon).putString(pre + "type", type)
                                        .putBoolean(pre + "seasonFolder", seasonFolder.isChecked());
                                body.put("seriesType", type);
                                body.put("seasonFolder", seasonFolder.isChecked());
                                body.put("monitored", !"none".equals(mon));
                                if (lang != null) body.put("languageProfileId", langProfiles.optJSONObject(lang.getSelectedItemPosition()).optInt("id"));
                                body.put("addOptions", new JSONObject().put("monitor", mon)
                                        .put("searchForMissingEpisodes", searchNow.isChecked())
                                        .put("searchForCutoffUnmetEpisodes", false));
                            }
                            ed.apply();
                            add(o, body, k);
                        } catch (Exception e) { Ui.toast(m, Net.msg(e)); }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    void add(final JSONObject original, final JSONObject body, final Kind k) {
        Ui.toast(m, "Adding…");
        final Svc svc = k.svc;
        Net.async(m, new Net.Job<JSONObject>() {
            public JSONObject run() throws Exception { return Net.obj(svc, "POST", k.movies ? "/movie" : "/series", body); }
        }, new Net.Ok<JSONObject>() {
            public void ok(JSONObject added) {
                Ui.toast(m, "Added " + original.optString("title"));
                try { original.put("id", added.optInt("id", 1)); } catch (Exception ignored) {}
                adapter.notifyDataSetChanged();
                m.dirty(k.movies);
                if (afterAdd != null) { afterAdd.run(); afterAdd = null; }
            }
        }, null);
    }
}
