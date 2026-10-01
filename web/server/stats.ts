// ---------------------------------------------------------------------------
// Statistici pe lună (fila Costuri): km, timp, litri estimați, cost și alimentări,
// pe luna calendaristică din ora României. Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { getDb } from "./db.ts";
import { applyFuel } from "./fuel.ts";
import { readTrips } from "./trips.ts";

export interface MonthStats {
  month: string; // YYYY-MM
  trips: number; // fără pornirile pe loc
  km: number;
  minutes: number;
  liters: number; // estimarea calibrată, inclusiv pornirile pe loc
  lPer100: number | null;
  cost: number | null;
  refuelLiters: number;
  refuelLei: number | null; // banii dați efectiv la pompă
}

const MONTHS = 12;
const monthFmt = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Europe/Bucharest",
  year: "numeric",
  month: "2-digit",
});

function monthOf(iso: string): string {
  return monthFmt.format(new Date(iso)).slice(0, 7);
}

function round(n: number, digits: number): number {
  const f = 10 ** digits;
  return Math.round(n * f) / f;
}

/** Ultimele 12 luni cu date, cele mai noi primele. */
export function readMonthlyStats(): MonthStats[] {
  const months = new Map<string, MonthStats>();
  const get = (m: string) => {
    let s = months.get(m);
    if (!s) {
      s = { month: m, trips: 0, km: 0, minutes: 0, liters: 0, lPer100: null, cost: null, refuelLiters: 0, refuelLei: null };
      months.set(m, s);
    }
    return s;
  };
  for (const t of applyFuel(readTrips(MONTHS * 31))) {
    const s = get(monthOf(t.start));
    if (!t.idle) s.trips++;
    s.km += t.distanceKm;
    s.minutes += t.durationMin;
    s.liters += t.fuelL ?? 0;
    if (t.cost !== null) s.cost = (s.cost ?? 0) + t.cost;
  }
  const since = new Date(Date.now() - MONTHS * 31 * 86_400_000).toISOString();
  const refuels = getDb()
    .prepare(`SELECT at, liters, price FROM refuel WHERE at >= ?`)
    .all(since) as Array<{ at: string; liters: number; price: number | null }>;
  for (const r of refuels) {
    const s = get(monthOf(r.at));
    s.refuelLiters += r.liters;
    if (r.price !== null) s.refuelLei = (s.refuelLei ?? 0) + r.liters * r.price;
  }
  return [...months.values()]
    .sort((a, b) => b.month.localeCompare(a.month))
    .slice(0, MONTHS)
    .map((s) => ({
      ...s,
      km: round(s.km, 1),
      minutes: Math.round(s.minutes),
      liters: round(s.liters, 2),
      lPer100: s.km >= 1 ? round((s.liters / s.km) * 100, 1) : null,
      cost: s.cost === null ? null : round(s.cost, 2),
      refuelLiters: round(s.refuelLiters, 2),
      refuelLei: s.refuelLei === null ? null : round(s.refuelLei, 2),
    }));
}
