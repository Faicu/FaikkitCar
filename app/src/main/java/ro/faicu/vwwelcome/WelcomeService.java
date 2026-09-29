package ro.faicu.vwwelcome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Serviciu permanent care detecteaza trezirea din hibernare.
 *
 * Principiu: la fiecare 5 secunde notam momentul pe ceasul elapsedRealtime
 * (care merge si in hibernare). Daca intre doua verificari au trecut mult mai
 * mult de 5 secunde, inseamna ca unitatea a fost "inghetata" si tocmai s-a
 * trezit -> redam sunetul. Nu depinde de niciun semnal specific Teyes.
 */
public class WelcomeService extends Service {
    private static final String TAG = "VWWelcome";
    private static final long TICK_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTick;
    private int count;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, WelcomeService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());

        // Caz 1: procesul a fost omorat si repornit (sau pornire la rece dupa boot).
        long now = System.currentTimeMillis();
        long alive = Prefs.lastAlive(this);
        if (alive > 0 && now - alive > thresholdMs()) {
            onWake("pornire proces, " + (now - alive) / 1000 + " s oprit");
        }
        Prefs.setLastAlive(this, now);

        lastTick = SystemClock.elapsedRealtime();
        handler.postDelayed(tick, TICK_MS);
    }

    // Caz 2: procesul a supravietuit, dar unitatea a fost in hibernare.
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            long gap = now - lastTick;
            lastTick = now;

            if (gap > thresholdMs()) {
                onWake("trezire din hibernare, " + gap / 1000 + " s");
            }
            // Scriem pe disc doar la ~15 s, ca sa nu uzam memoria interna.
            if (++count % 3 == 0 || gap > TICK_MS * 2) {
                Prefs.setLastAlive(WelcomeService.this, System.currentTimeMillis());
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private long thresholdMs() {
        return Prefs.thresholdSec(this) * 1000L;
    }

    private void onWake(String reason) {
        String when = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(new Date());
        Prefs.setLastWake(this, when + " (" + reason + ")");
        Log.i(TAG, "Wake detected: " + reason);
        // 2,5 s pauza: lasam amplificatorul si audio-ul sa porneasca.
        Player.play(this, 2500);
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
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        Prefs.setLastAlive(this, System.currentTimeMillis());
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
