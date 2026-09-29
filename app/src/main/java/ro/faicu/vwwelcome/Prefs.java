package ro.faicu.vwwelcome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Arrays;
import java.util.Random;

/** Setarile aplicatiei, pastrate intre porniri. */
final class Prefs {
    private static final int HISTORY_MAX = 20;
    private static final Random RANDOM = new Random();

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    /**
     * Sunetele alese sunt copiate aici, ca sa nu depinda de permisiuni pe fisierul original.
     * Numele fisierului e "<timp>_<nume original>", ca sa pastram ordinea adaugarii.
     */
    static File soundsDir(Context c) {
        File dir = new File(c.getFilesDir(), "sounds");
        dir.mkdirs();
        // Versiunea 1 pastra un singur sunet in filesDir/welcome_sound.
        File old = new File(c.getFilesDir(), "welcome_sound");
        if (old.exists()) old.renameTo(new File(dir, "0_sunet_initial"));
        return dir;
    }

    static File[] sounds(Context c) {
        File[] files = soundsDir(c).listFiles(File::isFile);
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    /** Numele afisat al unui sunet, fara prefixul de timp. */
    static String soundName(File f) {
        String n = f.getName();
        int i = n.indexOf('_');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    static boolean randomOrder(Context c) {
        return sp(c).getBoolean("random", false);
    }

    static void setRandomOrder(Context c, boolean random) {
        sp(c).edit().putBoolean("random", random).apply();
    }

    /** Alege urmatorul sunet: la rand sau aleatoriu, fara sa-l repete pe ultimul. */
    static File nextSound(Context c) {
        File[] s = sounds(c);
        if (s.length == 0) return null;
        int last = sp(c).getInt("last_sound", -1);
        int idx;
        if (randomOrder(c) && s.length > 1) {
            idx = RANDOM.nextInt(s.length - 1);
            if (idx >= last && last >= 0) idx++;
        } else {
            idx = (last + 1) % s.length;
        }
        sp(c).edit().putInt("last_sound", idx).apply();
        return s[idx];
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

    /** Contorul de boot-uri Android vazut ultima data (-1 = necunoscut). */
    static int lastBootCount(Context c) {
        return sp(c).getInt("boot_count", -1);
    }

    static void setLastBootCount(Context c, int n) {
        sp(c).edit().putInt("boot_count", n).apply();
    }

    /** Ultimele treziri detectate, cea mai noua prima, cate una pe rand. */
    static String history(Context c) {
        return sp(c).getString("history", "");
    }

    static void addHistory(Context c, String line) {
        String h = line + "\n" + history(c);
        String[] lines = h.split("\n");
        if (lines.length > HISTORY_MAX) {
            h = String.join("\n", Arrays.copyOf(lines, HISTORY_MAX));
        }
        sp(c).edit().putString("history", h.trim()).apply();
    }

    static void clearHistory(Context c) {
        sp(c).edit().remove("history").apply();
    }
}
