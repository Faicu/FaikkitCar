package ro.faicu.vwwelcome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Starea de pe car.faicu.ro (/api/car/status): kilometrajul, mentenanta, ultimul APK si
 * locurile salvate.
 * Citita de Uploader cel mult o data la 30 de minute (si la cerere din aplicatie) si pastrata
 * in Prefs, ca ecranul si salutul vorbit sa o aiba si fara internet.
 */
final class VwStatus {
    static final String BASE = "https://car.faicu.ro";
    private static final long MAX_AGE_MS = 30 * 60_000L;

    private VwStatus() {}

    /** Reincarca daca e mai veche de 30 de minute; ruleaza pe firul Uploader-ului. */
    static void refreshIfOld(Context c) {
        if (System.currentTimeMillis() - Prefs.statusAt(c) < MAX_AGE_MS) return;
        refresh(c);
    }

    static boolean refresh(Context c) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/api/car/status").openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
            try {
                if (conn.getResponseCode() != 200) return false;
                String body = read(conn.getInputStream());
                new JSONObject(body); // valid
                Prefs.setStatus(c, body);
                return true;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    static String read(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static JSONObject json(Context c) {
        try {
            return new JSONObject(Prefs.status(c));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Versiunea APK de pe server, daca e mai noua decat cea instalata; altfel null. */
    static JSONObject newerApk(Context c) {
        JSONObject apk = json(c).optJSONObject("apk");
        if (apk == null || apk.optInt("versionCode") <= BuildConfig.VERSION_CODE) return null;
        return apk;
    }

    static final class Reminder {
        final String title;
        final Integer kmLeft, daysLeft;
        final boolean soon, overdue;

        Reminder(JSONObject o) {
            title = o.optString("title");
            kmLeft = o.isNull("kmLeft") ? null : o.optInt("kmLeft");
            daysLeft = o.isNull("daysLeft") ? null : o.optInt("daysLeft");
            soon = o.optBoolean("soon");
            overdue = o.optBoolean("overdue");
        }

        /** Text scurt pentru ecran: "peste 800 km · 12 zile". */
        String describe() {
            List<String> parts = new ArrayList<>();
            if (kmLeft != null) parts.add(kmLeft <= 0 ? "depasit cu " + (-kmLeft) + " km" : "peste " + kmLeft + " km");
            if (daysLeft != null) parts.add(daysLeft <= 0 ? "expirat" : daysLeft + " zile");
            return String.join(" · ", parts);
        }
    }

    // Locurile salvate (Acasa, Serviciu...), din aceeasi stare; refacute doar cand se schimba.
    private static String placesFrom;
    private static JSONArray places = new JSONArray();

    /** Locul salvat in a carui raza e pozitia, sau null. */
    static synchronized String placeAt(Context c, double lat, double lon) {
        String status = Prefs.status(c);
        if (!status.equals(placesFrom)) {
            placesFrom = status;
            JSONArray p = json(c).optJSONArray("places");
            places = p == null ? new JSONArray() : p;
        }
        float[] d = new float[1];
        String best = null;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < places.length(); i++) {
            JSONObject p = places.optJSONObject(i);
            android.location.Location.distanceBetween(lat, lon, p.optDouble("lat"), p.optDouble("lon"), d);
            if (d[0] <= p.optDouble("radius", 150) && d[0] < bestD) {
                best = p.optString("name");
                bestD = d[0];
            }
        }
        return best;
    }

    static List<Reminder> reminders(Context c) {
        List<Reminder> out = new ArrayList<>();
        JSONArray arr = json(c).optJSONArray("reminders");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(new Reminder(o));
        }
        return out;
    }
}
