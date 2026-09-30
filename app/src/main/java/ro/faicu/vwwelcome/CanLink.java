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
    // Usile, din calibrarea ghidata: c1 sofer, c2 pasager fata, c3 spate stanga, c4 spate
    // dreapta, c5 portbagaj (1 = deschis).
    // Cu diacritice: textele sunt rostite de TextToSpeech.
    static final String[] DOORS = {"ușa șoferului", "ușa pasagerului", "ușa din spate stânga",
            "ușa din spate dreapta", "portbagajul"};
    private final int[] doors = new int[DOORS.length];

    private static CanLink instance;

    private final Context c;
    private final Handler handler;

    // Ultimele valori primite; citite din alt fir, deci volatile. MainServer trimite doar
    // schimbarile, asa ca o valoare "veche" e tot cea curenta (ex. viteza 0 cat stai pe loc,
    // turatia 0 cu motorul oprit). NaN / -1 = nu am primit inca nimic.
    private volatile double speed = Double.NaN, volt = Double.NaN, temp = Double.NaN;
    private volatile int rpm = -1, odo = -1;
    // Momentul ultimei viteze din c109 (x100); c1031 (km/h intregi) o inlocuieste doar daca
    // c109 tace de 2 s, altfel viteza ar sari intre valoarea exacta si cea rotunjita.
    private volatile long speed100At;

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
        boolean bound;
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
                    int v = ints[0];
                    switch (code) {
                        case RPM: rpm = v; break;
                        case SPEED100: speed = v / 100.0; speed100At = SystemClock.elapsedRealtime(); break;
                        case SPEED:
                            if (SystemClock.elapsedRealtime() - speed100At > 2_000) speed = v;
                            break;
                        case VOLT: volt = v / 100.0; break;
                        case ODO: odo = v; break;
                        case TEMP: temp = v / 10.0; break;
                        default:
                            if (code >= 1 && code <= DOORS.length) doors[code - 1] = v;
                            break;
                    }
                }
            };
            for (int code : new int[] {RPM, SPEED, SPEED100, VOLT, ODO, TEMP, 1, 2, 3, 4, 5}) {
                Syu.register(mod, cb, code);
            }
        } catch (Exception e) {
            Prefs.log(c, "Date masina: eroare " + e);
        }
    }

    /** Viteza de la masina, km/h; NaN daca nu stim. */
    double speed() {
        return speed;
    }

    /** Turatia; -1 daca nu stim. */
    int rpm() {
        return rpm;
    }

    /** Tensiunea bateriei, V; NaN daca nu stim. */
    double volt() {
        return volt;
    }

    /** Temperatura exterioara, °C; NaN daca nu stim. */
    double temp() {
        return temp;
    }

    /** Kilometrajul; -1 daca nu stim. */
    int odo() {
        return odo;
    }

    /** Indicii (in DOORS) usilor deschise acum. */
    java.util.List<Integer> openDoors() {
        java.util.List<Integer> open = new java.util.ArrayList<>();
        for (int i = 0; i < doors.length; i++) if (doors[i] == 1) open.add(i);
        return open;
    }
}
