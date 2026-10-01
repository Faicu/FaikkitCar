// ---------------------------------------------------------------------------
// Starea de acum a mașinii: aplicația FaikkitCar trimite la ~15 s (POST /api/car/state)
// contactul dedus din cadrele de bord, turația, viteza etc. De aici: oprită / contact /
// motor pornit / în mers, călătoria în curs (din punctele de traseu) și autonomia.
// Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { readLastPosition, type Position } from "./car.ts";
import { getDb } from "./db.ts";
import { applyFuel, readFuelSummary } from "./fuel.ts";
import { readTrips, TRIP_GAP_MS, type Trip } from "./trips.ts";

export type CarState = "off" | "contact" | "engine" | "driving";

export interface IncomingState {
  t?: number;
  contact?: boolean;
  rpm?: number | null;
  cs?: number | null; // viteza CAN, km/h
  gs?: number | null; // viteza GPS, km/h
  v?: number | null;
  fuel?: number | null;
  temp?: number | null;
  odo?: number | null;
  lat?: number | null;
  lon?: number | null;
}

export interface LiveData {
  rpm: number | null;
  speed: number | null;
  volt: number | null;
  fuel: number | null;
  temp: number | null;
  odo: number | null;
}

export interface Live {
  state: CarState;
  since: string | null; // de când e în starea asta (null = necunoscut)
  at: string | null; // ultima stare primită de la mașină
  data: LiveData | null; // null cu mașina oprită
  trip: Trip | null; // călătoria în curs
  position: Position | null;
  tank: { liters: number; at: string } | null;
  // Câți km mai merge cu ce e în rezervor, la consumul mediu (real sau estimat).
  range: { km: number; lPer100: number; real: boolean } | null;
}

// Fără stare nouă atâta timp, unitatea doarme sau s-a oprit: mașina e oprită.
const STALE_MS = 90_000;

function num(x: unknown): number | null {
  return typeof x === "number" && Number.isFinite(x) ? x : null;
}

function stateOf(p: IncomingState): CarState {
  const speed = num(p.cs) ?? num(p.gs);
  if (speed !== null && speed >= 3) return "driving";
  if ((num(p.rpm) ?? 0) > 300) return "engine";
  return p.contact ? "contact" : "off";
}

interface Row {
  received_at: string;
  state: CarState;
  since: string;
  data: string;
}

function readRow(): Row | undefined {
  return getDb()
    .prepare(`SELECT received_at, state, since, data FROM car_state WHERE id = 1`)
    .get() as Row | undefined;
}

export function saveState(p: IncomingState): CarState {
  const now = new Date().toISOString();
  const state = stateOf(p);
  const prev = readRow();
  // O pauză lungă înseamnă că între timp a fost oprită: starea începe acum.
  const fresh = prev && Date.now() - new Date(prev.received_at).getTime() < STALE_MS;
  const since = fresh && prev.state === state ? prev.since : now;
  const data: LiveData = {
    rpm: num(p.rpm),
    speed: num(p.cs) ?? num(p.gs),
    volt: num(p.v),
    fuel: (num(p.fuel) ?? 0) > 0 ? num(p.fuel) : null,
    temp: num(p.temp),
    odo: (num(p.odo) ?? 0) > 0 ? num(p.odo) : null,
  };
  getDb()
    .prepare(
      `INSERT INTO car_state (id, received_at, state, since, data) VALUES (1, ?, ?, ?, ?)
       ON CONFLICT (id) DO UPDATE SET received_at = excluded.received_at, state = excluded.state,
         since = excluded.since, data = excluded.data`,
    )
    .run(now, state, since, JSON.stringify(data));
  return state;
}

export function readLive(): Live {
  const row = readRow();
  const stale = !row || Date.now() - new Date(row.received_at).getTime() >= STALE_MS;
  let state: CarState = "off";
  let since: string | null = null;
  let data: LiveData | null = null;
  if (row && !stale) {
    state = row.state;
    since = row.since;
    data = JSON.parse(row.data) as LiveData;
  } else if (row) {
    // Oprită de la ultima stare primită (sau de când a raportat ea „oprită”).
    since = row.state === "off" ? row.since : row.received_at;
  }

  // Călătoria în curs: ultima, dacă n-a trecut pauza care desparte călătoriile.
  const last = readTrips(1)[0];
  const trip =
    last && state !== "off" && Date.now() - new Date(last.end).getTime() < TRIP_GAP_MS
      ? applyFuel([last])[0]
      : null;

  const fuel = readFuelSummary();
  const tank =
    data?.fuel != null ? { liters: data.fuel, at: row!.received_at } : fuel.tank;
  let range: Live["range"] = null;
  const real = fuel.avgLPer100 !== null;
  const lPer100 = fuel.avgLPer100 ?? estimatedLPer100();
  if (tank && lPer100 !== null && lPer100 > 0) {
    range = { km: Math.round((tank.liters / lPer100) * 100), lPer100: Math.round(lPer100 * 10) / 10, real };
  }
  return { state, since, at: row?.received_at ?? null, data, trip, position: readLastPosition(), tank, range };
}

/** Consumul estimat pe ultimele 60 de zile, cât nu există încă unul real din rezervor. */
function estimatedLPer100(): number | null {
  const trips = applyFuel(readTrips(60));
  const km = trips.reduce((s, t) => s + t.distanceKm, 0);
  const liters = trips.reduce((s, t) => s + (t.fuelL ?? 0), 0);
  return km >= 5 ? (liters / km) * 100 : null;
}
