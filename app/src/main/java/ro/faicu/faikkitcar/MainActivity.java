package ro.faicu.faikkitcar;

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

import static ro.faicu.faikkitcar.Ui.dp;

/** Ecranul aplicatiei: antet cu starea, apoi file Acasa / Sunete / Setari / Jurnal. */
public class MainActivity extends Activity {
    private static final int PICK_SOUND = 1;
    private static final int PERM_AUDIO = 3;
    private static final int PERM_LOCATION = 4;
    // Coduri deja stabilite sau care se schimba singure; sonda nu le arata.
    private static final java.util.Set<String> KNOWN = new java.util.HashSet<>(java.util.Arrays.asList(
            "m7 c110", "m7 c1032", "m7 c109", "m7 c1031", "m7 c1033", "m7 c105", "m7 c1049", "m7 c106",
            "m7 c139", "m7 c104", "m7 c1", "m7 c2", "m7 c3", "m7 c4", "m7 c5", "m7 raw 0x7d", "m7 raw 0x41/2",
            "m7 c101", "m7 c103", "m7 c21", "m7 c1019",
            // c10, c13, c27, c28 si cadrul 0x21 raman vizibile: SYNC-ul climei inca n-are cod (12.10).
            "m7 c11", "m7 c49", "m7 raw 0x14",
            "m1 c*", "m4 c*", "m8 c*",
            "m0 c41", "m0 c114", "m0 c115", "m0 c146", "m0 c179", "m0 c101", "m0 c40", "m0 c77"));
    private TextView liveChanges;
    private static final String[] TABS = {"⌂  Acasa", "♪  Sunete", "⚙  Setari", "☰  Jurnal"};

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
        RouteMap.init(this);
        // Si daca aplicatia e deschisa din autostart, serviciul trebuie sa porneasca.
        // Marcam deschiderea, ca serviciul sa nu o ia drept trezire si sa redea sunetul.
        Prefs.markUiStart(this);
        WelcomeService.start(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        // Continutul ocupa tot ecranul (Acasa: harta), iar meniul e o bara ingusta jos.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = dp(this, 12);
        root.setPadding(pad, pad, pad, dp(this, 8));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bottomBar());
        setContentView(root);
        showTab(0, false);
    }

    /** Bara de jos: iconita, filele (compacte) si, in dreapta, versiunea si starea serviciului. */
    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(this, Ui.CARD, 14));
        int p = dp(this, 4);
        bar.setPadding(dp(this, 8), p, dp(this, 8), p);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.logo);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34));
        ilp.rightMargin = dp(this, 8);
        bar.addView(icon, ilp);
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            TextView t = Ui.text(this, TABS[i], 16, Ui.MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(this, 18), dp(this, 9), dp(this, 18), dp(this, 9));
            t.setOnClickListener(v -> showTab(index, false));
            tabViews[i] = t;
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.rightMargin = dp(this, 4);
            bar.addView(t, tlp);
        }
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView version = Ui.text(this, "v" + BuildConfig.VERSION_NAME, 13, Ui.MUTED, false);
        version.setPadding(dp(this, 8), 0, dp(this, 10), 0);
        bar.addView(version);
        statusPill = Ui.pill(this, "", Ui.OK);
        bar.addView(statusPill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 8);
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
        content.removeAllViews();
        if (index == 0) {
            content.addView(buildHome());
            scroll = null;
            updateStatus();
            return;
        }
        homeHeadline = null; // opreste reimprospatarea Acasa
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // Pe ecranul lat al navigatiei, celelalte file stau pe o coloana de max. 1000 dp.
        int screen = getResources().getDisplayMetrics().widthPixels;
        int side = Math.max(0, (screen - dp(this, 1000)) / 2);
        col.setPadding(side, 0, side, dp(this, 24));
        if (index == 1) buildSounds(col);
        else if (index == 2) buildSettings(col);
        else buildLog(col);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
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

    // Acasa (din 12.10): harta mare (calatoria in curs sau ultima, cu masina la capat) si, alaturi,
    // doar ce nu arata bordul: viteza (mare), starea, costul si traficul calatoriei, ziua de azi si
    // avertizarile. Viteza, starea si bateria la 2 s (CanLink); calatoria si harta la 15 s (server).
    private TextView homeHeadline, homeSpeed, homeBattery, homeMapHint;
    private View homeFollow;
    private LinearLayout homeTrip;
    private org.json.JSONObject summary;
    private RouteMap routeMap; // pastrata intre redesenari, doar mutata in pagina noua
    private long summaryAt;
    private boolean summaryLoading;
    private boolean updateBusyShown; // butonul de actualizare arata „Se instaleaza...”
    private final Runnable homeTick = new Runnable() {
        @Override
        public void run() {
            if (tab != 0 || homeHeadline == null) return;
            // Instalarea a esuat sau a fost anulata: butonul revine.
            if (updateBusyShown && !Updater.busy(MainActivity.this)) {
                refresh();
                return;
            }
            updateHomeLive();
            if (System.currentTimeMillis() - summaryAt > 15_000) loadSummary();
            ui.postDelayed(this, 2_000);
        }
    };

    /** „In mers”, „Oprit in trafic · 45 s”, „Parcat, motor pornit”, „Contact pus”... */
    private String carHeadline() {
        CanLink can = CanLink.get(this);
        double kmh = can.speed();
        if (!Double.isNaN(kmh) && kmh >= 1) return "In mers";
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

    /** Ecranul lat al navigatiei: harta in stanga, restul in dreapta; pe ecran ingust, una sub alta. */
    private View buildHome() {
        boolean wide = getResources().getDisplayMetrics().widthPixels
                > getResources().getDisplayMetrics().heightPixels;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        // 1. Harta.
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(this, Ui.CARD, 18));
        box.setClipToOutline(true);
        RouteMap m = routeMap();
        if (m.map.getParent() != null) ((android.view.ViewGroup) m.map.getParent()).removeView(m.map);
        box.addView(m.map, new FrameLayout.LayoutParams(-1, -1));
        homeMapHint = Ui.text(this, "Astept traseul de la car.faicu.ro...", 16, Ui.MUTED, false);
        homeMapHint.setGravity(Gravity.CENTER);
        homeMapHint.setBackgroundColor(0x99000000);
        box.addView(homeMapHint, new FrameLayout.LayoutParams(-1, -1));
        Button follow = Ui.button(this, "◎  Urmareste masina", Ui.PRIMARY, v -> {
            m.fit();
            updateMapOverlay();
        });
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, dp(this, 14), dp(this, 14));
        box.addView(follow, flp);
        homeFollow = follow;
        page.addView(box, wide ? new LinearLayout.LayoutParams(0, -1, 2.2f)
                : new LinearLayout.LayoutParams(-1, dp(this, 420)));

        // 2. Alaturi: viteza, starea, calatoria, avertizarile.
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(wide ? dp(this, 16) : 0, wide ? 0 : dp(this, 16), 0, dp(this, 16));
        boolean running = serviceRunning();
        LinearLayout hero = Ui.card(this, side);
        homeHeadline = Ui.text(this, running ? carHeadline() : "Serviciul e oprit", 20, Ui.MUTED, true);
        hero.addView(homeHeadline);
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setGravity(Gravity.BOTTOM);
        homeSpeed = Ui.text(this, "—", 72, Ui.TEXT, true);
        homeSpeed.setIncludeFontPadding(false);
        speedRow.addView(homeSpeed);
        TextView unit = Ui.text(this, " km/h", 20, Ui.MUTED, false);
        unit.setPadding(0, 0, 0, dp(this, 10));
        speedRow.addView(unit);
        hero.addView(speedRow);
        homeBattery = Ui.text(this, "", 15, Ui.WARN, true);
        homeBattery.setVisibility(View.GONE);
        hero.addView(homeBattery);
        if (!running) {
            Ui.addButton(this, hero, "Porneste serviciul", Ui.PRIMARY, v -> {
                WelcomeService.start(this);
                ui.postDelayed(this::refresh, 800);
            });
        }
        homeTrip = new LinearLayout(this);
        homeTrip.setOrientation(LinearLayout.VERTICAL);
        side.addView(homeTrip);
        renderHomeTrip();
        addHomeWarnings(side);

        int pending = Prefs.outboxSize(this) + PointQueue.size(this);
        boolean gps = TripRecorder.hasPermission(this);
        Ui.hint(this, side, "Server: " + (!Uploader.configured() || !Prefs.uploadEnabled(this) ? "oprit"
                : pending == 0 ? "la zi" : pending + " in asteptare")
                + " · Calatorii: " + (!Prefs.tripsEnabled(this) ? "oprite" : gps ? "se inregistreaza" : "fara GPS"));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(side);
        page.addView(sv, wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, -2));

        updateHomeLive();
        updateMapOverlay();
        ui.removeCallbacks(homeTick);
        if (running) ui.postDelayed(homeTick, 2_000);
        if (System.currentTimeMillis() - summaryAt > 15_000) loadSummary();
        if (wide) return page;
        // Pe ecran ingust (telefon), toata pagina se deruleaza.
        ScrollView outer = new ScrollView(this);
        outer.addView(page);
        return outer;
    }

    /** Doar ce cere atentie: actualizarea, mentenanta scadenta, setarile care lipsesc. */
    private void addHomeWarnings(LinearLayout col) {
        org.json.JSONObject apk = VwStatus.newerApk(this);
        if (apk != null) {
            LinearLayout up = Ui.card(this, col);
            up.addView(Ui.text(this, "Versiune noua: " + apk.optString("versionName"), 18, Ui.ACCENT, true));
            updateBusyShown = Updater.busy(this);
            Ui.hint(this, up, updateBusyShown
                    ? "Se instaleaza. Dupa confirmare, Android verifica aplicatia inca ~30 s, fara nimic pe ecran; "
                            + "aplicatia se inchide si porneste singura cu versiunea noua. Nu e nevoie sa apesi din nou."
                    : "Instaleaza cu masina oprita; Android iti cere o confirmare.");
            Button b = Ui.addButton(this, up, updateBusyShown ? "Se instaleaza..." : "Actualizeaza acum", Ui.PRIMARY, v -> {
                v.setEnabled(false);
                ((Button) v).setText("Se descarca...");
                Updater.start(this, error -> {
                    if (error != null) toast("Actualizare esuata: " + error);
                    refresh();
                });
            });
            b.setEnabled(!updateBusyShown);
        }
        java.util.List<VwStatus.Reminder> due = new java.util.ArrayList<>();
        for (VwStatus.Reminder r : VwStatus.reminders(this)) if (r.soon || r.overdue) due.add(r);
        if (!due.isEmpty()) {
            LinearLayout mt = Ui.card(this, col);
            for (VwStatus.Reminder r : due) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(Ui.text(this, r.title, 17, Ui.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(Ui.pill(this, r.describe(), r.overdue ? Ui.BAD : Ui.WARN));
                mt.addView(row);
            }
        }
        if (!batteryOk()) {
            LinearLayout warn = Ui.card(this, col);
            warn.addView(Ui.text(this, "Optimizarea bateriei e activa", 17, Ui.WARN, true));
            Ui.addButton(this, warn, "Dezactiveaza optimizarea", Ui.SECONDARY, v -> askBattery());
        }
        if (Prefs.tripsEnabled(this) && !TripRecorder.hasPermission(this)) {
            LinearLayout warn = Ui.card(this, col);
            warn.addView(Ui.text(this, "Calatoriile nu au GPS", 17, Ui.WARN, true));
            Ui.addButton(this, warn, "Permite localizarea", Ui.PRIMARY, v -> askLocation());
        }
    }

    /**
     * Viteza, starea si bateria. Bateria apare doar cand e in afara limitelor (bordul n-o arata):
     * sub 13,2 V sau peste 15 V cu motorul pornit (incarcarea), sub 11,8 V cu el oprit.
     */
    private void updateHomeLive() {
        if (homeHeadline == null) return;
        CanLink can = CanLink.get(this);
        if (serviceRunning()) homeHeadline.setText(carHeadline());
        double kmh = can.speed(), volt = can.volt();
        homeSpeed.setText(Double.isNaN(kmh) ? "—" : String.valueOf(Math.round(kmh)));
        boolean engine = can.rpm() > 300;
        String warn = null;
        if (!Double.isNaN(volt) && can.dashFresh()) {
            if (engine && volt < 13.2) warn = "nu se incarca";
            else if (engine && volt > 15.0) warn = "tensiune prea mare";
            else if (!engine && volt < 11.8) warn = "descarcata";
        }
        homeBattery.setVisibility(warn == null ? View.GONE : View.VISIBLE);
        if (warn != null) homeBattery.setText(String.format(Locale.US, "⚠ Baterie %.1f V: %s", volt, warn));
    }

    private void loadSummary() {
        if (summaryLoading || !Uploader.configured()) return;
        summaryLoading = true;
        new Thread(() -> {
            org.json.JSONObject s = VwStatus.summary();
            // Harta: doar punctele noi (la o calatorie noua, serverul le trimite pe toate).
            RouteMap m = routeMap;
            org.json.JSONObject route = s == null ? null : VwStatus.route(m == null ? null : m.lastT);
            runOnUiThread(() -> {
                summaryLoading = false;
                summaryAt = System.currentTimeMillis();
                if (s == null) return;
                summary = s;
                // Doar cu Acasa pe ecran: in fundal (onStop) harta nu se reface. Daca harta a
                // fost refacuta intre timp (onStop / onResume), raspunsul are doar punctele de
                // dupa harta veche: il lasam si cerem imediat traseul intreg.
                boolean sameMap = m == null || routeMap == m;
                if (route != null && homeHeadline != null && sameMap) routeMap().update(route);
                if (!sameMap) summaryAt = 0;
                renderHomeTrip();
                updateMapOverlay();
            });
        }).start();
    }

    private RouteMap routeMap() {
        if (routeMap == null) {
            routeMap = new RouteMap(this);
            routeMap.onTouchEnd = this::updateMapOverlay; // apare „Urmareste masina”
        }
        return routeMap;
    }

    private void updateMapOverlay() {
        if (homeMapHint == null || routeMap == null) return;
        homeMapHint.setVisibility(routeMap.hasPoints() ? View.GONE : View.VISIBLE);
        homeFollow.setVisibility(routeMap.follow || !routeMap.hasPoints() ? View.GONE : View.VISIBLE);
    }

    /**
     * Calatoria in curs: costul, cat a consumat si cat a stat in trafic (distanta, durata si
     * consumul mediu le arata bordul); fara una in curs, ultima pe scurt. Apoi ziua de azi.
     */
    private void renderHomeTrip() {
        if (homeTrip == null) return;
        homeTrip.removeAllViews();
        if (summary == null) return;
        org.json.JSONObject trip = summary.optJSONObject("trip");
        if (trip != null) {
            LinearLayout card = Ui.card(this, homeTrip);
            String route = route(trip);
            card.addView(Ui.text(this, "Calatoria in curs · de la " + hm(trip.optString("start"))
                    + (route.isEmpty() ? "" : "\n" + route), 15, Ui.ACCENT, true));
            LinearLayout row = new LinearLayout(this);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = dp(this, 12);
            card.addView(row, rlp);
            stat(row, "Cost", trip.isNull("cost") ? "—"
                    : String.format(Locale.US, "%.2f lei", trip.optDouble("cost")), Ui.TEXT);
            stat(row, "Consumat", trip.isNull("fuelL") ? "—"
                    : String.format(Locale.US, "≈ %.2f L", trip.optDouble("fuelL")), Ui.TEXT);
            Ui.hint(this, card, trip.optDouble("trafficMin") > 0
                    ? "Oprit in trafic " + minutes(trip.optDouble("trafficMin"))
                    + (trip.optInt("trafficStops") > 0 ? " (" + trip.optInt("trafficStops") + " opriri)" : "")
                    : "Fara opriri in trafic");
        } else {
            org.json.JSONObject last = summary.optJSONObject("last");
            if (last != null) {
                LinearLayout card = Ui.card(this, homeTrip);
                String route = route(last);
                card.addView(Ui.text(this, "Ultima calatorie · " + hm(last.optString("start")) + "–"
                        + hm(last.optString("end")), 15, Ui.MUTED, true));
                card.addView(Ui.text(this, (route.isEmpty() ? "" : route + "\n")
                        + String.format(Locale.US, "%.1f km", last.optDouble("distanceKm"))
                        + (last.isNull("cost") ? "" : String.format(Locale.US, " · %.2f lei", last.optDouble("cost"))),
                        16, Ui.TEXT, false));
            }
        }
        org.json.JSONObject today = summary.optJSONObject("today");
        if (today != null && today.optInt("trips") > 0) {
            Ui.hint(this, homeTrip, String.format(Locale.US, "Azi: %d %s · %.1f km%s",
                    today.optInt("trips"), today.optInt("trips") == 1 ? "calatorie" : "calatorii",
                    today.optDouble("km"),
                    today.isNull("cost") ? "" : String.format(Locale.US, " · %.2f lei", today.optDouble("cost"))));
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
        String lastWake = firstLine(Prefs.history(this));
        Ui.hint(this, list, "Pauza " + fmtSec(Prefs.delayMs(this)) + " +" + fmtSec(Prefs.sleepExtraMs(this))
                + " dupa hibernare" + (lastWake == null ? "" : " · Ultima trezire: " + lastWake));
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
        toggle(voice, "Salut vorbit dupa 30 s de mers", "Ora zilei, temperatura de afara, mentenanta scadenta",
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
        Ui.hint(this, sys, "Acces la notificari: " + (KeepAliveListener.granted(this)
                ? "dat (ok): prioritate mai mare, repornire de catre sistem"
                : "NU. Da-l ca Android sa nu ne omoare cand ramane fara memorie (notificarile nu se citesc)"));
        Ui.addButton(this, sys, "Acces la notificari", Ui.SECONDARY, v -> startActivity(
                new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        Ui.hint(this, sys, "Optimizare baterie: " + (batteryOk() ? "dezactivata (ok)" : "ACTIVA"));
        Ui.addButton(this, sys, "Optimizarea bateriei", Ui.SECONDARY, v -> askBattery());
        toggle(sys, "Comenzi de la distanta (teste)", "Lasa serverul sa ruleze comenzi pe navigatie, "
                + "fara root, pentru diagnostic. Porneste-l doar cand facem teste; fiecare comanda apare in jurnal",
                Prefs.remoteCmdEnabled(this), on -> {
                    Prefs.setRemoteCmdEnabled(this, on);
                    Prefs.log(this, "Comenzi de la distanta: " + (on ? "pornite" : "oprite"));
                });
        Ui.addButton(this, sys, "Test Bluetooth (adaptor OBD)", Ui.SECONDARY, v -> {
            toast("Caut dispozitive Bluetooth, ~25 s; rezultatul apare in Jurnal");
            new Thread(() -> {
                Prefs.log(this, BleProbe.run(this));
                runOnUiThread(this::refresh);
            }).start();
        });
        // Pe drumuri cu 4G slab, modemul coboara pe 2G („G”), unde nu se incarca nimic. Cu „LTE only”
        // ramane pe 4G. Setarile Teyes nu au mereu optiunea; ecranul ascuns „Informatii telefon” o are.
        Ui.hint(this, sys, "Retea mobila, tipul preferat: " + networkMode()
                + ". Pentru internet fara caderi pe 2G alege „LTE only” in ecranul de mai jos");
        Ui.addButton(this, sys, "Informatii telefon (tipul de retea)", Ui.SECONDARY, v -> {
            try {
                startActivity(new Intent().setClassName("com.android.settings", "com.android.settings.RadioInfo"));
            } catch (RuntimeException e) {
                toast("Ecranul nu se poate deschide: " + e.getMessage());
            }
        });
        Ui.addButton(this, sys, "Porneste serviciul", Ui.SECONDARY, v -> {
            WelcomeService.start(this);
            toast("Serviciul ruleaza");
            ui.postDelayed(this::refresh, 800);
        });

        buildProbe(col);
    }

    /** Tipul de retea preferat al modemului (Settings.Global, valorile RILConstants). */
    private String networkMode() {
        String raw = null;
        for (String key : new String[] {"preferred_network_mode", "preferred_network_mode1"}) {
            String v = android.provider.Settings.Global.getString(getContentResolver(), key);
            if (v != null && !v.isEmpty()) {
                raw = v.split(",")[0].trim();
                break;
            }
        }
        if (raw == null) return "necunoscut";
        switch (raw) {
            case "0": return "3G/2G (" + raw + ")";
            case "1": return "doar 2G (" + raw + ")";
            case "3": return "2G/3G (" + raw + ")";
            case "9": return "4G/3G/2G automat (" + raw + ")";
            case "11": return "doar 4G, LTE only (" + raw + ")";
            case "12": return "4G/3G (" + raw + ")";
            case "26": case "33": return "5G/4G/3G/2G automat (" + raw + ")";
            default: return "cod " + raw;
        }
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
        if (routeMap != null) routeMap.map.onPause();
    }

    /**
     * Cat aplicatia nu e in prim-plan, harta nu tine memorie si nu descarca nimic: o eliberam si
     * o refacem la revenire. In fundal raman doar serviciul (datele masinii, drumurile, serverul).
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (routeMap != null) {
            if (routeMap.map.getParent() != null) ((android.view.ViewGroup) routeMap.map.getParent()).removeView(routeMap.map);
            routeMap.map.onDetach();
            routeMap = null;
            // osmdroid pastreaza imaginile dalelor intr-un depozit global, pentru refolosire, si
            // dupa onDetach. 20.10: navigatia ramasese fara memorie (swap plin), iar noi eram al
            // treilea proces ca marime si neprotejat (5 in skipkillapp.prop) -> omorati la somn.
            org.osmdroid.tileprovider.BitmapPool.getInstance().clearBitmapPool();
        }
        homeHeadline = null;
        summaryAt = 0; // la revenire, rezumatul si traseul se cer imediat
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (routeMap != null) routeMap.map.onResume();
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
