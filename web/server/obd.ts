// ---------------------------------------------------------------------------
// Adaptorul OBD2 din mașină (cheia „o” din POST /api/car/state, 1.1.100+): starea legăturii
// (conectat / caută / eliberat / motor oprit), citirile pe secundă, eliberarea pentru Car Scanner
// (cerută din Panel, site sau mașină; mașina o află din răspunsul la starea live, `obdOff`) și
// codurile de eroare (memorate în `obd_dtc`, ca să rămână și cu mașina oprită).
// Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { getDb } from "./db.ts";
import { describeDtc, parseDtcs } from "./dtc.ts";

export type ObdStatus = "connected" | "searching" | "released" | "off";

export interface ObdCode {
  code: string;
  text: string;
  known: boolean;
  kind: "stored" | "pending"; // memorat (03) sau în așteptare (07)
  since: string; // prima citire în care a apărut
}

export interface ObdLive {
  status: ObdStatus | null; // null = nicio stare recentă de la mașină
  hz: number | null; // citiri ale motorului pe secundă
  releasedFor: number | null; // secunde rămase din eliberare (null = nu e eliberat)
  checkedAt: string | null; // ultima citire a codurilor
  mil: boolean; // martorul motorului aprins la ultima citire
  codes: ObdCode[]; // active
}

const STATUS: Record<string, ObdStatus> = { c: "connected", s: "searching", r: "released", o: "off" };
const FRESH_MS = 90_000;
const MAX_RELEASE_MIN = 60;

let last: { at: number; status: ObdStatus; hz: number | null } | null = null;
let releaseUntil = 0;

/** Eliberarea pentru Car Scanner: `minutes` 0 = reia acum. Întoarce secundele eliberării. */
export function releaseObd(minutes: unknown): number {
  const m = typeof minutes === "number" && Number.isFinite(minutes) ? Math.max(0, Math.min(MAX_RELEASE_MIN, minutes)) : 0;
  releaseUntil = m > 0 ? Date.now() + m * 60_000 : 0;
  return obdOffSeconds();
}

/** Secundele rămase din eliberare, pentru răspunsul la starea live (0 = nu e eliberat). */
export function obdOffSeconds(now = Date.now()): number {
  return releaseUntil > now ? Math.ceil((releaseUntil - now) / 1000) : 0;
}

/** Cheia „o” din starea mașinii: { st, hz, rel?, d?: { at, mil, n, s, p } }. */
export function saveObd(o: unknown): void {
  if (!o || typeof o !== "object") return;
  const x = o as Record<string, unknown>;
  const status = typeof x.st === "string" ? STATUS[x.st] : undefined;
  if (status) last = { at: Date.now(), status, hz: typeof x.hz === "number" ? x.hz : null };
  const d = x.d as Record<string, unknown> | undefined;
  if (d && typeof d === "object" && typeof d.at === "number") saveCheck(d);
}

function saveCheck(d: Record<string, unknown>): void {
  const stored = parseDtcs(d.s as string, 3);
  const pending = parseDtcs(d.p as string, 7);
  if (!stored || !pending) return;
  const at = new Date(d.at as number);
  if (Number.isNaN(at.getTime())) return;
  const iso = at.toISOString();
  const db = getDb();
  const prev = db.prepare(`SELECT at FROM obd_check WHERE id = 1`).get() as { at: string } | undefined;
  if (prev && prev.at >= iso) return; // aceeași citire, trimisă din nou cu starea
  db.exec("BEGIN");
  try {
    db.prepare(
      `INSERT INTO obd_check (id, at, mil) VALUES (1, ?, ?)
       ON CONFLICT (id) DO UPDATE SET at = excluded.at, mil = excluded.mil`,
    ).run(iso, d.mil === true ? 1 : 0);
    for (const [kind, codes] of [["stored", stored], ["pending", pending]] as const) {
      const active = db
        .prepare(`SELECT id, code FROM obd_dtc WHERE kind = ? AND cleared_at IS NULL`)
        .all(kind) as Array<{ id: number; code: string }>;
      for (const r of active) {
        if (codes.includes(r.code)) db.prepare(`UPDATE obd_dtc SET last_at = ? WHERE id = ?`).run(iso, r.id);
        else db.prepare(`UPDATE obd_dtc SET cleared_at = ? WHERE id = ?`).run(iso, r.id);
      }
      for (const code of new Set(codes)) {
        if (active.some((r) => r.code === code)) continue;
        db.prepare(`INSERT INTO obd_dtc (code, kind, first_at, last_at) VALUES (?, ?, ?, ?)`).run(code, kind, iso, iso);
      }
    }
    db.exec("COMMIT");
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
}

export function readObd(now = Date.now()): ObdLive {
  const db = getDb();
  const check = db.prepare(`SELECT at, mil FROM obd_check WHERE id = 1`).get() as { at: string; mil: number } | undefined;
  const rows = db
    .prepare(`SELECT code, kind, first_at FROM obd_dtc WHERE cleared_at IS NULL ORDER BY kind DESC, first_at`)
    .all() as Array<{ code: string; kind: "stored" | "pending"; first_at: string }>;
  const fresh = last && now - last.at < FRESH_MS ? last : null;
  const off = obdOffSeconds(now);
  return {
    status: off > 0 ? "released" : (fresh?.status ?? null),
    hz: fresh?.status === "connected" ? fresh.hz : null,
    releasedFor: off > 0 ? off : null,
    checkedAt: check?.at ?? null,
    mil: check?.mil === 1,
    codes: rows.map((r) => ({ ...describeDtc(r.code), kind: r.kind, since: r.first_at })),
  };
}
