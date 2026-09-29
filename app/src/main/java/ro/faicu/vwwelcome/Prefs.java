package ro.faicu.vwwelcome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** Setarile aplicatiei, pastrate intre porniri. */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    /** Sunetul ales e copiat aici, ca sa nu depinda de permisiuni pe fisierul original. */
    static File soundFile(Context c) {
        return new File(c.getFilesDir(), "welcome_sound");
    }

    /** Cate secunde trebuie sa stea contactul luat ca sa se considere o "pornire". */
    static int thresholdSec(Context c) {
        return sp(c).getInt("threshold", 60);
    }

    static void setThresholdSec(Context c, int sec) {
        sp(c).edit().putInt("threshold", Math.max(30, sec)).apply();
    }

    /** Ultimul moment (ceas real) in care serviciul a rulat. */
    static long lastAlive(Context c) {
        return sp(c).getLong("alive", 0);
    }

    static void setLastAlive(Context c, long t) {
        sp(c).edit().putLong("alive", t).apply();
    }

    static String lastWake(Context c) {
        return sp(c).getString("wake", "niciuna inca");
    }

    static void setLastWake(Context c, String s) {
        sp(c).edit().putString("wake", s).apply();
    }
}
