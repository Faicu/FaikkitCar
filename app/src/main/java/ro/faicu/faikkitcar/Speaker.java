package ro.faicu.faikkitcar;

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
import java.security.MessageDigest;
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
    private static final String URL_TTS = VwStatus.BASE + "/api/car/tts";
    // Referinta statica: altfel MediaPlayer poate fi colectat de GC in timpul redarii.
    private static MediaPlayer serverPlayer;
    // Motorul TTS tinut legat ramane in memorie (procesul lui, zeci de MB) cat traim noi; pe
    // navigatia fara memorie il eliberam la atat timp dupa ultima vorbire si il refacem la nevoie.
    private static final long TTS_IDLE_MS = 30_000;
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable releaseTts = Speaker::releaseTts;

    private Speaker() {}

    static void say(Context ctx, String text, boolean beepIfNoVoice) {
        Context c = ctx.getApplicationContext();
        // Avertizarile (urgente) si frazele fixe se tin pe navigatie ca MP3 Piper (28.11): pornesc
        // instant, fara motorul Google (care poate lipsi) si fara cererea de ~3 s la server.
        File cached = cacheFile(c, text);
        if (beepIfNoVoice && cached.exists()) {
            new Thread(() -> {
                try {
                    playServer(c, text, cached);
                } catch (Exception e) {
                    Prefs.log(c, "Voce din cache: eroare (" + e.getMessage() + "), folosesc vocea locala");
                    sayTts(c, text, true);
                }
            }, "Speaker").start();
            return;
        }
        if (beepIfNoVoice || !Uploader.configured() || !online(c)) {
            sayTts(c, text, beepIfNoVoice);
            if (beepIfNoVoice) prefetchOne(c, text);
            return;
        }
        new Thread(() -> {
            try {
                playServer(c, text, download(c, text, false));
            } catch (Exception e) {
                Prefs.log(c, "Voce server indisponibila (" + e.getMessage() + "), folosesc vocea locala");
                sayTts(c, text, false);
            }
        }, "Speaker").start();
    }

    private static boolean prefetched;

    /**
     * Pregateste in fundal MP3-urile frazelor fixe (usile, rețeaua mobilă), o data pe pornire, cand
     * avem internet: la prima ușă deschisă ele sunt deja pe navigatie.
     */
    static synchronized void prefetchFixed(Context ctx) {
        if (prefetched || !Uploader.configured()) return;
        Context c = ctx.getApplicationContext();
        if (!online(c)) return;
        prefetched = true;
        new Thread(() -> {
            List<String> texts = new ArrayList<>();
            int n = CanLink.DOORS.length;
            for (int mask = 1; mask < (1 << n); mask++) {
                List<Integer> open = new ArrayList<>();
                for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) open.add(i);
                texts.add(Greeting.doorOpen(open));
            }
            texts.add(Greeting.networkMode().trim());
            int got = 0;
            for (String t : texts) {
                if (cacheFile(c, t).exists()) continue;
                try {
                    download(c, t, true);
                    got++;
                    Thread.sleep(300);
                } catch (Exception e) {
                    Prefs.log(c, "Voce: pregatirea frazelor oprita (" + e.getMessage() + ")");
                    synchronized (Speaker.class) { prefetched = false; }
                    return;
                }
            }
            if (got > 0) Prefs.log(c, "Voce: " + got + " fraze fixe salvate pe navigatie");
        }, "SpeakerPrefetch").start();
    }

    /** O fraza urgenta spusa fara cache: o aducem de pe server pentru data viitoare. */
    private static void prefetchOne(Context c, String text) {
        if (!Uploader.configured() || !online(c)) return;
        new Thread(() -> {
            try {
                download(c, text, true);
            } catch (Exception ignored) {
            }
        }, "SpeakerPrefetch").start();
    }

    /** MP3-ul pastrat al unei fraze fixe: numele e hash-ul textului. */
    private static File cacheFile(Context c, String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            File dir = new File(c.getFilesDir(), "voce");
            dir.mkdirs();
            return new File(dir, sb + ".mp3");
        } catch (Exception e) {
            return new File(c.getCacheDir(), "voce_cache_indisponibil.mp3");
        }
    }

    /**
     * MP3-ul cu textul rostit de vocea de pe server. Cu persist (fraze fixe), ramane pe navigatie
     * pentru totdeauna; altfel (salutul, care difera de fiecare data) e doar un fisier temporar.
     */
    private static File download(Context c, String text, boolean persist) throws Exception {
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
            File f = persist ? cacheFile(c, text) : new File(c.getCacheDir(), "voce.mp3");
            File part = new File(f.getPath() + ".part");
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            if (part.length() < 1000) {
                part.delete();
                throw new Exception("raspuns prea mic");
            }
            f.delete();
            if (!part.renameTo(f)) throw new Exception("nu pot salva MP3-ul");
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
        main.removeCallbacks(releaseTts);
        main.postDelayed(releaseTts, TTS_IDLE_MS);
    }

    /** Elibereaza motorul TTS dupa TTS_IDLE_MS fara vorbire (cat inca vorbeste, mai asteapta). */
    private static synchronized void releaseTts() {
        if (tts == null || !ready) return;
        if (tts.isSpeaking()) {
            main.postDelayed(releaseTts, TTS_IDLE_MS);
            return;
        }
        tts.shutdown();
        tts = null;
        ready = false;
        currentVoice = null;
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
