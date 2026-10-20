package ro.faicu.faikkitcar;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Ancora de prioritate, fara sa citeasca vreo notificare: cu „Acces la notificari” dat, sistemul
 * ramane legat permanent de acest serviciu, deci procesul nostru urca in prioritate (20.10:
 * navigatia ramasese fara memorie si ne omora la 200, ca pe orice serviciu), iar daca ne omoara
 * totusi, sistemul reface legatura si ne reporneste, independent de autostart-ul Teyes.
 */
public class KeepAliveListener extends NotificationListenerService {
    static boolean granted(Context c) {
        return c.getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(new ComponentName(c, KeepAliveListener.class));
    }

    @Override
    public void onListenerConnected() {
        if (!Watchdog.running) {
            Prefs.log(this, "Acces la notificari: sistemul ne-a pornit, pornesc serviciul");
            try {
                WelcomeService.start(this);
            } catch (RuntimeException e) {
                Prefs.log(this, "Acces la notificari: pornirea serviciului a fost refuzata: " + e.getMessage());
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        // Legatura cazuta (ex. procesul repornit): o cerem inapoi.
        requestRebind(new ComponentName(this, KeepAliveListener.class));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Nu ne intereseaza notificarile.
    }
}
