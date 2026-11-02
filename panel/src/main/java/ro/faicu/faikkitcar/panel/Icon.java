package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Iconitele aplicatiei, desenate din cod (fara resurse) pe o grila de 24 x 24: filele de jos si
 * controalele muzicii. Linii simple sau forme pline, intr-o singura culoare.
 */
final class Icon extends View {
    // 0–4 = filele (in ordinea din MainActivity.TABS)
    static final int SEARCH = 5, PREV = 6, NEXT = 7, PLAY = 8, PAUSE = 9, VOL_LOW = 10, VOL_HIGH = 11,
            REFRESH = 12, CLOSE = 13, PIN = 14, MORE = 4, FUEL = 2;

    private int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Icon(Context c, int kind) {
        super(c);
        this.kind = kind;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setKind(int kind) {
        if (this.kind == kind) return;
        this.kind = kind;
        invalidate();
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
            case SEARCH: // lupa
                c.drawCircle(10.5f, 10.5f, 6, p);
                c.drawLine(15, 15, 20, 20, p);
                break;
            case PREV:
                fillPath(c, new float[] {18, 6, 18, 18, 9, 12});
                p.setStrokeWidth(2.4f);
                c.drawLine(7, 6.5f, 7, 17.5f, p);
                break;
            case NEXT:
                fillPath(c, new float[] {6, 6, 6, 18, 15, 12});
                p.setStrokeWidth(2.4f);
                c.drawLine(17, 6.5f, 17, 17.5f, p);
                break;
            case PLAY:
                fillPath(c, new float[] {8, 5, 8, 19, 19, 12});
                break;
            case PAUSE:
                p.setStyle(Paint.Style.FILL);
                r.set(6.5f, 5, 10.5f, 19);
                c.drawRoundRect(r, 1, 1, p);
                r.set(13.5f, 5, 17.5f, 19);
                c.drawRoundRect(r, 1, 1, p);
                p.setStyle(Paint.Style.STROKE);
                break;
            case VOL_LOW:
            case VOL_HIGH: // difuzor, cu una sau doua unde
                fillPath(c, new float[] {3, 9.5f, 3, 14.5f, 7, 14.5f, 12, 19, 12, 5, 7, 9.5f});
                c.drawArc(arc(12, 12, 3.5f), -45, 90, false, p);
                if (kind == VOL_HIGH) c.drawArc(arc(12, 12, 7.5f), -50, 100, false, p);
                break;
            case REFRESH: // sageata in cerc
                c.drawArc(arc(12, 12, 7.5f), -60, 300, false, p);
                fillPath(c, new float[] {15.5f, 2.5f, 20.5f, 5.6f, 15.8f, 8.6f});
                break;
            case PIN: // locul pe harta
                path.reset();
                path.moveTo(12, 21);
                path.cubicTo(7, 15.5f, 5, 12.5f, 5, 9.5f);
                path.cubicTo(5, 5.5f, 8.2f, 3, 12, 3);
                path.cubicTo(15.8f, 3, 19, 5.5f, 19, 9.5f);
                path.cubicTo(19, 12.5f, 17, 15.5f, 12, 21);
                path.close();
                c.drawPath(path, p);
                c.drawCircle(12, 9.5f, 2.5f, p);
                break;
            case CLOSE:
                c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, p);
                c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, p);
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

    /** Poligon plin cu colturi usor rotunjite (perechi x, y). */
    private void fillPath(Canvas c, float[] xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.close();
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeWidth(1.2f);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
    }

    private void fillCircle(Canvas c, float x, float y, float rad) {
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, rad, p);
        p.setStyle(Paint.Style.STROKE);
    }
}
