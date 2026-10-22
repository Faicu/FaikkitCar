package ro.faicu.faikkitcar;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Comenzi shell de pe car.faicu.ro, pentru teste si diagnostic (ce nu prinde jurnalul): doar cu
 * comutatorul din Setari pornit (implicit oprit). Serverul spune in raspunsul la starea live
 * cate asteapta; le luam, le rulam cu `sh -c` cu drepturile aplicatiei (fara root), una cate
 * una, cu limita de timp si de iesire, si trimitem rezultatul. Fiecare comanda intra si in jurnal.
 */
final class RemoteCmd {
    private static final String BASE = "https://car.faicu.ro/api/car/cmd";
    private static final int MAX_OUTPUT = 256 * 1024;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean busy = new AtomicBoolean();

    private RemoteCmd() {}

    /** Apelat cand serverul anunta comenzi in asteptare. */
    static void poll(Context c) {
        if (!Prefs.remoteCmdEnabled(c) || !busy.compareAndSet(false, true)) return;
        Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            try {
                JSONArray cmds = new JSONArray(request("GET", BASE, null));
                for (int i = 0; i < cmds.length(); i++) {
                    JSONObject o = cmds.getJSONObject(i);
                    run(app, o.getInt("id"), o.getString("cmd"), o.optInt("timeoutS", 30));
                }
            } catch (Exception e) {
                Log.w("FaikkitCar", "Comenzi: eroare", e);
            } finally {
                busy.set(false);
            }
        });
    }

    private static void run(Context c, int id, String cmd, int timeoutS) throws Exception {
        // Nu intra in jurnal (din 1.1.59): comanda, codul si rezultatul raman pe server
        // (car_cmd), in jurnalul separat „Comenzi la distanta” de pe site si din Panel.
        int code;
        String output;
        Process p = null;
        if (cmd.trim().equals("@bluetooth")) {
            // Comanda interna: testul Bluetooth cere API-ul Android, nu se poate din shell.
            request("POST", BASE + "/" + id,
                    new JSONObject().put("exitCode", 0).put("output", BleProbe.run(c)).toString());
            return;
        }
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            Process proc = p;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        synchronized (buf) {
                            if (buf.size() < MAX_OUTPUT) buf.write(b, 0, Math.min(n, MAX_OUTPUT - buf.size()));
                        }
                    }
                } catch (Exception ignored) {
                    // procesul a fost oprit
                }
            });
            reader.start();
            boolean done = p.waitFor(timeoutS, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            reader.join(2_000);
            boolean cut;
            synchronized (buf) {
                output = buf.toString("UTF-8");
                cut = buf.size() >= MAX_OUTPUT;
            }
            if (cut) output += "\n[iesire taiata la " + MAX_OUTPUT / 1024 + " KB]";
            if (!done) output += "\n[oprita dupa " + timeoutS + " s]";
            code = done ? p.exitValue() : -1;
        } catch (Exception e) {
            code = -2;
            output = "eroare: " + e;
            if (p != null) p.destroyForcibly();
        }
        request("POST", BASE + "/" + id,
                new JSONObject().put("exitCode", code).put("output", output).toString());
    }

    private static String request(String method, String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
            if (body != null) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(b.length);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(b);
                }
            }
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }
}
