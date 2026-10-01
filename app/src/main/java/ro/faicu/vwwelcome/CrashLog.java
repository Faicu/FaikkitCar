package ro.faicu.vwwelcome;

import android.content.Context;
import android.util.Log;

/**
 * Noteaza in jurnal eroarea care opreste aplicatia; linia ajunge la server la urmatoarea
 * pornire. Fara asta, o cadere apare in jurnal doar ca o noua "Serviciu pornit".
 */
final class CrashLog {
    private static boolean installed;

    private CrashLog() {}

    static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        Context c = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                StringBuilder sb = new StringBuilder("CADERE: ").append(e);
                StackTraceElement[] st = e.getStackTrace();
                for (int i = 0; i < Math.min(st.length, 6); i++) sb.append(" | at ").append(st[i]);
                Throwable cause = e.getCause();
                if (cause != null) sb.append(" | cauza: ").append(cause);
                Prefs.log(c, sb.toString());
                Prefs.flushNow(c);
            } catch (Throwable t) {
                Log.e("FaikkitCar", "CrashLog", t);
            }
            if (previous != null) previous.uncaughtException(thread, e);
        });
    }
}
