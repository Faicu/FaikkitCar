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
    private static final String[] APPS = {Music.YTM, "com.android.vending", "com.mapgoo.diruite", "com.waze"};
    private static final int CARD = 0x590C1A55;
    private static final int WHITE = 0xFFFFFFFF, SOFT = 0xB3FFFFFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController mc;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::onSessions;
    private android.widget.FrameLayout root;
    private LinearLayout home;
    private ScrollView all;
    private TextView title, artist;
    private ImageView cover;
    private TextView dateView;
    private IconView play;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF050B2B, 0xFF0B1A6B, 0xFF1B1066});
        root.setBackground(bg);
        home = buildHome();
        all = new ScrollView(this);
        all.setVisibility(View.GONE);
        root.addView(home);
        root.addView(all);
        setContentView(root);
    }

    // ------------------------------------------------------------ iconite desenate

    /** Iconite simple desenate pe grila 24x24, ca in Panel. */
    static final class IconView extends View {
        static final int PREV = 0, NEXT = 1, PLAY = 2, PAUSE = 3, GEAR = 4, CUBE = 5, ARROW = 6, PLUS = 7, WAVE = 8;
        private int kind;
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        IconView(android.content.Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            fill.setColor(color);
            line.setColor(color);
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeWidth(1.8f);
            line.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            line.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        void setKind(int k) {
            if (k != kind) { kind = k; invalidate(); }
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float k = Math.min(getWidth(), getHeight()) / 24f;
            c.translate((getWidth() - 24 * k) / 2f, (getHeight() - 24 * k) / 2f);
            c.scale(k, k);
            android.graphics.Path p = new android.graphics.Path();
            switch (kind) {
                case PREV:
                    c.drawRect(4, 5, 6.5f, 19, fill);
                    p.moveTo(20, 5); p.lineTo(20, 19); p.lineTo(8, 12); p.close();
                    c.drawPath(p, fill);
                    break;
                case NEXT:
                    c.drawRect(17.5f, 5, 20, 19, fill);
                    p.moveTo(4, 5); p.lineTo(4, 19); p.lineTo(16, 12); p.close();
                    c.drawPath(p, fill);
                    break;
                case PLAY:
                    p.moveTo(7, 4); p.lineTo(7, 20); p.lineTo(19, 12); p.close();
                    c.drawPath(p, fill);
                    break;
                case PAUSE:
                    c.drawRect(6, 5, 10, 19, fill);
                    c.drawRect(14, 5, 18, 19, fill);
                    break;
                case GEAR:
                    c.drawCircle(12, 12, 3.5f, line);
                    c.drawCircle(12, 12, 7, line);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        c.drawLine(12 + (float) Math.cos(a) * 7, 12 + (float) Math.sin(a) * 7,
                                12 + (float) Math.cos(a) * 10, 12 + (float) Math.sin(a) * 10, line);
                    }
                    break;
                case CUBE:
                    p.moveTo(12, 2); p.lineTo(21, 7); p.lineTo(21, 17); p.lineTo(12, 22); p.lineTo(3, 17); p.lineTo(3, 7); p.close();
                    c.drawPath(p, line);
                    c.drawLine(12, 12, 12, 22, line);
                    c.drawLine(12, 12, 3, 7, line);
                    c.drawLine(12, 12, 21, 7, line);
                    break;
                case ARROW:
                    p.moveTo(3, 11); p.lineTo(21, 3); p.lineTo(14, 21); p.lineTo(11, 13); p.close();
                    c.drawPath(p, fill);
                    break;
                case PLUS:
                    c.drawLine(12, 5, 12, 19, line);
                    c.drawLine(5, 12, 19, 12, line);
                    break;
                case WAVE:
                    float[] h = {5, 9, 14, 20, 12, 17, 8, 4};
                    for (int i = 0; i < h.length; i++) {
                        float x = 2 + i * 2.7f;
                        c.drawLine(x, 12 - h[i] / 2, x, 12 + h[i] / 2, line);
                    }
                    break;
            }
        }
    }

    // ------------------------------------------------------------ pagina de start

    private LinearLayout.LayoutParams lp(float wt, float ht, float weight) {
        int m = Ui.dp(this, 7);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(wt < 0 ? -1 : (int) wt, ht < 0 ? -1 : (int) ht, weight);
        l.setMargins(m, m, m, m);
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(this, CARD, 22));
        int p = Ui.dp(this, 18);
        c.setPadding(p, p, p, p);
        return c;
    }

    private LinearLayout buildHome() {
        int pad = Ui.dp(this, 10);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(pad, pad, pad, pad);
        row.setWeightSum(100);

        // --- panoul din stanga: ceas, (loc gol: asistentul), setari, aplicatii, Waze
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setBackground(Ui.round(this, 0x400A1448, 22));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(26);
        clock.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        clock.setTextColor(WHITE);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(0, Ui.dp(this, 18), 0, 0);
        left.addView(clock);
        dateView = Ui.text(this, "", 12, SOFT, false);
        dateView.setGravity(Gravity.CENTER);
        left.addView(dateView);
        left.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1.3f)); // inelul (asistentul): gol
        left.addView(leftIcon(IconView.GEAR, () -> open("com.syu.settings")));
        left.addView(leftIcon(IconView.CUBE, this::showAll));
        left.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFF1FD3C0, 0xFF4B2BD9, 0xFFB621D6}) {{
            setCornerRadius(Ui.dp(LauncherActivity.this, 22));
        }});
        IconView arrow = new IconView(this, IconView.ARROW, WHITE);
        nav.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        nav.setOnClickListener(v -> open("com.waze"));
        left.addView(nav, new LinearLayout.LayoutParams(-1, Ui.dp(this, 92)));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -1, 12);
        llp.setMargins(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        row.addView(left, llp);

        // --- dreapta: doua randuri de carduri
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setWeightSum(100);
        top.addView(buildMusic(), lp(0, -1, 42));
        top.addView(buildMap(), lp(0, -1, 58));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setWeightSum(100);
        bottom.addView(buildApps(), lp(0, -1, 62));
        bottom.addView(buildRadio(), lp(0, -1, 38));
        right.addView(top, new LinearLayout.LayoutParams(-1, 0, 56));
        right.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 44));
        row.addView(right, new LinearLayout.LayoutParams(0, -1, 88));
        return row;
    }

    private View leftIcon(int kind, Runnable r) {
        IconView v = new IconView(this, kind, 0xFF8FA0D6);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        l.topMargin = Ui.dp(this, 22);
        v.setLayoutParams(l);
        v.setOnClickListener(x -> r.run());
        return v;
    }

    private View buildMusic() {
        LinearLayout c = card();
        c.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFF1B2BB8, 0xFF2B3EDB, 0xFF18C9C4}) {{
            setCornerRadius(Ui.dp(LauncherActivity.this, 22));
        }});
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(this, "", 23, WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist = Ui.text(this, "", 15, SOFT, false);
        artist.setMaxLines(1);
        txt.addView(title);
        txt.addView(artist);
        head.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        head.addView(cover, new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 80)));
        c.addView(head, new LinearLayout.LayoutParams(-1, -2));
        c.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        ctl.addView(circle(IconView.PREV, () -> { if (mc != null) mc.getTransportControls().skipToPrevious(); }), circleLp());
        play = new IconView(this, IconView.PLAY, WHITE);
        View playC = wrapCircle(play, () -> {
            if (mc == null) { open(Music.YTM); return; }
            PlaybackState s = mc.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
            else mc.getTransportControls().play();
        });
        ctl.addView(playC, circleLp());
        ctl.addView(circle(IconView.NEXT, () -> { if (mc != null) mc.getTransportControls().skipToNext(); }), circleLp());
        c.addView(ctl, new LinearLayout.LayoutParams(-1, -2));
        c.setOnClickListener(v -> open(mc != null ? mc.getPackageName() : Music.YTM));
        return c;
    }

    private LinearLayout.LayoutParams circleLp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        l.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        return l;
    }

    private View circle(int kind, Runnable r) {
        return wrapCircle(new IconView(this, kind, WHITE), r);
    }

    private View wrapCircle(IconView icon, Runnable r) {
        LinearLayout f = new LinearLayout(this);
        f.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(0x40FFFFFF);
        f.setBackground(d);
        f.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        f.setOnClickListener(v -> r.run());
        return f;
    }

    /** Harta Waze din launcherul original nu se poate copia: card gol, atingerea deschide Waze. */
    private View buildMap() {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.round(this, 0x33FFFFFF, 22));
        c.addView(new IconView(this, IconView.ARROW, SOFT), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView t = Ui.text(this, "Waze", 18, SOFT, false);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        c.setOnClickListener(v -> open("com.waze"));
        return c;
    }

    private View buildApps() {
        LinearLayout c = card();
        c.addView(Ui.text(this, "Aplicatii", 15, SOFT, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        PackageManager pm = getPackageManager();
        for (String pkg : APPS) {
            Drawable ic;
            CharSequence label;
            try {
                ic = pm.getApplicationIcon(pkg);
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ic);
            t.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60)));
            TextView l = Ui.text(this, label.toString(), 13, WHITE, false);
            l.setSingleLine(true);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            t.addView(l);
            t.setOnClickListener(v -> open(pkg));
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout plus = new LinearLayout(this);
        plus.setOrientation(LinearLayout.VERTICAL);
        plus.setGravity(Gravity.CENTER);
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setBackground(Ui.round(this, 0x33FFFFFF, 14));
        box.addView(new IconView(this, IconView.PLUS, SOFT), new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        plus.addView(box, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60)));
        plus.addView(Ui.text(this, "Toate", 13, WHITE, false));
        plus.setOnClickListener(v -> showAll());
        row.addView(plus, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        return c;
    }

    /** Radio: frecventa si butoanele din original nu se stiu (CAN/Teyes), deci doar titlul si deschiderea. */
    private View buildRadio() {
        LinearLayout c = card();
        c.addView(Ui.text(this, "Radio", 15, SOFT, false));
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        IconView wave = new IconView(this, IconView.WAVE, 0xFF35D6D0);
        r.addView(wave, new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 70)));
        c.addView(r, new LinearLayout.LayoutParams(-1, 0, 1));
        c.setOnClickListener(v -> open("com.syu.radio"));
        return c;
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

    private final Runnable tickDate = new Runnable() {
        @Override
        public void run() {
            String d = new java.text.SimpleDateFormat("EEE d MMM", new java.util.Locale("ro")).format(new java.util.Date());
            dateView.setText(d);
            ui.postDelayed(this, 30_000);
        }
    };

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
            TextView l = Ui.text(this, r.loadLabel(pm).toString(), 14, WHITE, false);
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
            title.setText("Lipseste „Acces la notificari”");
            return;
        }
        try {
            ComponentName cn = new ComponentName(this, KeepAliveListener.class);
            msm.addOnActiveSessionsChangedListener(sessions, cn, ui);
            onSessions(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            title.setText("Sesiunile media refuzate");
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
            cover.setImageDrawable(null);
            play.setKind(IconView.PLAY);
            return;
        }
        MediaMetadata m = mc.getMetadata();
        PlaybackState st = mc.getPlaybackState();
        String t = m != null ? m.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        String a = m != null ? m.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        title.setText(t != null ? t : "");
        artist.setText(a != null ? a : "");
        Bitmap art = m != null ? m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) : null;
        if (art == null && m != null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        cover.setImageBitmap(art);
        play.setKind(st != null && st.getState() == PlaybackState.STATE_PLAYING ? IconView.PAUSE : IconView.PLAY);
    }
}
