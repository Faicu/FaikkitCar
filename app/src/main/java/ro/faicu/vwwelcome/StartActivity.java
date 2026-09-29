package ro.faicu.vwwelcome;

import android.app.Activity;
import android.os.Bundle;

/**
 * Intrare invizibila pentru lista de autostart Teyes: la somn unitatea inchide aplicatiile,
 * iar dupa trezire nu vine BOOT_COMPLETED. Pornim serviciul, care vede in onCreate ca
 * ultima activitate e veche si reda sunetul, apoi ne inchidem fara sa aratam nimic.
 */
public class StartActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.log(this, "Pornit din autostart");
        WelcomeService.start(this);
        finish();
    }
}
