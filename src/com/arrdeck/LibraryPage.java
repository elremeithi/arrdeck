package com.arrdeck;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Radarr movies or Sonarr series: Library grid, Upcoming calendar, download Queue. */
final class LibraryPage extends Page {
    final boolean movies;
    Svc svc;
    int mode = 0; // 0 library, 1 upcoming, 2 queue
    int filter = 0, sort = 0;
    static final String[] FILTERS = {"All", "Missing", "Downloaded", "Monitored", "Unmonitored"};
    static final String[] SORTS = {"Title", "Recently added", "Year", "Size"};

    JSONArray library;
    final JAdapter gridAdapter, listAdapter;
    GridView grid;
    ListView list;
    TextView status, filterChip, sortChip;
    TextView[] modeChips = new TextView[3];
    EditText search;
    LinearLayout libTools;
    int loadedMode = -1;
    long loadedAt;
    String lastSvcConfig = "";

    LibraryPage(Main m, boolean movies) {
        super(m);
        this.movies = movies;
        gridAdapter = new JAdapter(new JAdapter.Binder() {
            public View bind(JSONObject o, View cv, ViewGroup p) { return bindPoster(o, cv); }
        });
        listAdapter = new JAdapter(new JAdapter.Binder() {
            public View bind(JSONObject o, View cv, ViewGroup p) { return mode == 2 ? bindQueue(o, cv) : bindUpcoming(o, cv); }
        });
    }

    int color() { return movies ? Ui.RADARR : Ui.SONARR; }

    @Override View build() {
        LinearLayout root = Ui.col(m);
        root.addView(Ui.header(m, movies ? "Movies" : "Shows", color(), false,
                Ui.iconBtn(m, "＋", new View.OnClickListener() {
                    public void onClick(View v) { if (check()) m.push(new AddPage(m, movies)); }
                }),
                Ui.iconBtn(m, "↻", new View.OnClickListener() {
                    public void onClick(View v) { load(true); }
                })));

        LinearLayout chips = Ui.row(m);
        chips.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(8));
        String[] names = {"Library", "Upcoming", "Queue"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            modeChips[i] = Ui.chip(m, names[i], new View.OnClickListener() {
                public void onClick(View v) { setMode(idx); }
            });
            chips.addView(modeChips[i], Ui.margins(-2, -2, 0, 0, 8, 0));
        }
        root.addView(chips);

