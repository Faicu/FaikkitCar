package ro.faicu.faikkitcar.panel;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static ro.faicu.faikkitcar.panel.Ui.dp;

/**
 * Muzica de pe navigatie (YouTube Music): piesa, redare/pauza, anterioara/urmatoarea, derularea,
 * volumul Teyes si cautarea unei piese, care porneste apoi in masina. Cardul e acelasi intre
 * redesenarile filei Acum (ca harta live), are firul lui de long polling (/api/music) si se
 * actualizeaza singur; MainActivity il redeseneaza doar cand apare sau dispare.
 */
final class MusicCard {
    final LinearLayout view;
    /** Cat e tras cursorul de derulare: fila nu se redeseneaza (ar muta cardul de sub deget). */
    boolean touching;
    /** Muzica a aparut sau a disparut: fila Acum se redeseneaza. */
    Runnable onVisibility = () -> {};

    private final Context c;
    private final Api api;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService cmds = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final ExecutorService searches = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> artCache = new HashMap<>();

    private final ImageView art;
    private final TextView source, title, sub, pos, dur, volText;
    private final SeekBar bar, volBar;
    private static final int VOL_MAX = 36; // treapta maxima pe navigatie
    private long volSentAt;
    private final Button play;

    private JSONObject music;
    private long receivedAt; // SystemClock.elapsedRealtime() la primire
    private int vol = -1;
    private long volSetAt; // dupa o apasare, volumul de pe server poate fi inca cel vechi
    private volatile int gen;
    private boolean on;

