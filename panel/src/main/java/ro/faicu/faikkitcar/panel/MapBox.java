package ro.faicu.faikkitcar.panel;

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

/** Harta OpenStreetMap (osmdroid): pozitia masinii sau traseul unei calatorii. */
final class MapBox {
    private MapBox() {}

    static void init(Context c) {
        // OpenStreetMap cere un User-Agent propriu; cache-ul de dale sta in folderul aplicatiei.
        Configuration.getInstance().setUserAgentValue(c.getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(c.getCacheDir());
    }

    @SuppressLint("ClickableViewAccessibility")
    private static MapView base(Context c) {
        MapView map = new MapView(c);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getZoomController().setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);
        // Harta sta intr-un ScrollView: cat o atingi, pagina nu trebuie sa se miste.
        map.setOnTouchListener((v, e) -> {
            v.getParent().requestDisallowInterceptTouchEvent(e.getAction() != MotionEvent.ACTION_UP
                    && e.getAction() != MotionEvent.ACTION_CANCEL);
            return false;
        });
        return map;
    }

    private static Marker dot(Context c, MapView map, GeoPoint p, int color, int sizeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(Ui.dp(c, 2), Color.WHITE);
        int s = Ui.dp(c, sizeDp);
        d.setSize(s, s);
        Marker m = new Marker(map);
        m.setPosition(p);
        m.setIcon(d);
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        m.setInfoWindow(null);
        return m;
    }

    /** Un singur punct: masina parcata. */
    static MapView position(Context c, double lat, double lon) {
        MapView map = base(c);
        GeoPoint p = new GeoPoint(lat, lon);
        map.getOverlays().add(dot(c, map, p, Ui.ACCENT, 18));
        map.getController().setZoom(16.5);
        map.getController().setCenter(p);
        return map;
    }

    /**
     * Traseul: linie albastra, verde = plecare, rosu = sosire, galben = opririle dintre partile
     * unei calatorii combinate (atinge punctul pentru ora si durata). Null daca nu are GPS.
     */
    static MapView route(Context c, JSONArray points, JSONArray stops) {
        List<GeoPoint> geo = new ArrayList<>();
        for (int i = 0; i < points.length(); i++) {
            JSONObject p = points.optJSONObject(i);
            if (p.isNull("lat") || p.isNull("lon")) continue;
            geo.add(new GeoPoint(p.optDouble("lat"), p.optDouble("lon")));
        }
        if (geo.isEmpty()) return null;
        MapView map = base(c);
        if (geo.size() == 1) {
            map.getOverlays().add(dot(c, map, geo.get(0), Ui.ACCENT, 18));
            map.getController().setZoom(16.5);
            map.getController().setCenter(geo.get(0));
            return map;
        }
        Polyline line = new Polyline(map);
        line.setPoints(geo);
        line.getOutlinePaint().setColor(Ui.ACCENT);
        line.getOutlinePaint().setStrokeWidth(Ui.dp(c, 5));
        map.getOverlays().add(line);
        map.getOverlays().add(dot(c, map, geo.get(0), 0xFF22C55E, 14));
        map.getOverlays().add(dot(c, map, geo.get(geo.size() - 1), 0xFFEF4444, 14));
        for (int i = 0; stops != null && i < stops.length(); i++) {
            JSONObject s = stops.optJSONObject(i);
            JSONArray pos = s.optJSONArray("pos");
            if (pos == null) continue;
            Marker m = dot(c, map, new GeoPoint(pos.optDouble(0), pos.optDouble(1)), Ui.WARN, 18);
            // Bula standard osmdroid, cu titlul: „Oprire 10 min · 23:32–23:42”.
            m.setInfoWindow(new org.osmdroid.views.overlay.infowindow.MarkerInfoWindow(
                    org.osmdroid.library.R.layout.bonuspack_bubble, map));
            m.setTitle((s.isNull("place") ? "Oprire " : s.optString("place") + " ")
                    + Fmt.duration(s.optDouble("minutes")) + " · "
                    + Fmt.hm(s.optString("from")) + "–" + Fmt.hm(s.optString("to")));
            map.getOverlays().add(m);
        }
        BoundingBox box = BoundingBox.fromGeoPointsSafe(geo);
        // zoomToBoundingBox merge doar dupa ce harta are dimensiuni.
        map.addOnFirstLayoutListener((v, l, t, r, b) -> {
            if (box.getLatitudeSpan() < 0.002 && box.getLongitudeSpan() < 0.002) {
                map.getController().setZoom(16.5);
                map.getController().setCenter(box.getCenterWithDateLine());
            } else {
                map.zoomToBoundingBox(box, false, Ui.dp(c, 28));
            }
        });
        map.getController().setCenter(box.getCenterWithDateLine());
        map.getController().setZoom(14.0);
        return map;
    }

