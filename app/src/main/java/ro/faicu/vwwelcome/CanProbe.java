package ro.faicu.vwwelcome;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sonda CAN: asculta toate codurile modulelor MAIN (0) si CANBUS (7) ale MainServer-ului FYT
 * (vezi Syu), ca sa aflam ce date primeste navigatia de la masina (Golf 6). Trimite la server
 * doar schimbarile, cel mult una pe secunda pentru fiecare cod, in linii "CAN m<modul>
 * c<cod> ...", cu ora exacta a schimbarii. Calibrarea adauga marcaje "CAN MARK ..." intre ele.
 */
final class CanProbe {
    static final long DURATION_MS = 5 * 60_000L;
    private static final int MAX_LINES = 5000;

    private static CanProbe running;

    private final Context c;
    private final Handler handler;
    private final Map<String, String> lastValue = new HashMap<>();
    private final Map<String, Long> lastSent = new HashMap<>();
    private final Map<String, String> pending = new HashMap<>();
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

    /** Marcaj de calibrare, cu ora apasarii, intre liniile sondei. */
    static synchronized void mark(String text) {
        if (running == null) return;
        long t = System.currentTimeMillis();
        CanProbe p = running;
        p.handler.post(() -> p.add("CAN MARK " + text, t));
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
        subscribeModule(toolkit, Syu.MODULE_CANBUS, concat(range(0, 400), range(1000, 1300)));
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
                String value = "i=" + Arrays.toString(ints)
                        + (flts != null && flts.length > 0 ? " f=" + Arrays.toString(flts) : "")
                        + (strs != null && strs.length > 0 ? " s=" + Arrays.toString(strs) : "");
                handler.post(() -> onValue(module, code, value, t));
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

    private void onValue(int module, int update, String value, long t) {
        String key = "m" + module + " c" + update;
        if (value.equals(lastValue.get(key))) return;
        lastValue.put(key, value);
        Long prev = lastSent.get(key);
        if (prev != null && t - prev < 1000) {
            pending.put(key, value); // o trimitem la urmatorul flush, ultima valoare castiga
            return;
        }
        lastSent.put(key, t);
        pending.remove(key);
        add("CAN " + key + " " + value, t);
    }

    private void add(String line, long t) {
        if (sent + lines.size() >= MAX_LINES) return;
        lines.add(line);
        times.add(t);
    }

    private void flush() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> e : pending.entrySet()) {
            lastSent.put(e.getKey(), now);
            add("CAN " + e.getKey() + " " + e.getValue(), now);
        }
        pending.clear();
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
        add("CAN sonda oprita, " + (sent + lines.size()) + " linii, " + lastValue.size() + " coduri vazute",
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

    private static int[] concat(int[] a, int[] b) {
        int[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
