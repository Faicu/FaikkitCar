package ro.faicu.vwwelcome;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sonda CAN, pornita doar de calibrare: asculta toate codurile modulelor MainServer-ului FYT
 * (vezi Syu) si tine local valorile si ultimele schimbari, pentru afisarea live. La server
 * ajung doar marcajele calibrarii ("CAN MARK ...", cu schimbarile pasului) si capturile
 * complete ("CAN SNAP ..."), nu fiecare schimbare.
 */
final class CanProbe {
    private static final int MAX_LINES = 5000;
    private static final int MAX_RECENT = 500;

    private static CanProbe running;

    /** O schimbare de valoare, pentru afisarea live din calibrare. */
    static final class Change {
        final long t;
        final String key, from, to;

        Change(long t, String key, String from, String to) {
            this.t = t;
            this.key = key;
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return key + ": " + shortValue(from) + " → " + shortValue(to);
        }
    }

    // Ultimele schimbari (fara prima valoare a fiecarui cod), citite de UI.
    private static final List<Change> recent = new ArrayList<>();

    private final Context c;
    private final Handler handler;
    // Citit si din UI (findValue), deci concurent.
    private final Map<String, String> lastValue = new ConcurrentHashMap<>();
    private final List<String> lines = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private int sent;
    private boolean bound;

    private CanProbe(Context c) {
        this.c = c.getApplicationContext();
        HandlerThread t = new HandlerThread("CanProbe");
        t.start();
        handler = new Handler(t.getLooper());
    }

    /** Porneste sonda daca e activata si nu ruleaza; o opreste cand i-a expirat timpul. */
    static synchronized void check(Context c) {
        boolean enabled = Prefs.canProbeUntil(c) > System.currentTimeMillis();
        if (enabled && running == null) {
            running = new CanProbe(c);
            running.handler.post(running::bind);
        } else if (!enabled && running != null) {
            running.handler.post(running::stop);
            running = null;
        }
    }

    static synchronized boolean isRunning() {
        return running != null;
    }

    /** Cate coduri au trimis deja o valoare (0 cat timp sonda abia porneste). */
    static synchronized int valueCount() {
        return running == null ? 0 : running.lastValue.size();
    }

    /** Schimbarile de dupa momentul t, fara codurile din ignore (chei "m7 c110" sau prefixe). */
    static List<Change> changesSince(long t, Set<String> ignore) {
        List<Change> out = new ArrayList<>();
        synchronized (recent) {
            for (Change ch : recent) {
                if (ch.t < t) continue;
                boolean skip = false;
                for (String ig : ignore) {
                    if (ch.key.equals(ig) || (ig.endsWith("*") && ch.key.startsWith(ig.substring(0, ig.length() - 1)))) {
                        skip = true;
                        break;
                    }
                }
                if (!skip) out.add(ch);
            }
        }
        return out;
    }

