// ---------------------------------------------------------------------------
// Călătoriile mașinii (FaikkitCar): puncte de traseu cu GPS și date CAN, primite
// prin POST /api/car/trip. O călătorie = puncte consecutive fără pauză mai lungă
// de TRIP_GAP_MS; statisticile se calculează la citire. Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { getDb } from "./db.ts";
import { estimateFuel } from "./fuel-model.ts";
import { placeAt, readPlaces } from "./places.ts";

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
}

export interface TripPoint {
  t: string;
  lat: number | null;
  lon: number | null;
  speed: number | null; // CAN dacă există, altfel GPS
  rpm: number | null;
}

/** O oprire dintre părțile unei călătorii combinate: unde a stat mașina și cât. */
export interface TripStop {
  from: string; // ultimul punct înainte de oprire
  to: string; // primul punct după
  minutes: number;
  pos: [number, number] | null;
  place: string | null; // locul salvat, dacă oprirea e într-unul
}

export interface Trip {
  start: string;
  end: string;
  points: number;
  distanceKm: number;
  durationMin: number; // de la plecare la sosire, cu tot cu opririle dintre părți
  parts: number; // călătorii combinate de utilizator (1 = una singură)
  stopMin: number; // opririle dintre părți (motor oprit), scăzute din viteza medie
  stops: TripStop[];
  maxSpeed: number | null;
  avgSpeed: number | null; // km/h, pe durata fără opririle dintre părți
  maxRpm: number | null;
  odoStart: number | null;
  odoEnd: number | null;
  fuelStart: number | null; // litri în rezervor (CAN), la plecare și la sosire
  fuelEnd: number | null;
  tempC: number | null;
  minVolt: number | null;
  startPos: [number, number] | null;
  endPos: [number, number] | null;
  fromPlace: string | null; // locurile salvate (places.ts) de la plecare și sosire
  toPlace: string | null;
  modelLiters: number; // estimarea brută, necalibrată (fuel-model.ts)
  idleMin: number; // pe loc cu motorul pornit
  idle: boolean; // pornire pe loc sau manevră (sub IDLE_KM): ascunsă la cerere
  // Completate de fuel.ts cu factorul din alimentări și prețul de atunci.
  fuelL: number | null;
  lPer100: number | null;
  cost: number | null;
}

export const MAX_POINTS_PER_REQUEST = 500;
export const TRIP_GAP_MS = 5 * 60_000;
// Sub atâția km e o pornire pe loc sau o manevră în parcare (01.10: 13 m cu 12,9 km/h),
// oricât de repede a mers; totalurile le includ, doar lista le poate ascunde.
const IDLE_KM = 0.5;
const MAX_POINTS = 2_000_000;

function num(x: unknown): number | null {
  return typeof x === "number" && Number.isFinite(x) ? x : null;
}

