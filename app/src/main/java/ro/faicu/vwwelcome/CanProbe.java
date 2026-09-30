package ro.faicu.vwwelcome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sonda CAN: se conecteaza la MainServer-ul FYT (com.syu.ms, serviciul "com.syu.ms.toolkit")
 * si inregistreaza un callback pe toate codurile modulelor MAIN (0) si CANBUS (7), ca sa vedem
 * ce date primeste navigatia de la masina (Golf 6). Trimite la server doar schimbarile, cel
 * mult una pe secunda pentru fiecare cod, in linii "CAN m<modul> c<cod> ...".
 *
 * Protocolul (AIDL com.syu.ipc, fara permisiuni) e documentat public de FYTCanbusMonitor si
 * chrisuthe/7870-Projects: IRemoteToolkit.getRemoteModule = 1; IRemoteModule.register = 3
 * (callback, cod, 1); IModuleCallback.update = 1 (cod, int[], float[], String[]).
 */
final class CanProbe {
    static final long DURATION_MS = 5 * 60_000L;
    private static final String TOOLKIT = "com.syu.ipc.IRemoteToolkit";
    private static final String MODULE = "com.syu.ipc.IRemoteModule";
    private static final String CALLBACK = "com.syu.ipc.IModuleCallback";
    private static final int MAX_LINES = 5000;

    private static CanProbe running;

    private final Context c;
    private final Handler handler;
    private final Map<String, String> lastValue = new HashMap<>();
    private final Map<String, Long> lastSent = new HashMap<>();
    private final Map<String, String> pending = new HashMap<>();
    private final List<String> buffer = new ArrayList<>();
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

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder toolkit) {
            handler.post(() -> subscribe(toolkit));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            add("CAN sonda: MainServer deconectat");
        }
    };

    private void bind() {
        Intent i = new Intent("com.syu.ms.toolkit")
                .setComponent(new ComponentName("com.syu.ms", "app.ToolkitService"));
        try {
            bound = c.bindService(i, conn, Context.BIND_AUTO_CREATE);
            add("CAN sonda pornita, legare la com.syu.ms: " + (bound ? "ok" : "refuzata"));
        } catch (Exception e) {
            add("CAN sonda: eroare la legare " + e);
        }
        handler.postDelayed(this::flush, 5_000);
    }

    private void subscribe(IBinder toolkit) {
        add("CAN sonda conectata la MainServer");
        subscribeModule(toolkit, 0, range(0, 200));
        int[] can = concat(range(0, 400), range(1000, 1300));
        subscribeModule(toolkit, 7, can);
    }

    private void subscribeModule(IBinder toolkit, int module, int[] codes) {
        IBinder mod;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOOLKIT);
            data.writeInt(module);
            toolkit.transact(1, data, reply, 0);
            reply.readException();
            mod = reply.readStrongBinder();
        } catch (Exception e) {
            add("CAN sonda: modul " + module + " eroare " + e);
            return;
        } finally {
            data.recycle();
            reply.recycle();
        }
        if (mod == null) {
            add("CAN sonda: modul " + module + " indisponibil");
            return;
        }
        Callback cb = new Callback(module);
        int ok = 0;
        for (int code : codes) {
            Parcel d = Parcel.obtain();
            try {
                d.writeInterfaceToken(MODULE);
                d.writeStrongBinder(cb);
                d.writeInt(code);
                d.writeInt(1);
                mod.transact(3, d, null, IBinder.FLAG_ONEWAY);
                ok++;
            } catch (RemoteException e) {
                break;
            } finally {
                d.recycle();
            }
        }
        add("CAN sonda: modul " + module + ", " + ok + "/" + codes.length + " coduri inregistrate");
    }

    /** Callback-ul primit de la MainServer; ruleaza pe firele binder, deci trecem pe handler. */
    private final class Callback extends Binder {
        private final int module;

        Callback(int module) {
            this.module = module;
            attachInterface(null, CALLBACK);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(CALLBACK);
                return true;
            }
            if (code != 1) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CALLBACK);
            int update = data.readInt();
            int[] ints = data.createIntArray();
            float[] flts = data.createFloatArray();
            String[] strs = data.createStringArray();
            String value = "i=" + Arrays.toString(ints)
                    + (flts != null && flts.length > 0 ? " f=" + Arrays.toString(flts) : "")
                    + (strs != null && strs.length > 0 ? " s=" + Arrays.toString(strs) : "");
            handler.post(() -> onUpdate(module, update, value));
            return true;
        }
    }

    private void onUpdate(int module, int update, String value) {
        String key = "m" + module + " c" + update;
        if (value.equals(lastValue.get(key))) return;
        lastValue.put(key, value);
        long now = System.currentTimeMillis();
        Long prev = lastSent.get(key);
        if (prev != null && now - prev < 1000) {
            pending.put(key, value); // o trimitem la urmatorul flush, ultima valoare castiga
            return;
        }
        lastSent.put(key, now);
        pending.remove(key);
        add("CAN " + key + " " + value);
    }

    private void add(String line) {
        if (sent + buffer.size() >= MAX_LINES) return;
        buffer.add(line);
    }

    private void flush() {
        for (Map.Entry<String, String> e : pending.entrySet()) {
            lastSent.put(e.getKey(), System.currentTimeMillis());
            add("CAN " + e.getKey() + " " + e.getValue());
        }
        pending.clear();
        if (!buffer.isEmpty()) {
            Prefs.remoteOnly(c, new ArrayList<>(buffer));
            sent += buffer.size();
            buffer.clear();
        }
        synchronized (CanProbe.class) {
            if (running == this) handler.postDelayed(this::flush, 5_000);
        }
    }

    private void stop() {
        add("CAN sonda oprita, " + (sent + buffer.size()) + " linii, " + lastValue.size() + " coduri vazute");
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
