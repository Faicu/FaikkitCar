// ---------------------------------------------------------------------------
// Alimentările Golf-ului (tabela refuel) și combustibilul pe călătorie:
// estimarea brută din fuel-model.ts × factorul intervalului dintre alimentări în care
// cade drumul (bonuri + nivelul rezervorului, CAN c104); costul cu prețul mediu al
// benzinei din rezervor la acel moment. Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { HTTPException } from "hono/http-exception";

import { dataChanged, getDb, memo } from "./db.ts";
import {
  calibrate,
  currentLevel,
  factorAt,
  levelRefills,
  lPer100At,
  matchRefuels,
  priceAt,
  REFUEL_MATCH_MS,
  tankPrices,
  type FuelInterval,
  type FuelRefuel,
  type LevelConsumption,
  type LevelReading,
} from "./fuel-model.ts";
import { readGaps, readTripsSince, type Gap, type Trip } from "./trips.ts";

export interface Refuel extends FuelRefuel {
  note: string | null;
  lPer100: number | null; // consumul real al intervalului încheiat de această alimentare
  since: string | null; // de când (începutul intervalului)
  // Se vede în nivelul rezervorului la ora ei? false = nu (data sau ora greșite; `gaugeAt` =
  // saltul care pare al ei), null = încă nu se poate ști (mașina n-a mai mers de atunci).
  seen: boolean | null;
  gaugeAt: string | null;
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
  avgLPer100: number | null; // consumul real recent (ultimele intervale dintre alimentări)
  lastPrice: number | null;
  tankPrice: number | null; // prețul mediu al benzinei din rezervor acum (cu el se socotesc drumurile)
  source: "level" | "refuels" | null;
  level: LevelConsumption | null;
  tank: { liters: number; at: string } | null; // ultimul nivel citit
}

/** Nivelul se citește pe tot istoricul călătoriilor (trips.ts), ca intervalele vechi să rămână. */
const LEVEL_DAYS = 400;

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
 * Alimentările, nivelurile, calibrarea, prețurile din rezervor și potrivirea alimentărilor cu
 * salturile nivelului, calculate o dată per versiune a datelor.
 */
export const fuelState = memo(() => {
  const refuels = readRefuels();
  const levels = readLevels();
  const refills = levelRefills(levels);
  return {
    refuels,
    levels,
    refills,
    match: matchRefuels(refills, refuels),
    cal: calibrate(refuels, readTripsSince(""), levels),
    prices: tankPrices(refuels, levels),
  };
});

/**
 * Completează litrii, consumul și costul călătoriilor: fiecare cu factorul intervalului dintre
 * alimentări în care a început și cu prețul benzinei din rezervor de atunci.
 */
export function applyFuel(trips: Trip[]): Trip[] {
  const { prices, cal } = fuelState();
  return trips.map((t) => {
    const factor = factorAt(cal, t.start);
    const fuelL = t.modelLiters * factor;
    const standL = t.standModelLiters * factor;
    const price = priceAt(prices, t.start);
    return {
      ...t,
      fuelL: Math.round(fuelL * 100) / 100,
      lPer100: t.distanceKm >= 1 ? Math.round((fuelL / t.distanceKm) * 1000) / 10 : null,
      cost: price !== null ? Math.round(fuelL * price * 100) / 100 : null,
      standFuelL: Math.round(standL * 100) / 100,
      standCost: price !== null ? Math.round(standL * price * 100) / 100 : null,
    };
  });
}

/**
 * Drumurile neînregistrate, cu litrii din consumul real al perioadei lor (intervalul dintre
 * alimentări) și prețul de atunci. Fără consum real încă, rămân fără litri.
 */
export function readGapsWithFuel(days = 60): Gap[] {
  const { prices, cal } = fuelState();
  return readGaps(days).map((g) => {
    const lPer100 = lPer100At(cal, g.after);
    if (lPer100 === null) return g;
    const fuelL = (g.km * lPer100) / 100;
    const price = priceAt(prices, g.after);
    return {
      ...g,
      fuelL: Math.round(fuelL * 100) / 100,
      cost: price !== null ? Math.round(fuelL * price * 100) / 100 : null,
    };
  });
}

export function readFuelSummary(): FuelSummary {
  const { refuels, levels, refills, match, cal, prices } = fuelState();
  const last = levels[levels.length - 1];
  const byTo = new Map<number, FuelInterval>();
  for (const i of cal.intervals) if (i.toId !== null) byTo.set(i.toId, i);
  const priced = refuels.filter((r) => r.price !== null);
  return {
    refuels: refuels
      .map((r, k) => {
        const jump = match.byRefuel[k] >= 0 ? refills[match.byRefuel[k]] : null;
        const late = match.late.has(k);
        // Fără salt: lipsește abia dacă mașina a mai mers după (altfel încă nu se poate ști).
        const missing =
          !jump &&
          levels.length > 0 &&
          levels[0].t <= r.at &&
          Date.parse(last.t) > Date.parse(r.at) + REFUEL_MATCH_MS;
        return {
          ...r,
          lPer100: byTo.get(r.id)?.lPer100 ?? null,
          since: byTo.get(r.id)?.from ?? null,
          seen: jump && !late ? true : late || missing ? false : null,
          gaugeAt: late && jump ? jump.at : null,
        };
      })
      .reverse(),
    factor: cal.factor,
    calibrated: cal.calibrated,
    avgLPer100: cal.lPer100,
    lastPrice: priced.length ? priced[priced.length - 1].price : null,
    tankPrice: prices.length ? Math.round(prices[prices.length - 1].price * 1000) / 1000 : null,
    source: cal.source,
    level: cal.level,
    tank: last ? { liters: currentLevel(levels)!, at: last.t } : null,
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
  if (Number.isNaN(at.getTime())) throw new HTTPException(400, { message: "Data lipsește" });
  const liters = numOrNull(input.liters);
  if (liters === null || liters <= 0 || liters > 100)
    throw new HTTPException(400, { message: "Litrii trebuie să fie între 0 și 100" });
  const price = numOrNull(input.price);
  if (price !== null && (price <= 0 || price > 50)) throw new HTTPException(400, { message: "Prețul pe litru pare greșit" });
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
