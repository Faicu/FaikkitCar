package ro.faicu.vwwelcome;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starea de acum (contact, turatie, viteza, rezervor...) pentru fila „Acum” din Panel si de pe
 * site: trimisa la car.faicu.ro la 15 s cu contactul pus, la 60 s fara, si imediat la o
 * schimbare (oprita / contact / motor / mers). Nu intra in coada: o stare veche nu mai
 * conteaza, deci la eroare se pierde si o inlocuieste urmatoarea.
 */
final class LiveState {
    private static final String URL_STATE = "https://car.faicu.ro/api/car/state";
    private static final long ACTIVE_MS = 15_000;
    private static final long IDLE_MS = 60_000;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean busy = new AtomicBoolean();

    private static long lastSentAt;
    private static String lastKind = "";

    private LiveState() {}

    /** Apelat la fiecare esantion din TripRecorder (5 s); trimite doar cand e cazul. */
    static void maybeSend(String kind, JSONObject state) {
        if (!Uploader.configured()) return;
        long now = SystemClock.elapsedRealtime();
        long every = "off".equals(kind) ? IDLE_MS : ACTIVE_MS;
        if (kind.equals(lastKind) && now - lastSentAt < every) return;
        if (!busy.compareAndSet(false, true)) return;
        lastKind = kind;
        lastSentAt = now;
        EXEC.execute(() -> {
            try {
                post(state);
            } finally {
                busy.set(false);
            }
        });
    }

    private static void post(JSONObject state) {
        HttpURLConnection conn = null;
        try {
            byte[] body = state.put("version", BuildConfig.VERSION_NAME).toString().getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(URL_STATE).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            conn.getResponseCode();
        } catch (Exception e) {
            Log.w("FaikkitCar", "Trimitere stare esuata", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
