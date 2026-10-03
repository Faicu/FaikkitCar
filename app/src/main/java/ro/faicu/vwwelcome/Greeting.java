package ro.faicu.vwwelcome;

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
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        StringBuilder sb = new StringBuilder(h >= 5 && h < 11 ? "Bună dimineața!"
                : h >= 11 && h < 18 ? "Bună ziua!" : h >= 18 && h < 23 ? "Bună seara!" : "Salut!");
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

    static String doorOpen(List<Integer> open) {
        List<String> names = new ArrayList<>();
        for (int i : open) names.add(CanLink.DOORS[i]);
        String what = String.join(" și ", names);
        boolean plural = names.size() > 1;
        boolean masc = !plural && open.get(0) == CanLink.DOORS.length - 1; // portbagajul
        return "Atenție, " + what + (plural ? " sunt deschise!" : masc ? " e deschis!" : " e deschisă!");
    }
}
