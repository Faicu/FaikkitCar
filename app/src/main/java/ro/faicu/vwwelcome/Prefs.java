package ro.faicu.vwwelcome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** Setarile aplicatiei, pastrate intre porniri. */
final class Prefs {
    private static final int HISTORY_MAX = 20;
    private static final int LOG_MAX = 100;
    // Liniile netrimise inca la server; peste atat le pierdem pe cele mai vechi.
    // Incape si un diagnostic intreg (pana la 1500 de linii).
    private static final int OUTBOX_MAX = 2000;
    private static final Random RANDOM = new Random();

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    /**
     * Jurnalul si istoricul stau separat de setari: "cfg" se rescrie la ~15 s (ora ultimei
     * activitati) si nu vrem sa rescriem de fiecare data si cei cativa KB de jurnal.
     */
    private static synchronized SharedPreferences journal(Context c) {
        SharedPreferences j = c.getSharedPreferences("journal", Context.MODE_PRIVATE);
        SharedPreferences cfg = sp(c);
        // Versiunile pana la 1.1.7 tineau jurnalul in "cfg": il mutam o singura data.
        if (cfg.contains("log") || cfg.contains("history")) {
            j.edit().putString("log", cfg.getString("log", ""))
                    .putString("history", cfg.getString("history", "")).commit();
            cfg.edit().remove("log").remove("history").apply();
        }
        return j;
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

    /** Pauza dintre detectarea trezirii si redare, ca amplificatorul sa apuce sa porneasca. */
    static long delayMs(Context c) {
        // 5 s: pe Teyes CC3 2K, cu 2,5 s se pierdeau primele ~2 s (amplificatorul pornea tarziu).
        return sp(c).getLong("delay", 5000);
    }

    static void setDelayMs(Context c, long ms) {
        sp(c).edit().putLong("delay", Math.max(0, Math.min(15_000, ms))).apply();
    }

    /** Pauza adaugata cand unitatea chiar a hibernat (amplificatorul porneste mai greu). */
    static long sleepExtraMs(Context c) {
        return sp(c).getLong("sleep_extra", 2000);
    }

    static void setSleepExtraMs(Context c, long ms) {
        sp(c).edit().putLong("sleep_extra", Math.max(0, Math.min(15_000, ms))).apply();
    }

    /** Inregistrarea calatoriilor (GPS + date masina) la status.faicu.ro/calatorii. */
    static boolean tripsEnabled(Context c) {
        return sp(c).getBoolean("trips", true);
    }

    static void setTripsEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("trips", on).apply();
    }

    /** Pana cand (ora reala, ms) ruleaza sonda CAN; 0 = oprita. */
    static long canProbeUntil(Context c) {
        return sp(c).getLong("can_probe_until", 0);
    }

    static void setCanProbeUntil(Context c, long t) {
        sp(c).edit().putLong("can_probe_until", t).apply();
    }

    /** Volumul sunetului de bun venit, in procente din maxim; 0 = lasam volumul sistemului. */
    static int volumePercent(Context c) {
        return sp(c).getInt("volume", 0);
    }

    static void setVolumePercent(Context c, int pct) {
        sp(c).edit().putInt("volume", Math.max(0, Math.min(100, pct))).apply();
    }

    /** Momentul (elapsedRealtime) in care utilizatorul a deschis aplicatia. */
    static void markUiStart(Context c) {
        sp(c).edit().putLong("ui_start", android.os.SystemClock.elapsedRealtime()).commit();
    }

    /** Serviciul a fost pornit chiar acum de deschiderea aplicatiei, nu de o trezire. */
    static boolean startedFromUi(Context c) {
        long t = sp(c).getLong("ui_start", -1);
        long now = android.os.SystemClock.elapsedRealtime();
        return t >= 0 && t <= now && now - t < 5_000;
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
        return journal(c).getString("history", "");
    }

    static void addHistory(Context c, String line) {
        prepend(c, "history", now() + "  " + line, HISTORY_MAX);
    }

    static void clearHistory(Context c) {
        journal(c).edit().remove("history").apply();
    }

    /**
     * Jurnal de diagnostic: pornirile serviciului, pauzele dintre tick-uri, ecranul,
     * redarea. Arata unde s-a oprit lantul cand trezirea nu e detectata.
     */
    static void log(Context c, String line) {
        android.util.Log.i("VWWelcome", line);
        prepend(c, "log", now() + "  " + line, LOG_MAX);
        if (Uploader.configured() && uploadEnabled(c)) {
            enqueue(c, System.currentTimeMillis(), line);
            Uploader.kick(c);
        }
    }

