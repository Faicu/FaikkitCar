package ro.faicu.faikkitcar;

import android.app.Activity;
import android.os.Bundle;

/**
 * Intrare invizibila pentru lista de autostart Teyes, care o lanseaza la ACC ON. Pentru noi
 * e semnalul de contact pus: serviciul reda sunetul (protectia de 20 s evita dublarea cu
 * detectia prin pauza sau prin boot), apoi ne inchidem fara sa aratam nimic.
 */
public class StartActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.log(this, "Pornit din autostart");
        WelcomeService.startAccOn(this);
        // Masina sta pe loc: o versiune noua descarcata se propune acum (o atingere). Pornita
        // de aici, fereastra de confirmare are voie sa apara (aplicatia tocmai a fost vizibila).
        Updater.offerAtStart(this);
        finish();
    }
}
