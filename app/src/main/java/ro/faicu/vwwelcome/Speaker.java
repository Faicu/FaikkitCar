package ro.faicu.vwwelcome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mesaje vorbite (salutul de dupa 30 s de mers, avertizarea de usa deschisa) prin TextToSpeech,
 * ca ghidare de navigatie: muzica doar isi coboara volumul. Daca pe navigatie nu exista un
 * motor TTS (sau nu stie romana), avertizarile se reduc la un semnal sonor. Alegem cea mai
 * buna voce romana: cea online (mai naturala) cand avem internet, altfel cea locala. Vocea
 * online porneste cu ~3 s intarziere, deci avertizarile (urgente) folosesc vocea locala.
 * Salutul (neurgent) vine, cand avem internet, ca MP3 de pe server (vocea Piper, mult mai
 * naturala decat vocea Google de pe navigatie); daca nu merge, ramane TextToSpeech.
 */
final class Speaker {
    private static TextToSpeech tts;
    private static boolean ready;
    private static String pending;
    private static boolean pendingUrgent;
    private static String currentVoice;
    private static final String URL_TTS = "https://car.faicu.ro/api/car/tts";
    // Referinta statica: altfel MediaPlayer poate fi colectat de GC in timpul redarii.
    private static MediaPlayer serverPlayer;

    private Speaker() {}

    static void say(Context ctx, String text, boolean beepIfNoVoice) {
        Context c = ctx.getApplicationContext();
        if (beepIfNoVoice || !Uploader.configured() || !online(c)) {
            sayTts(c, text, beepIfNoVoice);
            return;
        }
        new Thread(() -> {
            try {
                playServer(c, text, download(c, text));
            } catch (Exception e) {
                Prefs.log(c, "Voce server indisponibila (" + e.getMessage() + "), folosesc vocea locala");
                sayTts(c, text, false);
            }
        }, "Speaker").start();
    }

    /** MP3-ul cu textul rostit de vocea de pe server, salvat in cache. */
    private static File download(Context c, String text) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(URL_TTS).openConnection();
        try {
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(new JSONObject().put("text", text).toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            File f = new File(c.getCacheDir(), "voce.mp3");
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return f;
        } finally {
            conn.disconnect();
        }
    }

    /** Ca ghidarea de navigatie: muzica isi coboara volumul cat vorbim. */
    private static synchronized void playServer(Context c, String text, File f) throws Exception {
        if (serverPlayer != null) serverPlayer.release();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioManager am = c.getSystemService(AudioManager.class);
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build();
        MediaPlayer mp = new MediaPlayer();
        serverPlayer = mp;
        mp.setAudioAttributes(attrs);
        mp.setDataSource(f.getAbsolutePath());
        MediaPlayer.OnCompletionListener done = m -> {
            synchronized (Speaker.class) {
                if (serverPlayer == m) serverPlayer = null;
            }
            m.release();
            am.abandonAudioFocusRequest(focus);
        };
        mp.setOnCompletionListener(done);
        mp.setOnErrorListener((m, what, extra) -> {
            Prefs.log(c, "Voce server: eroare MediaPlayer " + what + "/" + extra);
            done.onCompletion(m);
            return true;
        });
        mp.prepare();
        am.requestAudioFocus(focus);
        mp.start();
        Prefs.log(c, "Voce: \"" + text + "\" (Piper, server)");
    }

    private static synchronized void sayTts(Context c, String text, boolean beepIfNoVoice) {
        // beepIfNoVoice = avertizare: trebuie sa se auda imediat.
        if (ready) {
            speakNow(c, text, beepIfNoVoice);
            return;
        }
        pending = text;
        pendingUrgent = beepIfNoVoice;
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
                if (pending != null) speakNow(c, pending, pendingUrgent);
                pending = null;
            }
        });
    }

    private static void speakNow(Context c, String text, boolean urgent) {
        chooseVoice(c, !urgent && online(c));
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

    /** Cea mai buna voce romana (online doar daca e permis); o schimbam doar daca difera. */
    private static void chooseVoice(Context c, boolean online) {
        Voice best = null;
        for (Voice v : romanianVoices()) {
            if (notInstalled(v) || (v.isNetworkConnectionRequired() && !online)) continue;
            if (best == null || score(v) > score(best)) best = v;
        }
        if (best == null || best.getName().equals(currentVoice)) return;
        try {
            if (tts.setVoice(best) == TextToSpeech.SUCCESS) {
                currentVoice = best.getName();
                Prefs.log(c, "Voce: folosesc " + best.getName());
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
