package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Bare verticale (lunile, de la stanga la dreapta), cu eticheta sub fiecare. Bara aleasa e
 * plina, cu valoarea deasupra; celelalte, estompate. O atingere alege alta bara.
 */
final class BarChart extends View {
    interface OnSelect {
        void on(int index);
    }

    private double[] values = new double[0];
    private String[] labels = new String[0], texts = new String[0];
    private int selected = -1, color = Ui.ACCENT;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    OnSelect onSelect;

    BarChart(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        float sp = c.getResources().getDisplayMetrics().scaledDensity;
        label.setColor(Ui.MUTED);
        label.setTextSize(11 * sp);
        label.setTextAlign(Paint.Align.CENTER);
        value.setColor(Ui.TEXT);
        value.setTextSize(12 * sp);
        value.setTextAlign(Paint.Align.CENTER);
        value.setFakeBoldText(true);
    }

    /** texts = valoarea scrisa deasupra barei alese (ex. „453 km”). */
    void set(double[] values, String[] labels, String[] texts, int color, int selected) {
        this.values = values;
        this.labels = labels;
        this.texts = texts;
        this.color = color;
        this.selected = selected;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(150 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = values.length;
        if (n == 0) return;
        double max = 0;
        for (double v : values) max = Math.max(max, v);
        if (max <= 0) max = 1;
        float labelH = 20 * d, valueH = 20 * d;
        float top = valueH, bottom = getHeight() - labelH;
        float slot = getWidth() / (float) n;
        float bw = Math.min(slot * 0.56f, 34 * d);
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2;
            float h = (float) (values[i] / max * (bottom - top));
            r.set(cx - bw / 2, bottom - Math.max(h, 3 * d), cx + bw / 2, bottom);
            bar.setColor(i == selected ? color : (color & 0x00FFFFFF) | 0x4D000000);
            c.drawRoundRect(r, 6 * d, 6 * d, bar);
            label.setColor(i == selected ? Ui.TEXT : Ui.MUTED);
            c.drawText(labels[i], cx, getHeight() - 5 * d, label);
            if (i == selected && texts[i] != null) {
                // Valoarea deasupra barei, tinuta in interiorul graficului la margini.
                float half = value.measureText(texts[i]) / 2;
                float x = Math.max(half, Math.min(getWidth() - half, cx));
                c.drawText(texts[i], x, r.top - 6 * d, value);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (values.length == 0) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_MOVE
                || e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int i = (int) Math.max(0, Math.min(values.length - 1, e.getX() / (getWidth() / (float) values.length)));
            if (i != selected) {
                selected = i;
                invalidate();
                if (onSelect != null) onSelect.on(i);
            }
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) getParent().requestDisallowInterceptTouchEvent(false);
        return true;
    }
}
