// ---------------------------------------------------------------------------
// Statistici (fila Costuri): ultimele 30 de zile și pe luni calendaristice (ora României).
// Un singur agregator pentru ambele, ca site-ul și Panel-ul să arate aceleași cifre.
// Totalurile includ și pornirile pe loc (consumă combustibil); doar numărul le exclude.
// Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { getDb, hourKey, memo } from "./db.ts";
import { applyFuel } from "./fuel.ts";
import { readTrips, type Trip } from "./trips.ts";

export interface PeriodStats {
  trips: number; // fără pornirile pe loc
  km: number;
  minutes: number; // la volan, fără opririle cu motorul oprit
  trafficMin: number; // oprit în trafic
  liters: number; // estimarea calibrată
  lPer100: number | null;
  cost: number | null;
  // Pentru raportul lunar: cea mai lungă călătorie și locurile unde a ajuns cel mai des.
  longest: { km: number; start: string; from: string | null; to: string | null } | null;
  topPlaces: Array<{ name: string; visits: number }>;
}

export interface MonthStats extends PeriodStats {
  month: string; // YYYY-MM
  refuelLiters: number;
  refuelLei: number | null; // banii dați efectiv la pompă
}

export interface Stats {
  last30: PeriodStats;
  months: MonthStats[]; // cele mai noi primele, cel mult 12
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

function sum(trips: Trip[]): PeriodStats {
  let km = 0;
  let minutes = 0;
  let trafficMin = 0;
  let liters = 0;
  let cost: number | null = null;
  let longest: Trip | null = null;
  const visits = new Map<string, number>();
  for (const t of trips) {
    if (!longest || t.distanceKm > longest.distanceKm) longest = t;
    if (t.toPlace && !t.idle) visits.set(t.toPlace, (visits.get(t.toPlace) ?? 0) + 1);
    km += t.distanceKm;
    minutes += t.durationMin - t.stopMin;
    trafficMin += t.trafficMin;
    liters += t.fuelL ?? 0;
    if (t.cost !== null) cost = (cost ?? 0) + t.cost;
  }
  return {
    trips: trips.filter((t) => !t.idle).length,
    km: round(km, 1),
    minutes: Math.round(minutes),
    trafficMin: Math.round(trafficMin),
    liters: round(liters, 2),
    lPer100: km >= 1 ? round((liters / km) * 100, 1) : null,
    cost: cost === null ? null : round(cost, 2),
    longest:
      longest && longest.distanceKm > 0
        ? { km: longest.distanceKm, start: longest.start, from: longest.fromPlace, to: longest.toPlace }
        : null,
    topPlaces: [...visits.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 3)
      .map(([name, n]) => ({ name, visits: n })),
  };
}

/** Recalculate doar la date noi (puncte, alimentări, combinări) sau la ora următoare. */
export const readStats = memo((): Stats => {
  const trips = applyFuel(readTrips(MONTHS * 31));
  const cutoff = new Date(Date.now() - 30 * 86_400_000).toISOString();
  const byMonth = new Map<string, Trip[]>();
  for (const t of trips) {
    const m = monthOf(t.start);
    byMonth.set(m, [...(byMonth.get(m) ?? []), t]);
  }
  const since = new Date(Date.now() - MONTHS * 31 * 86_400_000).toISOString();
  const refuels = getDb()
    .prepare(`SELECT at, liters, price FROM refuel WHERE at >= ?`)
    .all(since) as Array<{ at: string; liters: number; price: number | null }>;
  const pump = new Map<string, { liters: number; lei: number | null }>();
  for (const r of refuels) {
    const m = monthOf(r.at);
    const p = pump.get(m) ?? { liters: 0, lei: null };
    p.liters += r.liters;
    if (r.price !== null) p.lei = (p.lei ?? 0) + r.liters * r.price;
    pump.set(m, p);
  }
  const months = [...new Set([...byMonth.keys(), ...pump.keys()])]
    .sort((a, b) => b.localeCompare(a))
    .slice(0, MONTHS)
    .map((month) => {
      const p = pump.get(month);
      return {
        month,
        ...sum(byMonth.get(month) ?? []),
        refuelLiters: round(p?.liters ?? 0, 2),
        refuelLei: p?.lei == null ? null : round(p.lei, 2),
      };
    });
  return { last30: sum(trips.filter((t) => t.start >= cutoff)), months };
}, hourKey);
