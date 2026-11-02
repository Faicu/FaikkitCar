package ro.faicu.faikkitcar;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.media.MediaBrowserService;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test pentru controlul muzicii de pe telefon (02.11): cu „Acces la notificari” dat
 * (KeepAliveListener), vedem sesiunile media active (YouTube Music) si le putem comanda direct.
 * Doar comanda interna `@media` prin car-cmd.ts:
 *   @media                          sesiunile, metadatele, starea, volumul
 *   @media play|pause|toggle|next|prev|stop   prin MediaController (prima sesiune)
 *   @media seek <s>                 la secunda data
 *   @media search <text>            playFromSearch
 *   @media key play|pause|toggle|next|prev    prin AudioManager.dispatchMediaKeyEvent
 *   @media vol [n]                  volumul STREAM_MUSIC (citit, apoi pus la n)
 *   @media adj up|down              adjustStreamVolume pe STREAM_MUSIC
 *   @media intent <text>            MEDIA_PLAY_FROM_SEARCH catre YouTube Music (ca Asistentul)
 *   @media open <url>               ACTION_VIEW catre YouTube Music (music.youtube.com/watch?v=...)
 *   @media browse [text]            MediaBrowser la YouTube Music (ca Android Auto): radacina,
 *                                   copiii; cu text, playFromSearch prin sesiunea data de browser
 * Dupa o comanda asteapta 1,5 s si raporteaza din nou starea.
 */
final class MediaProbe {
    private MediaProbe() {}

