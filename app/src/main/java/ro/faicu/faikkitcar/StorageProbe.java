package ro.faicu.faikkitcar;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.storage.StorageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Test (22.11): spatiul de stocare al navigatiei prin StorageStatsManager (cu „Acces la datele de
 * utilizare” dat): total / liber, apoi aplicatiile dupa marime (aplicatie + date + cache).
 * Doar comanda interna `@storage` prin car-cmd.ts. Doar citire.
 */
final class StorageProbe {
    private StorageProbe() {}

    private static String mb(long b) {
        return b / (1024 * 1024) + " MB";
    }

    static String run(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            StorageStatsManager sm = (StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
            UUID uuid = StorageManager.UUID_DEFAULT;
            sb.append("total ").append(mb(sm.getTotalBytes(uuid))).append(", liber ")
                    .append(mb(sm.getFreeBytes(uuid))).append('\n');
            try {
                android.app.usage.ExternalStorageStats e = sm.queryExternalStatsForUser(uuid, Process.myUserHandle());
                sb.append("partajat (/sdcard): total ").append(mb(e.getTotalBytes())).append(", imagini ")
                        .append(mb(e.getImageBytes())).append(", video ").append(mb(e.getVideoBytes()))
                        .append(", audio ").append(mb(e.getAudioBytes())).append(", alte aplicatii ")
                        .append(mb(e.getAppBytes())).append('\n');
            } catch (Exception e) {
                sb.append("partajat: ").append(e).append('\n');
            }
            PackageManager pm = c.getPackageManager();
            List<String[]> rows = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            long sum = 0;
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                try {
                    StorageStats s = sm.queryStatsForPackage(uuid, ai.packageName, Process.myUserHandle());
                    long tot = s.getAppBytes() + s.getDataBytes();
                    sum += tot;
                    rows.add(new String[]{ai.packageName, mb(s.getAppBytes()), mb(s.getDataBytes()), mb(s.getCacheBytes())});
                    sizes.add(tot);
                } catch (Exception ignored) {
                    // aplicatie fara statistici
                }
            }
            Integer[] idx = new Integer[rows.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Long.compare(sizes.get(b), sizes.get(a)));
            sb.append("aplicatii (").append(rows.size()).append("), total ").append(mb(sum))
                    .append(" (aplicatie / date / din care cache):\n");
            for (int i = 0; i < Math.min(30, idx.length); i++) {
                String[] r = rows.get(idx[i]);
                sb.append(r[0]).append("  ").append(r[1]).append(" / ").append(r[2]).append(" / ").append(r[3]).append('\n');
            }
        } catch (Throwable t) {
            sb.append("eroare: ").append(t);
        }
        return sb.toString();
    }
}
