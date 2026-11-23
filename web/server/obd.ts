// ---------------------------------------------------------------------------
// Adaptorul OBD2 din mașină (cheia „o” din POST /api/car/state, 1.1.100+): starea legăturii
// (conectat / caută / motor oprit / dezactivat), citirile pe secundă, comutatorul „OBD (teste)”
// (din site, Panel sau mașină; mașina îl află din răspunsul la starea live, `obd`) și codurile
// de eroare (memorate în `obd_dtc`, ca să rămână și cu mașina oprită). Tot ce ține de OBD e de
// test: cu comutatorul oprit (sau fără adaptor) călătoriile și restul merg ca înainte.
// Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { getDb } from "./db.ts";
import { describeDtc, DTC_DETAIL, parseDtcs, parseUdsDtcs, UDS_MODULES } from "./dtc.ts";
import { flapFault } from "./flap.ts";
import { scanRateNow } from "./obd-scan.ts";

export type ObdStatus = "connected" | "searching" | "off" | "disabled";

export interface ObdCode {
  code: string;
  text: string;
  known: boolean;
  kind: "stored" | "pending"; // memorat (03) sau în așteptare (07)
  since: string; // prima citire în care a apărut
}

/** O eroare de arătat pe Acum, din orice modul. */
export interface ObdFault {
  module: string; // „Motor”, „Bord”, „Climatronic”, „Airbag”
  code: string; // „P0301” (OBD) sau „U1013 00” (UDS)
  text: string;
  known: boolean;
  state: "active" | "stored" | "pending"; // defect acum / memorat / în așteptare
  since: string;
  detail?: string; // ce înseamnă și ce faci (site, Panel)
}

export interface ObdModule {
  module: string;
  name: string;
  checkedAt: string;
  ok: boolean; // a răspuns la ultima citire
  count: number; // erori active (cleared_at NULL)
}

export interface ObdLive {
  status: ObdStatus | null; // null = nicio stare recentă de la mașină
  hz: number | null; // citiri ale motorului pe secundă
  scanHz: number | null; // scanarea: coduri pe secundă (null = nu scanează)
  enabled: boolean; // comutatorul „OBD (teste)”: oprit = adaptorul liber, nu se citește nimic
  checkedAt: string | null; // ultima citire a codurilor
  mil: boolean; // martorul motorului aprins la ultima citire
  codes: ObdCode[]; // active (OBD standard, doar motorul)
  faults: ObdFault[]; // toate, din toate modulele (fără dublurile motorului)
  modules: ObdModule[]; // modulele citite prin UDS
}

// „x” = comutatorul oprit în mașină; „r” (eliberat 10 min, până la 1.1.102) se citește ca motor oprit.
const STATUS: Record<string, ObdStatus> = { c: "connected", s: "searching", o: "off", r: "off", x: "disabled" };
const FRESH_MS = 90_000;

let last: { at: number; status: ObdStatus; hz: number | null } | null = null;

/** Comutatorul „OBD (teste)”, pornit implicit (tabela `setting`, cheia `obd_enabled`). */
export function obdEnabled(): boolean {
  const r = getDb().prepare(`SELECT value FROM setting WHERE key = 'obd_enabled'`).get() as { value: string } | undefined;
  return r?.value !== "0";
}

export function setObdEnabled(on: unknown): boolean {
  getDb()
    .prepare(`INSERT INTO setting (key, value) VALUES ('obd_enabled', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value`)
    .run(on === false ? "0" : "1");
  return obdEnabled();
}

/** Cheia „o” din starea mașinii: { st, hz, rel?, d?: { at, mil, n, s, p } }. */
export function saveObd(o: unknown): void {
  if (!o || typeof o !== "object") return;
  const x = o as Record<string, unknown>;
  const status = typeof x.st === "string" ? STATUS[x.st] : undefined;
  if (status) last = { at: Date.now(), status, hz: typeof x.hz === "number" ? x.hz : null };
  const d = x.d as Record<string, unknown> | undefined;
  if (d && typeof d === "object" && typeof d.at === "number") saveCheck(d);
  const u = x.u as Record<string, unknown> | undefined;
  if (u && typeof u === "object" && typeof u.at === "number" && u.m && typeof u.m === "object") {
    saveModules(u.at, u.m as Record<string, unknown>);
    if (typeof u.l === "string") saveLamp(u.at, u.l);
  }
}

/** Avertizarea de bec ars de pe bord (DID 2224, bitul 0x01 din primul octet); null = nu se poate citi. */
export function lampWarning(raw: string): boolean | null {
  if (!/^([0-9A-Fa-f]{2})+$/.test(raw)) return null;
  return (parseInt(raw.slice(0, 2), 16) & 0x01) !== 0;
}

function saveLamp(atMs: number, raw: string): void {
  const warn = lampWarning(raw);
  if (warn === null) return; // bordul n-a răspuns: rămâne ce era
  const iso = new Date(atMs).toISOString();
  const db = getDb();
  const prev = db.prepare(`SELECT at, since FROM obd_lamp WHERE id = 1`).get() as { at: string; since: string | null } | undefined;
  if (prev && prev.at >= iso) return; // aceeași citire, trimisă din nou cu starea
  const since = warn ? (prev?.since ?? iso) : null;
  db.prepare(
    `INSERT INTO obd_lamp (id, at, raw, since) VALUES (1, ?, ?, ?)
     ON CONFLICT (id) DO UPDATE SET at = excluded.at, raw = excluded.raw, since = excluded.since`,
  ).run(iso, raw.toUpperCase(), since);
}

