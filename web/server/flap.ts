// ---------------------------------------------------------------------------
// Clapeta de distribuție față a climatronicului (motorașul V426, eroarea B1091; 23.11): poziția reală
// (DID 262E, al doilea octet) și cea cerută (DID 262F), citite de urmărirea „clapeta” la 30 s (din 24.11; înainte 1 min).
// Când merge, reala e cu ~0x1D peste cea cerută (0x19–0x1F în toate pozițiile); blocată, rămâne pe drum
// (89 față de BE, 1A față de 94 pe 23.11). Alerta: aceeași cerere sau aceeași poziție reală, nepotrivită la două
// citiri la cel puțin 30 s una de alta (clapeta se mișcă în câteva secunde, deci nu e o mișcare prinsă la jumătate).
// Ca becul ars: rândul rămâne cu mașina oprită, până la o citire bună. Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { getDb } from "./db.ts";

export const FLAP_ACTUAL = 0x262e;
export const FLAP_DEMAND = 0x262f;
/** Diferența reală − cerută când clapeta a ajuns (văzută 0x19–0x1F), cu marjă. */
const OK_MIN = 0x10;
const OK_MAX = 0x28;
const CONFIRM_MS = 30_000;

/** Poziția din valoarea citită (fără ecou): 262E are 2 octeți (al doilea contează), 262F unul. */
export function flapByte(value: string): number | null {
  if (!/^([0-9A-Fa-f]{2})+$/.test(value)) return null;
  return parseInt(value.slice(-2), 16);
}

export function flapOk(actual: number, demand: number): boolean {
  const d = actual - demand;
  return d >= OK_MIN && d <= OK_MAX;
}

export interface FlapRow {
  at: string;
  actual: number;
  demand: number;
  badFrom: string | null; // prima citire nepotrivită cu cererea asta
  since: string | null; // blocată, confirmat (null = merge)
}

/** Starea după o citire nouă (pur). */
export function nextFlap(prev: FlapRow | null, at: string, actual: number, demand: number): FlapRow {
  if (flapOk(actual, demand)) return { at, actual, demand, badFrom: null, since: null };
  const sameDemand = prev !== null && prev.badFrom !== null && Math.abs(prev.demand - demand) <= 2;
  // Sau reala n-a plecat deloc, deși cererea s-a schimbat (24.11, la încălzire: AUTO schimbă cererea
  // la fiecare minut, iar clapeta stătea pe 21 de la cererea 06 la 20).
  const sameActual = prev !== null && prev.badFrom !== null && Math.abs(prev.actual - actual) <= 2;
  const badFrom = sameDemand || sameActual ? prev!.badFrom! : at;
  const confirmed = Date.parse(at) - Date.parse(badFrom) >= CONFIRM_MS;
  const since = prev?.since ?? (confirmed ? badFrom : null);
  return { at, actual, demand, badFrom, since };
}

export function noteFlap(atIso: string, actual: number, demand: number): void {
  const db = getDb();
  const r = db.prepare(`SELECT at, actual, demand, bad_from, since FROM obd_flap WHERE id = 1`).get() as
    | { at: string; actual: number; demand: number; bad_from: string | null; since: string | null }
    | undefined;
  if (r && r.at >= atIso) return; // citire veche, retrimisă din coadă
  const prev = r ? { at: r.at, actual: r.actual, demand: r.demand, badFrom: r.bad_from, since: r.since } : null;
  const n = nextFlap(prev, atIso, actual, demand);
  db.prepare(
    `INSERT INTO obd_flap (id, at, actual, demand, bad_from, since) VALUES (1, ?, ?, ?, ?, ?)
     ON CONFLICT (id) DO UPDATE SET at = excluded.at, actual = excluded.actual, demand = excluded.demand,
       bad_from = excluded.bad_from, since = excluded.since`,
  ).run(n.at, n.actual, n.demand, n.badFrom, n.since);
}

/** Din rezultatele unei trimiteri: perechile 262E / 262F ale climatronicului citite la ≤ 5 s una de alta. */
export function noteFlapItems(items: Array<{ x?: unknown; s?: unknown; i?: unknown; v?: unknown; at?: unknown }>): void {
  const reads = items.filter(
    (r) => r && r.x === "746" && r.s === "22" && typeof r.v === "string" && typeof r.at === "number"
      && (r.i === FLAP_ACTUAL || r.i === FLAP_DEMAND),
  ) as Array<{ i: number; v: string; at: number }>;
  for (const a of reads) {
    if (a.i !== FLAP_ACTUAL) continue;
    const d = reads.find((r) => r.i === FLAP_DEMAND && Math.abs(r.at - a.at) <= 5_000);
    const actual = flapByte(a.v);
    const demand = d ? flapByte(d.v) : null;
    if (actual === null || demand === null) continue;
    noteFlap(new Date(Math.max(a.at, d!.at)).toISOString(), actual, demand);
  }
}

const hex = (n: number) => n.toString(16).toUpperCase().padStart(2, "0");

/** Defectul pentru cardul de erori (site, Panel) cât clapeta e blocată. */
export function flapFault(): { module: string; code: string; text: string; known: boolean; state: "active"; since: string; detail: string } | null {
  const f = readFlap();
  if (!f?.since) return null;
  return {
    module: "Climatronic",
    code: "clapeta",
    text: "Clapeta de distribuție față blocată: motorașul V426",
    known: true,
    state: "active",
    since: f.since,
    detail: `Poziția cerută ${hex(f.demand)}, cea reală ${hex(f.actual)} (când merge, reala e cu ~1D peste). `
      + `Gurile de aer din mijloc nu primesc aer cum ai cerut. O mișcare ușoară a rotiței albe din spatele navigației `
      + `o deblochează de obicei; dispare de aici la prima citire bună (urmărirea o verifică la 30 s).`,
  };
}

/** Pentru mașină (răspunsul la /api/car/state): ce se spune vocal, o dată pe drum, cu id-ul defectului. */
export function carAlerts(): Array<{ id: string; say: string }> {
  const f = readFlap();
  if (!f?.since) return [];
  return [{
    id: `clapeta:${f.since}`,
    say: "Atenție! Clapeta de aer din față a climatronicului s-a blocat. Gurile din mijloc nu primesc aer. Motorașul trebuie schimbat.",
  }];
}

export function readFlap(): FlapRow | null {
  const r = getDb().prepare(`SELECT at, actual, demand, bad_from, since FROM obd_flap WHERE id = 1`).get() as
    | { at: string; actual: number; demand: number; bad_from: string | null; since: string | null }
    | undefined;
  return r ? { at: r.at, actual: r.actual, demand: r.demand, badFrom: r.bad_from, since: r.since } : null;
}
