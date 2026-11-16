package com.arrdeck;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Prowlarr indexer search (torrents by default) with grab / magnet / copy actions. */
final class SearchPage extends Page {
    Svc svc;
    EditText q;
    JAdapter adapter;
    TextView status, catChip, protoChip, sortChip, idxChip;
    List<JSONObject> results = new ArrayList<>();
    JSONArray indexers;
    final Set<Integer> chosen = new HashSet<>();
    int cat = 0, sort = 0, proto = 1, seq;
    static final String[] CATS = {"All", "Movies", "TV", "Music", "Books", "PC/Software", "Anime"};
    static final int[][] CAT_IDS = {{}, {2000}, {5000}, {3000}, {7000}, {4000}, {5070}};
    static final String[] SORTS = {"Seeders", "Size", "Newest", "Grabs"};
    static final String[] PROTOS = {"Any", "Torrents", "Usenet"};

    /** 0 = Movies & Shows (Radarr + Sonarr lookup), 1 = Torrents (Prowlarr). */
    int mode = 0;
    AddPage media;
    View mediaView, torrentView;
    final TextView[] modeChips = new TextView[2];

    SearchPage(Main m) { super(m); }

    @Override View build() {
        LinearLayout root = Ui.col(m);
        root.addView(Ui.header(m, "Search", Ui.ACCENT, false));
        LinearLayout chips = Ui.row(m);
        chips.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(8));
        String[] names = {"Movies & Shows", "Torrents"};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            modeChips[i] = Ui.chip(m, names[i], new View.OnClickListener() {
                public void onClick(View v) { setMode(idx); }
            });
            chips.addView(modeChips[i], Ui.margins(-2, -2, 0, 0, 8, 0));
        }
        root.addView(chips);
        FrameLayout holder = new FrameLayout(m);
        media = new AddPage(m);
        mediaView = media.view();
        torrentView = buildTorrents();
        holder.addView(mediaView);
        holder.addView(torrentView);
        root.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));
        setModeUi();
        return root;
    }

    /** Focuses the active search box and pops up the keyboard. */
    void focusSearch() {
        view();
        final EditText e = mode == 0 ? media.q : q;
        e.requestFocus();
        e.setSelection(e.getText().length());
        e.postDelayed(new Runnable() {
            public void run() {
                ((android.view.inputmethod.InputMethodManager) m.getSystemService(Main.INPUT_METHOD_SERVICE))
                        .showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120);
    }

    void setMode(int i) {
        if (mode != i) {
            // carry the query across, so you can type once and flip between the two
            String text = (mode == 0 ? media.q : q).getText().toString();
            (i == 0 ? media.q : q).setText(text);
        }
        mode = i;
        setModeUi();
        onShow();
        focusSearch();
    }

    void setModeUi() {
        Ui.setChip(modeChips[0], mode == 0, Ui.ACCENT);
        Ui.setChip(modeChips[1], mode == 1, Ui.PROWLARR);
        mediaView.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        torrentView.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
    }

    View buildTorrents() {
        svc = Svc.load(m, Svc.PROWLARR);
        LinearLayout root = Ui.col(m);
        LinearLayout bar = Ui.row(m);
        bar.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(8));
        q = Ui.input(m, "Search all indexers…", "", false);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int a, KeyEvent e) { search(); return true; }
        });
        bar.addView(q, Ui.weight(1));
        bar.addView(Ui.button(m, "Go", Ui.PROWLARR, new View.OnClickListener() {
            public void onClick(View v) { search(); }
        }), Ui.margins(-2, -2, 8, 0, 0, 0));
        root.addView(bar);

        HorizontalScrollView hs = new HorizontalScrollView(m);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(m);
        chips.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(8));
        catChip = Ui.chip(m, CATS[0], new View.OnClickListener() {
            public void onClick(View v) {
                choose("Category", CATS, cat, new Net.Ok<Integer>() {
                    public void ok(Integer w) { cat = w; refreshChips(); if (!results.isEmpty()) search(); }
                });
            }
        });
        protoChip = Ui.chip(m, PROTOS[proto], new View.OnClickListener() {
            public void onClick(View v) { proto = (proto + 1) % 3; refreshChips(); apply(); }
        });
        sortChip = Ui.chip(m, "", new View.OnClickListener() {
            public void onClick(View v) {
                choose("Sort by", SORTS, sort, new Net.Ok<Integer>() {
                    public void ok(Integer w) { sort = w; refreshChips(); apply(); }
                });
            }
        });
        idxChip = Ui.chip(m, "All indexers", new View.OnClickListener() {
            public void onClick(View v) { pickIndexers(); }
        });
        for (TextView t : new TextView[]{catChip, protoChip, sortChip, idxChip}) chips.addView(t, Ui.margins(-2, -2, 0, 0, 8, 0));
        hs.addView(chips);
        root.addView(hs);

        FrameLayout body = new FrameLayout(m);
        ListView lv = new ListView(m);
        lv.setDivider(null);
        adapter = new JAdapter(new JAdapter.Binder() {
            public View bind(JSONObject o, View cv, ViewGroup p) { return bindResult(o, cv); }
        });
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) { actions(adapter.items.get(pos)); }
        });
        status = m.status("");
        body.addView(lv);
        body.addView(status, new FrameLayout.LayoutParams(-1, -2));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        refreshChips();
        return root;
    }

    @Override void onShow() {
        if (mode == 0) { media.onShow(); return; }
        Svc s = Svc.load(m, Svc.PROWLARR);
        if (!s.url.equals(svc.url) || !s.key.equals(svc.key)) {
            indexers = null;
            chosen.clear();
            results = new ArrayList<>();
            adapter.set(new ArrayList<JSONObject>());
            refreshChips();
        }
        svc = s;
        if (!svc.ok()) setStatus("Prowlarr isn't configured.\nImport your nzb360 backup or add it in Settings.");
        else if (results.isEmpty()) setStatus("Search torrents across all your Prowlarr indexers.\nTap a result to send it to your download client.");
        if (svc.ok() && indexers == null) loadIndexers();
    }

    void setStatus(String s) {
        status.setText(s);
        status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void refreshChips() {
        catChip.setText(CATS[cat]);
        Ui.setChip(catChip, cat != 0, Ui.PROWLARR);
        protoChip.setText(PROTOS[proto]);
        Ui.setChip(protoChip, proto != 0, Ui.PROWLARR);
        sortChip.setText("⇅ " + SORTS[sort]);
        Ui.setChip(sortChip, false, Ui.PROWLARR);
        idxChip.setText(chosen.isEmpty() ? "All indexers" : chosen.size() + " indexer" + (chosen.size() == 1 ? "" : "s"));
        Ui.setChip(idxChip, !chosen.isEmpty(), Ui.PROWLARR);
    }

    void choose(String title, String[] opts, int cur, final Net.Ok<Integer> ok) {
        new AlertDialog.Builder(m).setTitle(title).setSingleChoiceItems(opts, cur, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { d.dismiss(); ok.ok(w); }
        }).show();
    }

    void loadIndexers() {
        Net.async(m, new Net.Job<JSONArray>() {
            public JSONArray run() throws Exception { return Net.arr(svc, "/indexer"); }
        }, new Net.Ok<JSONArray>() {
            public void ok(JSONArray a) { indexers = a; }
        }, new Net.Ok<Exception>() { public void ok(Exception e) {} });
    }

    void pickIndexers() {
        if (indexers == null) { Ui.toast(m, "Loading indexers…"); loadIndexers(); return; }
        final List<JSONObject> list = new ArrayList<>();
        for (JSONObject o : LibraryPage.toList(indexers)) {
            if (!o.optBoolean("enable", true)) continue;
            if (proto == 1 && !"torrent".equals(o.optString("protocol"))) continue;
            if (proto == 2 && !"usenet".equals(o.optString("protocol"))) continue;
            list.add(o);
        }
        String[] names = new String[list.size()];
        final boolean[] on = new boolean[list.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = list.get(i).optString("name");
            on[i] = chosen.contains(list.get(i).optInt("id"));
        }
        new AlertDialog.Builder(m).setTitle("Indexers (none = all)").setMultiChoiceItems(names, on, new DialogInterface.OnMultiChoiceClickListener() {
            public void onClick(DialogInterface d, int w, boolean c) { on[w] = c; }
        }).setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                chosen.clear();
                for (int i = 0; i < on.length; i++) if (on[i]) chosen.add(list.get(i).optInt("id"));
                refreshChips();
            }
        }).setNeutralButton("All", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { chosen.clear(); refreshChips(); }
        }).show();
    }

    void search() {
        final String term = q.getText().toString().trim();
        if (term.isEmpty()) return;
        if (!svc.ok()) { Ui.toast(m, "Set up Prowlarr in Settings first"); return; }
        m.hideKeyboard();
        StringBuilder p = new StringBuilder("/search?type=search&limit=100&offset=0&query=").append(Net.enc(term));
        for (int c : CAT_IDS[cat]) p.append("&categories=").append(c);
        for (int id : chosen) p.append("&indexerIds=").append(id);
        final String path = p.toString();
        final int s = ++seq;
        results = new ArrayList<>();
        adapter.set(new ArrayList<JSONObject>());
        setStatus("Searching indexers…");
        Net.async(m, new Net.Job<JSONArray>() {
            public JSONArray run() throws Exception {
                Object o = Net.json(svc, "GET", path, null, 180000);
                return o instanceof JSONArray ? (JSONArray) o : new JSONArray();
            }
        }, new Net.Ok<JSONArray>() {
            public void ok(JSONArray a) {
                if (s != seq) return;
                results = LibraryPage.toList(a);
                apply();
            }
        }, new Net.Ok<Exception>() {
            public void ok(Exception e) { if (s == seq) setStatus("Search failed\n" + Net.msg(e)); }
        });
    }

    void apply() {
        List<JSONObject> l = new ArrayList<>();
        for (JSONObject o : results) {
            String pr = o.optString("protocol");
            if (proto == 1 && !"torrent".equals(pr)) continue;
            if (proto == 2 && !"usenet".equals(pr)) continue;
            l.add(o);
        }
        Collections.sort(l, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                switch (sort) {
                    case 1: return Long.compare(b.optLong("size"), a.optLong("size"));
                    case 2: return b.optString("publishDate").compareTo(a.optString("publishDate"));
                    case 3: return b.optInt("grabs") - a.optInt("grabs");
                    default: return b.optInt("seeders") - a.optInt("seeders");
                }
            }
        });
        adapter.set(l);
        if (l.isEmpty()) setStatus(results.isEmpty() ? "No results" : results.size() + " results hidden by the " + PROTOS[proto] + " filter");
        else setStatus("");
    }

    View bindResult(JSONObject o, View cv) {
        Ui.Row r = cv instanceof Ui.Row ? (Ui.Row) cv : new Ui.Row(m, false);
        r.bar.setVisibility(View.GONE);
        r.title.setText(o.optString("title"));
        String cats = "";
        JSONArray c = o.optJSONArray("categories");
        if (c != null && c.length() > 0 && c.optJSONObject(0) != null) cats = c.optJSONObject(0).optString("name");
        Ui.set(r.sub, Ui.size(o.optLong("size")) + " · " + o.optString("indexer") + " · " + ReleasesPage.ageText(o));
        Ui.set(r.sub2, cats + (o.optInt("grabs") > 0 ? (cats.isEmpty() ? "" : " · ") + o.optInt("grabs") + " grabs" : ""));
        r.sub2.setTextColor(Ui.DIM);
        if ("torrent".equals(o.optString("protocol"))) {
            int se = o.optInt("seeders");
            Ui.set(r.r1, "▲ " + se);
            r.r1.setTextColor(se >= 10 ? Ui.GREEN : se > 0 ? Ui.RADARR : Ui.RED);
            Ui.set(r.r2, "▼ " + o.optInt("leechers"));
        } else {
            Ui.set(r.r1, "NZB");
            r.r1.setTextColor(Ui.BLUE);
            Ui.set(r.r2, "");
        }
        return r;
    }

    static String magnet(JSONObject o) {
        String mg = o.optString("magnetUrl", "");
        if (mg.startsWith("magnet:")) return mg;
        String d = o.optString("downloadUrl", "");
        if (d.startsWith("magnet:")) return d;
        String h = o.optString("infoHash", "");
        if (h.length() >= 32) return "magnet:?xt=urn:btih:" + h + "&dn=" + Net.enc(o.optString("title"));
        return null;
    }

    void actions(final JSONObject o) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Send to download client");
        acts.add(new Runnable() { public void run() { grab(o); } });
        final String mg = magnet(o);
        if (mg != null) {
            labels.add("Open magnet in torrent app");
            acts.add(new Runnable() { public void run() { view(mg); } });
        }
        final String link = mg != null ? mg : o.optString("downloadUrl", "");
        if (!link.isEmpty()) {
            labels.add(mg != null ? "Copy magnet link" : "Copy download link");
            acts.add(new Runnable() {
                public void run() {
                    ((ClipboardManager) m.getSystemService(Main.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("link", link));
                    Ui.toast(m, "Copied");
                }
            });
        }
        final String info = o.optString("infoUrl", "");
        if (info.startsWith("http")) {
            labels.add("Open indexer page");
            acts.add(new Runnable() { public void run() { view(info); } });
        }
        String size = Ui.size(o.optLong("size"));
        TextView title = Ui.text(m, o.optString("title") + "\n" + size + " · " + o.optString("indexer"), 14, Ui.TEXT, false);
        title.setPadding(Ui.dp(24), Ui.dp(20), Ui.dp(24), Ui.dp(4));
        new AlertDialog.Builder(m).setCustomTitle(title).setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { acts.get(w).run(); }
        }).show();
    }

    void view(String uri) {
        try { m.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri))); }
        catch (Exception e) { Ui.toast(m, "No app can open this link"); }
    }

    void grab(final JSONObject o) {
        Ui.toast(m, "Sending…");
        Net.async(m, new Net.Job<Object>() {
            public Object run() throws Exception {
                JSONObject b = new JSONObject().put("guid", o.optString("guid")).put("indexerId", o.optInt("indexerId"));
                return Net.json(svc, "POST", "/search", b, 60000);
            }
        }, new Net.Ok<Object>() {
            public void ok(Object v) { Ui.toast(m, "Sent to download client ✔"); }
        }, new Net.Ok<Exception>() {
            public void ok(Exception e) { Ui.toast(m, "Grab failed: " + Net.msg(e)); }
        });
    }
}