        libTools = Ui.row(m);
        libTools.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(8));
        search = Ui.input(m, "Filter library…", "", false);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { applyLibrary(); }
        });
        libTools.addView(search, Ui.weight(1));
        filterChip = Ui.chip(m, FILTERS[0], new View.OnClickListener() {
            public void onClick(View v) { pick("Show", FILTERS, filter, true); }
        });
        sortChip = Ui.chip(m, "⇅", new View.OnClickListener() {
            public void onClick(View v) { pick("Sort by", SORTS, sort, false); }
        });
        libTools.addView(filterChip, Ui.margins(-2, -2, 8, 0, 0, 0));
        libTools.addView(sortChip, Ui.margins(-2, -2, 6, 0, 0, 0));
        root.addView(libTools);

        FrameLayout body = new FrameLayout(m);
        grid = new GridView(m);
        grid.setColumnWidth(Ui.dp(108));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(10));
        grid.setVerticalSpacing(Ui.dp(14));
        grid.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(16));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setAdapter(gridAdapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) { open(gridAdapter.items.get(pos)); }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) { if (s != SCROLL_STATE_IDLE) m.hideKeyboard(); }
            public void onScroll(AbsListView v, int a, int b, int c) {}
        });
        list = new ListView(m);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setAdapter(listAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                JSONObject o = listAdapter.items.get(pos);
                if (mode == 2) queueActions(o);
                else if (movies) m.push(new MovieDetailPage(m, o));
                else if (o.optJSONObject("series") != null) m.push(new SeriesDetailPage(m, o.optJSONObject("series")));
            }
        });
        status = m.status("");
        body.addView(grid);
        body.addView(list);
        body.addView(status, new FrameLayout.LayoutParams(-1, -2));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setModeUi();
        return root;
    }

    boolean check() {
        svc = Svc.load(m, movies ? Svc.RADARR : Svc.SONARR);
        if (!svc.ok()) Ui.toast(m, svc.name() + " isn't set up yet – see Settings");
        return svc.ok();
    }

    @Override void onShow() {
        Svc s = Svc.load(m, movies ? Svc.RADARR : Svc.SONARR);
        String cfg = s.url + s.key;
        boolean dirty = movies ? m.moviesDirty : m.showsDirty;
        if (!cfg.equals(lastSvcConfig) || dirty || loadedMode != mode
                || System.currentTimeMillis() - loadedAt > 5 * 60 * 1000) load(dirty || !cfg.equals(lastSvcConfig));
    }

    void setMode(int i) {
        mode = i;
        setModeUi();
        load(false);
    }

    void setModeUi() {
        for (int k = 0; k < 3; k++) Ui.setChip(modeChips[k], k == mode, color());
        libTools.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        grid.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        list.setVisibility(mode == 0 ? View.GONE : View.VISIBLE);
    }

    void pick(String title, String[] opts, int cur, final boolean isFilter) {
        new AlertDialog.Builder(m).setTitle(title).setSingleChoiceItems(opts, cur, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (isFilter) { filter = w; filterChip.setText(FILTERS[w]); } else sort = w;
                d.dismiss();
                applyLibrary();
            }
        }).show();
    }

    void setStatus(String s) {
        status.setText(s);
        status.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void load(boolean force) {
        svc = Svc.load(m, movies ? Svc.RADARR : Svc.SONARR);
        if (!(svc.url + svc.key).equals(lastSvcConfig)) { // different server: drop the old library at once
            library = null;
            gridAdapter.set(new ArrayList<JSONObject>());
        }
        lastSvcConfig = svc.url + svc.key;
        if (!svc.ok()) {
            gridAdapter.set(new ArrayList<JSONObject>());
            listAdapter.set(new ArrayList<JSONObject>());
            setStatus(svc.name() + " isn't configured.\nImport your nzb360 backup or add it in Settings.");
            return;
        }
        if (movies) m.moviesDirty = false; else m.showsDirty = false;
        final int forMode = mode;
        loadedMode = mode;
        loadedAt = System.currentTimeMillis();
        if (mode == 0) {
            if (library != null && !force) { applyLibrary(); return; }
            if (library == null) setStatus("Loading…");
            Net.async(m, new Net.Job<JSONArray>() {
                public JSONArray run() throws Exception { return Net.arr(svc, movies ? "/movie" : "/series"); }
            }, new Net.Ok<JSONArray>() {
                public void ok(JSONArray a) { library = a; if (mode == 0) applyLibrary(); }
            }, err(forMode));
        } else {
            listAdapter.set(new ArrayList<JSONObject>());
            setStatus("Loading…");
            final String path;
            if (mode == 1) {
                String range = "?start=" + Ui.isoNow(-86400000L) + "&end=" + Ui.isoNow((movies ? 90L : 21L) * 86400000L);
                path = "/calendar" + range + (movies ? "" : "&includeSeries=true");
            } else path = "/queue?page=1&pageSize=100&" + (movies ? "includeMovie=true" : "includeSeries=true&includeEpisode=true");
            Net.async(m, new Net.Job<JSONArray>() {
                public JSONArray run() throws Exception { return Net.arr(svc, path); }
            }, new Net.Ok<JSONArray>() {
                public void ok(JSONArray a) {
                    if (mode != forMode) return;
                    List<JSONObject> l = toList(a);
                    if (forMode == 1) sortUpcoming(l);
                    listAdapter.set(l);
                    setStatus(l.isEmpty() ? (forMode == 1 ? "Nothing coming up" : "Queue is empty") : "");
                }
            }, err(forMode));
        }
    }

    Net.Ok<Exception> err(final int forMode) {
        return new Net.Ok<Exception>() {
            public void ok(Exception e) {
                if (mode != forMode) return;
                if (forMode == 0 && library != null) { Ui.toast(m, Net.msg(e)); return; }
                setStatus("Couldn't reach " + svc.name() + "\n" + Net.msg(e) + "\n\nTap ↻ to retry");
                loadedMode = -1;
            }
        };
    }

    static List<JSONObject> toList(JSONArray a) {
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) l.add(a.optJSONObject(i));
        return l;
    }

    // ---------------------------------------------------------------- library grid

    static boolean complete(JSONObject o, boolean movies) {
        if (movies) return o.optBoolean("hasFile");
        JSONObject st = o.optJSONObject("statistics");
        if (st == null) return false;
        int have = st.optInt("episodeFileCount"), want = st.optInt("episodeCount");
        return want > 0 && have >= want;
    }

    static long sizeOf(JSONObject o) {
        JSONObject st = o.optJSONObject("statistics");
        return o.has("sizeOnDisk") ? o.optLong("sizeOnDisk") : st == null ? 0 : st.optLong("sizeOnDisk");
    }

    void applyLibrary() {
        if (library == null || mode != 0) return;
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : toList(library)) {
            if (!q.isEmpty() && !o.optString("title").toLowerCase(Locale.ROOT).contains(q)) continue;
            boolean done = complete(o, movies), mon = o.optBoolean("monitored");
            if (filter == 1 && (done || !mon)) continue;
            if (filter == 2 && !done) continue;
            if (filter == 3 && !mon) continue;
            if (filter == 4 && mon) continue;
            out.add(o);
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                switch (sort) {
                    case 1: return b.optString("added").compareTo(a.optString("added"));
                    case 2: return b.optInt("year") - a.optInt("year");
                    case 3: return Long.compare(sizeOf(b), sizeOf(a));
                    default: return a.optString("sortTitle", a.optString("title")).compareToIgnoreCase(b.optString("sortTitle", b.optString("title")));
                }
            }
        });
        gridAdapter.set(out);
        setStatus(out.isEmpty() ? (library.length() == 0 ? "Library is empty – tap ＋ to add" : "No matches") : "");
    }

    static final class Cell {
        Ui.Poster poster;
        TextView title, sub, badge;
        Ui.Bar bar;
    }

    View bindPoster(JSONObject o, View cv) {
        Cell c;
        if (cv == null) {
            c = new Cell();
            LinearLayout l = Ui.col(m);
            FrameLayout f = new FrameLayout(m);
            c.poster = new Ui.Poster(m);
            f.addView(c.poster);
            c.badge = Ui.text(m, "", 10, 0xFF111111, true);
            c.badge.setPadding(Ui.dp(5), Ui.dp(1), Ui.dp(5), Ui.dp(1));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.END);
            bp.setMargins(0, Ui.dp(4), Ui.dp(4), 0);
            f.addView(c.badge, bp);
            l.addView(f);
            c.bar = new Ui.Bar(m);
            l.addView(c.bar, Ui.margins(-1, Ui.dp(3), 0, 5, 0, 3));
            c.title = Ui.text(m, "", 12, Ui.TEXT, true);
            c.title.setSingleLine();
            c.title.setEllipsize(TextUtils.TruncateAt.END);
            c.sub = Ui.text(m, "", 11, Ui.SUB, false);
            c.sub.setSingleLine();
            l.addView(c.title);
            l.addView(c.sub);
            l.setTag(c);
            cv = l;
        }
        c = (Cell) cv.getTag();
        c.title.setText(o.optString("title"));
        boolean mon = o.optBoolean("monitored");
        if (movies) {
            boolean has = o.optBoolean("hasFile");
            boolean avail = o.optBoolean("isAvailable", true);
            int col = has ? Ui.GREEN : !mon ? Ui.DIM : avail ? Ui.RED : Ui.BLUE;
            c.bar.set(1f, col);
            String q = "";
            JSONObject mf = o.optJSONObject("movieFile");
            if (mf != null && mf.optJSONObject("quality") != null && mf.optJSONObject("quality").optJSONObject("quality") != null)
                q = mf.optJSONObject("quality").optJSONObject("quality").optString("name");
            c.sub.setText(o.optInt("year") + (q.isEmpty() ? "" : " · " + q));
            setBadge(c.badge, has ? "" : !avail && mon ? "SOON" : "", Ui.BLUE);
        } else {
            JSONObject st = o.optJSONObject("statistics");
            int have = st == null ? 0 : st.optInt("episodeFileCount"), want = st == null ? 0 : st.optInt("episodeCount");
            boolean done = want > 0 && have >= want;
            c.bar.set(want == 0 ? 1f : have / (float) want, done ? Ui.GREEN : !mon ? Ui.DIM : "ended".equals(o.optString("status")) ? Ui.RED : Ui.SONARR);
            c.sub.setText(have + "/" + want + " eps" + (o.optString("network").isEmpty() ? "" : " · " + o.optString("network")));
            setBadge(c.badge, "ended".equals(o.optString("status")) ? "" : "upcoming".equals(o.optString("status")) ? "NEW" : "", Ui.SONARR);
        }
        c.poster.setAlpha(mon ? 1f : 0.55f);
        Net.image(c.poster, svc, Net.posters(svc, o, true), Ui.dp(120));
        return cv;
    }

    static void setBadge(TextView b, String s, int color) {
        b.setText(s);
        b.setBackground(Ui.round(color, 4));
        b.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void open(JSONObject o) {
        if (movies) m.push(new MovieDetailPage(m, o));
        else m.push(new SeriesDetailPage(m, o));
    }

    // ---------------------------------------------------------------- upcoming

    static Date movieDate(JSONObject o) {
        long now = Ui.startOfDay(System.currentTimeMillis());
        Date best = null;
        for (String k : new String[]{"inCinemas", "digitalRelease", "physicalRelease"}) {
            Date d = Ui.iso(o.optString(k, null));
            if (d != null && d.getTime() >= now - 86400000L && (best == null || d.before(best))) best = d;
        }
        return best;
    }

    static String movieDateKind(JSONObject o, Date d) {
        if (d == null) return "";
        for (String k : new String[]{"inCinemas", "digitalRelease", "physicalRelease"}) {
            Date x = Ui.iso(o.optString(k, null));
            if (x != null && x.getTime() == d.getTime())
                return k.equals("inCinemas") ? "In cinemas" : k.equals("digitalRelease") ? "Digital" : "Physical";
        }
        return "";
    }

    Date upcomingDate(JSONObject o) { return movies ? movieDate(o) : Ui.iso(o.optString("airDateUtc", null)); }

    void sortUpcoming(List<JSONObject> l) {
        Collections.sort(l, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                Date x = upcomingDate(a), y = upcomingDate(b);
                if (x == null) return 1;
                if (y == null) return -1;
                return x.compareTo(y);
            }
        });
    }

    View bindUpcoming(JSONObject o, View cv) {
        Ui.Row r = cv instanceof Ui.Row ? (Ui.Row) cv : new Ui.Row(m, true);
        r.bar.setVisibility(View.GONE);
        Date d = upcomingDate(o);
        boolean has = o.optBoolean("hasFile");
        if (movies) {
            r.title.setText(o.optString("title"));
            Ui.set(r.sub, movieDateKind(o, d) + (o.optString("studio").isEmpty() ? "" : " · " + o.optString("studio")));
            Ui.set(r.sub2, o.optInt("year") > 0 ? String.valueOf(o.optInt("year")) : "");
            Net.image(r.thumb, svc, Net.posters(svc, o, true), Ui.dp(46));
        } else {
            JSONObject s = o.optJSONObject("series");
            r.title.setText(s == null ? "" : s.optString("title"));
            Ui.set(r.sub, String.format(Locale.US, "S%02dE%02d · %s", o.optInt("seasonNumber"), o.optInt("episodeNumber"), o.optString("title")));
            Ui.set(r.sub2, s == null ? "" : s.optString("network"));
            if (s != null) Net.image(r.thumb, svc, Net.posters(svc, s, true), Ui.dp(46));
            else r.thumb.setImageDrawable(null);
        }
        Ui.set(r.r1, Ui.day(d));
        r.r1.setTextColor(has ? Ui.GREEN : Ui.TEXT);
        Ui.set(r.r2, has ? "Downloaded" : movies ? "" : Ui.time(d));
        return r;
    }

    // ---------------------------------------------------------------- queue

    View bindQueue(JSONObject o, View cv) {
        Ui.Row r = cv instanceof Ui.Row ? (Ui.Row) cv : new Ui.Row(m, true);
        long size = o.optLong("size"), left = o.optLong("sizeleft");
        float frac = size > 0 ? 1f - left / (float) size : 0;
        String state = o.optString("trackedDownloadState", o.optString("status"));
        String st = o.optString("status");
        boolean warn = "warning".equals(o.optString("trackedDownloadStatus")) || "failed".equals(st);
        r.title.setText(o.optString("title"));
        JSONObject media = o.optJSONObject(movies ? "movie" : "series");
        String what = media == null ? "" : media.optString("title");
        JSONObject ep = o.optJSONObject("episode");
        if (ep != null) what += String.format(Locale.US, " · S%02dE%02d", ep.optInt("seasonNumber"), ep.optInt("episodeNumber"));
        Ui.set(r.sub, what);
        String q = "";
        if (o.optJSONObject("quality") != null && o.optJSONObject("quality").optJSONObject("quality") != null)
            q = o.optJSONObject("quality").optJSONObject("quality").optString("name");
        Ui.set(r.sub2, Ui.size(size) + (q.isEmpty() ? "" : " · " + q) + " · " + o.optString("protocol"));
        r.bar.setVisibility(View.VISIBLE);
        r.bar.set(frac, warn ? Ui.RED : "paused".equals(st) ? Ui.DIM : color());
        Ui.set(r.r1, Math.round(frac * 100) + "%");
        String tl = o.optString("timeleft", "");
        if (tl.startsWith("00:")) tl = tl.substring(3);
        Ui.set(r.r2, state + (tl.isEmpty() || frac >= 1 ? "" : "\n" + tl));
        r.r2.setTextColor(warn ? Ui.RED : Ui.SUB);
        if (media != null) Net.image(r.thumb, svc, Net.posters(svc, media, true), Ui.dp(46));
        else r.thumb.setImageDrawable(null);
        return r;
    }

    void queueActions(final JSONObject o) {
        StringBuilder msg = new StringBuilder(o.optString("title"));
        JSONArray sm = o.optJSONArray("statusMessages");
        if (sm != null) for (int i = 0; i < sm.length(); i++) {
            JSONArray ms = sm.optJSONObject(i) == null ? null : sm.optJSONObject(i).optJSONArray("messages");
            if (ms != null) for (int k = 0; k < ms.length(); k++) msg.append("\n• ").append(ms.optString(k));
        }
        if (o.has("errorMessage")) msg.append("\n• ").append(o.optString("errorMessage"));
        new AlertDialog.Builder(m).setTitle("Queue item").setMessage(msg)
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { removeQueue(o, false); }
                })
                .setNeutralButton("Remove + blocklist", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { removeQueue(o, true); }
                })
                .setNegativeButton("Close", null).show();
    }

    void removeQueue(final JSONObject o, final boolean block) {
        Net.async(m, new Net.Job<Object>() {
            public Object run() throws Exception {
                return Net.json(svc, "DELETE", "/queue/" + o.optInt("id") + "?removeFromClient=true&blocklist=" + block, null, 30000);
            }
        }, new Net.Ok<Object>() {
            public void ok(Object v) { Ui.toast(m, "Removed"); load(true); }
        }, null);
    }
}
