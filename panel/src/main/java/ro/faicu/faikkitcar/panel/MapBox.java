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

    /** Traseul: linie albastra, verde = plecare, rosu = sosire. Null daca nu are GPS. */
    static MapView route(Context c, JSONArray points) {
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
}
