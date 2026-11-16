package com.arrdeck;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Movie details + actions (automatic / interactive search, monitor, delete). */
final class MovieDetailPage extends Page {
    JSONObject movie;
    final Svc svc;
    LinearLayout body;
    TextView monBtn;

    MovieDetailPage(Main m, JSONObject movie) {
        super(m);
        this.movie = movie;
        svc = Svc.load(m, Svc.RADARR);
    }

    @Override View build() {
        LinearLayout root = Ui.col(m);
        root.addView(Ui.header(m, movie.optString("title"), Ui.RADARR, true));
        ScrollView sv = new ScrollView(m);
        body = Ui.col(m);
        body.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(24));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        render();
        if (movie.optInt("id") > 0) refresh();
        return root;
    }

    void refresh() {
        Net.async(m, new Net.Job<JSONObject>() {
            public JSONObject run() throws Exception { return Net.obj(svc, "GET", "/movie/" + movie.optInt("id"), null); }
        }, new Net.Ok<JSONObject>() {
            public void ok(JSONObject o) { if (o.has("id")) { movie = o; render(); } }
        }, new Net.Ok<Exception>() { public void ok(Exception e) {} });
    }

    /** Poster + text block used by both detail pages. */
    static LinearLayout hero(Main m, Svc svc, JSONObject o, String meta, String extra, int color) {
        LinearLayout top = Ui.row(m);
        top.setGravity(android.view.Gravity.TOP);
        Ui.Poster p = new Ui.Poster(m);
        top.addView(p, Ui.margins(Ui.dp(118), -2, 0, 0, 14, 0));
        Net.image(p, svc, Net.posters(svc, o, false), Ui.dp(240));
        LinearLayout info = Ui.col(m);
        TextView t = Ui.text(m, o.optString("title"), 20, Ui.TEXT, true);
        info.addView(t);
        info.addView(Ui.text(m, meta, 13, Ui.SUB, false), Ui.margins(-2, -2, 0, 4, 0, 0));
        TextView ex = Ui.text(m, extra, 13, Ui.TEXT, false);
        ex.setLineSpacing(Ui.dp(3), 1f);
        info.addView(ex, Ui.margins(-2, -2, 0, 10, 0, 0));
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            StringBuilder g = new StringBuilder();
            for (int i = 0; i < Math.min(4, genres.length()); i++) g.append(i > 0 ? " · " : "").append(genres.optString(i));
            info.addView(Ui.text(m, g, 12, color, true), Ui.margins(-2, -2, 0, 10, 0, 0));
        }
        top.addView(info, Ui.weight(1));
        return top;
    }

    static TextView overview(Main m, String s) {
        final TextView t = Ui.text(m, s, 14, Ui.SUB, false);
        t.setLineSpacing(Ui.dp(3), 1f);
        t.setMaxLines(5);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { t.setMaxLines(t.getMaxLines() == 5 ? 100 : 5); }
        });
        return t;
    }

    void render() {
        body.removeAllViews();
        int rt = movie.optInt("runtime");
        String meta = movie.optInt("year") + (rt > 0 ? " · " + (rt / 60) + "h " + (rt % 60) + "m" : "")
                + (movie.optString("certification").isEmpty() ? "" : " · " + movie.optString("certification"));
        StringBuilder ex = new StringBuilder();
        boolean has = movie.optBoolean("hasFile");
        JSONObject mf = movie.optJSONObject("movieFile");
        if (has && mf != null) {
            JSONObject q = mf.optJSONObject("quality");
            ex.append("✔ ").append(q != null && q.optJSONObject("quality") != null ? q.optJSONObject("quality").optString("name") : "Downloaded")
                    .append(" · ").append(Ui.size(mf.optLong("size", movie.optLong("sizeOnDisk"))));
        } else if (has) ex.append("✔ Downloaded");
        else ex.append(movie.optBoolean("isAvailable", true) ? "✖ Missing" : "⏳ Not yet available");
        ex.append("\nStatus: ").append(movie.optString("status"));
        String[][] dates = {{"inCinemas", "Cinemas"}, {"digitalRelease", "Digital"}, {"physicalRelease", "Physical"}};
        for (String[] d : dates) {
            java.util.Date dt = Ui.iso(movie.optString(d[0], null));
            if (dt != null) ex.append("\n").append(d[1]).append(": ").append(Ui.day(dt));
        }
        JSONObject ratings = movie.optJSONObject("ratings");
        if (ratings != null) {
            JSONObject imdb = ratings.optJSONObject("imdb"), tmdb = ratings.optJSONObject("tmdb");
            StringBuilder r = new StringBuilder();
            if (imdb != null && imdb.optDouble("value", 0) > 0) r.append("IMDb ").append(String.format(Locale.US, "%.1f", imdb.optDouble("value")));
            if (tmdb != null && tmdb.optDouble("value", 0) > 0) r.append(r.length() > 0 ? "  " : "").append("TMDb ").append(String.format(Locale.US, "%.1f", tmdb.optDouble("value")));
            if (r.length() > 0) ex.append("\n★ ").append(r);
        }
        body.addView(hero(m, svc, movie, meta, ex.toString(), Ui.RADARR));

        LinearLayout actions = Ui.row(m);
        actions.addView(Ui.button(m, "Search", Ui.RADARR, new View.OnClickListener() {
            public void onClick(View v) { autoSearch(); }
        }), Ui.weight(1));
        actions.addView(new View(m), Ui.lp(Ui.dp(8), 1));
        actions.addView(Ui.outline(m, "Interactive", Ui.RADARR, new View.OnClickListener() {
            public void onClick(View v) {
                m.push(new ReleasesPage(m, svc, "/release?movieId=" + movie.optInt("id"), movie.optString("title"), true));
            }
        }), Ui.weight(1));
        body.addView(actions, Ui.margins(-1, -2, 0, 18, 0, 0));

        LinearLayout a2 = Ui.row(m);
        monBtn = Ui.outline(m, "", Ui.TEXT, new View.OnClickListener() {
            public void onClick(View v) { toggleMonitor(); }
        });
        setMonText();
        a2.addView(monBtn, Ui.weight(1));
        a2.addView(new View(m), Ui.lp(Ui.dp(8), 1));
        a2.addView(Ui.outline(m, "Delete", Ui.RED, new View.OnClickListener() {
            public void onClick(View v) { delete(); }
        }), Ui.weight(1));
        body.addView(a2, Ui.margins(-1, -2, 0, 8, 0, 0));

        String ov = movie.optString("overview");
        if (!ov.isEmpty()) body.addView(overview(m, ov), Ui.margins(-1, -2, 0, 18, 0, 0));
        View links = InfoPage.links(m, movie, true);
        if (links != null) body.addView(links, Ui.margins(-1, -2, 0, 14, 0, 0));
        String path = movie.optString("path");
        if (!path.isEmpty()) body.addView(Ui.text(m, path, 12, Ui.DIM, false), Ui.margins(-1, -2, 0, 14, 0, 0));
    }

    void setMonText() {
        boolean mon = movie.optBoolean("monitored");
        monBtn.setText(mon ? "◉ Monitored" : "○ Unmonitored");
        monBtn.setTextColor(mon ? Ui.GREEN : Ui.SUB);
    }

    void autoSearch() {
        Net.async(m, new Net.Job<Object>() {
            public Object run() throws Exception {
                JSONObject c = new JSONObject().put("name", "MoviesSearch").put("movieIds", new JSONArray().put(movie.optInt("id")));
                return Net.json(svc, "POST", "/command", c, 30000);
            }
        }, new Net.Ok<Object>() {
            public void ok(Object v) { Ui.toast(m, "Search started"); }
        }, null);
    }

    void toggleMonitor() {
        Net.async(m, new Net.Job<JSONObject>() {
            public JSONObject run() throws Exception {
                JSONObject cur = Net.obj(svc, "GET", "/movie/" + movie.optInt("id"), null);
                cur.put("monitored", !cur.optBoolean("monitored"));
                return Net.obj(svc, "PUT", "/movie/" + movie.optInt("id"), cur);
            }
        }, new Net.Ok<JSONObject>() {
            public void ok(JSONObject o) {
                if (o.has("id")) movie = o;
                else try { movie.put("monitored", !movie.optBoolean("monitored")); } catch (Exception ignored) {}
                setMonText();
                m.dirty(true);
            }
        }, null);
    }

    void delete() {
        final CheckBox files = new CheckBox(m);
        files.setText("Also delete files from disk");
        files.setTextColor(Ui.TEXT);
        final CheckBox excl = new CheckBox(m);
        excl.setText("Add import list exclusion");
        excl.setTextColor(Ui.TEXT);
        LinearLayout l = Ui.col(m);
        l.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        l.addView(files);
        l.addView(excl);
        new AlertDialog.Builder(m).setTitle("Delete " + movie.optString("title") + "?").setView(l)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String q = "?deleteFiles=" + files.isChecked() + "&addImportExclusion=" + excl.isChecked();
                        Net.async(m, new Net.Job<Object>() {
                            public Object run() throws Exception { return Net.json(svc, "DELETE", "/movie/" + movie.optInt("id") + q, null, 30000); }
                        }, new Net.Ok<Object>() {
                            public void ok(Object v) {
                                Ui.toast(m, "Deleted");
                                m.dirty(true);
                                if (visible()) m.onBackPressed();
                            }
                        }, null);
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
