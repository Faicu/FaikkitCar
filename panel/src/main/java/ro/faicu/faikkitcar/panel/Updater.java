package ro.faicu.faikkitcar.panel;

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

/**
 * Actualizarea din aplicatie, ca la FaikkitCar din masina: ultimul APK Panel de pe
 * car.faicu.ro (incarcat de CI), instalat prin PackageInstaller; Android cere o confirmare.
 * Prima data trebuie permisa instalarea din FaikkitCar Panel ("surse necunoscute").
 */
final class Updater {
    private Updater() {}

    /** Versiunea noua de pe server, sau null daca nu e (sau nu se poate afla). Blocant. */
    static JSONObject newer(Context c) {
        try {
            JSONObject apk = new Api(c).panelApk();
            return apk.optInt("versionCode") > BuildConfig.VERSION_CODE ? apk : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Porneste actualizarea in fundal; onDone primeste pe firul UI eroarea (null = trimisa la instalare). */
    static void start(Activity a, java.util.function.Consumer<String> onDone) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return;
        }
        Context c = a.getApplicationContext();
        new Thread(() -> {
            String error = install(c);
            a.runOnUiThread(() -> onDone.accept(error));
        }).start();
    }

    private static String install(Context c) {
        if (newer(c) == null) return "nicio versiune nouă";
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        HttpURLConnection conn = null;
        try {
            conn = new Api(c).openApkDownload();
            if (conn.getResponseCode() != 200) return "descărcare eșuată, HTTP " + conn.getResponseCode();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            int id = pi.createSession(params);
            try (PackageInstaller.Session session = pi.openSession(id)) {
                try (InputStream in = conn.getInputStream();
                     OutputStream out = session.openWrite("FaikkitCarPanel.apk", 0, -1)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent done = new Intent(c, Result.class);
                PendingIntent pending = PendingIntent.getBroadcast(c, id, done,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                session.commit(pending.getIntentSender());
            }
            return null;
        } catch (Exception e) {
            return "eroare " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Raspunsul PackageInstaller-ului: cere confirmarea utilizatorului sau noteaza rezultatul. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(confirm);
                }
                return;
            }
            if (status != PackageInstaller.STATUS_SUCCESS) {
                android.widget.Toast.makeText(c, "Actualizare eșuată: "
                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }
}
