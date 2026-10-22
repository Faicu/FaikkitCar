package ro.faicu.faikkitcar;

import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Performanta navigatiei, trimisa cu starea de acum (LiveState, cheia „u”): procesorul (din
 * /proc/stat, intre doua trimiteri), memoria si swap-ul (/proc/meminfo), temperatura cea mai
 * mare a procesorului (zonele termice), de cand ruleaza, memoria si adj-ul nostru, spatiul
 * liber, iar la 30 s procesele care consuma cel mai mult. Toate se pot citi fara root pe Teyes
 * (verificat cu comenzile la distanta, 20–21.10). Apelat doar de pe firul LiveState.
 */
final class UnitStats {
    private static final long TOP_EVERY_MS = 30_000;
    private static final int TOP_N = 6;

    private static long prevTotal = -1;
    private static long prevIdle;
    private static long topAt;
    private static long topTotal = -1;
    private static Map<Integer, Long> topTicks = new HashMap<>();

    private UnitStats() {}

    static JSONObject sample() {
        JSONObject u = new JSONObject();
        try {
            long[] cpu = cpuTicks();
            if (cpu != null) {
                if (prevTotal >= 0 && cpu[0] > prevTotal) {
                    double busy = 1 - (double) (cpu[1] - prevIdle) / (cpu[0] - prevTotal);
                    u.put("cpu", Math.round(Math.max(0, Math.min(1, busy)) * 1000) / 10.0);
                }
                prevTotal = cpu[0];
                prevIdle = cpu[1];
            }
            String load = read("/proc/loadavg", 128);
            if (load != null) {
                String[] f = load.trim().split("\\s+");
                if (f.length > 0) u.put("load", Double.parseDouble(f[0]));
            }
            u.put("ncpu", Runtime.getRuntime().availableProcessors());
            Map<String, Long> mem = meminfo();
            putMb(u, "mt", mem.get("MemTotal"));
            putMb(u, "ma", mem.get("MemAvailable"));
            putMb(u, "st", mem.get("SwapTotal"));
            putMb(u, "sf", mem.get("SwapFree"));
            double temp = cpuTemp();
            if (!Double.isNaN(temp)) u.put("temp", Math.round(temp * 10) / 10.0);
            String up = read("/proc/uptime", 128);
            if (up != null) u.put("up", Math.round(Double.parseDouble(up.trim().split("\\s+")[0])));
            u.put("awake", SystemClock.uptimeMillis() / 1000); // fara somn
            long[] own = statm(android.os.Process.myPid());
            if (own != null) u.put("am", own[1]);
            String adj = read("/proc/self/oom_score_adj", 32);
            if (adj != null) u.put("adj", Integer.parseInt(adj.trim()));
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            u.put("disk", Math.round(fs.getAvailableBytes() / 1e8) / 10.0); // GB
            long now = SystemClock.elapsedRealtime();
            if (cpu != null && (topAt == 0 || now - topAt >= TOP_EVERY_MS)) {
                JSONArray top = top(cpu[0]);
                topAt = now;
                if (top != null) u.put("top", top);
            }
        } catch (Exception ignored) {
            // Ce s-a putut citi pleaca oricum.
        }
        return u;
    }

    /** {total, idle+iowait} din prima linie a /proc/stat. */
    private static long[] cpuTicks() {
        String s = read("/proc/stat", 512);
        if (s == null || !s.startsWith("cpu ")) return null;
        String[] f = s.substring(0, s.indexOf('\n') > 0 ? s.indexOf('\n') : s.length()).trim().split("\\s+");
        long total = 0;
        for (int i = 1; i < f.length && i <= 8; i++) total += Long.parseLong(f[i]); // fara guest
        long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
        return new long[] {total, idle};
    }

