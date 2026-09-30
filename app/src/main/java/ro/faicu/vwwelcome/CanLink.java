package ro.faicu.vwwelcome;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Legatura permanenta la datele masinii din MainServer (modulul CANBUS), pentru calatorii.
 * Codurile au fost gasite cu CanProbe pe Golf 6 (vezi CLAUDE.md): c110 turatie, c1031 viteza
 * km/h, c109 viteza x100, c105 tensiune x100, c106 kilometraj, c139 temperatura exterioara x10.
 */
final class CanLink {
    private static final int RPM = 110, SPEED = 1031, SPEED100 = 109, VOLT = 105, ODO = 106, TEMP = 139;
    // O valoare mai veche de atat nu mai descrie masina (ex. MainServer nu mai trimite).
    private static final long FRESH_MS = 10_000;

    private static CanLink instance;

    private final Context c;
    private final Handler handler;
    private boolean bound;

    // Ultimele valori si momentul lor (elapsedRealtime); citite din alt fir, deci volatile.
    private volatile double speed = Double.NaN, volt = Double.NaN, temp = Double.NaN;
    private volatile int rpm = -1, odo = -1;
    private volatile long speedAt, rpmAt, voltAt, tempAt, odoAt;

    private CanLink(Context c) {
        this.c = c.getApplicationContext();
        HandlerThread t = new HandlerThread("CanLink");
        t.start();
        handler = new Handler(t.getLooper());
    }

    static synchronized CanLink get(Context c) {
        if (instance == null) {
            instance = new CanLink(c);
            instance.handler.post(instance::bind);
        }
        return instance;
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder toolkit) {
            handler.post(() -> subscribe(toolkit));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // bindService cu BIND_AUTO_CREATE se reconecteaza singur cand revine MainServer.
        }
    };

    private void bind() {
        try {
            bound = c.bindService(Syu.toolkitIntent(), conn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            bound = false;
        }
        if (!bound) Prefs.log(c, "Date masina: MainServer indisponibil");
    }

    private void subscribe(IBinder toolkit) {
        try {
            IBinder mod = Syu.module(toolkit, Syu.MODULE_CANBUS);
            if (mod == null) {
                Prefs.log(c, "Date masina: modulul CANBUS lipseste");
                return;
            }
            Syu.Callback cb = new Syu.Callback() {
                @Override
                void onUpdate(int code, int[] ints, float[] flts, String[] strs) {
                    if (ints == null || ints.length == 0) return;
                    long now = SystemClock.elapsedRealtime();
                    int v = ints[0];
                    switch (code) {
                        case RPM: rpm = v; rpmAt = now; break;
                        case SPEED100: speed = v / 100.0; speedAt = now; break;
                        case SPEED: if (now - speedAt > 2_000) { speed = v; speedAt = now; } break;
                        case VOLT: volt = v / 100.0; voltAt = now; break;
                        case ODO: odo = v; odoAt = now; break;
                        case TEMP: temp = v / 10.0; tempAt = now; break;
                        default: break;
                    }
                }
            };
            for (int code : new int[] {RPM, SPEED, SPEED100, VOLT, ODO, TEMP}) Syu.register(mod, cb, code);
        } catch (Exception e) {
            Prefs.log(c, "Date masina: eroare " + e);
        }
    }

    private static boolean fresh(long at) {
        return at > 0 && SystemClock.elapsedRealtime() - at < FRESH_MS;
    }

    /** Viteza de la masina, km/h; NaN daca nu stim. */
    double speed() {
        return fresh(speedAt) ? speed : Double.NaN;
    }

    /** Turatia; -1 daca nu stim. MainServer trimite doar schimbari, deci o valoare stabila
     * poate fi "veche": o consideram valabila cat timp motorul pare pornit (> 0). */
    int rpm() {
        return fresh(rpmAt) || rpm > 0 ? rpm : -1;
    }

    double volt() {
        return !Double.isNaN(volt) && voltAt > 0 ? volt : Double.NaN;
    }

    double temp() {
        return tempAt > 0 ? temp : Double.NaN;
    }

    int odo() {
        return odoAt > 0 ? odo : -1;
    }
}
