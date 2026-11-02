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
        head.addView(Ui.text(c, "Navigația", 18, awake ? Ui.TEXT : Ui.MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Ui.text(c, awake ? "trează · acum" : "doarme · ultima măsurare " + Fmt.ago(at), 12, Ui.MUTED, false));
        card.addView(head);
        if (health != null) {
            TextView h = healthLine(c, health, false);
            h.setPadding(0, dp(c, 8), 0, 0);
            card.addView(h);
        }

        // Cate un rand pe valoare: cifra de acum in stanga, graficul ultimei ore in dreapta.
        card.addView(spark(c, u, history, "cpu", "%", "procesor", 100));
        card.addView(spark(c, u, history, "memAvail", "MB", "RAM liber", u.isNull("memTotal") ? Double.NaN : u.optDouble("memTotal")));
        card.addView(spark(c, u, history, "temp", "°C", "temperatură", Double.NaN));

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
        if (u.has("netMode") && !u.isNull("netMode")) facts.add("rețea mobilă: " + netMode(u.optInt("netMode")));
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

    /** Tipul de retea preferat pe inteles, ca netModeLabel din unit.ts. */
    static String netMode(int code) {
        switch (code) {
            case 0: return "3G/2G";
            case 1: return "doar 2G";
            case 3: return "2G/3G";
            case 9: return "4G/3G/2G automat";
            case 11: return "doar 4G (LTE only)";
            case 12: return "4G/3G";
            case 26: case 33: return "5G/4G/3G/2G automat";
            default: return "cod " + code;
        }
    }

    static LinearLayout procRow(Context c, String name, String cpu, String mb, boolean header) {
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

    /**
     * Randul unei valori: cifra de acum si numele in stanga, graficul ultimei ore in dreapta, cu
     * axa timpului dedesubt (atins = valoarea si ora de atunci, in stanga).
     */
    private static LinearLayout spark(Context c, JSONObject u, JSONArray history, String key, String unit, String label,
                                      double max) {
        String now = u.isNull(key) ? "—" : Fmt.num(u.optDouble(key), 0);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 12), 0, 0);
        LinearLayout box = Ui.kpi(c, now, unit, label);
        box.setBackground(null);
        box.setPadding(0, 0, dp(c, 8), 0);
        row.addView(box, new LinearLayout.LayoutParams(dp(c, 104), -2));
        TextView value = (TextView) box.getChildAt(0);
        CharSequence shown = value.getText();
        TextView name = (TextView) box.getChildAt(1);
        name.setSingleLine(true);
        Spark s = new Spark(c, history, key, max, u.isNull(key) ? Double.NaN : u.optDouble(key));
        if (s.count() < 2) return row;
        s.onPick = (v, t) -> {
            value.setText(v == null ? shown : Fmt.num(v, 0) + " " + unit);
            name.setText(v == null ? label : "la " + Fmt.hm(java.time.Instant.ofEpochMilli(t).toString()));
        };
        LinearLayout chart = new LinearLayout(c);
        chart.setOrientation(LinearLayout.VERTICAL);
        chart.addView(s, new LinearLayout.LayoutParams(-1, dp(c, 56)));
        LinearLayout axis = new LinearLayout(c);
        TextView from = Ui.text(c, "acum 1 h", 10, Ui.MUTED, false);
        TextView mid = Ui.text(c, "30 min", 10, Ui.MUTED, false);
        mid.setGravity(android.view.Gravity.CENTER);
        TextView to = Ui.text(c, "acum", 10, Ui.MUTED, false);
        to.setGravity(android.view.Gravity.END);
        axis.addView(from, new LinearLayout.LayoutParams(0, -2, 1));
        axis.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        axis.addView(to, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(c, 2);
        chart.addView(axis, alp);
        row.addView(chart, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
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

        /** Inchide zona de sub o bucata de linie (de la ultimul punct, jos, inapoi la primul). */
        private void closeArea(Path area, int from, int to) {
            area.lineTo(x(ts[to]), getHeight());
            area.lineTo(x(ts[from]), getHeight());
            area.close();
        }

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

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
            // Liniile de ghidaj la jumatate de ora (ca axa de dedesubt).
            canvas.drawLine(getWidth() / 2f, 0, getWidth() / 2f, getHeight(), base);
            Path p = new Path();
            Path area = new Path();
            int segStart = 0;
            for (int i = 0; i < ts.length; i++) {
                boolean newSeg = i == 0 || ts[i] - ts[i - 1] > GAP_MS;
                if (newSeg && i > 0) closeArea(area, segStart, i - 1);
                if (newSeg) {
                    segStart = i;
                    p.moveTo(x(ts[i]), y(vs[i]));
                    area.moveTo(x(ts[i]), y(vs[i]));
                } else {
                    p.lineTo(x(ts[i]), y(vs[i]));
                    area.lineTo(x(ts[i]), y(vs[i]));
                }
            }
            if (ts.length > 0) closeArea(area, segStart, ts.length - 1);
            if (fill.getShader() == null) {
                fill.setShader(new android.graphics.LinearGradient(0, 0, 0, getHeight(),
                        0x5538BDF8, 0x0038BDF8, android.graphics.Shader.TileMode.CLAMP));
            }
            canvas.drawPath(area, fill);
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
