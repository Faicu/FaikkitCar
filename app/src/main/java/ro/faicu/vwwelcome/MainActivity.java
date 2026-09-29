package ro.faicu.vwwelcome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int PICK_SOUND = 1;

    private TextView status;
    private EditText threshold;

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

        l.addView(button("1. Alege sunetul (MP3)", v -> pickSound()));

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
        l.addView(button("Test sunet", v -> Player.test(this)));

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
        status.setText(
                "Sunet: " + (Prefs.soundFile(this).exists() ? "ales" : "NEALES") + "\n"
                + "Optimizare baterie: " + (batteryOk ? "dezactivata (ok)" : "ACTIVA") + "\n"
                + "Prag: " + Prefs.thresholdSec(this) + " s\n"
                + "Ultima trezire detectata: " + Prefs.lastWake(this) + "\n");
    }

    private void pickSound() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, PICK_SOUND);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_SOUND || res != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream in = getContentResolver().openInputStream(data.getData());
             OutputStream out = new FileOutputStream(Prefs.soundFile(this))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            toast("Sunet salvat");
        } catch (Exception e) {
            toast("Eroare la copiere: " + e.getMessage());
        }
        refresh();
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
