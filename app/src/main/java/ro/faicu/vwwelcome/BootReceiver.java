package ro.faicu.vwwelcome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Porneste serviciul dupa boot-ul complet al navigatiei sau dupa actualizarea aplicatiei. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Prefs.log(c, "BootReceiver: " + intent.getAction());
        WelcomeService.start(c);
    }
}
