package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Bara de derulare si de volum, desenata din cod: pista subtire rotunjita, partea plina in
 * culoarea accentului si o bulina. Cu `showValue`, valoarea e scrisa in bulina (volumul).
 * Se trage oriunde pe bara; parintele (ScrollView) nu mai preia gestul cat tragi.
 */
final class Slider extends View {
    interface Listener {
        /** done = degetul ridicat (valoarea finala). */
        void onChange(int value, boolean done);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float density;
    private final boolean showValue;
    private int max = 100, value;
    private boolean dragging, enabled = true;
    Listener listener;

    Slider(Context c, boolean showValue) {
        super(c);
        this.showValue = showValue;
        density = c.getResources().getDisplayMetrics().density;
        track.setColor(Ui.LINE);
        fill.setColor(Ui.ACCENT);
        thumb.setColor(Ui.ACCENT);
        label.setColor(Ui.BG);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(12 * c.getResources().getDisplayMetrics().scaledDensity);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    }

    void setMax(int max) {
        this.max = Math.max(1, max);
        invalidate();
    }

    /** Valoarea de la server; ignorata cat tragi. */
    void setValue(int v) {
        if (dragging) return;
        value = Math.max(0, Math.min(max, v));
        invalidate();
    }

    int value() {
        return value;
    }

    boolean dragging() {
        return dragging;
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        enabled = on;
        fill.setColor(on ? Ui.ACCENT : Ui.MUTED);
        thumb.setColor(on ? Ui.ACCENT : Ui.MUTED);
        invalidate();
    }

    private float thumbR() {
        return (showValue ? 14 : dragging ? 8 : 6) * density;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round((showValue ? 36 : 28) * density));
    }

    @Override
    protected void onDraw(Canvas c) {
        float inset = 14 * density; // aceeasi pentru ambele bare, ca sa fie aliniate
        float left = inset, right = getWidth() - inset, cy = getHeight() / 2f;
        float th = (showValue ? 6 : 4) * density;
        float x = left + (right - left) * value / max;
        r.set(left, cy - th / 2, right, cy + th / 2);
        c.drawRoundRect(r, th / 2, th / 2, track);
        r.set(left, cy - th / 2, x, cy + th / 2);
        c.drawRoundRect(r, th / 2, th / 2, fill);
        float tr = thumbR();
        c.drawCircle(x, cy, tr, thumb);
        if (showValue) c.drawText(String.valueOf(value), x, cy - (label.descent() + label.ascent()) / 2, label);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!enabled) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                move(e.getX(), false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                move(e.getX(), true);
                dragging = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void move(float x, boolean done) {
        float inset = 14 * density;
        float k = (x - inset) / Math.max(1, getWidth() - 2 * inset);
        int v = Math.round(Math.max(0, Math.min(1, k)) * max);
        boolean changed = v != value;
        value = v;
        invalidate();
        if (listener != null && (changed || done)) listener.onChange(v, done);
    }
}
