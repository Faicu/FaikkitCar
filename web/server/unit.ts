// ---------------------------------------------------------------------------
// Performanța navigației (Teyes): aplicația din mașină o trimite cu starea de acum (cheia „u”
// din POST /api/car/state, de la 1.1.62): procesorul, memoria, swap-ul, temperatura, de când
// rulează, memoria și adj-ul aplicației, spațiul liber și procesele care consumă cel mai mult.
// Ultima valoare stă în memorie (cu procesele); istoricul, la cel mult 30 s, în `unit_stat`.
// Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { getDb } from "./db.ts";

export interface UnitProc {
  name: string;
  label: string; // pe înțeles („YouTube Music”, „Radio (sqlfmserver)”), altfel numele
  cpu: number | null; // % din tot procesorul, pe ultimele ~30 s (null la prima măsurare)
  mb: number; // memoria proprie (fără cea partajată), MB
}

export interface UnitStats {
  at: string;
  cpu: number | null; // % din tot procesorul, de la trimiterea anterioară
  load: number | null; // media de încărcare pe 1 min
  cores: number | null;
  memTotal: number | null; // MB
  memAvail: number | null;
  swapTotal: number | null;
  swapFree: number | null;
  temp: number | null; // °C, cea mai caldă zonă a procesorului
  uptime: number | null; // s de la pornirea navigației (cu somnul)
  awake: number | null; // s de la pornire, fără somn
  appMb: number | null; // memoria proprie a FaikkitCar
  appAdj: number | null; // oom_score_adj al FaikkitCar (mai mare = omorât mai devreme)
  diskGb: number | null; // liber în /data
  procs: UnitProc[] | null; // null = nemăsurate în trimiterea asta
}

// Starea pe scurt, pentru primul card de pe Acum (site și Panel).
export interface UnitHealth {
  level: "ok" | "warn" | "bad";
  text: string; // „merge bine · 1,1 GB liberi · procesor 24% · 48 °C” sau problemele
}

export type UnitPoint = Pick<UnitStats, "at" | "cpu" | "memAvail" | "swapFree" | "temp">;

const HISTORY_EVERY_MS = 30_000;
const KEEP_DAYS = 90;

/** Nume pe înțeles pentru procesele care apar des în topul navigației. */
const NAMES: Array<[RegExp, string]> = [
  [/^com\.mapgoo\.diruite/, "FaikkitCar"],
  [/^com\.waze/, "Waze"],
  [/^com\.google\.android\.apps\.youtube\.music/, "YouTube Music"],
  [/^com\.google\.android\.gms/, "Servicii Google"],
  [/^com\.android\.vending/, "Magazin Play"],
  [/^com\.syu\.ms/, "Teyes (CAN, sistem)"],
  [/^com\.syu\.canbus/, "Teyes CAN"],
  [/^com\.syu\.rearcamera/, "Camera spate"],
  [/^com\.syu\.music/, "Muzica Teyes"],
  [/^sqlfmserver$/, "Radio (sqlfmserver)"],
  [/^com\.lsec\.gesture/, "Gesturi Teyes"],
  [/^com\.android\.launcher/, "Ecranul principal"],
  [/^com\.android\.systemui/, "Bara de sistem"],
  [/^com\.android\.bluetooth/, "Bluetooth"],
  [/^system_server$/, "Android (system_server)"],
  [/^surfaceflinger$/, "Afișajul (surfaceflinger)"],
  [/^android\.hardware\.graphics\.composer/, "Afișajul (composer)"],
  [/^com\.yf\.teyesspotify/, "Spotify Teyes"],
  [/^com\.txznet/, "TXZ (asistent vocal)"],
];

export function procLabel(name: string): string {
  for (const [re, label] of NAMES) if (re.test(name)) return label;
  return name;
}

function num(x: unknown): number | null {
  return typeof x === "number" && Number.isFinite(x) ? x : null;
}

let latest: UnitStats | null = null;
let lastProcs: UnitProc[] | null = null;
let lastSavedAt = 0;
let lastPrunedAt = 0;

/** Din cheia „u” a stării trimise de mașină. */
export function saveUnit(u: unknown): void {
  if (!u || typeof u !== "object") return;
  const x = u as Record<string, unknown>;
  const now = new Date();
  const procs = Array.isArray(x.top)
    ? (x.top as unknown[])
        .filter((p): p is Record<string, unknown> => !!p && typeof p === "object")
        .map((p) => {
          const name = String(p.n ?? "?").slice(0, 80);
          return { name, label: procLabel(name), cpu: num(p.c), mb: num(p.m) ?? 0 };
        })
        .slice(0, 12)
    : null;
  if (procs) lastProcs = procs;
  latest = {
    at: now.toISOString(),
    cpu: num(x.cpu),
    load: num(x.load),
    cores: num(x.ncpu),
    memTotal: num(x.mt),
    memAvail: num(x.ma),
    swapTotal: num(x.st),
    swapFree: num(x.sf),
    temp: num(x.temp),
    uptime: num(x.up),
    awake: num(x.awake),
    appMb: num(x.am),
    appAdj: num(x.adj),
    diskGb: num(x.disk),
    procs: lastProcs,
  };
  if (now.getTime() - lastSavedAt < HISTORY_EVERY_MS) return;
  lastSavedAt = now.getTime();
  const { procs: _p, ...rest } = latest;
  const db = getDb();
  db.prepare(`INSERT OR REPLACE INTO unit_stat (at, data) VALUES (?, ?)`).run(rest.at, JSON.stringify(rest));
  if (now.getTime() - lastPrunedAt > 3_600_000) {
    lastPrunedAt = now.getTime();
    db.prepare(`DELETE FROM unit_stat WHERE at < ?`).run(new Date(now.getTime() - KEEP_DAYS * 86_400_000).toISOString());
  }
}

