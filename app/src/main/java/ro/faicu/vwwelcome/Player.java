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
            Log.w("VWWelcome", "Sunetul nu mai exista: " + f);
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