    MusicCard(Context c, Api api) {
        this.c = c;
        this.api = api;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(Ui.round(c, Ui.CARD, 20));
        int p = dp(c, 20);
        view.setPadding(p, p, p, p);

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // Coperta, cu o nota muzicala dedesubt cat nu e (sau nu s-a incarcat inca).
        android.widget.FrameLayout artBox = new android.widget.FrameLayout(c);
        artBox.setBackground(Ui.round(c, Ui.CARD2, 12));
        artBox.setClipToOutline(true);
        TextView note = Ui.text(c, "♪", 28, Ui.MUTED, false);
        note.setGravity(Gravity.CENTER);
        artBox.addView(note, new android.widget.FrameLayout.LayoutParams(-1, -1));
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artBox.addView(art, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(c, 64), dp(c, 64));
        alp.rightMargin = dp(c, 14);
        top.addView(artBox, alp);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        source = Ui.text(c, "", 12, Ui.MUTED, false);
        texts.addView(source);
        title = Ui.text(c, "", 18, Ui.TEXT, true);
        title.setMaxLines(2);
        sub = Ui.text(c, "", 14, Ui.MUTED, false);
        sub.setMaxLines(2);
        texts.addView(title);
        texts.addView(sub);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        view.addView(top);

        bar = new SeekBar(c);
        bar.setMax(1000);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) pos.setText(Fmt.clock(v * durationMs() / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                touching = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                touching = false;
                long ms = s.getProgress() * durationMs() / 1000;
                if (music != null) {
                    try {
                        music.put("posMs", ms).put("ageMs", 0);
                        receivedAt = SystemClock.elapsedRealtime();
                    } catch (Exception ignored) {
                    }
                }
                send("seek", ms);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(c, 12);
        view.addView(bar, blp);
        LinearLayout times = new LinearLayout(c);
        pos = Ui.text(c, "", 12, Ui.MUTED, false);
        dur = Ui.text(c, "", 12, Ui.MUTED, false);
        dur.setGravity(Gravity.END);
        times.addView(pos, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(dur, new LinearLayout.LayoutParams(0, -2, 1));
        view.addView(times);

        LinearLayout controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER);
        Button prev = Ui.button(c, "⏮\uFE0E", Ui.SECONDARY, v -> send("prev", null));
        play = Ui.button(c, "▶\uFE0E", Ui.PRIMARY, v -> {
            // Afisat imediat; starea reala vine de la masina in ~1 s.
            if (music != null) {
                try {
                    boolean playing = music.optBoolean("playing");
                    music.put("posMs", positionMs()).put("ageMs", 0).put("playing", !playing);
                    receivedAt = SystemClock.elapsedRealtime();
                    update();
                } catch (Exception ignored) {
                }
            }
            send("toggle", null);
        });
        Button next = Ui.button(c, "⏭\uFE0E", Ui.SECONDARY, v -> send("next", null));
        for (Button b : new Button[] {prev, play, next}) {
            b.setTextSize(22);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 56), 1);
            lp.leftMargin = lp.rightMargin = dp(c, 4);
            controls.addView(b, lp);
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(c, 8);
        view.addView(controls, clp);

        // Volumul Teyes: bara (0–36, ca pe navigatie) intre „−” si „+”, eticheta deasupra.
        volText = Ui.text(c, "", 14, Ui.MUTED, false);
        LinearLayout.LayoutParams vtp = new LinearLayout.LayoutParams(-1, -2);
        vtp.topMargin = dp(c, 12);
        view.addView(volText, vtp);
        LinearLayout volRow = new LinearLayout(c);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        Button down = Ui.button(c, "−", Ui.SECONDARY, v -> setVol(vol - 1));
        Button up = Ui.button(c, "+", Ui.SECONDARY, v -> setVol(vol + 1));
        for (Button b : new Button[] {down, up}) {
            b.setTextSize(22);
            b.setPadding(0, 0, 0, 0);
        }
        volBar = new SeekBar(c);
        volBar.setMax(VOL_MAX);
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user) return;
                vol = v;
                volSetAt = SystemClock.elapsedRealtime();
                volText.setText("Volum " + v);
                // Cat glisezi: cel mult o comanda la 300 ms (serverul pastreaza doar ultima).
                if (volSetAt - volSentAt > 300) {
                    volSentAt = volSetAt;
                    send("vol", v);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                touching = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                touching = false;
                setVol(s.getProgress());
            }
        });
        volRow.addView(down, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        volRow.addView(volBar, new LinearLayout.LayoutParams(0, -2, 1));
        volRow.addView(up, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
        vlp.topMargin = dp(c, 4);
        view.addView(volRow, vlp);

        Ui.addButton(c, view, "🔍 Caută o piesă", Ui.SECONDARY, v -> openSearch());
    }

    boolean visible() {
        return music != null;
    }

    /** Pornit pe fila Acum; long polling pe firul lui. */
    void start() {
        if (on) return;
        on = true;
        int g = ++gen;
        ui.post(tick);
        new Thread(() -> {
            Integer after = null;
            while (gen == g) {
                try {
                    JSONObject r = api.music(after);
                    if (gen != g) return;
                    after = r.optInt("seq");
                    JSONObject m = r.optJSONObject("music");
                    ui.post(() -> apply(m));
                } catch (Exception e) {
                    try {
                        Thread.sleep(5_000); // fara retea / sesiune expirata (o trateaza starea live)
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "music").start();
    }

    void stop() {
        on = false;
        gen++;
        ui.removeCallbacks(tick);
    }

    private void apply(JSONObject m) {
        boolean was = music != null;
        music = m;
        receivedAt = SystemClock.elapsedRealtime();
        if (m != null && !m.isNull("vol") && SystemClock.elapsedRealtime() - volSetAt > 3_000) vol = m.optInt("vol");
        update();
        if (was != (m != null)) onVisibility.run();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (music != null && !touching) updatePosition();
            ui.postDelayed(this, 1_000);
        }
    };

    private void update() {
        if (music == null) return;
        String t = music.optString("title");
        title.setText(t.isEmpty() ? "Nimic de redat" : t);
        String artist = music.optString("artist"), album = music.optString("album");
        sub.setText(artist + (album.isEmpty() || album.equals(t) ? "" : (artist.isEmpty() ? "" : " · ") + album));
        // Fara varianta emoji (pe telefon ⏸ iesea portocaliu): bare si triunghi ca text.
        play.setText(music.optBoolean("playing") ? "❚❚" : "▶\uFE0E");
        source.setText(sourceLabel(music.optString("pkg")));
        volText.setText(vol >= 0 ? "Volum " + vol : "Volum —");
        if (vol >= 0 && !touching) volBar.setProgress(vol);
        volBar.setEnabled(vol >= 0);
        dur.setText(durationMs() > 0 ? Fmt.clock(durationMs()) : "");
        updatePosition();
        String artId = music.isNull("artId") ? "" : music.optString("artId");
        if (artId.isEmpty()) {
            art.setImageBitmap(null);
        } else if (artCache.containsKey(artId)) {
            art.setImageBitmap(artCache.get(artId));
        } else {
            images.execute(() -> {
                try {
                    Bitmap b = api.image("/api/music/art/" + artId);
                    ui.post(() -> {
                        artCache.put(artId, b);
                        if (music != null && artId.equals(music.optString("artId"))) art.setImageBitmap(b);
                    });
                } catch (Exception ignored) {
                    // fara coperta
                }
            });
        }
    }

    private static String sourceLabel(String pkg) {
        return "com.google.android.apps.youtube.music".equals(pkg) ? "YouTube Music pe navigație"
                : "Muzica de pe navigație";
    }

    private long durationMs() {
        return music == null ? 0 : music.optLong("durationMs");
    }

    private long positionMs() {
        if (music == null) return 0;
        long p = music.optLong("posMs");
        if (music.optBoolean("playing")) p += music.optLong("ageMs") + SystemClock.elapsedRealtime() - receivedAt;
        long d = durationMs();
        return d > 0 ? Math.min(p, d) : p;
    }

    private void updatePosition() {
        long p = positionMs(), d = durationMs();
        pos.setText(Fmt.clock(p));
        bar.setProgress(d > 0 ? (int) (p * 1000 / d) : 0);
    }

    private void setVol(int v) {
        if (vol < 0) return; // nu stim volumul de acum
        vol = Math.max(0, Math.min(VOL_MAX, v));
        volSetAt = volSentAt = SystemClock.elapsedRealtime();
        volText.setText("Volum " + vol);
        volBar.setProgress(vol);
        send("vol", vol);
    }

    private void send(String action, Object value) {
        cmds.execute(() -> {
            try {
                api.musicCmd(action, value);
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(c, "Nu am trimis comanda: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ------------------------------------------------------------ cautarea

    private void openSearch() {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 20);
        box.setPadding(p, dp(c, 8), p, p);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText q = new EditText(c);
        q.setHint("Artist, piesă...");
        q.setHintTextColor(Ui.MUTED);
        q.setTextColor(Ui.TEXT);
        q.setTextSize(17);
        q.setSingleLine(true);
        q.setInputType(InputType.TYPE_CLASS_TEXT);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setBackground(Ui.round(c, Ui.CARD2, 12));
        q.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        row.addView(q, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        android.view.inputmethod.InputMethodManager imm = c.getSystemService(android.view.inputmethod.InputMethodManager.class);
        // Cautarea pornește singura la 400 ms dupa ultima tasta; tasta „Caută” ascunde tastatura.
        Runnable go = () -> search(q.getText().toString().trim(), results, d);
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int a, int b, int n) {}

            @Override
            public void onTextChanged(CharSequence t, int a, int b, int n) {}

            @Override
            public void afterTextChanged(android.text.Editable e) {
                ui.removeCallbacks(go);
                if (e.toString().trim().length() >= 2) ui.postDelayed(go, 400);
            }
        });
        q.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                ui.removeCallbacks(go);
                if (imm != null) imm.hideSoftInputFromWindow(q.getWindowToken(), 0);
                go.run();
                return true;
            }
            return false;
        });
        box.addView(row);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(c, 8);
        box.addView(results, rlp);
        ScrollView sv = new ScrollView(c);
        sv.addView(box);
        d[0] = new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Pune o piesă în mașină")
                .setView(sv)
                .setNegativeButton("Închide", null)
                .create();
        // Tastatura deschisa de la inceput (pe telefon nu aparea cu SOFT_INPUT_STATE_VISIBLE singur).
        if (d[0].getWindow() != null) {
            d[0].getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d[0].setOnShowListener(x -> {
            q.requestFocus();
            q.postDelayed(() -> {
                if (imm != null) imm.showSoftInput(q, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }, 150);
        });
        d[0].setOnDismissListener(x -> ui.removeCallbacks(go));
        d[0].show();
    }

    private int searchSeq; // doar raspunsul la ultima cautare se afiseaza

    private void search(String query, LinearLayout results, AlertDialog[] d) {
        if (query.isEmpty()) return;
        int seq = ++searchSeq;
        if (results.getChildCount() == 0) Ui.hint(c, results, "Caut...");
        searches.execute(() -> {
            try {
                JSONArray hits = api.musicSearch(query);
                ui.post(() -> {
                    if (seq == searchSeq) showHits(hits, results, d);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (seq != searchSeq) return;
                    results.removeAllViews();
                    Ui.hint(c, results, "Căutarea a eșuat: " + e.getMessage());
                });
            }
        });
    }

    private void showHits(JSONArray hits, LinearLayout results, AlertDialog[] d) {
        results.removeAllViews();
        if (hits.length() == 0) {
            Ui.hint(c, results, "Nimic găsit.");
            return;
        }
        for (int i = 0; i < hits.length(); i++) {
            JSONObject h = hits.optJSONObject(i);
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(c, 8), 0, dp(c, 8));
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, Ui.round(c, Ui.CARD2, 10)));
            ImageView th = new ImageView(c);
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            th.setBackground(Ui.round(c, Ui.CARD2, 8));
            th.setClipToOutline(true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48));
            tlp.rightMargin = dp(c, 12);
            row.addView(th, tlp);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = Ui.text(c, h.optString("title"), 16, Ui.TEXT, true);
            t.setMaxLines(2);
            texts.addView(t);
            String meta = h.optString("artist");
            if (!h.optString("album").isEmpty()) meta += " · " + h.optString("album");
            if (!h.optString("duration").isEmpty()) meta += " · " + h.optString("duration");
            TextView m = Ui.text(c, meta, 13, Ui.MUTED, false);
            m.setMaxLines(2);
            texts.addView(m);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            String id = h.optString("id");
            row.setOnClickListener(v -> {
                send("open", id);
                Toast.makeText(c, "Pornește în mașină: " + h.optString("title"), Toast.LENGTH_SHORT).show();
                if (d[0] != null) d[0].dismiss();
            });
            results.addView(row, new ViewGroup.LayoutParams(-1, -2));
            String thumb = h.isNull("thumb") ? "" : h.optString("thumb");
            if (!thumb.isEmpty()) {
                images.execute(() -> {
                    try {
                        Bitmap b = api.image(thumb);
                        ui.post(() -> th.setImageBitmap(b));
                    } catch (Exception ignored) {
                        // fara miniatura
                    }
                });
            }
        }
    }

    /** Pus in fila Acum (scos din parintele de la redesenarea trecuta). */
    void attach(LinearLayout parent) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 14);
        parent.addView(view, lp);
        view.setVisibility(View.VISIBLE);
    }
}
