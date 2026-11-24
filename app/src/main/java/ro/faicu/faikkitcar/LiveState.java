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
 * contactul, la 60 s fara, si imediat la o schimbare (oprita / contact / motor / mers). Fara starea la
 * fiecare secunda cat se uita cineva (02.11–25.11): cu muzica pornita umfla procesul Android al navigatiei.
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

    private static long lastSentAt;

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
        long every = "off".equals(kind) ? IDLE_MS : "contact".equals(kind) ? CONTACT_MS : ENGINE_MS;
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
                // Comutatorul „OBD (teste)” din site / Panel / Setari (lipsa = pornit).
                if (resp != null) Obd.setEnabled(resp.optBoolean("obd", true));
                // Defectele de spus vocal (clapeta climatronicului, 23.11); lipsa = niciunul.
                if (resp != null) setAlerts(resp.optJSONArray("alerts"));
            } finally {
                busy.set(false);
            }
        });
    }

    /** Un defect de spus vocal: id-ul (acelasi cat dureaza defectul) si textul. */
    static final class Alert {
        final String id, say;

        Alert(String id, String say) {
            this.id = id;
            this.say = say;
        }
    }

    private static volatile java.util.List<Alert> alerts = java.util.Collections.emptyList();

    static java.util.List<Alert> alerts() {
        return alerts;
    }

    private static void setAlerts(org.json.JSONArray a) {
        java.util.List<Alert> list = new java.util.ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("id").isEmpty() || o.optString("say").isEmpty()) continue;
            list.add(new Alert(o.optString("id"), o.optString("say")));
        }
        alerts = list;
    }

    /** Trimite starea; intoarce raspunsul serverului (cmd, apk) sau null. */
    private static JSONObject post(JSONObject state) {
        HttpURLConnection conn = null;
        try {
            state.put("u", UnitStats.sample()); // performanta navigatiei (Panel, site)
            org.json.JSONObject obd = Obd.live(); // adaptorul OBD: starea, codurile de eroare
            if (obd != null) state.put("o", obd);
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