    static String logText(Context c) {
        return journal(c).getString("log", "");
    }

    static void clearLog(Context c) {
        journal(c).edit().remove("log").apply();
    }

    private static synchronized void prepend(Context c, String key, String line, int max) {
        SharedPreferences j = journal(c);
        String[] lines = (line + "\n" + j.getString(key, "")).split("\n");
        String text = String.join("\n", Arrays.copyOf(lines, Math.min(lines.length, max)));
        j.edit().putString(key, text.trim()).apply();
    }

    /** Scrie pe disc acum ce era programat cu apply(): procesul e pe cale sa moara. */
    static void flushNow(Context c) {
        journal(c).edit().commit();
    }

    static boolean uploadEnabled(Context c) {
        return sp(c).getBoolean("upload", true);
    }

    static void setUploadEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("upload", on).apply();
    }

    /** Rezultatul ultimei trimiteri la server, cu ora, pentru ecranul aplicatiei. */
    static String uploadStatus(Context c) {
        return journal(c).getString("upload_status", "");
    }

    static void setUploadStatus(Context c, String status) {
        journal(c).edit().putString("upload_status", now() + " " + status).apply();
    }

    private static JSONArray outbox(Context c) {
        try {
            return new JSONArray(journal(c).getString("outbox", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static synchronized void enqueue(Context c, long t, String line) {
        JSONArray box = outbox(c);
        try {
            box.put(new JSONObject().put("t", t).put("text", line));
        } catch (JSONException e) {
            return;
        }
        while (box.length() > OUTBOX_MAX) box.remove(0);
        journal(c).edit().putString("outbox", box.toString()).apply();
    }

    /** Linii doar pentru server (ex. diagnostic), fara jurnalul local; o singura scriere. */
    static void remoteOnly(Context c, java.util.List<String> lines) {
        remoteOnly(c, lines, null);
    }

    /** Ca mai sus, cu ora fiecarei linii (null = acum, crescator ca sa ramana unice). */
    static synchronized void remoteOnly(Context c, java.util.List<String> lines,
            java.util.List<Long> times) {
        JSONArray box = outbox(c);
        long t = System.currentTimeMillis();
        try {
            for (int i = 0; i < lines.size(); i++) {
                long at = times != null ? times.get(i) : t++;
                box.put(new JSONObject().put("t", at).put("text", lines.get(i)));
            }
        } catch (JSONException e) {
            return;
        }
        while (box.length() > OUTBOX_MAX) box.remove(0);
        journal(c).edit().putString("outbox", box.toString()).apply();
        Uploader.kick(c);
    }

    static synchronized int outboxSize(Context c) {
        return outbox(c).length();
    }

    /** Primele max linii din coada, fara sa le scoatem (le scoate dropOutbox dupa succes). */
    static synchronized JSONArray peekOutbox(Context c, int max) {
        JSONArray box = outbox(c);
        JSONArray out = new JSONArray();
        for (int i = 0; i < box.length() && i < max; i++) out.put(box.opt(i));
        return out;
    }

    /** Scoate primele n linii; cele adaugate intre timp raman, fiind la coada. */
    static synchronized void dropOutbox(Context c, int n) {
        JSONArray box = outbox(c);
        JSONArray rest = new JSONArray();
        for (int i = n; i < box.length(); i++) rest.put(box.opt(i));
        journal(c).edit().putString("outbox", rest.toString()).apply();
    }

    /** Antetul jurnalului copiat: versiuni si permisiuni, ca sa nu le mai cerem separat. */
    static String deviceInfo(Context c) {
        String version;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            version = pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            version = "necunoscuta";
        }
        PowerManager pm = c.getSystemService(PowerManager.class);
        boolean notif = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(
                "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        return "VW Welcome " + version + " [" + c.getPackageName() + "]\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Build: " + Build.DISPLAY + "\n"
                + "Optimizare baterie: " + (pm.isIgnoringBatteryOptimizations(c.getPackageName())
                        ? "dezactivata" : "ACTIVA") + ", notificari: " + (notif ? "da" : "NU") + "\n"
                + "Prag " + thresholdSec(c) + " s, pauza " + delayMs(c) + " ms (+"
                + sleepExtraMs(c) + " ms dupa hibernare), volum "
                + volumePercent(c) + "%, sunete " + sounds(c).length
                + (randomOrder(c) ? " (aleatoriu)" : " (la rand)");
    }

    private static String now() {
        return new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(new Date());
    }
}
