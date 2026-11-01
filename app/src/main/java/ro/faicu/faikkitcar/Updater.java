package ro.faicu.faikkitcar;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Actualizarea din aplicatie: descarca ultimul APK de pe car.faicu.ro (incarcat de CI) si
 * il instaleaza prin PackageInstaller; Android cere o singura confirmare pe ecran. Prima data
 * trebuie permisa instalarea de aplicatii din FaikkitCar (setarea "surse necunoscute").
 */
final class Updater {
    // 21.10: dupa confirmare, Android (verificarea Play Protect) a mai stat ~25 s fara nimic pe
    // ecran; a doua apasare a facut o sesiune noua, iar aplicatia s-a instalat de doua ori. Cat
    // sesiunea trimisa e inca deschisa, o noua apasare nu mai porneste alta.
    private static final long BUSY_MS = 90_000;
    private static volatile int sessionId = -1;
    private static volatile long sessionAt;
    private static volatile boolean downloading;

    private Updater() {}

    /** O instalare trimisa in ultimele 90 s inca nu s-a terminat. */
    static boolean busy(Context c) {
        if (downloading) return true;
        int id = sessionId;
        if (id < 0 || android.os.SystemClock.elapsedRealtime() - sessionAt > BUSY_MS) return false;
        return c.getPackageManager().getPackageInstaller().getSessionInfo(id) != null;
    }

