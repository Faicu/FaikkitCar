// ---------------------------------------------------------------------------
// Călătoriile mașinii (FaikkitCar): puncte de traseu cu GPS și date CAN, primite
// prin POST /api/car/trip. O călătorie = puncte consecutive fără pauză mai lungă
// de TRIP_GAP_MS (sau combinate de utilizator); valorile vin din trip-math.ts și se
// recalculează doar la date noi (memo din db.ts). Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { dataChanged, getDb, memo } from "./db.ts";
import { placeAt, readPlaces } from "./places.ts";
import {
  analyze,
  canScale,
  haversineKm,
  pos,
  speedOf,
  type PointRow,
  type TripMetrics,
  type TripStop,
} from "./trip-math.ts";

export interface IncomingPoint {
  t: number; // epoch ms, ceasul navigației
  lat?: number | null;
  lon?: number | null;
  alt?: number | null;
  acc?: number | null; // precizia GPS, metri
  gs?: number | null; // viteza GPS, km/h
  cs?: number | null; // viteza de la mașină (CAN), km/h
  rpm?: number | null;
  v?: number | null; // tensiunea bateriei, V
  temp?: number | null; // temperatura exterioară, °C
  odo?: number | null; // kilometraj, km
  fuel?: number | null; // litri în rezervor
  ic?: number | null; // probabil consumul instantaneu al bordului (c1033), L/100 km x10
  cv?: number | null; // tensiunea minimă la pornirea motorului (demarorul), V
}

export interface TripPoint {
  t: string;
  lat: number | null;
  lon: number | null;
  speed: number | null; // CAN dacă există, altfel GPS
  rpm: number | null;
}

export type { TripStop };

export interface Trip extends TripMetrics {
  parts: number; // călătorii combinate de utilizator (1 = una singură)
  fromPlace: string | null; // locurile salvate (places.ts) de la plecare și sosire
  toPlace: string | null;
  idle: boolean; // pornire pe loc sau manevră (sub IDLE_KM): ascunsă la cerere
  // Completate de fuel.ts cu factorul de calibrare și prețul de atunci.
  fuelL: number | null;
  lPer100: number | null;
  cost: number | null;
}

export const MAX_POINTS_PER_REQUEST = 500;
/** O pauză mai lungă desparte două călătorii (dacă nu sunt combinate). */
export const TRIP_GAP_MS = 5 * 60_000;
// Sub atâția km e o pornire pe loc sau o manevră în parcare (01.10: 13 m cu 12,9 km/h),
// oricât de repede a mers; totalurile le includ, doar lista le poate ascunde.
const IDLE_KM = 0.5;
const MAX_POINTS = 2_000_000;
// Câte zile de istoric se calculează (statisticile pe 12 luni au nevoie de ~372).
const HISTORY_DAYS = 400;
// Plecarea e locul unde a parcat la sfârșitul drumului anterior, dacă primul fix GPS e la
// cel mult atât de el (GPS-ul prinde semnal uneori abia după câteva sute de metri).
const PARKED_MATCH_KM = 0.3;

function num(x: unknown): number | null {
  return typeof x === "number" && Number.isFinite(x) ? x : null;
}

