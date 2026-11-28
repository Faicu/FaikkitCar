package ro.faicu.faikkitcar;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Launcherul propriu (28.11, de incercat la masina): pagina de pornire cu ceasul, cardul de
 * muzica (sesiunea media activa, comenzi directe) si scurtaturile; „Toate aplicatiile” deschide
 * lista completa. Nu inlocuieste nimic pana nu e ales ca Home (Teyes launcher8 ramane instalat).
 * Muzica: aceeasi sesiune ca la Music.java, citita aici separat (cu „Acces la notificari”).
 */
public class LauncherActivity extends Activity {
    private static final String[][] TILES = {
            {"Waze", "com.waze"},
            {"Radio", "com.syu.radio"},
            {"Muzica", Music.YTM},
            {"Telefon", "com.syu.bt"},
            {"Egalizator", "com.syu.eq"},
            {"FaikkitCar", "com.mapgoo.diruite"},
            {"Setari Teyes", "com.syu.settings"},
            {"Setari", null},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController mc;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::onSessions;
    private LinearLayout home;
    private ScrollView all;
    private TextView title, artist, hint;
    private ImageView cover;
    private TextView dateView;
    private final Runnable tickDate = new Runnable() {
        @Override
        public void run() {
            String d = new java.text.SimpleDateFormat("EEEE, d MMMM", new java.util.Locale("ro")).format(new java.util.Date());
            dateView.setText(d.substring(0, 1).toUpperCase() + d.substring(1));
            ui.postDelayed(this, 30_000);
        }
    };
    private TextView play;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setBackgroundColor(Ui.BG);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        home = buildHome();
        all = new ScrollView(this);
        all.setVisibility(View.GONE);
        root.addView(home);
        root.addView(all);
        setContentView(root);
    }

