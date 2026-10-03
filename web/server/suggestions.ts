// ---------------------------------------------------------------------------
// Propunerile: lucruri văzute în datele mașinii pe care utilizatorul le confirmă cu o atingere.
//   - o alimentare: nivelul rezervorului a sărit ≥ 3 L, dar nu e în jurnalul de alimentări;
//   - un loc: a parcat acolo de cel puțin 2 ori în ultimele 60 de zile, în afara locurilor salvate.
// Cele respinse rămân în tabela `dismissed`. Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { dataChanged, getDb } from "./db.ts";
import { fuelState } from "./fuel.ts";
import { levelRefills } from "./fuel-model.ts";
import { meters, placeAt, readPlaces, reverseGeocode } from "./places.ts";
import { readTrips } from "./trips.ts";

export interface RefuelSuggestion {
  kind: "refuel";
  key: string;
  at: string;
  liters: number; // ≈, din nivel (pași de 1 L): utilizatorul pune litrii de pe bon
  odo: number | null;
}

export interface PlaceSuggestion {
  kind: "place";
  key: string;
  lat: number;
  lon: number;
  visits: number;
  minutes: number; // cât a stat parcat acolo, în total
  lastAt: string;
  label: string | null; // strada și numărul, din OpenStreetMap
}

export type Suggestion = RefuelSuggestion | PlaceSuggestion;

/** O alimentare din jurnal se potrivește cu saltul nivelului la cel mult atâtea ore. */
const REFUEL_MATCH_MS = 3 * 3_600_000;
const PLACE_DAYS = 60;
const PLACE_CLUSTER_M = 100;
const PLACE_MIN_VISITS = 2;
/** O oprire cu motorul oprit mai scurtă de atât nu e o parcare (ex. un semafor lung). */
const MIN_PARK_MIN = 3;

interface Dismissed {
  kind: string;
  key: string;
  lat: number | null;
  lon: number | null;
}

function readDismissed(): Dismissed[] {
  return getDb().prepare(`SELECT kind, key, lat, lon FROM dismissed`).all() as unknown as Dismissed[];
}

function refuelSuggestions(dismissed: Dismissed[]): RefuelSuggestion[] {
  const { levels, refuels } = fuelState();
  return levelRefills(levels)
    .filter((j) => !refuels.some((r) => Math.abs(Date.parse(r.at) - Date.parse(j.at)) <= REFUEL_MATCH_MS))
    .filter((j) => !dismissed.some((d) => d.kind === "refuel" && d.key === j.at))
    .map((j) => ({ kind: "refuel" as const, key: j.at, at: j.at, liters: j.liters, odo: j.odo }));
}

/** Parcările (sosiri și opriri cu motorul oprit), grupate pe 100 m, în afara locurilor salvate. */
async function placeSuggestions(dismissed: Dismissed[]): Promise<PlaceSuggestion[]> {
  const places = readPlaces();
  const trips = readTrips(PLACE_DAYS).reverse(); // crescător
  // Fiecare parcare: unde și cât a stat (până la plecarea următoare, dacă o știm).
  const parks: Array<{ pos: [number, number]; at: string; minutes: number }> = [];
  trips.forEach((t, i) => {
    const next = trips[i + 1];
    if (t.endPos && !t.toPlace) {
      const minutes = next ? (Date.parse(next.start) - Date.parse(t.end)) / 60_000 : 0;
      parks.push({ pos: t.endPos, at: t.end, minutes });
    }
    for (const s of t.stops) {
      if (s.pos && !s.place && s.minutes >= MIN_PARK_MIN) parks.push({ pos: s.pos, at: s.from, minutes: s.minutes });
    }
  });
  const clusters: Array<{ lat: number; lon: number; visits: number; minutes: number; lastAt: string }> = [];
  for (const p of parks) {
    const c = clusters.find((c) => meters([c.lat, c.lon], p.pos) <= PLACE_CLUSTER_M);
    if (c) {
      // Centrul = media parcărilor (ca pinul să cadă unde parchează de obicei).
      c.lat = (c.lat * c.visits + p.pos[0]) / (c.visits + 1);
      c.lon = (c.lon * c.visits + p.pos[1]) / (c.visits + 1);
      c.visits++;
      c.minutes += p.minutes;
      if (p.at > c.lastAt) c.lastAt = p.at;
    } else {
      clusters.push({ lat: p.pos[0], lon: p.pos[1], visits: 1, minutes: p.minutes, lastAt: p.at });
    }
  }
  const found = clusters
    .filter((c) => c.visits >= PLACE_MIN_VISITS)
    .filter((c) => placeAt([c.lat, c.lon], places) === null)
    .filter(
      (c) =>
        !dismissed.some(
          (d) => d.kind === "place" && d.lat !== null && d.lon !== null && meters([d.lat, d.lon], [c.lat, c.lon]) <= PLACE_CLUSTER_M,
        ),
    )
    .sort((a, b) => b.visits - a.visits)
    .slice(0, 5);
  const out: PlaceSuggestion[] = [];
  for (const c of found) {
    out.push({
      kind: "place",
      key: `${c.lat.toFixed(5)},${c.lon.toFixed(5)}`,
      lat: c.lat,
      lon: c.lon,
      visits: c.visits,
      minutes: Math.round(c.minutes),
      lastAt: c.lastAt,
      label: await reverseGeocode(c.lat, c.lon),
    });
  }
  return out;
}

export async function readSuggestions(): Promise<Suggestion[]> {
  const dismissed = readDismissed();
  return [...refuelSuggestions(dismissed), ...(await placeSuggestions(dismissed))];
}

/** „Nu” la o propunere: nu mai apare. */
export function dismissSuggestion(kind: string, key: string, lat?: number, lon?: number): void {
  if (kind !== "refuel" && kind !== "place") throw new Error("Propunere necunoscută");
  getDb()
    .prepare(
      `INSERT OR IGNORE INTO dismissed (kind, key, lat, lon, created_at) VALUES (?, ?, ?, ?, ?)`,
    )
    .run(kind, String(key).slice(0, 80), lat ?? null, lon ?? null, new Date().toISOString());
  dataChanged();
}
