package ro.faicu.vwwelcome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Mesaje vorbite (salutul de dupa 3 minute, avertizarea de usa deschisa) prin TextToSpeech,
 * ca ghidare de navigatie: muzica doar isi coboara volumul. Daca pe navigatie nu exista un
 * motor TTS (sau nu stie romana), avertizarile se reduc la un semnal sonor.
 */
final class Speaker {
    private static TextToSpeech tts;
    private static boolean ready;
    private static String pending;

    private Speaker() {}

    static synchronized void say(Context ctx, String text, boolean beepIfNoVoice) {
        Context c = ctx.getApplicationContext();
        if (ready) {
            speakNow(c, text);
            return;
        }
        pending = text;
        if (tts != null) return; // initializarea e in curs
        tts = new TextToSpeech(c, status -> {
            synchronized (Speaker.class) {
                if (status != TextToSpeech.SUCCESS) {
                    Prefs.log(c, "Voce: niciun motor TTS pe navigatie (" + status + ")");
                    tts = null;
                    if (beepIfNoVoice) beep();
                    return;
                }
                int lang = tts.setLanguage(new Locale("ro", "RO"));
                Prefs.log(c, "Voce: motor " + tts.getDefaultEngine() + ", romana "
                        + (lang >= TextToSpeech.LANG_AVAILABLE ? "disponibila" : "INDISPONIBILA (" + lang + ")"));
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                ready = true;
                if (pending != null) speakNow(c, pending);
                pending = null;
            }
        });
    }

    private static void speakNow(Context c, String text) {
        int r = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "vw" + System.nanoTime());
        Prefs.log(c, "Voce: \"" + text + "\"" + (r == TextToSpeech.SUCCESS ? "" : " (eroare " + r + ")"));
    }

    /** Trei bipuri scurte, pentru avertizari cand nu avem voce. */
    static void beep() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 900);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(tg::release, 1500);
        } catch (Exception ignored) {
        }
    }
}
