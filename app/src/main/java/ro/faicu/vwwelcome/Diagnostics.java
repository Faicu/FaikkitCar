package ro.faicu.vwwelcome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cauta pe navigatie setari Teyes legate de somn / aplicatii inchise / autostart:
 * aplicatiile de sistem, componentele lor, tabelele Settings si textele din APK-urile
 * Teyes. Rezultatul merge doar la server (pagina /vw), nu in jurnalul local de 100 linii.
 */
final class Diagnostics {
    private static final Pattern KEY = Pattern.compile(
            "(?i)white.?list|un.?kill|no.?kill|kill|keep.?alive|auto.?run|auto.?start|autostart"
                    + "|sleep|standby|acc.?(on|off|state)|protect|clean|background|boot");
    private static final String[] CHINESE = {"白名单", "保活", "休眠", "自启", "清理", "后台", "杀"};
    private static final int MAX_LINES = 450;
    private static final int MAX_STRINGS_PER_APK = 60;
    private static final long MAX_ENTRY_BYTES = 24L << 20;

    private Diagnostics() {}

    /** Ruleaza in fundal; intoarce numarul de linii trimise in coada. */
    static int run(Context c) {
        List<String> out = new ArrayList<>();
        out.add("DIAG start");
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        List<ApplicationInfo> vendor = new ArrayList<>();
        for (ApplicationInfo a : apps) {
            String p = a.packageName;
            if (p.equals(c.getPackageName())) continue;
            boolean stock = p.startsWith("com.google.") || p.startsWith("com.android.")
                    || p.equals("android");
            if (stock && !p.equals("com.android.settings")) continue;
            vendor.add(a);
        }
        out.add("DIAG pachete: " + apps.size() + " total, " + vendor.size() + " non-standard");
        for (ApplicationInfo a : vendor) {
            boolean sys = (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String version = "";
            List<String> comps = new ArrayList<>();
            try {
                PackageInfo pi = pm.getPackageInfo(a.packageName, PackageManager.GET_ACTIVITIES
                        | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                        | PackageManager.GET_PROVIDERS | PackageManager.GET_DISABLED_COMPONENTS);
                version = pi.versionName;
                addComps(comps, "A", pi.activities);
                addComps(comps, "S", pi.services);
                addComps(comps, "R", pi.receivers);
                addComps(comps, "P", pi.providers);
            } catch (Exception e) {
                comps.add("eroare " + e.getClass().getSimpleName());
            }
            out.add("DIAG PKG " + a.packageName + " \"" + pm.getApplicationLabel(a) + "\" v" + version
                    + (sys ? " sys" : " user") + (comps.isEmpty() ? "" : " | " + String.join(", ", comps)));
        }

        for (String table : new String[] {"system", "global", "secure"}) {
            try (Cursor cur = c.getContentResolver().query(
                    Uri.parse("content://settings/" + table), new String[] {"name", "value"},
                    null, null, null)) {
                int n = 0;
                while (cur != null && cur.moveToNext()) {
                    n++;
                    String name = cur.getString(0);
                    if (name != null && KEY.matcher(name).find()) {
                        out.add("DIAG SET " + table + " " + name + "=" + cur.getString(1));
                    }
                }
                out.add("DIAG SET " + table + ": " + n + " valori citite");
            } catch (Exception e) {
                out.add("DIAG SET " + table + ": eroare " + e);
            }
        }

        for (ApplicationInfo a : vendor) {
            String id = (a.packageName + " " + pm.getApplicationLabel(a)).toLowerCase(Locale.ROOT);
            if (!id.matches(".*(teyes|syu|fyt|sprd|unisoc|settings|setting|launcher|car|mcu|power).*")) {
                continue;
            }
            scanApk(a, out);
            if (out.size() > MAX_LINES) break;
        }
        out.add("DIAG gata");
        List<String> lines = out.size() > MAX_LINES ? out.subList(0, MAX_LINES) : out;
        Prefs.remoteOnly(c, lines);
        return lines.size();
    }

    private static void addComps(List<String> out, String kind, ComponentInfo[] comps) {
        if (comps == null) return;
        for (ComponentInfo ci : comps) {
            if (KEY.matcher(ci.name).find()) out.add(kind + ":" + ci.name);
        }
    }

    /** Textele din resources.arsc si classes*.dex care contin cuvintele cautate. */
    private static void scanApk(ApplicationInfo a, List<String> out) {
        Set<String> found = new LinkedHashSet<>();
        int[] chinese = new int[CHINESE.length];
        try (ZipFile zip = new ZipFile(a.sourceDir)) {
            for (java.util.Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!(n.equals("resources.arsc") || (n.startsWith("classes") && n.endsWith(".dex")))) continue;
                if (e.getSize() > MAX_ENTRY_BYTES) continue;
                byte[] data = readAll(zip.getInputStream(e), (int) e.getSize());
                extract(data, found);
                for (int i = 0; i < CHINESE.length; i++) {
                    chinese[i] += count(data, CHINESE[i].getBytes(StandardCharsets.UTF_8))
                            + count(data, CHINESE[i].getBytes(StandardCharsets.UTF_16LE));
                }
            }
        } catch (Throwable e) { // si OutOfMemoryError: un APK prea mare nu opreste restul
            out.add("DIAG APK " + a.packageName + ": eroare " + e.getClass().getSimpleName());
            return;
        }
        StringBuilder zh = new StringBuilder();
        for (int i = 0; i < CHINESE.length; i++) {
            if (chinese[i] > 0) zh.append(' ').append(CHINESE[i]).append('=').append(chinese[i]);
        }
        out.add("DIAG APK " + a.packageName + ": " + found.size() + " texte" + zh);
        int k = 0;
        for (String s : found) {
            if (k++ >= MAX_STRINGS_PER_APK) break;
            out.add("DIAG TXT " + a.packageName + ": " + s);
        }
    }

    private static int count(byte[] data, byte[] pat) {
        int n = 0;
        outer:
        for (int i = 0; i + pat.length <= data.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) continue outer;
            }
            n++;
        }
        return n;
    }

    private static byte[] readAll(InputStream in, int size) throws java.io.IOException {
        try (InputStream is = in) {
            byte[] buf = new byte[Math.max(size, 0)];
            int off = 0, n;
            while (off < buf.length && (n = is.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        }
    }

    /** Siruri ASCII si UTF-16LE (min. 5 caractere) care contin un cuvant cheie. */
    private static void extract(byte[] b, Set<String> found) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            if (x >= 0x20 && x < 0x7f) {
                sb.append((char) x);
            } else {
                keep(sb, found);
            }
        }
        keep(sb, found);
        for (int parity = 0; parity < 2; parity++) {
            for (int i = parity; i + 1 < b.length; i += 2) {
                if (b[i + 1] == 0 && b[i] >= 0x20 && b[i] < 0x7f) {
                    sb.append((char) b[i]);
                } else {
                    keep(sb, found);
                }
            }
            keep(sb, found);
        }
    }

    private static void keep(StringBuilder sb, Set<String> found) {
        if (sb.length() >= 5 && sb.length() <= 160 && KEY.matcher(sb).find()) found.add(sb.toString());
        sb.setLength(0);
    }
}
