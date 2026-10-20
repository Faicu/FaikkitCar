package ro.faicu.faikkitcar;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * La pornirea serviciului: suntem inca pe listele Teyes (cele care ne feresc de oprirea
 * fortata la somn si ne dau voie la autostart)? S-a actualizat MainServer-ul? Cata memorie e
 * libera? 20.10: procesul a murit de doua ori exact la intrarea in somn, desi in zilele de
 * dinainte trecea prin somn fara probleme.
 */
final class TeyesCheck {
    private static final String[] FILES = {"/oem/app/pwctl_config.xml",
            "/oem/app/skipkillapp.prop", "/oem/app/protected_app.txt"};

    private TeyesCheck() {}

    static String describe(Context c) {
        String me = c.getPackageName();
        StringBuilder sb = new StringBuilder("Teyes: ");
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo("com.syu.ms", 0);
            sb.append("com.syu.ms ").append(pi.versionName).append(" (actualizat ")
                    .append(new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
                            .format(new Date(pi.lastUpdateTime)))
                    .append(")");
            try (ZipFile zip = new ZipFile(pi.applicationInfo.sourceDir)) {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.toLowerCase(Locale.US).contains("unkill")) continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        sb.append(" | ").append(n.substring(n.lastIndexOf('/') + 1))
                                .append(contains(in, me) ? ": suntem" : ": NU suntem");
                    }
                }
            }
        } catch (Exception e) {
            sb.append("com.syu.ms: ").append(e.getClass().getSimpleName());
        }
        for (String path : FILES) {
            File f = new File(path);
            sb.append(" | ").append(f.getName());
            if (!f.exists()) {
                sb.append(": lipseste");
                continue;
            }
            try (InputStream in = new FileInputStream(f)) {
                sb.append(contains(in, me) ? ": suntem" : ": NU suntem").append(" (")
                        .append(new SimpleDateFormat("dd.MM.yyyy", Locale.US)
                                .format(new Date(f.lastModified())))
                        .append(")");
            } catch (Exception e) {
                sb.append(": ").append(e.getClass().getSimpleName());
            }
        }
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        sb.append(" | memorie libera ").append(mi.availMem >> 20).append(" / ")
                .append(mi.totalMem >> 20).append(" MB").append(mi.lowMemory ? " (PUTINA)" : "");
        return sb.toString();
    }

    private static boolean contains(InputStream in, String s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < 4 << 20) out.write(buf, 0, n);
        return out.toString("UTF-8").contains(s);
    }
}
