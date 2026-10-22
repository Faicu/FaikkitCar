package ro.faicu.faikkitcar;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test pentru un adaptor OBD2 fara fire (22.10): pe Teyes, Bluetooth-ul din meniu (telefonul)
 * e un modul separat (`sys.fyt.bluetooth_type` = 7, `/dev/BT_serial`, condus de com.syu.bt) pe
 * care aplicatiile nu-l vad. Procesorul are si cipul lui (wcnd, stiva Android): il putem folosi
 * noi? Raporteaza starea adaptorului Android, scaneaza BLE si clasic si spune ce a gasit. Daca
 * l-am pornit noi, il oprim la sfarsit. Blocheaza ~25 s: nu din firul UI.
 */
final class BleProbe {
    private static final long BLE_MS = 10_000;
    private static final long CLASSIC_MS = 12_000;
    private static final int MAX_LIST = 25;

    private BleProbe() {}

    @SuppressWarnings({"MissingPermission", "deprecation"})
    static String run(Context c) {
        StringBuilder sb = new StringBuilder("Test Bluetooth: ");
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null) return sb.append("fara adaptor Bluetooth Android").toString();
        boolean le = c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        sb.append("BLE ").append(le ? "da" : "NU").append(" | stare ").append(state(ad.getState()));
        boolean enabledByUs = false;
        try {
            sb.append(" | nume ").append(ad.getName());
            sb.append(" | asociate ").append(ad.getBondedDevices().size());
            for (BluetoothDevice d : ad.getBondedDevices()) sb.append(" [").append(d.getName()).append("]");
            if (!ad.isEnabled()) {
                boolean asked = ad.enable();
                long until = System.currentTimeMillis() + 8_000;
                while (!ad.isEnabled() && System.currentTimeMillis() < until) Thread.sleep(250);
                enabledByUs = ad.isEnabled();
                sb.append(" | pornire: ").append(asked ? "cerut" : "refuzat")
                        .append(enabledByUs ? ", pornit" : ", tot oprit (" + state(ad.getState()) + ")");
                if (!enabledByUs) return sb.toString();
            }
            if (le) sb.append("\nBLE 10 s: ").append(scanLe(ad));
            sb.append("\nClasic 12 s: ").append(scanClassic(c, ad));
        } catch (Exception e) {
            sb.append(" | eroare: ").append(e);
        } finally {
            if (enabledByUs) {
                ad.disable();
                sb.append("\nBluetooth Android oprit la loc");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("MissingPermission")
    private static String scanLe(BluetoothAdapter ad) throws InterruptedException {
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null) return "fara scanner";
        Map<String, String> found = new LinkedHashMap<>();
        int[] err = {0};
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                synchronized (found) {
                    String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                    found.put(r.getDevice().getAddress(), (name != null ? name : "?") + " " + r.getRssi() + " dBm");
                }
            }

            @Override
            public void onScanFailed(int code) {
                err[0] = code;
            }
        };
        sc.startScan(cb);
        Thread.sleep(BLE_MS);
        sc.stopScan(cb);
        if (err[0] != 0) return "scanare esuata (cod " + err[0] + ")";
        return list(found);
    }

    @SuppressWarnings("MissingPermission")
    private static String scanClassic(Context c, BluetoothAdapter ad) throws InterruptedException {
        Map<String, String> found = new LinkedHashMap<>();
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                short rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                synchronized (found) {
                    found.put(d.getAddress(), (d.getName() != null ? d.getName() : "?") + " " + rssi + " dBm");
                }
            }
        };
        c.registerReceiver(rx, new IntentFilter(BluetoothDevice.ACTION_FOUND), null,
                new Handler(Looper.getMainLooper()));
        try {
            if (!ad.startDiscovery()) return "pornirea cautarii refuzata";
            Thread.sleep(CLASSIC_MS);
            ad.cancelDiscovery();
        } finally {
            c.unregisterReceiver(rx);
        }
        return list(found);
    }

    private static String list(Map<String, String> found) {
        synchronized (found) {
            if (found.isEmpty()) return "nimic";
            StringBuilder sb = new StringBuilder().append(found.size()).append(" dispozitive");
            int n = 0;
            for (Map.Entry<String, String> e : found.entrySet()) {
                if (n++ == MAX_LIST) break;
                sb.append("\n  ").append(e.getKey()).append(" ").append(e.getValue());
            }
            return sb.toString();
        }
    }

    private static String state(int s) {
        switch (s) {
            case BluetoothAdapter.STATE_ON: return "pornit";
            case BluetoothAdapter.STATE_OFF: return "oprit";
            case BluetoothAdapter.STATE_TURNING_ON: return "porneste";
            case BluetoothAdapter.STATE_TURNING_OFF: return "se opreste";
            default: return String.valueOf(s);
        }
    }
}
