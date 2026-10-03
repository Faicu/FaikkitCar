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
    private static final String[] TABS = {"Acum", "Călătorii", "Costuri", "Mai mult"};
    private static final int NOW = 0, TRIPS = 1, COSTS = 2, MORE = 3;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Api api;

    private int tab;
    private LinearLayout root, content;
    private ScrollView scroll;
    private final TextView[] tabViews = new TextView[TABS.length];
    private final List<MapView> maps = new ArrayList<>();

    // Datele curente; `shown` = ce s-a desenat ultima data, ca sa nu redesenam degeaba.
    private JSONArray trips, log, places;
    private JSONObject live, car, fuel, stats, newerApk;
    private JSONArray points;
    private String pointsFor, selected, shown = "";
    private boolean eventsOnly = true;

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
        for (MapView m : maps) m.onResume();
        if (content != null) load(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        stopLive();
        for (MapView m : maps) m.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private final Runnable tick = () -> load(false);

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
            TextView t = Ui.text(this, TABS[i], 15, Ui.MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(this, 10), 0, dp(this, 10));
            t.setOnClickListener(v -> {
                placeDraft = null; // o fila inchide editorul de loc
                if (tab == index && index == TRIPS) selected = null; // a doua atingere: inapoi la lista
                tab = index;
                shown = "";
                styleTabs();
                render();
                load(false);
            });
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
        if (force) shown = "";
        if (tab == NOW) startLive();
        else stopLive();
        int forTab = tab;
        boolean events = eventsOnly;
        io.execute(() -> {
            try {
                if (forTab == NOW) {
                    // Starea vine pe firul ei (startLive); aici doar actualizarea aplicatiei.
                    JSONObject apk = newerApk == null ? Updater.newer(this) : newerApk;
                    ui.post(() -> {
                        newerApk = apk;
                        render();
                    });
                    return;
                } else if (forTab == TRIPS) {
                    JSONArray t = api.trips();
                    ui.post(() -> {
                        trips = t;
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
                    JSONArray l = api.log(events);
                    JSONObject apk = newerApk == null ? Updater.newer(this) : newerApk;
                    ui.post(() -> {
                        car = c;
                        places = pl;
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
                    ui.post(() -> {
                        live = l;
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
        String state = tab + "|" + selected + "|" + eventsOnly + "|" + Store.hideIdle(this) + "|"
                + (newerApk != null) + "|"
                + (tab == NOW ? String.valueOf(live)
                        : tab == TRIPS ? trips + "|" + pointsFor
                        : tab == COSTS ? stats + "|" + fuel
                        : car + "|" + places + "|" + log);
        if (state.equals(shown)) return;
        shown = state;
        int y = scroll.getScrollY();
        detachMaps();
        content.removeAllViews();
        if (tab == NOW) renderNow();
        else if (tab == TRIPS) renderTrips();
        else if (tab == COSTS) renderCosts();
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
            default: return "Oprită";
        }
    }

    private static int stateColor(String state) {
        switch (state) {
            case "driving": return Ui.ACCENT;
            case "engine": return Ui.OK;
            case "traffic": return 0xFFFB923C;
            case "contact": return Ui.WARN;
            default: return Ui.MUTED;
        }
    }

    /**
     * Fila Acum, in ordinea importantei: starea (cu valorile de acum, clima intr-un rand si
     * avertizarile), calatoria in curs, rezervorul cu autonomia si unde e masina.
     */
    private void renderNow() {
        renderUpdate();
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
            if (!d.isNull("instantL100") && d.has("instantL100")) {
                Ui.hint(this, card, "Consum acum*: " + Fmt.num(d.optDouble("instantL100"), 1)
                        + " L/100 km (bordul; presupus, compară cu afișajul)");
            }
            renderCarDetails(card, d);
        }

        JSONObject trip = live.optJSONObject("trip");
        if (trip != null) tripSummary(trip, "Călătoria în curs · de " + Fmt.duration(trip.optDouble("durationMin")));

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
        if (pos != null) {
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
        if (!d.isNull("lights") && d.has("lights")) ok.add(d.optBoolean("lights") ? "luminile aprinse" : "luminile stinse");
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
        boolean board = t.has("boardLPer100") && !t.isNull("boardLPer100");
        if (board) fc.add(Ui.info(this, "Bordul, în mers*", Fmt.num(t.optDouble("boardLPer100"), 1) + " L/100"));
        if (!t.isNull("fuelStart") && !t.isNull("fuelEnd")) {
            fc.add(Ui.info(this, "Rezervor", Fmt.num(t.optDouble("fuelStart"), 0) + " → "
                    + Fmt.num(t.optDouble("fuelEnd"), 0) + " L"));
        }
        fuelCard.addView(Ui.grid(this, fc, 3));
        if (board) Ui.hint(this, fuelCard, "* din consumul instantaneu al bordului (c1033), de confirmat cu afișajul.");

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
        for (JSONObject t : list) {
            String start = t.optString("start");
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

    // ---------------------------------------------------------------- fila Costuri

    private void renderCosts() {
        if (stats == null || fuel == null) {
            Ui.hint(this, content, "Se încarcă...");
            return;
        }
        renderStats(stats.optJSONObject("last30"));
        renderMonthly(stats.optJSONArray("months"));
        renderFuel();
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
                + "Un loc nou: după adresă, cu pinul mutat pe hartă.");
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
            texts.addView(Ui.text(this, "rază " + p.optInt("radius") + " m", 12, Ui.MUTED, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
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
        try {
            placeDraft = new JSONObject().put("name", name).put("radius", radius);
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
        card.addView(Ui.text(this, d.has("id") ? "Modifică locul" : "Loc nou", 19, Ui.TEXT, true));
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
                        toast("Loc salvat");
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

    private void refuelDialog(JSONObject r) {
        LinearLayout form = dialogForm();
        String at = r == null ? LocalDateTime.now().format(LOCAL)
                : LocalDateTime.ofInstant(java.time.Instant.parse(r.optString("at")), ZoneId.systemDefault()).format(LOCAL);
        EditText when = Ui.labeled(this, form, "Data și ora (AAAA-LL-ZZ OO:MM)", at, InputType.TYPE_CLASS_TEXT);
        int dec = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        EditText liters = Ui.labeled(this, form, "Litri", r == null ? "" : num(r, "liters"), dec);
        String price = r != null ? num(r, "price")
                : fuel.isNull("lastPrice") ? "" : String.valueOf(fuel.optDouble("lastPrice"));
        EditText priceField = Ui.labeled(this, form, "Preț (lei/L)", price, dec);
        EditText odo = Ui.labeled(this, form, "Kilometraj (gol = automat)", r == null ? "" : num(r, "odo"),
                InputType.TYPE_CLASS_NUMBER);
        EditText note = Ui.labeled(this, form, "Notă (benzinărie...)", r == null ? "" : r.optString("note", ""),
                InputType.TYPE_CLASS_TEXT);
        CheckBox full = new CheckBox(this);
        full.setText("Plin (până la oprirea pistolului)");
        full.setTextColor(Ui.TEXT);
        full.setChecked(r == null || r.optBoolean("full"));
        form.addView(full);
        Ui.hint(this, form, "Consumul real vine din nivelul rezervorului citit de la mașină; „Plin” ajută doar ca rezervă.");
        showForm(r == null ? "Alimentare nouă" : "Editează alimentarea", form, () -> {
            JSONObject o = new JSONObject();
            if (r != null) o.put("id", r.optInt("id"));
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
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        int n = log == null ? 0 : log.length();
        titles.addView(Ui.text(this, n + " linii" + (eventsOnly ? " · doar evenimente" : ""), 17, Ui.TEXT, true));
        String version = n > 0 ? log.optJSONObject(0).optString("version", "") : "";
        titles.addView(Ui.text(this, (version.isEmpty() ? "" : "Aplicație " + version + " · ")
                + "ora e cea de pe navigație", 13, Ui.MUTED, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView toggle = Ui.text(this, eventsOnly ? "Și CAN" : "Doar evenimente", 14, Ui.ACCENT, false);
        toggle.setOnClickListener(v -> {
            eventsOnly = !eventsOnly;
            log = null;
            render();
            load(true);
        });
        head.addView(toggle);
        card.addView(head);
        if (log == null) {
            Ui.hint(this, card, "Se încarcă...");
            return;
        }
        if (n == 0) {
            Ui.hint(this, card, "Nicio linie primită încă.");
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
