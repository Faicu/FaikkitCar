package ro.faicu.faikkitcar;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Muzica de pe navigatie comandata din Panel (02.11, testat cu @media/@syu): cu „Acces la
 * notificari” (KeepAliveListener) vedem sesiunea YouTube Music si o comandam direct; volumul il
 * tine Teyes (modulul SOUND al com.syu.ms: update c2 = volumul, cmd 0 [v] il pune). Starea merge
 * la /api/car/music la fiecare schimbare (si la 30 s cat e o sesiune); comenzile vin pe o cerere
 * tinuta deschisa la server (long polling), deci ajung in sub o secunda. O piesa aleasa pe telefon
 * = linkul music.youtube.com deschis in YouTube Music (cautarea din aplicatie nu merge); dupa
 * pornire, daca YouTube Music a iesit in fata peste alta aplicatie, o aducem pe aceea inapoi.
 */
final class Music {
    private static final String URL_STATE = VwStatus.BASE + "/api/car/music";
    private static final String URL_WAIT = VwStatus.BASE + "/api/car/music/wait";
    static final String YTM = "com.google.android.apps.youtube.music";
    private static final int SOUND_VOL = 2; // update: volumul Teyes
    private static final int SOUND_SET_VOL = 0; // cmd: pune volumul
    private static final int MAIN_TOP = 138; // update: [pachet, activitate] din prim-plan
    private static final long HEARTBEAT_MS = 30_000;
    private static final int ART_PX = 200;

    private static Music instance;

    private final Context c;
    private final Handler h;
    private final ExecutorService send = Executors.newSingleThreadExecutor();
    private MediaController mc;
    private IBinder sound;
    private volatile int vol = -1;
    private volatile String top = "";
    private volatile String[] topCmp;
    private String lastJson = "";
    private String artSent = "";
    private boolean sentNone = true;

    private Music(Context c) {
        this.c = c.getApplicationContext();
        HandlerThread t = new HandlerThread("Music");
        t.start();
        h = new Handler(t.getLooper());
    }

    /** Din WelcomeService.onCreate; fara acces la notificari nu vedem sesiunile. */
    static synchronized void start(Context c) {
        if (instance != null || !Uploader.configured()) return;
        instance = new Music(c);
        instance.h.post(instance::init);
        new Thread(instance::pollLoop, "music-cmd").start();
    }

    private void init() {
        bindSyu();
        if (!KeepAliveListener.granted(c)) return;
        try {
            MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
            ComponentName cn = new ComponentName(c, KeepAliveListener.class);
            msm.addOnActiveSessionsChangedListener(this::onSessions, cn, h);
            onSessions(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            Prefs.log(c, "Muzica: sesiunile media refuzate: " + e.getMessage());
        }
        h.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (mc != null) lastJson = ""; // retrimisa, ca serverul sa stie ca e inca acolo
            changed();
            h.postDelayed(this, HEARTBEAT_MS);
        }
    };

    // ------------------------------------------------------------ sesiunea media

