package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.json.JSONArray;

/**
 * Miniatura unui traseu (lista de calatorii): doar forma drumului, fara harta dedesubt, cu
 * plecarea verde si sosirea albastra, ca pe hartile mari. Proportiile reale (longitudinea
 * scalata cu cos(latitudine)), centrata in casuta.
 */
final class RouteThumb extends View {
    private final double[] lat, lon;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float d;

    RouteThumb(Context c, JSONArray shape) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        int n = shape == null ? 0 : shape.length();
        lat = new double[n];
        lon = new double[n];
        for (int i = 0; i < n; i++) {
            JSONArray p = shape.optJSONArray(i);
            lat[i] = p.optDouble(0);
            lon[i] = p.optDouble(1);
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.2f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(Ui.ACCENT);
        setBackground(Ui.round(c, Ui.CARD, 12));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (lat.length < 2) return;
        double k = Math.cos(Math.toRadians(lat[0]));
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < lat.length; i++) {
            double x = lon[i] * k, y = lat[i];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        float pad = 9 * d, w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
        // Un drum foarte scurt nu se umfla pana la marginile casutei.
        double span = Math.max(Math.max(maxX - minX, maxY - minY), 0.004);
        double s = Math.min(w, h) / span;
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        path.reset();
        for (int i = 0; i < lat.length; i++) {
            float x = (float) (getWidth() / 2f + (lon[i] * k - cx) * s);
            float y = (float) (getHeight() / 2f - (lat[i] - cy) * s);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, line);
        dot(c, 0, Ui.OK, cx, cy, k, s);
        dot(c, lat.length - 1, Ui.ACCENT, cx, cy, k, s);
    }

    private void dot(Canvas c, int i, int color, double cx, double cy, double k, double s) {
        float x = (float) (getWidth() / 2f + (lon[i] * k - cx) * s);
        float y = (float) (getHeight() / 2f - (lat[i] - cy) * s);
        dot.setColor(Ui.CARD);
        c.drawCircle(x, y, 4.5f * d, dot);
        dot.setColor(color);
        c.drawCircle(x, y, 3.2f * d, dot);
    }
}
