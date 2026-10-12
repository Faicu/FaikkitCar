import { useEffect, useRef, useState } from "react";
import "leaflet/dist/leaflet.css";

import { api, duration, hm, type Live, type LiveRoute } from "../api";

type Point = LiveRoute["points"][number];

/**
 * Traseul călătoriei în curs: la fiecare stare nouă de la mașină cere doar punctele noi
 * (`after`); la o călătorie nouă o ia de la capăt.
 */
function useLiveRoute(live: Live): { route: LiveRoute | null; points: Point[] } {
  const [route, setRoute] = useState<LiveRoute | null>(null);
  const [points, setPoints] = useState<Point[]>([]);
  const last = useRef<{ start: string; t: string } | null>(null);
  const tripStart = live.trip?.start ?? null;

  useEffect(() => {
    if (!tripStart) {
      last.current = null;
      setRoute(null);
      setPoints([]);
      return;
    }
    const abort = new AbortController();
    const same = last.current?.start === tripStart;
    api
      .liveRoute(same ? last.current!.t : null, abort.signal)
      .then((r) => {
        if (!r) return;
        const fresh = last.current?.start !== r.start;
        setRoute(r);
        setPoints((old) => (fresh ? r.points : [...old, ...r.points]));
        const t = r.points.length ? r.points[r.points.length - 1][0] : (fresh ? r.start : last.current!.t);
        last.current = { start: r.start, t };
      })
      .catch(() => {}); // la următoarea stare încearcă din nou
    return () => abort.abort();
  }, [tripStart, live.at]);

  return { route, points };
}

/**
 * Harta călătoriei în curs (Leaflet): verde = plecarea, linia = pe unde ai fost, albastru =
 * unde e mașina acum, galben = opririle cu motorul oprit. Harta se creează o singură dată și
 * urmărește mașina până o muți tu; „Urmărește mașina” o readuce.
 */
export function LiveTripMap({ live }: { live: Live }) {
  const { route, points } = useLiveRoute(live);
  const ref = useRef<HTMLDivElement>(null);
  const leaflet = useRef<{
    L: typeof import("leaflet");
    map: import("leaflet").Map;
    layer: import("leaflet").LayerGroup;
  } | null>(null);
  const [follow, setFollow] = useState(true);
  const followRef = useRef(true);
  followRef.current = follow;
  const [ready, setReady] = useState(false);

  // Harta, o singură dată.
  useEffect(() => {
    let cancelled = false;
    void import("leaflet").then((L) => {
      if (cancelled || !ref.current) return;
      const map = L.map(ref.current, { zoomControl: true, attributionControl: true });
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: "© OpenStreetMap",
      }).addTo(map);
      map.setView([44.4268, 26.1025], 13);
      // Doar mutarea făcută de mână oprește urmărirea (zoomul programatic nu).
      map.on("dragstart", () => setFollow(false));
      leaflet.current = { L, map, layer: L.layerGroup().addTo(map) };
      setReady(true);
    });
    return () => {
      cancelled = true;
      leaflet.current?.map.remove();
      leaflet.current = null;
    };
  }, []);

  // Straturile, la fiecare punct nou.
  useEffect(() => {
    const lf = leaflet.current;
    if (!lf || !route || points.length === 0) return;
    const { L, map, layer } = lf;
    layer.clearLayers();
    const coords = points.map((p) => [p[1], p[2]] as [number, number]);
    const line = L.polyline(coords, { color: "#38bdf8", weight: 5, opacity: 0.9 }).addTo(layer);
    L.circleMarker(coords[0], { radius: 7, color: "#fff", weight: 2, fillColor: "#22c55e", fillOpacity: 1 })
      .bindTooltip(`Plecare ${hm(route.start)}${route.fromPlace ? ` · ${route.fromPlace}` : ""}`, {
        direction: "top",
        offset: [0, -8],
      })
      .addTo(layer);
    for (const s of route.stops) {
      if (!s.pos) continue;
      L.circleMarker(s.pos, { radius: 8, color: "#fff", weight: 2, fillColor: "#f59e0b", fillOpacity: 1 })
        .bindTooltip(`${s.place ?? "Oprire"} ${duration(s.minutes)} · ${hm(s.from)}–${hm(s.to)}`, {
          direction: "top",
          offset: [0, -8],
        })
        .addTo(layer);
    }
    const here = coords[coords.length - 1];
    L.circleMarker(here, { radius: 10, color: "#fff", weight: 3, fillColor: "#0ea5e9", fillOpacity: 1 })
      .bindTooltip(`Acum · ${hm(points[points.length - 1][0])}`, { direction: "top", offset: [0, -10] })
      .addTo(layer);
    if (followRef.current) {
      map.fitBounds(line.getBounds(), { padding: [28, 28], maxZoom: 16, animate: false });
    }
  }, [ready, route, points, follow]);

  if (!live.trip) return null;
  return (
    <div className="relative">
      <div ref={ref} className="h-80 w-full overflow-hidden rounded-2xl" />
      {points.length === 0 && (
        <p className="pointer-events-none absolute inset-0 flex items-center justify-center rounded-2xl bg-black/40 text-sm text-muted-foreground">
          Aștept poziția GPS...
        </p>
      )}
      {!follow && (
        <button
          onClick={() => setFollow(true)}
          className="absolute bottom-3 right-3 z-[1000] rounded-lg bg-slate-900/90 px-3 py-1.5 text-sm text-sky-400 shadow"
        >
          Urmărește mașina
        </button>
      )}
    </div>
  );
}