    /**
     * Codurile a caror valoare curenta se potriveste cu numarul dat (ex. litrii afisati de
     * Car Info): egal, x10 sau x100, cu o marja de o unitate pentru rotunjire.
     */
    static synchronized List<String> findValue(double target) {
        List<String> out = new ArrayList<>();
        if (running == null) return out;
        long[] wanted = {Math.round(target), Math.round(target * 10), Math.round(target * 100)};
        for (Map.Entry<String, String> e : running.lastValue.entrySet()) {
            String v = e.getValue();
            int a = v.indexOf("i=["), b = v.indexOf(']', a + 3);
            if (a < 0 || b < 0) continue;
            String[] parts = v.substring(a + 3, b).split(",\\s*");
            for (int i = 0; i < parts.length; i++) {
                long n;
                try {
                    n = Long.parseLong(parts[i].trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                for (long w : wanted) {
                    if (w > 0 && Math.abs(n - w) <= Math.max(1, w / 100)) {
                        out.add(e.getKey() + (parts.length > 1 ? "[" + i + "]" : "") + "=" + n);
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** "i=[1]" -> "1"; restul ramane cum e. */
    static String shortValue(String v) {
        if (v == null) return "?";
        if (v.startsWith("i=[") && v.endsWith("]") && v.indexOf(',') < 0) return v.substring(3, v.length() - 1);
        return v.length() > 60 ? v.substring(0, 60) + "…" : v;
    }

    /** Marcaj de calibrare, cu ora apasarii, intre liniile sondei. */
    static synchronized void mark(String text) {
        if (running == null) return;
        long t = System.currentTimeMillis();
        CanProbe p = running;
        p.handler.post(() -> p.add("CAN MARK " + text, t));
    }

    /** Toate valorile curente (in ordinea cheilor), ca sa le comparam pe server intre doua capturi. */
    static synchronized void snapshot(String label) {
        if (running == null) return;
        long t = System.currentTimeMillis();
        CanProbe p = running;
        p.handler.post(() -> {
            Map<String, String> all = new TreeMap<>(p.lastValue);
            all.remove("m0 c77"); // atingerile pe ecran
            p.add("CAN SNAP " + label + ": " + all.size() + " coduri", t);
            for (Map.Entry<String, String> e : all.entrySet()) {
                p.add("CAN SNAP " + label + " " + e.getKey() + " " + e.getValue(), t);
            }
        });
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder toolkit) {
            handler.post(() -> subscribe(toolkit));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            handler.post(() -> add("CAN sonda: MainServer deconectat", System.currentTimeMillis()));
        }
    };

    private void bind() {
        try {
            bound = c.bindService(Syu.toolkitIntent(), conn, Context.BIND_AUTO_CREATE);
            add("CAN sonda pornita, legare la com.syu.ms: " + (bound ? "ok" : "refuzata"),
                    System.currentTimeMillis());
        } catch (Exception e) {
            add("CAN sonda: eroare la legare " + e, System.currentTimeMillis());
        }
        handler.postDelayed(this::flush, 5_000);
    }

    private void subscribe(IBinder toolkit) {
        add("CAN sonda conectata la MainServer", System.currentTimeMillis());
        subscribeModule(toolkit, Syu.MODULE_MAIN, range(0, 200));
        // Tot intervalul CANBUS: nivelul rezervorului (afisat de "Car Info") nu era in 0-399/1000-1299.
        subscribeModule(toolkit, Syu.MODULE_CANBUS, range(0, 2000));
        // Si celelalte module cunoscute (radio, BT, sunet, DVR, OBD, CAN_UP...), la cautare.
        for (int m : new int[] {1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17}) {
            subscribeModule(toolkit, m, range(0, 200));
        }
    }

    private void subscribeModule(IBinder toolkit, int module, int[] codes) {
        long now = System.currentTimeMillis();
        IBinder mod;
        try {
            mod = Syu.module(toolkit, module);
        } catch (Exception e) {
            add("CAN sonda: modul " + module + " eroare " + e, now);
            return;
        }
        if (mod == null) {
            add("CAN sonda: modul " + module + " indisponibil", now);
            return;
        }
        Syu.Callback cb = new Syu.Callback() {
            @Override
            void onUpdate(int code, int[] ints, float[] flts, String[] strs) {
                long t = System.currentTimeMillis();
                String key;
                String value;
                if (module == Syu.MODULE_CANBUS && code == 1019 && ints != null && ints.length > 2 && ints[0] == 0x2E) {
                    // Cadru brut Raise (0x2E, comanda, lungime, date...): cheie separata pe comanda,
                    // altfel cadrele diferite s-ar "schimba" intre ele la fiecare mesaj.
                    key = String.format(java.util.Locale.US, "m7 raw 0x%02x", ints[1])
                            // 0x41 (date de bord) are subcomenzi diferite in primul octet de date.
                            + (ints[1] == 0x41 && ints.length > 3 ? "/" + ints[3] : "");
                    value = "i=" + Arrays.toString(Arrays.copyOfRange(ints, 2, ints.length));
                } else {
                    key = "m" + module + " c" + code;
                    value = "i=" + Arrays.toString(ints)
                            + (flts != null && flts.length > 0 ? " f=" + Arrays.toString(flts) : "")
                            + (strs != null && strs.length > 0 ? " s=" + Arrays.toString(strs) : "");
                }
                handler.post(() -> onValue(key, value, t));
            }
        };
        int ok = 0;
        for (int code : codes) {
            try {
                Syu.register(mod, cb, code);
                ok++;
            } catch (Exception e) {
                break;
            }
        }
        add("CAN sonda: modul " + module + ", " + ok + "/" + codes.length + " coduri inregistrate", now);
    }

    private void onValue(String key, String value, long t) {
        String old = lastValue.get(key);
        if (value.equals(old)) return;
        lastValue.put(key, value);
        if (old == null) return;
        synchronized (recent) {
            recent.add(new Change(t, key, old, value));
            if (recent.size() > MAX_RECENT) recent.remove(0);
        }
    }

    private void add(String line, long t) {
        if (sent + lines.size() >= MAX_LINES) return;
        lines.add(line);
        times.add(t);
    }

    private void flush() {
        if (!lines.isEmpty()) {
            Prefs.remoteOnly(c, new ArrayList<>(lines), new ArrayList<>(times));
            sent += lines.size();
            lines.clear();
            times.clear();
        }
        synchronized (CanProbe.class) {
            if (running == this) handler.postDelayed(this::flush, 5_000);
        }
    }

    private void stop() {
        add("CAN sonda oprita, " + lastValue.size() + " coduri vazute",
                System.currentTimeMillis());
        if (bound) {
            try {
                c.unbindService(conn);
            } catch (Exception ignored) {
            }
        }
        flush();
        handler.getLooper().quitSafely();
    }

    private static int[] range(int from, int to) {
        int[] r = new int[to - from];
        for (int i = 0; i < r.length; i++) r[i] = from + i;
        return r;
    }
}
