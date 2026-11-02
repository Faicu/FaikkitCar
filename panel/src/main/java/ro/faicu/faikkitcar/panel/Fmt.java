package ro.faicu.faikkitcar.panel;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatarile din site, in romana: date, durate, litri, lei. */
final class Fmt {
    private Fmt() {}

    private static final Locale RO = new Locale("ro", "RO");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    static long millis(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static String pattern(String iso, String p) {
        return DateTimeFormatter.ofPattern(p, RO).withZone(ZONE).format(Instant.parse(iso));
    }

    /** „joi, 01 oct.” */
    static String day(String iso) {
        return pattern(iso, "EEE, dd MMM");
    }

    /** Ziua locala, pentru grupare: „2026-11-02”. */
    static String dayKey(String iso) {
        return pattern(iso, "yyyy-MM-dd");
    }

    /** Antetul unei zile in liste: „Azi”, „Ieri”, altfel „Sâmbătă, 31 oct.”. */
    static String dayTitle(String iso) {
        java.time.LocalDate d = Instant.parse(iso).atZone(ZONE).toLocalDate();
        java.time.LocalDate today = java.time.LocalDate.now(ZONE);
        if (d.equals(today)) return "Azi";
        if (d.equals(today.minusDays(1))) return "Ieri";
        String s = pattern(iso, d.getYear() == today.getYear() ? "EEEE, d MMM" : "EEEE, d MMM yyyy");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String hm(String iso) {
        return pattern(iso, "HH:mm");
    }

    static String dateTime(String iso) {
        return pattern(iso, "dd.MM, HH:mm:ss");
    }

    static String date(String iso) {
        return pattern(iso, "dd MMM yyyy");
    }

    /** „Acasă → Serviciu”, sau "" daca niciun capat nu e un loc salvat. */
    static String route(org.json.JSONObject t) {
        if (t.isNull("fromPlace") && t.isNull("toPlace")) return "";
        return (t.isNull("fromPlace") ? "…" : t.optString("fromPlace")) + " → "
                + (t.isNull("toPlace") ? "…" : t.optString("toPlace"));
    }

    /** „Octombrie 2026” din „2026-10”. */
    static String month(String ym) {
        String s = java.time.YearMonth.parse(ym).format(DateTimeFormatter.ofPattern("LLLL yyyy", RO));
        return s.isEmpty() ? ym : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Sub 10 minute, cu secunde („1 min 30 s”, „45 s”); altfel ca duration. */
    static String shortDuration(double min) {
        if (min >= 10) return duration(min);
        long sec = Math.round(min * 60);
        if (sec < 60) return sec + " s";
        return sec % 60 != 0 ? sec / 60 + " min " + sec % 60 + " s" : sec / 60 + " min";
    }

    /** Pozitia intr-o piesa: „3:07”, „1:02:05”. */
    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    static String duration(double min) {
        if (min < 60) return Math.round(min) + " min";
        return (int) (min / 60) + " h " + Math.round(min % 60) + " min";
    }

    static String num(double v, int maxDigits) {
        NumberFormat f = NumberFormat.getNumberInstance(RO);
        f.setMaximumFractionDigits(maxDigits);
        f.setMinimumFractionDigits(0);
        return f.format(v);
    }

    static String fixed(double v, int digits) {
        NumberFormat f = NumberFormat.getNumberInstance(RO);
        f.setMaximumFractionDigits(digits);
        f.setMinimumFractionDigits(digits);
        return f.format(v);
    }

    static String liters(double l) {
        return num(l, l < 10 ? 2 : 1) + "\u00A0L"; // fara rupere intre numar si unitate
    }

    static String lei(double v) {
        return num(v, v < 100 ? 2 : 0) + "\u00A0lei";
    }

    static String km(long km) {
        return num(km, 0) + "\u00A0km";
    }

    /** „acum 5 min”, „acum 2 h”, „ieri”. */
    static String ago(String iso) {
        long s = Math.max(0, (System.currentTimeMillis() - millis(iso)) / 1000);
        if (s < 60) return "acum câteva secunde";
        if (s < 3600) return "acum " + Math.round(s / 60.0) + " min";
        if (s < 86_400) return "acum " + Math.round(s / 3600.0) + " h";
        long d = Math.round(s / 86_400.0);
        return d == 1 ? "ieri" : "acum " + d + " zile";
    }
}
