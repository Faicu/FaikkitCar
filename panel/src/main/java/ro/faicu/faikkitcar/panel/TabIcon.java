package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Iconitele filelor de jos, desenate din cod (fara resurse): linii simple, in culoarea filei. */
final class TabIcon extends View {
    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    TabIcon(Context c, int kind) {
        super(c);
        this.kind = kind;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int color) {
        p.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
        c.save();
        c.translate(ox, oy);
        c.scale(s / 24f, s / 24f); // desenat pe o grila de 24 x 24
        p.setStrokeWidth(2f);
        p.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (kind) {
            case 0: // Acum: semnal live (punct cu doua arce)
                fillCircle(c, 12, 12, 2.6f);
                c.drawArc(arc(12, 12, 6), -45, 90, false, p);
                c.drawArc(arc(12, 12, 6), 135, 90, false, p);
                c.drawArc(arc(12, 12, 10), -40, 80, false, p);
                c.drawArc(arc(12, 12, 10), 140, 80, false, p);
                break;
            case 1: // Calatorii: traseu intre doua puncte
                path.moveTo(6, 18);
                path.cubicTo(6, 11, 18, 13, 18, 6);
                c.drawPath(path, p);
                fillCircle(c, 6, 19, 2.4f);
                c.drawCircle(18, 5, 2.4f, p);
                break;
            case 2: // Costuri: pompa de benzina
                r.set(4, 4, 13, 20);
                c.drawRoundRect(r, 1.5f, 1.5f, p);
                c.drawLine(6.5f, 9, 10.5f, 9, p);
                path.moveTo(13, 11);
                path.lineTo(17, 11);
                path.lineTo(17, 17);
                path.quadTo(17, 19, 18.5f, 19);
                path.quadTo(20, 19, 20, 17);
                path.lineTo(20, 9);
                path.lineTo(17.5f, 6);
                c.drawPath(path, p);
                break;
            case 3: // Navigatie: ecranul unitatii
                r.set(3, 5, 21, 17);
                c.drawRoundRect(r, 2, 2, p);
                c.drawLine(9, 20, 15, 20, p);
                path.moveTo(8, 13.5f);
                path.lineTo(11, 10.5f);
                path.lineTo(13, 12.5f);
                path.lineTo(16.5f, 8.5f);
                c.drawPath(path, p);
                break;
            default: // Mai mult: trei puncte
                fillCircle(c, 5, 12, 1.8f);
                fillCircle(c, 12, 12, 1.8f);
                fillCircle(c, 19, 12, 1.8f);
                break;
        }
        c.restore();
    }

    private RectF arc(float cx, float cy, float rad) {
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        return r;
    }

    private void fillCircle(Canvas c, float x, float y, float rad) {
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, rad, p);
        p.setStyle(Paint.Style.STROKE);
    }
}