    private static Map<String, Long> meminfo() {
        Map<String, Long> m = new HashMap<>();
        String s = read("/proc/meminfo", 4096);
        if (s == null) return m;
        for (String line : s.split("\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String[] v = line.substring(c + 1).trim().split("\\s+");
            try {
                m.put(line.substring(0, c), Long.parseLong(v[0]));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    private static void putMb(JSONObject u, String key, Long kb) throws JSONException {
        if (kb != null) u.put(key, Math.round(kb / 1024.0));
    }

    /** Cea mai mare zona termica, fara indicatorul bateriei (sc27xx-fgu); in °C. */
    private static double cpuTemp() {
        File[] zones = new File("/sys/class/thermal").listFiles((d, n) -> n.startsWith("thermal_zone"));
        if (zones == null) return Double.NaN;
        double max = Double.NaN;
        for (File z : zones) {
            String type = read(new File(z, "type").getPath(), 64);
            String t = read(new File(z, "temp").getPath(), 32);
            if (type == null || t == null || type.contains("fgu") || type.contains("bat")) continue;
            try {
                double v = Double.parseDouble(t.trim());
                if (Math.abs(v) > 1000) v /= 1000; // miligrade
                if (v > 0 && v < 150 && (Double.isNaN(max) || v > max)) max = v;
            } catch (NumberFormatException ignored) {
            }
        }
        return max;
    }

    /** {rss, propriu (rss − partajat)} in MB, din /proc/<pid>/statm (pagini de 4 KB). */
    private static long[] statm(int pid) {
        String s = read("/proc/" + pid + "/statm", 128);
        if (s == null) return null;
        String[] f = s.trim().split("\\s+");
        if (f.length < 3) return null;
        long rss = Long.parseLong(f[1]);
        long shared = Long.parseLong(f[2]);
        return new long[] {rss * 4 / 1024, Math.max(0, rss - shared) * 4 / 1024};
    }

    /**
     * Procesele cu cel mai mult procesor (din ultimele ~30 s, in % din tot procesorul, ca
     * totalul) si cu cea mai multa memorie proprie: {n, c, m}. Prima trecere da doar memoria.
     */
    private static JSONArray top(long total) throws JSONException {
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return null;
        Map<Integer, Long> ticks = new HashMap<>();
        List<long[]> rows = new ArrayList<>(); // {pid, cpu‰, mb}
        long dTotal = topTotal >= 0 ? total - topTotal : 0;
        for (File d : dirs) {
            String name = d.getName();
            if (name.isEmpty() || name.charAt(0) < '0' || name.charAt(0) > '9') continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            String st = read("/proc/" + pid + "/stat", 1024);
            if (st == null) continue;
            int close = st.lastIndexOf(')');
            if (close < 0) continue;
            String[] f = st.substring(close + 2).split(" ");
            if (f.length < 13) continue;
            long t;
            try {
                t = Long.parseLong(f[11]) + Long.parseLong(f[12]); // utime + stime
            } catch (NumberFormatException e) {
                continue;
            }
            ticks.put(pid, t);
            Long before = topTicks.get(pid);
            long permille = dTotal > 0 && before != null ? Math.round((t - before) * 1000.0 / dTotal) : 0;
            long[] m = statm(pid);
            rows.add(new long[] {pid, permille, m == null ? 0 : m[1]});
        }
        topTicks = ticks;
        topTotal = total;
        List<long[]> picked = new ArrayList<>();
        if (dTotal > 0) {
            rows.sort((a, b) -> Long.compare(b[1], a[1]));
            for (int i = 0; i < rows.size() && picked.size() < TOP_N; i++) if (rows.get(i)[1] > 0) picked.add(rows.get(i));
        }
        rows.sort((a, b) -> Long.compare(b[2], a[2]));
        for (int i = 0, n = 0; i < rows.size() && n < TOP_N; i++, n++) if (!picked.contains(rows.get(i))) picked.add(rows.get(i));
        JSONArray out = new JSONArray();
        for (long[] r : picked) {
            JSONObject o = new JSONObject().put("n", procName((int) r[0])).put("m", r[2]);
            if (dTotal > 0) o.put("c", r[1] / 10.0); // la prima trecere nu se stie
            out.put(o);
        }
        return out;
    }

    private static String procName(int pid) {
        String cmd = read("/proc/" + pid + "/cmdline", 256);
        if (cmd != null) {
            int z = cmd.indexOf('\0');
            String first = (z >= 0 ? cmd.substring(0, z) : cmd).trim();
            if (!first.isEmpty()) return first.substring(first.lastIndexOf('/') + 1);
        }
        String st = read("/proc/" + pid + "/stat", 256);
        if (st != null && st.indexOf('(') >= 0 && st.lastIndexOf(')') > st.indexOf('(')) {
            return "[" + st.substring(st.indexOf('(') + 1, st.lastIndexOf(')')) + "]";
        }
        return String.valueOf(pid);
    }

    /** Fisierele din /proc au marimea 0, deci citim pana la capat (cel mult `max` octeti). */
    private static String read(String path, int max) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[max];
            int len = 0;
            int n;
            while (len < max && (n = in.read(buf, len, max - len)) > 0) len += n;
            return new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
