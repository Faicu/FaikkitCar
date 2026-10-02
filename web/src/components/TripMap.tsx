import { useEffect, useRef } from "react";
import "leaflet/dist/leaflet.css";

import { duration, hm, type Trip, type TripPoint } from "../api";

// Harta traseului (Leaflet + OpenStreetMap). Leaflet atinge `window` la import,
// deci îl încărcăm doar în browser, din efect.
export function TripMap({ points, stops = [] }: { points: TripPoint[]; stops?: Trip["stops"] }) {
  const ref = useRef<HTMLDivElement>(null);
  // Lista de opriri vine nouă la fiecare reîmprospătare: harta se reface doar dacă diferă.
  const stopsKey = JSON.stringify(stops);

  useEffect(() => {
    const coords = points
      .filter((p) => p.lat !== null && p.lon !== null)
      .map((p) => [p.lat, p.lon] as [number, number]);
    if (!ref.current || coords.length === 0) return;
    let map: import("leaflet").Map | null = null;
    let cancelled = false;
    void import("leaflet").then((L) => {
      if (cancelled || !ref.current) return;
      map = L.map(ref.current, { zoomControl: true, attributionControl: true });
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: "© OpenStreetMap",
      }).addTo(map);
      if (coords.length === 1) {
        // Un singur punct: mașina parcată.
        L.circleMarker(coords[0], {
          radius: 9,
          color: "#fff",
          weight: 3,
          fillColor: "#38bdf8",
          fillOpacity: 1,
        }).addTo(map);
        map.setView(coords[0], 16);
        return;
      }
      const line = L.polyline(coords, { color: "#38bdf8", weight: 5, opacity: 0.9 }).addTo(map);
      L.circleMarker(coords[0], {
        radius: 7,
        color: "#fff",
        weight: 2,
        fillColor: "#22c55e",
        fillOpacity: 1,
      }).addTo(map);
      L.circleMarker(coords[coords.length - 1], {
        radius: 7,
        color: "#fff",
        weight: 2,
        fillColor: "#ef4444",
        fillOpacity: 1,
      }).addTo(map);
      // Opririle dintre părțile unei călătorii combinate (galben, cu ora și durata).
      for (const s of JSON.parse(stopsKey) as Trip["stops"]) {
        if (!s.pos) continue;
        L.circleMarker(s.pos, {
          radius: 9,
          color: "#fff",
          weight: 2,
          fillColor: "#f59e0b",
          fillOpacity: 1,
        })
          .bindTooltip(`Oprire ${duration(s.minutes)} · ${hm(s.from)}–${hm(s.to)}`, {
            permanent: true,
            direction: "top",
            offset: [0, -8],
          })
          .addTo(map);
      }
      map.fitBounds(line.getBounds(), { padding: [24, 24], maxZoom: 16 });
    });
    return () => {
      cancelled = true;
      map?.remove();
    };
  }, [points, stopsKey]);

  const hasCoords = points.some((p) => p.lat !== null);
  return hasCoords ? (
    <div ref={ref} className="h-72 w-full overflow-hidden rounded-2xl" />
  ) : (
    <p className="rounded-2xl glass-card p-4 text-sm text-muted-foreground">
      Călătoria nu are poziții GPS.
    </p>
  );
}