/** Inserează un lot de puncte; întoarce câte au intrat (duplicatele după oră se ignoră). */
export function insertPoints(points: IncomingPoint[]): number {
  const db = getDb();
  const now = new Date().toISOString();
  const stmt = db.prepare(
    `INSERT OR IGNORE INTO trip_point
       (device_at, received_at, lat, lon, alt, acc, gps_speed, can_speed, rpm, volt, temp, odo, fuel, cons)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
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
  return added;
}

interface Row {
  device_at: string;
  lat: number | null;
  lon: number | null;
  acc: number | null;
  gps_speed: number | null;
  can_speed: number | null;
  rpm: number | null;
  volt: number | null;
  temp: number | null;
  odo: number | null;
  fuel: number | null;
}

function haversineKm(a: [number, number], b: [number, number]): number {
  const R = 6371;
  const rad = Math.PI / 180;
  const dLat = (b[0] - a[0]) * rad;
  const dLon = (b[1] - a[1]) * rad;
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * rad) * Math.cos(b[0] * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

function pos(r: Row): [number, number] | null {
  if (r.lat === null || r.lon === null) return null;
  if (r.acc !== null && r.acc > 60) return null; // fix GPS prea imprecis
  return [r.lat, r.lon];
}

function summarize(rows: Row[], parts = 1): Trip {
  let gpsKm = 0;
  let last: [number, number] | null = null;
  let maxSpeed: number | null = null;
  let maxRpm: number | null = null;
  let minVolt: number | null = null;
  let startPos: [number, number] | null = null;
  let endPos: [number, number] | null = null;
  for (const r of rows) {
    const p = pos(r);
    if (p) {
      // Săriturile de peste 2 km între două puncte sunt erori de GPS, nu drum.
      if (last) {
        const d = haversineKm(last, p);
        if (d < 2) gpsKm += d;
      }
      last = p;
      startPos ??= p;
      endPos = p;
    }
    const speed = r.can_speed ?? r.gps_speed;
    if (speed !== null) maxSpeed = Math.max(maxSpeed ?? 0, speed);
    if (r.rpm !== null) maxRpm = Math.max(maxRpm ?? 0, r.rpm);
    if (r.volt !== null && r.volt > 5) minVolt = Math.min(minVolt ?? 99, r.volt);
  }
  const odos = rows.map((r) => r.odo).filter((o): o is number => o !== null && o > 0);
  const odoStart = odos.length ? odos[0] : null;
  const odoEnd = odos.length ? odos[odos.length - 1] : null;
  // Kilometrajul are rezoluție de 1 km: îl preferăm doar pe drumuri mai lungi.
  const odoKm = odoStart !== null && odoEnd !== null ? odoEnd - odoStart : null;
  const distanceKm = odoKm !== null && odoKm >= 5 ? odoKm : gpsKm;
  const start = rows[0].device_at;
  const end = rows[rows.length - 1].device_at;
  const durationMin = (new Date(end).getTime() - new Date(start).getTime()) / 60_000;
  let stopMs = 0;
  const stops: TripStop[] = [];
  let lastPos: [number, number] | null = null;
  for (let i = 0; i < rows.length; i++) {
    if (i > 0) {
      const gap = new Date(rows[i].device_at).getTime() - new Date(rows[i - 1].device_at).getTime();
      if (gap > TRIP_GAP_MS) {
        stopMs += gap;
        stops.push({
          from: rows[i - 1].device_at,
          to: rows[i].device_at,
          minutes: Math.round(gap / 6_000) / 10,
          // Unde a parcat: ultima poziție bună dinainte, altfel prima de după.
          pos: lastPos ?? pos(rows[i]),
          place: null,
        });
      }
    }
    lastPos = pos(rows[i]) ?? lastPos;
  }
  const movingMin = durationMin - stopMs / 60_000;
  const tank = rows.map((r) => r.fuel).filter((f): f is number => f !== null && f > 0);
  const temps = rows.map((r) => r.temp).filter((t): t is number => t !== null);
  const fuel = estimateFuel(
    rows.map((r) => ({
      t: new Date(r.device_at).getTime(),
      speed: r.can_speed ?? r.gps_speed,
      rpm: r.rpm,
    })),
  );
  return {
    start,
    end,
    points: rows.length,
    distanceKm: Math.round(distanceKm * 10) / 10,
    durationMin: Math.round(durationMin * 10) / 10,
    parts,
    stops,
    stopMin: Math.round((stopMs / 60_000) * 10) / 10,
    maxSpeed: maxSpeed === null ? null : Math.round(maxSpeed),
    avgSpeed: movingMin > 0 ? Math.round((distanceKm / movingMin) * 60) : null,
    maxRpm,
    odoStart,
    odoEnd,
    fuelStart: tank.length ? tank[0] : null,
    fuelEnd: tank.length ? tank[tank.length - 1] : null,
    tempC: temps.length ? temps[temps.length - 1] : null,
    minVolt: minVolt === null ? null : Math.round(minVolt * 100) / 100,
    startPos,
    endPos,
    fromPlace: null,
    toPlace: null,
    modelLiters: fuel.liters,
    idleMin: Math.round(fuel.idleMin * 10) / 10,
    idle: parts === 1 && distanceKm < IDLE_KM,
    fuelL: null,
    lPer100: null,
    cost: null,
  };
}

/** Călătoriile din ultimele `days` zile, cele mai noi primele. */
export function readTrips(days = 60): Trip[] {
  return readTripsSince(new Date(Date.now() - days * 86_400_000).toISOString());
}

/** Călătoriile începute după `since` (ISO), cele mai noi primele. */
export function readTripsSince(since: string): Trip[] {
  const rows = getDb()
    .prepare(
      `SELECT device_at, lat, lon, acc, gps_speed, can_speed, rpm, volt, temp, odo, fuel
       FROM trip_point WHERE device_at >= ? ORDER BY device_at`,
    )
    .all(since) as unknown as Row[];
  // Întâi bucățile despărțite de pauze; trei-patru puncte izolate (ex. o repornire pe loc)
  // nu sunt o călătorie.
  const groups: Row[][] = [];
  let cur: Row[] = [];
  for (const r of rows) {
    const prev = cur[cur.length - 1];
    if (
      prev &&
      new Date(r.device_at).getTime() - new Date(prev.device_at).getTime() > TRIP_GAP_MS
    ) {
      groups.push(cur);
      cur = [];
    }
    cur.push(r);
  }
  if (cur.length) groups.push(cur);
  // Apoi cele combinate de utilizator (tabela trip_join): bucățile consecutive care încep
  // în același interval devin o singură călătorie.
  const joins = readJoins();
  const joinOf = (g: Row[]) =>
    joins.findIndex((j) => g[0].device_at >= j.start && g[0].device_at <= j.end);
  const trips: Trip[] = [];
  let merged: Row[] = [];
  let parts = 0;
  let mergedJoin = -1;
  for (const g of groups.filter((g) => g.length >= 5)) {
    const j = joinOf(g);
    if (parts > 0 && (j < 0 || j !== mergedJoin)) {
      trips.push(summarize(merged, parts));
      merged = [];
      parts = 0;
    }
    merged = merged.concat(g);
    parts++;
    mergedJoin = j;
  }
  if (parts > 0) trips.push(summarize(merged, parts));
  const places = readPlaces();
  return trips
    .map((t) => ({
      ...t,
      fromPlace: placeAt(t.startPos, places),
      toPlace: placeAt(t.endPos, places),
      stops: t.stops.map((s) => ({ ...s, place: placeAt(s.pos, places) })),
    }))
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
}

/** Desparte la loc călătoria combinată care începe la `start`. */
export function splitTrip(start: string): void {
  getDb().prepare(`DELETE FROM trip_join WHERE start <= ? AND end >= ?`).run(start, start);
}

/** Punctele unei călătorii (între `start` și `end`, inclusiv), pentru hartă și grafic. */
export function readTripPoints(start: string, end: string): TripPoint[] {
  const rows = getDb()
    .prepare(
      `SELECT device_at, lat, lon, acc, gps_speed, can_speed, rpm FROM trip_point
       WHERE device_at BETWEEN ? AND ? ORDER BY device_at`,
    )
    .all(start, end) as unknown as Row[];
  return rows.map((r) => {
    const p = pos(r);
    return {
      t: r.device_at,
      lat: p ? p[0] : null,
      lon: p ? p[1] : null,
      speed: r.can_speed ?? r.gps_speed,
      rpm: r.rpm,
    };
  });
}