    /**
     * Harta pentru alegerea unui loc: pinul se muta atingand harta sau tragandu-l; cercul arata
     * raza. `onPin` primeste fiecare pozitie noua.
     */
    static final class Picker {
        final MapView map;
        private final Context c;
        private final java.util.function.BiConsumer<Double, Double> onPin;
        private Marker marker;
        private org.osmdroid.views.overlay.Polygon circle;
        private int radius;

        Picker(Context c, Double lat, Double lon, int radius, java.util.function.BiConsumer<Double, Double> onPin) {
            this.c = c;
            this.onPin = onPin;
            this.radius = radius;
            map = base(c);
            map.getOverlays().add(new org.osmdroid.views.overlay.MapEventsOverlay(
                    new org.osmdroid.events.MapEventsReceiver() {
                        @Override
                        public boolean singleTapConfirmedHelper(GeoPoint p) {
                            set(p.getLatitude(), p.getLongitude(), false);
                            onPin.accept(p.getLatitude(), p.getLongitude());
                            return true;
                        }

                        @Override
                        public boolean longPressHelper(GeoPoint p) {
                            return false;
                        }
                    }));
            if (lat != null && lon != null) {
                set(lat, lon, true);
            } else {
                // Bucuresti, cat nu avem alta pozitie.
                map.getController().setZoom(12.0);
                map.getController().setCenter(new GeoPoint(44.4268, 26.1025));
            }
        }

        /** Muta pinul (si centreaza harta, daca `center`). */
        void set(double lat, double lon, boolean center) {
            GeoPoint p = new GeoPoint(lat, lon);
            if (marker == null) {
                marker = dot(c, map, p, Ui.ACCENT, 20);
                marker.setDraggable(true);
                marker.setOnMarkerDragListener(new Marker.OnMarkerDragListener() {
                    @Override
                    public void onMarkerDrag(Marker m) {}

                    @Override
                    public void onMarkerDragStart(Marker m) {}

                    @Override
                    public void onMarkerDragEnd(Marker m) {
                        GeoPoint q = m.getPosition();
                        drawCircle(q);
                        onPin.accept(q.getLatitude(), q.getLongitude());
                    }
                });
                circle = new org.osmdroid.views.overlay.Polygon(map);
                circle.getFillPaint().setColor(0x2238BDF8);
                circle.getOutlinePaint().setColor(Ui.ACCENT);
                circle.getOutlinePaint().setStrokeWidth(Ui.dp(c, 1));
                map.getOverlays().add(circle);
                map.getOverlays().add(marker);
            }
            marker.setPosition(p);
            drawCircle(p);
            if (center) {
                map.getController().setZoom(17.0);
                map.getController().setCenter(p);
            }
            map.invalidate();
        }

        void setRadius(int r) {
            radius = r;
            if (marker != null) drawCircle(marker.getPosition());
            map.invalidate();
        }

        private void drawCircle(GeoPoint p) {
            circle.setPoints(org.osmdroid.views.overlay.Polygon.pointsAsCircle(p, radius));
        }
    }
}
