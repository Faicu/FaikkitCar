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
 * km/h, c109 viteza x100, c105 tensiune x100, c106 kilometraj, c139 temperatura exterioara x10,
 * c104 litrii din rezervor (ultimul camp din cadrul Raise 0x41/2, dupa kilometraj).
 * Pentru starea live: clima (c11 AC, c49 AUTO, c21 ventilator, c27/c28 temperatura setata),
 * c1033 (probabil consumul instantaneu x10, L/100 km; de confirmat cu bordul), c101 centura,
 * c103 frana de mana, octetul de stare din 0x41/1 si marsarierul (modulul 0, c68 = [1, 1]).
 */
final class CanLink {
    private static final int RPM = 110, SPEED = 1031, SPEED100 = 109, VOLT = 105, ODO = 106, TEMP = 139,
            FUEL = 104, RAW = 1019, AC = 11, AUTO = 49, FAN = 21, TEMP_L = 27, TEMP_R = 28,
            INSTANT = 1033, BELT = 101, HANDBRAKE = 103, REVERSE_MAIN = 68;
    // Cadrele brute Raise 0x41 (date de bord: turatie, viteza, tensiune...) vin de la bord,
    // care e alimentat doar cu contactul pus; daca tac atatea secunde, contactul e luat.
    private static final long CONTACT_MS = 10_000;
    // Un salt de atatia litri in rezervor (alimentare) se noteaza in jurnal.
    private static final int FUEL_JUMP = 3;
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
    private volatile int rpm = -1, odo = -1, fuel = -1;
    // Valorile brute pentru starea live (serverul le traduce); -1 = necunoscut.
    private volatile int ac = -1, auto = -1, fan = -1, tempL = -1, tempR = -1, instant = -1,
            belt = -1, handbrake = -1, status41 = -1;
    private volatile boolean reverse;
    // Caderea de tensiune de la demaror: ultimele citiri ale tensiunii (timp, valoare) si
    // momentul pornirii motorului (turatia trece de 300); crankVolt() o calculeaza o data.
    private final long[] voltAt = new long[64];
    private final double[] voltVal = new double[64];
    private int voltN;
    private volatile long engineStartAt;
    private boolean crankTaken = true;
    private int fuelLogged = -1;
    // Momentul ultimei viteze din c109 (x100); c1031 (km/h intregi) o inlocuieste doar daca
    // c109 tace de 2 s, altfel viteza ar sari intre valoarea exacta si cea rotunjita.
    private volatile long speed100At;
    // Ultimul cadru de bord (0x41) sau ultima turatie/tensiune primita; 0 = niciodata.
    private volatile long dashAt;
    private Boolean contactLogged;

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
                        case RAW: onRaw(ints); break;
                        case AC: ac = v; break;
                        case AUTO: auto = v; break;
                        case FAN: fan = v; break;
                        case TEMP_L: tempL = v; break;
                        case TEMP_R: tempR = v; break;
                        case INSTANT: instant = v; break;
                        case BELT: belt = v; break;
                        case HANDBRAKE:
                            if (handbrake >= 0 && v != handbrake) logHandbrake("c103 " + handbrake + " -> " + v);
                            handbrake = v;
                            break;
                        case RPM:
                            // Doar o pornire vazuta (turatia era cunoscuta si mica), nu prima
                            // valoare primita cu motorul deja pornit.
                            if (rpm >= 0 && rpm <= 300 && v > 300) onEngineStart();
                            rpm = v;
                            dashAt = SystemClock.elapsedRealtime();
                            break;
                        case SPEED100: speed = v / 100.0; speed100At = SystemClock.elapsedRealtime(); break;
                        case SPEED:
                            if (SystemClock.elapsedRealtime() - speed100At > 2_000) speed = v;
                            break;
                        case VOLT:
                            volt = v / 100.0;
                            dashAt = SystemClock.elapsedRealtime();
                            recordVolt(dashAt, volt);
                            break;
                        case ODO: odo = v; break;
                        case TEMP: temp = v / 10.0; break;
                        case FUEL: onFuel(v); break;
                        default:
                            if (code >= 1 && code <= DOORS.length) doors[code - 1] = v;
                            break;
                    }
                }
            };
            for (int code : new int[] {RPM, SPEED, SPEED100, VOLT, ODO, TEMP, FUEL, RAW, 1, 2, 3, 4, 5,
                    AC, AUTO, FAN, TEMP_L, TEMP_R, INSTANT, BELT, HANDBRAKE}) {
                Syu.register(mod, cb, code);
            }
            // Marsarierul vine din modulul principal (camera), nu din CANBUS.
            IBinder main = Syu.module(toolkit, Syu.MODULE_MAIN);
            if (main != null) {
                Syu.register(main, new Syu.Callback() {
                    @Override
                    void onUpdate(int code, int[] ints, float[] flts, String[] strs) {
                        if (code == REVERSE_MAIN && ints != null && ints.length > 0) reverse = ints[0] == 1;
                    }
                }, REVERSE_MAIN);
            }
        } catch (Exception e) {
            Prefs.log(c, "Date masina: eroare " + e);
        }
    }

    /**
     * Cadru brut Raise (0x2E, comanda, lungime, date..., uneori precedat de 0xFF): 0x41 vine de
     * la bord doar cu contactul pus; 0x41/1 are octetul de stare (0x20 frana de mana eliberata,
     * 0x80 probabil luminile).
     */
    private void onRaw(int[] ints) {
        int o = ints[0] == 0x2E ? 0 : ints.length > 1 && ints[0] == 0xFF && ints[1] == 0x2E ? 1 : -1;
        if (o < 0 || ints.length < o + 2 || ints[o + 1] != 0x41) return;
        dashAt = SystemClock.elapsedRealtime();
        if (ints.length > o + 4 && ints[o + 3] == 1) {
            int v = ints[o + 4];
            if (status41 >= 0 && ((v ^ status41) & 0x20) != 0) {
                logHandbrake("0x41/1 bitul 0x20 " + ((v & 0x20) != 0 ? "1 (eliberata)" : "0 (trasa)"));
            }
            status41 = v;
        }
    }

    private synchronized void recordVolt(long t, double v) {
        voltAt[voltN % voltAt.length] = t;
        voltVal[voltN % voltVal.length] = v;
        voltN++;
    }

    private synchronized void onEngineStart() {
        engineStartAt = SystemClock.elapsedRealtime();
        crankTaken = false;
    }

    /**
     * Tensiunea minima la pornirea motorului (10 s inainte, 3 s dupa: demarorul), o singura data
     * pe pornire, la 3 s dupa ea; NaN pana atunci sau daca nu avem citiri. Sub ~9,6 V = baterie slaba.
     */
    synchronized double takeCrankVolt() {
        if (crankTaken || SystemClock.elapsedRealtime() - engineStartAt < 3_000) return Double.NaN;
        crankTaken = true;
        double min = Double.NaN;
        for (int i = 0; i < Math.min(voltN, voltAt.length); i++) {
            long dt = voltAt[i] - engineStartAt;
            if (dt >= -10_000 && dt <= 3_000 && voltVal[i] > 5 && !(voltVal[i] >= min)) min = voltVal[i];
        }
        if (!Double.isNaN(min)) {
            Prefs.log(c, "Pornire motor: baterie minim " + String.format(java.util.Locale.US, "%.2f", min) + " V");
        }
        return min;
    }

    /** Pana confirmam care cod e frana de mana: fiecare schimbare ajunge in jurnal. */
    private void logHandbrake(String what) {
        Prefs.log(c, "Frana de mana: " + what + " (c103=" + handbrake + ", 0x41/1=" + status41 + ")");
    }

    /** Prima valoare si salturile mari (alimentare) ajung in jurnal; restul doar in puncte. */
    private void onFuel(int v) {
        fuel = v;
        if (v <= 0) return;
        if (fuelLogged < 0 || Math.abs(v - fuelLogged) >= FUEL_JUMP) {
            Prefs.log(c, "Rezervor: " + v + " L" + (fuelLogged < 0 ? "" : " (inainte " + fuelLogged + " L)"));
            fuelLogged = v;
        }
    }

    /**
     * Contactul pus: datele de bord au venit in ultimele 10 s. Dedus (fara semnal ACC direct):
     * dupa ACC OFF scurt unitatea ramane treaza ~10 min, dar bordul tace. Schimbarile ajung
     * in jurnal, ca sa se poata verifica pe server.
     */
    boolean contact() {
        boolean on = dashFresh();
        if (contactLogged == null || contactLogged != on) {
            contactLogged = on;
            Prefs.log(c, "Contact: " + (on ? "pus" : "luat") + " (date de bord " + (on ? "primite" : "absente") + ")");
        }
        return on;
    }

    /** Datele de bord au venit in ultimele 10 s (fara jurnal; pentru UI). */
    boolean dashFresh() {
        return dashAt > 0 && SystemClock.elapsedRealtime() - dashAt < CONTACT_MS;
    }

    /** Litrii din rezervor; -1 daca nu stim. */
    int fuel() {
        return fuel;
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

    int ac() { return ac; }
    int auto() { return auto; }
    int fan() { return fan; }
    int tempLeft() { return tempL; }
    int tempRight() { return tempR; }
    /** c1033 brut (probabil L/100 km x10); -1 daca nu stim. */
    int instant() { return instant; }
    int belt() { return belt; }
    int handbrake() { return handbrake; }
    int status41() { return status41; }
    boolean reverse() { return reverse; }

    /** Indicii (in DOORS) usilor deschise acum. */
    java.util.List<Integer> openDoors() {
        java.util.List<Integer> open = new java.util.ArrayList<>();
        for (int i = 0; i < doors.length; i++) if (doors[i] == 1) open.add(i);
        return open;
    }
}
