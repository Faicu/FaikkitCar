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
  // Staționarea cu motorul pornit (încălzire, așteptat): timpul, litrii și banii arși pe loc.
  standMin: number;
  standLiters: number;
  standCost: number | null;
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
  serviceLei: number | null; // lucrările din jurnalul de service
}

/** Un drum pe care îl faci des (ex. Acasă → Serviciu): cât te costă și când e mai bine să pleci. */
export interface RouteStats {
  from: string;
  to: string;
  trips: number;
  km: number; // medii pe drum
  minutes: number;
  trafficMin: number;
  cost: number | null;
  lPer100: number | null;
  monthCost: number | null; // totalul pe drumul ăsta în luna curentă
  // Durata medie după ora plecării; cea mai bună / cea mai proastă, din orele cu ≥ 2 drumuri.
  byHour: Array<{ hour: number; trips: number; minutes: number; trafficMin: number }>;
  bestHour: number | null;
  worstHour: number | null;
}

export interface Stats {
  last30: PeriodStats;
  months: MonthStats[]; // cele mai noi primele, cel mult 12
  routes: RouteStats[]; // pe ultimele 90 de zile, cele mai dese primele
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
  let standMin = 0;
  let standLiters = 0;
  let standCost: number | null = null;
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
    standMin += t.standMin;
    standLiters += t.standFuelL ?? 0;
    if (t.standCost !== null) standCost = (standCost ?? 0) + t.standCost;
    liters += t.fuelL ?? 0;
    if (t.cost !== null) cost = (cost ?? 0) + t.cost;
  }
  return {
    trips: trips.filter((t) => !t.idle).length,
    km: round(km, 1),
    minutes: Math.round(minutes),
    trafficMin: Math.round(trafficMin),
    standMin: Math.round(standMin),
    standLiters: round(standLiters, 2),
    standCost: standCost === null ? null : round(standCost, 2),
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

const ROUTE_DAYS = 90;
const hourFmt = new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Bucharest", hour: "2-digit", hour12: false });

/** Drumurile între două locuri salvate diferite, făcute de cel puțin 2 ori în ultimele 90 de zile. */
function routes(trips: Trip[]): RouteStats[] {
  const cutoff = new Date(Date.now() - ROUTE_DAYS * 86_400_000).toISOString();
  const thisMonth = monthOf(new Date().toISOString());
  const byRoute = new Map<string, Trip[]>();
  for (const t of trips) {
    if (t.idle || t.start < cutoff || !t.fromPlace || !t.toPlace || t.fromPlace === t.toPlace) continue;
    const k = `${t.fromPlace}→${t.toPlace}`;
    byRoute.set(k, [...(byRoute.get(k) ?? []), t]);
  }
  const avg = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length;
  return [...byRoute.values()]
    .filter((ts) => ts.length >= 2)
    .sort((a, b) => b.length - a.length)
    .map((ts) => {
      const priced = ts.filter((t) => t.cost !== null);
      const consum = ts.filter((t) => t.lPer100 !== null);
      const hours = new Map<number, Trip[]>();
      for (const t of ts) {
        const h = Number(hourFmt.format(new Date(t.start)));
        hours.set(h, [...(hours.get(h) ?? []), t]);
      }
      const byHour = [...hours.entries()]
        .sort((a, b) => a[0] - b[0])
        .map(([hour, hs]) => ({
          hour,
          trips: hs.length,
          minutes: round(avg(hs.map((t) => t.durationMin - t.stopMin)), 1),
          trafficMin: round(avg(hs.map((t) => t.trafficMin)), 1),
        }));
      const solid = byHour.filter((h) => h.trips >= 2).sort((a, b) => a.minutes - b.minutes);
      const month = ts.filter((t) => monthOf(t.start) === thisMonth && t.cost !== null);
      return {
        from: ts[0].fromPlace!,
        to: ts[0].toPlace!,
        trips: ts.length,
        km: round(avg(ts.map((t) => t.distanceKm)), 1),
        minutes: round(avg(ts.map((t) => t.durationMin - t.stopMin)), 1),
        trafficMin: round(avg(ts.map((t) => t.trafficMin)), 1),
        cost: priced.length ? round(avg(priced.map((t) => t.cost!)), 2) : null,
        lPer100: consum.length ? round(avg(consum.map((t) => t.lPer100!)), 1) : null,
        monthCost: month.length ? round(month.reduce((a, t) => a + t.cost!, 0), 2) : null,
        byHour,
        bestHour: solid.length >= 2 ? solid[0].hour : null,
        worstHour: solid.length >= 2 ? solid[solid.length - 1].hour : null,
      };
    });
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
  const service = getDb()
    .prepare(`SELECT date, cost FROM service WHERE date >= ? AND cost IS NOT NULL`)
    .all(since.slice(0, 10)) as Array<{ date: string; cost: number }>;
  const serviceByMonth = new Map<string, number>();
  for (const sv of service) serviceByMonth.set(sv.date.slice(0, 7), (serviceByMonth.get(sv.date.slice(0, 7)) ?? 0) + sv.cost);
  const months = [...new Set([...byMonth.keys(), ...pump.keys(), ...serviceByMonth.keys()])]
    .sort((a, b) => b.localeCompare(a))
    .slice(0, MONTHS)
    .map((month) => {
      const p = pump.get(month);
      return {
        month,
        ...sum(byMonth.get(month) ?? []),
        refuelLiters: round(p?.liters ?? 0, 2),
        refuelLei: p?.lei == null ? null : round(p.lei, 2),
        serviceLei: serviceByMonth.has(month) ? round(serviceByMonth.get(month)!, 2) : null,
      };
    });
  return { last30: sum(trips.filter((t) => t.start >= cutoff)), months, routes: routes(trips) };
}, hourKey);
