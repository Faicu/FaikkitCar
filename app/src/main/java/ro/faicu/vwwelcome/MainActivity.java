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
    /** Un pas al calibrarii: ce faci si o precizare. */
    private static final class Step {
        final String title, hint;

        Step(String title, String hint) {
            this.title = title;
            this.hint = hint;
        }
    }

    // Doar ce a ramas nesigur (vezi CLAUDE.md): c107, care a sarit la eliberarea franei de mana
    // si n-a mai revenit, si marsarierul, care nu are un cod curat. Restul e confirmat.
    private static final Step[] STEPS = {
            new Step("Pregatire", "Masina parcata, motorul PORNIT, frana de mana trasa, schimbatorul "
                    + "in punctul mort (neutru). Apasa Gata."),
            new Step("Apasa pedala de frana si elibereaza frana de mana",
                    "Tine piciorul pe frana tot timpul; masina sta in neutru."),
            new Step("Trage frana de mana la loc", "Apoi poti lua piciorul de pe frana."),
            new Step("Baga marsarierul", "Ambreiajul apasat, piciorul pe frana, frana de mana trasa. "
                    + "Asteapta sa porneasca camera/radarul, apoi Gata."),
            new Step("Scoate marsarierul (neutru)", "Asteapta 2-3 secunde, apoi Gata."),
    };
    // Coduri deja stabilite sau care se schimba singure; nu le aratam in timpul calibrarii.
    private static final java.util.Set<String> KNOWN = new java.util.HashSet<>(java.util.Arrays.asList(
            "m7 c110", "m7 c1032", "m7 c109", "m7 c1031", "m7 c1033", "m7 c105", "m7 c1049", "m7 c106",
            "m7 c139", "m7 c104", "m7 c1", "m7 c2", "m7 c3", "m7 c4", "m7 c5", "m7 raw 0x7d", "m7 raw 0x41/2",
            "m7 c101", "m7 c103", "m7 c21", "m7 c27", "m7 c28", "m7 c1019",
            "m7 c10", "m7 c11", "m7 c13", "m7 c49", "m7 raw 0x21", "m7 raw 0x14",
            "m1 c*", "m4 c*", "m8 c*",
            "m0 c41", "m0 c114", "m0 c115", "m0 c146", "m0 c179", "m0 c101", "m0 c40", "m0 c77"));
    private int calibStep = -1;
    private long stepStart;
    private final List<String> calibResults = new ArrayList<>();
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

    // Titlul din Acasa urmeaza starea masinii (ca fila „Acum” din Panel), la 2 s.
    private TextView homeHeadline;
    private final Runnable homeTick = new Runnable() {
        @Override
        public void run() {
            if (tab != 0 || homeHeadline == null) return;
            homeHeadline.setText(carHeadline());
            ui.postDelayed(this, 2_000);
        }
    };

    /** „In mers · 34 km/h”, „Motor pornit”, „Contact pus” sau „Gata de drum”. */
    private String carHeadline() {
        CanLink can = CanLink.get(this);
        double kmh = can.speed();
        if (!Double.isNaN(kmh) && kmh >= 1) return "In mers · " + Math.round(kmh) + " km/h";
        if (can.rpm() > 300) return "Motor pornit";
        if (can.dashFresh()) return "Contact pus";
        return "Gata de drum";
    }

    private void buildHome(LinearLayout col) {
        LinearLayout hero = Ui.card(this, col);
        boolean running = serviceRunning();
        File[] sounds = Prefs.sounds(this);
        String headline = !running ? "Serviciul e oprit"
                : sounds.length == 0 ? "Alege un sunet" : carHeadline();
        homeHeadline = Ui.text(this, headline, 28, Ui.TEXT, true);
        hero.addView(homeHeadline);
        ui.removeCallbacks(homeTick);
        if (running && sounds.length > 0) ui.postDelayed(homeTick, 2_000);
        String last = firstLine(Prefs.history(this));
        Ui.hint(this, hero, last == null ? "Nicio trezire inregistrata inca."
                : "Ultima trezire: " + last);

        LinearLayout stats = new LinearLayout(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(this, 18);
        hero.addView(stats, slp);
        stat(stats, "Sunete", String.valueOf(sounds.length), sounds.length > 0 ? Ui.TEXT : Ui.WARN);
        stat(stats, "Pauza", fmtSec(Prefs.delayMs(this)) + " +" + fmtSec(Prefs.sleepExtraMs(this)), Ui.TEXT);
        int pending = Prefs.outboxSize(this);
        stat(stats, "Server", !Uploader.configured() || !Prefs.uploadEnabled(this) ? "oprit"
                : pending == 0 ? "la zi" : pending + " in coada", pending > 50 ? Ui.WARN : Ui.TEXT);

        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(this, 18);
        hero.addView(actions, alp);
        Button play = Ui.button(this, "▶  Reda acum", Ui.PRIMARY, v -> {
            File f = Player.test(this);
            toast(f == null ? "Niciun sunet ales" : "Redau: " + Prefs.soundName(f));
        });
        actions.addView(play, new LinearLayout.LayoutParams(0, -2, 1));
        if (!running) {
            Button start = Ui.button(this, "Porneste serviciul", Ui.SECONDARY, v -> {
                WelcomeService.start(this);
                ui.postDelayed(this::refresh, 800);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = dp(this, 12);
            actions.addView(start, lp);
        }

        org.json.JSONObject apk = VwStatus.newerApk(this);
        if (apk != null) {
            LinearLayout up = Ui.card(this, col);
            up.addView(Ui.text(this, "Versiune noua: " + apk.optString("versionName"), 19, Ui.ACCENT, true));
            Ui.hint(this, up, "Se descarca de pe car.faicu.ro si se instaleaza peste aceasta; "
                    + "Android iti cere o confirmare.");
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
            Ui.hint(this, mt, "Se editeaza pe car.faicu.ro.");
        }

        if (!batteryOk()) {
            LinearLayout warn = Ui.card(this, col);
            warn.addView(Ui.text(this, "Optimizarea bateriei e activa", 18, Ui.WARN, true));
            Ui.hint(this, warn, "Android poate opri serviciul. Dezactiveaz-o pentru FaikkitCar.");
            Ui.addButton(this, warn, "Dezactiveaza optimizarea", Ui.SECONDARY, v -> askBattery());
        }

        buildTrips(col);
    }

    private void buildCalibration(LinearLayout col) {
        LinearLayout card = Ui.card(this, col);
        Ui.title(this, card, "Calibrare CAN (avansat)");
        if (calibStep < 0) {
            Ui.hint(this, card, "Doar ce a ramas nesigur: un cod legat de frana de mana (c107) si "
                    + "marsarierul. Tot restul e confirmat; nu e nevoie s-o faci. Masina parcata, "
                    + "motorul pornit; ~1 minut.");
            if (!calibResults.isEmpty()) {
                TextView res = Ui.mono(this, String.join("\n", calibResults), 13);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = dp(this, 12);
                card.addView(res, rlp);
            }
            Ui.addButton(this, card, calibResults.isEmpty() ? "Incepe calibrarea" : "Reia calibrarea",
                    Ui.SECONDARY, v -> startCalibration(0, "start calibrare (doar necunoscutele)"));
            return;
        }
        Step step = STEPS[calibStep];
        Ui.hint(this, card, "Pasul " + (calibStep + 1) + " din " + STEPS.length);
        TextView title = Ui.text(this, step.title, 22, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(this, 8);
        card.addView(title, tlp);
        if (!step.hint.isEmpty()) Ui.hint(this, card, step.hint);

        {
            // Ce s-a schimbat de cand a aparut pasul, actualizat live de liveTick.
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(Ui.round(this, Ui.CARD2, 12));
            int p = dp(this, 12);
            box.setPadding(p, p, p, p);
            box.addView(Ui.text(this, "Schimbari vazute la acest pas", 13, Ui.MUTED, false));
            liveChanges = Ui.mono(this, "", 14);
            box.addView(liveChanges);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = dp(this, 12);
            card.addView(box, blp);
            updateLive();
        }

        LinearLayout btns = new LinearLayout(this);
        btns.addView(Ui.button(this, "Gata", Ui.PRIMARY, v -> calibNext(true)),
                new LinearLayout.LayoutParams(0, -2, 2));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
        rp.leftMargin = dp(this, 10);
        btns.addView(Ui.button(this, "Repeta", Ui.SECONDARY, v -> {
            stepStart = System.currentTimeMillis();
            CanProbe.mark((calibStep + 1) + " REIA: " + step.title);
            updateLive();
        }), rp);
        LinearLayout.LayoutParams sk = new LinearLayout.LayoutParams(0, -2, 1);
        sk.leftMargin = dp(this, 10);
        btns.addView(Ui.button(this, "Sari", Ui.SECONDARY, v -> calibNext(false)), sk);
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(0, -2, 1);
        st.leftMargin = dp(this, 10);
        btns.addView(Ui.button(this, "Opreste", Ui.DANGER, v -> {
            CanProbe.mark("calibrare oprita la pasul " + (calibStep + 1));
            calibStep = -1;
            refresh();
        }), st);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(this, 16);
        card.addView(btns, blp);
    }

    private void startCalibration(int step, String mark) {
        Prefs.setCanProbeUntil(this, System.currentTimeMillis() + 10 * 60_000L);
        CanProbe.check(getApplicationContext());
        calibResults.clear();
        calibStep = step;
        stepStart = System.currentTimeMillis();
        // Sonda are nevoie de o clipa sa se lege la MainServer inainte de marcaj.
        ui.postDelayed(() -> CanProbe.mark(mark), 1500);
        refresh();
    }

    /** Schimbarile pasului curent, cate una pe cod (ultima valoare), fara codurile stiute. */
    private String stepChanges() {
        java.util.LinkedHashMap<String, CanProbe.Change> byKey = new java.util.LinkedHashMap<>();
        for (CanProbe.Change ch : CanProbe.changesSince(stepStart, KNOWN)) {
            CanProbe.Change first = byKey.get(ch.key);
            // Pastram valoarea de dinainte de primul salt si pe cea de acum.
            byKey.put(ch.key, first == null ? ch : new CanProbe.Change(ch.t, ch.key, first.from, ch.to));
        }
        List<String> out = new ArrayList<>();
        for (CanProbe.Change ch : byKey.values()) out.add(ch.toString());
        return String.join("\n", out);
    }

    private final Runnable liveTick = this::updateLive;

    private void updateLive() {
        ui.removeCallbacks(liveTick);
        if (calibStep < 0 || liveChanges == null) return;
        String changes = stepChanges();
        liveChanges.setText(!CanProbe.isRunning() ? "sonda porneste..."
                : changes.isEmpty() ? "inca nimic — fa actiunea" : changes);
        ui.postDelayed(liveTick, 700);
    }

    private void calibNext(boolean done) {
        Step step = STEPS[calibStep];
        String found = done ? stepChanges().replace("\n", "; ") : "sarit";
        if (found.isEmpty()) found = "nicio schimbare";
        CanProbe.mark((calibStep + 1) + " " + (done ? "GATA" : "SARIT") + ": " + step.title + " | " + found);
        if (calibStep > 0) calibResults.add((calibStep + 1) + ". " + step.title + ": " + found);
        calibStep++;
        stepStart = System.currentTimeMillis();
        if (calibStep >= STEPS.length) {
            CanProbe.mark("calibrare terminata");
            calibStep = -1;
            liveChanges = null;
            toast("Calibrare terminata, multumesc!");
        }
        refresh();
    }

    private void buildTrips(LinearLayout col) {
        LinearLayout card = Ui.card(this, col);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Calatorii", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        boolean on = Prefs.tripsEnabled(this);
        boolean gps = TripRecorder.hasPermission(this);
        head.addView(Ui.pill(this, !on ? "oprite" : gps ? "se inregistreaza" : "fara GPS",
                !on ? Ui.MUTED : gps ? Ui.OK : Ui.WARN));
        card.addView(head);
        Ui.hint(this, card, "Traseul, viteza, turatia si kilometrajul fiecarui drum, pe "
                + "car.faicu.ro. Puncte in asteptare: " + PointQueue.size(this));
        if (on && !gps) {
            Ui.addButton(this, card, "Permite localizarea", Ui.PRIMARY, v -> askLocation());
        }
    }

    private void askLocation() {
        requestPermissions(new String[] {android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION}, PERM_LOCATION);
    }

    private void stat(LinearLayout parent, String label, String value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, Ui.CARD2, 14));
        int p = dp(this, 14);
        box.setPadding(p, p, p, p);
        box.addView(Ui.text(this, label, 13, Ui.MUTED, false));
        box.addView(Ui.text(this, value, 20, color, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() > 0) lp.leftMargin = dp(this, 10);
        parent.addView(box, lp);
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

        buildCalibration(col);
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
