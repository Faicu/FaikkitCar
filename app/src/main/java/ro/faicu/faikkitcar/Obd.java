package ro.faicu.faikkitcar;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Adaptorul OBD2 BLE (vLinker MC+, 20.11), fara niciun comutator: cu motorul pornit (turatia de pe
 * CAN) ne conectam singuri la adresa stiuta, cu motorul oprit de 60 s il eliberam (o singura
 * conexiune BLE: apoi il poate lua Car Scanner). Daca adaptorul nu e in masina, doar reincercam rar.
 * Citim turatia, presiunea din galerie, aerul admis, starea injectiei si pedala de cateva ori pe
 * secunda (o singura cerere cu mai multe PID-uri), restul la 2 s. Motorul (Simos 10) nu da nici
 * debitul de aer, nici consumul, deci consumul vine din aerul aspirat (densitatea aerului), cu
 * randamentul de umplere fix: serverul il calibreaza pe bonurile de alimentare. Litrii se aduna
 * pe fiecare punct de traseu (`take`).
 */
final class Obd extends BluetoothGattCallback {
    private static final String TAG = "FaikkitCar";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb");
    /** Adresa adaptorului din primul test (20.11), pana il gaseste si salveaza testul. */
    private static final String DEFAULT_ADDR = "C5:57:46:DD:88:83";
    private static final long RELEASE_MS = 60_000;
    private static final long SLOW_MS = 2_000;
    private static final long STATUS_MS = 5 * 60_000;
    /** Incercari fara niciun raspuns de la stiva (~1,5 min) dupa care verificam daca e blocata. */
    private static final int STUCK_FAILURES = 4;
    private static final long UNSTICK_EVERY_MS = 30 * 60_000;
    /** Setarile noastre; confirmarea cadrelor manuala (si pentru celelalte module, la scanare). */
    private static final String[] INIT = {"ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATSH7E0", "ATCRA",
            "ATFCSH7E0", "ATFCSD300000", "ATFCSM1"};

    // Motorul CBZB: 1197 cm3. Benzina: 745 g/L, stoechiometric 14,7.
    private static final double DISPLACEMENT_M3 = 0.001197;
    private static final double VE = 0.85;
    private static final double AFR = 14.7;
    private static final double FUEL_G_PER_L = 745;

    private static Obd instance;

    private final Context c;
    private final Object lock = new Object();
    /** O singura conversatie cu adaptorul: ciclul de citire sau testul de la distanta. */
    private final Object io = new Object();
    private final StringBuilder rx = new StringBuilder();
    /** Ultima cerere a ramas fara raspuns la timp (poate veni intarziat). */
    private boolean late;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic wr;
    private volatile boolean connected;
    private boolean discovered;
    private boolean opDone;
    private int connStatus;
    volatile boolean probeRunning;

    private long engineOffAt;
    private long nextTryAt;
    private int failures;
    private boolean failLogged;
    private boolean multi = true;
    private int multiFails;
    private long multiRetryAt;
    private boolean multiLogged;
    private long lastSlowAt;
    private long lastStatusAt;
    private long connectedAt;
    /** Conectat si initializat (pentru linia din jurnal la deconectare). */
    private boolean session;
    private int cycles;

    // Ultimele valori (doar din firul OBD; citite din TripRecorder sub `this`).
    private int rpm = -1;
    private double map = Double.NaN;
    private double iat = Double.NaN;
    private double ect = Double.NaN;
    private double load = Double.NaN;
    private double lambda = Double.NaN;
    private double pedal = Double.NaN;
    private double pedalRest = Double.NaN;
    private int fuelStatus = -1;
    private double lh = Double.NaN;
    private long fastAt;
    private long lastSampleAt;
    private double liters;
    private double seconds;
    private double litersTotal;

    /**
     * Comutatorul „OBD (teste)”, pornit implicit, tinut pe server (site, Panel, Setari): oprit =
     * nicio legatura cu adaptorul (il poate lua Car Scanner), nicio citire, nicio scanare. Tot ce
     * face clasa asta e de test: calatoriile si restul aplicatiei nu depind de ea.
     */
    private volatile boolean enabled;
    private boolean disabledLogged;
    /** Pentru starea live: c = conectat, s = caut adaptorul, o = motor oprit, x = comutatorul oprit. */
    private volatile String status = "o";

