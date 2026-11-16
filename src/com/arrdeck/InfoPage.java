package com.arrdeck;

import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * nzb360-style info page for a search result: backdrop, poster, ratings, synopsis, release / air dates,
 * links to IMDb / TMDb / TVDb / trailer, and an Add (or Open in library) button.
 */
final class InfoPage extends Page {
    final AddPage owner;
    final AddPage.Kind kind;
    final JSONObject item;
    LinearLayout body;

    InfoPage(Main m, AddPage owner, AddPage.Kind kind, JSONObject item) {
        super(m);
        this.owner = owner;
        this.kind = kind;
        this.item = item;
    }

    @Override View build() {
        LinearLayout root = Ui.col(m);
        root.addView(Ui.header(m, item.optString("title"), kind.color(), true));
        ScrollView sv = new ScrollView(m);
        body = Ui.col(m);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        render();
        return root;
    }

    @Override void onShow() { if (body != null) render(); } // reflects "added" state when coming back

    void render() {
        body.removeAllViews();
        final boolean movies = kind.movies;
        final Svc svc = kind.svc;

        // backdrop
        String[] fan = Net.covers(svc, item, "fanart", false);
        if (fan.length > 0) {
            ImageView bg = new ImageView(m);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setBackgroundColor(Ui.CARD);
            bg.setAlpha(0.85f);
            body.addView(bg, new LinearLayout.LayoutParams(-1, Ui.dp(190)));
            Net.image(bg, svc, fan, Ui.dp(400));
        }

        body.setClipChildren(false);
        LinearLayout pad = Ui.col(m);
        pad.setClipChildren(false);
        pad.setClipToPadding(false);
        pad.setPadding(Ui.dp(16), Ui.dp(fan.length > 0 ? 0 : 6), Ui.dp(16), Ui.dp(28));
        body.addView(pad);

        // poster + headline facts (poster overlaps the backdrop a little, like nzb360)
        LinearLayout top = Ui.row(m);
        top.setGravity(Gravity.BOTTOM);
        top.setClipChildren(false);
        Ui.Poster p = new Ui.Poster(m);
        p.setElevation(Ui.dp(6));
        LinearLayout.LayoutParams plp = Ui.margins(Ui.dp(112), -2, 0, 0, 14, 0);
        if (fan.length > 0) plp.topMargin = -Ui.dp(70);
        top.addView(p, plp);
        Net.image(p, svc, Net.posters(svc, item, false), Ui.dp(240));
        LinearLayout head = Ui.col(m);
        TextView type = Ui.text(m, movies ? "MOVIE" : "SHOW", 11, kind.color(), true);
        head.addView(type);
        head.addView(Ui.text(m, item.optString("title"), 20, Ui.TEXT, true));
        head.addView(Ui.text(m, meta(), 13, Ui.SUB, false), Ui.margins(-2, -2, 0, 3, 0, 0));
        String genres = genres(item);
        if (!genres.isEmpty()) head.addView(Ui.text(m, genres, 12, kind.color(), true), Ui.margins(-2, -2, 0, 6, 0, 0));
        top.addView(head, Ui.weight(1));
        pad.addView(top, Ui.margins(-1, -2, 0, 12, 0, 0));

        // ratings
        View ratings = ratings();
        if (ratings != null) pad.addView(ratings, Ui.margins(-1, -2, 0, 16, 0, 0));

        // main action
        final boolean have = item.optInt("id") > 0;
        TextView act = have
                ? Ui.button(m, "✔ In library – open", Ui.GREEN, new View.OnClickListener() {
                    public void onClick(View v) { m.push(movies ? new MovieDetailPage(m, item) : new SeriesDetailPage(m, item)); }
                })
                : Ui.button(m, "＋ Add to " + svc.name(), kind.color(), new View.OnClickListener() {
                    public void onClick(View v) {
                        owner.requestAdd(item, kind, new Runnable() {
                            public void run() { if (visible()) render(); }
                        });
                    }
                });
        pad.addView(act, Ui.margins(-1, -2, 0, 16, 0, 0));

        // synopsis
        String ov = item.optString("overview");
        if (!ov.isEmpty()) {
            pad.addView(section("Synopsis"));
            TextView t = Ui.text(m, ov, 14, Ui.TEXT, false);
            t.setLineSpacing(Ui.dp(3), 1f);
            pad.addView(t);
        }

        // dates
        pad.addView(section(movies ? "Release dates" : "Airing"));
        LinearLayout dates = Ui.col(m);
        dates.setBackground(Ui.round(Ui.CARD, 12));
        dates.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
        if (movies) {
            dateRow(dates, "🎬  In cinemas", item.optString("inCinemas", null));
            dateRow(dates, "💻  Digital release", item.optString("digitalRelease", null));
            dateRow(dates, "📀  Physical release", item.optString("physicalRelease", null));
        } else {
            dateRow(dates, "📺  First aired", item.optString("firstAired", null));
            if (item.has("nextAiring")) dateRow(dates, "⏭  Next episode", item.optString("nextAiring"));
            if (item.has("previousAiring")) dateRow(dates, "⏮  Last aired", item.optString("previousAiring"));
            String air = item.optString("airTime");
            if (!air.isEmpty()) factRow(dates, "⏰  Air time", air + (item.optString("network").isEmpty() ? "" : " on " + item.optString("network")));
            factRow(dates, "ℹ  Status", cap(item.optString("status")));
            int seasons = item.optInt("seasonCount", item.optJSONArray("seasons") == null ? 0 : item.optJSONArray("seasons").length());
            if (seasons > 0) factRow(dates, "🗂  Seasons", String.valueOf(seasons));
        }
        pad.addView(dates);

        // details
        LinearLayout facts = Ui.col(m);
        facts.setBackground(Ui.round(Ui.CARD, 12));
        facts.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
        if (movies) {
            factRow(facts, "Studio", item.optString("studio"));
            factRow(facts, "Status", cap(item.optString("status")));
            factRow(facts, "Original title", item.optString("originalTitle").equals(item.optString("title")) ? "" : item.optString("originalTitle"));
            JSONObject lang = item.optJSONObject("originalLanguage");
            factRow(facts, "Language", lang == null ? "" : lang.optString("name"));
        } else {
            factRow(facts, "Network", item.optString("network"));
            factRow(facts, "Type", cap(item.optString("seriesType")));
            JSONObject lang = item.optJSONObject("originalLanguage");
            factRow(facts, "Language", lang == null ? "" : lang.optString("name"));
        }
        factRow(facts, "Certification", item.optString("certification"));
        if (facts.getChildCount() > 0) {
            pad.addView(section("Details"));
            pad.addView(facts);
        }

        // links
        View links = links(m, item, movies);
        if (links != null) {
            pad.addView(section("Links"));
            pad.addView(links);
        }
    }

