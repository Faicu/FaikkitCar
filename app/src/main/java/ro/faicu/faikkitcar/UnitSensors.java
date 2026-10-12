package ro.faicu.faikkitcar;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.BatteryManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Ce temperaturi poate citi navigatia singura (12.10): decodorul CAN nu trimite temperatura din
 * habitaclu, asa ca vedem daca unitatea are un senzor de aer, ce arata „bateria” si zonele
 * termice ale procesorului. O linie in jurnal la pornirea serviciului, pentru comparat cu
 * temperatura de afara si cu cea simtita in masina.
 */
final class UnitSensors {
    private UnitSensors() {}

    static String describe(Context c) {
        StringBuilder sb = new StringBuilder("Temperaturi unitate: senzori aer: ");
        SensorManager sm = c.getSystemService(SensorManager.class);
        StringBuilder air = new StringBuilder();
        if (sm != null) {
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
                if (s.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE || s.getStringType().toLowerCase(Locale.US).contains("temp")) {
                    air.append(air.length() == 0 ? "" : ", ").append(s.getName()).append(" (").append(s.getStringType()).append(")");
                }
            }
        }
        sb.append(air.length() == 0 ? "niciunul" : air);
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int bt = b == null ? Integer.MIN_VALUE : b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        sb.append(" | baterie: ").append(bt == Integer.MIN_VALUE ? "—" : String.format(Locale.US, "%.1f", bt / 10.0));
        sb.append(" | zone termice:");
        File[] zones = new File("/sys/class/thermal").listFiles((d, n) -> n.startsWith("thermal_zone"));
        int found = 0;
        if (zones != null) {
            for (File z : zones) {
                String type = read(new File(z, "type"));
                String temp = read(new File(z, "temp"));
                if (type == null || temp == null) continue;
                try {
                    double v = Double.parseDouble(temp);
                    if (Math.abs(v) > 1000) v /= 1000; // de obicei in miligrade
                    sb.append(' ').append(type).append('=').append(String.format(Locale.US, "%.1f", v));
                    found++;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (found == 0) sb.append(" niciuna citibila");
        return sb.toString();
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }
}
