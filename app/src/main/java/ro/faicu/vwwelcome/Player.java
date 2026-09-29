package ro.faicu.vwwelcome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;

/** Reda sunetul de bun venit. Cere "focus" audio: radioul/muzica se opresc si revin dupa. */
final class Player {
    private static long lastPlay = -100_000;
    // Referinta statica: altfel MediaPlayer poate fi colectat de GC in timpul redarii.
    private static MediaPlayer current;
    // Volumul sistemului de dinainte de redare (-1 = nu l-am schimbat) si cel setat de noi.
    private static int restoreVolume = -1;
    private static int ourVolume;

    private Player() {}

    /**
     * Programeaza redarea fisierului dupa delayMs. Intoarce false (si nu reda) daca s-a mai
     * redat ceva in ultimele 20 s, ca sa evitam redarea dubla; force ocoleste verificarea.
     */
    static boolean play(Context ctx, File f, long delayMs, boolean force) {
        if (!force && playedRecently()) return false;
        lastPlay = SystemClock.elapsedRealtime();
        Context c = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> start(c, f), delayMs);
        return true;
    }

    static boolean playedRecently() {
        return SystemClock.elapsedRealtime() - lastPlay < 20_000;
    }

    /** Redare imediata din butonul de test: urmatorul sunet din lista. */
    static File test(Context ctx) {
        File f = Prefs.nextSound(ctx);
        if (f != null) play(ctx, f, 0, true);
        return f;
    }

    private static void start(Context c, File f) {
        if (!f.exists()) {
            Prefs.log(c, "Redare: sunetul nu mai exista (" + Prefs.soundName(f) + ")");
            return;
        }

        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build();
        stopCurrent(c, am);
        int granted = am.requestAudioFocus(focus);
        applyVolume(c, am);

        MediaPlayer mp = new MediaPlayer();
        current = mp;
        try {
            mp.setAudioAttributes(attrs);
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> {
                Prefs.log(c, "Redare terminata");
                finish(c, m, am, focus);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Prefs.log(c, "Redare: eroare MediaPlayer " + what + "/" + extra);
                finish(c, m, am, focus);
                return true;
            });
            mp.prepare();
            mp.start();
            Prefs.log(c, "Redare pornita: " + Prefs.soundName(f) + ", " + mp.getDuration() / 1000
                    + " s, focus audio " + (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                            ? "primit" : "REFUZAT (" + granted + ")"));
        } catch (Exception e) {
            Prefs.log(c, "Redare: eroare " + e);
            finish(c, mp, am, focus);
        }
    }

    /** Seteaza volumul media la procentul ales; il refacem la final. */
    private static void applyVolume(Context c, AudioManager am) {
        int pct = Prefs.volumePercent(c);
        if (pct <= 0) return;
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int before = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            ourVolume = Math.max(1, Math.round(max * pct / 100f));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, ourVolume, 0);
            restoreVolume = before;
            Prefs.log(c, "Volum " + before + " -> " + ourVolume + " (din " + max + ")");
        } catch (Exception e) {
            Prefs.log(c, "Volum: nu am putut seta (" + e + ")");
        }
    }

    /** Refacem volumul doar daca nu l-a schimbat nimeni intre timp. */
    private static void restoreVolume(Context c, AudioManager am) {
        if (restoreVolume < 0) return;
        try {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == ourVolume) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0);
            }
        } catch (Exception e) {
            Prefs.log(c, "Volum: nu am putut reface (" + e + ")");
        }
        restoreVolume = -1;
    }

    private static void finish(Context c, MediaPlayer mp, AudioManager am, AudioFocusRequest focus) {
        if (current == mp) current = null;
        mp.release();
        restoreVolume(c, am);
        am.abandonAudioFocusRequest(focus);
    }

    private static void stopCurrent(Context c, AudioManager am) {
        if (current == null) return;
        try {
            current.release();
        } catch (Exception ignored) {
        }
        current = null;
        restoreVolume(c, am);
    }
}
