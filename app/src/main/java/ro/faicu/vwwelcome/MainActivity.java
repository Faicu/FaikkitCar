package ro.faicu.vwwelcome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static ro.faicu.vwwelcome.Ui.dp;

/** Ecranul aplicatiei: antet cu starea, apoi file Acasa / Sunete / Setari / Jurnal. */
public class MainActivity extends Activity {
    private static final int PICK_SOUND = 1;
    private static final int PERM_AUDIO = 3;
    private static final int PERM_LOCATION = 4;
    // Coduri deja stabilite sau care se schimba singure; sonda nu le arata.
    private static final java.util.Set<String> KNOWN = new java.util.HashSet<>(java.util.Arrays.asList(
            "m7 c110", "m7 c1032", "m7 c109", "m7 c1031", "m7 c1033", "m7 c105", "m7 c1049", "m7 c106",
            "m7 c139", "m7 c104", "m7 c1", "m7 c2", "m7 c3", "m7 c4", "m7 c5", "m7 raw 0x7d", "m7 raw 0x41/2",
            "m7 c101", "m7 c103", "m7 c21", "m7 c27", "m7 c28", "m7 c1019",
            "m7 c10", "m7 c11", "m7 c13", "m7 c49", "m7 raw 0x21", "m7 raw 0x14",
            "m1 c*", "m4 c*", "m8 c*",
            "m0 c41", "m0 c114", "m0 c115", "m0 c146", "m0 c179", "m0 c101", "m0 c40", "m0 c77"));
    private TextView liveChanges;
    private static final String[] TABS = {"Acasa", "Sunete", "Setari", "Jurnal"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView[] tabViews = new TextView[TABS.length];
    private int tab;
    private FrameLayout content;
    private ScrollView scroll;
    private TextView statusPill;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);
        // Si daca aplicatia e deschisa din autostart, serviciul trebuie sa porneasca.
        // Marcam deschiderea, ca serviciul sa nu o ia drept trezire si sa redea sunetul.
        Prefs.markUiStart(this);
        WelcomeService.start(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        // Pe ecranul lat al navigatiei tinem continutul pe o coloana de max. 1000 dp.
        int screen = getResources().getDisplayMetrics().widthPixels;
        int side = Math.max(dp(this, 20), (screen - dp(this, 1000)) / 2);
        root.setPadding(side, dp(this, 16), side, 0);

        root.addView(header());
        root.addView(tabBar());
        content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1);
        clp.topMargin = dp(this, 16);
        root.addView(content, clp);
        setContentView(root);
        showTab(0, false);
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.logo);
        row.addView(icon, new LinearLayout.LayoutParams(dp(this, 56), dp(this, 56)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(this, 14), 0, 0, 0);
        texts.addView(Ui.text(this, "FaikkitCar", 26, Ui.TEXT, true));
        texts.addView(Ui.text(this, "v" + BuildConfig.VERSION_NAME + " · Golf 6", 14, Ui.MUTED, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        statusPill = Ui.pill(this, "", Ui.OK);
        row.addView(statusPill);
        return row;
    }

    private View tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(Ui.round(this, Ui.CARD, 16));
        int p = dp(this, 5);
        bar.setPadding(p, p, p, p);
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            TextView t = Ui.text(this, TABS[i], 17, Ui.MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(this, 12), 0, dp(this, 12));
            t.setOnClickListener(v -> showTab(index, false));
            tabViews[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 18);
        bar.setLayoutParams(lp);
        return bar;
    }

    /**
     * Reconstruieste fila. La reimprospatarea aceleiasi file (dupa orice buton) pastram pozitia
     * derularii; altfel fiecare apasare ducea ecranul inapoi sus.
     */
    private void showTab(int index, boolean keepScroll) {
        int y = keepScroll && scroll != null ? scroll.getScrollY() : 0;
        if (index != tab || !keepScroll) liveChanges = null;
        tab = index;
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == index;
            tabViews[i].setTextColor(on ? Ui.BG : Ui.MUTED);
            tabViews[i].setBackground(on ? Ui.round(this, Ui.ACCENT, 12) : null);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(this, 24));
        if (index == 0) buildHome(col);
        else if (index == 1) buildSounds(col);
        else if (index == 2) buildSettings(col);
        else buildLog(col);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        content.removeAllViews();
        content.addView(sv);
        scroll = sv;
        if (y > 0) sv.post(() -> sv.scrollTo(0, y));
        updateStatus();
    }

    private void refresh() {
        showTab(tab, true);
    }

    private boolean serviceRunning() {
        ActivityManager am = getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(100)) {
            if (s.service.getClassName().equals(WelcomeService.class.getName())) return true;
        }
        return false;
    }

    private boolean batteryOk() {
        return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    private void updateStatus() {
        boolean running = serviceRunning();
        int color = !running ? Ui.BAD : batteryOk() && Prefs.sounds(this).length > 0 ? Ui.OK : Ui.WARN;
        statusPill.setText(running ? "● Activ" : "● Oprit");
        statusPill.setTextColor(color);
        statusPill.setBackground(Ui.round(this, (color & 0x00FFFFFF) | 0x2E000000, 99));
    }

    // ---------------------------------------------------------------- Acasa

    // Tabloul de bord: titlul cu starea si valorile de acum se actualizeaza la 2 s (din CanLink),
    // calatoria si autonomia la 15 s (de pe car.faicu.ro).
    private TextView homeHeadline, homeSub;
    private TextView[] homeTiles;
    private LinearLayout homeTrip;
    // Calatoria in curs: de cand merge (ceasul navigatiei), actualizat la 2 s.
    private TextView homeTripSince;
    private long homeTripStart;
    private org.json.JSONObject summary;
    private long summaryAt;
    private boolean summaryLoading;
    private final Runnable homeTick = new Runnable() {
        @Override
        public void run() {
            if (tab != 0 || homeHeadline == null) return;
            updateHomeLive();
            updateTripSince();
            if (System.currentTimeMillis() - summaryAt > 15_000) loadSummary();
            ui.postDelayed(this, 2_000);
        }
    };

    /** „In mers · 34 km/h”, „Oprit in trafic · 45 s”, „Parcat, motor pornit”, „Contact pus”... */
    private String carHeadline() {
        CanLink can = CanLink.get(this);
        double kmh = can.speed();
        if (!Double.isNaN(kmh) && kmh >= 1) return "In mers · " + Math.round(kmh) + " km/h";
        long traffic = TripRecorder.trafficStopMs();
        if (traffic >= 0) {
            long s = traffic / 1000;
            return "Oprit in trafic · " + (s < 60 ? s + " s" : s / 60 + " min " + s % 60 + " s");
        }
        if (TripRecorder.parkedWithEngine()) return "Parcat, motor pornit";
        if (can.rpm() > 300) return "Motor pornit";
        if (can.dashFresh()) return "Contact pus";
        return "Gata de drum";
    }

    private void buildHome(LinearLayout col) {
        boolean running = serviceRunning();
        File[] sounds = Prefs.sounds(this);

        // 1. Starea masinii si valorile de acum.
        LinearLayout hero = Ui.card(this, col);
        homeHeadline = Ui.text(this, running ? carHeadline() : "Serviciul e oprit", 30, Ui.TEXT, true);
        hero.addView(homeHeadline);
        homeSub = Ui.hint(this, hero, "");
        LinearLayout tiles = new LinearLayout(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(this, 16);
        hero.addView(tiles, tlp);
        String[] labels = {"Viteza", "Turatie", "Baterie", "Afara", "Clima"};
        homeTiles = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) homeTiles[i] = tile(tiles, labels[i]);
        if (!running) {
            Ui.addButton(this, hero, "Porneste serviciul", Ui.PRIMARY, v -> {
                WelcomeService.start(this);
                ui.postDelayed(this::refresh, 800);
            });
        }

        // 2. Calatoria in curs (sau ultima) si ziua de azi, de pe server.
        homeTrip = new LinearLayout(this);
        homeTrip.setOrientation(LinearLayout.VERTICAL);
        col.addView(homeTrip);
        renderHomeTrip();

        // 3. Actualizare, mentenanta scadenta, avertizari.
        org.json.JSONObject apk = VwStatus.newerApk(this);
        if (apk != null) {
            LinearLayout up = Ui.card(this, col);
            up.addView(Ui.text(this, "Versiune noua: " + apk.optString("versionName"), 19, Ui.ACCENT, true));
            Ui.hint(this, up, "Se descarca de pe car.faicu.ro si se instaleaza peste aceasta; "
                    + "Android iti cere o confirmare. Fa-o cu masina oprita.");
            Ui.addButton(this, up, "Actualizeaza acum", Ui.PRIMARY, v -> {
                toast("Descarc actualizarea...");
                Updater.start(this, error -> {
                    if (error != null) toast("Actualizare esuata: " + error);
                    refresh();
                });
            });
        }
        java.util.List<VwStatus.Reminder> due = new java.util.ArrayList<>();
        for (VwStatus.Reminder r : VwStatus.reminders(this)) if (r.soon || r.overdue) due.add(r);
        if (!due.isEmpty()) {
            LinearLayout mt = Ui.card(this, col);
            Ui.title(this, mt, "Mentenanta");
            for (VwStatus.Reminder r : due) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(Ui.text(this, r.title, 17, Ui.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(Ui.pill(this, r.describe(), r.overdue ? Ui.BAD : Ui.WARN));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = dp(this, 10);
                mt.addView(row, rlp);
            }
        }
        if (!batteryOk()) {
            LinearLayout warn = Ui.card(this, col);
            warn.addView(Ui.text(this, "Optimizarea bateriei e activa", 18, Ui.WARN, true));
            Ui.hint(this, warn, "Android poate opri serviciul. Dezactiveaz-o pentru FaikkitCar.");
            Ui.addButton(this, warn, "Dezactiveaza optimizarea", Ui.SECONDARY, v -> askBattery());
        }
        boolean gps = TripRecorder.hasPermission(this);
        if (Prefs.tripsEnabled(this) && !gps) {
            LinearLayout warn = Ui.card(this, col);
            warn.addView(Ui.text(this, "Calatoriile nu au GPS", 18, Ui.WARN, true));
            Ui.addButton(this, warn, "Permite localizarea", Ui.PRIMARY, v -> askLocation());
        }

        // 4. Sunetul de bun venit, compact.
        LinearLayout welcome = Ui.card(this, col);
        LinearLayout wrow = new LinearLayout(this);
        wrow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout wtexts = new LinearLayout(this);
        wtexts.setOrientation(LinearLayout.VERTICAL);
        wtexts.addView(Ui.text(this, "Sunet de bun venit", 18, Ui.TEXT, true));
        String last = firstLine(Prefs.history(this));
        wtexts.addView(Ui.text(this, (sounds.length == 0 ? "niciun sunet ales" : sounds.length == 1
                ? Prefs.soundName(sounds[0]) : sounds.length + " sunete")
                + " · pauza " + fmtSec(Prefs.delayMs(this)) + " +" + fmtSec(Prefs.sleepExtraMs(this))
                + (last == null ? "" : "\nUltima trezire: " + last), 13, Ui.MUTED, false));
        wrow.addView(wtexts, new LinearLayout.LayoutParams(0, -2, 1));
        wrow.addView(Ui.button(this, "▶  Reda", Ui.SECONDARY, v -> {
            File f = Player.test(this);
            toast(f == null ? "Niciun sunet ales" : "Redau: " + Prefs.soundName(f));
        }));
        welcome.addView(wrow);

        // 5. Sistemul, intr-un rand.
        int pending = Prefs.outboxSize(this) + PointQueue.size(this);
        Ui.hint(this, col, "Server: " + (!Uploader.configured() || !Prefs.uploadEnabled(this) ? "oprit"
                : pending == 0 ? "la zi" : pending + " in asteptare")
                + " · Calatorii: " + (!Prefs.tripsEnabled(this) ? "oprite" : gps ? "se inregistreaza" : "fara GPS")
                + " · car.faicu.ro");

        updateHomeLive();
        ui.removeCallbacks(homeTick);
        if (running) ui.postDelayed(homeTick, 2_000);
        if (System.currentTimeMillis() - summaryAt > 15_000) loadSummary();
    }

    /** O casuta din randul de valori; intoarce textul valorii, actualizat de updateHomeLive. */
    private TextView tile(LinearLayout parent, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.CARD2, 14));
        int p = dp(this, 12);
        box.setPadding(p, p, p, p);
        TextView value = Ui.text(this, "—", 22, Ui.TEXT, true);
        box.addView(value);
        box.addView(Ui.text(this, label, 13, Ui.MUTED, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() > 0) lp.leftMargin = dp(this, 10);
        parent.addView(box, lp);
        return value;
    }

    /** Titlul, valorile de acum si randul cu rezervorul, din CanLink si din ultimul rezumat. */
    private void updateHomeLive() {
        if (homeHeadline == null) return;
        CanLink can = CanLink.get(this);
        if (serviceRunning()) homeHeadline.setText(carHeadline());
        double kmh = can.speed(), volt = can.volt(), temp = can.temp();
        homeTiles[0].setText(Double.isNaN(kmh) ? "—" : Math.round(kmh) + " km/h");
        homeTiles[1].setText(can.rpm() < 0 ? "—" : can.rpm() + " rpm");
        homeTiles[2].setText(Double.isNaN(volt) ? "—" : String.format(Locale.US, "%.1f V", volt));
        homeTiles[3].setText(Double.isNaN(temp) ? "—" : String.format(Locale.US, "%.0f °C", temp));
        String clima = "—";
        if (can.auto() >= 0 || can.ac() >= 0) {
            clima = (can.auto() == 1 ? "AUTO" : "manual") + (can.ac() == 1 ? " · AC" : "");
            int tl = can.tempLeft();
            if (tl > 0 && tl < 31) clima += String.format(Locale.US, " · %.1f°", 15.5 + tl / 2.0);
        }
        homeTiles[4].setText(clima);
        StringBuilder sub = new StringBuilder();
        org.json.JSONObject tank = summary == null ? null : summary.optJSONObject("tank");
        org.json.JSONObject range = summary == null ? null : summary.optJSONObject("range");
        int liters = can.fuel() > 0 ? can.fuel() : tank != null ? (int) Math.round(tank.optDouble("liters")) : -1;
        if (liters > 0) sub.append("Rezervor ").append(liters).append(" L");
        if (range != null) sub.append(" · ≈ ").append(range.optInt("km")).append(" km autonomie");
        if (can.odo() > 0) sub.append(sub.length() > 0 ? " · " : "").append(String.format(Locale.US, "%,d km", can.odo()).replace(',', '.'));
        homeSub.setText(sub.length() == 0 ? "Datele masinii apar cu contactul pus." : sub.toString());
    }

    private void loadSummary() {
        if (summaryLoading || !Uploader.configured()) return;
        summaryLoading = true;
        new Thread(() -> {
            org.json.JSONObject s = VwStatus.summary();
            runOnUiThread(() -> {
                summaryLoading = false;
                summaryAt = System.currentTimeMillis();
                if (s == null) return;
                summary = s;
                renderHomeTrip();
                updateHomeLive();
            });
        }).start();
    }

    /** Calatoria in curs (sau ultima) si ziua de azi, din rezumatul de pe server. */
    private void renderHomeTrip() {
        if (homeTrip == null) return;
        homeTrip.removeAllViews();
        if (summary == null) return;
        org.json.JSONObject trip = summary.optJSONObject("trip");
        boolean current = trip != null;
        if (trip == null) trip = summary.optJSONObject("last");
        homeTripSince = null;
        if (trip != null) {
            LinearLayout card = Ui.card(this, homeTrip);
            String route = route(trip);
            String start = trip.optString("start");
            card.addView(Ui.text(this, (current ? "Calatoria in curs · de la " + hm(start)
                    : "Ultima calatorie · " + hm(start) + "–" + hm(trip.optString("end")))
                    + (route.isEmpty() ? "" : " · " + route), 15, current ? Ui.ACCENT : Ui.MUTED, true));
            LinearLayout row = new LinearLayout(this);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = dp(this, 12);
            card.addView(row, rlp);
            stat(row, "Distanta", String.format(Locale.US, "%.1f km", trip.optDouble("distanceKm")), Ui.TEXT);
            TextView dur = stat(row, current ? "De cand merg" : "Durata", minutes(trip.optDouble("durationMin")), Ui.TEXT);
            stat(row, "Consumat", trip.isNull("fuelL") ? "—"
                    : String.format(Locale.US, "≈ %.2f L", trip.optDouble("fuelL")), Ui.TEXT);
            stat(row, "Consum", trip.isNull("lPer100") ? "—"
                    : String.format(Locale.US, "%.1f L/100", trip.optDouble("lPer100")), Ui.TEXT);
            stat(row, "Cost", trip.isNull("cost") ? "—"
                    : String.format(Locale.US, "%.2f lei", trip.optDouble("cost")), Ui.TEXT);
            if (trip.optDouble("trafficMin") > 0) {
                Ui.hint(this, card, "Din care oprit in trafic " + minutes(trip.optDouble("trafficMin"))
                        + (trip.optInt("trafficStops") > 0 ? " (" + trip.optInt("trafficStops") + " opriri)" : ""));
            }
            if (current) {
                homeTripSince = dur;
                homeTripStart = parseIso(start);
                updateTripSince();
            }
        }
        org.json.JSONObject today = summary.optJSONObject("today");
        if (today != null && today.optInt("trips") > 0) {
            Ui.hint(this, homeTrip, String.format(Locale.US, "Azi: %d %s · %.1f km · %s la volan%s",
                    today.optInt("trips"), today.optInt("trips") == 1 ? "calatorie" : "calatorii",
                    today.optDouble("km"), minutes(today.optDouble("minutes")),
                    today.isNull("cost") ? "" : String.format(Locale.US, " · %.2f lei", today.optDouble("cost"))));
        }
    }

    /** „De cand merg”: de la plecare pana acum, nu doar pana la ultimul punct primit de server. */
    private void updateTripSince() {
        if (homeTripSince == null || homeTripStart <= 0) return;
        homeTripSince.setText(minutes(Math.max(0, System.currentTimeMillis() - homeTripStart) / 60_000.0));
    }

    private static long parseIso(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String minutes(double min) {
        if (min < 1) return Math.round(min * 60) + " s";
        if (min < 60) return Math.round(min) + " min";
        return (int) (min / 60) + " h " + Math.round(min % 60) + " min";
    }

    private static String hm(String iso) {
        try {
            return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(java.time.Instant.parse(iso).toEpochMilli()));
        } catch (Exception e) {
            return "";
        }
    }

    private static String route(org.json.JSONObject t) {
        if (t.isNull("fromPlace") && t.isNull("toPlace")) return "";
        return (t.isNull("fromPlace") ? "…" : t.optString("fromPlace")) + " → "
                + (t.isNull("toPlace") ? "…" : t.optString("toPlace"));
    }

    // ---------------------------------------------------------------- sonda CAN (avansat)

    // Calibrarea ghidata nu mai e necesara (toate codurile folosite sunt confirmate); sonda
    // ramane pentru cand mai cautam ceva: 10 minute, marcaje cu text si schimbarile live.
    private long markAt;

    private void buildProbe(LinearLayout col) {
        LinearLayout card = Ui.card(this, col);
        Ui.title(this, card, "Sonda CAN (avansat)");
        boolean on = CanProbe.isRunning();
        Ui.hint(this, card, "Pentru cand cautam un cod nou: porneste sonda (10 minute), fa actiunea "
                + "in masina si apasa Marcheaza. Schimbarile de dupa ultimul marcaj ajung pe server.");
        if (!on) {
            Ui.addButton(this, card, "Porneste sonda (10 minute)", Ui.SECONDARY, v -> {
                Prefs.setCanProbeUntil(this, System.currentTimeMillis() + 10 * 60_000L);
                CanProbe.check(getApplicationContext());
                markAt = System.currentTimeMillis();
                ui.postDelayed(() -> CanProbe.mark("start sonda"), 1500);
                refresh();
            });
            return;
        }
        EditText label = new EditText(this);
        label.setHint("Ce ai facut (ex. am aprins farurile)");
        label.setHintTextColor(Ui.MUTED);
        label.setTextColor(Ui.TEXT);
        label.setSingleLine(true);
        label.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        label.setBackground(Ui.round(this, Ui.CARD2, 12));
        int lp0 = dp(this, 14);
        label.setPadding(lp0, lp0, lp0, lp0);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(this, 12);
        card.addView(label, llp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.CARD2, 12));
        int p = dp(this, 12);
        box.setPadding(p, p, p, p);
        box.addView(Ui.text(this, "Schimbari de la ultimul marcaj", 13, Ui.MUTED, false));
        liveChanges = Ui.mono(this, "", 14);
        box.addView(liveChanges);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(this, 12);
        card.addView(box, blp);
        updateLive();
        LinearLayout btns = new LinearLayout(this);
        btns.addView(Ui.button(this, "Marcheaza", Ui.PRIMARY, v -> {
            String what = label.getText().toString().trim();
            String found = probeChanges().replace("\n", "; ");
            CanProbe.mark((what.isEmpty() ? "marcaj" : what) + " | " + (found.isEmpty() ? "nicio schimbare" : found));
            markAt = System.currentTimeMillis();
            label.setText("");
            toast("Marcat");
            updateLive();
        }), new LinearLayout.LayoutParams(0, -2, 2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1);
        sp.leftMargin = dp(this, 10);
        btns.addView(Ui.button(this, "Opreste", Ui.DANGER, v -> {
            Prefs.setCanProbeUntil(this, 0);
            CanProbe.check(getApplicationContext());
            liveChanges = null;
            refresh();
        }), sp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(this, 14);
        card.addView(btns, rlp);
    }

    /** Schimbarile de la ultimul marcaj, cate una pe cod (prima si ultima valoare), fara cele stiute. */
    private String probeChanges() {
        java.util.LinkedHashMap<String, CanProbe.Change> byKey = new java.util.LinkedHashMap<>();
        for (CanProbe.Change ch : CanProbe.changesSince(markAt, KNOWN)) {
            CanProbe.Change first = byKey.get(ch.key);
            byKey.put(ch.key, first == null ? ch : new CanProbe.Change(ch.t, ch.key, first.from, ch.to));
        }
        List<String> out = new ArrayList<>();
        for (CanProbe.Change ch : byKey.values()) out.add(ch.toString());
        return String.join("\n", out);
    }

    private final Runnable liveTick = this::updateLive;

    private void updateLive() {
        ui.removeCallbacks(liveTick);
        if (liveChanges == null) return;
        String changes = probeChanges();
        liveChanges.setText(!CanProbe.isRunning() ? "sonda porneste..."
                : changes.isEmpty() ? "inca nimic — fa actiunea" : changes);
        ui.postDelayed(liveTick, 700);
    }

    private void askLocation() {
        requestPermissions(new String[] {android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION}, PERM_LOCATION);
    }

    /** O casuta cu eticheta si valoare; intoarce valoarea, ca sa poata fi actualizata. */
    private TextView stat(LinearLayout parent, String label, String value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.CARD2, 14));
        int p = dp(this, 14);
        box.setPadding(p, p, p, p);
        box.addView(Ui.text(this, label, 13, Ui.MUTED, false));
        TextView v = Ui.text(this, value, 20, color, true);
        box.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() > 0) lp.leftMargin = dp(this, 10);
        parent.addView(box, lp);
        return v;
    }

    // ---------------------------------------------------------------- Sunete

    private void buildSounds(LinearLayout col) {
        LinearLayout list = Ui.card(this, col);
        Ui.title(this, list, "Sunetele de bun venit");
        File[] sounds = Prefs.sounds(this);
        if (sounds.length == 0) {
            Ui.hint(this, list, "Niciun sunet inca. Adauga un MP3 de pe navigatie.");
        }
        for (File f : sounds) {
            Ui.divider(this, list);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView note = Ui.text(this, "♪", 20, Ui.ACCENT, true);
            note.setGravity(Gravity.CENTER);
            note.setBackground(Ui.round(this, 0x2238BDF8, 99));
            row.addView(note, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
            TextView name = Ui.text(this, Prefs.soundName(f), 17, Ui.TEXT, false);
            name.setPadding(dp(this, 14), 0, dp(this, 8), 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Ui.button(this, "▶", Ui.SECONDARY, v -> Player.play(this, f, 0, true)));
            Button del = Ui.button(this, "✕", Ui.DANGER, v -> new AlertDialog.Builder(this,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Stergi " + Prefs.soundName(f) + "?")
                    .setPositiveButton("Sterge", (d, w) -> {
                        f.delete();
                        refresh();
                    })
                    .setNegativeButton("Renunta", null)
                    .show());
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.leftMargin = dp(this, 8);
            row.addView(del, dlp);
            list.addView(row);
        }
        Ui.addButton(this, list, "+  Adauga sunete", Ui.PRIMARY, v -> pickSound());

        LinearLayout order = Ui.card(this, col);
        Ui.title(this, order, "Ordinea de redare");
        Ui.hint(this, order, "Cu mai multe sunete, la fiecare pornire se reda urmatorul.");
        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(Ui.round(this, Ui.CARD2, 14));
        int p = dp(this, 4);
        seg.setPadding(p, p, p, p);
        boolean random = Prefs.randomOrder(this);
        seg.addView(segment("La rand", !random, v -> {
            Prefs.setRandomOrder(this, false);
            refresh();
        }), new LinearLayout.LayoutParams(0, -2, 1));
        seg.addView(segment("Aleatoriu", random, v -> {
            Prefs.setRandomOrder(this, true);
            refresh();
        }), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sgl = new LinearLayout.LayoutParams(-1, -2);
        sgl.topMargin = dp(this, 14);
        order.addView(seg, sgl);

        LinearLayout vol = Ui.card(this, col);
        Ui.title(this, vol, "Volum");
        TextView volLabel = Ui.hint(this, vol, volumeText());
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Prefs.volumePercent(this));
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (fromUser) Prefs.setVolumePercent(MainActivity.this, value);
                volLabel.setText(volumeText());
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(this, 48));
        blp.topMargin = dp(this, 8);
        vol.addView(bar, blp);
    }

    private TextView segment(String label, boolean on, View.OnClickListener click) {
        TextView t = Ui.text(this, label, 16, on ? Ui.BG : Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(this, 12), 0, dp(this, 12));
        if (on) t.setBackground(Ui.round(this, Ui.ACCENT, 11));
        t.setOnClickListener(click);
        return t;
    }

    private String volumeText() {
        int v = Prefs.volumePercent(this);
        return v == 0 ? "0 = se foloseste volumul curent al sistemului" : v + "% din volumul maxim, refacut dupa redare";
    }

    // ---------------------------------------------------------------- Setari

    private void buildSettings(LinearLayout col) {
        LinearLayout det = Ui.card(this, col);
        Ui.title(this, det, "Detectie si redare");
        EditText threshold = Ui.field(this, det, "Prag hibernare",
                "Secunde de somn care conteaza ca pornire (min. 30)",
                String.valueOf(Prefs.thresholdSec(this)), false);
        EditText delay = Ui.field(this, det, "Pauza inainte de redare",
                "Secunde, cat porneste amplificatorul (0-15)", fmtNum(Prefs.delayMs(this)), true);
        EditText extra = Ui.field(this, det, "Pauza dupa hibernare",
                "Secunde adaugate dupa un somn adevarat (0-15)", fmtNum(Prefs.sleepExtraMs(this)), true);
        Ui.addButton(this, det, "Salveaza", Ui.PRIMARY, v -> {
            try {
                Prefs.setThresholdSec(this, Integer.parseInt(threshold.getText().toString().trim()));
                Prefs.setDelayMs(this, Math.round(parse(delay) * 1000));
                Prefs.setSleepExtraMs(this, Math.round(parse(extra) * 1000));
                toast("Salvat");
            } catch (NumberFormatException e) {
                toast("Numar invalid");
            }
            refresh();
        });

        LinearLayout srv = Ui.card(this, col);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, "Trimite jurnalul la server", 18, Ui.TEXT, true));
        String st = Prefs.uploadStatus(this);
        texts.addView(Ui.text(this, !Uploader.configured() ? "Indisponibil in acest build"
                : "car.faicu.ro · in asteptare: " + Prefs.outboxSize(this)
                        + (st.isEmpty() ? "" : " · ultima: " + st), 13, Ui.MUTED, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(Uploader.configured() && Prefs.uploadEnabled(this));
        sw.setEnabled(Uploader.configured());
        sw.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {Ui.ACCENT, Ui.LINE}));
        sw.setOnCheckedChangeListener((b, on) -> {
            Prefs.setUploadEnabled(this, on);
            Uploader.kick(this);
        });
        row.addView(sw);
        srv.addView(row);
        Ui.divider(this, srv);
        LinearLayout trow = new LinearLayout(this);
        trow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ttexts = new LinearLayout(this);
        ttexts.setOrientation(LinearLayout.VERTICAL);
        ttexts.addView(Ui.text(this, "Inregistreaza calatoriile", 18, Ui.TEXT, true));
        ttexts.addView(Ui.text(this, "GPS + date de la masina, la car.faicu.ro", 13,
                Ui.MUTED, false));
        trow.addView(ttexts, new LinearLayout.LayoutParams(0, -2, 1));
        Switch tsw = new Switch(this);
        tsw.setChecked(Prefs.tripsEnabled(this));
        tsw.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        tsw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {Ui.ACCENT, Ui.LINE}));
        tsw.setOnCheckedChangeListener((b, on) -> {
            Prefs.setTripsEnabled(this, on);
            TripRecorder.check(getApplicationContext());
            if (on && !TripRecorder.hasPermission(this)) askLocation();
        });
        trow.addView(tsw);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(this, 8);
        srv.addView(trow, tlp);

        LinearLayout voice = Ui.card(this, col);
        Ui.title(this, voice, "In mers");
        toggle(voice, "Salut vorbit dupa 3 minute", "Ora zilei, temperatura de afara, mentenanta scadenta",
                Prefs.greetEnabled(this), on -> Prefs.setGreetEnabled(this, on));
        Ui.divider(this, voice);
        toggle(voice, "Avertizare usa deschisa", "Cand masina porneste din loc cu o usa sau portbagajul deschis",
                Prefs.doorAlertEnabled(this), on -> Prefs.setDoorAlertEnabled(this, on));
        Ui.addButton(this, voice, "Asculta salutul acum", Ui.SECONDARY,
                v -> Speaker.say(this, Greeting.salute(this, CanLink.get(this).temp()), false));

        LinearLayout sys = Ui.card(this, col);
        Ui.title(this, sys, "Sistem");
        Ui.addButton(this, sys, "Verifica actualizari", Ui.SECONDARY, v -> new Thread(() -> {
            boolean ok = VwStatus.refresh(this);
            runOnUiThread(() -> {
                toast(!ok ? "Serverul nu raspunde" : VwStatus.newerApk(this) != null
                        ? "Exista o versiune noua, vezi Acasa" : "Ai ultima versiune");
                refresh();
            });
        }).start());
        Ui.hint(this, sys, "Optimizare baterie: " + (batteryOk() ? "dezactivata (ok)" : "ACTIVA"));
        Ui.addButton(this, sys, "Optimizarea bateriei", Ui.SECONDARY, v -> askBattery());
        Ui.addButton(this, sys, "Porneste serviciul", Ui.SECONDARY, v -> {
            WelcomeService.start(this);
            toast("Serviciul ruleaza");
            ui.postDelayed(this::refresh, 800);
        });

        buildProbe(col);
    }

    /** Rand cu titlu, explicatie si comutator. */
    private void toggle(LinearLayout parent, String title, String help, boolean value,
            java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 17, Ui.TEXT, true));
        texts.addView(Ui.text(this, help, 13, Ui.MUTED, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {Ui.ACCENT, Ui.LINE}));
        sw.setOnCheckedChangeListener((b, on) -> onChange.accept(on));
        row.addView(sw);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 12);
        parent.addView(row, lp);
    }

    // ---------------------------------------------------------------- Jurnal

    private void buildLog(LinearLayout col) {
        LinearLayout hist = Ui.card(this, col);
        Ui.title(this, hist, "Istoric treziri");
        String h = Prefs.history(this);
        TextView ht = Ui.mono(this, h.isEmpty() ? "nicio trezire inca" : h, 13);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(this, 12);
        hist.addView(ht, tlp);
        Ui.addButton(this, hist, "Sterge istoricul", Ui.DANGER, v -> {
            Prefs.clearHistory(this);
            refresh();
        });

        LinearLayout log = Ui.card(this, col);
        Ui.title(this, log, "Jurnal diagnostic");
        Ui.hint(this, log, "Ultimele 100 de evenimente. Jurnalul complet e pe car.faicu.ro.");
        String l = Prefs.logText(this);
        TextView lt = Ui.mono(this, l.isEmpty() ? "gol" : l, 12);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(this, 12);
        log.addView(lt, llp);
        LinearLayout btns = new LinearLayout(this);
        btns.addView(Ui.button(this, "Copiaza", Ui.SECONDARY, v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(
                    "FaikkitCar", Prefs.deviceInfo(this) + "\n\nIstoric:\n" + Prefs.history(this)
                            + "\n\nJurnal:\n" + Prefs.logText(this)));
            toast("Jurnal copiat in clipboard");
        }), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
        clp.leftMargin = dp(this, 12);
        btns.addView(Ui.button(this, "Sterge", Ui.DANGER, v -> {
            Prefs.clearLog(this);
            refresh();
        }), clp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(this, 12);
        log.addView(btns, blp);
    }

    // ---------------------------------------------------------------- utilitare

    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return null;
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String fmtNum(long ms) {
        double s = ms / 1000.0;
        return s == Math.rint(s) ? String.valueOf((long) s) : String.valueOf(s);
    }

    private static String fmtSec(long ms) {
        return fmtNum(ms) + " s";
    }

    private static double parse(EditText e) {
        return Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Android 13+: fara permisiune, notificarea serviciului nu apare (serviciul merge oricum).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 2);
        } else if (Prefs.tripsEnabled(this) && !TripRecorder.hasPermission(this)) {
            askLocation();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(liveTick);
        ui.removeCallbacks(homeTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // Serviciul pornit in onCreate apare in lista abia dupa cateva sute de ms.
        ui.postDelayed(this::updateStatus, 1000);
        // Mentenanta si versiunea noua, daca starea de pe server e mai veche de 30 de minute.
        new Thread(() -> {
            long before = Prefs.statusAt(this);
            VwStatus.refreshIfOld(this);
            if (Prefs.statusAt(this) != before) runOnUiThread(this::refresh);
        }).start();
    }

    /**
     * Selectorul de fisiere Android lipseste pe unele navigatii (Teyes): incercam pe rand
     * OPEN_DOCUMENT, GET_CONTENT, apoi lista noastra de fisiere audio din MediaStore.
     */
    private void pickSound() {
        for (String action : new String[] {Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT}) {
            Intent i = new Intent(action);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(i, PICK_SOUND);
                return;
            } catch (ActivityNotFoundException e) {
                Prefs.log(this, "Selector fisiere indisponibil: " + action);
            }
        }
        pickFromMediaStore();
    }

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_AUDIO"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    /** Lista proprie: fisierele audio indexate de Android (Download, Music, stick USB etc.). */
    private void pickFromMediaStore() {
        if (checkSelfPermission(audioPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {audioPermission()}, PERM_AUDIO);
            return;
        }
        List<Uri> uris = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME},
                null, null, MediaStore.Audio.Media.DISPLAY_NAME)) {
            while (c != null && c.moveToNext()) {
                uris.add(Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0))));
                names.add(c.getString(1));
            }
        } catch (Exception e) {
            Prefs.log(this, "Lista audio: eroare " + e);
        }
        Prefs.log(this, "Lista audio: " + uris.size() + " fisiere");
        if (uris.isEmpty()) {
            toast("Niciun fisier audio gasit. Copiaza MP3-ul pe navigatie (ex. in Download)");
            return;
        }
        boolean[] checked = new boolean[uris.size()];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Alege sunetele")
                .setMultiChoiceItems(names.toArray(new String[0]), checked,
                        (d, which, on) -> checked[which] = on)
                .setPositiveButton("Adauga", (d, w) -> {
                    int added = 0;
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i] && copySound(uris.get(i))) added++;
                    }
                    toast(added == 1 ? "Sunet adaugat" : added + " sunete adaugate");
                    refresh();
                })
                .setNegativeButton("Renunta", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == PERM_LOCATION) {
            TripRecorder.check(getApplicationContext());
            refresh();
            return;
        }
        if (req != PERM_AUDIO) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pickFromMediaStore();
        } else {
            toast("Fara permisiune nu pot citi fisierele audio");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_SOUND || res != RESULT_OK || data == null) return;
        int added = 0;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (copySound(clip.getItemAt(i).getUri())) added++;
            }
        } else if (data.getData() != null && copySound(data.getData())) {
            added++;
        }
        toast(added == 1 ? "Sunet adaugat" : added + " sunete adaugate");
        refresh();
    }

    private boolean copySound(Uri uri) {
        String name = displayName(uri).replaceAll("[^\\w.\\- ]", "_");
        File dest;
        long t = System.currentTimeMillis();
        do {
            dest = new File(Prefs.soundsDir(this), (t++) + "_" + name);
        } while (dest.exists());
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            dest.delete();
            toast("Eroare la copiere: " + e.getMessage());
            return false;
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        return "sunet";
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast("Seteaza manual din Setari > Aplicatii > FaikkitCar > Baterie");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
