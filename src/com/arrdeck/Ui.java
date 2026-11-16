package com.arrdeck;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Tiny programmatic UI toolkit: no XML layouts, no support libs, fast startup. */
final class Ui {
    static final int BG = 0xFF101317, CARD = 0xFF1B2027, CARD2 = 0xFF252C35, TEXT = 0xFFEDF1F5,
            SUB = 0xFF95A1AE, DIM = 0xFF5E6873, GREEN = 0xFF27C24C, RED = 0xFFF05050, BLUE = 0xFF5D9CEC,
            RADARR = 0xFFFFC230, SONARR = 0xFF35C5F4, PROWLARR = 0xFFFF7A3D, ACCENT = 0xFF9B7CFF;

    static float density = 1f;

    static int dp(float v) { return Math.round(v * density); }

    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable round(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        return g;
    }

    static void clickable(View v, Drawable bg) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, bg == null ? round(Color.WHITE, 8) : null));
        v.setClickable(true);
    }

    static TextView button(Context c, String label, int color, View.OnClickListener l) {
        TextView b = text(c, label, 14, isLight(color) ? 0xFF111111 : Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        clickable(b, round(color, 10));
        b.setOnClickListener(l);
        return b;
    }

    static TextView outline(Context c, String label, int color, View.OnClickListener l) {
        TextView b = text(c, label, 14, color, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        clickable(b, round(CARD2, 10));
        b.setOnClickListener(l);
        return b;
    }

    static boolean isLight(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return (r * 299 + g * 587 + b * 114) / 1000 > 150;
    }

    static TextView chip(Context c, String label, View.OnClickListener l) {
        TextView t = text(c, label, 13, SUB, true);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setSingleLine();
        t.setOnClickListener(l);
        setChip(t, false, ACCENT);
        return t;
    }

    static void setChip(TextView t, boolean on, int color) {
        t.setTextColor(on ? (isLight(color) ? 0xFF111111 : Color.WHITE) : SUB);
        clickable(t, round(on ? color : CARD2, 16));
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    static EditText input(Context c, String hint, String value, boolean secret) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine();
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setTextSize(15);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(round(CARD2, 10));
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    static TextView iconBtn(Context c, String glyph, View.OnClickListener l) {
        TextView t = text(c, glyph, 22, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(44));
        t.setMinHeight(dp(44));
        clickable(t, null);
        t.setOnClickListener(l);
        return t;
    }

    /** Top bar: optional back arrow, coloured title, and right-aligned actions. */
    static LinearLayout header(final Main m, String title, int color, boolean back, View... actions) {
        LinearLayout h = row(m);
        h.setPadding(dp(back ? 4 : 16), dp(6), dp(6), dp(6));
        h.setMinimumHeight(dp(56));
        if (back) h.addView(iconBtn(m, "←", new View.OnClickListener() {
            public void onClick(View v) { m.onBackPressed(); }
        }));
        TextView t = text(m, title, 20, TEXT, true);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        View dot = new View(m);
        dot.setBackground(round(color, 4));
        h.addView(dot, margins(dp(8), dp(8), back ? 4 : 0, 0, 10, 0));
        h.addView(t, weight(1));
        for (View a : actions) h.addView(a);
        h.addView(m.serverChip(), margins(-2, -2, 4, 0, 6, 0));
        return h;
    }

    static ProgressBar spinner(Context c) {
        ProgressBar p = new ProgressBar(c);
        p.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        return p;
    }

    static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }

    static void confirm(Context c, String title, String msg, String ok, final Runnable r) {
        new AlertDialog.Builder(c).setTitle(title).setMessage(msg)
                .setPositiveButton(ok, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { r.run(); }
                }).setNegativeButton("Cancel", null).show();
    }

    static void set(TextView t, CharSequence s) {
        t.setText(s);
        t.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    static String size(long b) {
        if (b <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        double d = b;
        while (d >= 1024 && i < u.length - 1) { d /= 1024; i++; }
        return String.format(Locale.US, i >= 3 ? "%.2f %s" : "%.0f %s", d, u[i]);
    }

    static Date iso(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat(s.length() >= 19 ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s.length() >= 19 ? s.substring(0, 19) : s.substring(0, 10));
        } catch (Exception e) { return null; }
    }

    static String isoNow(long offsetMs) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(System.currentTimeMillis() + offsetMs));
    }

    static String day(Date d) {
        if (d == null) return "";
        long days = Math.round((startOfDay(d.getTime()) - startOfDay(System.currentTimeMillis())) / 86400000.0);
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        if (days == -1) return "Yesterday";
        return new SimpleDateFormat(days > -7 && days < 7 ? "EEE d MMM" : "d MMM yyyy", Locale.getDefault()).format(d);
    }

    static long startOfDay(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String age(Date d) {
        if (d == null) return "";
        long h = (System.currentTimeMillis() - d.getTime()) / 3600000L;
        if (h < 1) return "now";
        if (h < 48) return h + "h";
        long days = h / 24;
        if (days < 60) return days + "d";
        if (days < 730) return (days / 30) + "mo";
        return (days / 365) + "y";
    }

    static String time(Date d) {
        return d == null ? "" : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d);
    }

    /** Thin progress bar. */
    static final class Bar extends View {
        float frac;
        int color = GREEN;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Bar(Context c) { super(c); }

        void set(float f, int col) { frac = Math.max(0, Math.min(1, f)); color = col; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float rad = getHeight() / 2f;
            p.setColor(CARD2);
            r.set(0, 0, getWidth(), getHeight());
            c.drawRoundRect(r, rad, rad, p);
            if (frac > 0) {
                p.setColor(color);
                r.set(0, 0, Math.max(getHeight(), getWidth() * frac), getHeight());
                c.drawRoundRect(r, rad, rad, p);
            }
        }
    }

    /** ImageView locked to a 2:3 poster aspect ratio. */
    static final class Poster extends ImageView {
        Poster(Context c) {
            super(c);
            setScaleType(ScaleType.CENTER_CROP);
            setBackground(round(CARD2, 8));
            setClipToOutline(true);
        }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, width * 3 / 2);
        }
    }

    /** General purpose list row: thumb, title/sub/sub2/progress, right-side labels. */
    static final class Row extends LinearLayout {
        final ImageView thumb;
        final View accent; // optional colour stripe (e.g. movie vs show)
        final TextView title, sub, sub2, r1, r2;
        final Bar bar;

        Row(Context c, boolean withThumb) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(10), dp(14), dp(10));
            // ripple only: ListView handles the click, so the row itself must not be clickable
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, round(Color.WHITE, 8)));
            accent = new View(c);
            accent.setVisibility(GONE);
            addView(accent, margins(dp(4), LayoutParams.MATCH_PARENT, 0, 0, 10, 0));
            if (withThumb) {
                thumb = new ImageView(c);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(round(CARD2, 6));
                thumb.setClipToOutline(true);
                addView(thumb, margins(dp(46), dp(69), 0, 0, 12, 0));
            } else thumb = null;
            LinearLayout mid = col(c);
            title = text(c, "", 15, TEXT, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = text(c, "", 13, SUB, false);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub2 = text(c, "", 12, DIM, false);
            sub2.setSingleLine();
            sub2.setEllipsize(TextUtils.TruncateAt.END);
            bar = new Bar(c);
            mid.addView(title);
            mid.addView(sub);
            mid.addView(sub2);
            mid.addView(bar, margins(-1, dp(4), 0, 6, 0, 2));
            addView(mid, weight(1));
            LinearLayout right = col(c);
            right.setGravity(Gravity.END);
            r1 = text(c, "", 14, TEXT, true);
            r2 = text(c, "", 12, SUB, false);
            r1.setGravity(Gravity.END);
            r2.setGravity(Gravity.END);
            right.addView(r1);
            right.addView(r2);
            addView(right, margins(-2, -2, 10, 0, 0, 0));
        }
    }
}
