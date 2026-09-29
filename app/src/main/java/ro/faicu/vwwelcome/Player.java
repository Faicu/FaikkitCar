package ro.faicu.vwwelcome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/** Reda sunetul de bun venit. Cere "focus" audio: radioul/muzica se opresc si revin dupa. */
final class Player {
    private static long lastPlay = -100_000;
    // Referinta statica: altfel MediaPlayer poate fi colectat de GC in timpul redarii.
    private static MediaPlayer current;

    private Player() {}

    /** Redare la trezire; ignorata daca s-a mai redat in ultimele 20 s (evitam redarea dubla). */
    static void play(Context ctx, long delayMs) {
        Context c = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> start(c, false), delayMs);
    }

    /** Redare imediata din butonul de test, fara protectia anti-dubla. */
    static void test(Context ctx) {
        start(ctx.getApplicationContext(), true);
    }

    private static void start(Context c, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPlay < 20_000) return;
        File f = Prefs.soundFile(c);
        if (!f.exists()) {
            Log.w("VWWelcome", "Niciun sunet ales");
            return;
        }
        lastPlay = now;

        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build();
        stopCurrent();
        am.requestAudioFocus(focus);

        MediaPlayer mp = new MediaPlayer();
        current = mp;
        try {
            mp.setAudioAttributes(attrs);
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> finish(m, am, focus));
            mp.setOnErrorListener((m, what, extra) -> {
                finish(m, am, focus);
                return true;
            });
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            finish(mp, am, focus);
            Log.e("VWWelcome", "Eroare la redare", e);
        }
    }

    private static void finish(MediaPlayer mp, AudioManager am, AudioFocusRequest focus) {
        if (current == mp) current = null;
        mp.release();
        am.abandonAudioFocusRequest(focus);
    }

    private static void stopCurrent() {
        if (current == null) return;
        try {
            current.release();
        } catch (Exception ignored) {
        }
        current = null;
    }
}
