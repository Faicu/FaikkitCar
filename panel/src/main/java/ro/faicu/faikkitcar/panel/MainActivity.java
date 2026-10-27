package ro.faicu.faikkitcar.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.views.MapView;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static ro.faicu.faikkitcar.panel.Ui.dp;

/**
 * FaikkitCar Panel: datele de pe car.faicu.ro pe telefon. Patru file jos, ca site-ul: Acum
 * (starea masinii, calatoria in curs, rezervorul si autonomia, pozitia), Calatorii (lista; una
 * aleasa se deschide pe ecranul ei, cu harta si grafic), Costuri (30 de zile, pe luni,
 * alimentari) si Mai mult (mentenanta, jurnalul, actualizarea, iesirea din cont).
 * UI-ul e construit din cod; datele se citesc pe un fir de fundal si se redesenează doar
 * cand s-au schimbat (harta nu se reseteaza la fiecare reimprospatare).
 */
public class MainActivity extends Activity {
    private static final long REFRESH_MS = 30_000;
    private static final String[] TABS = {"Acum", "Călătorii", "Costuri", "Navigație", "Mai mult"};
    private static final int NOW = 0, TRIPS = 1, COSTS = 2, UNIT = 3, MORE = 4;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Api api;

    private int tab;
    private LinearLayout root, content;
    private ScrollView scroll;
    private final TextView[] tabViews = new TextView[TABS.length];
    private final List<MapView> maps = new ArrayList<>();
    private MapBox.LiveRoute liveMap; // harta calatoriei in curs, pastrata intre redesenari