    /** Comutatorul, de la server (raspunsul la starea live); tinut minte pentru pornirea fara retea. */
    static void setEnabled(boolean on) {
        Obd o = instance;
        if (o == null || o.enabled == on) return;
        o.enabled = on;
        o.c.getSharedPreferences("obd", Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
    }

    static boolean enabled() {
        Obd o = instance;
        return o != null && o.enabled;
    }

    /**
     * Butonul din Setari: comutatorul trece prin server (ca Panel-ul si site-ul), altfel urmatorul
     * raspuns la starea live l-ar intoarce. Din alt fir decat UI; true = ok.
     */
    static boolean requestEnabled(boolean on) {
        java.net.HttpURLConnection conn = null;
        try {
            byte[] body = ("{\"enabled\":" + on + "}").getBytes(StandardCharsets.UTF_8);
            conn = (java.net.HttpURLConnection) new java.net.URL(VwStatus.BASE + "/api/car/obd/enabled").openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
            try (java.io.OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            try (java.io.InputStream in = conn.getInputStream()) {
                while (in.read() >= 0) {
                    // citit pana la capat
                }
            }
            if (code != 200) return false;
            setEnabled(on);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "OBD: comutatorul nu s-a trimis", e);
            if (conn != null) conn.disconnect();
            return false;
        }
    }

    /** Pe scurt, pentru Setari: conectat / oprit / caut / motor oprit. */
    static String statusText() {
        Obd o = instance;
        if (o == null) return "neinceput";
        switch (o.status) {
            case "c": return "conectat, " + Math.round(o.rate * 10) / 10.0 + " citiri/s";
            case "x": return "oprit (adaptorul e liber)";
            case "s": return "caut adaptorul";
            default: return "motor oprit (adaptorul e liber)";
        }
    }

    /** Pentru starea live (`o`): starea, citirile pe secunda, codurile de eroare. */
    static org.json.JSONObject live() throws org.json.JSONException {
        Obd o = instance;
        if (o == null) return null;
        org.json.JSONObject j = new org.json.JSONObject().put("st", o.status)
                .put("hz", Math.round(o.rate * 10) / 10.0);
        if (!o.enabled) return j;
        if (o.udsAt > 0) {
            org.json.JSONObject m = new org.json.JSONObject();
            for (java.util.Map.Entry<String, String> e : o.udsDtc.entrySet()) m.put(e.getKey(), e.getValue());
            j.put("u", new org.json.JSONObject().put("at", o.udsAt).put("m", m));
        }
        if (o.dtcAt > 0) {
            j.put("d", new org.json.JSONObject().put("at", o.dtcAt).put("mil", o.dtcMil)
                    .put("n", o.dtcCount).put("s", o.dtcStored).put("p", o.dtcPending));
        }
        return j;
    }

    private Obd(Context c) {
        this.c = c.getApplicationContext();
        enabled = this.c.getSharedPreferences("obd", Context.MODE_PRIVATE).getBoolean("enabled", true);
    }

    static synchronized void start(Context c) {
        if (instance != null) return;
        instance = new Obd(c);
        Thread t = new Thread(instance::loop, "obd");
        t.setDaemon(true);
        t.start();
    }

    static Obd instance() {
        return instance;
    }

    boolean connected() {
        return connected;
    }

    /**
     * Pentru punctul de traseu: litrii si secundele citite de la punctul anterior (apoi zero) si
     * ultimele valori, daca sunt proaspete. null fara nimic citit.
     */
    static double[] take() {
        Obd o = instance;
        if (o == null || !o.enabled) return null;
        synchronized (o) {
            boolean fresh = SystemClock.elapsedRealtime() - o.fastAt < 5_000;
            if (o.seconds <= 0 && !fresh) return null;
            double[] r = {o.liters, o.seconds, fresh ? o.lh : Double.NaN, fresh ? o.map : Double.NaN,
                    fresh ? o.iat : Double.NaN, o.ect, o.load};
            o.liters = 0;
            o.seconds = 0;
            return r;
        }
    }

    private void loop() {
        for (;;) {
            try {
                if (!step()) SystemClock.sleep(1_000);
            } catch (Throwable t) {
                Log.w(TAG, "OBD: eroare", t);
                disconnect("eroare " + t.getClass().getSimpleName());
                SystemClock.sleep(5_000);
            }
        }
    }

    /** Un pas; true = a citit (bucla merge imediat mai departe). */
    private boolean step() throws InterruptedException {
        if (probeRunning) return false;
        long now = SystemClock.elapsedRealtime();
        // Comutatorul oprit (site, Panel, Setari): adaptorul ramane liber, nu citim nimic.
        if (!enabled) {
            if (!disabledLogged) {
                disabledLogged = true;
                Prefs.log(c, "OBD: oprit (comutatorul)");
            }
            if (connected || gatt != null) disconnect("comutatorul oprit");
            status = "x";
            job = null;
            return false;
        }
        if (disabledLogged) {
            disabledLogged = false;
            Prefs.log(c, "OBD: pornit (comutatorul)");
            nextTryAt = 0;
            failures = 0;
        }
        boolean engine = CanLink.get(c).rpm() > 300;
        if (engine) engineOffAt = 0;
        else if (engineOffAt == 0) engineOffAt = now;
        status = connected ? "c" : engine ? "s" : "o";
        if (!engine && (!connected || now - engineOffAt >= RELEASE_MS)) {
            if (connected) disconnect("motor oprit");
            if (gatt != null) closeGatt();
            failures = 0;
            silentFailures = 0;
            failLogged = false;
            nextTryAt = 0;
            return false;
        }
        if (!connected) {
            if (gatt != null) disconnect("legatura pierduta");
            if (now < nextTryAt) return false;
            if (connect() && init()) {
                failures = 0;
                silentFailures = 0;
                failLogged = false;
                return true;
            }
            disconnectQuiet();
            failures++;
            nextTryAt = SystemClock.elapsedRealtime() + Math.min(120_000, 10_000L * failures);
            if (noAnswer) silentFailures++;
            else silentFailures = 0;
            if (silentFailures >= STUCK_FAILURES) unstick();
            if (failures == 3 && !failLogged) {
                failLogged = true;
                Prefs.log(c, "OBD: adaptorul nu raspunde (cod " + connStatus + "), reincerc");
            }
            return false;
        }
        synchronized (io) {
            poll();
        }
        // Scanarea, cu motorul pornit: in mers bucati scurte (consumul ramane acoperit).
        double kmh = CanLink.get(c).speed();
        if (connected) scanBlock(Double.isNaN(kmh) || kmh >= 1);
        return true;
    }

    // --- scanarea (lucrarile de pe server, obd-scan.ts) -------------------------------------

    /**
     * Rezultatele si progresul scanarii, de trimis la server (Uploader). Pe disc, ca o repornire
     * sau o actualizare a aplicatiei sa nu piarda nimic din ce s-a citit.
     */
    static final JsonlQueue SCAN_QUEUE = new JsonlQueue("obd_scan.jsonl", 100_000);
    private static final String URL_SCAN = VwStatus.BASE + "/api/car/obd/scan";
    private static final long JOBS_MS = 60_000;
    /** Refuzul „lent / fara raspuns” (serverul: NRC_SLOW). */
    private static final int NRC_SLOW = 256;
    private static final long PROGRESS_MS = 15_000;

    /** O lucrare de la server: interval (range), lista (list) sau recitirea in bucla (loop). */
    private static final class Job {
        int id, round, tx, rx, first, last, next;
        String svc, kind, label;
        int[] ids;
        boolean parked;

        String key() {
            return id + ":" + round;
        }

        boolean range() {
            return kind.equals("range");
        }
    }

    private volatile java.util.List<Job> jobs = java.util.Collections.emptyList();
    private volatile long jobsAt;
    private volatile boolean jobsLoading;
    private volatile String jobsError;
    /** Terminate aici, inca necunoscute serverului (rezultatele asteapta in coada). */
    private final java.util.Set<String> finishedHere = new java.util.HashSet<>();
    /** Modulele care n-au raspuns in sesiunea asta: lucrarile lor asteapta reconectarea. */
    private final java.util.Set<Integer> silentModules = new java.util.HashSet<>();
    private Job job;
    /** range: urmatorul cod; list/loop: urmatorul index. */
    private int scanNext;
    /** Codurile unui grup de 3 care a raspuns: recitite pe rand, ca sa stim al cui e raspunsul. */
    private final java.util.ArrayDeque<Integer> scanPending = new java.util.ArrayDeque<>();
    private int pendingTries;
    private int scanSilent;
    /** Primul grup din sirul fara raspuns: de acolo se reia cand modulul tace de tot. */
    private int scanSilentFrom;
    private int scanFound;
    private long progressAt;
    private int progressSent = -1;

    /** `@obd scan` (sau `scan status`): lucrarile de la server, cea in curs, coada netrimisa. */
    String scanCommand(String args) {
        StringBuilder out = new StringBuilder();
        Job j = job;
        if (j != null) {
            out.append("In curs: #").append(j.id).append(" ").append(j.label).append(" (").append(hex(j.tx, 3))
                    .append(" ").append(j.svc).append(", ").append(j.kind).append(j.round > 0 ? " trecerea " + j.round : "")
                    .append(") la ").append(j.range() ? hex(scanNext, 4) : scanNext + "/" + j.ids.length)
                    .append(", gasite acum ").append(scanFound);
        } else {
            out.append("Nicio lucrare in curs");
        }
        out.append(connected ? ", legatura deschisa" : ", legatura inchisa (asteapta motorul)")
                .append("\nDe la server (acum ").append(jobsAt == 0 ? "niciodata" : (SystemClock.elapsedRealtime() - jobsAt) / 1000 + " s")
                .append(jobsError != null ? ", eroare " + jobsError : "").append("):");
        for (Job x : jobs) {
            boolean done;
            synchronized (finishedHere) {
                done = finishedHere.contains(x.key());
            }
            out.append("\n  #").append(x.id).append(" ").append(x.label).append(x.parked ? " (doar pe loc)" : "")
                    .append(done ? " [terminata aici]" : "")
                    .append(silentModules.contains(x.tx) ? " [modulul tace]" : "");
        }
        out.append("\nNetrimise: ").append(SCAN_QUEUE.size(c));
        return out.toString();
    }

    /** Lista de la server, intr-un fir separat (cererea nu tine pe loc citirile motorului). */
    private void refreshJobs() {
        if (jobsLoading) return;
        jobsLoading = true;
        Thread t = new Thread(() -> {
            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(URL_SCAN).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(20_000);
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
                int code = conn.getResponseCode();
                StringBuilder sb = new StringBuilder();
                try (java.io.InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                    if (in != null) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                org.json.JSONArray a = new org.json.JSONObject(sb.toString()).getJSONArray("jobs");
                java.util.List<Job> list = new java.util.ArrayList<>();
                for (int i = 0; i < a.length(); i++) list.add(parseJob(a.getJSONObject(i)));
                jobs = list;
                jobsError = null;
                synchronized (finishedHere) {
                    // Ce nu mai e in lista a ajuns la server: nu mai trebuie tinut minte.
                    java.util.Set<String> keys = new java.util.HashSet<>();
                    for (Job x : list) keys.add(x.key());
                    finishedHere.retainAll(keys);
                }
            } catch (Exception e) {
                jobsError = e.getClass().getSimpleName();
                Log.w(TAG, "OBD: lucrarile de scanare nu s-au luat", e);
                if (conn != null) conn.disconnect();
            } finally {
                jobsAt = SystemClock.elapsedRealtime();
                jobsLoading = false;
            }
        }, "obd-jobs");
        t.setDaemon(true);
        t.start();
    }

    private static Job parseJob(org.json.JSONObject o) throws org.json.JSONException {
        Job j = new Job();
        j.id = o.getInt("id");
        j.round = o.optInt("round");
        j.label = o.optString("label");
        j.tx = Integer.parseInt(o.getString("tx"), 16);
        j.rx = Integer.parseInt(o.getString("rx"), 16);
        j.svc = o.getString("svc");
        j.kind = o.getString("kind");
        j.first = o.optInt("first");
        j.last = o.optInt("last");
        j.next = o.optInt("next");
        j.parked = o.optBoolean("parked");
        org.json.JSONArray ids = o.optJSONArray("ids");
        j.ids = new int[ids == null ? 0 : ids.length()];
        for (int i = 0; i < j.ids.length; i++) j.ids[i] = ids.getInt(i);
        return j;
    }

    /** Prima lucrare potrivita acum, in ordinea serverului. */
    private Job pickJob(boolean moving) {
        for (Job j : jobs) {
            synchronized (finishedHere) {
                if (finishedHere.contains(j.key())) continue;
            }
            if (j.parked && moving) continue;
            if (silentModules.contains(j.tx)) continue;
            if (!j.range() && j.ids.length == 0) continue;
            return j;
        }
        return null;
    }

    /** Lucrarea aleasa devine cea in curs: de unde a ramas (aici sau pe server, cel mai departe). */
    private void startJob(Job j) {
        flushProgress(true);
        job = j;
        scanPending.clear();
        pendingTries = 0;
        scanSilent = 0;
        scanFound = 0;
        progressSent = -1;
        android.content.SharedPreferences p = c.getSharedPreferences("obd", Context.MODE_PRIVATE);
        int here = j.key().equals(p.getString("scanJob", "")) ? p.getInt("scanNext", 0) : 0;
        scanNext = Math.max(j.range() ? Math.max(j.first, j.next) : j.next, here);
    }

    /** Unde a ajuns lucrarea: nimic inaintea lui nu mai trebuie citit. */
    private int reached() {
        return job.range() && !scanPending.isEmpty() ? scanPending.peek() : scanNext;
    }

    /**
     * Progresul, pe disc (pentru repornire) si la server, la ~15 s (ca sa nu uzam memoria interna),
     * la schimbarea lucrarii si la sfarsit (dupa rezultatele ei, in aceeasi coada). Dupa o repornire
     * se recitesc cel mult ~15 s de coduri: serverul le primeste de doua ori fara probleme.
     */
    private void flushProgress(boolean now) {
        Job j = job;
        if (j == null) return;
        int n = reached();
        long t = SystemClock.elapsedRealtime();
        if (n == progressSent || (!now && t - progressAt < PROGRESS_MS)) return;
        c.getSharedPreferences("obd", Context.MODE_PRIVATE).edit()
                .putString("scanJob", j.key()).putInt("scanNext", n).apply();
        progressAt = t;
        progressSent = n;
        try {
            SCAN_QUEUE.add(c, new org.json.JSONObject().put("t", "p").put("j", j.id).put("r", j.round).put("n", n));
        } catch (org.json.JSONException ignored) {
        }
    }

    private void finishJob(String why) {
        Job j = job;
        scanNext = j.range() ? j.last + 1 : j.ids.length;
        scanPending.clear();
        try {
            SCAN_QUEUE.add(c, new org.json.JSONObject().put("t", "p").put("j", j.id).put("r", j.round)
                    .put("n", scanNext).put("d", 1));
        } catch (org.json.JSONException ignored) {
        }
        synchronized (finishedHere) {
            finishedHere.add(j.key());
        }
        c.getSharedPreferences("obd", Context.MODE_PRIVATE).edit().remove("scanJob").remove("scanNext").apply();
        // Buclele se termina des (la fiecare trecere): doar lucrarile adevarate intra in jurnal.
        if (!j.kind.equals("loop")) Prefs.log(c, "OBD: scanarea „" + j.label + "” " + why + ", " + scanFound + " coduri");
        job = null;
        jobsAt = 0; // lista noua cat mai repede
        Uploader.kick(c);
    }

    private void result(Job j, int id, int[] data, int from, int nrc) {
        try {
            org.json.JSONObject o = new org.json.JSONObject().put("t", "r").put("j", j.id).put("x", hex(j.tx, 3))
                    .put("s", j.svc).put("i", id).put("at", System.currentTimeMillis());
            if (nrc >= 0) {
                o.put("n", nrc);
            } else {
                StringBuilder v = new StringBuilder();
                for (int i = from; i < data.length; i++) v.append(hex(data[i], 2));
                o.put("v", v.toString());
                scanFound++;
            }
            SCAN_QUEUE.add(c, o);
        } catch (org.json.JSONException ignored) {
        }
    }

    /** Raspunsul la 22 (o lista de un cod): bun, refuz (cu cod), lent, sau lipsa (nimic de scris). */
    private void result22(Job j, int did, int[] r) {
        if (isMissing(r)) return;
        if (isSlow(r)) result(j, did, r, 0, NRC_SLOW);
        else if (r[0] == 0x7F) result(j, did, r, 0, r[2]);
        else result(j, did, r, 3, -1);
    }

    /**
     * O bucata de scanare. In mers (cererea utilizatorului, 21.11: sa se poata plimba) bucati de
     * ~0,4 s cu raspunsul asteptat cel mult 0,5 s, ca citirile motorului sa ramana sub o secunda;
     * ce raspunde greu se trimite ca „lent” si serverul il pune la recitit pe loc. Pe loc: 1,2 s si
     * raspunsuri de 1,5 s, de cel mult 3 ori.
     */
    private void scanBlock(boolean moving) throws InterruptedException {
        long now = SystemClock.elapsedRealtime();
        if (jobsAt == 0 || now - jobsAt >= JOBS_MS) refreshJobs();
        Job j = pickJob(moving);
        if (j == null) {
            if (job != null) {
                flushProgress(true);
                job = null;
            }
            return;
        }
        if (job == null || !job.key().equals(j.key())) startJob(j);
        // Lista de la server se reface la fiecare minut cu obiecte noi: lucram mai departe pe cel in
        // curs (buclele de mai jos compara obiectul; 21.11, cu cel nou scanarea statea dupa un minut).
        j = job;
        long budget = moving ? 400 : 1_200;
        long wait = moving ? 500 : 1_500;
        long end = SystemClock.elapsedRealtime() + budget;
        synchronized (io) {
            target(j.tx, j.rx);
            try {
                if (!j.svc.equals("22")) {
                    while (connected && job == j && SystemClock.elapsedRealtime() < end) {
                        if (j.range() ? scanNext > j.last : scanNext >= j.ids.length) {
                            finishJob("gata");
                            break;
                        }
                        int id = j.range() ? scanNext : j.ids[scanNext];
                        readObd(j, id);
                        scanNext++;
                    }
                } else if (!j.range()) {
                    while (connected && job == j && SystemClock.elapsedRealtime() < end) {
                        if (scanNext >= j.ids.length) {
                            finishJob("gata");
                            break;
                        }
                        int did = j.ids[scanNext];
                        int[] r = read22(did, wait);
                        if (isSlow(r) && !moving && ++pendingTries < 3) continue;
                        pendingTries = 0;
                        result22(j, did, r);
                        scanNext++;
                    }
                } else {
                    scanRange(j, moving, wait, end);
                }
            } finally {
                target(0x7E0, -1);
            }
        }
        if (job == j) flushProgress(false);
    }

    private void scanRange(Job j, boolean moving, long wait, long end) throws InterruptedException {
        while (connected && job == j && !scanPending.isEmpty() && SystemClock.elapsedRealtime() < end) {
            int did = scanPending.peek();
            int[] r = read22(did, wait);
            if (isSlow(r) && !moving && ++pendingTries < 3) continue;
            pendingTries = 0;
            scanPending.poll();
            result22(j, did, r);
        }
        while (connected && job == j && scanPending.isEmpty() && scanNext <= j.last
                && SystemClock.elapsedRealtime() < end) {
            int from = scanNext, n = Math.min(3, j.last - from + 1);
            StringBuilder req = new StringBuilder("22");
            for (int i = 0; i < n; i++) req.append(hex(from + i, 4));
            int[] b = strip(bytesOf(send(req.toString(), wait)));
            // Un raspuns intarziat la altceva (21.11: „41 0C …” la DID 000F) = ca si cum n-ar fi venit.
            if (b.length > 0 && b[0] != 0x62 && !(b[0] == 0x7F && b.length >= 3 && b[1] == 0x22)) b = new int[0];
            scanNext = from + n;
            if (b.length == 0) {
                if (scanSilent++ == 0) scanSilentFrom = from;
                if (scanSilent >= 20) {
                    // Modulul nu raspunde (ex. climatronicul inca adormit): reluam la reconectare.
                    scanNext = scanSilentFrom;
                    silentModules.add(j.tx);
                    flushProgress(true);
                    Prefs.log(c, "OBD: modulul " + hex(j.tx, 3) + " nu raspunde, scanarea lui asteapta reconectarea");
                    job = null;
                    return;
                }
            } else {
                scanSilent = 0;
            }
            if (!isMissing(b)) for (int i = 0; i < n; i++) scanPending.add(from + i);
        }
        if (job == j && scanNext > j.last && scanPending.isEmpty()) finishJob("gata");
    }

    /**
     * Un cod din OBD-ul standard: 01/09/06 <id>, 02 <id> 00 (cadrul 0), 03/07/0A (id = serviciul).
     * Ce nu e suportat nu raspunde („NO DATA”) si nu se scrie.
     */
    private void readObd(Job j, int id) throws InterruptedException {
        boolean dtc = j.svc.equals("dtc");
        int svc = dtc ? id : Integer.parseInt(j.svc, 16);
        String req = dtc ? hex(id, 2) : j.svc.equals("02") ? "02" + hex(id, 2) + "00" : j.svc + hex(id, 2);
        int[] b = bytesOf(send(req, 1_500));
        if (b.length < 1 || b[0] != 0x40 + svc) return;
        if (dtc) {
            result(j, id, b, 1, -1);
            return;
        }
        if (b.length < 2 || b[1] != id) return;
        result(j, id, b, j.svc.equals("02") ? 3 : 2, -1);
    }

    private int targetTx = 0x7E0;

    /** Adresa modulului (cererile, filtrul raspunsurilor, confirmarea cadrelor); 7E0 = motorul. */
    private void target(int tx, int rx) throws InterruptedException {
        if (tx == targetTx) return;
        targetTx = tx;
        send("ATSH" + hex(tx, 3), 1_000);
        send(tx == 0x7E0 ? "ATCRA" : "ATCRA" + hex(rx, 3), 1_000);
        send("ATFCSH" + hex(tx, 3), 1_000);
    }

    /**
     * Un DID, doar raspunsul lui: „62 <DID> …” sau „7F 22 <cod>”; altceva (raspunsul intarziat al
     * altei cereri) = fara raspuns. Prefixele „7F 22 78” (asteapta) dinaintea raspunsului se scot.
     */
    private int[] read22(int did, long wait) throws InterruptedException {
        int[] r = strip(bytesOf(send("22" + hex(did, 4), wait)));
        if (r.length >= 3 && r[0] == 0x62 && r[1] == (did >> 8) && r[2] == (did & 0xFF)) return r;
        if (r.length == 3 && r[0] == 0x7F && r[1] == 0x22) return r;
        return new int[0];
    }

    /**
     * Scoate „7F <serviciul> 78/21” (asteapta / ocupat) dinaintea raspunsului, la orice serviciu (21.11:
     * motorul si airbag-ul raspund la 19 02 intai cu 7F 19 78; pana la 1.1.105 doar 22 era curatat).
     */
    private static int[] strip(int[] r) {
        int i = 0;
        while (r.length - i > 3 && r[i] == 0x7F && (r[i + 2] == 0x78 || r[i + 2] == 0x21)) i += 3;
        return i == 0 ? r : java.util.Arrays.copyOfRange(r, i, r.length);
    }

    private static boolean isSlow(int[] r) {
        return r.length == 0 || (r[0] == 0x7F && r.length == 3 && (r[2] == 0x78 || r[2] == 0x21));
    }

    private static boolean isMissing(int[] r) {
        return r.length >= 3 && r[0] == 0x7F && r[2] == 0x31;
    }

    private int[] bytesOf(String r) {
        return r == null ? new int[0] : bytes(r);
    }

    private static String hex(int v, int digits) {
        return String.format(java.util.Locale.US, "%0" + digits + "X", v);
    }

    // --- citirea ---------------------------------------------------------------------------

    private void poll() throws InterruptedException {
        long now = SystemClock.elapsedRealtime();
        boolean ok;
        if (!multi && now >= multiRetryAt) {
            multi = true;
            multiFails = 0;
        }
        if (multi) {
            // Cifra de la sfarsit = cate cadre CAN asteptam: adaptorul se intoarce imediat, oricat si-ar
            // fi marit singur asteptarea (dupa raspunsurile lente ale bordului, ~240 ms pe cerere).
            // Raspunsul are 13 octeti = 2 cadre; cu „1” (1.1.100–1.1.103) venea doar primul (turatia
            // si presiunea, fara aer, injectie, pedala: niciun litru), confirmat 21.11: „2” = 60–75 ms.
            String r = send("010C0B0F03492", 1_500);
            ok = mode01(r);
            if (!ok && ++multiFails >= 3) {
                // Pe rand un minut, apoi iar mai multe deodata (21.11: a esuat doar la pornire, cat
                // testul de la distanta schimba setarile adaptorului).
                multi = false;
                multiRetryAt = now + 60_000;
                if (!multiLogged) {
                    multiLogged = true;
                    String raw = r == null ? "fara raspuns" : r.replace("\r", " | ").trim();
                    Prefs.log(c, "OBD: cererea cu mai multe PID-uri a esuat (" + raw.substring(0, Math.min(80, raw.length()))
                            + "), citesc pe rand un minut");
                }
            } else if (ok) {
                multiFails = 0;
            }
        } else {
            ok = mode01(send("010C1", 1_000)) & mode01(send("010B1", 1_000)) & mode01(send("010F1", 1_000));
        }
        if (now - lastSlowAt >= SLOW_MS) {
            lastSlowAt = now;
            if (multi) {
                mode01(send("0105044406070E3", 1_500)); // 14 octeti = 3 cadre
            } else {
                for (String p : new String[]{"0105", "0104", "0144", "0103", "0149"}) mode01(send(p + "1", 1_000));
            }
        }
        if (now - lastStatusAt >= STATUS_MS) {
            lastStatusAt = now;
            readDtc();
        }
        if (now - lastUdsAt >= UDS_DTC_MS) {
            lastUdsAt = now;
            readUdsDtc();
        }
        if (now - rateAt >= 10_000) {
            rate = rateAt == 0 ? 0 : (cycles - rateCycles) * 1000.0 / (now - rateAt);
            rateAt = now;
            rateCycles = cycles;
        }
        if (ok) sample();
        cycles++;
        if (!connected) return;
        if (!ok && consecutiveTimeouts >= 5) disconnect("adaptorul nu mai raspunde");
    }

    // Codurile de eroare: martorul si numarul (0101), memorate (03), in asteptare (07); brute in
    // hex (serverul le traduce, dtc.ts). dtcAt = ora citirii (System.currentTimeMillis), 0 = niciuna.
    private volatile long dtcAt;
    private volatile boolean dtcMil;
    private volatile int dtcCount = -1;
    private volatile String dtcStored = "";
    private volatile String dtcPending = "";
    private long rateAt;
    private int rateCycles;
    private volatile double rate;

    /** Modulele care raspund la UDS prin priza OBD (21.11): motorul, bordul, climatronicul, airbag-ul. */
    private static final int[][] UDS_MODULES = {{0x7E0, 0x7E8}, {0x714, 0x77E}, {0x746, 0x7B0}, {0x715, 0x77F}};
    private static final long UDS_DTC_MS = 15 * 60_000;
    private long lastUdsAt;
    // Erorile tuturor modulelor (UDS 19 02 FF), brute in hex pe modul („-” = n-a raspuns); udsAt = ora.
    private volatile long udsAt;
    private volatile java.util.Map<String, String> udsDtc = new java.util.LinkedHashMap<>();

    /** Erorile fiecarui modul UDS: o singura citire (19 02 = dupa stare), nu sterge nimic. */
    private void readUdsDtc() throws InterruptedException {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (int[] m : UDS_MODULES) {
            if (!connected) return;
            target(m[0], m[1]);
            int[] r = strip(bytesOf(send("1902FF", 3_000)));
            out.put(hex(m[0], 3), r.length >= 3 && r[0] == 0x59 && r[1] == 0x02 ? hexOf(r) : "-");
        }
        target(0x7E0, -1);
        udsDtc = out;
        udsAt = System.currentTimeMillis();
    }

    private void readDtc() throws InterruptedException {
        mode01(send("01011", 1_000));
        String s = send("03", 2_000);
        String p = send("07", 2_000);
        if (s == null || p == null) return;
        int[] bs = bytes(s), bp = bytes(p);
        if (bs.length == 0 || bs[0] != 0x43 || bp.length == 0 || bp[0] != 0x47) return;
        dtcStored = hexOf(bs);
        dtcPending = hexOf(bp);
        dtcAt = System.currentTimeMillis();
    }

    private static String hexOf(int[] b) {
        StringBuilder sb = new StringBuilder();
        for (int x : b) sb.append(hex(x, 2));
        return sb.toString();
    }

    private int consecutiveTimeouts;
    private int milCount = -1;

    /** Raspunsul la o cerere mod 01, una sau mai multe PID-uri; true daca a venit ceva. */
    private boolean mode01(String r) {
        if (r == null) {
            consecutiveTimeouts++;
            return false;
        }
        consecutiveTimeouts = 0;
        int[] b = bytes(r);
        if (b.length < 3 || b[0] != 0x41) return false;
        int i = 1;
        boolean any = false;
        synchronized (this) {
            while (i < b.length) {
                int pid = b[i];
                int n = len(pid);
                if (n == 0 || i + n >= b.length) break;
                int a = b[i + 1];
                int bb = n > 1 ? b[i + 2] : 0;
                switch (pid) {
                    case 0x0C: rpm = (a * 256 + bb) / 4; break;
                    case 0x0B: map = a; break;
                    case 0x0F: iat = a - 40; break;
                    case 0x05: ect = a - 40; break;
                    case 0x04: load = Math.round(a * 1000 / 255.0) / 10.0; break;
                    case 0x44: lambda = (a * 256 + bb) * 2 / 65536.0; break;
                    case 0x03: fuelStatus = a; break;
                    case 0x49:
                        pedal = a * 100 / 255.0;
                        if (Double.isNaN(pedalRest) || pedal < pedalRest) pedalRest = pedal;
                        break;
                    case 0x01: {
                        int count = a & 0x7F;
                        boolean mil = (a & 0x80) != 0;
                        dtcMil = mil;
                        dtcCount = count;
                        if (count != milCount) {
                            if (milCount >= 0 || count > 0) {
                                Prefs.log(c, "OBD: coduri de eroare " + count + (mil ? ", martorul aprins" : ""));
                            }
                            milCount = count;
                        }
                        break;
                    }
                    default: break;
                }
                any = true;
                i += 1 + n;
            }
        }
        return any;
    }

    private static int len(int pid) {
        switch (pid) {
            case 0x0C: case 0x03: case 0x44: return 2;
            case 0x0B: case 0x0F: case 0x05: case 0x04: case 0x06: case 0x07: case 0x0E: case 0x49: case 0x11:
                return 1;
            case 0x01: return 4;
            default: return 0;
        }
    }

    /**
     * Octetii din raspunsul ELM (fara spatii si antete): o linie „410C0BC0” sau, pe mai multe
     * cadre, „00A” (lungimea), „0:410C0BC00B”, „1:1F…”; liniile de text se sar.
     */
    static int[] bytes(String r) {
        StringBuilder hex = new StringBuilder();
        int total = -1;
        // Unde incepe mesajul pe mai multe cadre: lungimea lui nu cuprinde ce a venit inainte
        // (ex. un „7F 19 78” pe linia de deasupra).
        int start = 0;
        for (String line : r.split("[\\r\\n]+")) {
            String l = line.replace(" ", "").trim();
            int colon = l.indexOf(':');
            if (colon >= 0) l = l.substring(colon + 1);
            if (l.isEmpty() || !l.matches("[0-9A-Fa-f]+")) continue;
            if (colon < 0 && l.length() == 3) {
                total = Integer.parseInt(l, 16);
                start = hex.length() / 2;
                continue;
            }
            if (l.length() % 2 != 0) continue;
            hex.append(l);
        }
        int n = hex.length() / 2;
        if (total >= 0 && start + total < n) n = start + total;
        int[] b = new int[n];
        for (int i = 0; i < n; i++) b[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    /** Consumul de acum din aerul aspirat si integrarea lui de la ultima citire. */
    private synchronized void sample() {
        long now = SystemClock.elapsedRealtime();
        fastAt = now;
        if (rpm < 0 || Double.isNaN(map) || Double.isNaN(iat)) return;
        double v;
        if (rpm < 300) {
            v = 0;
        } else if (fuelStatus == 4 && rpm > 1_000 && !Double.isNaN(pedal) && pedal <= pedalRest + 1.5) {
            // Injectia taiata la decelerare (bucla deschisa, pedala ridicata, turatie mare).
            v = 0;
        } else {
            double airKgS = map * 1000 * DISPLACEMENT_M3 * (rpm / 120.0) * VE / (287.05 * (iat + 273.15));
            double lam = Double.isNaN(lambda) || lambda < 0.6 || lambda > 1.5 ? 1 : lambda;
            v = airKgS * 1000 / (AFR * lam) * 3600 / FUEL_G_PER_L;
        }
        lh = Math.round(v * 100) / 100.0;
        if (lastSampleAt > 0) {
            long dt = now - lastSampleAt;
            if (dt <= 5_000) {
                liters += v * dt / 3_600_000.0;
                litersTotal += v * dt / 3_600_000.0;
                seconds += dt / 1000.0;
            }
        }
        lastSampleAt = now;
    }

    // --- legatura --------------------------------------------------------------------------

    private long unstickAt;
    /** Ultima conectare a expirat fara niciun raspuns de la stiva Bluetooth; cate la rand. */
    private boolean noAnswer;
    private int silentFailures;

    /**
     * 21.11: stiva Bluetooth a navigatiei ramasese blocata (adaptorul se vedea in scanare, dar orice
     * conectare expira fara niciun raspuns, si din testul de la distanta); oprirea si pornirea
     * Bluetooth-ului Android a rezolvat-o. O facem singuri, cel mult o data la 30 min si doar daca
     * adaptorul chiar se vede (fara el, ex. scos din priza, nu deranjam alte dispozitive BLE).
     * Telefonul din masina (modulul Teyes) nu trece prin Bluetooth-ul Android.
     */
    private void unstick() throws InterruptedException {
        long now = SystemClock.elapsedRealtime();
        if (unstickAt != 0 && now - unstickAt < UNSTICK_EVERY_MS) return;
        unstickAt = now;
        String addr = c.getSharedPreferences("obd", Context.MODE_PRIVATE).getString("addr", DEFAULT_ADDR);
        if (!advertising(addr)) {
            Prefs.log(c, "OBD: adaptorul nu se vede in scanare, nu repornesc Bluetooth-ul");
            return;
        }
        String r = ObdProbe.reset(c);
        Prefs.log(c, "OBD: Bluetooth repornit (adaptorul se vedea, dar nu accepta legatura): " + r.replace("\n", "; "));
        failures = 0;
        silentFailures = 0;
        failLogged = false;
        nextTryAt = SystemClock.elapsedRealtime() + 3_000;
    }

    /** Adaptorul apare in scanarea BLE (cel mult 8 s)? */
    @SuppressWarnings("MissingPermission")
    private boolean advertising(String addr) throws InterruptedException {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        android.bluetooth.le.BluetoothLeScanner sc = ad != null && ad.isEnabled() ? ad.getBluetoothLeScanner() : null;
        if (sc == null) return false;
        boolean[] seen = {false};
        android.bluetooth.le.ScanCallback cb = new android.bluetooth.le.ScanCallback() {
            @Override
            public void onScanResult(int type, android.bluetooth.le.ScanResult r) {
                if (!addr.equalsIgnoreCase(r.getDevice().getAddress())) return;
                synchronized (seen) {
                    seen[0] = true;
                    seen.notifyAll();
                }
            }
        };
        try {
            sc.startScan(cb);
        } catch (Exception e) {
            return false;
        }
        try {
            synchronized (seen) {
                long end = SystemClock.elapsedRealtime() + 8_000;
                while (!seen[0] && SystemClock.elapsedRealtime() < end) seen.wait(250);
            }
        } finally {
            try {
                sc.stopScan(cb);
            } catch (Exception ignored) {
                // Bluetooth oprit intre timp
            }
        }
        return seen[0];
    }

    @SuppressWarnings("MissingPermission")
    private boolean connect() throws InterruptedException {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) return false;
        String addr = c.getSharedPreferences("obd", Context.MODE_PRIVATE).getString("addr", DEFAULT_ADDR);
        BluetoothDevice dev = ad.getRemoteDevice(addr);
        noAnswer = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            synchronized (lock) {
                connStatus = 0;
                discovered = false;
                gatt = dev.connectGatt(c, false, this, BluetoothDevice.TRANSPORT_LE);
                long end = SystemClock.elapsedRealtime() + 15_000;
                while (!connected && connStatus == 0 && SystemClock.elapsedRealtime() < end) lock.wait(250);
            }
            if (connected) break;
            noAnswer = connStatus == 0;
            closeGatt();
            if (connStatus != 133) return false;
            Thread.sleep(1_000);
        }
        if (!connected) return false;
        Thread.sleep(600);
        synchronized (lock) {
            if (!gatt.discoverServices()) return false;
            long end = SystemClock.elapsedRealtime() + 10_000;
            while (!discovered && connected && SystemClock.elapsedRealtime() < end) lock.wait(250);
        }
        BluetoothGattService s = discovered ? gatt.getService(SERVICE) : null;
        BluetoothGattCharacteristic nt = s != null ? s.getCharacteristic(NOTIFY) : null;
        wr = s != null ? s.getCharacteristic(WRITE) : null;
        if (nt == null || wr == null) return false;
        gatt.setCharacteristicNotification(nt, true);
        BluetoothGattDescriptor d = nt.getDescriptor(CCCD);
        if (d != null) {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            synchronized (lock) {
                opDone = false;
                if (gatt.writeDescriptor(d)) {
                    long end = SystemClock.elapsedRealtime() + 3_000;
                    while (!opDone && SystemClock.elapsedRealtime() < end) lock.wait(100);
                }
            }
        }
        wr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        return connected;
    }

    /** Setarile ELM si o prima citire; false daca motorul nu raspunde. */
    private boolean init() throws InterruptedException {
        synchronized (io) {
            if (send("ATZ", 3_000) == null) return false;
            for (String a : INIT) send(a, 1_000);
            targetTx = 0x7E0;
            String r = send("0100", 3_000);
            if (r == null || !r.replace(" ", "").contains("4100")) return false;
        }
        connectedAt = SystemClock.elapsedRealtime();
        cycles = 0;
        rateAt = connectedAt;
        rateCycles = 0;
        rate = 0;
        multi = true;
        multiFails = 0;
        lastSlowAt = 0;
        lastStatusAt = 0;
        lastUdsAt = 0;
        silentModules.clear();
        synchronized (this) {
            litersTotal = 0;
            lastSampleAt = 0;
        }
        session = true;
        Prefs.log(c, "OBD: conectat");
        return true;
    }

    /** Trimite o comanda si asteapta „>”; raspunsul fara prompt, null fara raspuns la timp. */
    @SuppressWarnings("MissingPermission")
    private String send(String cmd, long waitMs) throws InterruptedException {
        synchronized (lock) {
            if (!connected || gatt == null || wr == null) return null;
            if (late) {
                // Cererea anterioara a ramas fara „>”: asteptam sa se termine si aruncam raspunsul ei.
                long until = SystemClock.elapsedRealtime() + 1_000;
                while (rx.indexOf(">") < 0 && connected && SystemClock.elapsedRealtime() < until) lock.wait(20);
                late = false;
            }
            rx.setLength(0);
            wr.setValue((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
            if (!gatt.writeCharacteristic(wr)) {
                // O scriere inca in curs: mai incercam o data.
                lock.wait(30);
                if (!gatt.writeCharacteristic(wr)) return null;
            }
            long end = SystemClock.elapsedRealtime() + waitMs;
            while (rx.indexOf(">") < 0 && connected && SystemClock.elapsedRealtime() < end) lock.wait(20);
            if (rx.indexOf(">") < 0) {
                late = true;
                return null;
            }
            return rx.substring(0, rx.indexOf(">"));
        }
    }

    /** Pentru testul de la distanta, prin legatura deschisa; dupa el refacem setarile noastre. */
    String script(String[] cmds) throws InterruptedException {
        StringBuilder out = new StringBuilder("Prin legatura FaikkitCar (conectat de ")
                .append((SystemClock.elapsedRealtime() - connectedAt) / 1000).append(" s, ")
                .append(cycles).append(" cicluri, ")
                .append(multi ? "mai multe PID-uri pe cerere" : "PID-uri pe rand");
        synchronized (this) {
            out.append(", ").append(String.format(java.util.Locale.US, "%.3f", litersTotal)).append(" L, acum ")
                    .append(lh).append(" L/h, MAP ").append(map).append(" kPa, aer ").append(iat)
                    .append(" °C, racire ").append(ect).append(" °C, lambda ").append(lambda)
                    .append(", injectie ").append(fuelStatus).append(", pedala ")
                    .append(Math.round(pedal * 10) / 10.0).append(" (repaus ")
                    .append(Math.round(pedalRest * 10) / 10.0).append(")");
        }
        out.append(")\n");
        synchronized (io) {
            for (String cmd : cmds) {
                long t0 = SystemClock.elapsedRealtime();
                String up = cmd.toUpperCase();
                String r = ObdProbe.forbidden(cmd) ? null : send(cmd, up.equals("ATZ") ? 4_000 : 6_000);
                out.append("\n").append(cmd).append(" (").append(SystemClock.elapsedRealtime() - t0).append(" ms) -> ")
                        .append(ObdProbe.forbidden(cmd) ? "sarit (nu e o citire)"
                                : r == null ? "[fara raspuns]" : r.replace("\r", " | ").trim());
            }
            for (String a : INIT) send(a, 1_000);
            targetTx = 0x7E0;
        }
        return out.toString();
    }

    private void disconnect(String why) {
        boolean was = session;
        session = false;
        double total;
        synchronized (this) {
            total = litersTotal;
        }
        disconnectQuiet();
        if (was) {
            long s = (SystemClock.elapsedRealtime() - connectedAt) / 1000;
            Prefs.log(c, "OBD: deconectat (" + why + "), " + s + " s, "
                    + (s > 0 ? Math.round(cycles * 10.0 / s) / 10.0 : 0) + " cicluri/s, "
                    + String.format(java.util.Locale.US, "%.2f", total) + " L");
        }
    }

    /** Deconectarea si inchiderea dupa confirmare (close imediat lasa legatura deschisa). */
    @SuppressWarnings("MissingPermission")
    private void disconnectQuiet() {
        BluetoothGatt g = gatt;
        if (g == null) return;
        try {
            synchronized (lock) {
                if (connected) {
                    g.disconnect();
                    long end = SystemClock.elapsedRealtime() + 3_000;
                    while (connected && SystemClock.elapsedRealtime() < end) lock.wait(100);
                }
            }
        } catch (Exception ignored) {
            // inchidem oricum
        }
        closeGatt();
    }

    @SuppressWarnings("MissingPermission")
    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        wr = null;
        connected = false;
        if (g == null) return;
        try {
            g.close();
        } catch (Exception ignored) {
            // deja inchis
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
        synchronized (lock) {
            if (g != gatt) return;
            connected = newState == BluetoothProfile.STATE_CONNECTED;
            if (status != 0) connStatus = status;
            lock.notifyAll();
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt g, int status) {
        synchronized (lock) {
            discovered = true;
            lock.notifyAll();
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
        synchronized (lock) {
            opDone = true;
            lock.notifyAll();
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        byte[] v = ch.getValue();
        if (v == null) return;
        String s = new String(Arrays.copyOf(v, v.length), StandardCharsets.US_ASCII);
        synchronized (lock) {
            rx.append(s);
            lock.notifyAll();
        }
    }
}
