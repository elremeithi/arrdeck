package com.arrdeck;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Interactive search for Radarr / Sonarr: lists releases from indexers, tap to grab. */
final class ReleasesPage extends Page {
    final Svc svc;
    final String path, title;
    final boolean showRejected;
    JAdapter adapter;
    TextView status, sortChip, protoChip;
    List<JSONObject> all = new ArrayList<>();
    int sort = 0; // 0 = Arr ranking, 1 seeders, 2 size, 3 age
    int proto = 0; // 0 all, 1 torrent, 2 usenet
    static final String[] SORTS = {"Best match", "Seeders", "Size", "Newest"};
    static final String[] PROTOS = {"All", "Torrent", "Usenet"};

    ReleasesPage(Main m, Svc svc, String path, String title, boolean showRejected) {
        super(m);
        this.svc = svc;
        this.path = path;
        this.title = title;
        this.showRejected = showRejected;
    }

    @Override View build() {
        LinearLayout root = Ui.col(m);
        root.addView(Ui.header(m, title, svc.color(), true, Ui.iconBtn(m, "↻", new View.OnClickListener() {
            public void onClick(View v) { load(); }
        })));
        LinearLayout chips = Ui.row(m);
        chips.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(8));
        sortChip = Ui.chip(m, "⇅ " + SORTS[0], new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(m).setSingleChoiceItems(SORTS, sort, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { sort = w; sortChip.setText("⇅ " + SORTS[w]); d.dismiss(); apply(); }
                }).show();
            }
        });
        protoChip = Ui.chip(m, PROTOS[0], new View.OnClickListener() {
            public void onClick(View v) { proto = (proto + 1) % 3; protoChip.setText(PROTOS[proto]); apply(); }
        });
        chips.addView(sortChip, Ui.margins(-2, -2, 0, 0, 8, 0));
        chips.addView(protoChip);
        root.addView(chips);

        FrameLayout body = new FrameLayout(m);
        ListView lv = new ListView(m);
        lv.setDivider(null);
        adapter = new JAdapter(new JAdapter.Binder() {
            public View bind(JSONObject o, View cv, ViewGroup p) { return bindRelease(m, o, cv); }
        });
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) { grab(adapter.items.get(pos)); }
        });
        status = m.status("");
        body.addView(lv);
        body.addView(status, new FrameLayout.LayoutParams(-1, -2));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        load();
        return root;
    }

    void load() {
        adapter.set(new ArrayList<JSONObject>());
        status.setVisibility(View.VISIBLE);
        status.setText("Searching indexers…\nthis can take a little while");
        Net.async(m, new Net.Job<JSONArray>() {
            public JSONArray run() throws Exception {
                Object o = Net.json(svc, "GET", path, null, 180000);
                return o instanceof JSONArray ? (JSONArray) o : new JSONArray();
            }
        }, new Net.Ok<JSONArray>() {
            public void ok(JSONArray a) {
                all = LibraryPage.toList(a);
                for (int i = 0; i < all.size(); i++) try { all.get(i).put("_rank", i); } catch (Exception ignored) {}
                apply();
            }
        }, new Net.Ok<Exception>() {
            public void ok(Exception e) { status.setText("Search failed\n" + Net.msg(e)); }
        });
    }

    void apply() {
        List<JSONObject> l = new ArrayList<>();
        for (JSONObject o : all) {
            String p = o.optString("protocol");
            if (proto == 1 && !"torrent".equals(p)) continue;
            if (proto == 2 && !"usenet".equals(p)) continue;
            l.add(o);
        }
        Collections.sort(l, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                // approved releases first, like the Arr UI
                int ra = a.optBoolean("rejected") ? 1 : 0, rb = b.optBoolean("rejected") ? 1 : 0;
                if (sort == 0 && ra != rb) return ra - rb;
                switch (sort) {
                    case 1: return b.optInt("seeders") - a.optInt("seeders");
                    case 2: return Long.compare(b.optLong("size"), a.optLong("size"));
                    case 3: return Double.compare(a.optDouble("ageMinutes", a.optDouble("age", 0) * 1440), b.optDouble("ageMinutes", b.optDouble("age", 0) * 1440));
                    default: return a.optInt("_rank") - b.optInt("_rank");
                }
            }
        });
        adapter.set(l);
        status.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
        status.setText(all.isEmpty() ? "No releases found" : "Nothing matches this filter");
    }

    static String quality(JSONObject o) {
        JSONObject q = o.optJSONObject("quality");
        q = q == null ? null : q.optJSONObject("quality");
        return q == null ? "" : q.optString("name");
    }

    static View bindRelease(Main m, JSONObject o, View cv) {
        Ui.Row r = cv instanceof Ui.Row ? (Ui.Row) cv : new Ui.Row(m, false);
        r.bar.setVisibility(View.GONE);
        boolean rej = o.optBoolean("rejected");
        r.title.setText(o.optString("title"));
        r.title.setTextColor(rej ? Ui.SUB : Ui.TEXT);
        String q = quality(o);
        Ui.set(r.sub, (q.isEmpty() ? "" : q + " · ") + Ui.size(o.optLong("size")) + " · " + o.optString("indexer")
                + " · " + ageText(o));
        StringBuilder rs = new StringBuilder();
        JSONArray rj = o.optJSONArray("rejections");
        if (rej && rj != null) for (int i = 0; i < Math.min(2, rj.length()); i++) {
            Object x = rj.opt(i);
            String t = x instanceof JSONObject ? ((JSONObject) x).optString("reason") : String.valueOf(x);
            rs.append(i > 0 ? " · " : "⚠ ").append(t);
        }
        Ui.set(r.sub2, rs.toString());
        r.sub2.setTextColor(Ui.RED);
        boolean torrent = "torrent".equals(o.optString("protocol"));
        if (torrent) {
            Ui.set(r.r1, String.valueOf(o.optInt("seeders")));
            r.r1.setTextColor(o.optInt("seeders") > 0 ? Ui.GREEN : Ui.RED);
            Ui.set(r.r2, o.optInt("leechers") + " L");
        } else {
            Ui.set(r.r1, "NZB");
            r.r1.setTextColor(Ui.BLUE);
            Ui.set(r.r2, "");
        }
        return r;
    }

    static String ageText(JSONObject o) {
        if (o.has("publishDate")) return Ui.age(Ui.iso(o.optString("publishDate")));
        return o.optInt("age") + "d";
    }

    void grab(final JSONObject o) {
        String msg = o.optString("title") + "\n\n" + Ui.size(o.optLong("size")) + " · " + o.optString("indexer");
        if (o.optBoolean("rejected")) msg += "\n\nThis release was rejected by " + svc.name() + " – grab anyway?";
        new AlertDialog.Builder(m).setTitle("Download release?").setMessage(msg)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Net.async(m, new Net.Job<Object>() {
                            public Object run() throws Exception {
                                JSONObject b = new JSONObject().put("guid", o.optString("guid")).put("indexerId", o.optInt("indexerId"));
                                return Net.json(svc, "POST", "/release", b, 60000);
                            }
                        }, new Net.Ok<Object>() {
                            public void ok(Object v) { Ui.toast(m, "Sent to download client"); }
                        }, null);
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
