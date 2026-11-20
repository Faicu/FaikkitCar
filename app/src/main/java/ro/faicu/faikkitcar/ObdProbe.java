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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Test pentru adaptorul OBD2 BLE (20.11, vLinker MC+, se anunta „vLinker MC-IOS”): il cauta,
 * se conecteaza prin GATT, listeaza serviciile, alege caracteristica de notificare si pe cea de
 * scriere dupa proprietati si trimite comenzi ELM327 (doar citiri), cu raspunsul pana la „>”.
 * `@obd` = setul implicit (adaptorul, PID-urile suportate, motorul, codurile de eroare, UDS);
 * `@obd ATI 010C …` = doar comenzile date. Blocheaza cel mult ~2 min; la sfarsit se deconecteaza,
 * ca adaptorul sa ramana liber (BLE = o singura conexiune).
 */
final class ObdProbe {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long DEADLINE_MS = 110_000;
    private static final String[] DEFAULT = {
            "ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATI", "STI", "STDI", "ATRV",
            "ATSP0", "0100", "ATDPN", "0120", "0140", "0160", "0902",
            "0104", "0105", "0106", "0107", "010B", "010C", "010D", "010E", "010F", "0110",
            "0111", "0133", "0146", "015E", "0101", "03", "07",
            "ATSH7E0", "22F190", "22F187", "22F189", "1A9B", "ATD",
    };
    /** Doar citiri: fara stergerea codurilor (04), sesiuni (10), scrieri (2E), rutine (31), 14. */
    private static final String[] FORBIDDEN = {"04", "10", "2E", "31", "14", "11", "27", "28", "85"};

    private ObdProbe() {}

    static String run(Context c, String args) {
        String[] cmds = args.trim().isEmpty() ? DEFAULT : args.trim().split("\\s+");
        Probe p = new Probe(c);
        try {
            p.go(cmds);
        } catch (Exception e) {
            p.out.append("\neroare: ").append(e);
        } finally {
            p.close();
        }
        return p.out.toString();
    }

    private static boolean forbidden(String cmd) {
        String u = cmd.toUpperCase();
        if (u.startsWith("AT") || u.startsWith("ST")) return false;
        for (String f : FORBIDDEN) if (u.startsWith(f)) return true;
        return false;
    }

    private static final class Probe extends BluetoothGattCallback {
        final Context c;
        final StringBuilder out = new StringBuilder();
        final long until = System.currentTimeMillis() + DEADLINE_MS;
        final Object lock = new Object();
        final StringBuilder rx = new StringBuilder();
        BluetoothGatt gatt;
        int connState = -1;
        int connStatus;
        boolean discovered;
        boolean opDone;
        int opStatus;
        BluetoothGattCharacteristic wr;

        Probe(Context c) {
            this.c = c;
        }

