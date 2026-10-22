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

export type UnitPoint = Pick<UnitStats, "at" | "cpu" | "memAvail" | "swapFree" | "temp">;

const HISTORY_EVERY_MS = 30_000;
const KEEP_DAYS = 90;

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
        .map((p) => ({
          name: String(p.n ?? "?").slice(0, 80),
          cpu: num(p.c),
          mb: num(p.m) ?? 0,
        }))
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
