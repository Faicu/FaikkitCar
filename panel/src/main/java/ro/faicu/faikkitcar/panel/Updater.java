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
 * car.faicu.ro (incarcat de CI), instalat prin PackageInstaller. Prima data trebuie permisa
 * instalarea din FaikkitCar Panel ("surse necunoscute"). De la Android 12, daca versiunea de
 * acum a fost instalata tot de Panel, instalarea merge fara confirmare: atunci se face singura
 * (auto), la deschiderea aplicatiei si zilnic in fundal (AutoUpdateJob).
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

    // O incercare automata pe versiune la cel mult atatea ore (daca Android tot cere confirmare).
    private static final long AUTO_RETRY_MS = 6 * 3_600_000L;

    /** Se poate instala fara confirmare: Android 12+ si permisiunea de instalare data. */
    static boolean silentPossible(Context c) {
        return android.os.Build.VERSION.SDK_INT >= 31 && c.getPackageManager().canRequestPackageInstalls()
                && c.getPackageName().equals(installer(c)) && !silentRefused(c);
    }

    /**
     * Cine a instalat versiunea de acum. Android da instalarea fara confirmare doar aceluia: dupa
     * o instalare din browser (1.0.32, 02.11) Panel nu mai poate, pana la o instalare facuta tot de el.
     */
    static String installer(Context c) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                return c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getInstallingPackageName();
            }
            return c.getPackageManager().getInstallerPackageName(c.getPackageName());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * O instalare fara confirmare a esuat cu acelasi instalator ca acum: nu o mai incercam (doar
     * daca s-a schimbat instalatorul, ex. dupa o instalare din buton).
     */
    static boolean silentRefused(Context c) {
        String by = c.getSharedPreferences("update", Context.MODE_PRIVATE).getString("silentRefusedBy", null);
        return by != null && by.equals(String.valueOf(installer(c)));
    }

    /**
     * Fara instalare automata: la deschiderea aplicatiei pornim direct instalarea cu confirmare
     * (ramane doar „Instalează”), o data pe versiune la cel mult 6 ore.
     */
    static boolean promptDue(Context c, int versionCode) {
        android.content.SharedPreferences sp = c.getSharedPreferences("update", Context.MODE_PRIVATE);
        if (sp.getInt("prompted", 0) == versionCode
                && System.currentTimeMillis() - sp.getLong("promptedAt", 0) < AUTO_RETRY_MS) {
            return false;
        }
        sp.edit().putInt("prompted", versionCode).putLong("promptedAt", System.currentTimeMillis()).apply();
        return true;
    }

    /**
     * Actualizarea automata: daca e o versiune noua si se poate fara confirmare, o instaleaza.
     * Blocant (din fir separat). Intoarce true daca a trimis-o la instalare.
     */
    static boolean auto(Context c) {
        if (!silentPossible(c)) return false;
        JSONObject apk = newer(c);
        if (apk == null) return false;
        android.content.SharedPreferences sp = c.getSharedPreferences("update", Context.MODE_PRIVATE);
        int code = apk.optInt("versionCode");
        if (sp.getInt("tried", 0) == code && System.currentTimeMillis() - sp.getLong("triedAt", 0) < AUTO_RETRY_MS) {
            return false;
        }
        sp.edit().putInt("tried", code).putLong("triedAt", System.currentTimeMillis()).apply();
        return install(c, true) == null;
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
            String error = install(c, false);
            a.runOnUiThread(() -> onDone.accept(error));
        }).start();
    }

    /**
     * silent = fara confirmare (doar actualizarea automata). Butonul cere mereu confirmarea:
     * pe telefonul utilizatorului (02.11, Panel 1.0.30) sistemul refuza instalarea fara confirmare
     * cu „INSTALL_FAILED_ABORTED: Permission denied”, deci si butonul cadea.
     */
    private static String install(Context c, boolean silent) {
        if (newer(c) == null) return "nicio versiune nouă";
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        HttpURLConnection conn = null;
        try {
            conn = new Api(c).openApkDownload();
            if (conn.getResponseCode() != 200) return "descărcare eșuată, HTTP " + conn.getResponseCode();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            if (silent && android.os.Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int id = pi.createSession(params);
            try (PackageInstaller.Session session = pi.openSession(id)) {
                try (InputStream in = conn.getInputStream();
                     OutputStream out = session.openWrite("FaikkitCarPanel.apk", 0, -1)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                } catch (Exception e) {
                    // Altfel APK-ul descarcat pe jumatate ramane intr-o sesiune deschisa.
                    session.abandon();
                    throw e;
                }
                Intent done = new Intent(c, Result.class).putExtra("silent", silent);
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
            if (status != PackageInstaller.STATUS_SUCCESS && intent.getBooleanExtra("silent", false)) {
                // Refuzata fara confirmare: de acum doar cu butonul (cardul „Versiune noua”).
                // (02.11: dupa scanarea Play Protect si „Instalează”, cardul ramanea pe „Se instalează automat”.)
                c.getSharedPreferences("update", Context.MODE_PRIVATE).edit()
                        .putString("silentRefusedBy", String.valueOf(installer(c))).apply();
                android.widget.Toast.makeText(c, "Actualizarea automată n-a mers ("
                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        + ", instalator " + installer(c) + "); apasă „Actualizează acum”.",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            // „Anulează” la confirmare (STATUS_FAILURE_ABORTED) nu e o eroare de aratat.
            if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                android.widget.Toast.makeText(c, "Actualizare eșuată: "
                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }
}
