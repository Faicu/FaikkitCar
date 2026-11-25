package ro.faicu.faikkitcar;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 25.11: test pentru Bluetooth clasic (SPP/RFCOMM) catre adaptorul OBD2, in locul BLE (traficul GATT
 * umfla system_server). Doar comanda interna `@spp [adresa]`: dispozitivele asociate, cautarea clasica
 * (12 s), apoi la cele numite ca un adaptor OBD (sau la adresa data) conectarea RFCOMM si `ATZ` / `ATI`.
 * Nu asociaza nimic (createBond doar daca `@spp bond <adresa>`).
 */
final class SppProbe {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private SppProbe() {
    }

    @SuppressLint("MissingPermission")
    static String run(Context c, String args) {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) return "Bluetooth Android oprit";
        StringBuilder sb = new StringBuilder();
        String a = args.trim();
        try {
            if (a.startsWith("bond ")) {
                BluetoothDevice d = ad.getRemoteDevice(a.substring(5).trim().toUpperCase());
                sb.append("createBond: ").append(d.createBond()).append(", stare ").append(d.getBondState());
                Thread.sleep(8_000);
                return sb.append(" -> ").append(d.getBondState()).toString();
            }
            sb.append("Asociate:");
            for (BluetoothDevice d : ad.getBondedDevices()) {
                sb.append("\n  ").append(d.getAddress()).append(" ").append(d.getName()).append(" tip ").append(d.getType());
            }
            Map<String, String> found = new LinkedHashMap<>();
            if (a.isEmpty() || !a.contains(":")) discover(c, ad, found);
            sb.append("\nClasic gasite: ").append(found.size());
            for (Map.Entry<String, String> e : found.entrySet()) sb.append("\n  ").append(e.getKey()).append(" ").append(e.getValue());
            if (a.contains(":")) found.put(a.toUpperCase(), "dat");
            for (Map.Entry<String, String> e : found.entrySet()) {
                String n = e.getValue().toLowerCase();
                if (!a.contains(":") && !(n.contains("vlinker") || n.contains("obd") || n.contains("elm"))) continue;
                sb.append("\nConectare ").append(e.getKey()).append(": ").append(talk(ad.getRemoteDevice(e.getKey())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            sb.append("\neroare ").append(e);
        }
        return sb.toString();
    }

    @SuppressLint("MissingPermission")
    private static void discover(Context c, BluetoothAdapter ad, Map<String, String> found) throws InterruptedException {
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                synchronized (found) {
                    found.put(d.getAddress(), (d.getName() != null ? d.getName() : "?")
                            + " " + i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE) + " dBm");
                }
            }
        };
        c.registerReceiver(rx, new IntentFilter(BluetoothDevice.ACTION_FOUND), null, new Handler(Looper.getMainLooper()));
        try {
            if (ad.startDiscovery()) Thread.sleep(12_000);
            ad.cancelDiscovery();
        } finally {
            c.unregisterReceiver(rx);
        }
    }

    @SuppressLint("MissingPermission")
    private static String talk(BluetoothDevice d) {
        BluetoothSocket s = null;
        long t0 = System.currentTimeMillis();
        try {
            s = d.createInsecureRfcommSocketToServiceRecord(SPP);
            s.connect();
            long conn = System.currentTimeMillis() - t0;
            OutputStream o = s.getOutputStream();
            InputStream in = s.getInputStream();
            StringBuilder r = new StringBuilder("conectat in " + conn + " ms, asociere " + d.getBondState());
            for (String cmd : new String[]{"ATZ", "ATI", "ATRV", "0100"}) {
                o.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
                o.flush();
                StringBuilder b = new StringBuilder();
                long end = System.currentTimeMillis() + 2_500;
                while (System.currentTimeMillis() < end) {
                    if (in.available() > 0) {
                        int ch = in.read();
                        if (ch < 0) break;
                        if (ch == '>') break;
                        b.append(ch == '\r' ? ' ' : (char) ch);
                    } else {
                        Thread.sleep(20);
                    }
                }
                r.append("\n    ").append(cmd).append(" -> ").append(b.toString().trim());
            }
            return r.toString();
        } catch (Exception e) {
            return "esec dupa " + (System.currentTimeMillis() - t0) + " ms: " + e + ", asociere " + d.getBondState();
        } finally {
            try {
                if (s != null) s.close();
            } catch (Exception ignored) {
                // inchis
            }
        }
    }
}