    // Datele curente; `shown` = ce s-a desenat ultima data, ca sa nu redesenam degeaba.
    private JSONArray trips, gaps, log, places, suggestions, service;
    private JSONObject live, car, fuel, stats, newerApk;
    private JSONArray points;
    private String pointsFor, selected, shown = "";
    // Fila jurnalului: "" = evenimente, o categorie (ca pe server), "toate"; "comenzi" = comenzile.
    private String logCat = "";
    private static final String[] LOG_CATS = {"", "sistem", "masina", "calatorii", "sunet", "actualizari", "can", "comenzi", "toate"};
    private static final String[] LOG_LABELS = {"Evenimente", "Pornire și somn", "Mașina", "GPS", "Sunet și voce",
            "Actualizări", "CAN și diagnostic", "Comenzi la distanță", "Tot"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MapBox.init(this);
        api = new Api(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        if (Store.token(this).isEmpty()) showLogin();
        else showMain();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        for (MapView m : maps) m.onResume();
        if (liveMap != null) liveMap.map.onResume();
        if (content != null) load(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tick);
        stopLive();
        for (MapView m : maps) m.onPause();
        if (liveMap != null) liveMap.map.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private final Runnable tick = () -> load(false);
    // In prim-plan: o cerere terminata dupa onPause nu mai reporneste nimic (tick, starea live).
    private boolean resumed;

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- login

    private void showLogin() {
        content = null;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(this, 20);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        LinearLayout card = Ui.card(this, col);
        card.addView(Ui.text(this, "FaikkitCar Panel", 24, Ui.TEXT, true));
        Ui.hint(this, card, "Intră cu contul de pe car.faicu.ro.");
        EditText user = Ui.labeled(this, card, "Utilizator", "", InputType.TYPE_CLASS_TEXT);
        EditText pass = Ui.labeled(this, card, "Parolă", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Ui.addButton(this, card, "Intră", Ui.PRIMARY, v -> {
            v.setEnabled(false);
            String u = user.getText().toString().trim(), pw = pass.getText().toString();
            io.execute(() -> {
                String error = api.login(u, pw);
                ui.post(() -> {
                    v.setEnabled(true);
                    if (error == null) showMain();
                    else toast(error);
                });
            });
        });
        setContentView(sv);
    }

    private void logout() {
        stopLive();
        Store.setToken(this, "");
        trips = log = points = places = null;
        live = car = fuel = stats = newerApk = null;
        selected = null;
        shown = "";
        detachMaps();
        showLogin();
    }

    // ---------------------------------------------------------------- ecranul principal

    private void showMain() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = dp(this, 14);
        root.setPadding(p, dp(this, 12), p, 0);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.logo);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40));
        llp.rightMargin = dp(this, 10);
        head.addView(logo, llp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "FaikkitCar", 22, Ui.TEXT, true));
        titles.addView(Ui.text(this, "Golf 6 · v" + BuildConfig.VERSION_NAME, 13, Ui.MUTED, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView refresh = Ui.text(this, "↻", 24, Ui.ACCENT, false);
        refresh.setPadding(p, p / 2, p, p / 2);
        refresh.setOnClickListener(v -> load(true));
        head.addView(refresh);
        root.addView(head);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(Ui.round(this, Ui.CARD, 16));
        int bp = dp(this, 4);
        bar.setPadding(bp, bp, bp, bp);
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            TextView t = Ui.text(this, TABS[i], 13, Ui.MUTED, true);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(this, 10), 0, dp(this, 10));
            t.setOnClickListener(v -> openTab(index));
            tabViews[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }

        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(this, 12));
        scroll.addView(content);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1);
        slp.topMargin = dp(this, 10);
        root.addView(scroll, slp);
        // Filele jos, la indemana degetului.
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(this, 6);
        blp.bottomMargin = dp(this, 10);
        root.addView(bar, blp);
        setContentView(root);
        styleTabs();
        render();
        load(true);
    }

    private void openTab(int index) {
        placeDraft = null; // o fila inchide editorul de loc
        if (tab == index && index == TRIPS) selected = null; // a doua atingere: inapoi la lista
        tab = index;
        shown = "";
        styleTabs();
        render();
        load(false);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void styleTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == tab;
            tabViews[i].setTextColor(on ? Ui.ACCENT : Ui.MUTED);
            tabViews[i].setBackground(on ? Ui.round(this, 0x2638BDF8, 12) : null);
        }
    }

    /** Citeste datele filei curente pe firul de fundal; force = redeseneaza oricum. */
    private void load(boolean force) {
        ui.removeCallbacks(tick);
        if (!resumed) return;
        if (force) shown = "";
        if (tab == NOW || tab == UNIT) startLive(); // Navigatie: aceeasi stare live
        else stopLive();
        int forTab = tab;
        String cat = logCat;
        io.execute(() -> {
            try {
                if (forTab == NOW) {
                    // Starea vine pe firul ei (startLive); aici actualizarea si propunerile.
                    JSONObject apk = newerApk == null ? Updater.newer(this) : newerApk;
                    JSONArray sg = api.suggestions();
                    ui.post(() -> {
                        newerApk = apk;
                        suggestions = sg;
                        render();
                    });
                    ui.postDelayed(tick, 60_000);
                    return;
                } else if (forTab == UNIT) {
                    return; // totul vine din starea live (si istoricul, loadUnitHistory)
                } else if (forTab == TRIPS) {
                    JSONArray t = api.trips();
                    JSONArray g = gapsOrEmpty();
                    ui.post(() -> {
                        trips = t;
                        gaps = g;
                        render();
                    });
                } else if (forTab == COSTS) {
                    JSONObject st = api.stats();
                    JSONObject f = api.fuel();
                    ui.post(() -> {
                        stats = st;
                        fuel = f;
                        render();
                    });
                } else {
                    JSONObject c = api.car();
                    JSONArray pl = api.places();
                    JSONArray sv = api.service();
                    JSONArray l = cat.equals("comenzi") ? api.cmds() : api.log(cat);
                    JSONObject apk = newerApk == null ? Updater.newer(this) : newerApk;
                    ui.post(() -> {
                        car = c;
                        places = pl;
                        service = sv;
                        log = l;
                        newerApk = apk;
                        render();
                    });
                }
            } catch (Api.Unauthorized e) {
                ui.post(this::logout);
                return;
            } catch (Exception e) {
                ui.post(() -> toast("Nu pot citi datele: " + e.getMessage()));
            }
            ui.postDelayed(tick, REFRESH_MS);
        });
    }

    // ---------------------------------------------------------------- starea live

    // Generatia firului live: o generatie noua (sau -1) il opreste dupa cererea in curs.
    private volatile int liveGen;
    private boolean liveOn;

    /**
     * Fila Acum: cererea asteapta la server o stare noua de la masina (long polling), apoi se
     * pune imediat urmatoarea. Starea apare pe telefon la ~1 s dupa ce o trimite masina.
     */
    private void startLive() {
        if (liveOn) return;
        liveOn = true;
        int gen = ++liveGen;
        String start = live == null || live.isNull("at") ? null : live.optString("at");
        new Thread(() -> {
            String after = start;
            while (liveGen == gen) {
                try {
                    JSONObject l = api.live(after);
                    if (liveGen != gen) return;
                    JSONObject route = liveRoute(l);
                    ui.post(() -> {
                        live = l;
                        if (route != null) liveMap().update(route);
                        render();
                    });
                    after = l.isNull("at") ? null : l.optString("at");
                    // Nicio stare primita vreodata: n-avem dupa ce astepta, deci intrebam rar.
                    if (after == null) Thread.sleep(15_000);
                } catch (Api.Unauthorized e) {
                    ui.post(this::logout);
                    return;
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    try {
                        Thread.sleep(5_000); // fara retea: reincearca
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "live").start();
    }

    private MapBox.LiveRoute liveMap() {
        if (liveMap == null) {
            liveMap = new MapBox.LiveRoute(this);
            liveMap.onTouchEnd = () -> {
                shown = "";
                render();
            };
        }
        return liveMap;
    }

    /** Punctele noi ale calatoriei in curs (toate, la una noua); null fara calatorie. */
    private JSONObject liveRoute(JSONObject l) {
        JSONObject trip = l.optJSONObject("trip");
        if (trip == null) return null;
        MapBox.LiveRoute m = liveMap;
        boolean same = m != null && trip.optString("start").equals(m.tripStart);
        try {
            return api.liveRoute(same ? m.lastT : null);
        } catch (Exception e) {
            return null; // harta ramane cum era; la starea urmatoare incearca din nou
        }
    }

    private void stopLive() {
        liveOn = false;
        liveGen++;
    }

    private void detachMaps() {
        for (MapView m : maps) m.onDetach();
        maps.clear();
    }

    private void addMap(LinearLayout parent, MapView map, int heightDp) {
        maps.add(map);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(this, heightDp));
        lp.bottomMargin = dp(this, 14);
        parent.addView(map, lp);
    }

    private void render() {
        if (content == null) return;
        if (liveMap != null && liveMap.touching) return; // reluat la ridicarea degetului
        if (UnitCard.Spark.touching) return; // idem, graficele navigatiei
        if (placeDraft != null) {
            // Editorul de loc se construieste o singura data (nu la fiecare reimprospatare),
            // ca textul scris si pinul sa nu se piarda.
            String state = "place|" + placeEditorGen;
            if (state.equals(shown)) return;
            shown = state;
            detachMaps();
            content.removeAllViews();
            renderPlaceEditor();
            scroll.post(() -> scroll.scrollTo(0, 0));
            return;
        }
        String state = tab + "|" + selected + "|" + logCat + "|" + Store.hideIdle(this) + "|"
                + (newerApk != null) + "|"
                + (tab == NOW ? live + "|" + suggestions
                        : tab == UNIT ? live + "|" + unitHistoryAt
                        : tab == TRIPS ? trips + "|" + pointsFor
                        : tab == COSTS ? stats + "|" + fuel
                        : car + "|" + places + "|" + service + "|" + log);
        if (state.equals(shown)) return;
        shown = state;
        int y = scroll.getScrollY();
        detachMaps();
        content.removeAllViews();
        if (tab == NOW) renderNow();
        else if (tab == TRIPS) renderTrips();
        else if (tab == COSTS) renderCosts();
        else if (tab == UNIT) renderUnit();
        else renderMore();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ---------------------------------------------------------------- fila Acum

    @Override
    public void onBackPressed() {
        if (placeDraft != null) {
            closePlaceEditor();
            return;
        }
        // Din detaliile unei calatorii, „inapoi” duce la lista, nu iese din aplicatie.
        if (content != null && tab == TRIPS && selected != null) {
            selected = null;
            render();
            return;
        }
        super.onBackPressed();
    }

    private void renderUpdate() {
        if (newerApk == null) return;
        LinearLayout up = Ui.card(this, content);
        up.addView(Ui.text(this, "Versiune nouă: " + newerApk.optString("versionName"), 18, Ui.ACCENT, true));
        Ui.hint(this, up, "Se descarcă de pe car.faicu.ro; Android îți cere o confirmare.");
        Ui.addButton(this, up, "Actualizează acum", Ui.PRIMARY, v -> {
            toast("Descarc actualizarea...");
            Updater.start(this, error -> {
                if (error != null) toast("Actualizare eșuată: " + error);
            });
        });
    }

    private static String stateLabel(String state) {
        switch (state) {
            case "driving": return "În mers";
            case "engine": return "Motor pornit, pe loc";
            case "traffic": return "Oprit în trafic";
            case "contact": return "Doar contact";
            case "nosignal": return "Fără semnal";
            default: return "Oprită";
        }
    }

    private static int stateColor(String state) {
        switch (state) {
            case "driving": return Ui.ACCENT;
            case "engine": return Ui.OK;
            case "traffic": return 0xFFFB923C;
            case "contact": return Ui.WARN;
            case "nosignal": return 0xFF94A3B8;
            default: return Ui.MUTED;
        }
    }

    /**
     * Fila Acum, in ordinea importantei: starea (cu valorile de acum, clima intr-un rand si
     * avertizarile, navigatia pe scurt), calatoria in curs, rezervorul cu autonomia si unde e
     * masina. Performanta navigatiei pe larg e in fila Navigatie.
     */
    private void renderNow() {
        renderUpdate();
        renderSuggestions();
        if (live == null) {
            Ui.hint(this, content, "Se încarcă...");
            return;
        }
        String state = live.optString("state", "off");
        LinearLayout card = Ui.card(this, content);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(Ui.round(this, stateColor(state), 8));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(this, 16), dp(this, 16));
        dlp.rightMargin = dp(this, 12);
        row.addView(dot, dlp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, stateLabel(state), 22, Ui.TEXT, true));
        String since;
        if (live.isNull("since")) {
            since = "nicio stare primită încă";
        } else {
            String iso = live.optString("since");
            double min = (System.currentTimeMillis() - Fmt.millis(iso)) / 60_000.0;
            // Sub 10 minute (semafor), cu secunde.
            since = "de " + (min < 10 ? Fmt.shortDuration(Math.max(0, min))
                    : Fmt.ago(iso).replaceFirst("^acum ", "")) + " (din " + Fmt.hm(iso) + ")";
        }
        texts.addView(Ui.text(this, since, 13, Ui.MUTED, false));
        if ("nosignal".equals(state)) {
            texts.addView(Ui.text(this, "Mașina nu are internet; drumul se înregistrează și apare întreg când revine semnalul.",
                    13, Ui.MUTED, false));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
        JSONObject d = live.optJSONObject("data");
        if (d != null) {
            // Unitatile in eticheta: patru casute inguste, iar „1846 rpm” nu incapea pe un rand.
            LinearLayout kpis = Ui.kpiRow(this,
                    Ui.kpi(this, d.isNull("speed") ? "—" : String.valueOf(Math.round(d.optDouble("speed"))), "", "viteză, km/h"),
                    Ui.kpi(this, d.isNull("rpm") ? "—" : String.valueOf(d.optInt("rpm")), "", "turație, rpm"),
                    Ui.kpi(this, d.isNull("volt") ? "—" : Fmt.num(d.optDouble("volt"), 1), "", "baterie, V"),
                    Ui.kpi(this, d.isNull("temp") ? "—" : Fmt.num(d.optDouble("temp"), 1), "", "afară, °C"));
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-1, -2);
            klp.topMargin = dp(this, 12);
            card.addView(kpis, klp);
            JSONObject cl = d.optJSONObject("climate");
            if (cl != null) {
                TextView line = Ui.text(this, "❄ Climatronic  " + climateLine(cl), 14, Ui.TEXT, false);
                line.setPadding(0, dp(this, 10), 0, 0);
                card.addView(line);
            }
            renderCarDetails(card, d);
        }
        JSONObject health = live.optJSONObject("unitHealth");
        if (health != null) {
            // Navigatia pe scurt; atinsa, duce la fila Navigatie.
            TextView line = UnitCard.healthLine(this, health, true);
            line.setPadding(0, dp(this, 10), 0, 0);
            line.setOnClickListener(v -> openTab(UNIT));
            card.addView(line);
        }

        JSONObject trip = live.optJSONObject("trip");
        if (trip != null) {
            tripSummary(trip, "Călătoria în curs · de " + Fmt.duration(trip.optDouble("durationMin")));
            renderLiveMap();
        }

        JSONObject tank = live.optJSONObject("tank");
        if (tank != null) {
            JSONObject range = live.optJSONObject("range");
            LinearLayout fc = Ui.card(this, content);
            fc.addView(Ui.text(this, "⛽ " + Fmt.num(tank.optDouble("liters"), 0) + " L"
                    + (range == null ? "" : " · ≈ " + range.optInt("km") + " km autonomie"), 17, Ui.TEXT, true));
            Ui.hint(this, fc, (range == null ? "" : "la " + Fmt.num(range.optDouble("lPer100"), 1) + " L/100 "
                    + (range.optBoolean("real") ? "(real, din rezervor)" : "(estimat din drumuri)") + ", până la gol · ")
                    + "citit " + Fmt.ago(tank.optString("at")));
        }

        JSONObject pos = live.optJSONObject("position");
        // In timpul calatoriei, masina se vede pe harta de mai sus.
        if (pos != null && trip == null) {
            LinearLayout pc = Ui.card(this, content);
            LinearLayout prow = new LinearLayout(this);
            prow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout pt = new LinearLayout(this);
            pt.setOrientation(LinearLayout.VERTICAL);
            String parked = pos.isNull("place") ? "Mașina e parcată aici"
                    : "Mașina e parcată la " + pos.optString("place");
            pt.addView(Ui.text(this, "driving".equals(state) ? "Mașina e aici" : parked, 17, Ui.TEXT, true));
            pt.addView(Ui.text(this, "ultima poziție: " + Fmt.day(pos.optString("t")) + ", "
                    + Fmt.hm(pos.optString("t")) + " (" + Fmt.ago(pos.optString("t")) + ")", 13, Ui.MUTED, false));
            prow.addView(pt, new LinearLayout.LayoutParams(0, -2, 1));
            double lat = pos.optDouble("lat"), lon = pos.optDouble("lon");
            TextView gm = Ui.text(this, "Google Maps", 15, Ui.ACCENT, true);
            gm.setPadding(dp(this, 10), dp(this, 8), 0, dp(this, 8));
            gm.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=" + lat + "," + lon))));
            prow.addView(gm);
            pc.addView(prow);
            addMap(content, MapBox.position(this, lat, lon), 220);
        }

    }

    /** Fila Navigatie: performanta navigatiei Teyes, din aceeasi stare live ca Acum. */
    private void renderUnit() {
        if (live == null) {
            Ui.hint(this, content, "Se încarcă...");
            return;
        }
        JSONObject unit = live.optJSONObject("unit");
        if (unit == null) {
            Ui.hint(this, Ui.card(this, content), "Nicio măsurare încă: apare de la FaikkitCar 1.1.62, cât navigația e trează.");
            return;
        }
        UnitCard.render(this, content, unit, live.optJSONObject("unitHealth"), unitHistory);
        loadUnitHistory();
    }

    // Istoricul performantei navigatiei (graficele de pe Acum), cerut la cel mult un minut.
    private JSONArray unitHistory;
    private long unitHistoryAt;
    private boolean unitHistoryBusy;

    private void loadUnitHistory() {
        if (unitHistoryBusy || System.currentTimeMillis() - unitHistoryAt < 60_000) return;
        unitHistoryBusy = true;
        io.execute(() -> {
            JSONArray h = null;
            try {
                h = api.unitHistory(60);
            } catch (Exception ignored) {
            }
            JSONArray got = h;
            ui.post(() -> {
                unitHistoryBusy = false;
                unitHistoryAt = System.currentTimeMillis();
                if (got != null) unitHistory = got;
                render();
            });
        });
    }

    /** Harta calatoriei in curs (aceeasi MapView, mutata in pagina noua) si „Urmareste masina”. */
    private void renderLiveMap() {
        MapBox.LiveRoute m = liveMap();
        if (m.map.getParent() != null) ((ViewGroup) m.map.getParent()).removeView(m.map);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(this, 300));
        lp.bottomMargin = dp(this, m.follow ? 14 : 4);
        content.addView(m.map, lp);
        if (m.tripStart == null) Ui.hint(this, content, "Aștept poziția GPS...");
        if (!m.follow) {
            TextView f = Ui.text(this, "◎ Urmărește mașina", 15, Ui.ACCENT, true);
            f.setPadding(0, dp(this, 6), 0, dp(this, 14));
            f.setOnClickListener(v -> {
                m.fit();
                shown = "";
                render();
            });
            content.addView(f);
        }
    }

    /**
     * Propunerile: o alimentare vazuta in rezervor, dar nescrisa in jurnal, si locurile unde
     * parchezi des. „Da” deschide formularul precompletat, „Nu” o ascunde de tot.
     */
    private void renderSuggestions() {
        if (suggestions == null || suggestions.length() == 0) return;
        LinearLayout card = Ui.section(this, content, "Propuneri");
        card.setBackground(Ui.round(this, 0x1438BDF8, 18));
        for (int i = 0; i < suggestions.length(); i++) {
            JSONObject s = suggestions.optJSONObject(i);
            boolean refuel = "refuel".equals(s.optString("kind"));
            String text = refuel
                    ? "⛽ Ai alimentat pe " + Fmt.day(s.optString("at")) + ", " + Fmt.hm(s.optString("at"))
                            + "? Rezervorul a crescut cu ~" + Fmt.num(s.optDouble("liters"), 0) + " L."
                    : "📍 Parchezi des aici" + (s.isNull("label") ? "" : ": " + s.optString("label"))
                            + " (" + s.optInt("visits") + " ori). Îl salvezi ca loc?";
            TextView t = Ui.text(this, text, 15, Ui.TEXT, false);
            t.setPadding(0, dp(this, 10), 0, 0);
            card.addView(t);
            Runnable yes = refuel ? () -> {
                try {
                    refuelDialog(new JSONObject().put("at", s.optString("at")).put("liters", s.optDouble("liters")));
                } catch (Exception ignored) {
                }
            } : () -> openPlaceEditor(null, "", s.optDouble("lat"), s.optDouble("lon"), 100);
            card.addView(actions(new String[] {refuel ? "Da, completez bonul" : "Salvează ca loc", "Nu"},
                    new int[] {Ui.ACCENT, Ui.MUTED},
                    new Runnable[] {yes, () -> mutate(() -> api.dismissSuggestion(s), "Ascuns")}));
        }
    }

    /** Clima intr-un rand: „AUTO · AC oprit · 21 °C · ventilator 2”. */
    private static String climateLine(JSONObject cl) {
        // Ventilatorul pe 0 = climatronicul oprit (indicatorul AUTO ramane pe 1 si atunci).
        if (cl.has("on") && !cl.isNull("on") && !cl.optBoolean("on")) return "oprit";
        List<String> parts = new ArrayList<>();
        if (!cl.isNull("auto")) parts.add(cl.optBoolean("auto") ? "AUTO" : "manual");
        if (!cl.isNull("ac")) parts.add(cl.optBoolean("ac") ? "AC pornit" : "AC oprit");
        boolean hasL = !cl.isNull("setLeft"), hasR = !cl.isNull("setRight");
        if (hasL || hasR) {
            double l = cl.optDouble("setLeft", cl.optDouble("setRight")), r = cl.optDouble("setRight", l);
            parts.add(l == r ? Fmt.num(l, 1) + " °C" : Fmt.num(l, 1) + " / " + Fmt.num(r, 1) + " °C");
        }
        if (!cl.isNull("fan")) parts.add(cl.optInt("fan") == 0 ? "ventilator oprit" : "ventilator " + cl.optInt("fan"));
        return String.join(" · ", parts);
    }

    /** Usile, centura, frana de mana, marsarierul, luminile: doar ce e de semnalat. */
    private void renderCarDetails(LinearLayout card, JSONObject d) {
        List<String> warn = new ArrayList<>(), ok = new ArrayList<>();
        JSONArray doors = d.optJSONArray("doorsOpen");
        if (doors != null && doors.length() > 0) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < doors.length(); i++) names.add(doors.optString(i));
            warn.add("Deschis: " + String.join(", ", names));
        }
        if (!d.isNull("handbrake")) {
            if (d.optBoolean("handbrake")) ok.add("frâna de mână trasă");
            else if (d.optDouble("speed", 0) < 1) warn.add("Frâna de mână e eliberată");
        }
        if (!d.isNull("belt")) {
            if (!d.optBoolean("belt") && d.optDouble("speed", 0) >= 5) warn.add("Centura șoferului nu e pusă");
            else if (d.optBoolean("belt")) ok.add("centura pusă");
        }
        if (d.optBoolean("reverse")) warn.add("În marșarier");
        for (String w : warn) {
            TextView t = Ui.text(this, "⚠ " + w, 15, Ui.WARN, true);
            t.setPadding(0, dp(this, 8), 0, 0);
            card.addView(t);
        }
        if (!ok.isEmpty()) Ui.hint(this, card, String.join(" · ", ok));
    }

    // ---------------------------------------------------------------- fila Calatorii

    private List<JSONObject> visibleTrips() {
        List<JSONObject> out = new ArrayList<>();
        if (trips == null) return out;
        boolean hide = Store.hideIdle(this);
        for (int i = 0; i < trips.length(); i++) {
            JSONObject t = trips.optJSONObject(i);
            if (!hide || !t.optBoolean("idle")) out.add(t);
        }
        return out;
    }

    private void renderTrips() {
        if (trips == null) {
            Ui.hint(this, content, "Se încarcă...");
            return;
        }
        // Lista e de la cea mai noua: vecina mai veche e la i + 1, cea mai noua la i - 1.
        for (int i = 0; i < trips.length(); i++) {
            JSONObject t = trips.optJSONObject(i);
            if (t.optString("start").equals(selected)) {
                renderTrip(t, trips.optJSONObject(i + 1), i > 0 ? trips.optJSONObject(i - 1) : null);
                return;
            }
        }
        renderTripList(visibleTrips());
    }

    // Culorile timpului unei calatorii, ca pe site: mers, trafic, stationare, motor oprit.
    private static final int[] TIME_COLORS = {Ui.ACCENT, 0xFFFB923C, 0xFF94A3B8, 0xFFFCD34D};

    /**
     * Antetul unei calatorii (si al celei in curs, pe fila Acum): titlul, traseul, cifrele mari
     * (distanta, durata, costul) si bara timpului cu legenda.
     */
    private LinearLayout tripSummary(JSONObject t, String title) {
        LinearLayout card = Ui.card(this, content);
        card.addView(Ui.text(this, title, 13, Ui.MUTED, false));
        if (!Fmt.route(t).isEmpty()) card.addView(Ui.text(this, Fmt.route(t), 19, Ui.TEXT, true));
        View third = t.isNull("cost")
                ? Ui.kpi(this, t.isNull("fuelL") ? "—" : Fmt.liters(t.optDouble("fuelL")), "", "combustibil")
                : Ui.kpi(this, Fmt.lei(t.optDouble("cost")).replace(" lei", ""), "lei",
                        "≈ " + Fmt.liters(t.optDouble("fuelL", 0)));
        LinearLayout kpis = Ui.kpiRow(this,
                Ui.kpi(this, Fmt.num(t.optDouble("distanceKm"), 1), "km", "distanță"),
                Ui.kpi(this, Fmt.duration(t.optDouble("durationMin")), "", "durată"),
                third);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-1, -2);
        klp.topMargin = dp(this, 10);
        card.addView(kpis, klp);
        double[] v = {t.optDouble("movingMin", 0), t.optDouble("trafficMin", 0), t.optDouble("standMin", 0),
                t.optDouble("stopMin", 0)};
        int stops = t.optInt("trafficStops");
        String[] legend = {"În mers " + Fmt.shortDuration(v[0]),
                "Trafic " + Fmt.shortDuration(v[1]) + (stops > 0 ? " · " + stops + " opriri" : ""),
                "Staționare " + Fmt.shortDuration(v[2]), "Motor oprit " + Fmt.shortDuration(v[3])};
        LinearLayout bar = Ui.stackedBar(this, v, TIME_COLORS, legend);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(this, 12);
        card.addView(bar, blp);
        return card;
    }

    private static String kmh(JSONObject t, String key) {
        return t.isNull(key) || !t.has(key) ? "—" : t.optInt(key) + " km/h";
    }

    /** Detaliile pe teme: viteza, consumul, masina. Doar ce avem (drumurile vechi au mai putine). */
    private void tripSections(JSONObject t) {
        LinearLayout speed = Ui.section(this, content, "Viteză");
        List<View> sc = new ArrayList<>();
        sc.add(Ui.info(this, "Medie", kmh(t, "avgSpeed")));
        sc.add(Ui.info(this, "În mișcare", kmh(t, "movingAvgSpeed")));
        sc.add(Ui.info(this, "Maximă", kmh(t, "maxSpeed")));
        sc.add(Ui.info(this, "Turație max", t.isNull("maxRpm") ? "—" : t.optInt("maxRpm") + " rpm"));
        if (t.optInt("trafficStops") > 0) {
            sc.add(Ui.info(this, "Cea mai lungă în trafic", Fmt.shortDuration(t.optDouble("trafficMaxMin"))));
        }
        speed.addView(Ui.grid(this, sc, 3));

        LinearLayout fuelCard = Ui.section(this, content, "Consum");
        List<View> fc = new ArrayList<>();
        fc.add(Ui.info(this, "Combustibil (est.)", t.isNull("fuelL") ? "—" : "≈ " + Fmt.liters(t.optDouble("fuelL"))));
        fc.add(Ui.info(this, "Consum (est.)", t.isNull("lPer100") ? "—" : Fmt.num(t.optDouble("lPer100"), 1) + " L/100"));
        fc.add(Ui.info(this, "Cost (est.)", t.isNull("cost") ? "—" : Fmt.lei(t.optDouble("cost"))));
        if (!t.isNull("fuelStart") && !t.isNull("fuelEnd")) {
            fc.add(Ui.info(this, "Rezervor", Fmt.num(t.optDouble("fuelStart"), 0) + " → "
                    + Fmt.num(t.optDouble("fuelEnd"), 0) + " L"));
        }
        fuelCard.addView(Ui.grid(this, fc, 3));

        LinearLayout car = Ui.section(this, content, "Mașina");
        List<View> cc = new ArrayList<>();
        // Afara la plecare → la sosire, daca s-a schimbat.
        String temp = t.isNull("tempC") ? "—" : (!t.isNull("tempStartC") && t.optDouble("tempStartC") != t.optDouble("tempC")
                ? Fmt.num(t.optDouble("tempStartC"), 1) + " → " : "") + Fmt.num(t.optDouble("tempC"), 1) + " °C";
        cc.add(Ui.info(this, "Afară", temp));
        if (t.has("acMin") && !t.isNull("acMin")) {
            double ac = t.optDouble("acMin");
            double drive = Math.max(t.optDouble("durationMin") - t.optDouble("stopMin"), 0.1);
            cc.add(Ui.info(this, "AC", ac > 0 ? Fmt.shortDuration(ac) + " (" + Math.round(ac / drive * 100) + "%)" : "oprit"));
        }
        if (t.has("noBeltMin") && !t.isNull("noBeltMin") && t.optDouble("noBeltMin") > 0) {
            cc.add(Ui.info(this, "Fără centură", Fmt.shortDuration(t.optDouble("noBeltMin")) + " ⚠"));
        }
        // La pornire (demarorul) sub 9,6 V = baterie slaba; in mers (incarcarea) normal 13,5–14,9 V.
        double crank = t.optDouble("crankVolt", Double.NaN), run = t.optDouble("runVolt", Double.NaN);
        cc.add(Ui.info(this, "Baterie la pornire", Double.isNaN(crank) ? "—"
                : Fmt.num(crank, 2) + " V" + (crank < 9.6 ? " ⚠" : "")));
        cc.add(Ui.info(this, "Baterie în mers", Double.isNaN(run) ? "—"
                : Fmt.num(run, 2) + " V" + (run < 13.5 || run > 14.9 ? " ⚠" : "")));
        cc.add(Ui.info(this, "Kilometraj", t.isNull("odoEnd") ? "—" : Fmt.km(t.optLong("odoEnd"))));
        car.addView(Ui.grid(this, cc, 3));
    }

    private void renderTrip(JSONObject t, JSONObject older, JSONObject newer) {
        String start = t.optString("start"), end = t.optString("end");
        TextView back = Ui.text(this, "‹ Toate călătoriile", 16, Ui.ACCENT, true);
        back.setPadding(0, dp(this, 4), 0, dp(this, 12));
        back.setOnClickListener(v -> {
            selected = null;
            render();
        });
        content.addView(back);
        int parts = t.optInt("parts", 1);
        tripSummary(t, Fmt.day(start) + " · " + Fmt.hm(start) + "–" + Fmt.hm(end)
                + (parts > 1 ? " · " + parts + " părți" : ""));
        // Plecarea sau sosirea intr-un loc nesalvat: se salveaza direct de aici (ex. „Frizerie”).
        JSONArray endPos = t.optJSONArray("endPos"), startPos = t.optJSONArray("startPos");
        boolean saveEnd = t.isNull("toPlace") && endPos != null, saveStart = t.isNull("fromPlace") && startPos != null;
        if (saveEnd || saveStart) {
            LinearLayout sp = Ui.card(this, content);
            if (saveEnd) {
                TextView b = Ui.text(this, "📍 Salvează sosirea ca loc", 15, Ui.ACCENT, true);
                b.setPadding(0, dp(this, 6), 0, dp(this, 6));
                b.setOnClickListener(v -> openPlaceEditor(null, "", endPos.optDouble(0), endPos.optDouble(1), 100));
                sp.addView(b);
            }
            if (saveStart) {
                TextView b = Ui.text(this, "📍 Salvează plecarea ca loc", 15, Ui.ACCENT, true);
                b.setPadding(0, dp(this, 6), 0, dp(this, 6));
                b.setOnClickListener(v -> openPlaceEditor(null, "", startPos.optDouble(0), startPos.optDouble(1), 100));
                sp.addView(b);
            }
        }

        String key = start + "|" + end;
        if (!key.equals(pointsFor)) {
            Ui.hint(this, content, "Se încarcă traseul...");
            io.execute(() -> {
                try {
                    JSONArray p = api.tripPoints(start, end);
                    ui.post(() -> {
                        points = p;
                        pointsFor = key;
                        render();
                    });
                } catch (Exception ignored) {
                }
            });
            return;
        }
        JSONArray stops = t.optJSONArray("stops");
        MapView map = MapBox.route(this, points, stops);
        if (map != null) addMap(content, map, 260);
        if (stops != null && stops.length() > 0) {
            LinearLayout sc = Ui.section(this, content, "Opriri cu motorul oprit (galben pe hartă)");
            for (int i = 0; i < stops.length(); i++) {
                JSONObject s = stops.optJSONObject(i);
                TextView st = Ui.text(this, Fmt.hm(s.optString("from")) + "–" + Fmt.hm(s.optString("to"))
                        + " · " + Fmt.duration(s.optDouble("minutes"))
                        + (s.isNull("place") ? "" : " · " + s.optString("place")), 15, Ui.TEXT, false);
                st.setPadding(0, dp(this, 6), 0, 0);
                sc.addView(st);
            }
        }
        tripSections(t);
        // Combinarea cu vecinele (ex. dus-intors cu o oprire scurta) si despartirea.
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (older != null) {
            String from = older.optString("start");
            labels.add("Combină cu precedenta (" + Fmt.hm(from) + ")");
            actions.add(() -> joinAndShow(() -> api.joinTrips(from, end), from, "Călătorii combinate"));
        }
        if (newer != null) {
            String to = newer.optString("end");
            labels.add("Combină cu următoarea (" + Fmt.hm(newer.optString("start")) + ")");
            actions.add(() -> joinAndShow(() -> api.joinTrips(start, to), start, "Călătorii combinate"));
        }
        if (parts > 1) {
            labels.add("Desparte (" + parts + " părți)");
            actions.add(() -> joinAndShow(() -> api.splitTrip(start), start, "Călătorie despărțită"));
        }
        if (!labels.isEmpty()) {
            LinearLayout ac = Ui.card(this, content);
            for (int i = 0; i < labels.size(); i++) {
                Runnable action = actions.get(i);
                TextView b = Ui.text(this, labels.get(i), 15, Ui.ACCENT, true);
                b.setPadding(0, dp(this, 6), 0, dp(this, 6));
                b.setOnClickListener(v -> action.run());
                ac.addView(b);
            }
        }
    }

    /** Combina / desparte, apoi arata calatoria care incepe la `start`. */
    private void joinAndShow(Call call, String start, String ok) {
        io.execute(() -> {
            try {
                call.run();
                JSONArray t = api.trips();
                ui.post(() -> {
                    trips = t;
                    selected = start;
                    pointsFor = null;
                    toast(ok);
                    render();
                });
            } catch (Api.Unauthorized e) {
                ui.post(this::logout);
            } catch (Exception e) {
                ui.post(() -> toast(e.getMessage()));
            }
        });
    }

    private void renderTripList(List<JSONObject> list) {
        int idle = 0;
        for (int i = 0; i < trips.length(); i++) if (trips.optJSONObject(i).optBoolean("idle")) idle++;
        LinearLayout card = Ui.card(this, content);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Călătorii", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (idle > 0) {
            boolean hide = Store.hideIdle(this);
            TextView toggle = Ui.text(this, (hide ? "Arată pornirile pe loc (" : "Ascunde pornirile pe loc (")
                    + idle + ")", 14, Ui.ACCENT, false);
            toggle.setOnClickListener(v -> {
                Store.setHideIdle(this, !hide);
                render();
            });
            head.addView(toggle);
        }
        card.addView(head);
        if (list.isEmpty()) {
            Ui.hint(this, card, "Nicio călătorie încă. Aplicația FaikkitCar trimite traseul automat când mașina merge.");
            return;
        }
        String newer = null;
        for (JSONObject t : list) {
            String start = t.optString("start");
            addGapRows(card, t.optString("end"), newer);
            newer = start;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Ui.round(this, Ui.CARD2, 14));
            int p = dp(this, 12);
            row.setPadding(p, p, p, p);
            LinearLayout top = new LinearLayout(this);
            top.addView(Ui.text(this, Fmt.day(start) + " · " + Fmt.hm(start) + "–" + Fmt.hm(t.optString("end")),
                    16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(Ui.text(this, Fmt.num(t.optDouble("distanceKm"), 1) + " km", 15, Ui.ACCENT, true));
            row.addView(top);
            if (!Fmt.route(t).isEmpty()) row.addView(Ui.text(this, Fmt.route(t), 14, Ui.ACCENT, false));
            StringBuilder sub = new StringBuilder(t.optBoolean("idle") ? "pe loc · " : "");
            if (t.optInt("parts", 1) > 1) {
                sub.append(t.optInt("parts")).append(" părți, oprire ")
                        .append(Fmt.duration(t.optDouble("stopMin"))).append(" · ");
            }
            sub.append(Fmt.duration(t.optDouble("durationMin")));
            if (!t.isNull("avgSpeed")) sub.append(" · medie ").append(t.optInt("avgSpeed")).append(" km/h");
            if (!t.isNull("maxSpeed")) sub.append(" · max ").append(t.optInt("maxSpeed")).append(" km/h");
            if (!t.isNull("fuelL")) sub.append(" · ≈ ").append(Fmt.liters(t.optDouble("fuelL")));
            if (!t.isNull("cost")) sub.append(" · ").append(Fmt.lei(t.optDouble("cost")));
            row.addView(Ui.text(this, sub.toString(), 13, Ui.MUTED, false));
            row.setOnClickListener(v -> {
                selected = start;
                render();
                scroll.post(() -> scroll.scrollTo(0, 0));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(this, 8);
            card.addView(row, lp);
        }
    }

    /** Golurile de kilometraj (aplicatia din masina oprita); un server vechi = niciunul. */
    private JSONArray gapsOrEmpty() {
        try {
            return api.gaps();
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Drumurile neinregistrate dintre sosirea `after` si plecarea `before` (null = oricand). */
    private void addGapRows(LinearLayout card, String after, String before) {
        if (gaps == null) return;
        for (int i = 0; i < gaps.length(); i++) {
            JSONObject g = gaps.optJSONObject(i);
            String from = g.optString("after"), to = g.optString("before");
            if (from.compareTo(after) < 0 || (before != null && to.compareTo(before) > 0)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = Ui.round(this, Ui.CARD, 14);
            bg.setStroke(dp(this, 1), Ui.WARN, dp(this, 6), dp(this, 4));
            row.setBackground(bg);
            int p = dp(this, 12);
            row.setPadding(p, p, p, p);
            LinearLayout top = new LinearLayout(this);
            top.addView(Ui.text(this, "Drum neînregistrat", 16, Ui.WARN, true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(Ui.text(this, "~" + g.optInt("km") + " km", 15, Ui.WARN, true));
            row.addView(top);
            if (!Fmt.route(g).isEmpty()) row.addView(Ui.text(this, Fmt.route(g), 14, Ui.ACCENT, false));
            boolean sameDay = Fmt.day(from).equals(Fmt.day(to));
            StringBuilder sub = new StringBuilder("între ").append(Fmt.day(from)).append(' ')
                    .append(Fmt.hm(from)).append(" și ").append(sameDay ? "" : Fmt.day(to) + " ")
                    .append(Fmt.hm(to)).append(" · aplicația era oprită, se știe doar din kilometraj");
            if (!g.isNull("fuelL")) sub.append(" · ≈ ").append(Fmt.liters(g.optDouble("fuelL")));
            if (!g.isNull("cost")) sub.append(" · ").append(Fmt.lei(g.optDouble("cost")));
            row.addView(Ui.text(this, sub.toString(), 13, Ui.MUTED, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(this, 8);
            card.addView(row, lp);
        }
    }

    // ---------------------------------------------------------------- fila Costuri

    private void renderCosts() {
        if (stats == null || fuel == null) {
            Ui.hint(this, content, "Se încarcă...");
            return;
        }
        renderMonthReport(stats.optJSONArray("months"));
        renderCommute(stats.optJSONObject("commute"));
        renderRoutes(stats.optJSONArray("routes"));
        renderStats(stats.optJSONObject("last30"));
        renderMonthly(stats.optJSONArray("months"));
        renderFuel();
    }

    /**
     * Raportul lunii: luna curenta fata de cea trecuta (intreaga), cu diferentele in procente,
     * cea mai lunga calatorie si locurile unde ai ajuns cel mai des.
     */
    private void renderMonthReport(JSONArray months) {
        if (months == null || months.length() == 0) return;
        JSONObject cur = months.optJSONObject(0), prev = months.optJSONObject(1);
        LinearLayout card = Ui.section(this, content, "Raportul lunii");
        card.addView(Ui.text(this, Fmt.month(cur.optString("month")), 19, Ui.TEXT, true));
        if (prev != null) {
            card.addView(Ui.text(this, "față de " + Fmt.month(prev.optString("month")).toLowerCase(new java.util.Locale("ro", "RO"))
                    + " (întreagă)", 12, Ui.MUTED, false));
        }
        List<View> cells = new ArrayList<>();
        cells.add(reportCell("Distanță", Fmt.num(cur.optDouble("km"), 1) + " km", cur, prev, "km", false));
        cells.add(reportCell("Călătorii", String.valueOf(cur.optInt("trips")), cur, prev, "trips", false));
        cells.add(reportCell("Timp la volan", Fmt.duration(cur.optDouble("minutes")), cur, prev, "minutes", false));
        cells.add(reportCell("Oprit în trafic", Fmt.duration(cur.optDouble("trafficMin")), cur, prev, "trafficMin", true));
        cells.add(reportCell("Combustibil", "≈ " + Fmt.liters(cur.optDouble("liters")), cur, prev, "liters", true));
        cells.add(reportCell("Consum", cur.isNull("lPer100") ? "—" : Fmt.num(cur.optDouble("lPer100"), 1) + " L/100",
                cur, prev, "lPer100", true));
        cells.add(reportCell("Cost", cur.isNull("cost") ? "—" : Fmt.lei(cur.optDouble("cost")), cur, prev, "cost", true));
        cells.add(reportCell("Staționare (motor pornit)", Fmt.duration(cur.optDouble("standMin"))
                + (cur.isNull("standCost") ? "" : " · " + Fmt.lei(cur.optDouble("standCost"))), cur, prev, "standMin", true));
        if (!cur.isNull("serviceLei") && cur.has("serviceLei")) {
            cells.add(Ui.info(this, "Service", Fmt.lei(cur.optDouble("serviceLei"))));
        }
        card.addView(Ui.grid(this, cells, 2));
        JSONObject longest = cur.optJSONObject("longest");
        if (longest != null) {
            String route = longest.isNull("from") && longest.isNull("to") ? "" : " · "
                    + (longest.isNull("from") ? "…" : longest.optString("from")) + " → "
                    + (longest.isNull("to") ? "…" : longest.optString("to"));
            Ui.hint(this, card, "Cea mai lungă: " + Fmt.num(longest.optDouble("km"), 1) + " km, "
                    + Fmt.day(longest.optString("start")) + route);
        }
        JSONArray top = cur.optJSONArray("topPlaces");
        if (top != null && top.length() > 0) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < top.length(); i++) {
                JSONObject p = top.optJSONObject(i);
                names.add(p.optString("name") + " (" + p.optInt("visits") + ")");
            }
            Ui.hint(this, card, "Cel mai des: " + String.join(", ", names));
        }
    }

    /** „azi”, „mâine” sau ziua. */
    private static String dayWord(String iso) {
        String d = Fmt.date(iso);
        long now = System.currentTimeMillis();
        if (d.equals(Fmt.date(java.time.Instant.ofEpochMilli(now).toString()))) return "azi";
        if (d.equals(Fmt.date(java.time.Instant.ofEpochMilli(now + 86_400_000L).toString()))) return "mâine";
        return Fmt.day(iso);
    }

    /**
     * Drumul la serviciu pe ture (zi 08–20, noapte 20–08, doua zile libere): urmatoarea tura cu
     * minutul pana la care sa pleci (15 min rezerva) si, pe fiecare tura, cum pleci de obicei.
     */
    private void renderCommute(JSONObject c) {
        if (c == null) return;
        int margin = c.optInt("marginMin");
        String work = c.optString("work").toLowerCase(java.util.Locale.ROOT);
        LinearLayout card = Ui.section(this, content, "La " + work + " (ture 12/24, 90 de zile)");
        JSONArray shifts = c.optJSONArray("shifts");
        JSONArray next = c.optJSONArray("next");
        JSONObject nx = null;
        for (int i = 0; next != null && i < next.length(); i++) {
            JSONObject n = next.optJSONObject(i);
            if (Fmt.millis(n.optString("start")) > System.currentTimeMillis()) { nx = n; break; }
        }
        JSONObject nxShift = null;
        for (int i = 0; nx != null && shifts != null && i < shifts.length(); i++) {
            if (shifts.optJSONObject(i).optString("name").equals(nx.optString("shift"))) nxShift = shifts.optJSONObject(i);
        }
        int p = dp(this, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 8);
        if (nx != null && nxShift != null) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(Ui.round(this, 0x2222C55E, 14));
            box.setPadding(p, p, p, p);
            box.addView(Ui.text(this, "Următoarea tură: " + dayWord(nx.optString("start")) + ", "
                    + nxShift.optString("name").replace("Tura de ", "") + " " + Fmt.hm(nx.optString("start")), 14, Ui.MUTED, false));
            JSONObject tw = nxShift.optJSONObject("toWork");
            if (!nx.isNull("leaveBy") && tw != null) {
                String leave = nx.optString("leaveBy");
                box.addView(Ui.text(this, "Pleacă până la " + Fmt.hm(leave), 22, Ui.OK, true));
                String why = "ca să fii acolo cu " + margin + " min înainte (drumul: ~" + Fmt.duration(tw.optDouble("driveMin"))
                        + ", în zilele proaste " + Fmt.duration(tw.optDouble("safeMin")) + ")";
                long warm = Math.round(tw.optDouble("warmupMin"));
                if (warm >= 1) {
                    why += "; cu încălzirea de ~" + warm + " min, pornește motorul la "
                            + Fmt.hm(java.time.Instant.ofEpochMilli(Fmt.millis(leave) - warm * 60_000L).toString());
                }
                box.addView(Ui.text(this, why, 12, Ui.MUTED, false));
            } else {
                box.addView(Ui.text(this, "Ora de plecare apare după câteva drumuri la tura asta.", 12, Ui.MUTED, false));
            }
            card.addView(box, lp);
        }
        for (int i = 0; shifts != null && i < shifts.length(); i++) {
            JSONObject s = shifts.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Ui.round(this, Ui.CARD2, 14));
            row.setPadding(p, p, p, p);
            LinearLayout top = new LinearLayout(this);
            top.addView(Ui.text(this, s.optString("name"), 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(Ui.text(this, s.optString("start") + "–" + s.optString("end"), 13, Ui.MUTED, false));
            row.addView(top);
            JSONObject tw = s.optJSONObject("toWork");
            if (tw != null) {
                if (!tw.isNull("leaveBy")) {
                    row.addView(Ui.text(this, "Pleacă până la " + tw.optString("leaveBy") + " (drum ~"
                            + Fmt.duration(tw.optDouble("driveMin")) + ", cel mult " + Fmt.duration(tw.optDouble("safeMin")) + ")",
                            14, Ui.OK, false));
                }
                row.addView(Ui.text(this, "De obicei pleci la " + tw.optString("usualLeave") + " și ajungi la "
                        + tw.optString("usualArrive"), 13, Ui.MUTED, false));
                int tight = tw.optInt("tight"), late = tw.optInt("late");
                if (tight > 0) {
                    row.addView(Ui.text(this, "De " + tight + " din " + tw.optInt("trips") + " ori cu sub " + margin
                            + " min înainte" + (late > 0 ? ", " + late + (late == 1 ? " întârziere" : " întârzieri") : ""),
                            13, Ui.WARN, false));
                }
            } else {
                row.addView(Ui.text(this, "Încă niciun drum spre " + work + " la tura asta.", 12, Ui.MUTED, false));
            }
            JSONObject fw = s.optJSONObject("fromWork");
            if (fw != null) {
                row.addView(Ui.text(this, "Înapoi: pleci pe la " + fw.optString("usualLeave") + ", ești "
                        + c.optString("home").toLowerCase(java.util.Locale.ROOT) + " pe la " + fw.optString("usualArrive")
                        + " (~" + Fmt.duration(fw.optDouble("driveMin")) + ")", 13, Ui.MUTED, false));
            }
            card.addView(row, lp);
        }
    }

    /** Drumurile pe care le faci des (de la 10 deplasari, intre doua locuri salvate): costul si durata medie. */
    private void renderRoutes(JSONArray routes) {
        if (routes == null || routes.length() == 0) return;
        LinearLayout card = Ui.section(this, content, "Drumurile tale (90 de zile)");
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Ui.round(this, Ui.CARD2, 14));
            int p = dp(this, 12);
            row.setPadding(p, p, p, p);
            LinearLayout top = new LinearLayout(this);
            top.addView(Ui.text(this, r.optString("from") + " → " + r.optString("to"), 16, Ui.TEXT, true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(Ui.text(this, r.optInt("trips") + " drumuri", 13, Ui.MUTED, false));
            row.addView(top);
            StringBuilder sb = new StringBuilder("~" + Fmt.num(r.optDouble("km"), 1) + " km · " + Fmt.duration(r.optDouble("minutes")));
            if (r.optDouble("trafficMin") > 0) sb.append(" (").append(Fmt.duration(r.optDouble("trafficMin"))).append(" în trafic)");
            if (!r.isNull("cost")) sb.append(" · ").append(Fmt.lei(r.optDouble("cost"))).append(" pe drum");
            row.addView(Ui.text(this, sb.toString(), 14, Ui.TEXT, false));
            if (!r.isNull("monthCost")) row.addView(Ui.text(this, "Luna aceasta: " + Fmt.lei(r.optDouble("monthCost")), 13, Ui.MUTED, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(this, 8);
            card.addView(row, lp);
        }
    }

    /** O valoare din raport, cu diferenta fata de luna trecuta (verde = mai bine). */
    private View reportCell(String label, String value, JSONObject cur, JSONObject prev, String key, boolean lowerIsBetter) {
        LinearLayout box = Ui.info(this, label, value);
        if (prev == null || cur.isNull(key) || prev.isNull(key) || prev.optDouble(key) == 0) return box;
        long pct = Math.round((cur.optDouble(key) - prev.optDouble(key)) / prev.optDouble(key) * 100);
        int color = pct == 0 ? Ui.MUTED : (pct < 0) == lowerIsBetter ? Ui.OK : Ui.WARN;
        box.addView(Ui.text(this, (pct > 0 ? "▲ " : pct < 0 ? "▼ " : "= ") + Math.abs(pct) + "%", 12, color, true));
        return box;
    }

    /** Ultimele 30 de zile, calculate pe server (aceleasi cifre ca pe site). */
    private void renderStats(JSONObject s) {
        if (s == null) return;
        LinearLayout card = Ui.card(this, content);
        Ui.title(this, card, "Ultimele 30 de zile");
        List<View> cells = new ArrayList<>();
        cells.add(Ui.info(this, "Călătorii", String.valueOf(s.optInt("trips"))));
        cells.add(Ui.info(this, "Distanță", Math.round(s.optDouble("km")) + " km"));
        cells.add(Ui.info(this, "Timp la volan", Fmt.duration(s.optDouble("minutes"))));
        cells.add(Ui.info(this, "Combustibil (est.)", "≈ " + Fmt.liters(s.optDouble("liters"))));
        cells.add(Ui.info(this, "Consum (est.)", s.isNull("lPer100") ? "—" : Fmt.num(s.optDouble("lPer100"), 1) + " L/100"));
        cells.add(Ui.info(this, "Cost (est.)", s.isNull("cost") ? "—" : Fmt.lei(s.optDouble("cost"))));
        card.addView(Ui.grid(this, cells, 3));
        if (s.optDouble("trafficMin") > 0) {
            Ui.hint(this, card, "Din timpul la volan, " + Fmt.duration(s.optDouble("trafficMin")) + " oprit în trafic.");
        }
    }

    private void renderMonthly(JSONArray months) {
        if (months == null || months.length() == 0) return;
        double maxKm = 1, maxLei = 1;
        for (int i = 0; i < months.length(); i++) {
            JSONObject m = months.optJSONObject(i);
            maxKm = Math.max(maxKm, m.optDouble("km"));
            maxLei = Math.max(maxLei, m.optDouble("cost", 0));
        }
        LinearLayout card = Ui.card(this, content);
        Ui.title(this, card, "Pe luni");
        for (int i = 0; i < months.length(); i++) {
            JSONObject m = months.optJSONObject(i);
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, dp(this, 8), 0, dp(this, 4));
            LinearLayout top = new LinearLayout(this);
            top.addView(Ui.text(this, Fmt.month(m.optString("month")), 16, Ui.TEXT, true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(Ui.text(this, m.optInt("trips") + " călătorii · " + Fmt.duration(m.optDouble("minutes")),
                    13, Ui.MUTED, false));
            block.addView(top);
            block.addView(bar(m.optDouble("km") / maxKm, Ui.ACCENT, Fmt.num(m.optDouble("km"), 1) + " km"));
            block.addView(bar(m.optDouble("cost", 0) / maxLei, Ui.WARN,
                    m.isNull("cost") ? "—" : Fmt.lei(m.optDouble("cost")) + " (est.)"));
            StringBuilder sub = new StringBuilder("≈ " + Fmt.liters(m.optDouble("liters")));
            if (!m.isNull("lPer100")) sub.append(" · ").append(Fmt.num(m.optDouble("lPer100"), 1)).append(" L/100 km");
            if (m.optDouble("refuelLiters") > 0) {
                sub.append(" · alimentat ").append(Fmt.liters(m.optDouble("refuelLiters")));
                if (!m.isNull("refuelLei")) sub.append(" (").append(Fmt.lei(m.optDouble("refuelLei"))).append(")");
            }
            block.addView(Ui.text(this, sub.toString(), 13, Ui.MUTED, false));
            card.addView(block);
        }
    }

    /** O bara orizontala (fractie 0..1) cu eticheta in dreapta. */
    private View bar(double fraction, int color, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(this, 3), 0, dp(this, 3));
        LinearLayout track = new LinearLayout(this);
        track.setBackground(Ui.round(this, Ui.CARD2, 5));
        View fill = new View(this);
        fill.setBackground(Ui.round(this, color, 5));
        float f = (float) Math.max(0, Math.min(1, fraction));
        track.setWeightSum(1);
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(this, 10), f));
        row.addView(track, new LinearLayout.LayoutParams(0, dp(this, 10), 1));
        TextView t = Ui.text(this, label, 13, Ui.TEXT, false);
        t.setGravity(Gravity.END);
        row.addView(t, new LinearLayout.LayoutParams(dp(this, 120), -2));
        return row;
    }

    // ---------------------------------------------------------------- jurnal de service

    private static final int PICK_SERVICE_FILE = 7;
    // Fisierele alese in dialogul deschis (se trimit dupa ce se salveaza lucrarea).
    private final List<Uri> pendingFiles = new ArrayList<>();
    private TextView pendingLabel;

    /** Lucrarile facute la masina (ulei, piese, ITP...), cu bonurile atasate. */
    private void renderService() {
        LinearLayout card = Ui.card(this, content);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "🔧 Jurnal de service", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = Ui.text(this, "+ Adaugă", 15, Ui.ACCENT, true);
        add.setOnClickListener(v -> serviceDialog(null));
        head.addView(add);
        card.addView(head);
        if (service == null || service.length() == 0) {
            Ui.hint(this, card, "Nicio lucrare încă. Trece aici schimbul de ulei, piesele, ITP-ul, cu poza bonului.");
            return;
        }
        for (int i = 0; i < service.length(); i++) {
            JSONObject e = service.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Ui.round(this, Ui.CARD2, 14));
            int p = dp(this, 12);
            row.setPadding(p, p, p, p);
            LinearLayout top = new LinearLayout(this);
            top.addView(Ui.text(this, e.optString("title"), 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
            if (!e.isNull("cost")) {
                TextView tc = Ui.text(this, Fmt.lei(e.optDouble("cost")), 16, Ui.TEXT, true);
                tc.setPadding(dp(this, 8), 0, 0, 0);
                top.addView(tc);
            }
            row.addView(top);
            String when = LocalDate.parse(e.optString("date")).format(java.time.format.DateTimeFormatter
                    .ofPattern("EEEE, d MMMM yyyy", new java.util.Locale("ro", "RO")));
            TextView wt = Ui.text(this, "📅 " + when, 14, Ui.TEXT, false);
            wt.setPadding(0, dp(this, 6), 0, 0);
            row.addView(wt);
            if (!e.isNull("odo")) row.addView(Ui.text(this, "🛣 " + Fmt.km(e.optLong("odo")), 14, Ui.TEXT, false));
            if (!e.isNull("shop")) row.addView(Ui.text(this, "📍 " + e.optString("shop"), 14, Ui.TEXT, false));
            serviceItems(row, e);
            if (!e.isNull("notes")) {
                TextView nt = Ui.text(this, e.optString("notes"), 13, Ui.MUTED, false);
                nt.setPadding(0, dp(this, 8), 0, 0);
                row.addView(nt);
            }
            JSONArray files = e.optJSONArray("files");
            for (int k = 0; files != null && k < files.length(); k++) {
                JSONObject f = files.optJSONObject(k);
                TextView ft = Ui.text(this, "📎 " + f.optString("name"), 14, Ui.ACCENT, false);
                ft.setPadding(0, dp(this, 6), 0, 0);
                ft.setOnClickListener(v -> showServiceFile(f));
                row.addView(ft);
            }
            row.addView(actions(new String[] {"Modifică", "Șterge"}, new int[] {Ui.ACCENT, Ui.BAD},
                    new Runnable[] {() -> serviceDialog(e), () -> confirm("Ștergi „" + e.optString("title")
                            + "” cu tot cu fișiere?", () -> mutate(() -> api.deleteService(e.optInt("id")), "Șters"))}));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(this, 8);
            card.addView(row, lp);
        }
    }

    /** Articolele lucrarii (piese cu cod, manopera, reduceri), pe grupe, cu subtotal. */
    private void serviceItems(LinearLayout row, JSONObject e) {
        JSONArray items = e.optJSONArray("items");
        if (items == null || items.length() == 0) return;
        double parts = 0, labor = 0, off = 0;
        java.util.LinkedHashMap<String, List<JSONObject>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            double pr = it.isNull("price") ? 0 : it.optDouble("price");
            // Manopera = randurile care incep cu „Manoperă”; reducerile = preturile negative.
            if (pr < 0) off += pr;
            else if (it.optString("name").toLowerCase(java.util.Locale.ROOT).startsWith("manoper")) labor += pr;
            else parts += pr;
            groups.computeIfAbsent(it.optString("group"), k -> new ArrayList<>()).add(it);
        }
        for (java.util.Map.Entry<String, List<JSONObject>> g : groups.entrySet()) {
            LinearLayout gh = new LinearLayout(this);
            gh.setPadding(0, dp(this, 10), 0, dp(this, 2));
            gh.addView(Ui.text(this, g.getKey().toUpperCase(java.util.Locale.ROOT), 12, Ui.ACCENT, true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            double sub = 0;
            boolean priced = false;
            for (JSONObject it : g.getValue()) {
                if (!it.isNull("price")) {
                    sub += it.optDouble("price");
                    priced = true;
                }
            }
            if (priced) gh.addView(Ui.text(this, Fmt.lei(sub), 12, Ui.MUTED, true));
            row.addView(gh);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(Ui.round(this, Ui.CARD, 10));
            int p = dp(this, 8);
            box.setPadding(p, dp(this, 4), p, dp(this, 4));
            for (JSONObject it : g.getValue()) {
                LinearLayout line = new LinearLayout(this);
                line.setPadding(0, dp(this, 4), 0, dp(this, 4));
                LinearLayout left = new LinearLayout(this);
                left.setOrientation(LinearLayout.VERTICAL);
                double qty = it.isNull("qty") ? 1 : it.optDouble("qty");
                left.addView(Ui.text(this, it.optString("name") + (qty != 1 ? "  × " + Fmt.num(qty, 2) : ""), 14, Ui.TEXT, false));
                if (!it.isNull("code")) {
                    TextView code = Ui.text(this, it.optString("code"), 12, Ui.MUTED, false);
                    code.setTypeface(android.graphics.Typeface.MONOSPACE);
                    left.addView(code);
                }
                line.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
                if (!it.isNull("price")) {
                    double pr = it.optDouble("price");
                    TextView pt = Ui.text(this, Fmt.lei(pr), 14, pr < 0 ? Ui.OK : Ui.TEXT, false);
                    pt.setPadding(dp(this, 8), 0, 0, 0);
                    line.addView(pt);
                }
                box.addView(line);
            }
            row.addView(box);
        }
        LinearLayout tot = new LinearLayout(this);
        tot.setOrientation(LinearLayout.VERTICAL);
        tot.setPadding(0, dp(this, 10), 0, 0);
        String[] k = {"Piese", "Manoperă", "Reduceri"};
        double[] v = {parts, labor, off};
        for (int i = 0; i < 3; i++) if (v[i] != 0) tot.addView(pair(k[i], Fmt.lei(v[i]), 14, Ui.MUTED, false));
        double all = Math.round((parts + labor + off) * 100) / 100.0;
        double cost = e.isNull("cost") ? all : e.optDouble("cost");
        tot.addView(pair("Total", Fmt.lei(cost), 16, Ui.TEXT, true));
        if (Math.abs(cost - all) >= 1)
            tot.addView(Ui.text(this, "Articolele adunate dau " + Fmt.lei(all) + ", nu " + Fmt.lei(cost) + ".", 12, Ui.WARN, false));
        row.addView(tot);
    }

    private LinearLayout pair(String k, String v, float sp, int color, boolean bold) {
        LinearLayout l = new LinearLayout(this);
        l.addView(Ui.text(this, k, sp, color, bold), new LinearLayout.LayoutParams(0, -2, 1));
        l.addView(Ui.text(this, v, sp, color, bold));
        return l;
    }

    private void serviceDialog(JSONObject e) {
        pendingFiles.clear();
        LinearLayout form = dialogForm();
        EditText title = Ui.labeled(this, form, "Ce s-a făcut (ex. Schimb ulei și filtre)",
                e == null ? "" : e.optString("title"), InputType.TYPE_CLASS_TEXT);
        EditText date = Ui.labeled(this, form, "Data (AAAA-LL-ZZ)", e == null ? LocalDate.now().toString()
                : e.optString("date"), InputType.TYPE_CLASS_TEXT);
        EditText odo = Ui.labeled(this, form, "Kilometraj (gol = cel de acum)", e == null ? "" : num(e, "odo"),
                InputType.TYPE_CLASS_NUMBER);
        EditText cost = Ui.labeled(this, form, "Cost (lei)", e == null ? "" : num(e, "cost"),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText notes = Ui.labeled(this, form, "Note (service, piese, garanție...)", e == null ? "" : e.optString("notes", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        JSONArray files = e == null ? null : e.optJSONArray("files");
        for (int k = 0; files != null && k < files.length(); k++) {
            JSONObject f = files.optJSONObject(k);
            TextView ft = Ui.text(this, "📎 " + f.optString("name") + "   ✕ șterge", 14, Ui.BAD, false);
            ft.setPadding(0, dp(this, 6), 0, 0);
            ft.setOnClickListener(v -> confirm("Ștergi fișierul?", () -> {
                ft.setVisibility(View.GONE);
                mutate(() -> api.deleteServiceFile(f.optInt("id")), "Fișier șters");
            }));
            form.addView(ft);
        }
        TextView attach = Ui.text(this, "📎 Atașează bon / poză", 15, Ui.ACCENT, true);
        attach.setPadding(0, dp(this, 12), 0, dp(this, 4));
        attach.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "application/pdf"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, "Alege bonul"), PICK_SERVICE_FILE);
        });
        form.addView(attach);
        pendingLabel = Ui.text(this, "", 13, Ui.MUTED, false);
        form.addView(pendingLabel);
        showForm(e == null ? "Lucrare nouă" : "Modifică lucrarea", form, () -> {
            JSONObject o = new JSONObject();
            if (e != null) o.put("id", e.optInt("id"));
            o.put("title", text(title));
            o.put("date", text(date));
            o.put("odo", text(odo).isEmpty() ? JSONObject.NULL : parse(odo));
            o.put("cost", text(cost).isEmpty() ? JSONObject.NULL : parse(cost));
            o.put("notes", text(notes).isEmpty() ? JSONObject.NULL : text(notes));
            int id = api.saveService(o);
            for (Uri u : new ArrayList<>(pendingFiles)) {
                String mime = getContentResolver().getType(u);
                try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    api.uploadServiceFile(id, fileName(u), mime == null ? "image/jpeg" : mime, out.toByteArray());
                }
            }
            pendingFiles.clear();
        }, "Lucrare salvată");
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_SERVICE_FILE || result != RESULT_OK || data == null) return;
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) pendingFiles.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            pendingFiles.add(data.getData());
        }
        if (pendingLabel != null) {
            List<String> names = new ArrayList<>();
            for (Uri u : pendingFiles) names.add(fileName(u));
            pendingLabel.setText("De trimis: " + String.join(", ", names));
        }
    }

    /** Numele fisierului ales (pentru server), din ContentResolver. */
    private String fileName(Uri u) {
        try (android.database.Cursor cur = getContentResolver().query(u,
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        return "bon.jpg";
    }

    /** O poza atasata, pe ecran; un PDF se deschide pe site. */
    private void showServiceFile(JSONObject f) {
        if (!f.optString("mime").startsWith("image/")) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Api.BASE + "/api/service/files/" + f.optInt("id"))));
            return;
        }
        toast("Se încarcă...");
        io.execute(() -> {
            try {
                byte[] data = api.serviceFile(f.optInt("id"));
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                // Micsorata cat sa incapa pe ecran (pozele de telefon au ~4000 px).
                int scale = 1;
                while (o.outWidth / scale > 2000 || o.outHeight / scale > 2000) scale *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = scale;
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                ui.post(() -> {
                    if (bmp == null) {
                        toast("Nu pot afișa fișierul");
                        return;
                    }
                    android.widget.ImageView iv = new android.widget.ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setAdjustViewBounds(true);
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(f.optString("name")).setView(iv).setPositiveButton("Închide", null).show();
                });
            } catch (Exception e) {
                ui.post(() -> toast(e.getMessage()));
            }
        });
    }

    // ---------------------------------------------------------------- locuri salvate

    /** Acasa, Serviciu, Frizerie...: calatoriile, opririle si masina parcata se denumesc dupa ele. */
    private void renderPlaces() {
        LinearLayout card = Ui.card(this, content);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Locuri salvate", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = Ui.text(this, "+ Adaugă", 15, Ui.ACCENT, true);
        JSONObject pos = car.optJSONObject("position");
        add.setOnClickListener(v -> openPlaceEditor(null, "",
                pos == null ? null : pos.optDouble("lat"), pos == null ? null : pos.optDouble("lon"), 100));
        head.addView(add);
        card.addView(head);
        Ui.hint(this, card, "Călătoriile apar ca „Acasă → Serviciu”, iar mașina parcată „la Serviciu”. "
                + "Un loc nou: după adresă, cu pinul mutat pe hartă. Un loc poate avea mai multe "
                + "poziții (ex. două parcări acasă): „+ Poziție” pe rândul lui.");
        if (places == null || places.length() == 0) return;
        for (int i = 0; i < places.length(); i++) {
            JSONObject p = places.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(this, Ui.CARD2, 14));
            int pad = dp(this, 12);
            row.setPadding(pad, pad, pad, pad);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(Ui.text(this, p.optString("name"), 16, Ui.TEXT, true));
            // Pozitiile aceluiasi loc vin una dupa alta (lista e ordonata dupa nume, apoi id).
            int nth = 1, total = 0;
            for (int j = 0; j < places.length(); j++) {
                if (!places.optJSONObject(j).optString("name").equals(p.optString("name"))) continue;
                total++;
                if (j < i) nth++;
            }
            texts.addView(Ui.text(this, (total > 1 ? "poziția " + nth + " · " : "")
                    + "rază " + p.optInt("radius") + " m", 12, Ui.MUTED, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            TextView more = Ui.text(this, "+ Poziție", 14, Ui.ACCENT, true);
            more.setOnClickListener(v -> openPlaceEditor(null, p.optString("name"),
                    pos == null ? null : pos.optDouble("lat"), pos == null ? null : pos.optDouble("lon"),
                    p.optInt("radius", 100), true));
            row.addView(more);
            TextView edit = Ui.text(this, "Modifică", 14, Ui.ACCENT, true);
            edit.setPadding(dp(this, 10), 0, dp(this, 10), 0);
            edit.setOnClickListener(v -> openPlaceEditor(p.optInt("id"), p.optString("name"),
                    p.optDouble("lat"), p.optDouble("lon"), p.optInt("radius", 100)));
            row.addView(edit);
            TextView del = Ui.text(this, "Șterge", 14, Ui.BAD, true);
            del.setOnClickListener(v -> confirm("Ștergi „" + p.optString("name") + "”?",
                    () -> mutate(() -> api.deletePlace(p.optInt("id")), "Loc șters")));
            row.addView(del);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(this, 8);
            card.addView(row, lp);
        }
    }

    // Locul in lucru: {id?, name, lat?, lon?, radius}; null = editorul e inchis.
    private JSONObject placeDraft;
    private int placeEditorGen;
    private static final int[] RADII = {50, 100, 150, 250};

    private void openPlaceEditor(Integer id, String name, Double lat, Double lon, int radius) {
        openPlaceEditor(id, name, lat, lon, radius, false);
    }

    /** extra = inca o pozitie pentru un loc existent (ex. a doua parcare „Acasă”), cu acelasi nume. */
    private void openPlaceEditor(Integer id, String name, Double lat, Double lon, int radius, boolean extra) {
        try {
            placeDraft = new JSONObject().put("name", name).put("radius", radius);
            if (extra) placeDraft.put("extra", true);
            if (id != null) placeDraft.put("id", id);
            if (lat != null && lon != null && !lat.isNaN()) placeDraft.put("lat", lat).put("lon", lon);
        } catch (Exception ignored) {
        }
        placeEditorGen++;
        render();
    }

    private void closePlaceEditor() {
        placeDraft = null;
        shown = "";
        render();
    }

    /**
     * Ecranul de loc: numele, cautarea adresei (OpenStreetMap), harta cu pinul (atingi harta sau
     * tragi pinul), raza si Salveaza. Totul se actualizeaza pe loc, fara reconstruire.
     */
    private void renderPlaceEditor() {
        JSONObject d = placeDraft;
        TextView back = Ui.text(this, "‹ Înapoi", 16, Ui.ACCENT, true);
        back.setPadding(0, dp(this, 4), 0, dp(this, 12));
        back.setOnClickListener(v -> closePlaceEditor());
        content.addView(back);
        LinearLayout card = Ui.card(this, content);
        boolean extra = d.optBoolean("extra");
        card.addView(Ui.text(this, d.has("id") ? "Modifică locul"
                : extra ? "Încă o poziție pentru „" + d.optString("name") + "”" : "Loc nou", 19, Ui.TEXT, true));
        if (extra) Ui.hint(this, card, "Pinul pornește unde e mașina acum; călătoriile până aici vor apărea tot „"
                + d.optString("name") + "”.");
        EditText name = Ui.labeled(this, card, "Nume (ex. Frizerie)", d.optString("name"), InputType.TYPE_CLASS_TEXT);
        EditText address = Ui.labeled(this, card, "Adresa (ex. Bulevardul Timișoara 48)", "", InputType.TYPE_CLASS_TEXT);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        MapBox.Picker[] picker = new MapBox.Picker[1];
        Ui.addButton(this, card, "Caută adresa", Ui.SECONDARY, v -> {
            String q = text(address);
            if (q.length() < 3) return;
            v.setEnabled(false);
            io.execute(() -> {
                try {
                    JSONArray r = api.geocode(q);
                    ui.post(() -> {
                        v.setEnabled(true);
                        results.removeAllViews();
                        if (r.length() == 0) Ui.hint(this, results, "Nicio adresă găsită; atinge harta ca să pui pinul.");
                        for (int i = 0; i < r.length(); i++) {
                            JSONObject a = r.optJSONObject(i);
                            TextView t = Ui.text(this, a.optString("label"), 14, Ui.TEXT, false);
                            t.setBackground(Ui.round(this, Ui.CARD2, 10));
                            int p = dp(this, 10);
                            t.setPadding(p, p, p, p);
                            t.setOnClickListener(x -> {
                                setPin(a.optDouble("lat"), a.optDouble("lon"));
                                picker[0].set(a.optDouble("lat"), a.optDouble("lon"), true);
                            });
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                            lp.topMargin = dp(this, 6);
                            results.addView(t, lp);
                        }
                        // O singura adresa: pinul merge direct acolo.
                        if (r.length() == 1) results.getChildAt(0).performClick();
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        v.setEnabled(true);
                        toast(e.getMessage());
                    });
                }
            });
        });
        card.addView(results);

        picker[0] = new MapBox.Picker(this, d.has("lat") ? d.optDouble("lat") : null,
                d.has("lon") ? d.optDouble("lon") : null, d.optInt("radius", 100), this::setPin);
        maps.add(picker[0].map);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(this, 280));
        mlp.topMargin = dp(this, 12);
        card.addView(picker[0].map, mlp);
        Ui.hint(this, card, "Atinge harta sau trage pinul ca să-l muți.");

        LinearLayout radii = new LinearLayout(this);
        radii.setGravity(Gravity.CENTER_VERTICAL);
        radii.addView(Ui.text(this, "Rază ", 14, Ui.MUTED, false));
        TextView[] chips = new TextView[RADII.length];
        for (int i = 0; i < RADII.length; i++) {
            int r = RADII[i];
            TextView chip = Ui.text(this, r + " m", 14, Ui.TEXT, true);
            chip.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
            chips[i] = chip;
            chip.setOnClickListener(v -> {
                try {
                    placeDraft.put("radius", r);
                } catch (Exception ignored) {
                }
                picker[0].setRadius(r);
                styleChips(chips, r);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(this, 6);
            radii.addView(chip, lp);
        }
        styleChips(chips, d.optInt("radius", 100));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(this, 10);
        card.addView(radii, rlp);

        Ui.addButton(this, card, "Salvează", Ui.PRIMARY, v -> {
            if (!placeDraft.has("lat")) {
                toast("Pune pinul pe hartă");
                return;
            }
            if (text(name).isEmpty()) {
                toast("Scrie un nume");
                return;
            }
            JSONObject o = placeDraft;
            io.execute(() -> {
                try {
                    o.put("name", text(name));
                    api.savePlace(o);
                    JSONArray pl = api.places();
                    ui.post(() -> {
                        places = pl;
                        toast(o.optBoolean("extra") ? "Poziție adăugată" : "Loc salvat");
                        closePlaceEditor();
                        load(true);
                    });
                } catch (Api.Unauthorized e) {
                    ui.post(this::logout);
                } catch (Exception e) {
                    ui.post(() -> toast(e.getMessage()));
                }
            });
        });
    }

    private void setPin(double lat, double lon) {
        try {
            placeDraft.put("lat", lat).put("lon", lon);
        } catch (Exception ignored) {
        }
    }

    private void styleChips(TextView[] chips, int radius) {
        for (int i = 0; i < chips.length; i++) {
            boolean on = RADII[i] == radius;
            chips[i].setTextColor(on ? Ui.ACCENT : Ui.TEXT);
            chips[i].setBackground(Ui.round(this, on ? 0x2638BDF8 : Ui.CARD2, 10));
        }
    }

    // ---------------------------------------------------------------- fila Mai mult

    private void renderMore() {
        renderUpdate();
        if (car == null) {
            Ui.hint(this, content, "Se încarcă...");
            return;
        }
        renderMaintenance();
        renderService();
        renderPlaces();
        renderLog();
        LinearLayout acc = Ui.card(this, content);
        Ui.title(this, acc, "Aplicația");
        Ui.hint(this, acc, "FaikkitCar Panel v" + BuildConfig.VERSION_NAME + " · date de pe car.faicu.ro");
        Ui.addButton(this, acc, "Ieși din cont", Ui.SECONDARY, v -> confirm("Ieși din cont?", this::logout));
    }

    // ---------------------------------------------------------------- alimentari

    private void renderFuel() {
        LinearLayout card = Ui.card(this, content);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Alimentări", 19, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = Ui.text(this, "+ Adaugă", 15, Ui.ACCENT, true);
        add.setOnClickListener(v -> refuelDialog(null));
        head.addView(add);
        card.addView(head);

        StringBuilder s = new StringBuilder();
        JSONObject tank = fuel.optJSONObject("tank");
        if (tank != null) s.append("Rezervor: ").append(Fmt.num(tank.optDouble("liters"), 0)).append(" L · ");
        JSONObject level = fuel.optJSONObject("level");
        if (!fuel.isNull("avgLPer100")) {
            s.append("consum real ").append(Fmt.num(fuel.optDouble("avgLPer100"), 1)).append(" L/100 km");
        } else if (level != null) {
            s.append("consumat ").append(Fmt.num(level.optDouble("liters"), 0))
                    .append(" L din rezervor (consumul real apare după ~8 L)");
        } else {
            s.append("consumul real apare după primele drumuri");
        }
        String source = fuel.optString("source", "");
        s.append(" · ").append("level".equals(source) ? "estimare calibrată din rezervor (×"
                + Fmt.fixed(fuel.optDouble("factor"), 2) + ")"
                : "refuels".equals(source) ? "estimare calibrată din plinuri (×"
                        + Fmt.fixed(fuel.optDouble("factor"), 2) + ")"
                        : "estimare necalibrată");
        Ui.hint(this, card, s.toString());

        JSONArray list = fuel.optJSONArray("refuels");
        if (list == null || list.length() == 0) {
            Ui.hint(this, card, "Nicio alimentare. Adaugă fiecare alimentare (cu prețul): din ea iese costul fiecărui drum.");
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Ui.round(this, Ui.CARD2, 14));
            int p = dp(this, 12);
            row.setPadding(p, p, p, p);
            LinearLayout top = new LinearLayout(this);
            top.addView(Ui.text(this, Fmt.day(r.optString("at")) + " · " + Fmt.fixed(r.optDouble("liters"), 2) + " L"
                    + (r.optBoolean("full") ? " · plin" : ""), 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
            if (!r.isNull("price")) {
                top.addView(Ui.text(this, Fmt.fixed(r.optDouble("liters") * r.optDouble("price"), 2) + " lei",
                        15, Ui.ACCENT, true));
            }
            row.addView(top);
            List<String> parts = new ArrayList<>();
            if (!r.isNull("price")) parts.add(Fmt.fixed(r.optDouble("price"), 2) + " lei/L");
            if (!r.isNull("odo")) parts.add(Fmt.km(r.optLong("odo")));
            if (!r.isNull("lPer100")) parts.add(Fmt.num(r.optDouble("lPer100"), 1) + " L/100 km de la plinul anterior");
            if (!r.isNull("note")) parts.add(r.optString("note"));
            if (!parts.isEmpty()) row.addView(Ui.text(this, String.join(" · ", parts), 13, Ui.MUTED, false));
            row.addView(actions(new String[] {"Editează", "Șterge"}, new int[] {Ui.ACCENT, Ui.BAD},
                    new Runnable[] {() -> refuelDialog(r), () -> confirm("Ștergi alimentarea?",
                            () -> mutate(() -> api.deleteRefuel(r.optInt("id")), "Alimentare ștearsă"))}));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(this, 8);
            card.addView(row, lp);
        }
    }

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Alimentare noua (r = null), modificata (r cu id) sau propusa din rezervor (r fara id, cu
     * ora si litrii estimati).
     */
    private void refuelDialog(JSONObject r) {
        boolean editing = r != null && r.has("id");
        LinearLayout form = dialogForm();
        String at = r == null ? LocalDateTime.now().format(LOCAL)
                : LocalDateTime.ofInstant(java.time.Instant.parse(r.optString("at")), ZoneId.systemDefault()).format(LOCAL);
        EditText when = Ui.labeled(this, form, "Data și ora (AAAA-LL-ZZ OO:MM)", at, InputType.TYPE_CLASS_TEXT);
        int dec = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        EditText liters = Ui.labeled(this, form, "Litri", r == null ? "" : num(r, "liters"), dec);
        String price = r != null && r.has("price") ? num(r, "price")
                : fuel == null || fuel.isNull("lastPrice") ? "" : String.valueOf(fuel.optDouble("lastPrice"));
        EditText priceField = Ui.labeled(this, form, "Preț (lei/L)", price, dec);
        EditText odo = Ui.labeled(this, form, "Kilometraj (gol = automat)", r == null ? "" : num(r, "odo"),
                InputType.TYPE_CLASS_NUMBER);
        EditText note = Ui.labeled(this, form, "Notă (benzinărie...)", r == null ? "" : r.optString("note", ""),
                InputType.TYPE_CLASS_TEXT);
        CheckBox full = new CheckBox(this);
        full.setText("Plin (până la oprirea pistolului)");
        full.setTextColor(Ui.TEXT);
        full.setChecked(editing ? r.optBoolean("full") : r == null);
        form.addView(full);
        Ui.hint(this, form, "Consumul real vine din nivelul rezervorului citit de la mașină; „Plin” ajută doar ca rezervă.");
        showForm(editing ? "Editează alimentarea" : "Alimentare nouă", form, () -> {
            JSONObject o = new JSONObject();
            if (editing) o.put("id", r.optInt("id"));
            o.put("at", LocalDateTime.parse(when.getText().toString().trim(), LOCAL)
                    .atZone(ZoneId.systemDefault()).toInstant().toString());
            o.put("liters", parse(liters));
            o.put("price", text(priceField).isEmpty() ? JSONObject.NULL : parse(priceField));
            o.put("odo", text(odo).isEmpty() ? JSONObject.NULL : parse(odo));
            o.put("full", full.isChecked());
            o.put("note", text(note).isEmpty() ? JSONObject.NULL : text(note));
            api.saveRefuel(o);
        }, "Alimentare salvată");
    }

    // ---------------------------------------------------------------- mentenanta

    private static String describe(JSONObject r) {
        List<String> parts = new ArrayList<>();
        if (!r.isNull("kmLeft")) {
            long k = r.optLong("kmLeft");
            parts.add(k <= 0 ? "depășit cu " + Fmt.km(-k) : "peste " + Fmt.km(k));
        }
        if (!r.isNull("daysLeft") && !r.isNull("nextDate")) {
            String d = LocalDate.parse(r.optString("nextDate"))
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy", new java.util.Locale("ro", "RO")));
            long days = r.optLong("daysLeft");
            parts.add(days <= 0 ? "expirat pe " + d : "pe " + d + " (" + days + " zile)");
        }
        return parts.isEmpty() ? "completează ultimul km sau data" : String.join(" · ", parts);
    }

    private void renderMaintenance() {
        LinearLayout card = Ui.card(this, content);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "Mentenanță", 19, Ui.TEXT, true));
        titles.addView(Ui.text(this, "Kilometraj: " + (car.isNull("odometer") ? "necunoscut încă"
                : Fmt.km(car.optLong("odometer"))), 13, Ui.MUTED, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = Ui.text(this, "+ Adaugă", 15, Ui.ACCENT, true);
        add.setOnClickListener(v -> reminderDialog(null));
        head.addView(add);
        card.addView(head);

        JSONArray list = car.optJSONArray("reminders");
        if (list == null || list.length() == 0) {
            Ui.hint(this, card, "Nicio notificare de mentenanță. Adaugă schimbul de ulei, ITP-ul, RCA-ul...");
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Ui.round(this, Ui.CARD2, 14));
            int p = dp(this, 12);
            row.setPadding(p, p, p, p);
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(Ui.text(this, r.optString("title"), 16, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
            boolean overdue = r.optBoolean("overdue"), soon = r.optBoolean("soon");
            top.addView(Ui.pill(this, overdue ? "depășit" : soon ? "în curând" : "ok",
                    overdue ? Ui.BAD : soon ? Ui.WARN : Ui.MUTED));
            row.addView(top);
            row.addView(Ui.text(this, describe(r), 13, Ui.MUTED, false));
            int id = r.optInt("id");
            row.addView(actions(new String[] {"Făcut azi", "Editează", "Șterge"}, new int[] {Ui.OK, Ui.ACCENT, Ui.BAD},
                    new Runnable[] {() -> mutate(() -> api.reminderDone(id), "Marcat ca făcut azi"),
                            () -> reminderDialog(r),
                            () -> confirm("Ștergi „" + r.optString("title") + "”?",
                                    () -> mutate(() -> api.deleteReminder(id), "Șters"))}));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(this, 8);
            card.addView(row, lp);
        }
    }

    // Ca pe site: preseturi care completeaza formularul (titlu, km, luni).
    private static final Object[][] PRESETS = {
            {"Schimb ulei și filtre", 15000, 12},
            {"ITP", null, 24},
            {"RCA", null, 12},
            {"Rovinietă", null, 12},
            {"Kit distribuție", 90000, null},
    };

    private void reminderDialog(JSONObject r) {
        LinearLayout form = dialogForm();
        int numType = InputType.TYPE_CLASS_NUMBER;
        EditText title = Ui.labeled(this, form, "Ce", r == null ? "" : r.optString("title"), InputType.TYPE_CLASS_TEXT);
        EditText everyKm = Ui.labeled(this, form, "La fiecare (km)", r == null ? "" : num(r, "everyKm"), numType);
        EditText everyMonths = Ui.labeled(this, form, "La fiecare (luni)", r == null ? "" : num(r, "everyMonths"), numType);
        String odo = car.isNull("odometer") ? "" : String.valueOf(car.optLong("odometer"));
        EditText lastKm = Ui.labeled(this, form, "Ultima dată făcut la (km)", r == null ? "" : num(r, "lastKm"), numType);
        EditText lastDate = Ui.labeled(this, form, "Ultima dată făcut pe (AAAA-LL-ZZ)",
                r == null ? "" : r.optString("lastDate", ""), InputType.TYPE_CLASS_TEXT);
        EditText dueDate = Ui.labeled(this, form, "Sau expiră pe (ITP, RCA...; AAAA-LL-ZZ)",
                r == null ? "" : r.optString("dueDate", ""), InputType.TYPE_CLASS_TEXT);
        if (r == null) {
            LinearLayout presets = new LinearLayout(this);
            presets.setOrientation(LinearLayout.VERTICAL);
            for (Object[] p : PRESETS) {
                TextView t = Ui.text(this, "• " + p[0], 14, Ui.ACCENT, false);
                t.setPadding(0, dp(this, 6), 0, dp(this, 6));
                t.setOnClickListener(v -> {
                    title.setText((String) p[0]);
                    everyKm.setText(p[1] == null ? "" : String.valueOf(p[1]));
                    everyMonths.setText(p[2] == null ? "" : String.valueOf(p[2]));
                    lastKm.setText(p[1] == null ? "" : odo);
                });
                presets.addView(t);
            }
            form.addView(presets, 0);
        }
        showForm(r == null ? "Mentenanță nouă" : "Editează", form, () -> {
            JSONObject o = new JSONObject();
            if (r != null) o.put("id", r.optInt("id"));
            o.put("title", text(title));
            o.put("everyKm", text(everyKm).isEmpty() ? JSONObject.NULL : parse(everyKm));
            o.put("everyMonths", text(everyMonths).isEmpty() ? JSONObject.NULL : parse(everyMonths));
            o.put("lastKm", text(lastKm).isEmpty() ? JSONObject.NULL : parse(lastKm));
            o.put("lastDate", text(lastDate).isEmpty() ? JSONObject.NULL : text(lastDate));
            o.put("dueDate", text(dueDate).isEmpty() ? JSONObject.NULL : text(dueDate));
            api.saveReminder(o);
        }, "Salvat");
    }

    // ---------------------------------------------------------------- jurnal

    private static int lineColor(String line) {
        if (line.startsWith("TREZIRE")) return Ui.OK;
        String l = line.toLowerCase();
        if (line.contains("PIERDUT") || line.contains("REFUZAT") || l.contains("eroare") || l.contains("nu am putut")) {
            return Ui.BAD;
        }
        if (line.startsWith("Serviciu") || line.startsWith("Pornit din autostart") || line.startsWith("BootReceiver")) {
            return Ui.ACCENT;
        }
        return Ui.TEXT;
    }

    private void renderLog() {
        LinearLayout card = Ui.card(this, content);
        // Filele: un rand care se deruleaza orizontal.
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < LOG_CATS.length; i++) {
            String id = LOG_CATS[i];
            boolean on = id.equals(logCat);
            TextView chip = Ui.text(this, LOG_LABELS[i], 14, on ? Ui.ACCENT : Ui.TEXT, true);
            chip.setPadding(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));
            chip.setBackground(Ui.round(this, on ? 0x2638BDF8 : Ui.CARD2, 14));
            chip.setOnClickListener(v -> {
                if (id.equals(logCat)) return;
                logCat = id;
                log = null;
                render();
                load(true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(this, 6);
            row.addView(chip, lp);
        }
        hs.addView(row);
        card.addView(hs);
        boolean cmds = logCat.equals("comenzi");
        int n = log == null ? 0 : log.length();
        String label = LOG_LABELS[java.util.Arrays.asList(LOG_CATS).indexOf(logCat)];
        TextView head = Ui.text(this, n + (cmds ? " comenzi" : " linii · " + label.toLowerCase()), 17, Ui.TEXT, true);
        head.setPadding(0, dp(this, 10), 0, 0);
        card.addView(head);
        String version = n > 0 && !cmds ? log.optJSONObject(0).optString("version", "") : "";
        card.addView(Ui.text(this, cmds ? "Rulate doar cu „Comenzi de la distanta” pornit · ora serverului"
                : (version.isEmpty() ? "" : "Aplicație " + version + " · ") + "ora e cea de pe navigație", 13, Ui.MUTED, false));
        if (log == null) {
            Ui.hint(this, card, "Se încarcă...");
            return;
        }
        if (n == 0) {
            Ui.hint(this, card, cmds ? "Nicio comandă încă." : "Nicio linie aici.");
            return;
        }
        if (cmds) {
            renderCmds();
            return;
        }
        LinearLayout lines = Ui.card(this, content);
        int max = Math.min(n, 400);
        for (int i = 0; i < max; i++) {
            JSONObject e = log.optJSONObject(i);
            TextView t = Ui.mono(this, "", 12);
            String line = e.optString("line");
            t.setText(Fmt.dateTime(e.optString("deviceAt")) + "  " + line);
            t.setTextColor(lineColor(line));
            t.setPadding(0, dp(this, 3), 0, dp(this, 3));
            lines.addView(t);
        }
        if (n > max) Ui.hint(this, lines, "Restul (" + (n - max) + ") pe car.faicu.ro.");
    }

    /** Comenzile la distanta: comanda, starea, durata; atingerea arata rezultatul. */
    private void renderCmds() {
        for (int i = 0; i < log.length(); i++) {
            JSONObject c = log.optJSONObject(i);
            LinearLayout box = Ui.card(this, content);
            String status;
            int color;
            if (c.isNull("doneAt")) {
                status = c.isNull("sentAt") ? "în așteptare" : "rulează";
                color = Ui.MUTED;
            } else if (!c.isNull("exitCode") && c.optInt("exitCode") == 0) {
                status = "cod 0";
                color = Ui.OK;
            } else {
                status = c.isNull("exitCode") ? "fără cod" : "cod " + c.optInt("exitCode");
                color = Ui.BAD;
            }
            String dur = "";
            if (!c.isNull("doneAt") && !c.isNull("sentAt")) {
                long ms = Fmt.millis(c.optString("doneAt")) - Fmt.millis(c.optString("sentAt"));
                if (ms >= 0) dur = String.format(java.util.Locale.US, " · %.1f s", ms / 1000.0);
            }
            TextView meta = Ui.text(this, "#" + c.optInt("id") + " · " + Fmt.dateTime(c.optString("createdAt")) + " · " + status + dur,
                    13, color, true);
            box.addView(meta);
            TextView cmd = Ui.mono(this, c.optString("cmd"), 12);
            cmd.setTextColor(Ui.TEXT);
            cmd.setPadding(0, dp(this, 4), 0, 0);
            box.addView(cmd);
            String out = c.isNull("output") ? "" : c.optString("output");
            if (!out.isEmpty()) {
                TextView more = Ui.text(this, "Arată rezultatul (" + out.length() + " caractere)", 13, Ui.ACCENT, false);
                more.setPadding(0, dp(this, 6), 0, 0);
                TextView res = Ui.mono(this, out.length() > 8000 ? out.substring(0, 8000) + "\n[… restul pe car.faicu.ro]" : out, 11);
                res.setTextColor(Ui.MUTED);
                res.setVisibility(View.GONE);
                more.setOnClickListener(v -> {
                    boolean show = res.getVisibility() != View.VISIBLE;
                    res.setVisibility(show ? View.VISIBLE : View.GONE);
                    more.setText(show ? "Ascunde rezultatul" : "Arată rezultatul (" + out.length() + " caractere)");
                });
                box.addView(more);
                box.addView(res);
            }
        }
    }

    // ---------------------------------------------------------------- ajutoare

    /** Randul de butoane-text de sub un element (Editeaza, Sterge...). */
    private LinearLayout actions(String[] labels, int[] colors, Runnable[] actions) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(this, 6), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            Runnable action = actions[i];
            TextView t = Ui.text(this, labels[i], 14, colors[i], true);
            t.setPadding(0, dp(this, 6), dp(this, 18), dp(this, 6));
            t.setOnClickListener(v -> action.run());
            row.addView(t);
        }
        return row;
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("Da", (d, w) -> yes.run())
                .setNegativeButton("Nu", null)
                .show();
    }

    private interface Call {
        void run() throws Exception;
    }

    /** Trimite o modificare la server, apoi reincarca datele. */
    private void mutate(Call call, String ok) {
        io.execute(() -> {
            try {
                call.run();
                ui.post(() -> {
                    toast(ok);
                    load(true);
                });
            } catch (Api.Unauthorized e) {
                ui.post(this::logout);
            } catch (Exception e) {
                ui.post(() -> toast(e.getMessage()));
            }
        });
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = dp(this, 20);
        form.setPadding(p, dp(this, 4), p, 0);
        return form;
    }

    /** Dialog cu formular; „Salvează” valideaza si trimite, iar dialogul ramane deschis la eroare. */
    private void showForm(String title, LinearLayout form, Call save, String ok) {
        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("Salvează", null)
                .setNegativeButton("Renunță", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> io.execute(() -> {
            try {
                save.run();
                ui.post(() -> {
                    d.dismiss();
                    toast(ok);
                    load(true);
                });
            } catch (Api.Unauthorized e) {
                ui.post(() -> {
                    d.dismiss();
                    logout();
                });
            } catch (java.time.format.DateTimeParseException e) {
                ui.post(() -> toast("Data nu e în formatul cerut"));
            } catch (NumberFormatException e) {
                ui.post(() -> toast("Număr invalid"));
            } catch (Exception e) {
                ui.post(() -> toast(e.getMessage()));
            }
        })));
        d.show();
    }

    private static String text(EditText e) {
        return e.getText().toString().trim();
    }

    private static double parse(EditText e) {
        return Double.parseDouble(text(e).replace(',', '.'));
    }

    /** Valoarea numerica pentru un camp de formular: fara „.0” la intregi, gol daca lipseste. */
    private static String num(JSONObject o, String key) {
        if (o.isNull(key)) return "";
        double v = o.optDouble(key);
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
