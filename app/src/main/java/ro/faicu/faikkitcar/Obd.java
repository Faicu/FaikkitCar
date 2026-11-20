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

    private Obd(Context c) {
        this.c = c.getApplicationContext();
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
        if (o == null) return null;
        synchronized (o) {
            boolean fresh = SystemClock.elapsedRealtime() - o.fastAt < 3_000;
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
        boolean engine = CanLink.get(c).rpm() > 300;
        if (engine) engineOffAt = 0;
        else if (engineOffAt == 0) engineOffAt = now;
        if (!engine && (!connected || now - engineOffAt >= RELEASE_MS)) {
            if (connected) disconnect("motor oprit");
            if (gatt != null) closeGatt();
            failures = 0;
            failLogged = false;
            nextTryAt = 0;
            return false;
        }
        if (!connected) {
            if (gatt != null) disconnect("legatura pierduta");
            if (now < nextTryAt) return false;
            if (connect() && init()) {
                failures = 0;
                failLogged = false;
                return true;
            }
            disconnectQuiet();
            failures++;
            nextTryAt = SystemClock.elapsedRealtime() + Math.min(120_000, 10_000L * failures);
            if (failures == 3 && !failLogged) {
                failLogged = true;
                Prefs.log(c, "OBD: adaptorul nu raspunde (cod " + connStatus + "), reincerc");
            }
            return false;
        }
        synchronized (io) {
            poll();
        }
        return true;
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
            String r = send("010C0B0F0349", 1_500);
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
            ok = mode01(send("010C", 1_000)) & mode01(send("010B", 1_000)) & mode01(send("010F", 1_000));
        }
        if (now - lastSlowAt >= SLOW_MS) {
            lastSlowAt = now;
            if (multi) {
                mode01(send("0105044406070E", 1_500));
            } else {
                for (String p : new String[]{"0105", "0104", "0144", "0103", "0149"}) mode01(send(p, 1_000));
            }
        }
        if (now - lastStatusAt >= STATUS_MS) {
            lastStatusAt = now;
            mode01(send("0101", 1_000));
        }
        if (ok) sample();
        cycles++;
        if (!connected) return;
        if (!ok && consecutiveTimeouts >= 5) disconnect("adaptorul nu mai raspunde");
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
        for (String line : r.split("[\\r\\n]+")) {
            String l = line.replace(" ", "").trim();
            int colon = l.indexOf(':');
            if (colon >= 0) l = l.substring(colon + 1);
            if (l.isEmpty() || !l.matches("[0-9A-Fa-f]+")) continue;
            if (colon < 0 && l.length() == 3) {
                total = Integer.parseInt(l, 16);
                continue;
            }
            if (l.length() % 2 != 0) continue;
            hex.append(l);
        }
        int n = hex.length() / 2;
        if (total >= 0 && total < n) n = total;
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
            if (dt <= 3_000) {
                liters += v * dt / 3_600_000.0;
                litersTotal += v * dt / 3_600_000.0;
                seconds += dt / 1000.0;
            }
        }
        lastSampleAt = now;
    }

    // --- legatura --------------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    private boolean connect() throws InterruptedException {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) return false;
        String addr = c.getSharedPreferences("obd", Context.MODE_PRIVATE).getString("addr", DEFAULT_ADDR);
        BluetoothDevice dev = ad.getRemoteDevice(addr);
        for (int attempt = 0; attempt < 2; attempt++) {
            synchronized (lock) {
                connStatus = 0;
                discovered = false;
                gatt = dev.connectGatt(c, false, this, BluetoothDevice.TRANSPORT_LE);
                long end = SystemClock.elapsedRealtime() + 15_000;
                while (!connected && connStatus == 0 && SystemClock.elapsedRealtime() < end) lock.wait(250);
            }
            if (connected) break;
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
            for (String a : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATSH7E0"}) send(a, 1_000);
            String r = send("0100", 3_000);
            if (r == null || !r.replace(" ", "").contains("4100")) return false;
        }
        connectedAt = SystemClock.elapsedRealtime();
        cycles = 0;
        multi = true;
        multiFails = 0;
        lastSlowAt = 0;
        lastStatusAt = 0;
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
            rx.setLength(0);
            wr.setValue((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
            if (!gatt.writeCharacteristic(wr)) {
                // O scriere inca in curs: mai incercam o data.
                lock.wait(30);
                if (!gatt.writeCharacteristic(wr)) return null;
            }
            long end = SystemClock.elapsedRealtime() + waitMs;
            while (rx.indexOf(">") < 0 && connected && SystemClock.elapsedRealtime() < end) lock.wait(20);
            if (rx.indexOf(">") < 0) return null;
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
            for (String a : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATSH7E0"}) send(a, 1_000);
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
