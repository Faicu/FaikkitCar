// ---------------------------------------------------------------------------
// Locurile salvate (Acasă, Serviciu...): un nume, o poziție și o rază. Călătoriile primesc
// „de la” / „până la” după ele, iar mașina parcată spune unde stă. Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { dataChanged, getDb } from "./db.ts";

export interface Place {
  id: number;
  name: string;
  lat: number;
  lon: number;
  radius: number; // metri
}

export interface PlaceInput {
  id?: number;
  name: string;
  lat: number;
  lon: number;
  radius?: number;
}

const DEFAULT_RADIUS = 100;

export function readPlaces(): Place[] {
  return getDb()
    .prepare(`SELECT id, name, lat, lon, radius FROM place ORDER BY name`)
    .all() as unknown as Place[];
}

function meters(a: [number, number], b: [number, number]): number {
  const rad = Math.PI / 180;
  const dLat = (b[0] - a[0]) * rad;
  const dLon = (b[1] - a[1]) * rad;
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * rad) * Math.cos(b[0] * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
}

/** Locul cel mai apropiat în a cărui rază e poziția, sau null. */
export function placeAt(pos: [number, number] | null, places = readPlaces()): string | null {
  if (!pos) return null;
  let best: { name: string; d: number } | null = null;
  for (const p of places) {
    const d = meters(pos, [p.lat, p.lon]);
    if (d <= p.radius && (!best || d < best.d)) best = { name: p.name, d };
  }
  return best?.name ?? null;
}

export function savePlace(input: PlaceInput): void {
  const name = String(input.name ?? "")
    .trim()
    .slice(0, 40);
  if (!name) throw new Error("Numele lipsește");
  const lat = Number(input.lat);
  const lon = Number(input.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
    throw new Error("Poziția e invalidă");
  }
  const radius = Math.round(Math.min(2000, Math.max(30, Number(input.radius) || DEFAULT_RADIUS)));
  const db = getDb();
  if (input.id) {
    db.prepare(`UPDATE place SET name = ?, lat = ?, lon = ?, radius = ? WHERE id = ?`).run(
      name,
      lat,
      lon,
      radius,
      input.id,
    );
  } else {
    db.prepare(`INSERT INTO place (name, lat, lon, radius, created_at) VALUES (?, ?, ?, ?, ?)`).run(
      name,
      lat,
      lon,
      radius,
      new Date().toISOString(),
    );
  }
  dataChanged();
}

export function deletePlace(id: number): void {
  getDb().prepare(`DELETE FROM place WHERE id = ?`).run(id);
  dataChanged();
}

export interface GeocodeResult {
  label: string;
  lat: number;
  lon: number;
}

// Căutarea adreselor prin OpenStreetMap (Nominatim): cel mult o cerere pe secundă, cu un
// User-Agent propriu, iar rezultatele se țin minte (aceeași căutare nu mai pleacă din nou).
const geocodeCache = new Map<string, GeocodeResult[]>();
let lastGeocode = 0;

/** Adresele găsite pentru `q` (ex. „Bulevardul Timișoara 48”), în România, cel mult 5. */
export async function geocode(q: string): Promise<GeocodeResult[]> {
  const query = q.trim().slice(0, 120);
  if (query.length < 3) return [];
  const cached = geocodeCache.get(query.toLowerCase());
  if (cached) return cached;
  const wait = lastGeocode + 1_100 - Date.now();
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  lastGeocode = Date.now();
  const url =
    `https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=ro&limit=5` +
    `&accept-language=ro&q=${encodeURIComponent(query)}`;
  const res = await fetch(url, {
    headers: { "User-Agent": "FaikkitCar/1.0 (car.faicu.ro)" },
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) throw new Error(`Căutarea adresei a eșuat (${res.status})`);
  const rows = (await res.json()) as Array<{ display_name: string; lat: string; lon: string }>;
  const results = rows.map((r) => ({
    // Fără „România” și codul poștal la coadă: „48, Bulevardul Timișoara, Sector 6, București”.
    label: r.display_name.replace(/, \d{6}/, "").replace(/, România$/, ""),
    lat: Number(r.lat),
    lon: Number(r.lon),
  }));
  if (geocodeCache.size > 500) geocodeCache.clear();
  geocodeCache.set(query.toLowerCase(), results);
  return results;
}
