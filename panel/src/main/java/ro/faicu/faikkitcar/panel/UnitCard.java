package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static ro.faicu.faikkitcar.panel.Ui.dp;

/**
 * Performanta navigatiei (Teyes) pe fila Acum, ca pe site (UnitCard.tsx): procesorul, memoria,
 * temperatura, cu graficele pe ultima ora, si procesele care consuma cel mai mult.
 */
final class UnitCard {
    // Fara masurare noua atata timp, navigatia doarme (sau aplicatia nu ruleaza).
    private static final long STALE_MS = 90_000;
    // Intre doua masurari mai departe de atat, linia se intrerupe (somn).
    private static final long GAP_MS = 120_000;

    private UnitCard() {}

    /**
     * Navigatia pe scurt (serverul o calculeaza: „merge bine · 1,1 GB liberi…” sau problemele).
     * `prefix` = cu „Navigatia” in fata si „›” la capat (primul card de pe Acum).
     */
    static TextView healthLine(Context c, JSONObject h, boolean prefix) {
        String level = h.optString("level");
        String text = h.optString("text");
        boolean ok = "ok".equals(level);
        int color = ok ? Ui.TEXT : "bad".equals(level) ? Ui.BAD : Ui.WARN;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(ok ? "▣ " : "⚠ ");
        sb.setSpan(new android.text.style.ForegroundColorSpan(ok ? Ui.ACCENT : color), 0, sb.length(), 0);
        if (prefix) {
            int from = sb.length();
            sb.append("Navigația  ");
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED), from, sb.length(), 0);
        } else if (!text.isEmpty()) {
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        int start = sb.length();
        sb.append(text);
        if (!ok) sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), 0);
        if (prefix) {
            int from = sb.length();
            sb.append("\u00A0\u00A0›"); // lipit de text, nu singur pe un rand
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED), from, sb.length(), 0);
        }
        TextView t = Ui.text(c, "", 14, color, false);
        t.setText(sb);
        return t;
    }

    static void render(Context c, LinearLayout content, JSONObject u, JSONObject health, JSONArray history) {
        String at = u.optString("at");
        boolean awake = System.currentTimeMillis() - Fmt.millis(at) < STALE_MS;
        LinearLayout card = Ui.card(c, content);
        LinearLayout head = new LinearLayout(c);
        head.addView(Ui.text(c, "Navigația", 17, awake ? Ui.TEXT : Ui.MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Ui.text(c, awake ? "trează · acum" : "doarme · ultima măsurare " + Fmt.ago(at), 12, Ui.MUTED, false));
        card.addView(head);
        if (health != null) {
            TextView h = healthLine(c, health, false);
            h.setPadding(0, dp(c, 8), 0, 0);
            card.addView(h);
        }

        LinearLayout row = Ui.kpiRow(c,
                spark(c, u, history, "cpu", "%", "procesor", 100),
                spark(c, u, history, "memAvail", "MB", "RAM liber", u.isNull("memTotal") ? Double.NaN : u.optDouble("memTotal")),
                spark(c, u, history, "temp", "°C", "temperatură", Double.NaN));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(c, 10);
        card.addView(row, rlp);

        List<String> facts = new ArrayList<>();
        if (!u.isNull("memTotal")) facts.add(Fmt.num(u.optDouble("memTotal") / 1024, 1) + " GB memorie");
        if (!u.isNull("swapTotal") && !u.isNull("swapFree") && u.optDouble("swapTotal") > 0) {
            double used = u.optDouble("swapTotal") - u.optDouble("swapFree");
            facts.add("swap " + Math.round(used / u.optDouble("swapTotal") * 100) + "% plin");
        }
        if (!u.isNull("uptime")) facts.add("pornită de " + Fmt.duration(u.optDouble("uptime") / 60));
        if (!u.isNull("load")) facts.add("încărcare " + Fmt.num(u.optDouble("load"), 1)
                + (u.isNull("cores") ? "" : " (" + u.optInt("cores") + " nuclee)"));
        if (!u.isNull("appMb")) facts.add("FaikkitCar " + u.optInt("appMb") + " MB"
                + (u.isNull("appAdj") ? "" : ", adj " + u.optInt("appAdj")));
        if (!u.isNull("diskGb")) facts.add("liber " + Fmt.num(u.optDouble("diskGb"), 1) + " GB");
        if (!facts.isEmpty()) {
            TextView f = Ui.text(c, String.join(" · ", facts), 12, Ui.MUTED, false);
            f.setPadding(0, dp(c, 8), 0, 0);
            card.addView(f);
        }

        JSONArray procs = u.optJSONArray("procs");
        if (!awake || procs == null || procs.length() == 0) return;
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < procs.length(); i++) list.add(procs.optJSONObject(i));
        list.sort((a, b) -> {
            int byCpu = Double.compare(b.optDouble("cpu", 0), a.optDouble("cpu", 0));
            return byCpu != 0 ? byCpu : Integer.compare(b.optInt("mb"), a.optInt("mb"));
        });
        card.addView(procRow(c, "Procese (ultimele ~30 s)", "procesor", "memorie", true));
        for (JSONObject p : list) {
            card.addView(procRow(c, p.optString("label", p.optString("name")),
                    p.isNull("cpu") ? "—" : Fmt.num(p.optDouble("cpu"), 1) + "%", p.optInt("mb") + " MB", false));
        }
    }

    private static LinearLayout procRow(Context c, String name, String cpu, String mb, boolean header) {
        LinearLayout r = new LinearLayout(c);
        r.setPadding(0, dp(c, header ? 10 : 4), 0, 0);
        int size = header ? 11 : 14;
        int color = header ? Ui.MUTED : Ui.TEXT;
        TextView n = Ui.text(c, name, size, color, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        TextView a = Ui.text(c, cpu, size, color, false);
        a.setGravity(android.view.Gravity.END);
        r.addView(a, new LinearLayout.LayoutParams(dp(c, 64), -2));
        TextView b = Ui.text(c, mb, size, color, false);
        b.setGravity(android.view.Gravity.END);
        r.addView(b, new LinearLayout.LayoutParams(dp(c, 72), -2));
        return r;
    }

    /** Casuta cu valoarea de acum si graficul ei pe ultima ora (atins = valoarea de atunci). */
    private static LinearLayout spark(Context c, JSONObject u, JSONArray history, String key, String unit, String label,
                                      double max) {
        String now = u.isNull(key) ? "—" : Fmt.num(u.optDouble(key), 0);
        LinearLayout box = Ui.kpi(c, now, unit, label);
        TextView value = (TextView) box.getChildAt(0);
        CharSequence shown = value.getText();
        TextView name = (TextView) box.getChildAt(1);
        name.setSingleLine(true);
        Spark s = new Spark(c, history, key, max, u.isNull(key) ? Double.NaN : u.optDouble(key));
        if (s.count() < 2) return box;
        s.onPick = (v, t) -> {
            value.setText(v == null ? shown : Fmt.num(v, 0) + " " + unit);
            name.setText(v == null ? label : "la " + Fmt.hm(java.time.Instant.ofEpochMilli(t).toString()));
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 36));
        lp.topMargin = dp(c, 6);
        box.addView(s, lp);
        return box;
    }

    interface Pick {
        void on(Double value, long t);
    }

    /** O singura serie, linie de 2 dp; se intrerupe la golurile din masurari. */
    static final class Spark extends View {
        private final long[] ts;
        private final double[] vs;
        private final long start, end;
        private final double lo, hi;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint base = new Paint();
        private final Paint cross = new Paint();
        private int hover = -1;
        Pick onPick;
        // Cat e atins un grafic, fila nu se redeseneaza (ar pierde atingerea), ca la harta live.
        static boolean touching;

        Spark(Context c, JSONArray history, String key, double max, double now) {
            super(c);
            end = System.currentTimeMillis();
            start = end - 3_600_000;
            List<long[]> t = new ArrayList<>();
            List<Double> v = new ArrayList<>();
            for (int i = 0; history != null && i < history.length(); i++) {
                JSONObject p = history.optJSONObject(i);
                if (p == null || p.isNull(key)) continue;
                long at = Fmt.millis(p.optString("at"));
                if (at < start) continue;
                t.add(new long[] {at});
                v.add(p.optDouble(key));
            }
            ts = new long[t.size()];
            vs = new double[v.size()];
            double mn = Double.isNaN(now) ? Double.MAX_VALUE : now, mx = Double.isNaN(now) ? -Double.MAX_VALUE : now;
            for (int i = 0; i < ts.length; i++) {
                ts[i] = t.get(i)[0];
                vs[i] = v.get(i);
                mn = Math.min(mn, vs[i]);
                mx = Math.max(mx, vs[i]);
            }
            lo = Double.isNaN(max) ? mn - 2 : 0;
            hi = Double.isNaN(max) ? mx + 2 : max;
            line.setColor(Ui.ACCENT);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(c, 2));
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            base.setColor(0x1AFFFFFF);
            base.setStrokeWidth(1);
            cross.setColor(0x66FFFFFF);
            cross.setStrokeWidth(dp(c, 1));
        }

        int count() {
            return ts.length;
        }

        private float x(long t) {
            return (float) ((t - start) / (double) (end - start) * getWidth());
        }

        private float y(double v) {
            float pad = line.getStrokeWidth();
            return (float) (getHeight() - pad - (v - lo) / (hi - lo == 0 ? 1 : hi - lo) * (getHeight() - 2 * pad));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawLine(0, getHeight() - 0.5f, getWidth(), getHeight() - 0.5f, base);
            Path p = new Path();
            for (int i = 0; i < ts.length; i++) {
                if (i == 0 || ts[i] - ts[i - 1] > GAP_MS) p.moveTo(x(ts[i]), y(vs[i]));
                else p.lineTo(x(ts[i]), y(vs[i]));
            }
            canvas.drawPath(p, line);
            if (hover >= 0) canvas.drawLine(x(ts[hover]), 0, x(ts[hover]), getHeight(), cross);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (ts.length == 0) return false;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                touching = false;
                hover = -1;
                if (onPick != null) onPick.on(null, 0);
                invalidate();
                return true;
            }
            touching = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            long t = start + (long) (e.getX() / Math.max(1, getWidth()) * (end - start));
            int best = 0;
            for (int i = 1; i < ts.length; i++) if (Math.abs(ts[i] - t) < Math.abs(ts[best] - t)) best = i;
            hover = best;
            if (onPick != null) onPick.on(vs[best], ts[best]);
            invalidate();
            return true;
        }
    }
}
