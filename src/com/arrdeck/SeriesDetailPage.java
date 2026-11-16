package com.arrdeck;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Series details with seasons and episodes; search / monitor at series, season, episode level. */
final class SeriesDetailPage extends Page {
    JSONObject series;
    final Svc svc;
    LinearLayout body, seasonsBox;
    JSONArray episodes;
    final Set<Integer> expanded = new HashSet<>();
    TextView monBtn;

    SeriesDetailPage(Main m, JSONObject s) {
        super(m);
        series = s;
        svc = Svc.load(m, Svc.SONARR);
    }

    int id() { return series.optInt("id"); }

    @Override View build() {
        LinearLayout root = Ui.col(m);
        root.addView(Ui.header(m, series.optString("title"), Ui.SONARR, true));
        ScrollView sv = new ScrollView(m);
        body = Ui.col(m);
        body.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(24));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        render();
        refresh();
        return root;
    }

    void refresh() {
        Net.async(m, new Net.Job<Object[]>() {
            public Object[] run() throws Exception {
                return new Object[]{Net.obj(svc, "GET", "/series/" + id(), null), Net.arr(svc, "/episode?includeEpisodeFile=true&seriesId=" + id())};
            }
        }, new Net.Ok<Object[]>() {
            public void ok(Object[] r) {
                JSONObject s = (JSONObject) r[0];
                if (s.has("id")) series = s;
                episodes = (JSONArray) r[1];
                render();
            }
        }, null);
    }

    void render() {
        body.removeAllViews();
        JSONObject st = series.optJSONObject("statistics");
        int have = st == null ? 0 : st.optInt("episodeFileCount"), want = st == null ? 0 : st.optInt("episodeCount");
        int rt = series.optInt("runtime");
        String meta = series.optInt("year") + (series.optString("network").isEmpty() ? "" : " · " + series.optString("network"))
                + (rt > 0 ? " · " + rt + "m" : "");
        String ex = (have >= want && want > 0 ? "✔ " : "") + have + " / " + want + " episodes"
                + "\n" + Ui.size(LibraryPage.sizeOf(series))
                + "\nStatus: " + series.optString("status")
                + (series.has("nextAiring") ? "\nNext: " + Ui.day(Ui.iso(series.optString("nextAiring"))) : "");
        body.addView(MovieDetailPage.hero(m, svc, series, meta, ex, Ui.SONARR));

        LinearLayout a = Ui.row(m);
        a.addView(Ui.button(m, "Search missing", Ui.SONARR, new View.OnClickListener() {
            public void onClick(View v) {
                command(new String[]{"name", "SeriesSearch"}, "seriesId", id(), "Series search started");
            }
        }), Ui.weight(1));
        a.addView(new View(m), Ui.lp(Ui.dp(8), 1));
        monBtn = Ui.outline(m, "", Ui.TEXT, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    series.put("monitored", !series.optBoolean("monitored"));
                    saveSeries();
                } catch (Exception ignored) {}
            }
        });
        setMon(monBtn, series.optBoolean("monitored"));
        a.addView(monBtn, Ui.weight(1));
        body.addView(a, Ui.margins(-1, -2, 0, 18, 0, 0));

        LinearLayout a2 = Ui.row(m);
        a2.addView(Ui.outline(m, "Refresh & scan", Ui.SONARR, new View.OnClickListener() {
            public void onClick(View v) { command(new String[]{"name", "RefreshSeries"}, "seriesId", id(), "Refreshing"); }
        }), Ui.weight(1));
        a2.addView(new View(m), Ui.lp(Ui.dp(8), 1));
        a2.addView(Ui.outline(m, "Delete", Ui.RED, new View.OnClickListener() {
            public void onClick(View v) { delete(); }
        }), Ui.weight(1));
        body.addView(a2, Ui.margins(-1, -2, 0, 8, 0, 0));

        String ov = series.optString("overview");
        if (!ov.isEmpty()) body.addView(MovieDetailPage.overview(m, ov), Ui.margins(-1, -2, 0, 18, 0, 0));
        View links = InfoPage.links(m, series, false);
        if (links != null) body.addView(links, Ui.margins(-1, -2, 0, 14, 0, 0));

        body.addView(Ui.text(m, "Seasons", 17, Ui.TEXT, true), Ui.margins(-2, -2, 0, 22, 0, 6));
        seasonsBox = Ui.col(m);
        body.addView(seasonsBox);
        renderSeasons();
    }

    static void setMon(TextView t, boolean mon) {
        t.setText(mon ? "◉ Monitored" : "○ Unmonitored");
        t.setTextColor(mon ? Ui.GREEN : Ui.SUB);
    }

    void renderSeasons() {
        seasonsBox.removeAllViews();
        JSONArray seasons = series.optJSONArray("seasons");
        if (seasons == null) return;
        List<JSONObject> list = LibraryPage.toList(seasons);
        Collections.sort(list, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) { return b.optInt("seasonNumber") - a.optInt("seasonNumber"); }
        });
        for (final JSONObject s : list) {
            final int n = s.optInt("seasonNumber");
            JSONObject st = s.optJSONObject("statistics");
            int have = st == null ? 0 : st.optInt("episodeFileCount"), want = st == null ? 0 : st.optInt("episodeCount");
            int total = st == null ? 0 : st.optInt("totalEpisodeCount");
            LinearLayout card = Ui.col(m);
            card.setBackground(Ui.round(Ui.CARD, 12));
            LinearLayout head = Ui.row(m);
            head.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(6), Ui.dp(10));
            Ui.clickable(head, null);
            LinearLayout txt = Ui.col(m);
            txt.addView(Ui.text(m, (expanded.contains(n) ? "▾ " : "▸ ") + (n == 0 ? "Specials" : "Season " + n), 15, Ui.TEXT, true));
            txt.addView(Ui.text(m, have + "/" + want + " episodes" + (total > want ? " (" + total + " total)" : "")
                    + (st != null && st.optLong("sizeOnDisk") > 0 ? " · " + Ui.size(st.optLong("sizeOnDisk")) : ""), 12, Ui.SUB, false));
            Ui.Bar bar = new Ui.Bar(m);
            bar.set(want == 0 ? 0 : have / (float) want, have >= want && want > 0 ? Ui.GREEN : s.optBoolean("monitored") ? Ui.SONARR : Ui.DIM);
            txt.addView(bar, Ui.margins(-1, Ui.dp(4), 0, 6, 0, 0));
            head.addView(txt, Ui.weight(1));
            final boolean mon = s.optBoolean("monitored");
            TextView monT = Ui.iconBtn(m, mon ? "◉" : "○", new View.OnClickListener() {
                public void onClick(View v) {
                    try { s.put("monitored", !mon); saveSeries(); } catch (Exception ignored) {}
                }
            });
            monT.setTextColor(mon ? Ui.GREEN : Ui.SUB);
            head.addView(monT);
            head.addView(Ui.iconBtn(m, "⋮", new View.OnClickListener() {
                public void onClick(View v) { seasonMenu(n); }
            }));
            head.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!expanded.remove(n)) expanded.add(n);
                    renderSeasons();
                }
            });
            card.addView(head);
            if (expanded.contains(n)) addEpisodes(card, n);
            seasonsBox.addView(card, Ui.margins(-1, -2, 0, 0, 0, 8));
        }
    }

    void addEpisodes(LinearLayout card, int season) {
        if (episodes == null) {
            card.addView(Ui.text(m, "  Loading episodes…", 13, Ui.SUB, false), Ui.margins(-2, -2, 14, 0, 0, 10));
            return;
        }
        List<JSONObject> eps = new ArrayList<>();
        for (JSONObject e : LibraryPage.toList(episodes)) if (e.optInt("seasonNumber") == season) eps.add(e);
        Collections.sort(eps, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) { return b.optInt("episodeNumber") - a.optInt("episodeNumber"); }
        });
        long now = System.currentTimeMillis();
        for (final JSONObject e : eps) {
            LinearLayout r = Ui.row(m);
            r.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
            Ui.clickable(r, null);
            TextView num = Ui.text(m, String.valueOf(e.optInt("episodeNumber")), 13, Ui.SUB, true);
            num.setMinWidth(Ui.dp(28));
            r.addView(num);
            LinearLayout c = Ui.col(m);
            TextView t = Ui.text(m, e.optString("title", "TBA"), 14, e.optBoolean("monitored") ? Ui.TEXT : Ui.SUB, false);
            t.setSingleLine();
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.addView(t);
            Date air = Ui.iso(e.optString("airDateUtc", null));
            c.addView(Ui.text(m, air == null ? "TBA" : Ui.day(air), 12, Ui.DIM, false));
            r.addView(c, Ui.weight(1));
            boolean has = e.optBoolean("hasFile");
            boolean aired = air != null && air.getTime() < now;
            String mark;
            int col;
            if (has) {
                JSONObject f = e.optJSONObject("episodeFile");
                JSONObject q = f == null || f.optJSONObject("quality") == null ? null : f.optJSONObject("quality").optJSONObject("quality");
                mark = q == null ? "\u2714" : q.optString("name", "\u2714");
                col = Ui.GREEN;
            } else if (!aired) { mark = "Unaired"; col = Ui.BLUE; }
            else if (e.optBoolean("monitored")) { mark = "Missing"; col = Ui.RED; }
            else { mark = "—"; col = Ui.DIM; }
            TextView mk = Ui.text(m, mark, 12, col, true);
            mk.setGravity(Gravity.END);
            r.addView(mk);
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { episodeMenu(e); }
            });
            card.addView(r);
        }
    }

    void seasonMenu(final int n) {
        String[] opts = {"Search season", "Interactive search", "Monitor all episodes", "Unmonitor all episodes"};
        new AlertDialog.Builder(m).setTitle(n == 0 ? "Specials" : "Season " + n).setItems(opts, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    Net.async(m, new Net.Job<Object>() {
                        public Object run() throws Exception {
                            return Net.json(svc, "POST", "/command", new JSONObject().put("name", "SeasonSearch")
                                    .put("seriesId", id()).put("seasonNumber", n), 30000);
                        }
                    }, toast("Season search started"), null);
                } else if (w == 1) {
                    m.push(new ReleasesPage(m, svc, "/release?seriesId=" + id() + "&seasonNumber=" + n,
                            series.optString("title") + " S" + String.format(Locale.US, "%02d", n), true));
                } else monitorEpisodes(n, w == 2);
            }
        }).show();
    }

    void monitorEpisodes(int season, final boolean on) {
        if (episodes == null) return;
        final JSONArray ids = new JSONArray();
        for (JSONObject e : LibraryPage.toList(episodes)) if (e.optInt("seasonNumber") == season) ids.put(e.optInt("id"));
        Net.async(m, new Net.Job<Object>() {
            public Object run() throws Exception {
                return Net.json(svc, "PUT", "/episode/monitor", new JSONObject().put("episodeIds", ids).put("monitored", on), 30000);
            }
        }, new Net.Ok<Object>() {
            public void ok(Object v) { refresh(); }
        }, null);
    }

    void episodeMenu(final JSONObject e) {
        final boolean mon = e.optBoolean("monitored");
        String title = String.format(Locale.US, "S%02dE%02d · %s", e.optInt("seasonNumber"), e.optInt("episodeNumber"), e.optString("title"));
        String[] opts = {"Automatic search", "Interactive search", mon ? "Unmonitor" : "Monitor"};
        AlertDialog.Builder b = new AlertDialog.Builder(m).setTitle(title);
        b.setItems(opts, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                final int eid = e.optInt("id");
                if (w == 0) {
                    Net.async(m, new Net.Job<Object>() {
                        public Object run() throws Exception {
                            return Net.json(svc, "POST", "/command", new JSONObject().put("name", "EpisodeSearch")
                                    .put("episodeIds", new JSONArray().put(eid)), 30000);
                        }
                    }, toast("Episode search started"), null);
                } else if (w == 1) {
                    m.push(new ReleasesPage(m, svc, "/release?episodeId=" + eid, series.optString("title") + String.format(Locale.US, " S%02dE%02d",
                            e.optInt("seasonNumber"), e.optInt("episodeNumber")), true));
                } else {
                    Net.async(m, new Net.Job<Object>() {
                        public Object run() throws Exception {
                            return Net.json(svc, "PUT", "/episode/monitor", new JSONObject()
                                    .put("episodeIds", new JSONArray().put(eid)).put("monitored", !mon), 30000);
                        }
                    }, new Net.Ok<Object>() {
                        public void ok(Object v) { refresh(); }
                    }, null);
                }
            }
        }).show();
    }

    Net.Ok<Object> toast(final String s) {
        return new Net.Ok<Object>() {
            public void ok(Object v) { Ui.toast(m, s); }
        };
    }

    void command(final String[] nameKv, final String key, final int val, String done) {
        Net.async(m, new Net.Job<Object>() {
            public Object run() throws Exception {
                return Net.json(svc, "POST", "/command", new JSONObject().put(nameKv[0], nameKv[1]).put(key, val), 30000);
            }
        }, toast(done), null);
    }

    void saveSeries() {
        renderSeasons();
        setMon(monBtn, series.optBoolean("monitored"));
        final JSONObject s = series;
        Net.async(m, new Net.Job<JSONObject>() {
            public JSONObject run() throws Exception { return Net.obj(svc, "PUT", "/series/" + id(), s); }
        }, new Net.Ok<JSONObject>() {
            public void ok(JSONObject o) {
                if (o.has("id")) series = o;
                m.dirty(false);
                refresh();
            }
        }, new Net.Ok<Exception>() {
            public void ok(Exception e) { Ui.toast(m, Net.msg(e)); refresh(); }
        });
    }

    void delete() {
        final CheckBox files = new CheckBox(m);
        files.setText("Also delete files from disk");
        files.setTextColor(Ui.TEXT);
        LinearLayout l = Ui.col(m);
        l.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        l.addView(files);
        new AlertDialog.Builder(m).setTitle("Delete " + series.optString("title") + "?").setView(l)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Net.async(m, new Net.Job<Object>() {
                            public Object run() throws Exception {
                                return Net.json(svc, "DELETE", "/series/" + id() + "?deleteFiles=" + files.isChecked(), null, 30000);
                            }
                        }, new Net.Ok<Object>() {
                            public void ok(Object v) {
                                Ui.toast(m, "Deleted");
                                m.dirty(false);
                                if (visible()) m.onBackPressed();
                            }
                        }, null);
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