/** Citirea UDS a modulelor: { "7E0": "5902FF…", "714": "-" (n-a răspuns), … }. */
function saveModules(atMs: number, m: Record<string, unknown>): void {
  const at = new Date(atMs);
  if (Number.isNaN(at.getTime())) return;
  const iso = at.toISOString();
  const db = getDb();
  db.exec("BEGIN");
  try {
    for (const [module, raw] of Object.entries(m)) {
      if (!(module in UDS_MODULES)) continue;
      const prev = db.prepare(`SELECT at FROM obd_module_check WHERE module = ?`).get(module) as { at: string } | undefined;
      if (prev && prev.at >= iso) continue; // aceeași citire, trimisă din nou cu starea
      const codes = parseUdsDtcs(raw as string);
      db.prepare(
        `INSERT INTO obd_module_check (module, at, ok) VALUES (?, ?, ?)
         ON CONFLICT (module) DO UPDATE SET at = excluded.at, ok = excluded.ok`,
      ).run(module, iso, codes ? 1 : 0);
      if (!codes) continue; // n-a răspuns: erorile de dinainte rămân cum erau
      const active = db
        .prepare(`SELECT id, code FROM obd_module_dtc WHERE module = ? AND cleared_at IS NULL`)
        .all(module) as Array<{ id: number; code: string }>;
      for (const r of active) {
        const now = codes.find((c) => c.code === r.code);
        if (now) db.prepare(`UPDATE obd_module_dtc SET last_at = ?, status = ? WHERE id = ?`).run(iso, now.status, r.id);
        else db.prepare(`UPDATE obd_module_dtc SET cleared_at = ? WHERE id = ?`).run(iso, r.id);
      }
      for (const c of codes) {
        if (active.some((r) => r.code === c.code)) continue;
        db.prepare(
          `INSERT INTO obd_module_dtc (module, code, status, first_at, last_at) VALUES (?, ?, ?, ?, ?)`,
        ).run(module, c.code, c.status, iso, iso);
      }
    }
    db.exec("COMMIT");
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
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
  const enabled = obdEnabled();
  const check = db.prepare(`SELECT at, mil FROM obd_check WHERE id = 1`).get() as { at: string; mil: number } | undefined;
  const rows = db
    .prepare(`SELECT code, kind, first_at FROM obd_dtc WHERE cleared_at IS NULL ORDER BY kind DESC, first_at`)
    .all() as Array<{ code: string; kind: "stored" | "pending"; first_at: string }>;
  const fresh = last && now - last.at < FRESH_MS ? last : null;
  const codes = rows.map((r) => ({ ...describeDtc(r.code), kind: r.kind, since: r.first_at }));
  const mods = db
    .prepare(`SELECT module, at, ok FROM obd_module_check ORDER BY module`)
    .all() as Array<{ module: string; at: string; ok: number }>;
  const udsRows = db
    .prepare(`SELECT module, code, status, first_at FROM obd_module_dtc WHERE cleared_at IS NULL ORDER BY first_at`)
    .all() as Array<{ module: string; code: string; status: number; first_at: string }>;
  const faults: ObdFault[] = udsRows.map((r) => {
    const sae = r.code.split(" ")[0];
    const d = describeDtc(sae);
    return {
      module: UDS_MODULES[r.module] ?? r.module,
      code: r.code,
      text: d.text,
      known: d.known,
      state: r.status & 0x01 ? "active" : r.status & 0x08 ? "stored" : "pending",
      since: r.first_at,
      ...(DTC_DETAIL[sae] ? { detail: DTC_DETAIL[sae] } : {}),
    };
  });
  const lamp = db.prepare(`SELECT since FROM obd_lamp WHERE id = 1`).get() as { since: string | null } | undefined;
  if (lamp?.since) {
    faults.push({ module: "Bord", code: "bec", text: "Bec ars: vezi pe bord care", known: true, state: "active", since: lamp.since });
  }
  const flap = flapFault();
  if (flap) faults.push(flap);
  // Codurile OBD ale motorului, doar dacă citirea UDS a motorului nu le are deja.
  const engineUds = new Set(udsRows.filter((r) => r.module === "7E0").map((r) => r.code.split(" ")[0]));
  for (const c of codes) {
    if (engineUds.has(c.code)) continue;
    faults.unshift({ module: "Motor", code: c.code, text: c.text, known: c.known, state: c.kind === "pending" ? "pending" : "stored", since: c.since });
  }
  return {
    status: !enabled ? "disabled" : (fresh?.status ?? null),
    hz: enabled && fresh?.status === "connected" ? fresh.hz : null,
    scanHz: enabled && fresh?.status === "connected" ? scanRateNow() : null,
    enabled,
    checkedAt: check?.at ?? null,
    mil: check?.mil === 1,
    codes,
    faults,
    modules: mods.map((m) => ({
      module: m.module,
      name: UDS_MODULES[m.module] ?? m.module,
      checkedAt: m.at,
      ok: m.ok === 1,
      count: udsRows.filter((r) => r.module === m.module).length,
    })),
  };
}