    String meta() {
        StringBuilder s = new StringBuilder();
        if (item.optInt("year") > 0) s.append(item.optInt("year"));
        int rt = item.optInt("runtime");
        if (rt > 0) s.append(s.length() > 0 ? " · " : "").append(kind.movies ? (rt / 60) + "h " + (rt % 60) + "m" : rt + " min/ep");
        String cert = item.optString("certification");
        if (!cert.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(cert);
        if (!kind.movies && !item.optString("network").isEmpty()) s.append(s.length() > 0 ? " · " : "").append(item.optString("network"));
        return s.toString();
    }

    static String genres(JSONObject o) {
        JSONArray g = o.optJSONArray("genres");
        if (g == null) return "";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(4, g.length()); i++) s.append(i > 0 ? " · " : "").append(g.optString(i));
        return s.toString();
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    TextView section(String s) {
        TextView t = Ui.text(m, s, 16, Ui.TEXT, true);
        t.setLayoutParams(Ui.margins(-2, -2, 0, 22, 0, 8));
        return t;
    }

    /** IMDb / TMDb / Rotten Tomatoes / Metacritic tiles (Radarr), or the single TVDb rating (Sonarr). */
    View ratings() {
        JSONObject r = item.optJSONObject("ratings");
        if (r == null) return null;
        LinearLayout row = Ui.row(m);
        if (kind.movies) {
            tile(row, "IMDb", r.optJSONObject("imdb"), 0xFFF5C518, false);
            tile(row, "TMDb", r.optJSONObject("tmdb"), 0xFF01B4E4, false);
            tile(row, "Rotten Tomatoes", r.optJSONObject("rottenTomatoes"), 0xFFFA320A, true);
            tile(row, "Metacritic", r.optJSONObject("metacritic"), 0xFF66CC33, true);
        } else if (r.optDouble("value", 0) > 0) {
            tile(row, "Rating", r, Ui.SONARR, false);
        }
        if (row.getChildCount() == 0) return null;
        HorizontalScrollView hs = new HorizontalScrollView(m);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    void tile(LinearLayout row, String name, JSONObject r, int color, boolean percent) {
        if (r == null || r.optDouble("value", 0) <= 0) return;
        double v = r.optDouble("value");
        LinearLayout t = Ui.col(m);
        t.setBackground(Ui.round(Ui.CARD, 10));
        t.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        t.addView(Ui.text(m, percent ? Math.round(v) + "%" : String.format(Locale.US, "%.1f", v), 18, color, true));
        int votes = r.optInt("votes");
        t.addView(Ui.text(m, name + (votes > 0 ? " · " + compact(votes) : ""), 11, Ui.SUB, false));
        row.addView(t, Ui.margins(-2, -2, 0, 0, 8, 0));
    }

    static String compact(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1e6);
        if (n >= 1000) return String.format(Locale.US, "%.0fK", n / 1e3);
        return String.valueOf(n);
    }

    void dateRow(LinearLayout box, String label, String iso) {
        Date d = Ui.iso(iso);
        if (d == null) return;
        String txt = new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(d);
        long days = Math.round((Ui.startOfDay(d.getTime()) - Ui.startOfDay(System.currentTimeMillis())) / 86400000.0);
        String rel = days == 0 ? "today" : days == 1 ? "tomorrow" : days > 1 && days < 365 ? "in " + days + " days" : "";
        LinearLayout r = Ui.row(m);
        r.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        r.addView(Ui.text(m, label, 14, Ui.SUB, false), Ui.weight(1));
        LinearLayout right = Ui.col(m);
        right.setGravity(Gravity.END);
        TextView main = Ui.text(m, txt, 14, days >= 0 ? Ui.TEXT : Ui.SUB, true);
        main.setGravity(Gravity.END);
        right.addView(main);
        if (!rel.isEmpty()) {
            TextView rt = Ui.text(m, rel, 11, kind.color(), true);
            rt.setGravity(Gravity.END);
            right.addView(rt);
        }
        r.addView(right);
        box.addView(r);
    }

    void factRow(LinearLayout box, String label, String value) {
        if (value == null || value.isEmpty()) return;
        LinearLayout r = Ui.row(m);
        r.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        r.addView(Ui.text(m, label, 14, Ui.SUB, false), Ui.weight(1));
        TextView v = Ui.text(m, value, 14, Ui.TEXT, true);
        v.setGravity(Gravity.END);
        r.addView(v, Ui.weight(1.3f));
        box.addView(r);
    }

    /** IMDb / TMDb / TVDb / trailer link buttons. Used by this page and the library detail pages. */
    static View links(final Main m, JSONObject o, boolean movies) {
        LinearLayout row = Ui.row(m);
        String imdb = o.optString("imdbId");
        if (imdb.startsWith("tt")) link(m, row, "IMDb", 0xFFF5C518, "https://www.imdb.com/title/" + imdb + "/");
        if (movies && o.optInt("tmdbId") > 0) link(m, row, "TMDb", 0xFF01B4E4, "https://www.themoviedb.org/movie/" + o.optInt("tmdbId"));
        if (!movies && o.optInt("tvdbId") > 0) link(m, row, "TVDb", 0xFF6CD491, "https://thetvdb.com/dereferrer/series/" + o.optInt("tvdbId"));
        if (!movies && o.optInt("tmdbId") > 0) link(m, row, "TMDb", 0xFF01B4E4, "https://www.themoviedb.org/tv/" + o.optInt("tmdbId"));
        String yt = o.optString("youTubeTrailerId");
        if (!yt.isEmpty()) link(m, row, "▶ Trailer", 0xFFFF4444, "https://www.youtube.com/watch?v=" + yt);
        else link(m, row, "▶ Trailer", 0xFFFF4444, "https://www.youtube.com/results?search_query="
                + Net.enc(o.optString("title") + " " + (o.optInt("year") > 0 ? o.optInt("year") + " " : "") + "trailer"));
        if (row.getChildCount() == 0) return null;
        HorizontalScrollView hs = new HorizontalScrollView(m);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    static void link(final Main m, LinearLayout row, String label, int color, final String url) {
        TextView b = Ui.outline(m, label, color, new View.OnClickListener() {
            public void onClick(View v) {
                try { m.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { Ui.toast(m, "No browser available"); }
            }
        });
        row.addView(b, Ui.margins(-2, -2, 0, 0, 8, 0));
    }
}