    /** Porneste actualizarea in fundal; onDone primeste pe firul UI eroarea (null = trimisa la instalare). */
    static void start(Activity a, java.util.function.Consumer<String> onDone) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return;
        }
        Context c = a.getApplicationContext();
        if (busy(c)) {
            Prefs.log(c, "Actualizare: apasare ignorata, sesiunea " + sessionId + " e in curs");
            onDone.accept(null);
            return;
        }
        downloading = true;
        new Thread(() -> {
            String error;
            try {
                error = install(c);
            } finally {
                downloading = false;
            }
            if (error != null) Prefs.log(c, "Actualizare: " + error);
            a.runOnUiThread(() -> onDone.accept(error));
        }).start();
    }

    private static HttpURLConnection openDownload() throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(VwStatus.BASE + "/api/car/apk/download").openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
        return conn;
    }

    // ---------------------------------------------------------------- la pornirea masinii
    // Versiunea noua se descarca in fundal (prefetch, cand vine starea de pe server) si se
    // propune la ACC ON (StartActivity): masina sta pe loc, iar dupa somn internetul abia se
    // reconecteaza, deci instalam din fisier. Android 10 cere o atingere; la „Anulare” nu mai
    // intrebam pentru aceeasi versiune atatea ore (cardul de pe Acasa ramane).
    private static final long ASK_AGAIN_MS = 6 * 3_600_000L;
    private static volatile boolean prefetching;

    private static File prefetched(Context c) {
        return new File(c.getFilesDir(), "update.apk");
    }

    /** Descarca in avans versiunea noua (din firul trimiterii). Blocant. */
    static void prefetch(Context c) {
        JSONObject apk = VwStatus.newerApk(c);
        File f = prefetched(c);
        if (apk == null) {
            if (f.exists() && Prefs.prefetchedCode(c) <= BuildConfig.VERSION_CODE) f.delete();
            return;
        }
        int code = apk.optInt("versionCode");
        if (prefetching || (f.exists() && Prefs.prefetchedCode(c) == code)) return;
        prefetching = true;
        HttpURLConnection conn = null;
        File tmp = new File(c.getFilesDir(), "update.apk.part");
        try {
            conn = openDownload();
            if (conn.getResponseCode() != 200) return;
            try (InputStream in = conn.getInputStream(); OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (tmp.renameTo(f)) {
                Prefs.setPrefetchedCode(c, code);
                // Cu contactul pus (si in mers, cererea utilizatorului, 01.11) o propunem acum;
                // altfel la ~1 min dupa urmatoarea pornire.
                boolean now = CanLink.get(c).contact() && canShowLater(c);
                Prefs.log(c, "Actualizare: " + apk.optString("versionName") + " descarcata, o propun "
                        + (now ? "acum" : "la pornirea masinii"));
                if (now) main.post(() -> offerAtStart(c));
            }
        } catch (Exception e) {
            tmp.delete();
        } finally {
            prefetching = false;
            if (conn != null) conn.disconnect();
        }
    }

    // Fereastra apare la atat dupa pornire: motorul se incalzeste, aplicatiile au pornit deja
    // (cererea utilizatorului, 01.11). Fara „Afisare peste alte aplicatii”, Android 10 o lasa
    // doar imediat dupa autostart, deci atunci o deschidem pe loc.
    private static final long OFFER_DELAY_MS = 60_000;
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable pendingOffer;

    /** „Afisare peste alte aplicatii” data: putem deschide fereastra oricand, nu doar la autostart. */
    static boolean canShowLater(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** La ACC ON (autostart): propunerea, la ~1 min sau imediat daca nu avem voie mai tarziu. */
    static void scheduleOffer(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!canShowLater(c)) {
            offerAtStart(c);
            return;
        }
        if (pendingOffer != null) main.removeCallbacks(pendingOffer);
        pendingOffer = () -> {
            pendingOffer = null;
            offerAtStart(c);
        };
        main.postDelayed(pendingOffer, OFFER_DELAY_MS);
    }

    /**
     * Daca e o versiune descarcata si n-am intrebat de curand, cere confirmarea. La ~1 min dupa
     * pornire (scheduleOffer) sau imediat ce s-a descarcat cu contactul pus (prefetch).
     */
    static void offerAtStart(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!Prefs.autoUpdateEnabled(c) || !c.getPackageManager().canRequestPackageInstalls()) return;
        File f = prefetched(c);
        int code = Prefs.prefetchedCode(c);
        if (!f.exists() || code <= BuildConfig.VERSION_CODE || busy(c)) return;
        if (Prefs.offeredCode(c) == code && System.currentTimeMillis() - Prefs.offeredAt(c) < ASK_AGAIN_MS) return;
        Prefs.setOffered(c, code, System.currentTimeMillis());
        downloading = true;
        new Thread(() -> {
            String error;
            try {
                Prefs.log(c, "Actualizare: propun versiunea " + code + " la pornire");
                error = commit(c, () -> new java.io.FileInputStream(f));
            } finally {
                downloading = false;
            }
            if (error != null) Prefs.log(c, "Actualizare: " + error);
        }).start();
    }

    interface Source {
        InputStream open() throws Exception;
    }

    private static String install(Context c) {
        JSONObject apk = VwStatus.newerApk(c);
        if (apk == null) return "nicio versiune noua";
        File f = prefetched(c);
        if (f.exists() && Prefs.prefetchedCode(c) == apk.optInt("versionCode")) {
            Prefs.log(c, "Actualizare: instalez " + apk.optString("versionName") + " (descarcata deja)");
            return commit(c, () -> new java.io.FileInputStream(f));
        }
        Prefs.log(c, "Actualizare: descarc " + apk.optString("versionName"));
        HttpURLConnection[] conn = new HttpURLConnection[1];
        try {
            return commit(c, () -> {
                conn[0] = openDownload();
                if (conn[0].getResponseCode() != 200) {
                    throw new java.io.IOException("descarcare esuata, HTTP " + conn[0].getResponseCode());
                }
                return conn[0].getInputStream();
            });
        } finally {
            if (conn[0] != null) conn[0].disconnect();
        }
    }

    /** Scrie APK-ul intr-o sesiune PackageInstaller si o trimite; null = trimisa. */
    private static String commit(Context c, Source source) {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            // O sesiune veche, ramasa neconfirmata (fereastra inchisa), nu mai e nevoie de ea.
            int old = sessionId;
            if (old >= 0 && pi.getSessionInfo(old) != null) {
                try {
                    pi.abandonSession(old);
                } catch (Exception ignored) {
                }
            }
            int id = pi.createSession(params);
            try (PackageInstaller.Session session = pi.openSession(id)) {
                try (InputStream in = source.open();
                     OutputStream out = session.openWrite("FaikkitCar.apk", 0, -1)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                } catch (Exception e) {
                    // Altfel APK-ul descarcat pe jumatate ramane intr-o sesiune deschisa.
                    session.abandon();
                    throw e;
                }
                Intent done = new Intent(c, Result.class);
                PendingIntent pending = PendingIntent.getBroadcast(c, id, done,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                session.commit(pending.getIntentSender());
                sessionId = id;
                sessionAt = android.os.SystemClock.elapsedRealtime();
            }
            Prefs.log(c, "Actualizare: sesiunea " + id + " trimisa la instalare");
            return null;
        } catch (Exception e) {
            return "eroare " + e;
        }
    }

    /** Raspunsul PackageInstaller-ului: cere confirmarea utilizatorului sau noteaza rezultatul. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                // 20.10: dupa confirmare si scanarea Play Protect, prima sesiune ramanea uneori
                // fara rezultat si se termina abia la a doua apasare; pasii, ca sa vedem unde.
                Prefs.log(c, "Actualizare: sesiunea " + id + " cere confirmarea"
                        + (confirm == null ? " (fara fereastra!)" : ""));
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(confirm);
                }
                sessionAt = android.os.SystemClock.elapsedRealtime();
                return;
            }
            if (id == sessionId) sessionId = -1;
            Prefs.log(c, "Actualizare: sesiunea " + id + " " + (status == PackageInstaller.STATUS_SUCCESS ? "instalata"
                    : "esuata (" + status + ") " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
        }
    }
}
