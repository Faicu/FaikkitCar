// ---------------------------------------------------------------------------
// Alimentările Golf-ului (tabela refuel) și combustibilul pe călătorie:
// estimarea brută din fuel-model.ts × factorul din nivelul rezervorului (CAN c104)
// sau, fără destule citiri, din intervalele plin → plin; costul cu prețul ultimei
// alimentări. Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { dataChanged, getDb, memo } from "./db.ts";
import {
  calibrate,
  priceAt,
  type FuelInterval,
  type FuelRefuel,
  type LevelConsumption,
  type LevelReading,
} from "./fuel-model.ts";
import { readTripsSince, type Trip } from "./trips.ts";

export interface Refuel extends FuelRefuel {
  note: string | null;
  lPer100: number | null; // consumul real până la acest plin, de la plinul anterior
}

export interface RefuelInput {
  id?: number;
  at: string; // ISO
  odo: number | null; // null = din călătorii, la ora alimentării
  liters: number;
  price: number | null;
  full: boolean;
  note: string | null;
}

export interface FuelSummary {
  refuels: Refuel[]; // cele mai noi primele
  factor: number;
  calibrated: boolean;
  avgLPer100: number | null; // din nivelul rezervorului sau din plinuri
  lastPrice: number | null;
  source: "level" | "refuels" | null;
  level: LevelConsumption | null;
  tank: { liters: number; at: string } | null; // ultimul nivel citit
}

/** Nivelul se folosește pe ultimele atâtea zile (consumul se schimbă cu anotimpul). */
const LEVEL_DAYS = 90;

function readRefuels(): Array<FuelRefuel & { note: string | null }> {
  const rows = getDb()
    .prepare(`SELECT id, at, odo, liters, price, full, note FROM refuel ORDER BY at, id`)
    .all() as Array<{
    id: number;
    at: string;
    odo: number | null;
    liters: number;
    price: number | null;
    full: number;
    note: string | null;
  }>;
  return rows.map((r) => ({ ...r, full: r.full === 1 }));
}

function readLevels(): LevelReading[] {
  const since = new Date(Date.now() - LEVEL_DAYS * 86_400_000).toISOString();
  return (
    getDb()
      .prepare(
        `SELECT device_at, fuel, odo FROM trip_point
         WHERE fuel > 0 AND device_at >= ? ORDER BY device_at`,
      )
      .all(since) as Array<{ device_at: string; fuel: number; odo: number | null }>
  ).map((r) => ({ t: r.device_at, fuel: r.fuel, odo: r.odo }));
}

/**
 * Alimentările, nivelurile și calibrarea, calculate o dată per versiune a datelor. Intră
 * călătoriile de la primul plin sau de la prima citire de nivel.
 */
export const fuelState = memo(() => {
  const refuels = readRefuels();
  const levels = readLevels();
  const starts = [refuels.find((r) => r.full)?.at, levels[0]?.t].filter(
    (x): x is string => x !== undefined,
  );
  const trips = starts.length ? readTripsSince(starts.sort()[0]) : [];
  return { refuels, levels, cal: calibrate(refuels, trips, levels) };
});

/** Completează litrii, consumul și costul călătoriilor (aceeași calibrare pentru toate). */
export function applyFuel(trips: Trip[]): Trip[] {
  const { refuels, cal } = fuelState();
  return trips.map((t) => {
    const fuelL = t.modelLiters * cal.factor;
    const price = priceAt(refuels, t.start);
    return {
      ...t,
      fuelL: Math.round(fuelL * 100) / 100,
      lPer100: t.distanceKm >= 1 ? Math.round((fuelL / t.distanceKm) * 1000) / 10 : null,
      cost: price !== null ? Math.round(fuelL * price * 100) / 100 : null,
    };
  });
}

function currentLevel(levels: LevelReading[]): number {
  const end = Date.parse(levels[levels.length - 1].t);
  const xs = levels.filter((l) => end - Date.parse(l.t) <= 10 * 60_000).map((l) => l.fuel);
  const s = xs.sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

export function readFuelSummary(): FuelSummary {
  const { refuels, levels, cal } = fuelState();
  const last = levels[levels.length - 1];
  const byTo = new Map<number, FuelInterval>(cal.intervals.map((i) => [i.toId, i]));
  const withKm = cal.intervals.filter((i) => i.km !== null);
  const km = withKm.reduce((s, i) => s + (i.km ?? 0), 0);
  const liters = withKm.reduce((s, i) => s + i.liters, 0);
  const priced = refuels.filter((r) => r.price !== null);
  return {
    refuels: refuels
      .map((r) => ({
        ...r,
        lPer100: byTo.get(r.id)?.lPer100 ?? null,
      }))
      .reverse(),
    factor: cal.factor,
    calibrated: cal.calibrated,
    avgLPer100: cal.level?.lPer100 ?? (km > 0 ? (liters / km) * 100 : null),
    lastPrice: priced.length ? priced[priced.length - 1].price : null,
    source: cal.source,
    level: cal.level,
    // Mediana ultimelor 10 minute: citirile oscilează cu 1 L (37/38).
    tank: last ? { liters: currentLevel(levels), at: last.t } : null,
  };
}

/** Kilometrajul raportat de mașină cel mai aproape înainte de `at` (sau imediat după). */
function odometerAt(at: string): number | null {
  const db = getDb();
  const before = db
    .prepare(
      `SELECT odo FROM trip_point WHERE odo > 0 AND device_at <= ? ORDER BY device_at DESC LIMIT 1`,
    )
    .get(at) as { odo: number } | undefined;
  if (before) return before.odo;
  const after = db
    .prepare(
      `SELECT odo FROM trip_point WHERE odo > 0 AND device_at > ? ORDER BY device_at LIMIT 1`,
    )
    .get(at) as { odo: number } | undefined;
  return after?.odo ?? null;
}

function numOrNull(x: unknown): number | null {
  const n = typeof x === "number" ? x : typeof x === "string" && x.trim() ? Number(x) : NaN;
  return Number.isFinite(n) ? n : null;
}

export function saveRefuel(input: RefuelInput): void {
  const at = new Date(String(input.at ?? ""));
  if (Number.isNaN(at.getTime())) throw new Error("Data lipsește");
  const liters = numOrNull(input.liters);
  if (liters === null || liters <= 0 || liters > 100)
    throw new Error("Litrii trebuie să fie între 0 și 100");
  const price = numOrNull(input.price);
  if (price !== null && (price <= 0 || price > 50)) throw new Error("Prețul pe litru pare greșit");
  const iso = at.toISOString();
  const odoIn = numOrNull(input.odo);
  const odo = odoIn !== null ? Math.round(odoIn) : odometerAt(iso);
  const note =
    String(input.note ?? "")
      .trim()
      .slice(0, 120) || null;
  const values = [iso, odo, liters, price, input.full ? 1 : 0, note];
  const db = getDb();
  if (input.id) {
    db.prepare(
      `UPDATE refuel SET at = ?, odo = ?, liters = ?, price = ?, full = ?, note = ? WHERE id = ?`,
    ).run(...values, input.id);
  } else {
    db.prepare(
      `INSERT INTO refuel (at, odo, liters, price, full, note, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).run(...values, new Date().toISOString());
  }
  dataChanged();
}

export function deleteRefuel(id: number): void {
  getDb().prepare(`DELETE FROM refuel WHERE id = ?`).run(id);
  dataChanged();
}
