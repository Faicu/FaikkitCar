package ro.faicu.faikkitcar;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Textele rostite in mers: salutul de dupa 30 s de mers (ora zilei, temperatura de afara,
 * mentenanta scadenta) si avertizarea de usa deschisa. Cu diacritice, pentru TextToSpeech.
 */
final class Greeting {
    private Greeting() {}

    static String salute(Context c, double outsideTemp) {
        StringBuilder sb = new StringBuilder(hello(Calendar.getInstance()));
        if (!Double.isNaN(outsideTemp)) sb.append(' ').append(temperature((int) Math.round(outsideTemp)));
        List<String> due = new ArrayList<>();
        for (VwStatus.Reminder r : VwStatus.reminders(c)) {
            if (!r.soon && !r.overdue) continue;
            due.add(reminder(r));
            if (due.size() == 2) break;
        }
        if (!due.isEmpty()) sb.append(" Atenție: ").append(String.join(" ", due));
        sb.append(" Drum bun!");
        return sb.toString();
    }

    /** Bună seara de la apus (iarna e întuneric de la ~16:40), cel târziu de la 20:00. */
    static String hello(Calendar now) {
        double h = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0;
        if (h >= 5 && h < 11) return "Bună dimineața!";
        if (h >= 11 && h < Math.min(sunset(now), 20)) return "Bună ziua!";
        if (h >= 11 && h < 23) return "Bună seara!";
        return "Salut!";
    }

    // Bucuresti: masina sta in oras, iar o eroare de cateva minute la apus nu conteaza.
    private static final double LAT = 44.44, LON = 26.05;

    /** Ora apusului (ora locala, cu zecimale), formula NOAA simplificata. */
    static double sunset(Calendar now) {
        int day = now.get(Calendar.DAY_OF_YEAR);
        double g = 2 * Math.PI / 365 * (day - 1);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g)
                - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double lat = Math.toRadians(LAT);
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(lat) * Math.cos(decl))
                - Math.tan(lat) * Math.tan(decl);
        double ha = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosHa))));
        double utcMin = 720 - 4 * (LON - ha) - eqTime;
        double offsetMin = (now.get(Calendar.ZONE_OFFSET) + now.get(Calendar.DST_OFFSET)) / 60000.0;
        return (utcMin + offsetMin) / 60;
    }

    private static String temperature(int t) {
        if (t == 0) return "Afară sunt zero grade.";
        String sign = t < 0 ? "minus " : "";
        int a = Math.abs(t);
        if (a == 1) return "Afară este " + sign + "un grad.";
        // In romana: "20 de grade" de la 20 in sus (fara 1-19 si fara cele terminate in 01-19).
        boolean de = a % 100 == 0 || a % 100 >= 20;
        return "Afară sunt " + sign + a + (de ? " de grade." : " grade.");
    }

    private static String reminder(VwStatus.Reminder r) {
        if (r.overdue) return r.title + " a expirat.";
        if (r.kmLeft != null && r.kmLeft <= 1000) return r.title + " peste " + r.kmLeft + " de kilometri.";
        if (r.daysLeft != null) return r.title + " în " + r.daysLeft + (r.daysLeft == 1 ? " zi." : " zile.");
        return r.title + " în curând.";
    }

    /** Modemul a revenit pe automat (dupa somn, 01.11): pe 2G nu merge internetul. */
    static String networkMode() {
        return "Atenție! Rețeaua mobilă a revenit pe automat. Pune din nou doar 4G, de pe ecranul aplicației.";
    }

    static String doorOpen(List<Integer> open) {
        List<String> names = new ArrayList<>();
        for (int i : open) names.add(CanLink.DOORS[i]);
        String what = String.join(" și ", names);
        boolean plural = names.size() > 1;
        boolean masc = !plural && open.get(0) == CanLink.DOORS.length - 1; // portbagajul
        return "Atenție, " + what + (plural ? " sunt deschise!" : masc ? " e deschis!" : " e deschisă!");
    }
}
