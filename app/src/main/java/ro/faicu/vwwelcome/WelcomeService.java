package ro.faicu.vwwelcome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;

/**
 * Serviciu permanent care detecteaza trezirea din hibernare.
 *
 * Principiu: la fiecare 5 secunde notam momentul pe ceasul elapsedRealtime
 * (care merge si in hibernare). Daca intre doua verificari au trecut mult mai
 * mult de 5 secunde, inseamna ca unitatea a fost "inghetata" si tocmai s-a
 * trezit -> redam sunetul. Nu depinde de niciun semnal specific Teyes.
 */
public class WelcomeService extends Service {
    private static final long TICK_MS = 5000;
    // Pauzele mai lungi de atat intra in jurnal, chiar daca sunt sub prag.
    private static final long LOG_GAP_MS = 15_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTick;
    // Somnul total (sleptSec) la ultimul tick: diferenta arata daca intre timp s-a hibernat.
    private long sleptAtTick;
    private int count;

    // La trezire internetul revine abia dupa cateva secunde: atunci trimitem jurnalul adunat.
    private final ConnectivityManager.NetworkCallback netCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Uploader.kick(WelcomeService.this);
                }
            };

    private static final String EXTRA_ACC_ON = "acc_on";

    static void start(Context c) {
        c.startForegroundService(new Intent(c, WelcomeService.class));
    }

    /**
     * Autostart-ul Teyes lanseaza StartActivity la ACC ON, chiar si cand unitatea nu a
     * apucat sa hiberneze (oprire scurta: procesorul merge, nu exista nicio pauza de
     * detectat). Il tratam direct ca trezire.
     */
    static void startAccOn(Context c) {
        c.startForegroundService(new Intent(c, WelcomeService.class).putExtra(EXTRA_ACC_ON, true));
    }

    /** Cat a stat procesorul suspendat de la boot: elapsedRealtime include somnul, uptime nu. */
    static long sleptSec() {
        return (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()) / 1000;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        startForeground(1, buildNotification());

        // Caz 1: boot nou. Contorul de boot-uri nu depinde de ceas, deci merge
        // si cand ora nu e inca sincronizata dupa o pornire la rece.
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        int lastBoot = Prefs.lastBootCount(this);
        long now = System.currentTimeMillis();
        long alive = Prefs.lastAlive(this);
        Prefs.log(this, "Serviciu pornit: boot #" + boot + " (anterior #" + lastBoot + "), uptime "
                + SystemClock.elapsedRealtime() / 1000 + " s (din care somn " + sleptSec()
                + " s), ultima activitate "
                + (alive > 0 ? "acum " + (now - alive) / 1000 + " s" : "necunoscuta"));
        Prefs.log(this, "Info: " + Prefs.deviceInfo(this).replace("\n", " | "));
        if (boot >= 0 && lastBoot >= 0 && boot != lastBoot) {
            onWake("boot nou");
        } else if (alive > 0 && now - alive > thresholdMs()) {
            // Caz 2: procesul a fost omorat si repornit in acelasi boot. Daca l-a pornit
            // utilizatorul deschizand aplicatia, nu e o trezire.
            if (Prefs.startedFromUi(this)) {
                Prefs.log(this, "Pornit din aplicatie dupa " + (now - alive) / 1000
                        + " s oprit, fara sunet");
            } else {
                onWake("pornire proces, " + (now - alive) / 1000 + " s oprit");
            }
        }
        if (boot >= 0) Prefs.setLastBootCount(this, boot);
        Prefs.setLastAlive(this, now);

        lastTick = SystemClock.elapsedRealtime();
        sleptAtTick = sleptSec();
        handler.postDelayed(tick, TICK_MS);
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        // Nu poate fi primit din manifest (Android 8+), deci il ascultam aici.
        screen.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, screen);
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCallback);
        CanProbe.check(this);
        TripRecorder.check(this);
    }

    // Caz 3: procesul a supravietuit, dar unitatea a fost in hibernare.
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            long gap = now - lastTick;
            lastTick = now;

            if (gap > thresholdMs()) {
                onWake("trezire din hibernare, " + gap / 1000 + " s");
            } else if (gap > LOG_GAP_MS) {
                Prefs.log(WelcomeService.this, "Pauza " + gap / 1000 + " s (sub prag)");
            }
            sleptAtTick = sleptSec();
            // Scriem pe disc doar la ~15 s, ca sa nu uzam memoria interna.
            if (++count % 3 == 0 || gap > TICK_MS * 2) {
                Prefs.setLastAlive(WelcomeService.this, System.currentTimeMillis());
            }
            // Reincercam la ~30 s liniile ramase netrimise (ex. server indisponibil).
            if (count % 6 == 0 && (Prefs.outboxSize(WelcomeService.this) > 0
                    || PointQueue.size(WelcomeService.this) > 0)) {
                Uploader.kick(WelcomeService.this);
            }
            // Porneste / opreste sonda CAN dupa cum a fost activata din aplicatie.
            if (count % 6 == 0) {
                CanProbe.check(WelcomeService.this);
                TripRecorder.check(WelcomeService.this);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    // Notam in jurnal ecranul stins/aprins; la aprindere (sau deblocare) verificam imediat,
    // fara sa asteptam tick-ul.
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Prefs.log(c, "Ecran stins");
                return;
            }
            Prefs.log(c, Intent.ACTION_SCREEN_ON.equals(action) ? "Ecran aprins" : "Utilizator prezent");
            handler.removeCallbacks(tick);
            tick.run();
        }
    };

    private long thresholdMs() {
        return Prefs.thresholdSec(this) * 1000L;
    }

    private void onWake(String reason) {
        String result;
        if (Player.playedRecently()) {
            result = "ignorat, redat recent";
        } else {
            File f = Prefs.nextSound(this);
            if (f == null) {
                result = "niciun sunet ales";
            } else {
                // Pauza: lasam amplificatorul si audio-ul sa porneasca. Dupa o hibernare
                // adevarata porneste mai greu (se pierdea ~1 s la 5 s pauza), deci adaugam.
                boolean hibernated = sleptSec() - sleptAtTick >= 60;
                long delay = Prefs.delayMs(this) + (hibernated ? Prefs.sleepExtraMs(this) : 0);
                Player.play(this, f, delay, true);
                result = Prefs.soundName(f) + " (pauza " + delay / 1000.0 + " s"
                        + (hibernated ? ", dupa hibernare" : "") + ")";
            }
        }
        Prefs.addHistory(this, reason + " → " + result);
        Prefs.log(this, "TREZIRE: " + reason + " → " + result);
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                "svc", "Serviciu VW Welcome", NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(this, "svc")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("VW Welcome activ")
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Fiecare startForegroundService() cere un startForeground() in 5 s.
        startForeground(1, buildNotification());
        if (intent != null && intent.getBooleanExtra(EXTRA_ACC_ON, false)) {
            long sinceTick = (SystemClock.elapsedRealtime() - lastTick) / 1000;
            Prefs.log(this, "Autostart: somn total de la boot " + sleptSec() + " s, ultimul tick acum "
                    + sinceTick + " s");
            onWake("autostart (ACC ON)");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        unregisterReceiver(screenReceiver);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        Prefs.log(this, "Serviciu oprit");
        Prefs.setLastAlive(this, System.currentTimeMillis());
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
