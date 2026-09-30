package ro.faicu.vwwelcome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Inregistreaza calatoriile: pozitia GPS (Android) plus datele masinii (CanLink), intr-un
 * punct la 5 s cand masina merge si la 30 s cand motorul merge pe loc; cu motorul oprit si
 * masina pe loc, nimic. Punctele merg in PointQueue si de acolo la status.faicu.ro/calatorii,
 * unde se impart in calatorii la pauzele de peste 5 minute.
 */
final class TripRecorder implements LocationListener {
    private static final long SAMPLE_MS = 5_000;
    private static final long IDLE_MS = 30_000;
    private static final long LOCATION_FRESH_MS = 10_000;
    // Salutul vorbit vine dupa atatea minute de mers efectiv de la pornire.
    private static final long GREET_AFTER_MS = 3 * 60_000;
    // Cu motorul oprit atat timp, oprim GPS-ul (navigatia sta treaza ~10 min dupa ACC OFF).
    private static final long GPS_OFF_AFTER_MS = 60_000;
    private static final double DOOR_ALERT_KMH = 5;

    private static TripRecorder instance;

    private final Context c;
    private final Handler handler;
    private final CanLink can;
    private volatile Location last;
    private volatile long lastAt;
    private long lastPointAt;
    private boolean wasMoving;
    private boolean gpsOn;
    // GPS-ul n-a putut porni fiindca lipsea permisiunea; check() il porneste cand apare.
    private volatile boolean needsPermission;
    // Starea drumului curent: resetata cand motorul sta oprit sau unitatea a dormit.
    private long lastSampleAt;
    private long movingMs;
    private long engineOffSince;
    private boolean greeted;
    private boolean doorAlerted;

    private TripRecorder(Context c) {
        this.c = c.getApplicationContext();
        HandlerThread t = new HandlerThread("TripRecorder");
        t.start();
        handler = new Handler(t.getLooper());
        can = CanLink.get(c);
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Porneste / opreste inregistrarea dupa setare; apelat la pornirea serviciului si din tick. */
    static synchronized void check(Context c) {
        boolean on = Prefs.tripsEnabled(c);
        if (on && instance == null) {
            instance = new TripRecorder(c);
            instance.handler.post(instance::start);
        } else if (!on && instance != null) {
            TripRecorder r = instance;
            instance = null;
            r.handler.post(r::stop);
        } else if (on && instance.needsPermission && hasPermission(c)) {
            // Permisiunea a fost data intre timp din aplicatie. (Nu repornim GPS-ul oprit
            // intentionat de monitor() cu motorul oprit.)
            instance.needsPermission = false;
            instance.handler.post(instance::startGps);
        }
    }

    private void start() {
        startGps();
        handler.postDelayed(sample, SAMPLE_MS);
    }

    private void startGps() {
        if (gpsOn) return;
        if (!hasPermission(c)) {
            needsPermission = true;
            return;
        }
        try {
            LocationManager lm = c.getSystemService(LocationManager.class);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, handler.getLooper());
            gpsOn = true;
            Prefs.log(c, "Calatorii: GPS pornit");
        } catch (Exception e) {
            Prefs.log(c, "Calatorii: GPS indisponibil " + e);
        }
    }

    private void stop() {
        handler.removeCallbacks(sample);
        if (gpsOn) {
            try {
                c.getSystemService(LocationManager.class).removeUpdates(this);
            } catch (Exception ignored) {
            }
        }
        handler.getLooper().quitSafely();
    }

