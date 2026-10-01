package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Stilul interfetei (tema intunecata, ca FaikkitCar din masina), construit din cod. */
final class Ui {
    static final int BG = 0xFF0B0F17;
    static final int CARD = 0xFF151B26;
    static final int CARD2 = 0xFF1E2636;
    static final int LINE = 0xFF263041;
    static final int TEXT = 0xFFE8EDF5;
    static final int MUTED = 0xFF8A96A8;
    static final int ACCENT = 0xFF38BDF8;
    static final int OK = 0xFF22C55E;
    static final int WARN = 0xFFF59E0B;
    static final int BAD = 0xFFEF4444;

    static final int PRIMARY = 0, SECONDARY = 1, DANGER = 2;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView mono(Context c, String s, float sp) {
        TextView t = text(c, s, sp, TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** Card rotunjit, adaugat in parinte cu spatiu dedesubt. */
    static LinearLayout card(Context c, LinearLayout parent) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(c, CARD, 20));
        int p = dp(c, 20);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 14);
        parent.addView(card, lp);
        return card;
    }

    static TextView title(Context c, LinearLayout parent, String s) {
        TextView t = text(c, s, 19, TEXT, true);
        parent.addView(t);
        return t;
    }

    static TextView hint(Context c, LinearLayout parent, String s) {
        TextView t = text(c, s, 14, MUTED, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 4);
        parent.addView(t, lp);
        return t;
    }

    static Button button(Context c, String s, int style, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setStateListAnimator(null);
        int bg = style == PRIMARY ? ACCENT : style == DANGER ? 0x33EF4444 : CARD2;
        int fg = style == PRIMARY ? BG : style == DANGER ? BAD : TEXT;
        b.setTextColor(fg);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(c, bg, 14), null));
        int ph = dp(c, 18);
        b.setPadding(ph, 0, ph, 0);
        b.setMinHeight(dp(c, 52));
        b.setOnClickListener(click);
        return b;
    }

    /** Buton adaugat pe toata latimea, cu spatiu deasupra. */
    static Button addButton(Context c, LinearLayout parent, String s, int style, View.OnClickListener click) {
        Button b = button(c, s, style, click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 12);
        parent.addView(b, lp);
        return b;
    }

    static TextView pill(Context c, String s, int color) {
        TextView t = text(c, s, 14, color, true);
        t.setBackground(round(c, (color & 0x00FFFFFF) | 0x2E000000, 99));
        int ph = dp(c, 14), pv = dp(c, 6);
        t.setPadding(ph, pv, ph, pv);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static EditText input(Context c, String value, boolean decimal) {
        EditText e = new EditText(c);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setTextSize(18);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER
                | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setBackground(round(c, CARD2, 12));
        int p = dp(c, 14);
        e.setPadding(p, p, p, p);
        return e;
    }

    /** Eticheta + camp de introducere + explicatie, pe un rand. */
    static EditText field(Context c, LinearLayout parent, String label, String help, String value,
            boolean decimal) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(c, label, 16, TEXT, true));
        texts.addView(text(c, help, 13, MUTED, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        EditText e = input(c, value, decimal);
        e.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(c, 110), -2);
        elp.leftMargin = dp(c, 16);
        row.addView(e, elp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 16);
        parent.addView(row, lp);
        return e;
    }

    /** Separator subtire intre randuri. */
    static void divider(Context c, LinearLayout parent) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 12);
        lp.bottomMargin = dp(c, 4);
        parent.addView(v, lp);
    }

    /** Camp de text pentru dialoguri, cu eticheta deasupra. */
    static EditText labeled(Context c, LinearLayout parent, String label, String value, int inputType) {
        TextView l = text(c, label, 13, MUTED, false);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(c, 10);
        parent.addView(l, llp);
        EditText e = new EditText(c);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(17);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackground(round(c, CARD2, 12));
        int p = dp(c, 12);
        e.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 4);
        parent.addView(e, lp);
        return e;
    }

    /** O valoare mica cu eticheta deasupra (grila de detalii a unei calatorii). */
    static LinearLayout info(Context c, String label, String value) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(c, label, 12, MUTED, false));
        box.addView(text(c, value, 15, TEXT, true));
        return box;
    }

    /** Grila cu `cols` coloane egale, umpluta din `cells`. */
    static LinearLayout grid(Context c, java.util.List<? extends View> cells, int cols) {
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(c);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = dp(c, 10);
                g.addView(row, rlp);
            }
            row.addView(cells.get(i), new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (row != null) {
            for (int i = cells.size() % cols; i > 0 && i < cols; i++) {
                row.addView(new View(c), new LinearLayout.LayoutParams(0, 0, 1));
            }
        }
        return g;
    }
}
