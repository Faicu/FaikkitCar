package ro.faicu.faikkitcar;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starea de acum (contact, turatie, viteza, rezervor...) pentru fila „Acum” din Panel si de pe
 * site: trimisa la car.faicu.ro la fiecare esantion (5 s) cu motorul pornit, la 15 s doar cu
 * contactul, la 60 s fara, si imediat la o schimbare (oprita / contact / motor / mers). Cat cineva
 * se uita (serverul raspunde `fast`), la fiecare secunda, din TripRecorder.liveTick.
 * Conexiunea ramane deschisa intre trimiteri (keep-alive), deci fiecare costa putin. Nu intra
 * in coada: o stare veche nu mai conteaza, deci la eroare se pierde si o inlocuieste urmatoarea.
 */
final class LiveState {
    private static final String URL_STATE = VwStatus.BASE + "/api/car/state";
    private static final long ENGINE_MS = 4_500; // practic la fiecare esantion de 5 s
    private static final long CONTACT_MS = 15_000;
    private static final long IDLE_MS = 60_000;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean busy = new AtomicBoolean();

    private static final long FAST_MS = 900; // cat cineva se uita (Panel, site): la fiecare secunda
    private static long lastSentAt;
    // Pana cand trimitem des: serverul spune `fast` cat cineva urmareste starea live.
    private static volatile long fastUntil;

    /** Cineva se uita acum la starea live (ultimul raspuns al serverului, cel mult 20 s in urma). */
    static boolean fast() {
        return SystemClock.elapsedRealtime() < fastUntil;
    }
    private static String lastKind = "";
    private static String lastDetails = "";

    private LiveState() {}

    /**
     * Apelat la fiecare esantion din TripRecorder (5 s); trimite doar cand e cazul. `details`
     * (usi, frana de mana, clima...) schimbate = trimitere imediata, ca ultima stare dinainte
     * de luarea contactului sa fie cea reala (ex. frana trasa chiar inainte).
     */
    static void maybeSend(Context c, String kind, String details, JSONObject state) {
        if (!Uploader.configured()) return;
        long now = SystemClock.elapsedRealtime();
        long every = "off".equals(kind) ? IDLE_MS : fast() ? FAST_MS : "contact".equals(kind) ? CONTACT_MS : ENGINE_MS;
        if (kind.equals(lastKind) && details.equals(lastDetails) && now - lastSentAt < every) return;
        if (!busy.compareAndSet(false, true)) return;
        lastKind = kind;
        lastDetails = details;
        lastSentAt = now;
        EXEC.execute(() -> {
            try {
                JSONObject resp = post(state);
                if (resp != null && resp.optInt("cmd", 0) > 0) RemoteCmd.poll(c);
                if (resp != null) Updater.onServerVersion(c, resp.optInt("apk", 0));
                if (resp != null) fastUntil = resp.optBoolean("fast") ? SystemClock.elapsedRealtime() + 20_000 : 0;
            } finally {
                busy.set(false);
            }
        });
    }

    /** Trimite starea; intoarce raspunsul serverului (cmd, apk) sau null. */
    private static JSONObject post(JSONObject state) {
        HttpURLConnection conn = null;
        try {
            state.put("u", UnitStats.sample()); // performanta navigatiei (Panel, site)
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
            // Raspunsul citit pana la capat si inchis (fara disconnect): conexiunea ramane in
            // pool si urmatoarea trimitere nu mai reface TLS-ul.
            conn.getResponseCode();
            java.io.ByteArrayOutputStream resp = new java.io.ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[512];
                int n;
                while ((n = in.read(buf)) > 0) if (resp.size() < 64 * 1024) resp.write(buf, 0, n);
            }
            return new JSONObject(resp.toString("UTF-8"));
        } catch (Exception e) {
            Log.w("FaikkitCar", "Trimitere stare esuata", e);
            if (conn != null) conn.disconnect();
            return null;
        }
    }
}