    private void onSessions(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController m : list) {
                PlaybackState st = m.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    pick = m;
                    break;
                }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
        }
        if (mc != null && pick != null && mc.getSessionToken().equals(pick.getSessionToken())) return;
        if (mc != null) mc.unregisterCallback(cb);
        mc = pick;
        if (mc != null) mc.registerCallback(cb, h);
        changed();
    }

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            changed();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            changed();
        }

        @Override
        public void onSessionDestroyed() {
            if (mc != null) mc.unregisterCallback(this);
            mc = null;
            changed();
        }
    };

    /** Schimbarile vin in rafale (metadate, apoi starea): trimitem o data, dupa 300 ms. */
    private void changed() {
        h.removeCallbacks(flush);
        h.postDelayed(flush, 300);
    }

    private final Runnable flush = this::flush;

    private void flush() {
        try {
            JSONObject o = snapshot();
            String key = o.toString();
            if (key.equals(lastJson)) return;
            lastJson = key;
            if (o.optBoolean("none") && sentNone) return;
            sentNone = o.optBoolean("none");
            String artId = o.optString("artId", "");
            Bitmap art = !artId.isEmpty() && !artId.equals(artSent) ? art() : null;
            if (art != null) o.put("art", jpeg(art));
            send.execute(() -> {
                if (post(o) && art != null) h.post(() -> artSent = artId);
            });
        } catch (Exception e) {
            Log.w("FaikkitCar", "Muzica: stare", e);
        }
    }

    /** Starea fara pozitia exacta (care se schimba mereu): pozitia doar la schimbari. */
    private JSONObject snapshot() throws Exception {
        JSONObject o = new JSONObject();
        if (mc == null) return o.put("none", true).put("vol", vol);
        PlaybackState st = mc.getPlaybackState();
        MediaMetadata m = mc.getMetadata();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        long pos = 0;
        if (st != null) {
            pos = st.getPosition();
            if (playing && st.getLastPositionUpdateTime() > 0) {
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            }
        }
        String title = m != null ? nz(m.getString(MediaMetadata.METADATA_KEY_TITLE)) : "";
        String artist = m != null ? nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST)) : "";
        String album = m != null ? nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM)) : "";
        o.put("pkg", mc.getPackageName()).put("title", title).put("artist", artist).put("album", album)
                .put("durationMs", m != null ? Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION)) : 0)
                .put("playing", playing);
        if (vol >= 0) o.put("vol", vol);
        if (!title.isEmpty() && art() != null) {
            o.put("artId", Integer.toHexString((title + "|" + artist + "|" + album).hashCode()));
        }
        // Pozitia rotunjita la 5 s intra in cheie: o derulare se trimite, mersul normal nu.
        o.put("posKey", playing ? (pos - SystemClock.elapsedRealtime()) / 5_000 : pos / 1_000);
        lastPos = pos;
        return o;
    }

    private long lastPos;

    private Bitmap art() {
        MediaMetadata m = mc != null ? mc.getMetadata() : null;
        if (m == null) return null;
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        return b != null ? b : m.getBitmap(MediaMetadata.METADATA_KEY_ART);
    }

    private static String jpeg(Bitmap b) {
        float k = Math.min(1f, (float) ART_PX / Math.max(b.getWidth(), b.getHeight()));
        Bitmap s = k < 1f ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true) : b;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, 80, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private boolean post(JSONObject o) {
        try {
            JSONObject body = new JSONObject(o.toString());
            body.remove("posKey");
            if (!body.optBoolean("none")) body.put("posMs", lastPos);
            request("POST", URL_STATE, body.toString(), 10_000);
            return true;
        } catch (Exception e) {
            Log.w("FaikkitCar", "Muzica: trimitere esuata", e);
            h.post(() -> lastJson = ""); // reincearca la urmatoarea schimbare / la 30 s
            return false;
        }
    }

    // ------------------------------------------------------------ volumul si prim-planul (com.syu.ms)

    private void bindSyu() {
        try {
            c.bindService(Syu.toolkitIntent(), new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder toolkit) {
                    h.post(() -> subscribe(toolkit));
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    sound = null;
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.w("FaikkitCar", "Muzica: MainServer", e);
        }
    }

    private void subscribe(IBinder toolkit) {
        try {
            sound = Syu.module(toolkit, 4);
            if (sound != null) {
                Syu.register(sound, new Syu.Callback() {
                    @Override
                    void onUpdate(int code, int[] ints, float[] flts, String[] strs) {
                        if (code != SOUND_VOL || ints == null || ints.length != 1) return;
                        int v = ints[0];
                        if (v != vol) {
                            vol = v;
                            h.post(Music.this::changed);
                        }
                    }
                }, SOUND_VOL);
            }
            IBinder main = Syu.module(toolkit, Syu.MODULE_MAIN);
            if (main != null) {
                Syu.register(main, new Syu.Callback() {
                    @Override
                    void onUpdate(int code, int[] ints, float[] flts, String[] strs) {
                        if (code != MAIN_TOP || strs == null || strs.length < 2 || strs[0] == null) return;
                        top = strs[0];
                        topCmp = strs;
                    }
                }, MAIN_TOP);
            }
        } catch (Exception e) {
            Log.w("FaikkitCar", "Muzica: abonare MainServer", e);
        }
    }

    // ------------------------------------------------------------ comenzile de pe telefon

    /** Ascultam doar cat navigatia e treaza; in somn reteaua lipseste oricum. */
    private boolean awake() {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isInteractive();
    }

    // Ultima comanda primita si pornirea serverului care a numerotat-o (doar firul pollLoop).
    private int lastCmd;
    private long epoch;

    private void pollLoop() {
        long backoff = 2_000;
        while (true) {
            try {
                if (!awake()) {
                    Thread.sleep(10_000);
                    continue;
                }
                // Confirmam ultima comanda primita: serverul o tine pana atunci si o retrimite
                // daca s-a pierdut (ex. pe cererea ramasa deschisa a procesului de dinainte).
                JSONObject r = new JSONObject(request("GET", URL_WAIT + "?ack=" + lastCmd + "&epoch=" + epoch, null, 40_000));
                backoff = 2_000;
                long ep = r.optLong("epoch");
                if (ep != epoch) {
                    epoch = ep; // serverul a repornit: numerotarea o ia de la capat
                    lastCmd = 0;
                }
                JSONArray cmds = r.optJSONArray("cmds");
                for (int i = 0; cmds != null && i < cmds.length(); i++) {
                    JSONObject o = cmds.getJSONObject(i);
                    int id = o.optInt("id");
                    if (id <= lastCmd) continue; // deja facuta
                    lastCmd = id;
                    h.post(() -> run(o.optString("action"), o.opt("value")));
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(backoff * 2, 30_000);
            }
        }
    }

    private void run(String action, Object value) {
        try {
            if (action.equals("vol")) {
                if (sound != null && value instanceof Number) {
                    Syu.cmd(sound, SOUND_SET_VOL, new int[] {Math.max(0, Math.min(36, ((Number) value).intValue()))}, null);
                }
                return;
            }
            if (action.equals("open")) {
                open(String.valueOf(value));
                return;
            }
            if (mc == null) {
                // Nicio sesiune (YouTube Music inchis): o tasta media o porneste pe ultima.
                if (action.equals("play") || action.equals("toggle")) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
                return;
            }
            MediaController.TransportControls tc = mc.getTransportControls();
            PlaybackState st = mc.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            switch (action) {
                case "play": tc.play(); break;
                case "pause": tc.pause(); break;
                case "toggle": if (playing) tc.pause(); else tc.play(); break;
                case "next": tc.skipToNext(); break;
                case "prev": tc.skipToPrevious(); break;
                case "seek": if (value instanceof Number) tc.seekTo(((Number) value).longValue()); break;
                default: break;
            }
        } catch (Exception e) {
            Log.w("FaikkitCar", "Muzica: comanda " + action, e);
        }
    }

    private void mediaKey(int code) {
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    /** Piesa (ID de 11 caractere) sau lista aleasa pe telefon, deschisa in YouTube Music. */
    private void open(String id) {
        if (!id.matches("[A-Za-z0-9_-]{6,64}")) return;
        String url = id.length() == 11 ? "https://music.youtube.com/watch?v=" + id
                : "https://music.youtube.com/playlist?list=" + id;
        // Aplicatia din fata: din datele de utilizare (sigur), altfel din MainServer (m0 c138, care
        // insa nu e aplicatia din fata: cu Waze in fata raporta ecranul principal, 02.11).
        String[] fromUsage = foreground(c);
        String[] before = fromUsage != null ? fromUsage : topCmp;
        c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(YTM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String was = before == null ? "necunoscut" : before[0];
        String src = fromUsage != null ? "date de utilizare" : "MainServer, fara acces la datele de utilizare";
        if (before == null || YTM.equals(before[0])) {
            Prefs.log(c, "Muzica: piesa din Panel; in fata era " + was + " (" + src + "), nu revin");
            return;
        }
        // Revenim imediat ce YouTube Music e in fata si canta piesa noua (verificat la 300 ms);
        // cel mult dupa 7 s (02.11: asteptam mereu 7 s, desi piesa pornea in ~2 s).
        long started = SystemClock.elapsedRealtime();
        String titleBefore = title();
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            long waited = SystemClock.elapsedRealtime() - started;
            String[] cur = foreground(c);
            String now = cur != null ? cur[0] : top;
            boolean started2 = YTM.equals(now) && playing() && !title().isEmpty() && !title().equals(titleBefore);
            if (!started2 && waited < 7_000) {
                h.postDelayed(step[0], 300);
                return;
            }
            // Alta aplicatie decat YouTube Music si cea de dinainte = utilizatorul a schimbat-o el.
            if (!YTM.equals(now) && !before[0].equals(now)) {
                Prefs.log(c, "Muzica: piesa din Panel; acum in fata " + now + ", nu revin la " + was);
                return;
            }
            try {
                // Intentia de lansare aduce sarcina existenta in fata asa cum era (Waze cu traseul);
                // ecranul principal n-are una, deci prin componenta.
                Intent back = c.getPackageManager().getLaunchIntentForPackage(before[0]);
                if (back == null && before.length > 1 && before[1] != null) {
                    back = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(before[0], before[1]));
                }
                if (back == null) {
                    Prefs.log(c, "Muzica: nu pot reveni la " + was + " (fara activitate de pornire)");
                    return;
                }
                c.startActivity(back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Prefs.log(c, "Muzica: piesa din Panel; revin la " + was + " dupa "
                        + String.format(java.util.Locale.ROOT, "%.1f", waited / 1000.0) + " s ("
                        + (started2 ? "piesa pornita" : "limita de 7 s") + "; " + src + ")");
            } catch (Exception e) {
                Prefs.log(c, "Muzica: revenirea la " + was + " a esuat: " + e);
            }
        };
        h.postDelayed(step[0], 300);
    }

    /** Titlul piesei de acum ("" fara sesiune). */
    private String title() {
        MediaMetadata m = mc != null ? mc.getMetadata() : null;
        return m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
    }

    private boolean playing() {
        PlaybackState st = mc != null ? mc.getPlaybackState() : null;
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    /** Accesul la datele de utilizare (Setari → Sistem): fara el nu stim sigur aplicatia din fata. */
    static boolean usageGranted(Context c) {
        android.app.AppOpsManager ops = c.getSystemService(android.app.AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), c.getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Ultima activitate ajunsa in fata (pachet, clasa), din evenimentele de utilizare ale ultimei
     * ore, fara noi si fara bara de sistem; null fara acces.
     */
    static String[] foreground(Context c) {
        if (!usageGranted(c)) return null;
        try {
            android.app.usage.UsageStatsManager usm = c.getSystemService(android.app.usage.UsageStatsManager.class);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = usm.queryEvents(now - 3_600_000, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String[] last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() != android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
                String p = e.getPackageName();
                if (p == null || p.equals(c.getPackageName()) || p.equals("com.android.systemui")) continue;
                last = new String[] {p, e.getClassName()};
            }
            return last;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String request(String method, String url, String body, int readMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(readMs);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.VW_LOG_TOKEN);
        if (body != null) {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(b.length);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(b);
            }
        }
        int code = conn.getResponseCode();
        // Raspunsul citit pana la capat (fara disconnect): conexiunea ramane in pool.
        try (InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (code != 200) throw new Exception("HTTP " + code);
            return out.toString("UTF-8");
        }
    }
}
