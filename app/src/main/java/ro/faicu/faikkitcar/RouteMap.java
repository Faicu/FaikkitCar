package ro.faicu.faikkitcar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;

/**
 * Harta calatoriei in curs (sau a ultimei) pe ecranul Acasa (OpenStreetMap, osmdroid): verde = plecarea,
 * linia = pe unde a fost, albastru = unde e masina acum, galben = opririle cu motorul oprit.
 * Aceeasi harta ramane intre redesenari si primeste doar punctele noi de la /api/car/route;
 * urmareste masina pana o misti, apoi „Urmareste” o readuce.
 */
final class RouteMap {
    final MapView map;
    private final Context c;
    private final Polyline line;
    private final List<GeoPoint> geo = new ArrayList<>();
    private final List<Marker> stopMarkers = new ArrayList<>();
    private Marker start, car;
    volatile String tripStart; // calatoria afisata
    volatile String lastT; // ora ultimului punct primit
    boolean follow = true;
    Runnable onTouchEnd; // dupa un gest (apare „Urmareste masina”)

    static void init(Context c) {
        // OpenStreetMap cere un User-Agent propriu; cache-ul de dale sta in folderul aplicatiei.
        Configuration.getInstance().setUserAgentValue("FaikkitCar");
        Configuration.getInstance().setOsmdroidBasePath(c.getCacheDir());
        Configuration.getInstance().setOsmdroidTileCache(new java.io.File(c.getCacheDir(), "tiles"));
    }

    @SuppressLint("ClickableViewAccessibility")
    RouteMap(Context c) {
        this.c = c;
        map = new MapView(c);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        // Ecranul se reconstruieste des: harta se muta in pagina noua, nu se distruge.
        map.setDestroyMode(false);
        map.getZoomController().setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);
        map.setOnTouchListener((v, e) -> {
            // Harta sta intr-un ScrollView: cat o atingi, pagina nu trebuie sa se miste.
            boolean end = e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL;
            v.getParent().requestDisallowInterceptTouchEvent(!end);
            if (e.getAction() == MotionEvent.ACTION_MOVE) follow = false;
            if (end && onTouchEnd != null) map.post(onTouchEnd);
            return false;
        });
        line = new Polyline(map);
        line.getOutlinePaint().setColor(Ui.ACCENT);
        line.getOutlinePaint().setStrokeWidth(Ui.dp(c, 6));
        map.getOverlays().add(line);
        map.getController().setZoom(13.0);
        map.getController().setCenter(new GeoPoint(44.4268, 26.1025));
    }

    private Marker dot(GeoPoint p, int color, int sizeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(Ui.dp(c, 3), Color.WHITE);
        int s = Ui.dp(c, sizeDp);
        d.setSize(s, s);
        Marker m = new Marker(map);
        m.setPosition(p);
        m.setIcon(d);
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        m.setInfoWindow(null);
        return m;
    }

    boolean hasPoints() {
        return !geo.isEmpty();
    }

    /** Adauga raspunsul serverului (sau o ia de la capat la o calatorie noua). */
    void update(JSONObject route) {
        String s = route.optString("start");
        if (!s.equals(tripStart)) {
            tripStart = s;
            lastT = null;
            geo.clear();
            follow = true;
        }
        JSONArray pts = route.optJSONArray("points");
        for (int i = 0; pts != null && i < pts.length(); i++) {
            JSONArray p = pts.optJSONArray(i);
            geo.add(new GeoPoint(p.optDouble(1), p.optDouble(2)));
            lastT = p.optString(0);
        }
        if (geo.isEmpty()) return;
        line.setPoints(geo);
        if (start != null) map.getOverlays().remove(start);
        start = dot(geo.get(0), Ui.OK, 16);
        map.getOverlays().add(start);
        for (Marker m : stopMarkers) map.getOverlays().remove(m);
        stopMarkers.clear();
        JSONArray stops = route.optJSONArray("stops");
        for (int i = 0; stops != null && i < stops.length(); i++) {
            JSONArray pos = stops.optJSONObject(i).optJSONArray("pos");
            if (pos == null) continue;
            Marker m = dot(new GeoPoint(pos.optDouble(0), pos.optDouble(1)), Ui.WARN, 16);
            stopMarkers.add(m);
            map.getOverlays().add(m);
        }
        if (car != null) map.getOverlays().remove(car);
        car = dot(geo.get(geo.size() - 1), 0xFF0EA5E9, 24);
        map.getOverlays().add(car); // ultima, deasupra liniei
        if (follow) fit();
        map.invalidate();
    }

    /** Tot traseul pe ecran (sau masina, cat e un singur loc). */
    void fit() {
        follow = true;
        if (geo.isEmpty()) return;
        BoundingBox box = BoundingBox.fromGeoPointsSafe(geo);
        Runnable go = () -> {
            if (box.getLatitudeSpan() < 0.002 && box.getLongitudeSpan() < 0.002) {
                map.getController().setZoom(16.5);
                map.getController().setCenter(geo.get(geo.size() - 1));
            } else {
                map.zoomToBoundingBox(box, false, Ui.dp(c, 40));
            }
        };
        if (map.getWidth() > 0) go.run();
        else map.addOnFirstLayoutListener((v, l, t, r, b) -> go.run());
    }
}
