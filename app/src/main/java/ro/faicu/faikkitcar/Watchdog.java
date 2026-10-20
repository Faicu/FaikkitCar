package ro.faicu.faikkitcar;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Alarma de rezerva: la fiecare minut cat navigatia e treaza, reporneste serviciul daca
 * procesul a fost omorat (20.10: omorat in somn, autostart-ul a ratat, 3 km pierduti).
 * Alarma nu trezeste unitatea; vine la prima trezire. O oprire fortata (force-stop) anuleaza
 * si alarma, deci atunci ramane doar autostart-ul.
 */
public class Watchdog extends BroadcastReceiver {
    private static final long INTERVAL_MS = 60_000;

    /** Serviciul ruleaza in procesul acesta (pus de WelcomeService). */
    static volatile boolean running;

    static void schedule(Context c) {
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Watchdog.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(AlarmManager.class).setRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, pi);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (running) return;
        long alive = Prefs.lastAlive(c);
        Prefs.log(c, "Alarma de rezerva: serviciul nu rula (ultima activitate "
                + (alive > 0 ? "acum " + (System.currentTimeMillis() - alive) / 1000 + " s" : "necunoscuta")
                + "), il pornesc");
        try {
            WelcomeService.start(c);
        } catch (RuntimeException e) {
            // Android 12+ nu lasa o alarma sa porneasca un serviciu foreground (navigatia e pe 10).
            Prefs.log(c, "Alarma de rezerva: pornirea a fost refuzata: " + e.getMessage());
        }
    }
}