    private LinearLayout buildHome() {
        int p = Ui.dp(this, 24);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(p, p, p, p);

        // stanga: ceas, data, muzica
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(72);
        clock.setTextColor(Ui.TEXT);
        left.addView(clock);
        dateView = Ui.text(this, "", 20, Ui.MUTED, false);
        left.addView(dateView);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.CARD, 18));
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = Ui.dp(this, 28);
        left.addView(card, cp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Ui.round(this, Ui.CARD2, 10));
        int cs = Ui.dp(this, 110);
        top.addView(cover, new LinearLayout.LayoutParams(cs, cs));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(this, 16), 0, 0, 0);
        title = Ui.text(this, "", 22, Ui.TEXT, true);
        title.setMaxLines(2);
        artist = Ui.text(this, "", 17, Ui.MUTED, false);
        artist.setMaxLines(1);
        hint = Ui.text(this, "", 15, Ui.MUTED, false);
        txt.addView(title);
        txt.addView(artist);
        txt.addView(hint);
        top.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        ctl.setPadding(0, Ui.dp(this, 14), 0, 0);
        ctl.addView(ctlButton("⏮", () -> { if (mc != null) mc.getTransportControls().skipToPrevious(); }));
        play = ctlButton("▶", () -> {
            if (mc == null) { open(Music.YTM); return; }
            PlaybackState s = mc.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
            else mc.getTransportControls().play();
        });
        ctl.addView(play);
        ctl.addView(ctlButton("⏭", () -> { if (mc != null) mc.getTransportControls().skipToNext(); }));
        card.addView(ctl);
        card.setOnClickListener(v -> open(mc != null ? mc.getPackageName() : Music.YTM));

        row.addView(left, new LinearLayout.LayoutParams(0, -1, 4));

        // dreapta: scurtaturile
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        for (String[] t : TILES) grid.addView(tile(t[0], t[1], iconOf(t[1] != null ? t[1] : "com.android.settings")));
        grid.addView(tile("Toate", "*", null));
        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER);
        right.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 6);
        rp.leftMargin = p;
        row.addView(right, rp);
        return row;
    }

    private TextView ctlButton(String s, Runnable r) {
        TextView t = Ui.text(this, s, 30, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, Ui.CARD2, 14));
        int w = Ui.dp(this, 76), m = Ui.dp(this, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, Ui.dp(this, 56));
        lp.setMargins(m, 0, m, 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private Drawable iconOf(String pkg) {
        if (pkg == null) return null;
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private View tile(String name, String pkg, Drawable icon) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, Ui.CARD, 18));
        int m = Ui.dp(this, 8);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = Ui.dp(this, 130);
        lp.setMargins(m, m, m, m);
        t.setLayoutParams(lp);
        ImageView iv = new ImageView(this);
        if (icon != null) {
            iv.setImageDrawable(icon);
            t.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        } else {
            TextView g = Ui.text(this, "\u2630", 34, Ui.ACCENT, true);
            g.setGravity(Gravity.CENTER);
            t.addView(g, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        }
        TextView l = Ui.text(this, name, 16, pkg != null && icon == null && !"*".equals(pkg) && !isInstalled(pkg) ? Ui.MUTED : Ui.TEXT, false);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Ui.dp(this, 8), 0, 0);
        t.addView(l);
        t.setOnClickListener(v -> {
            if ("*".equals(pkg)) showAll();
            else if (pkg == null) startSafe(new Intent(android.provider.Settings.ACTION_SETTINGS));
            else open(pkg);
        });
        return t;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void open(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) startSafe(i);
    }

    private void startSafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Prefs.log(this, "Launcher: nu s-a putut deschide: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ toate aplicatiile

    private void showAll() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = new ArrayList<>(pm.queryIntentActivities(q, 0));
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        GridLayout g = new GridLayout(this);
        g.setColumnCount(6);
        int p = Ui.dp(this, 16);
        g.setPadding(p, p, p, p);
        for (ResolveInfo r : apps) {
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(this, 150);
            lp.height = Ui.dp(this, 130);
            t.setLayoutParams(lp);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(r.loadIcon(pm));
            t.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
            TextView l = Ui.text(this, r.loadLabel(pm).toString(), 14, Ui.TEXT, false);
            l.setGravity(Gravity.CENTER);
            l.setMaxLines(2);
            t.addView(l);
            final ComponentName cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            t.setOnClickListener(v -> startSafe(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)));
            g.addView(t);
        }
        all.removeAllViews();
        all.addView(g);
        all.setVisibility(View.VISIBLE);
        home.setVisibility(View.GONE);
    }

    private void showHome() {
        all.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showHome(); // butonul Home din Teyes: inapoi la prima pagina
    }

    @Override
    public void onBackPressed() {
        if (all.getVisibility() == View.VISIBLE) showHome();
    }

    // ------------------------------------------------------------ muzica

    @Override
    protected void onStart() {
        super.onStart();
        tickDate.run();
        msm = getSystemService(MediaSessionManager.class);
        if (!KeepAliveListener.granted(this)) {
            hint.setText("Lipseste „Acces la notificari” pentru FaikkitCar");
            return;
        }
        try {
            ComponentName cn = new ComponentName(this, KeepAliveListener.class);
            msm.addOnActiveSessionsChangedListener(sessions, cn, ui);
            onSessions(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            hint.setText("Sesiunile media refuzate");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tickDate);
        try {
            msm.removeOnActiveSessionsChangedListener(sessions);
        } catch (Exception ignored) {
        }
        if (mc != null) mc.unregisterCallback(cb);
        mc = null;
    }

    private void onSessions(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController m : list) {
                PlaybackState s = m.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = m; break; }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
        }
        if (mc != null) mc.unregisterCallback(cb);
        mc = pick;
        if (mc != null) mc.registerCallback(cb, ui);
        refresh();
    }

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) { refresh(); }

        @Override
        public void onMetadataChanged(MediaMetadata m) { refresh(); }

        @Override
        public void onSessionDestroyed() { mc = null; refresh(); }
    };

    private void refresh() {
        if (mc == null) {
            title.setText("Nimic nu canta");
            artist.setText("");
            hint.setText("Atinge pentru YouTube Music");
            cover.setImageDrawable(null);
            play.setText("▶");
            return;
        }
        MediaMetadata m = mc.getMetadata();
        PlaybackState s = mc.getPlaybackState();
        title.setText(m != null && m.getString(MediaMetadata.METADATA_KEY_TITLE) != null ? m.getString(MediaMetadata.METADATA_KEY_TITLE) : "");
        artist.setText(m != null && m.getString(MediaMetadata.METADATA_KEY_ARTIST) != null ? m.getString(MediaMetadata.METADATA_KEY_ARTIST) : "");
        hint.setText("");
        Bitmap art = m != null ? m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) : null;
        if (art == null && m != null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        cover.setImageBitmap(art);
        play.setText(s != null && s.getState() == PlaybackState.STATE_PLAYING ? "⏸" : "▶");
    }
}