        @SuppressWarnings("MissingPermission")
        void go(String[] cmds) throws Exception {
            BluetoothManager bm = c.getSystemService(BluetoothManager.class);
            BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            if (ad == null || !ad.isEnabled()) {
                out.append("Bluetooth Android oprit sau lipsa");
                return;
            }
            BluetoothDevice dev = find(ad);
            if (dev == null) {
                out.append("Adaptorul OBD nu s-a gasit in 8 s (nume vLinker/OBD/ELM)");
                return;
            }
            out.append("Adaptor: ").append(dev.getName()).append(" ").append(dev.getAddress());
            if (!connect(dev)) {
                connStatus = 0;
                connState = -1;
                out.append("\nreincerc conectarea");
                close();
                Thread.sleep(1_000);
                if (!connect(dev)) return;
            }
            Thread.sleep(600);
            synchronized (lock) {
                if (!gatt.discoverServices()) {
                    out.append("\ndiscoverServices refuzat");
                    return;
                }
                while (!discovered && left() > 0) lock.wait(500);
            }
            if (!discovered) {
                out.append("\nserviciile nu au venit");
                return;
            }
            BluetoothGattCharacteristic nt = null;
            out.append("\nServicii:");
            for (BluetoothGattService s : gatt.getServices()) {
                String su = s.getUuid().toString();
                out.append("\n  ").append(su);
                boolean std = su.startsWith("00001800") || su.startsWith("00001801")
                        || su.startsWith("0000180a") || su.startsWith("0000180f");
                for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
                    int pr = ch.getProperties();
                    out.append("\n    ").append(ch.getUuid()).append(" ").append(props(pr));
                    if (std) continue;
                    boolean canNotify = (pr & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
                    boolean canWrite = (pr & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
                    // Prima pereche din acelasi serviciu castiga.
                    if (nt == null && canNotify) nt = ch;
                    if (wr == null && canWrite && (nt == null || nt.getService() == s)) wr = ch;
                }
            }
            if (nt == null || wr == null) {
                out.append("\nnu am gasit caracteristicile de notificare / scriere");
                return;
            }
            out.append("\nNotificari: ").append(nt.getUuid()).append(" | scriere: ").append(wr.getUuid());
            gatt.setCharacteristicNotification(nt, true);
            BluetoothGattDescriptor d = nt.getDescriptor(CCCD);
            if (d != null) {
                boolean indicate = (nt.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
                d.setValue(indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                synchronized (lock) {
                    opDone = false;
                    if (!gatt.writeDescriptor(d)) out.append("\nwriteDescriptor refuzat");
                    else while (!opDone && left() > 0) lock.wait(500);
                }
            }
            wr.setWriteType((wr.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            out.append("\n");
            for (String cmd : cmds) {
                if (left() < 2_000) {
                    out.append("\n[timpul s-a terminat]");
                    break;
                }
                if (forbidden(cmd)) {
                    out.append("\n").append(cmd).append(" -> sarit (nu e o citire)");
                    continue;
                }
                long t0 = System.currentTimeMillis();
                String up = cmd.toUpperCase();
                long wait = up.equals("ATZ") ? 4_000 : up.equals("0100") ? 15_000 : 6_000;
                String r = send(cmd, wait);
                out.append("\n").append(cmd).append(" (").append(System.currentTimeMillis() - t0)
                        .append(" ms) -> ").append(r);
                if (connState != BluetoothProfile.STATE_CONNECTED) {
                    out.append("\n[deconectat]");
                    break;
                }
            }
        }

        @SuppressWarnings("MissingPermission")
        private BluetoothDevice find(BluetoothAdapter ad) throws InterruptedException {
            BluetoothLeScanner sc = ad.getBluetoothLeScanner();
            if (sc == null) return null;
            BluetoothDevice[] found = {null};
            ScanCallback cb = new ScanCallback() {
                @Override
                public void onScanResult(int type, ScanResult r) {
                    String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                    if (n == null) return;
                    String u = n.toUpperCase();
                    if (u.contains("VLINK") || u.contains("OBD") || u.contains("ELM")) {
                        synchronized (found) {
                            if (found[0] == null) found[0] = r.getDevice();
                            found.notifyAll();
                        }
                    }
                }
            };
            sc.startScan(cb);
            try {
                synchronized (found) {
                    long end = System.currentTimeMillis() + 8_000;
                    while (found[0] == null && System.currentTimeMillis() < end) found.wait(250);
                }
            } finally {
                sc.stopScan(cb);
            }
            return found[0];
        }

        @SuppressWarnings("MissingPermission")
        private boolean connect(BluetoothDevice dev) throws InterruptedException {
            synchronized (lock) {
                gatt = dev.connectGatt(c, false, this, BluetoothDevice.TRANSPORT_LE);
                long end = Math.min(until, System.currentTimeMillis() + 15_000);
                while (connState != BluetoothProfile.STATE_CONNECTED && connStatus == 0
                        && System.currentTimeMillis() < end) lock.wait(250);
            }
            boolean ok = connState == BluetoothProfile.STATE_CONNECTED;
            out.append("\nconectare: ").append(ok ? "da" : "nu (stare " + connState + ", cod " + connStatus + ")");
            return ok;
        }

        @SuppressWarnings("MissingPermission")
        private String send(String cmd, long waitMs) throws InterruptedException {
            synchronized (lock) {
                rx.setLength(0);
                wr.setValue((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
                opDone = false;
                if (!gatt.writeCharacteristic(wr)) return "[scriere refuzata]";
                long end = Math.min(until, System.currentTimeMillis() + waitMs);
                while (rx.indexOf(">") < 0 && System.currentTimeMillis() < end
                        && connState == BluetoothProfile.STATE_CONNECTED) lock.wait(50);
                boolean prompt = rx.indexOf(">") >= 0;
                String r = rx.toString().replace(">", "").replace("\r", " | ").replace("\n", "").trim();
                while (r.startsWith("|")) r = r.substring(1).trim();
                while (r.endsWith("|")) r = r.substring(0, r.length() - 1).trim();
                return prompt ? r : r + " [fara > in " + waitMs / 1000 + " s]";
            }
        }

        private long left() {
            return until - System.currentTimeMillis();
        }

        @SuppressWarnings("MissingPermission")
        void close() {
            BluetoothGatt g = gatt;
            gatt = null;
            if (g == null) return;
            try {
                g.disconnect();
                g.close();
            } catch (Exception ignored) {
                // deja inchis
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            synchronized (lock) {
                connState = newState;
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
                opStatus = status;
                lock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            synchronized (lock) {
                opDone = true;
                opStatus = status;
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

    private static String props(int p) {
        List<String> l = new ArrayList<>();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) l.add("read");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) l.add("write");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) l.add("writeNR");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) l.add("notify");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) l.add("indicate");
        return l.toString();
    }
}
