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

    private static String install(Context c) {
        JSONObject apk = VwStatus.newerApk(c);
        if (apk == null) return "nicio versiune noua";
        Prefs.log(c, "Actualizare: descarc " + apk.optString("versionName"));
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(VwStatus.BASE + "/api/car/apk/download").openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
            if (conn.getResponseCode() != 200) return "descarcare esuata, HTTP " + conn.getResponseCode();
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
                try (InputStream in = conn.getInputStream();
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
        } finally {
            if (conn != null) conn.disconnect();
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
