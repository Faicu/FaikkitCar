package ro.faicu.vwwelcome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trimite jurnalul la car.faicu.ro (fila Jurnal), ca sa poata fi citit de la distanta.
 * Liniile stau in coada din Prefs pana primim 200; dupa trezire internetul revine abia
 * dupa cateva secunde, deci trimiterea se reia la revenirea retelei si periodic din tick.
 * Aici nu scriem cu Prefs.log: fiecare linie noua ar intra din nou in coada.
 */
final class Uploader {
    private static final String URL_LOG = "https://car.faicu.ro/api/car/log";
    private static final String URL_TRIP = "https://car.faicu.ro/api/car/trip";
    private static final int POINT_BATCH = 300;
    private static final int BATCH = 100;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean running = new AtomicBoolean();

    private Uploader() {}

    static boolean configured() {
        return !BuildConfig.VW_LOG_TOKEN.isEmpty();
    }

    /** Porneste trimiterea in fundal, daca nu ruleaza deja una. */
    static void kick(Context ctx) {
        if (!configured() || !Prefs.uploadEnabled(ctx)) return;
        if (!running.compareAndSet(false, true)) return;
        Context c = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                flush(c);
            } finally {
                running.set(false);
            }
        });
    }

    private static void flush(Context c) {
        while (true) {
            JSONArray batch = Prefs.peekOutbox(c, BATCH);
            if (batch.length() == 0) break;
            String error = post(URL_LOG, "lines", batch);
            if (error != null) {
                Prefs.setUploadStatus(c, "eroare: " + error);
                return;
            }
            Prefs.dropOutbox(c, batch.length());
            Prefs.setUploadStatus(c, "ok");
        }
        // Punctele de traseu (TripRecorder), pe ruta lor.
        while (true) {
            JSONArray batch = PointQueue.peek(c, POINT_BATCH);
            if (batch.length() == 0) break;
            String error = post(URL_TRIP, "points", batch);
            if (error != null) {
                Prefs.setUploadStatus(c, "eroare traseu: " + error);
                return;
            }
            PointQueue.drop(c, batch.length());
        }
        // Avem internet: reimprospatam si starea (mentenanta, versiunea noua).
        VwStatus.refreshIfOld(c);
    }

    /** Intoarce null la succes, altfel motivul. */
    private static String post(String url, String key, JSONArray items) {
        HttpURLConnection conn = null;
        try {
            byte[] body = new JSONObject()
                    .put("version", BuildConfig.VERSION_NAME)
                    .put(key, items)
                    .toString().getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            return code == 200 ? null : "HTTP " + code;
        } catch (Exception e) {
            Log.w("FaikkitCar", "Trimitere jurnal esuata", e);
            return e.getClass().getSimpleName();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
