package ro.faicu.faikkitcar.panel;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * API-ul car.faicu.ro, acelasi pe care il foloseste site-ul. Login-ul da un token (sesiune
 * de 180 de zile), trimis apoi ca "Authorization: Bearer". Apelurile sunt blocante: se fac
 * de pe un fir de fundal.
 */
final class Api {
    static final String BASE = "https://car.faicu.ro";

    /** Sesiune lipsa sau expirata: aplicatia arata ecranul de login. */
    static final class Unauthorized extends Exception {}

    private final Context c;

    Api(Context c) {
        this.c = c.getApplicationContext();
    }

    /** Intoarce null daca a reusit, altfel mesajul de eroare de la server. */
    String login(String user, String pass) {
        try {
            JSONObject r = new JSONObject(call("POST", "/api/login",
                    new JSONObject().put("user", user).put("pass", pass), false));
            Store.setToken(c, r.getString("token"));
            return null;
        } catch (Unauthorized e) {
            return "Utilizator sau parolă greșită";
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Cu `after` (ultimul „at”), serverul raspunde abia la o stare noua (cel mult 25 s). */
    JSONObject live(String after) throws Exception {
        String path = after == null ? "/api/live" : "/api/live?after=" + enc(after);
        return new JSONObject(call("GET", path, null, true, 40_000));
    }

    JSONArray stats() throws Exception {
        return new JSONArray(call("GET", "/api/stats", null, true));
    }

    JSONArray trips() throws Exception {
        return new JSONArray(call("GET", "/api/trips", null, true));
    }

    JSONArray tripPoints(String start, String end) throws Exception {
        return new JSONArray(call("GET", "/api/trips/points?start=" + enc(start) + "&end=" + enc(end), null, true));
    }

    JSONObject car() throws Exception {
        return new JSONObject(call("GET", "/api/car", null, true));
    }

    JSONObject fuel() throws Exception {
        return new JSONObject(call("GET", "/api/fuel", null, true));
    }

    JSONArray log(boolean eventsOnly) throws Exception {
        return new JSONArray(call("GET", "/api/log?eventsOnly=" + (eventsOnly ? 1 : 0), null, true));
    }

    void saveRefuel(JSONObject r) throws Exception {
        call("POST", "/api/refuels", r, true);
    }

    void deleteRefuel(int id) throws Exception {
        call("DELETE", "/api/refuels/" + id, null, true);
    }

    void saveReminder(JSONObject r) throws Exception {
        call("POST", "/api/reminders", r, true);
    }

    void deleteReminder(int id) throws Exception {
        call("DELETE", "/api/reminders/" + id, null, true);
    }

    void joinTrips(String start, String end) throws Exception {
        call("POST", "/api/trips/join", new JSONObject().put("start", start).put("end", end), true);
    }

    void splitTrip(String start) throws Exception {
        call("POST", "/api/trips/split", new JSONObject().put("start", start), true);
    }

    void reminderDone(int id) throws Exception {
        call("POST", "/api/reminders/" + id + "/done", null, true);
    }

    JSONObject panelApk() throws Exception {
        return new JSONObject(call("GET", "/api/panel/apk", null, true));
    }

    /** Conexiunea pentru descarcarea APK-ului Panel (Updater o citeste in flux). */
    HttpURLConnection openApkDownload() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/api/panel/apk/download").openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Authorization", "Bearer " + Store.token(c));
        return conn;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String call(String method, String path, JSONObject body, boolean auth) throws Exception {
        return call(method, path, body, auth, 20_000);
    }

    private String call(String method, String path, JSONObject body, boolean auth, int readTimeout) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(readTimeout);
            conn.setRequestMethod(method);
            if (auth) conn.setRequestProperty("Authorization", "Bearer " + Store.token(c));
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            if (code == 401) throw new Unauthorized();
            String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                String msg = "Eroare " + code;
                try {
                    msg = new JSONObject(text).optString("error", msg);
                } catch (Exception ignored) {
                }
                throw new Exception(msg);
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
