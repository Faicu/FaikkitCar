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
    // Pasii calibrarii CAN: fiecare "Gata" lasa un marcaj intre liniile sondei, ca sa leg
    // codurile de actiuni. In pereche (fa / anuleaza), ca schimbarea sa se vada in ambele sensuri.
    private static final String[] CALIBRATION = {
            "Inchide toate usile, portbagajul si capota; motorul pornit, pe loc",
            "Deschide usa soferului", "Inchide usa soferului",
            "Deschide usa pasagerului din fata", "Inchide usa pasagerului din fata",
            "Deschide usa din spate stanga", "Inchide usa din spate stanga",
            "Deschide usa din spate dreapta", "Inchide usa din spate dreapta",
            "Deschide portbagajul", "Inchide portbagajul",
            "Elibereaza frana de mana (cu piciorul pe frana)", "Trage frana de mana",
            "Aprinde faza scurta", "Stinge faza scurta",
            "Aprinde faza lunga", "Stinge faza lunga",
            "Semnalizare stanga pornita", "Semnalizare stanga oprita",
            "Semnalizare dreapta pornita", "Semnalizare dreapta oprita",
            "Avariile pornite", "Avariile oprite",
            "Cupleaza marsarierul (cu frana apasata)", "Scoate marsarierul",
            "Desfa centura soferului", "Pune centura soferului",
    };
    private int calibStep = -1;
    private static final String[] TABS = {"Acasa", "Sunete", "Setari", "Jurnal"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView[] tabViews = new TextView[TABS.length];
    private int tab;
    private FrameLayout content;
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
        showTab(0);
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        row.addView(icon, new LinearLayout.LayoutParams(dp(this, 56), dp(this, 56)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(this, 14), 0, 0, 0);
        texts.addView(Ui.text(this, "VW Welcome", 26, Ui.TEXT, true));
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
            t.setOnClickListener(v -> showTab(index));
            tabViews[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 18);
        bar.setLayoutParams(lp);
        return bar;
    }

    private void showTab(int index) {
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
        updateStatus();
    }

    private void refresh() {
        showTab(tab);
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

    private void buildHome(LinearLayout col) {
        LinearLayout hero = Ui.card(this, col);
        boolean running = serviceRunning();
        File[] sounds = Prefs.sounds(this);
        String headline = !running ? "Serviciul e oprit"
                : sounds.length == 0 ? "Alege un sunet" : "Gata de drum";
        hero.addView(Ui.text(this, headline, 28, Ui.TEXT, true));
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

        if (!batteryOk()) {
            LinearLayout warn = Ui.card(this, col);
            warn.addView(Ui.text(this, "Optimizarea bateriei e activa", 18, Ui.WARN, true));
            Ui.hint(this, warn, "Android poate opri serviciul. Dezactiveaz-o pentru VW Welcome.");
            Ui.addButton(this, warn, "Dezactiveaza optimizarea", Ui.SECONDARY, v -> askBattery());
        }

        LinearLayout can = Ui.card(this, col);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Sonda CAN", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        long until = Prefs.canProbeUntil(this);
        boolean active = until > System.currentTimeMillis();
        head.addView(Ui.pill(this, active ? "activa pana la " + hhmm(until) : "oprita",
                active ? Ui.ACCENT : Ui.MUTED));
        can.addView(head);
        Ui.hint(this, can, "Asculta ce date primeste navigatia de la masina (viteza, turatie, "
                + "temperatura, usi...) si le trimite la server. Porneste-o si, in 5 minute, fa cat mai "
                + "multe actiuni: pornire motor, accelerari, frana, usi, lumini, semnalizare.");
        Ui.addButton(this, can, active ? "Opreste sonda" : "Porneste pentru 5 minute",
                active ? Ui.DANGER : Ui.SECONDARY, v -> {
                    Prefs.setCanProbeUntil(this, active ? 0 : System.currentTimeMillis() + CanProbe.DURATION_MS);
                    CanProbe.check(getApplicationContext());
                    toast(active ? "Sonda CAN oprita" : "Sonda CAN pornita");
                    refresh();
                });

        buildCalibration(col);
        buildTrips(col);
    }

    private void buildCalibration(LinearLayout col) {
        LinearLayout card = Ui.card(this, col);
        Ui.title(this, card, "Calibrare CAN");
        if (calibStep < 0) {
            Ui.hint(this, card, "Cu masina pe loc si motorul pornit: aplicatia iti cere pe rand o "
                    + "actiune (usi, frana de mana, lumini...), tu o faci si apesi Gata. Dureaza 2-3 minute.");
            Ui.addButton(this, card, "Incepe calibrarea", Ui.SECONDARY, v -> {
                Prefs.setCanProbeUntil(this, System.currentTimeMillis() + CanProbe.DURATION_MS);
                CanProbe.check(getApplicationContext());
                calibStep = 0;
                // Sonda are nevoie de o clipa sa se lege la MainServer inainte de primul marcaj.
                ui.postDelayed(() -> CanProbe.mark("start calibrare"), 1500);
                refresh();
            });
            return;
        }
        Ui.hint(this, card, "Pasul " + (calibStep + 1) + " din " + CALIBRATION.length);
        TextView step = Ui.text(this, CALIBRATION[calibStep], 22, Ui.TEXT, true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(this, 10);
        card.addView(step, slp);
        LinearLayout btns = new LinearLayout(this);
        btns.addView(Ui.button(this, "Gata", Ui.PRIMARY, v -> calibNext(true)),
                new LinearLayout.LayoutParams(0, -2, 2));
        LinearLayout.LayoutParams sk = new LinearLayout.LayoutParams(0, -2, 1);
        sk.leftMargin = dp(this, 10);
        btns.addView(Ui.button(this, "Sari peste", Ui.SECONDARY, v -> calibNext(false)), sk);
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

    private void calibNext(boolean done) {
        CanProbe.mark((calibStep + 1) + " " + (done ? "GATA" : "SARIT") + ": " + CALIBRATION[calibStep]);
        calibStep++;
        if (calibStep >= CALIBRATION.length) {
            CanProbe.mark("calibrare terminata");
            calibStep = -1;
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
                + "status.faicu.ro/calatorii. Puncte in asteptare: " + PointQueue.size(this));
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
                : "status.faicu.ro · in asteptare: " + Prefs.outboxSize(this)
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
        ttexts.addView(Ui.text(this, "GPS + date de la masina, la status.faicu.ro/calatorii", 13,
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

        LinearLayout sys = Ui.card(this, col);
        Ui.title(this, sys, "Sistem");
        Ui.hint(this, sys, "Optimizare baterie: " + (batteryOk() ? "dezactivata (ok)" : "ACTIVA"));
        Ui.addButton(this, sys, "Optimizarea bateriei", Ui.SECONDARY, v -> askBattery());
        Ui.addButton(this, sys, "Porneste serviciul", Ui.SECONDARY, v -> {
            WelcomeService.start(this);
            toast("Serviciul ruleaza");
            ui.postDelayed(this::refresh, 800);
        });
        Ui.addButton(this, sys, "Diagnostic Teyes (trimite la server)", Ui.SECONDARY, v -> runDiagnostics());
    }

    private void runDiagnostics() {
        if (!Uploader.configured() || !Prefs.uploadEnabled(this)) {
            toast("Trimiterea la server e oprita");
            return;
        }
        toast("Diagnostic pornit, dureaza cateva secunde...");
        new Thread(() -> {
            String msg;
            try {
                msg = "Diagnostic trimis: " + Diagnostics.run(this) + " linii";
            } catch (Exception e) {
                msg = "Diagnostic: eroare " + e;
            }
            Prefs.log(this, msg);
            String m = msg;
            runOnUiThread(() -> {
                toast(m);
                refresh();
            });
        }).start();
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
        Ui.hint(this, log, "Ultimele 100 de evenimente. Jurnalul complet e pe status.faicu.ro.");
        String l = Prefs.logText(this);
        TextView lt = Ui.mono(this, l.isEmpty() ? "gol" : l, 12);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(this, 12);
        log.addView(lt, llp);
        LinearLayout btns = new LinearLayout(this);
        btns.addView(Ui.button(this, "Copiaza", Ui.SECONDARY, v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(
                    "VW Welcome", Prefs.deviceInfo(this) + "\n\nIstoric:\n" + Prefs.history(this)
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

    private static String hhmm(long t) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t));
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
    protected void onResume() {
        super.onResume();
        refresh();
        // Serviciul pornit in onCreate apare in lista abia dupa cateva sute de ms.
        ui.postDelayed(this::updateStatus, 1000);
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
                toast("Seteaza manual din Setari > Aplicatii > VW Welcome > Baterie");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
