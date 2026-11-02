package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Bordul din fila Acum, cu masina pornita: viteza mare in mijloc, pe un arc de 240° (0–160 km/h),
 * iar turatia pe un arc subtire in interior (0–6000 rpm). Fara valoare, arcele raman goale.
 */
final class SpeedGauge extends View {
    private static final float SWEEP = 240, START = 150;
    private static final double MAX_KMH = 160, MAX_RPM = 6000;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private double kmh = Double.NaN, rpm = Double.NaN;
    private String volt, temp; // „14,2 V”, „9,5 °C”: mici, in colturile de jos

    SpeedGauge(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        float sp = c.getResources().getDisplayMetrics().scaledDensity;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Ui.LINE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        big.setColor(Ui.TEXT);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTextSize(52 * sp);
        big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setColor(Ui.MUTED);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(13 * sp);
    }

    void set(double kmh, double rpm, String volt, String temp) {
        this.kmh = kmh;
        this.rpm = rpm;
        this.volt = volt;
        this.temp = temp;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(190 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() * 0.56f;
        float rad = Math.min(getWidth() / 2f, getHeight() * 0.52f) - 10 * d;
        // Viteza: arcul exterior gros.
        float w1 = 10 * d;
        track.setStrokeWidth(w1);
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(r, START, SWEEP, false, track);
        if (!Double.isNaN(kmh)) {
            arc.setStrokeWidth(w1);
            arc.setColor(Ui.ACCENT);
            c.drawArc(r, START, (float) (SWEEP * Math.min(1, kmh / MAX_KMH)), false, arc);
        }
        // Turatia: arcul interior subtire.
        float rad2 = rad - 18 * d, w2 = 4 * d;
        track.setStrokeWidth(w2);
        r.set(cx - rad2, cy - rad2, cx + rad2, cy + rad2);
        c.drawArc(r, START, SWEEP, false, track);
        if (!Double.isNaN(rpm)) {
            arc.setStrokeWidth(w2);
            arc.setColor(Ui.WARN);
            c.drawArc(r, START, (float) (SWEEP * Math.min(1, rpm / MAX_RPM)), false, arc);
        }
        c.drawText(Double.isNaN(kmh) ? "—" : String.valueOf(Math.round(kmh)), cx, cy + 14 * d, big);
        c.drawText("km/h", cx, cy + 36 * d, small);
        if (!Double.isNaN(rpm)) c.drawText(Math.round(rpm) + " rpm", cx, cy + rad * 0.86f, small);
        // Bateria (stanga) si temperatura afara (dreapta), in colturile de jos, langa capetele arcului.
        float by = getHeight() - 22 * d;
        corner(c, volt, "baterie", 4 * d, by, Paint.Align.LEFT);
        corner(c, temp, "afară", getWidth() - 4 * d, by, Paint.Align.RIGHT);
    }

    private void corner(Canvas c, String value, String label, float x, float y, Paint.Align align) {
        if (value == null) return;
        value(c, value, x, y, align);
        small.setTextAlign(align);
        c.drawText(label, x, y + 17 * d, small);
        small.setTextAlign(Paint.Align.CENTER);
    }

    private void value(Canvas c, String text, float x, float y, Paint.Align align) {
        float size = big.getTextSize();
        big.setTextSize(size * 0.33f);
        big.setTextAlign(align);
        c.drawText(text, x, y, big);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTextSize(size);
    }
}
