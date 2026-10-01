package ro.faicu.faikkitcar.panel;

import android.content.Context;
import android.content.SharedPreferences;

/** Ce tine minte aplicatia: sesiunea de login si cateva preferinte de afisare. */
final class Store {
    private Store() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("panel", Context.MODE_PRIVATE);
    }

    static String token(Context c) {
        return sp(c).getString("token", "");
    }

    static void setToken(Context c, String token) {
        sp(c).edit().putString("token", token).apply();
    }

    static boolean hideIdle(Context c) {
        return sp(c).getBoolean("hide_idle", false);
    }

    static void setHideIdle(Context c, boolean on) {
        sp(c).edit().putBoolean("hide_idle", on).apply();
    }
}
