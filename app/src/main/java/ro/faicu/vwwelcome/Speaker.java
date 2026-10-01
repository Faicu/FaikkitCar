package ro.faicu.vwwelcome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mesaje vorbite (salutul de dupa 3 minute, avertizarea de usa deschisa) prin TextToSpeech,
 * ca ghidare de navigatie: muzica doar isi coboara volumul. Daca pe navigatie nu exista un
 * motor TTS (sau nu stie romana), avertizarile se reduc la un semnal sonor. Alegem cea mai
 * buna voce romana: cea online (mai naturala) cand avem internet, altfel cea locala.
 */
final class Speaker {
    private static TextToSpeech tts;
    private static boolean ready;
    private static String pending;
    private static String currentVoice;

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
                logVoices(c);
                ready = true;
                if (pending != null) speakNow(c, pending);
                pending = null;
            }
        });
    }

    private static void speakNow(Context c, String text) {
        chooseVoice(c);
        int r = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "vw" + System.nanoTime());
        Prefs.log(c, "Voce: \"" + text + "\"" + (r == TextToSpeech.SUCCESS ? "" : " (eroare " + r + ")"));
    }

    /** Vocile romane ale motorului, o data pe pornire, ca sa stim ce are navigatia. */
    private static void logVoices(Context c) {
        List<String> names = new ArrayList<>();
        for (Voice v : romanianVoices()) {
            names.add(v.getName() + " q" + v.getQuality() + (v.isNetworkConnectionRequired() ? " online" : "")
                    + (notInstalled(v) ? " nedescarcata" : ""));
        }
        Prefs.log(c, "Voce: voci romane " + (names.isEmpty() ? "niciuna in lista" : String.join(", ", names)));
    }

    /** Cea mai buna voce romana pentru conexiunea de acum; o schimbam doar daca difera. */
    private static void chooseVoice(Context c) {
        boolean online = online(c);
        Voice best = null;
        for (Voice v : romanianVoices()) {
            if (notInstalled(v) || (v.isNetworkConnectionRequired() && !online)) continue;
            if (best == null || score(v) > score(best)) best = v;
        }
        if (best == null || best.getName().equals(currentVoice)) return;
        try {
            if (tts.setVoice(best) == TextToSpeech.SUCCESS) {
                currentVoice = best.getName();
                Prefs.log(c, "Voce: folosesc " + best.getName() + (online ? " (online)" : " (fara internet)"));
            }
        } catch (Exception e) {
            Prefs.log(c, "Voce: nu am putut alege " + best.getName() + " (" + e + ")");
        }
    }

    /** Calitatea intai; la egalitate, vocea online (mai naturala la Google). */
    private static int score(Voice v) {
        return v.getQuality() * 2 + (v.isNetworkConnectionRequired() ? 1 : 0);
    }

    private static List<Voice> romanianVoices() {
        List<Voice> out = new ArrayList<>();
        try {
            java.util.Set<Voice> all = tts.getVoices();
            if (all == null) return out;
            for (Voice v : all) {
                if (v.getLocale() != null && "ro".equals(v.getLocale().getLanguage())) out.add(v);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static boolean notInstalled(Voice v) {
        return v.getFeatures() != null
                && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    private static boolean online(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
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
