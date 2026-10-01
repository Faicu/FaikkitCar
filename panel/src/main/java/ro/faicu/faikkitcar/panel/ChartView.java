package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/** Viteza (albastru) si turatia (portocaliu, scara proprie) in timp, ca graficul de pe site. */
final class ChartView extends View {
    private final long[] t;
    private final double[] speed, rpm;
    private final double maxSpeed, maxRpm;
    private final Paint speedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rpmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ChartView(Context c, JSONArray points) {
        super(c);
        int n = points.length();
        t = new long[n];
        speed = new double[n];
        rpm = new double[n];
        double ms = 30, mr = 1000;
        for (int i = 0; i < n; i++) {
            JSONObject p = points.optJSONObject(i);
            t[i] = Fmt.millis(p.optString("t"));
            speed[i] = p.isNull("speed") ? Double.NaN : p.optDouble("speed");
            rpm[i] = p.isNull("rpm") ? Double.NaN : p.optDouble("rpm");
            if (!Double.isNaN(speed[i])) ms = Math.max(ms, speed[i]);
            if (!Double.isNaN(rpm[i])) mr = Math.max(mr, rpm[i]);
        }
        maxSpeed = ms;
        maxRpm = mr;
        speedPaint.setColor(Ui.ACCENT);
        speedPaint.setStyle(Paint.Style.STROKE);
        speedPaint.setStrokeWidth(Ui.dp(c, 2));
        rpmPaint.setColor(0xB3FB923C);
        rpmPaint.setStyle(Paint.Style.STROKE);
        rpmPaint.setStrokeWidth(Ui.dp(c, 1.5f));
    }

    double maxSpeed() {
        return maxSpeed;
    }

    double maxRpm() {
        return maxRpm;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (t.length < 2) return;
        float w = getWidth(), h = getHeight();
        long span = Math.max(1, t[t.length - 1] - t[0]);
        canvas.drawPath(path(rpm, maxRpm, w, h, span), rpmPaint);
        canvas.drawPath(path(speed, maxSpeed, w, h, span), speedPaint);
    }

    private Path path(double[] v, double max, float w, float h, long span) {
        Path p = new Path();
        boolean started = false;
        for (int i = 0; i < t.length; i++) {
            if (Double.isNaN(v[i])) continue;
            float x = (float) ((t[i] - t[0]) * (double) w / span);
            float y = (float) (h - v[i] / max * h);
            if (started) p.lineTo(x, y);
            else p.moveTo(x, y);
            started = true;
        }
        return p;
    }
}