    static String run(Context c, String args) {
        StringBuilder sb = new StringBuilder();
        String[] a = args.trim().isEmpty() ? new String[0] : args.trim().split("\\s+", 2);
        String verb = a.length > 0 ? a[0] : "";
        String rest = a.length > 1 ? a[1] : "";
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            if (verb.equals("vol")) {
                sb.append(volume(am)).append('\n');
                if (!rest.isEmpty()) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Integer.parseInt(rest.trim()), 0);
                    sb.append("pus la ").append(rest.trim()).append(" -> ").append(volume(am)).append('\n');
                }
                return sb.toString();
            }
            if (verb.equals("adj")) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        rest.trim().equals("down") ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI);
                Thread.sleep(500);
                return volume(am);
            }
            if (verb.equals("intent") || verb.equals("open")) {
                Intent i = verb.equals("intent")
                        ? new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                                .putExtra(SearchManager.QUERY, rest)
                                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        : new Intent(Intent.ACTION_VIEW, Uri.parse(rest.trim()));
                i.setPackage(YTM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                List<ResolveInfo> r = c.getPackageManager().queryIntentActivities(i, 0);
                sb.append("activitati: ").append(r.size());
                for (ResolveInfo ri : r) sb.append(' ').append(ri.activityInfo.name);
                sb.append(" | peste alte aplicatii: ").append(Settings.canDrawOverlays(c) ? "da" : "nu").append('\n');
                c.startActivity(i);
                sb.append("pornit\n");
                Thread.sleep(6_000);
                return sb.append(list(c, am)).toString();
            }
            if (verb.equals("browse")) return sb.append(browse(c, rest.trim())).append(list(c, am)).toString();
            if (verb.equals("key")) {
                int code = keyCode(rest.trim());
                if (code == 0) return "tasta necunoscuta: " + rest;
                long t = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
                sb.append("tasta trimisa: ").append(rest.trim()).append("\n");
                Thread.sleep(1_500);
                return sb.append(list(c, am)).toString();
            }
            if (!verb.isEmpty()) {
                List<MediaController> s = sessions(c);
                if (s.isEmpty()) return "nicio sesiune media activa\n" + volume(am);
                MediaController mc = s.get(0);
                MediaController.TransportControls tc = mc.getTransportControls();
                switch (verb) {
                    case "play": tc.play(); break;
                    case "pause": tc.pause(); break;
                    case "toggle":
                        PlaybackState st = mc.getPlaybackState();
                        if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause();
                        else tc.play();
                        break;
                    case "next": tc.skipToNext(); break;
                    case "prev": tc.skipToPrevious(); break;
                    case "stop": tc.stop(); break;
                    case "seek": tc.seekTo((long) (Double.parseDouble(rest.trim()) * 1000)); break;
                    case "search": tc.playFromSearch(rest, new Bundle()); break;
                    default: return "comanda necunoscuta: " + verb;
                }
                sb.append(verb).append(" trimis la ").append(mc.getPackageName()).append('\n');
                Thread.sleep(1_500);
            }
            sb.append(list(c, am));
        } catch (SecurityException e) {
            sb.append("refuzat (acces la notificari ").append(KeepAliveListener.granted(c) ? "dat" : "NEDAT")
                    .append("): ").append(e.getMessage());
        } catch (Exception e) {
            sb.append("eroare: ").append(e);
        }
        return sb.toString();
    }

    private static final String YTM = "com.google.android.apps.youtube.music";

    private static String browse(Context c, String query) throws Exception {
        StringBuilder sb = new StringBuilder();
        List<ResolveInfo> svc = c.getPackageManager().queryIntentServices(
                new Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(YTM), 0);
        sb.append("servicii MediaBrowser: ").append(svc.size()).append('\n');
        if (svc.isEmpty()) return sb.toString();
        ComponentName cn = new ComponentName(YTM, svc.get(0).serviceInfo.name);
        sb.append(cn.getClassName()).append('\n');
        HandlerThread t = new HandlerThread("MediaProbe");
        t.start();
        Handler h = new Handler(t.getLooper());
        CountDownLatch done = new CountDownLatch(1);
        MediaBrowser[] mb = new MediaBrowser[1];
        String[] res = new String[1];
        h.post(() -> {
            mb[0] = new MediaBrowser(c, cn, new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    res[0] = "conectat, radacina " + mb[0].getRoot();
                    done.countDown();
                }

                @Override
                public void onConnectionFailed() {
                    res[0] = "conexiune refuzata";
                    done.countDown();
                }

                @Override
                public void onConnectionSuspended() {
                    res[0] = "conexiune suspendata";
                    done.countDown();
                }
            }, null);
            mb[0].connect();
        });
        try {
            if (!done.await(10, TimeUnit.SECONDS)) return sb.append("fara raspuns in 10 s\n").toString();
            sb.append(res[0]).append('\n');
            if (!mb[0].isConnected()) return sb.toString();
            CountDownLatch kids = new CountDownLatch(1);
            StringBuilder ks = new StringBuilder();
            h.post(() -> mb[0].subscribe(mb[0].getRoot(), new MediaBrowser.SubscriptionCallback() {
                @Override
                public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> children) {
                    ks.append("copii: ").append(children.size()).append('\n');
                    for (int i = 0; i < Math.min(20, children.size()); i++) {
                        MediaBrowser.MediaItem it = children.get(i);
                        ks.append("  ").append(it.isBrowsable() ? "[dosar] " : "").append(it.getDescription().getTitle())
                                .append(" | ").append(it.getMediaId()).append('\n');
                    }
                    kids.countDown();
                }

                @Override
                public void onError(String parentId) {
                    ks.append("copii: eroare\n");
                    kids.countDown();
                }
            }));
            if (!kids.await(10, TimeUnit.SECONDS)) ks.append("copii: fara raspuns\n");
            sb.append(ks);
            if (!query.isEmpty()) {
                MediaController mc = new MediaController(c, mb[0].getSessionToken());
                mc.getTransportControls().playFromSearch(query, new Bundle());
                sb.append("playFromSearch prin browser trimis\n");
                Thread.sleep(6_000);
            }
        } finally {
            h.post(() -> {
                if (mb[0] != null) mb[0].disconnect();
                t.quitSafely();
            });
        }
        return sb.toString();
    }

    private static List<MediaController> sessions(Context c) {
        MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
        return msm.getActiveSessions(new ComponentName(c, KeepAliveListener.class));
    }

    private static String list(Context c, AudioManager am) {
        StringBuilder sb = new StringBuilder();
        List<MediaController> s = sessions(c);
        sb.append("Sesiuni active: ").append(s.size()).append(" | ").append(volume(am))
                .append(" | muzica activa: ").append(am.isMusicActive() ? "da" : "nu").append('\n');
        for (MediaController mc : s) {
            sb.append("- ").append(mc.getPackageName());
            PlaybackState st = mc.getPlaybackState();
            if (st != null) {
                long pos = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                            * st.getPlaybackSpeed());
                }
                sb.append(" | ").append(state(st.getState())).append(" | poz ").append(pos / 1000).append(" s")
                        .append(" | actiuni 0x").append(Long.toHexString(st.getActions()));
            } else {
                sb.append(" | fara stare");
            }
            MediaMetadata m = mc.getMetadata();
            if (m != null) {
                sb.append("\n  ").append(m.getString(MediaMetadata.METADATA_KEY_TITLE))
                        .append(" — ").append(m.getString(MediaMetadata.METADATA_KEY_ARTIST))
                        .append(" | album ").append(m.getString(MediaMetadata.METADATA_KEY_ALBUM))
                        .append(" | durata ").append(m.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000).append(" s")
                        .append(" | coperta ").append(m.getBitmap(MediaMetadata.METADATA_KEY_ART) != null
                                || m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ? "da" : "nu")
                        .append(" | uri ").append(m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
            }
            MediaController.PlaybackInfo pi = mc.getPlaybackInfo();
            if (pi != null) {
                sb.append("\n  volum sesiune ").append(pi.getCurrentVolume()).append('/').append(pi.getMaxVolume())
                        .append(pi.getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE ? " (la distanta)" : "");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String volume(AudioManager am) {
        return "volum muzica " + am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    private static int keyCode(String k) {
        switch (k) {
            case "play": return KeyEvent.KEYCODE_MEDIA_PLAY;
            case "pause": return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case "toggle": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "next": return KeyEvent.KEYCODE_MEDIA_NEXT;
            case "prev": return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            default: return 0;
        }
    }

    private static String state(int s) {
        switch (s) {
            case PlaybackState.STATE_PLAYING: return "reda";
            case PlaybackState.STATE_PAUSED: return "pauza";
            case PlaybackState.STATE_STOPPED: return "oprit";
            case PlaybackState.STATE_BUFFERING: return "se incarca";
            case PlaybackState.STATE_NONE: return "nimic";
            case PlaybackState.STATE_ERROR: return "eroare";
            default: return "stare " + s;
        }
    }
}