/** Fără măsurare nouă atâta timp, navigația doarme (sau aplicația nu rulează). */
export const UNIT_STALE_MS = 90_000;
// Primele minute după pornire procesorul e ocupat cu pornirea aplicațiilor: nu e o problemă.
const BOOT_S = 180;

function fmt(n: number, digits = 0): string {
  return n.toLocaleString("ro-RO", { maximumFractionDigits: digits });
}

/**
 * Starea pe scurt a navigației. Pragurile vin din ce s-a văzut pe Teyes (20–22.10): sub ~400 MB
 * liberi aplicațiile au început să fie omorâte, după repornire stă la 1–1,5 GB; un proces care
 * ține ocupat un nucleu întreg (Radio, `sqlfmserver`, rămas pornit după închidere) e de semnalat.
 * `cpuAvg` = media procesorului pe ultimele minute (un vârf scurt nu contează). Null = doarme.
 */
export function unitHealth(u: UnitStats | null, cpuAvg: number | null, now = Date.now()): UnitHealth | null {
  if (!u || now - new Date(u.at).getTime() >= UNIT_STALE_MS) return null;
  const bad: string[] = [];
  const warn: string[] = [];
  const booting = u.uptime !== null && u.uptime < BOOT_S;
  if (u.memAvail !== null) {
    if (u.memAvail < 300) bad.push(`memorie foarte puțină (${u.memAvail}\u00a0MB liberi)`);
    else if (u.memAvail < 600) warn.push(`memorie puțină (${u.memAvail}\u00a0MB liberi)`);
  }
  if (u.swapTotal && u.swapFree !== null && u.swapFree / u.swapTotal < 0.1) warn.push("swap plin");
  if (u.temp !== null) {
    if (u.temp >= 80) bad.push(`fierbinte (${fmt(u.temp)}\u00a0°C)`);
    else if (u.temp >= 70) warn.push(`caldă (${fmt(u.temp)}\u00a0°C)`);
  }
  if (!booting && cpuAvg !== null && cpuAvg >= 75) warn.push(`procesor ocupat (${fmt(cpuAvg)}%)`);
  if (!booting && u.procs) {
    // Un nucleu întreg = 100% / nuclee din tot procesorul (12,5% la 8 nuclee).
    const core = 100 / (u.cores ?? 8);
    for (const p of u.procs) {
      if ((p.cpu ?? 0) >= core * 0.9 && p.label !== "FaikkitCar") warn.push(`${p.label} ține ocupat un nucleu`);
    }
  }
  if (u.diskGb !== null && u.diskGb < 1) warn.push(`spațiu puțin (${fmt(u.diskGb, 1)}\u00a0GB)`);
  if (bad.length + warn.length > 0) {
    return { level: bad.length > 0 ? "bad" : "warn", text: [...bad, ...warn].join(" · ") };
  }
  const parts = ["merge bine"];
  if (u.memAvail !== null) parts.push(`${fmt(u.memAvail / 1024, 1)}\u00a0GB liberi`);
  if (u.cpu !== null) parts.push(`procesor ${fmt(cpuAvg ?? u.cpu)}%`);
  if (u.temp !== null) parts.push(`${fmt(u.temp)}\u00a0°C`);
  if (booting) parts[0] = "tocmai a pornit";
  return { level: "ok", text: parts.join(" · ") };
}

/** Starea pe scurt de acum, cu media procesorului pe ultimele 3 minute. */
export function readUnitHealth(): UnitHealth | null {
  const u = readUnit();
  if (!u) return null;
  const cpus = readUnitHistory(3)
    .map((p) => p.cpu)
    .concat(u.cpu)
    .filter((c): c is number => c !== null);
  const avg = cpus.length > 0 ? cpus.reduce((a, b) => a + b, 0) / cpus.length : null;
  return unitHealth(u, avg);
}

/** Ultima măsurare: cea din memorie, altfel (după o repornire a serverului) ultima salvată. */
export function readUnit(): UnitStats | null {
  if (latest) return latest;
  const row = getDb().prepare(`SELECT data FROM unit_stat ORDER BY at DESC LIMIT 1`).get() as
    | { data: string }
    | undefined;
  return row ? { ...(JSON.parse(row.data) as UnitStats), procs: null } : null;
}

/** Istoricul pe ultimele `minutes` minute (graficele). */
export function readUnitHistory(minutes: number): UnitPoint[] {
  const from = new Date(Date.now() - minutes * 60_000).toISOString();
  const rows = getDb().prepare(`SELECT data FROM unit_stat WHERE at >= ? ORDER BY at`).all(from) as Array<{
    data: string;
  }>;
  return rows.map((r) => {
    const s = JSON.parse(r.data) as UnitStats;
    return { at: s.at, cpu: s.cpu, memAvail: s.memAvail, swapFree: s.swapFree, temp: s.temp };
  });
}