    @Override
    public void onLocationChanged(Location location) {
        last = location;
        lastAt = SystemClock.elapsedRealtime();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    private final Runnable sample = new Runnable() {
        @Override
        public void run() {
            try {
                sampleOnce();
            } finally {
                handler.postDelayed(this, SAMPLE_MS);
            }
        }
    };

    private void sampleOnce() {
        long now = SystemClock.elapsedRealtime();
        // Un gol mare intre esantioane = unitatea a dormit: incepe un drum nou.
        if (lastSampleAt > 0 && now - lastSampleAt > 60_000) resetDrive();
        lastSampleAt = now;
        Location loc = now - lastAt < LOCATION_FRESH_MS ? last : null;
        double gpsKmh = loc != null && loc.hasSpeed() ? loc.getSpeed() * 3.6 : Double.NaN;
        double canKmh = can.speed();
        int rpm = can.rpm();
        boolean moving = (!Double.isNaN(canKmh) && canKmh >= 1)
                || (Double.isNaN(canKmh) && !Double.isNaN(gpsKmh) && gpsKmh >= 3);
        boolean engine = rpm > 300;
        monitor(now, moving, engine, rpm, canKmh);
        long interval = moving ? SAMPLE_MS : engine ? IDLE_MS : Long.MAX_VALUE;
        // La oprire mai scriem un punct, ca sosirea sa fie exact unde a stat masina.
        boolean justStopped = wasMoving && !moving;
        wasMoving = moving;
        if (!justStopped && (interval == Long.MAX_VALUE || now - lastPointAt < interval - 500)) return;
        lastPointAt = now;
        try {
            JSONObject p = new JSONObject().put("t", System.currentTimeMillis());
            if (loc != null) {
                p.put("lat", loc.getLatitude()).put("lon", loc.getLongitude());
                if (loc.hasAltitude()) p.put("alt", Math.round(loc.getAltitude()));
                if (loc.hasAccuracy()) p.put("acc", Math.round(loc.getAccuracy()));
                if (!Double.isNaN(gpsKmh)) p.put("gs", round1(gpsKmh));
            }
            if (!Double.isNaN(canKmh)) p.put("cs", round1(canKmh));
            if (rpm >= 0) p.put("rpm", rpm);
            if (!Double.isNaN(can.volt())) p.put("v", can.volt());
            if (!Double.isNaN(can.temp())) p.put("temp", can.temp());
            if (can.odo() > 0) p.put("odo", can.odo());
            PointQueue.add(c, p);
        } catch (JSONException ignored) {
        }
    }

    private void resetDrive() {
        movingMs = 0;
        greeted = false;
        doorAlerted = false;
    }

    /** Salutul dupa 3 minute de mers, avertizarea de usa deschisa si GPS-ul pornit doar cand trebuie. */
    private void monitor(long now, boolean moving, boolean engine, int rpm, double canKmh) {
        if (rpm == 0) {
            if (engineOffSince == 0) engineOffSince = now;
            if (now - engineOffSince > 2 * 60_000) resetDrive();
            if (gpsOn && !moving && now - engineOffSince > GPS_OFF_AFTER_MS) stopGps();
        } else {
            engineOffSince = 0;
        }
        if ((engine || moving) && !gpsOn) startGps();

        if (moving) movingMs += SAMPLE_MS;
        if (!greeted && movingMs >= GREET_AFTER_MS) {
            greeted = true;
            if (Prefs.greetEnabled(c)) Speaker.say(c, Greeting.salute(c, can.temp()), false);
        }

        java.util.List<Integer> open = can.openDoors();
        if (open.isEmpty()) {
            doorAlerted = false;
        } else if (!doorAlerted && !Double.isNaN(canKmh) && canKmh >= DOOR_ALERT_KMH) {
            doorAlerted = true;
            Prefs.log(c, "Usa deschisa in mers: " + open);
            if (Prefs.doorAlertEnabled(c)) Speaker.say(c, Greeting.doorOpen(open), true);
        }
    }

    private void stopGps() {
        try {
            c.getSystemService(LocationManager.class).removeUpdates(this);
        } catch (Exception ignored) {
        }
        gpsOn = false;
        Prefs.log(c, "Calatorii: GPS oprit, motorul e oprit");
    }

    private static double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }
}
