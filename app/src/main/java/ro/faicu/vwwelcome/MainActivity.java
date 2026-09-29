package ro.faicu.vwwelcome;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int PICK_SOUND = 1;

    private TextView status;
    private LinearLayout soundList;
    private Button order;
    private EditText threshold;
    private TextView history;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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

        threshold = new EditText(this);
        threshold.setInputType(InputType.TYPE_CLASS_NUMBER);
        threshold.setHint("Secunde minime cu contactul luat (min. 30)");
        threshold.setText(String.valueOf(Prefs.thresholdSec(this)));
        l.addView(threshold);
        l.addView(button("2. Salveaza pragul", v -> saveThreshold()));

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
                + "Prag: " + Prefs.thresholdSec(this) + " s\n");

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
    }

    private void pickSound() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_SOUND);
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

    private void saveThreshold() {
        try {
            Prefs.setThresholdSec(this, Integer.parseInt(threshold.getText().toString().trim()));
            toast("Salvat");
        } catch (NumberFormatException e) {
            toast("Numar invalid");
        }
        threshold.setText(String.valueOf(Prefs.thresholdSec(this)));
        refresh();
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
