package ro.faicu.vwwelcome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK_SOUND = 1;
    private static final int PERM_AUDIO = 3;

    private TextView status;
    private LinearLayout soundList;
    private Button order;
    private EditText threshold;
    private EditText delay;
    private EditText sleepExtra;
    private TextView volumeLabel;
    private TextView history;
    private TextView log;
    private Button upload;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);
        // Si daca aplicatia e deschisa din autostart, serviciul trebuie sa porneasca.
        // Marcam deschiderea, ca serviciul sa nu o ia drept trezire si sa redea sunetul.
        Prefs.markUiStart(this);
        WelcomeService.start(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(18);
        l.addView(status);

        soundList = new LinearLayout(this);
        soundList.setOrientation(LinearLayout.VERTICAL);
        l.addView(soundList);
        l.addView(button("1. Adauga sunete (MP3)", v -> pickSound()));
        order = button("", v -> {
            Prefs.setRandomOrder(this, !Prefs.randomOrder(this));
            refresh();
        });
        l.addView(order);

        l.addView(label("Prag: secunde minime de hibernare (min. 30)"));
        threshold = new EditText(this);
        threshold.setInputType(InputType.TYPE_CLASS_NUMBER);
        l.addView(threshold);
        l.addView(label("Pauza inainte de redare, in secunde (0-15)"));
        delay = new EditText(this);
        delay.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        l.addView(delay);
        l.addView(label("Pauza suplimentara dupa hibernare, in secunde (0-15)"));
        sleepExtra = new EditText(this);
        sleepExtra.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        l.addView(sleepExtra);
        l.addView(button("2. Salveaza pragul si pauza", v -> saveSettings()));
        fillSettings();

        volumeLabel = label("");
        l.addView(volumeLabel);
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(Prefs.volumePercent(this));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) Prefs.setVolumePercent(MainActivity.this, p);
                showVolume();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        l.addView(volume);
        showVolume();

        l.addView(button("3. Porneste serviciul", v -> {
            WelcomeService.start(this);
            toast("Serviciul ruleaza");
        }));
        l.addView(button("4. Dezactiveaza optimizarea bateriei", v -> askBattery()));
        l.addView(button("Test sunet (urmatorul din lista)", v -> {
            File f = Player.test(this);
            toast(f == null ? "Niciun sunet ales" : "Redau: " + Prefs.soundName(f));
        }));

        TextView h = new TextView(this);
        h.setText("\nIstoric treziri (ultimele 20):");
        h.setTextSize(18);
        l.addView(h);
        history = new TextView(this);
        history.setTextIsSelectable(true);
        l.addView(history);
        l.addView(button("Sterge istoricul", v -> {
            Prefs.clearHistory(this);
            refresh();
        }));

        TextView lh = label("\nJurnal diagnostic (ultimele 100):");
        lh.setTextSize(18);
        l.addView(lh);
        log = new TextView(this);
        log.setTextIsSelectable(true);
        log.setTextSize(12);
        l.addView(log);
        upload = button("", v -> {
            Prefs.setUploadEnabled(this, !Prefs.uploadEnabled(this));
            Uploader.kick(this);
            refresh();
        });
        l.addView(upload);
        l.addView(button("Diagnostic Teyes (trimite la server)", v -> {
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
        }));
        l.addView(button("Copiaza jurnalul", v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(
                    "VW Welcome", Prefs.deviceInfo(this) + "\n\nIstoric:\n" + Prefs.history(this)
                            + "\n\nJurnal:\n" + Prefs.logText(this)));
            toast("Jurnal copiat in clipboard");
        }));
        l.addView(button("Sterge jurnalul", v -> {
            Prefs.clearLog(this);
            refresh();
        }));

        ScrollView s = new ScrollView(this);
        s.addView(l);
        setContentView(s);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Android 13+: fara permisiune, notificarea serviciului nu apare (serviciul merge oricum).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 2);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        return t;
    }

    private void showVolume() {
        int v = Prefs.volumePercent(this);
        volumeLabel.setText("Volum sunet de bun venit: "
                + (v == 0 ? "volumul curent al sistemului" : v + "%"));
    }

    private Button button(String text, View.OnClickListener click) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setOnClickListener(click);
        return bt;
    }

    private void refresh() {
        PowerManager pm = getSystemService(PowerManager.class);
        boolean batteryOk = pm.isIgnoringBatteryOptimizations(getPackageName());
        File[] sounds = Prefs.sounds(this);
        status.setText(
                "Sunete: " + (sounds.length == 0 ? "NICIUNUL" : sounds.length) + "\n"
                + "Optimizare baterie: " + (batteryOk ? "dezactivata (ok)" : "ACTIVA") + "\n"
                + "Prag: " + Prefs.thresholdSec(this) + " s, pauza "
                + Prefs.delayMs(this) / 1000.0 + " s (+" + Prefs.sleepExtraMs(this) / 1000.0
                + " s dupa hibernare)\n");

        soundList.removeAllViews();
        for (File f : sounds) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText("♪ " + Prefs.soundName(f));
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(button("Sterge", v -> {
                f.delete();
                refresh();
            }));
            soundList.addView(row);
        }
        order.setText("Ordine: " + (Prefs.randomOrder(this) ? "aleatorie" : "la rand")
                + " (apasa pentru schimbare)");

        String hist = Prefs.history(this);
        history.setText(hist.isEmpty() ? "nicio trezire inca" : hist);
        String logs = Prefs.logText(this);
        log.setText(logs.isEmpty() ? "gol" : logs);
        if (!Uploader.configured()) {
            upload.setText("Trimitere la server: indisponibila in acest build");
            upload.setEnabled(false);
        } else {
            String st = Prefs.uploadStatus(this);
            upload.setText("Trimite jurnalul la server: " + (Prefs.uploadEnabled(this) ? "DA" : "NU")
                    + "\nIn asteptare: " + Prefs.outboxSize(this)
                    + (st.isEmpty() ? "" : ", ultima: " + st));
        }
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
        new AlertDialog.Builder(this)
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

    private void saveSettings() {
        try {
            Prefs.setThresholdSec(this, Integer.parseInt(threshold.getText().toString().trim()));
            double sec = Double.parseDouble(delay.getText().toString().trim().replace(',', '.'));
            Prefs.setDelayMs(this, Math.round(sec * 1000));
            double extra = Double.parseDouble(sleepExtra.getText().toString().trim().replace(',', '.'));
            Prefs.setSleepExtraMs(this, Math.round(extra * 1000));
            toast("Salvat");
        } catch (NumberFormatException e) {
            toast("Numar invalid");
        }
        fillSettings();
        refresh();
    }

    private void fillSettings() {
        threshold.setText(String.valueOf(Prefs.thresholdSec(this)));
        delay.setText(String.valueOf(Prefs.delayMs(this) / 1000.0));
        sleepExtra.setText(String.valueOf(Prefs.sleepExtraMs(this) / 1000.0));
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
