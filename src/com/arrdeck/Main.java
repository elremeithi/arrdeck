package com.arrdeck;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Main extends Activity {
    static final int REQ_IMPORT = 7;

    FrameLayout content;
    final List<ArrayList<Page>> stacks = new ArrayList<>();
    final TextView[] tabViews = new TextView[4];
    static final String[] TABS = {"Movies", "Shows", "Search", "Settings"};
    static final String[] GLYPHS = {"🎬", "📺", "🔍", "⚙️"};
    static final int[] COLORS = {Ui.RADARR, Ui.SONARR, Ui.PROWLARR, Ui.ACCENT};
    int tab = 0;
    SettingsPage settings;
    SearchPage search;
    /** Open the keyboard on the search box once the window first gets focus (app launch). */
    boolean keyboardOnLaunch;

    /** Library pages reload when they become visible after something changed. */
    boolean moviesDirty, showsDirty;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.density = getResources().getDisplayMetrics().density;
        try { HttpResponseCache.install(new File(getCacheDir(), "http"), 64L * 1024 * 1024); } catch (Exception ignored) {}

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(0xFF161A20);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = Ui.text(this, GLYPHS[i] + "\n" + TABS[i], 11, Ui.SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
            t.setLineSpacing(Ui.dp(2), 1f);
            Ui.clickable(t, null);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (tab == idx && stacks.get(idx).size() > 1) { // re-tap: pop to root
                        ArrayList<Page> s = stacks.get(idx);
                        while (s.size() > 1) s.remove(s.size() - 1);
                    }
                    select(idx);
                    if (idx == 2 && stacks.get(2).size() == 1) search.focusSearch();
                }
            });
            tabViews[i] = t;
            bar.addView(t, Ui.weight(1));
        }
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        settings = new SettingsPage(this);
        stacks.add(list(new LibraryPage(this, true)));
        stacks.add(list(new LibraryPage(this, false)));
        search = new SearchPage(this);
        stacks.add(list(search));
        stacks.add(list(settings));

        boolean any = Svc.anyConfigured(this);
        // Search is the home screen; Settings first only when nothing is configured yet
        select(any ? 2 : 3);
        keyboardOnLaunch = any;
        handleIntent(getIntent());
    }

    private static ArrayList<Page> list(Page p) {
        ArrayList<Page> l = new ArrayList<>();
        l.add(p);
        return l;
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && keyboardOnLaunch) {
            keyboardOnLaunch = false;
            if (tab == 2 && stacks.get(2).size() == 1) search.focusSearch();
        }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) return;
        select(3);
        settings.importUri(u);
    }

    Page top() {
        ArrayList<Page> s = stacks.get(tab);
        return s.get(s.size() - 1);
    }

    void select(int i) {
        tab = i;
        for (int k = 0; k < 4; k++) {
            tabViews[k].setTextColor(k == i ? COLORS[k] : Ui.SUB);
            tabViews[k].setAlpha(k == i ? 1f : 0.75f);
        }
        show();
    }

    void show() {
        hideKeyboard();
        content.removeAllViews();
        Page p = top();
        View v = p.view();
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        p.onShow();
    }

    void push(Page p) {
        stacks.get(tab).add(p);
        show();
    }

    void hideKeyboard() {
        View f = getCurrentFocus();
        if (f != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    @Override public void onBackPressed() {
        ArrayList<Page> s = stacks.get(tab);
        if (s.size() > 1) {
            s.remove(s.size() - 1);
            show();
        } else if (tab != 0) select(0);
        else super.onBackPressed();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) settings.importUri(data.getData());
    }

    @Override protected void onStop() {
        super.onStop();
        HttpResponseCache c = HttpResponseCache.getInstalled();
        if (c != null) c.flush();
    }

    // ---------------------------------------------------------------- servers

    final ArrayList<java.lang.ref.WeakReference<TextView>> serverChips = new ArrayList<>();

    /** Small "⇄ name" chip shown at the top right of every page; tap to switch / add servers. */
    TextView serverChip() {
        TextView t = Ui.text(this, "", 12, Ui.TEXT, true);
        t.setSingleLine();
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(Ui.dp(120));
        t.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
        Ui.clickable(t, Ui.round(Ui.CARD2, 14));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickServer(); }
        });
        serverChips.add(new java.lang.ref.WeakReference<>(t));
        setChipText(t);
        return t;
    }

    private void setChipText(TextView t) {
        t.setText("⇄ " + Svc.serverName(this, Svc.active(this)));
    }

    void refreshServerChips() {
        for (int i = serverChips.size() - 1; i >= 0; i--) {
            TextView t = serverChips.get(i).get();
            if (t == null) serverChips.remove(i); else setChipText(t);
        }
    }

    void pickServer() {
        final List<Integer> ids = Svc.servers(this);
        int active = Svc.active(this);
        String[] items = new String[ids.size() + 1];
        int sel = 0;
        for (int i = 0; i < ids.size(); i++) {
            int n = ids.get(i);
            StringBuilder what = new StringBuilder();
            for (String s : new String[]{Svc.RADARR, Svc.SONARR, Svc.PROWLARR})
                if (Svc.load(this, s, n).ok()) what.append(what.length() > 0 ? " · " : "").append(new Svc(s).name());
            items[i] = Svc.serverName(this, n) + "\n" + (what.length() > 0 ? what : "not configured");
            if (n == active) sel = i;
        }
        items[ids.size()] = "＋ Add server…";
        new android.app.AlertDialog.Builder(this).setTitle("Server")
                .setSingleChoiceItems(items, sel, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        d.dismiss();
                        if (w < ids.size()) switchServer(ids.get(w));
                        else promptNewServer(null);
                    }
                }).show();
    }

    /** Asks for a name, creates the server, switches to it and opens Settings (then runs {@code then}). */
    void promptNewServer(final Net.Ok<Integer> then) {
        final android.widget.EditText name = Ui.input(this, "e.g. Home, Seedbox, Mum's", "Server " + (Svc.servers(this).size() + 1), false);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        box.addView(name);
        new android.app.AlertDialog.Builder(this).setTitle("New server").setView(box)
                .setPositiveButton("Create", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String nm = name.getText().toString().trim();
                        int n = Svc.addServer(Main.this, nm.isEmpty() ? "Server " + (Svc.servers(Main.this).size() + 1) : nm);
                        switchServer(n);
                        if (then != null) then.ok(n);
                        else { select(3); Ui.toast(Main.this, "Import a backup or enter connections for this server"); }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    void switchServer(int n) {
        Svc.setActive(this, n);
        // pages deeper in a tab belong to the old server's data, so go back to each tab's root
        for (ArrayList<Page> s : stacks) while (s.size() > 1) s.remove(s.size() - 1);
        moviesDirty = showsDirty = true;
        refreshServerChips();
        if (settings.root != null) settings.load();
        show();
        Ui.toast(this, "Switched to " + Svc.serverName(this, n));
    }

    void dirty(boolean movies) {
        if (movies) moviesDirty = true; else showsDirty = true;
    }

    /** Status message used for empty / loading / error states. */
    TextView status(String s) {
        TextView t = Ui.text(this, s, 14, Ui.SUB, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(24), Ui.dp(40), Ui.dp(24), Ui.dp(24));
        return t;
    }
}

abstract class Page {
    final Main m;
    View root;

    Page(Main m) { this.m = m; }

    abstract View build();

    View view() {
        if (root == null) root = build();
        return root;
    }

    void onShow() {}

    boolean visible() { return m.top() == this; }
}

/** Simple JSON-backed adapter for ListView / GridView. */
final class JAdapter extends BaseAdapter {
    interface Binder { View bind(JSONObject o, View convert, ViewGroup parent); }

    List<JSONObject> items = new ArrayList<>();
    final Binder binder;

    JAdapter(Binder b) { binder = b; }

    void set(List<JSONObject> l) {
        items = l;
        notifyDataSetChanged();
    }

    public int getCount() { return items.size(); }

    public Object getItem(int p) { return items.get(p); }

    public long getItemId(int p) { return p; }

    public View getView(int p, View cv, ViewGroup parent) { return binder.bind(items.get(p), cv, parent); }
}
