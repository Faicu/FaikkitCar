package ro.faicu.faikkitcar;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test pentru volumul Teyes (02.11): volumul e tinut de com.syu.ms, nu de Android
 * (setStreamVolume e ignorat). Cautam codul lui in modulele MainServer. Doar comanda interna
 * `@syu` prin car-cmd.ts:
 *   @syu watch <s> [modul,...]   abonare la codurile 0..199 (implicit modulele 0, 4), valorile
 *                                initiale si apoi fiecare schimbare (schimbi volumul intre timp)
 *   @syu get <modul> <cod>       valoarea curenta
 *   @syu cmd <modul> <cod> [int...]  trimite o comanda (atentie: poate opri com.syu.ms)
 */
final class SyuProbe {
    private static final int CODES = 200;

    private SyuProbe() {}

    static String run(Context c, String args) {
        String[] a = args.trim().isEmpty() ? new String[0] : args.trim().split("\\s+");
        CountDownLatch ready = new CountDownLatch(1);
        IBinder[] toolkit = new IBinder[1];
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder b) {
                toolkit[0] = b;
                ready.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {}
        };
        try {
            if (!c.bindService(Syu.toolkitIntent(), conn, Context.BIND_AUTO_CREATE)) return "MainServer indisponibil";
            if (!ready.await(5, TimeUnit.SECONDS)) return "MainServer nu raspunde";
            if (a.length == 0) return "folosire: watch <s> [module] | get <m> <cod> | cmd <m> <cod> [int...]";
            switch (a[0]) {
                case "get": {
                    IBinder m = Syu.module(toolkit[0], Integer.parseInt(a[1]));
                    if (m == null) return "modulul lipseste";
                    int[] v = Syu.get(m, Integer.parseInt(a[2]));
                    return "m" + a[1] + " c" + a[2] + " = " + (v == null ? "nimic" : Arrays.toString(v));
                }
                case "cmd": {
                    IBinder m = Syu.module(toolkit[0], Integer.parseInt(a[1]));
                    if (m == null) return "modulul lipseste";
                    int[] ints = new int[a.length - 3];
                    for (int i = 3; i < a.length; i++) ints[i - 3] = Integer.parseInt(a[i]);
                    Syu.cmd(m, Integer.parseInt(a[2]), ints, null);
                    return "trimis m" + a[1] + " c" + a[2] + " " + Arrays.toString(ints);
                }
                case "watch":
                    return watch(toolkit[0], a.length > 1 ? Integer.parseInt(a[1]) : 20,
                            a.length > 2 ? a[2] : "0,4");
                default:
                    return "comanda necunoscuta: " + a[0];
            }
        } catch (Exception e) {
            return "eroare: " + e;
        } finally {
            try {
                c.unbindService(conn);
            } catch (Exception ignored) {
                // nu era legat
            }
        }
    }

    private static String watch(IBinder toolkit, int seconds, String modules) throws Exception {
        StringBuilder sb = new StringBuilder();
        long start = SystemClock.elapsedRealtime();
        // Primele 1,5 s = valorile initiale trimise la abonare; dupa = doar valorile noi.
        Map<String, String> initial = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        List<String> changes = new ArrayList<>();
        List<Object[]> subs = new ArrayList<>();
        for (String ms : modules.split(",")) {
            int mod = Integer.parseInt(ms.trim());
            IBinder m = Syu.module(toolkit, mod);
            if (m == null) {
                sb.append("m").append(mod).append(" lipseste\n");
                continue;
            }
            Syu.Callback cb = new Syu.Callback() {
                @Override
                void onUpdate(int code, int[] ints, float[] flts, String[] strs) {
                    String v = (ints != null ? Arrays.toString(ints) : "")
                            + (flts != null && flts.length > 0 ? " f" + Arrays.toString(flts) : "")
                            + (strs != null && strs.length > 0 ? " s" + Arrays.toString(strs) : "");
                    String key = "m" + mod + " c" + code;
                    long t = SystemClock.elapsedRealtime() - start;
                    synchronized (initial) {
                        // La abonare unele coduri vin ca serie indexata ([i, valoare]); raportam doar
                        // perechile cod–valoare nevazute inca, dupa primele 1,5 s.
                        boolean fresh = seen.add(key + "=" + v);
                        if (t < 1_500) {
                            if (!initial.containsKey(key)) initial.put(key, v);
                        } else if (fresh && changes.size() < 300) {
                            changes.add(String.format("%.1f s %s = %s", t / 1000.0, key, v));
                        }
                    }
                }
            };
            for (int code = 0; code < CODES; code++) Syu.register(m, cb, code);
            subs.add(new Object[] {m, cb});
        }
        Thread.sleep(seconds * 1000L);
        for (Object[] s : subs) {
            for (int code = 0; code < CODES; code++) Syu.unregister((IBinder) s[0], (Syu.Callback) s[1], code);
        }
        synchronized (initial) {
            sb.append("Initiale (").append(initial.size()).append("):\n");
            for (Map.Entry<String, String> e : initial.entrySet()) {
                sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
            sb.append("Schimbari (").append(changes.size()).append("):\n");
            for (String s : changes) sb.append(s).append('\n');
        }
        return sb.toString();
    }
}
