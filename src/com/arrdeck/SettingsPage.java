package com.arrdeck;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** nzb360 backup import + manual server configuration. */
final class SettingsPage extends Page {
    static final String[] IDS = {Svc.RADARR, Svc.SONARR, Svc.PROWLARR};
    final EditText[] url = new EditText[3], key = new EditText[3], user = new EditText[3], pass = new EditText[3];
    final CheckBox[] trust = new CheckBox[3];
    final TextView[] state = new TextView[3];

    SettingsPage(Main m) { super(m); }

    @Override View build() {
        LinearLayout root = Ui.col(m);
        root.addView(Ui.header(m, "Settings", Ui.ACCENT, false));
        ScrollView sv = new ScrollView(m);
        LinearLayout body = Ui.col(m);
        body.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        body.addView(serverCard(), Ui.margins(-1, -2, 0, 0, 0, 14));

        LinearLayout imp = card();
        imp.addView(Ui.text(m, "Import from nzb360", 17, Ui.TEXT, true));
        imp.addView(Ui.text(m, "In nzb360 open Settings → Backup & Restore and export a backup, then pick the file here "
                + "(or share/open it with ArrDeck). Radarr, Sonarr and Prowlarr connections are imported into the current server, or into a new one.",
                13, Ui.SUB, false), Ui.margins(-1, -2, 0, 4, 0, 12));
        imp.addView(Ui.button(m, "Choose backup file", Ui.ACCENT, new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { m.startActivityForResult(i, Main.REQ_IMPORT); }
                catch (Exception e) { Ui.toast(m, "No file picker available"); }
            }
        }));
        body.addView(imp, Ui.margins(-1, -2, 0, 0, 0, 14));

        for (int i = 0; i < 3; i++) body.addView(serviceCard(i), Ui.margins(-1, -2, 0, 0, 0, 14));

        body.addView(Ui.button(m, "Save all", Ui.ACCENT, new View.OnClickListener() {
            public void onClick(View v) { saveAll(true); }
        }));
        body.addView(Ui.text(m, "ArrDeck 1.5 · credentials are stored only on this device.", 12, Ui.DIM, false),
                Ui.margins(-2, -2, 0, 18, 0, 0));
        load();
        return root;
    }

    TextView serverName, serverInfo;

    /** Which server these settings belong to, plus switch / rename / add / delete. */
    View serverCard() {
        LinearLayout c = card();
        c.addView(Ui.text(m, "SERVER", 11, Ui.SUB, true));
        serverName = Ui.text(m, "", 20, Ui.TEXT, true);
        c.addView(serverName, Ui.margins(-2, -2, 0, 2, 0, 0));
        serverInfo = Ui.text(m, "", 12, Ui.SUB, false);
        c.addView(serverInfo, Ui.margins(-2, -2, 0, 2, 0, 10));
        LinearLayout b = Ui.row(m);
        b.addView(Ui.outline(m, "\u21C4 Switch", Ui.ACCENT, new View.OnClickListener() {
            public void onClick(View v) { m.pickServer(); }
        }), Ui.weight(1));
        b.addView(new View(m), Ui.lp(Ui.dp(6), 1));
        b.addView(Ui.outline(m, "Rename", Ui.TEXT, new View.OnClickListener() {
            public void onClick(View v) { rename(); }
        }), Ui.weight(1));
        b.addView(new View(m), Ui.lp(Ui.dp(6), 1));
        b.addView(Ui.outline(m, "\uFF0B New", Ui.GREEN, new View.OnClickListener() {
            public void onClick(View v) { m.promptNewServer(null); }
        }), Ui.weight(1));
        b.addView(new View(m), Ui.lp(Ui.dp(6), 1));
        b.addView(Ui.outline(m, "Delete", Ui.RED, new View.OnClickListener() {
            public void onClick(View v) { deleteServer(); }
        }), Ui.weight(1));
        c.addView(b);
        return c;
    }

    void rename() {
        final int n = Svc.active(m);
        final EditText name = Ui.input(m, "Server name", Svc.serverName(m, n), false);
        LinearLayout box = Ui.col(m);
        box.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        box.addView(name);
        new AlertDialog.Builder(m).setTitle("Rename server").setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Svc.setServerName(m, n, name.getText().toString());
                        load();
                        m.refreshServerChips();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    void deleteServer() {
        final int n = Svc.active(m);
        if (Svc.servers(m).size() <= 1) { Ui.toast(m, "This is the only server \u2013 use Clear on each service instead"); return; }
        Ui.confirm(m, "Delete " + Svc.serverName(m, n) + "?", "Its Radarr, Sonarr and Prowlarr connections are removed from this device.",
                "Delete", new Runnable() {
                    public void run() {
                        Svc.deleteServer(m, n);
                        m.switchServer(Svc.active(m));
                    }
                });
    }

    LinearLayout card() {
        LinearLayout c = Ui.col(m);
        c.setBackground(Ui.round(Ui.CARD, 14));
        c.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(16));
        return c;
    }

    View serviceCard(final int i) {
        Svc s = new Svc(IDS[i]);
        LinearLayout c = card();
        LinearLayout head = Ui.row(m);
        View dot = new View(m);
        dot.setBackground(Ui.round(s.color(), 5));
        head.addView(dot, Ui.margins(Ui.dp(10), Ui.dp(10), 0, 0, 10, 0));
        head.addView(Ui.text(m, s.name(), 17, Ui.TEXT, true), Ui.weight(1));
        state[i] = Ui.text(m, "", 12, Ui.SUB, true);
        head.addView(state[i]);
        c.addView(head);
        url[i] = Ui.input(m, "URL  e.g. http://192.168.1.10:" + (i == 0 ? "7878" : i == 1 ? "8989" : "9696"), "", false);
        key[i] = Ui.input(m, "API key (Settings → General)", "", false);
        user[i] = Ui.input(m, "Basic auth user (optional)", "", false);
        pass[i] = Ui.input(m, "Basic auth password", "", true);
        c.addView(url[i], Ui.margins(-1, -2, 0, 12, 0, 0));
        c.addView(key[i], Ui.margins(-1, -2, 0, 8, 0, 0));
        LinearLayout auth = Ui.row(m);
        auth.addView(user[i], Ui.weight(1));
        auth.addView(new View(m), Ui.lp(Ui.dp(8), 1));
        auth.addView(pass[i], Ui.weight(1));
        c.addView(auth, Ui.margins(-1, -2, 0, 8, 0, 0));
        trust[i] = new CheckBox(m);
        trust[i].setText("Allow self-signed HTTPS certificate");
        trust[i].setTextColor(Ui.SUB);
        c.addView(trust[i], Ui.margins(-2, -2, 0, 6, 0, 0));
        LinearLayout btns = Ui.row(m);
        btns.addView(Ui.outline(m, "Test", s.color(), new View.OnClickListener() {
            public void onClick(View v) { test(i); }
        }), Ui.weight(1));
        btns.addView(new View(m), Ui.lp(Ui.dp(8), 1));
        btns.addView(Ui.outline(m, "Clear", Ui.SUB, new View.OnClickListener() {
            public void onClick(View v) {
                url[i].setText("");
                key[i].setText("");
                user[i].setText("");
                pass[i].setText("");
                trust[i].setChecked(false);
                state[i].setText("");
            }
        }), Ui.weight(1));
        c.addView(btns, Ui.margins(-1, -2, 0, 8, 0, 0));
        return c;
    }

    void load() {
        int n = Svc.active(m);
        java.util.List<Integer> all = Svc.servers(m);
        serverName.setText("\u21C4 " + Svc.serverName(m, n));
        serverInfo.setText(all.size() == 1 ? "Only server \u2013 add another with \uFF0B New"
                : "Server " + (all.indexOf(n) + 1) + " of " + all.size() + " \u00B7 the fields below belong to this server");
        for (int i = 0; i < 3; i++) {
            Svc s = Svc.load(m, IDS[i]);
            url[i].setText(s.url);
            key[i].setText(s.key);
            user[i].setText(s.user);
            pass[i].setText(s.pass);
            trust[i].setChecked(s.trustAll);
            state[i].setText(s.ok() ? "configured" : "not set");
            state[i].setTextColor(s.ok() ? Ui.SUB : Ui.DIM);
        }
    }

    Svc fromFields(int i) {
        Svc s = new Svc(IDS[i], Svc.active(m));
        s.url = Svc.normalize(url[i].getText().toString());
        s.key = key[i].getText().toString().trim();
        s.user = user[i].getText().toString().trim();
        s.pass = pass[i].getText().toString();
        s.trustAll = trust[i].isChecked();
        return s;
    }

    void saveAll(boolean toast) {
        for (int i = 0; i < 3; i++) fromFields(i).save(m);
        m.dirty(true);
        m.dirty(false);
        load();
        if (toast) Ui.toast(m, "Saved");
    }

    void test(final int i) {
        final Svc s = fromFields(i);
        if (!s.ok()) { Ui.toast(m, "Enter a URL and API key"); return; }
        state[i].setText("testing…");
        state[i].setTextColor(Ui.SUB);
        Net.async(m, new Net.Job<JSONObject>() {
            public JSONObject run() throws Exception { return Net.obj(s, "GET", "/system/status", null); }
        }, new Net.Ok<JSONObject>() {
            public void ok(JSONObject o) {
                String app = o.optString("appName", s.name());
                if (!app.equalsIgnoreCase(s.name())) {
                    state[i].setText("⚠ that's " + app);
                    state[i].setTextColor(Ui.RED);
                    return;
                }
                state[i].setText("✔ v" + o.optString("version"));
                state[i].setTextColor(Ui.GREEN);
                s.save(m);
                m.dirty(true);
                m.dirty(false);
            }
        }, new Net.Ok<Exception>() {
            public void ok(Exception e) {
                state[i].setText("✖ failed");
                state[i].setTextColor(Ui.RED);
                Ui.toast(m, s.name() + ": " + Net.msg(e));
            }
        });
    }

    // ---------------------------------------------------------------- import

    void importUri(final Uri uri) {
        view(); // make sure fields exist
        Net.async(m, new Net.Job<Importer.Result>() {
            public Importer.Result run() throws Exception {
                InputStream in = m.getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("Can't open file");
                byte[] data = Net.read(in);
                if (data.length > 20 * 1024 * 1024) throw new Exception("File too large");
                return Importer.parse(data);
            }
        }, new Net.Ok<Importer.Result>() {
            public void ok(Importer.Result r) { showImport(r); }
        }, new Net.Ok<Exception>() {
            public void ok(Exception e) { Ui.toast(m, "Import failed: " + Net.msg(e)); }
        });
    }

    void applyImport(List<Importer.Found> f, boolean[] on, int server) {
        Set<String> done = new HashSet<>();
        int n = 0;
        for (int i = 0; i < f.size(); i++) {
            Importer.Found x = f.get(i);
            if (!on[i] || !done.add(x.svc)) continue; // first checked per service wins
            Svc s = Svc.load(m, x.svc, server);
            s.url = x.url;
            s.key = x.key;
            s.user = x.user;
            s.pass = x.pass;
            s.save(m);
            n++;
        }
        load();
        m.dirty(true);
        m.dirty(false);
        Ui.toast(m, "Imported " + n + " connection" + (n == 1 ? "" : "s") + " into " + Svc.serverName(m, server) + " \u2013 testing\u2026");
        for (int i = 0; i < 3; i++) if (fromFields(i).ok()) test(i);
    }

    /** Server name from the imported hosts, e.g. radarr.example.com -> example.com. */
    static String suggestName(List<Importer.Found> f, boolean[] on) {
        for (int i = 0; i < f.size(); i++) {
            if (!on[i]) continue;
            try {
                String h = new java.net.URL(f.get(i).url).getHost();
                if (h.matches("[0-9.]+") || !h.contains(".")) return h;
                String[] parts = h.split("\\.");
                return parts.length > 2 ? h.substring(h.indexOf('.') + 1) : h;
            } catch (Exception ignored) {}
        }
        return "Imported server";
    }

    void showImport(final Importer.Result r) {
        if (r.found.isEmpty()) {
            StringBuilder sb = new StringBuilder("No Radarr, Sonarr or Prowlarr connection with an API key was found in this file. "
                    + "If the backup is encrypted, set the servers up manually below.");
            if (!r.keys.isEmpty()) {
                sb.append("\n\nSetting names found (values hidden):\n");
                for (int i = 0; i < Math.min(40, r.keys.size()); i++) sb.append(r.keys.get(i)).append('\n');
            }
            new AlertDialog.Builder(m).setTitle("Nothing to import").setMessage(sb).setPositiveButton("OK", null).show();
            return;
        }
        final List<Importer.Found> f = r.found;
        String[] labels = new String[f.size()];
        final boolean[] on = new boolean[f.size()];
        Set<String> picked = new HashSet<>();
        for (int i = 0; i < f.size(); i++) {
            labels[i] = f.get(i).label();
            on[i] = picked.add(f.get(i).svc); // best candidate per service pre-selected
        }
        new AlertDialog.Builder(m).setTitle("Import connections")
                .setMultiChoiceItems(labels, on, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int w, boolean c) { on[w] = c; }
                })
                .setPositiveButton("Into " + Svc.serverName(m, Svc.active(m)), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { applyImport(f, on, Svc.active(m)); }
                })
                .setNeutralButton("As new server", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int n = Svc.addServer(m, suggestName(f, on));
                        m.switchServer(n);
                        applyImport(f, on, n);
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