/** Inserează un lot de puncte; întoarce câte au intrat (duplicatele după oră se ignoră). */
export function insertPoints(points: IncomingPoint[]): number {
  const db = getDb();
  const now = new Date().toISOString();
  const stmt = db.prepare(
    `INSERT OR IGNORE INTO trip_point
       (device_at, received_at, lat, lon, alt, acc, gps_speed, can_speed, rpm, volt, temp, odo, fuel, cons, crank)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  );
  let added = 0;
  db.exec("BEGIN");
  try {
    for (const p of points) {
      if (!Number.isFinite(p?.t)) continue;
      const rpm = num(p.rpm);
      const odo = num(p.odo);
      const r = stmt.run(
        new Date(p.t).toISOString(),
        now,
        num(p.lat),
        num(p.lon),
        num(p.alt),
        num(p.acc),
        num(p.gs),
        num(p.cs),
        rpm === null ? null : Math.round(rpm),
        num(p.v),
        num(p.temp),
        odo === null ? null : Math.round(odo),
        num(p.fuel),
        num(p.ic),
        num(p.cv),
      );
      added += Number(r.changes);
    }
    db.prepare(
      `DELETE FROM trip_point WHERE id <= (SELECT id FROM trip_point ORDER BY id DESC LIMIT 1 OFFSET ?)`,
    ).run(MAX_POINTS);
    db.exec("COMMIT");
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
  if (added > 0) dataChanged();
  return added;
}

// Rezumatele bucăților deja calculate: în mers vin puncte noi la ~10 s, dar se schimbă doar
// ultima călătorie, deci celelalte nu se mai recalculează.
const summaries = new Map<string, TripMetrics>();

function analyzeCached(rows: PointRow[], scale: number): TripMetrics {
  const key = `${rows[0].device_at}|${rows[rows.length - 1].device_at}|${rows.length}|${scale}`;
  let m = summaries.get(key);
  if (!m) {
    if (summaries.size > 5_000) summaries.clear();
    m = analyze(rows, scale);
    summaries.set(key, m);
  }
  return m;
}

/** Toate călătoriile din istoric, crescător; recalculate doar la date noi. */
const allTrips = memo((): Trip[] => {
  const since = new Date(Date.now() - HISTORY_DAYS * 86_400_000).toISOString();
  const rows = getDb()
    .prepare(
      `SELECT device_at, lat, lon, acc, gps_speed, can_speed, rpm, volt, temp, odo, fuel, cons, crank
       FROM trip_point WHERE device_at >= ? ORDER BY device_at`,
    )
    .all(since) as unknown as PointRow[];
  const scale = canScale(rows);
  // Întâi bucățile despărțite de pauze; trei-patru puncte izolate (ex. o repornire pe loc)
  // nu sunt o călătorie.
  const groups: PointRow[][] = [];
  let cur: PointRow[] = [];
  for (const r of rows) {
    const prev = cur[cur.length - 1];
    if (prev && Date.parse(r.device_at) - Date.parse(prev.device_at) > TRIP_GAP_MS) {
      groups.push(cur);
      cur = [];
    }
    cur.push(r);
  }
  if (cur.length) groups.push(cur);
  // Apoi cele combinate de utilizator (tabela trip_join): bucățile consecutive care încep
  // în același interval devin o singură călătorie.
  const joins = readJoins();
  const joinOf = (g: PointRow[]) =>
    joins.findIndex((j) => g[0].device_at >= j.start && g[0].device_at <= j.end);
  const merged: Array<{ rows: PointRow[]; parts: number }> = [];
  let mergedJoin = -1;
  for (const g of groups.filter((g) => g.length >= 5)) {
    const j = joinOf(g);
    const last = merged[merged.length - 1];
    if (last && j >= 0 && j === mergedJoin) {
      last.rows = last.rows.concat(g);
      last.parts++;
    } else {
      merged.push({ rows: g, parts: 1 });
    }
    mergedJoin = j;
  }
  const places = readPlaces();
  let parkedAt: [number, number] | null = null;
  return merged.map(({ rows: r, parts }) => {
    const m = analyzeCached(r, scale);
    // Unde a plecat: unde a parcat ultima dată, dacă primul fix e aproape (fix întârziat).
    const from =
      parkedAt && m.startPos && haversineKm(parkedAt, m.startPos) <= PARKED_MATCH_KM
        ? parkedAt
        : m.startPos;
    parkedAt = m.endPos ?? parkedAt;
    return {
      ...m,
      parts,
      stops: m.stops.map((s) => ({ ...s, place: placeAt(s.pos, places) })),
      fromPlace: placeAt(from, places),
      toPlace: placeAt(m.endPos, places),
      idle: parts === 1 && m.distanceKm < IDLE_KM,
      fuelL: null,
      lPer100: null,
      cost: null,
    };
  });
});

/** Călătoriile începute în ultimele `days` zile, cele mai noi primele. */
export function readTrips(days = 60): Trip[] {
  return readTripsSince(new Date(Date.now() - days * 86_400_000).toISOString());
}

/** Călătoriile începute după `since` (ISO), cele mai noi primele. */
export function readTripsSince(since: string): Trip[] {
  return allTrips()
    .filter((t) => t.start >= since)
    .reverse();
}

interface Join {
  start: string;
  end: string;
}

function readJoins(): Join[] {
  return getDb().prepare(`SELECT start, end FROM trip_join ORDER BY start`).all() as unknown as Join[];
}

/**
 * Combină călătoriile dintre `start` (plecarea primei) și `end` (sosirea ultimei) într-una
 * singură, ex. dus-întors cu o oprire scurtă. Intervalele care se suprapun se unesc.
 */
export function joinTrips(start: string, end: string): void {
  if (!(start < end)) throw new Error("Interval invalid");
  const db = getDb();
  const overlapping = db
    .prepare(`SELECT id, start, end FROM trip_join WHERE start <= ? AND end >= ?`)
    .all(end, start) as Array<{ id: number; start: string; end: string }>;
  const from = [start, ...overlapping.map((j) => j.start)].sort()[0];
  const to = [end, ...overlapping.map((j) => j.end)].sort().reverse()[0];
  db.exec("BEGIN");
  try {
    for (const j of overlapping) db.prepare(`DELETE FROM trip_join WHERE id = ?`).run(j.id);
    db.prepare(`INSERT INTO trip_join (start, end, created_at) VALUES (?, ?, ?)`).run(
      from,
      to,
      new Date().toISOString(),
    );
    db.exec("COMMIT");
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
  dataChanged();
}

/** Desparte la loc călătoria combinată care începe la `start`. */
export function splitTrip(start: string): void {
  getDb().prepare(`DELETE FROM trip_join WHERE start <= ? AND end >= ?`).run(start, start);
  dataChanged();
}

/** Punctele unei călătorii (între `start` și `end`, inclusiv), pentru hartă și grafic. */
export function readTripPoints(start: string, end: string): TripPoint[] {
  const rows = getDb()
    .prepare(
      `SELECT device_at, lat, lon, acc, gps_speed, can_speed, rpm FROM trip_point
       WHERE device_at BETWEEN ? AND ? ORDER BY device_at`,
    )
    .all(start, end) as unknown as PointRow[];
  return rows.map((r) => {
    const p = pos(r);
    return {
      t: r.device_at,
      lat: p ? p[0] : null,
      lon: p ? p[1] : null,
      speed: speedOf(r),
      rpm: r.rpm,
    };
  });
}
