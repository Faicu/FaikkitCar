// ---------------------------------------------------------------------------
// Scanarea OBD: serverul ține lista lucrărilor (obd_scan_job, în ordine) și catalogul a tot ce
// a răspuns (obd_code + istoricul valorilor în obd_sample). Mașina ia lucrările de la
// GET /api/car/obd/scan, citește cu motorul pornit și trimite rezultatele și progresul prin
// coada ei de pe disc (POST /api/car/obd/scan), deci o repornire sau o actualizare a aplicației
// reia de la ultimul progres ajuns aici. Doar citiri: 22 (UDS), 01/02/06/09 și 03/07/0A.
// Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { HTTPException } from "hono/http-exception";

import { getDb } from "./db.ts";

export type ScanSvc = "22" | "01" | "02" | "06" | "09" | "dtc";
export type ScanKind = "range" | "list" | "loop" | "watch";
export type ScanStatus = "queued" | "running" | "done" | "paused";

/** Refuzul „lent / fără răspuns” (nu e un cod UDS: acelea sunt un octet). */
export const NRC_SLOW = 256;
/** „Codul nu există” (7F 22 31): trimis doar de recitiri, ca să scoată din catalog un cod lent. */
export const NRC_MISSING = 0x31;

export const SCAN_MODULES: Record<string, { name: string; rx: string }> = {
  "7E0": { name: "Motor", rx: "7E8" },
  "714": { name: "Bord", rx: "77E" },
  "746": { name: "Climatronic", rx: "7B0" },
};

const SVC: ScanSvc[] = ["22", "01", "02", "06", "09", "dtc"];

export interface ScanJob {
  id: number;
  ord: number;
  label: string;
  tx: string;
  rx: string;
  svc: ScanSvc;
  kind: ScanKind;
  first: number | null;
  last: number | null;
  ids: number[] | null;
  next: number;
  round: number;
  parked: boolean; // doar cu mașina pe loc (recitirea celor lente)
  parent: number | null;
  everyS: number | null; // watch: la câte secunde se reia
  passAt: string | null; // watch: ultima trecere
  status: ScanStatus;
  createdAt: string;
  startedAt: string | null;
  updatedAt: string | null;
  doneAt: string | null;
}

interface JobRow {
  id: number;
  ord: number;
  label: string;
  tx: string;
  rx: string;
  svc: string;
  kind: string;
  first: number | null;
  last: number | null;
  ids: string | null;
  next: number;
  round: number;
  parked: number;
  parent: number | null;
  every_s: number | null;
  pass_at: string | null;
  status: string;
  created_at: string;
  started_at: string | null;
  updated_at: string | null;
  done_at: string | null;
}

function toJob(r: JobRow): ScanJob {
  return {
    id: r.id,
    ord: r.ord,
    label: r.label,
    tx: r.tx,
    rx: r.rx,
    svc: r.svc as ScanSvc,
    kind: r.kind as ScanKind,
    first: r.first,
    last: r.last,
    ids: r.ids ? (JSON.parse(r.ids) as number[]) : null,
    next: r.next,
    round: r.round,
    parked: r.parked === 1,
    parent: r.parent,
    everyS: r.every_s ?? null,
    passAt: r.pass_at ?? null,
    status: r.status as ScanStatus,
    createdAt: r.created_at,
    startedAt: r.started_at,
    updatedAt: r.updated_at,
    doneAt: r.done_at,
  };
}

/** Câte coduri are lucrarea și câte sunt gata (pentru procente). */
export function jobProgress(j: Pick<ScanJob, "kind" | "first" | "last" | "ids" | "next" | "status">): {
  done: number;
  total: number;
} {
  const total = j.kind === "range" ? (j.last ?? 0) - (j.first ?? 0) + 1 : (j.ids?.length ?? 0);
  if (j.status === "done") return { done: total, total };
  const done = j.kind === "range" ? j.next - (j.first ?? 0) : j.next;
  return { done: Math.max(0, Math.min(total, done)), total };
}

// --- ordinea de la început ------------------------------------------------------------------

interface Seed {
  label: string;
  tx: string;
  svc: ScanSvc;
  kind: ScanKind;
  first?: number;
  last?: number;
  ids?: number[];
}

/**
 * Ordinea stabilită cu utilizatorul (21.11): motorul, OBD-ul standard, restul climatronicului
 * (0000–0A3A sunt importate din scanarea de pe 21.11), apoi recitirea în buclă a codurilor găsite.
 * Bordul (714) e scanat complet pe 21.11 (importat). Airbag-ul nu interesează.
 */
export const SEED: Seed[] = [
  { label: "Motor: toate codurile", tx: "7E0", svc: "22", kind: "range", first: 0x0000, last: 0xffff },
  { label: "OBD: valorile live", tx: "7E0", svc: "01", kind: "range", first: 0x00, last: 0xff },
  { label: "OBD: informațiile vehiculului", tx: "7E0", svc: "09", kind: "range", first: 0x00, last: 0xff },
  { label: "OBD: testele de la bord", tx: "7E0", svc: "06", kind: "range", first: 0x00, last: 0xff },
  { label: "OBD: datele de la apariția erorii", tx: "7E0", svc: "02", kind: "range", first: 0x00, last: 0xff },
  { label: "OBD: codurile de eroare", tx: "7E0", svc: "dtc", kind: "list", ids: [0x03, 0x07, 0x0a] },
  { label: "Climatronic: restul codurilor", tx: "746", svc: "22", kind: "range", first: 0x0a3b, last: 0xffff },
  { label: "Recitire: motor", tx: "7E0", svc: "22", kind: "loop" },
  { label: "Recitire: OBD live", tx: "7E0", svc: "01", kind: "loop" },
  { label: "Recitire: bord", tx: "714", svc: "22", kind: "loop" },
  { label: "Recitire: climatronic", tx: "746", svc: "22", kind: "loop" },
];

/**
 * Urmărirea candidaților (22.11, cererea utilizatorului): codurile bănuite (OBD-CODURI.md, „Candidați”)
 * recitite la fiecare pornire și apoi la 2 minute, înaintea scanării, ca să le putem lega de CAN / OBD
 * din aceeași clipă (1007 la prima pornire după o oprire lungă, 2205 cu frâna, 202F cu lichidul de răcire).
 * O trecere ține câteva secunde; restul timpului merge scanarea.
 */
export const WATCH: Array<Seed & { ord: number; everyS: number }> = [
  {
    label: "Urmărire: bord (candidații)", tx: "714", svc: "22", kind: "watch", ord: 0.1, everyS: 120,
    ids: [0x1007, 0x1009, 0x100a, 0x1014, 0x1019, 0x101a, 0x2203, 0x2205, 0x2206, 0x2208, 0x220c, 0x220d,
      0x2224, 0x2243, 0x2260, 0x2275, 0x202f, 0xf405, 0xf40c, 0xf40d, 0xf442],
  },
  {
    label: "Urmărire: motor (candidații)", tx: "7E0", svc: "22", kind: "watch", ord: 0.2, everyS: 120,
    ids: [0x1000, 0x1001, 0x1002, 0x1154, 0x117c, 0x117d, 0x1189, 0x118a, 0x118b, 0x118c, 0x118d, 0x118e,
      0x118f, 0x121c, 0x15d4, 0x2206, 0x3d73, 0x406a, 0x4183],
  },
];

/** Pune lucrările de urmărire care lipsesc (după etichetă); cele existente rămân cum le-a lăsat utilizatorul. */
export function seedWatchJobs(now = new Date().toISOString()): number {
  const db = getDb();
  const has = db.prepare(`SELECT 1 FROM obd_scan_job WHERE label = ?`);
  const ins = db.prepare(
    `INSERT INTO obd_scan_job (ord, label, tx, rx, svc, kind, ids, every_s, status, created_at)
     VALUES (?, ?, ?, ?, ?, 'watch', ?, ?, 'queued', ?)`,
  );
  let n = 0;
  for (const w of WATCH) {
    if (has.get(w.label)) continue;
    ins.run(w.ord, w.label, w.tx, SCAN_MODULES[w.tx].rx, w.svc, JSON.stringify(w.ids), w.everyS, now);
    n++;
  }
  return n;
}

/** O lucrare de urmărire în pauza dintre treceri. */
function resting(r: Pick<JobRow, "kind" | "every_s" | "pass_at">, now: number): boolean {
  return r.kind === "watch" && r.pass_at !== null && now - Date.parse(r.pass_at) < (r.every_s ?? 0) * 1000;
}

/** Pune ordinea de la început, o singură dată (tabela goală). */
export function seedScanJobs(now = new Date().toISOString()): number {
  const db = getDb();
  const n = (db.prepare(`SELECT COUNT(*) AS n FROM obd_scan_job`).get() as { n: number }).n;
  if (n > 0) return 0;
  const ins = db.prepare(
    `INSERT INTO obd_scan_job (ord, label, tx, rx, svc, kind, first, last, ids, status, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'queued', ?)`,
  );
  db.exec("BEGIN");
  SEED.forEach((s, i) => {
    ins.run(i + 1, s.label, s.tx, SCAN_MODULES[s.tx].rx, s.svc, s.kind, s.first ?? null, s.last ?? null,
      s.ids ? JSON.stringify(s.ids) : null, now);
  });
  db.exec("COMMIT");
  return SEED.length;
}

// --- ce primește mașina ---------------------------------------------------------------------

function rows(): JobRow[] {
  return getDb()
    .prepare(`SELECT * FROM obd_scan_job ORDER BY ord, id`)
    .all() as unknown as JobRow[];
}

/**
 * Codurile din catalog pentru o recitire (lista unei treceri noi): cele bune și cele refuzate doar
 * de condiții (0x21 ocupat, 0x22 condiții neîndeplinite, 0x24 ordinea cererii), care pot răspunde
 * altă dată (motorul pornit / cald, în mers). Nu cele cu acces refuzat (0x33) și nici cele lente
 * (au recitirea lor, doar pe loc).
 */
function catalogIds(tx: string, svc: string): number[] {
  return (
    getDb()
      .prepare(`SELECT id FROM obd_code WHERE tx = ? AND svc = ? AND (nrc IS NULL OR nrc IN (0x21, 0x22, 0x24)) ORDER BY id`)
      .all(tx, svc) as Array<{ id: number }>
  ).map((r) => r.id);
}

/**
 * Primele lucrări de făcut (cel mult `max`), în ordine; mașina o ia pe prima potrivită (cele
 * `parked` doar pe loc). O buclă își ia lista din catalog la sfârșitul fiecărei treceri (fixă
 * pe toată trecerea: progresul e un index în ea); până are ceva în catalog (modulul nescanat
 * încă) e sărită.
 */
export function carScanJobs(max = 3, now = Date.now()): ScanJob[] {
  const db = getDb();
  const out: ScanJob[] = [];
  for (const r of rows()) {
    if (out.length >= max) break;
    if (r.status !== "queued" && r.status !== "running") continue;
    if (resting(r, now)) continue;
    if (r.kind === "loop" && (!r.ids || r.ids === "[]")) {
      const ids = catalogIds(r.tx, r.svc);
      if (ids.length === 0) continue;
      r.ids = JSON.stringify(ids);
      r.next = 0;
      db.prepare(`UPDATE obd_scan_job SET ids = ?, next = 0 WHERE id = ?`).run(r.ids, r.id);
    }
    // Mașina știe doar range / list / loop: urmărirea e pentru ea o buclă (trecerile au `round`).
    const j = toJob(r);
    out.push(j.kind === "watch" ? { ...j, kind: "loop" } : j);
  }
  return out;
}

// --- ce trimite mașina ----------------------------------------------------------------------

/**
 * Un element din coada mașinii. Rezultat: { t: "r", j, x: tx, s: svc, i: id, v?: hex, n?: nrc,
 * at: ms }. Progres: { t: "p", j, r: round, n: next, d?: 1 = lucrarea terminată }.
 */
export interface ScanItem {
  t: "r" | "p";
  j: number;
  x?: string;
  s?: string;
  i?: number;
  v?: string;
  n?: number;
  r?: number;
  d?: number;
  at?: number;
}

const HEX = /^[0-9A-F]*$/;

/** Ce se schimbă în catalog la un rezultat: valoarea nouă, refuzul, dacă intră în istoric. */
export function codeUpdate(
  prev: { value: string | null; nrc: number | null } | undefined,
  value: string | null,
  nrc: number | null,
): { value: string | null; nrc: number | null; changed: boolean; sample: boolean } {
  if (nrc !== null) {
    // Un refuz sau o tăcere nu șterge valoarea bună de dinainte (o recitire în condiții proaste).
    if (prev && prev.nrc === null) return { value: prev.value, nrc: null, changed: false, sample: false };
    return { value: prev?.value ?? null, nrc, changed: false, sample: false };
  }
  const changed = prev !== undefined && prev.value !== null && prev.value !== value;
  const sample = prev === undefined || prev.value !== value;
  return { value, nrc: null, changed, sample };
}

export function saveScanItems(items: unknown): { ok: number; skipped: number } {
  if (!Array.isArray(items) || items.length > 2000) throw new HTTPException(400, { message: "Invalid items" });
  const db = getDb();
  const now = new Date().toISOString();
  const getCode = db.prepare(`SELECT value, nrc FROM obd_code WHERE tx = ? AND svc = ? AND id = ?`);
  const insCode = db.prepare(
    `INSERT INTO obd_code (tx, svc, id, value, nrc, first_at, last_at, reads, changes)
     VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0)`,
  );
  const updCode = db.prepare(
    `UPDATE obd_code SET value = ?, nrc = ?, last_at = ?, reads = reads + 1, changes = changes + ?
     WHERE tx = ? AND svc = ? AND id = ?`,
  );
  const delCode = db.prepare(`DELETE FROM obd_code WHERE tx = ? AND svc = ? AND id = ?`);
  const insSample = db.prepare(`INSERT INTO obd_sample (tx, svc, id, at, value) VALUES (?, ?, ?, ?, ?)`);
  let ok = 0;
  let skipped = 0;
  db.exec("BEGIN");
  try {
    for (const raw of items as ScanItem[]) {
      if (!raw || typeof raw !== "object" || typeof raw.j !== "number") {
        skipped++;
        continue;
      }
      if (raw.t === "p") {
        if (progress(raw, now)) ok++;
        else skipped++;
        continue;
      }
      const tx = typeof raw.x === "string" ? raw.x.toUpperCase() : "";
      const svc = typeof raw.s === "string" ? raw.s : "";
      const id = raw.i;
      const value = typeof raw.v === "string" ? raw.v.toUpperCase() : null;
      const nrc = typeof raw.n === "number" ? raw.n : null;
      if (raw.t !== "r" || !(tx in SCAN_MODULES) || !SVC.includes(svc as ScanSvc) || typeof id !== "number"
        || !Number.isInteger(id) || id < 0 || id > 0xffff || (value === null) === (nrc === null)
        || (value !== null && (!HEX.test(value) || value.length > 1024))) {
        skipped++;
        continue;
      }
      const at = typeof raw.at === "number" && Number.isFinite(raw.at) ? new Date(raw.at).toISOString() : now;
      const prev = getCode.get(tx, svc, id) as { value: string | null; nrc: number | null } | undefined;
      if (nrc === NRC_MISSING) {
        // O recitire a aflat că un cod lent nu există: iese din catalog (unul bun rămâne).
        if (prev && prev.value === null) delCode.run(tx, svc, id);
        ok++;
        continue;
      }
      const u = codeUpdate(prev, value, nrc);
      if (prev) updCode.run(u.value, u.nrc, at, u.changed ? 1 : 0, tx, svc, id);
      else insCode.run(tx, svc, id, u.value, u.nrc, at, at);
      if (u.sample && value !== null) insSample.run(tx, svc, id, at, value);
      ok++;
    }
    db.exec("COMMIT");
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
  return { ok, skipped };
}

/** Progresul unei lucrări (în aceeași tranzacție cu rezultatele dinaintea lui). */
function progress(p: ScanItem, now: string): boolean {
  const db = getDb();
  const r = db.prepare(`SELECT * FROM obd_scan_job WHERE id = ?`).get(p.j) as JobRow | undefined;
  if (!r || typeof p.n !== "number") return false;
  // Progresul unei treceri vechi a buclei (retrimis din coadă) nu mai contează.
  if ((r.kind === "loop" || r.kind === "watch") && (p.r ?? 0) !== r.round) return false;
  if (r.status === "done") return false;
  const next = Math.max(r.next, Math.trunc(p.n));
  db.prepare(
    `UPDATE obd_scan_job SET next = ?, status = CASE status WHEN 'queued' THEN 'running' ELSE status END,
       started_at = COALESCE(started_at, ?), updated_at = ? WHERE id = ?`,
  ).run(next, now, now, r.id);
  if (p.d === 1) finishJob(toJob({ ...r, next }), now);
  return true;
}

/**
 * Lucrare terminată. O buclă trece la sfârșitul listei pentru trecerea următoare. După o
 * scanare de interval, codurile rămase lente (în mers nu se așteaptă după ele) se recitesc
 * într-o lucrare doar pe loc, imediat după.
 */
export function finishJob(j: ScanJob, now = new Date().toISOString()): void {
  const db = getDb();
  const maxOrd = (db.prepare(`SELECT MAX(ord) AS m FROM obd_scan_job`).get() as { m: number }).m;
  if (j.kind === "watch") {
    // Aceeași listă, același loc în față; pauza până la trecerea următoare.
    db.prepare(
      `UPDATE obd_scan_job SET next = 0, round = round + 1, pass_at = ?, status = 'queued', updated_at = ? WHERE id = ?`,
    ).run(now, now, j.id);
    return;
  }
  if (j.kind === "loop") {
    db.prepare(
      `UPDATE obd_scan_job SET next = 0, round = round + 1, ids = ?, ord = ?, status = 'queued', updated_at = ?
       WHERE id = ?`,
    ).run(JSON.stringify(catalogIds(j.tx, j.svc)), maxOrd + 1, now, j.id);
    return;
  }
  db.prepare(`UPDATE obd_scan_job SET status = 'done', done_at = ?, updated_at = ? WHERE id = ?`).run(now, now, j.id);
  if (j.kind !== "range" || j.parent !== null) return;
  const slow = (
    db
      .prepare(`SELECT id FROM obd_code WHERE tx = ? AND svc = ? AND nrc = ? AND id BETWEEN ? AND ? ORDER BY id`)
      .all(j.tx, j.svc, NRC_SLOW, j.first ?? 0, j.last ?? 0) as Array<{ id: number }>
  ).map((r) => r.id);
  if (slow.length === 0) return;
  // Imediat după lucrarea terminată, înaintea următoarei.
  const after = db
    .prepare(`SELECT MIN(ord) AS m FROM obd_scan_job WHERE ord > ?`)
    .get(j.ord) as { m: number | null };
  const ord = after.m === null ? maxOrd + 1 : (j.ord + after.m) / 2;
  db.prepare(
    `INSERT INTO obd_scan_job (ord, label, tx, rx, svc, kind, ids, parked, parent, status, created_at)
     VALUES (?, ?, ?, ?, ?, 'list', ?, 1, ?, 'queued', ?)`,
  ).run(ord, `${j.label}: recitirea celor lente`, j.tx, j.rx, j.svc, JSON.stringify(slow), j.id, now);
}

// --- pentru site ----------------------------------------------------------------------------

export interface ScanModuleStats {
  tx: string;
  name: string;
  codes: number; // răspuns bun
  changing: number; // valoarea s-a schimbat măcar o dată
  refused: number; // există, dar refuzat (condiții, acces)
  slow: number; // lente / fără răspuns
  samples: number;
}

export interface ScanOverview {
  jobs: Array<ScanJob & { done: number; total: number }>;
  modules: ScanModuleStats[];
  current: number | null; // lucrarea pe care o face mașina acum (prima din ordine)
}

export function readScanOverview(): ScanOverview {
  const db = getDb();
  const jobs = rows().map((r) => {
    const j = toJob(r);
    return { ...j, ...jobProgress(j), ids: j.kind === "range" ? null : j.ids };
  });
  const stats = db
    .prepare(
      `SELECT tx, SUM(nrc IS NULL) AS codes, SUM(nrc IS NULL AND changes > 0) AS changing,
         SUM(nrc IS NOT NULL AND nrc <> ?) AS refused, SUM(nrc = ?) AS slow
       FROM obd_code GROUP BY tx`,
    )
    .all(NRC_SLOW, NRC_SLOW) as Array<{ tx: string; codes: number; changing: number; refused: number; slow: number }>;
  const samples = db.prepare(`SELECT tx, COUNT(*) AS n FROM obd_sample GROUP BY tx`).all() as Array<{ tx: string; n: number }>;
  const modules = Object.entries(SCAN_MODULES).map(([tx, m]) => {
    const s = stats.find((x) => x.tx === tx);
    return {
      tx,
      name: m.name,
      codes: s?.codes ?? 0,
      changing: s?.changing ?? 0,
      refused: s?.refused ?? 0,
      slow: s?.slow ?? 0,
      samples: samples.find((x) => x.tx === tx)?.n ?? 0,
    };
  });
  const now = Date.now();
  const current = jobs.find((j) => (j.status === "queued" || j.status === "running") && !(j.kind === "loop" && !j.ids?.length)
    && !resting({ kind: j.kind, every_s: j.everyS, pass_at: j.passAt }, now));
  return { jobs, modules, current: current?.id ?? null };
}

export interface ScanCode {
  svc: string;
  id: number;
  value: string | null;
  nrc: number | null;
  firstAt: string;
  lastAt: string;
  reads: number;
  changes: number;
}

export function readScanCodes(tx: string): ScanCode[] {
  if (!(tx in SCAN_MODULES)) throw new HTTPException(404, { message: "Modul necunoscut" });
  return (
    getDb()
      .prepare(`SELECT * FROM obd_code WHERE tx = ? ORDER BY svc, id`)
      .all(tx) as Array<{
      svc: string;
      id: number;
      value: string | null;
      nrc: number | null;
      first_at: string;
      last_at: string;
      reads: number;
      changes: number;
    }>
  ).map((r) => ({
    svc: r.svc,
    id: r.id,
    value: r.value,
    nrc: r.nrc,
    firstAt: r.first_at,
    lastAt: r.last_at,
    reads: r.reads,
    changes: r.changes,
  }));
}

export function readScanSamples(tx: string, svc: string, id: number, limit = 500): Array<{ at: string; value: string }> {
  return getDb()
    .prepare(`SELECT at, value FROM obd_sample WHERE tx = ? AND svc = ? AND id = ? ORDER BY at DESC LIMIT ?`)
    .all(tx, svc, id, limit) as Array<{ at: string; value: string }>;
}

/** Oprirea / reluarea unei lucrări de pe site. */
export function setScanJobPaused(id: number, paused: boolean): void {
  const db = getDb();
  const r = db.prepare(`SELECT * FROM obd_scan_job WHERE id = ?`).get(id) as JobRow | undefined;
  if (!r) throw new HTTPException(404, { message: "Lucrare necunoscută" });
  if (r.status === "done") return;
  const status = paused ? "paused" : r.started_at ? "running" : "queued";
  db.prepare(`UPDATE obd_scan_job SET status = ?, updated_at = ? WHERE id = ?`).run(status, new Date().toISOString(), id);
}

// --- importul scanărilor vechi (fișierele `@obd scan status` din 21.11) --------------------

/**
 * Liniile „714 1007: 16 D3” (răspuns bun), „746 0670: 7F 22 22” (refuz) și „714 1234: lent
 * (fara raspuns)”; ultima apariție a unui cod câștigă.
 */
export function parseScanStatus(text: string): Map<string, { tx: string; id: number; value: string | null; nrc: number | null }> {
  const out = new Map<string, { tx: string; id: number; value: string | null; nrc: number | null }>();
  for (const line of text.split("\n")) {
    const m = /^([0-9A-F]{3}) ([0-9A-F]{4}): (.*)$/.exec(line.trim());
    if (!m) continue;
    const [, tx, did, rest] = m;
    const id = parseInt(did, 16);
    if (rest.startsWith("lent")) {
      out.set(`${tx}:${id}`, { tx, id, value: null, nrc: NRC_SLOW });
      continue;
    }
    const bytes = rest.replace(/\s+".*"$/, "").trim().split(/\s+/);
    if (!bytes.every((b) => /^[0-9A-F]{2}$/.test(b))) continue;
    if (bytes.length === 3 && bytes[0] === "7F" && bytes[1] === "22") {
      out.set(`${tx}:${id}`, { tx, id, value: null, nrc: parseInt(bytes[2], 16) });
    } else {
      out.set(`${tx}:${id}`, { tx, id, value: bytes.join(""), nrc: null });
    }
  }
  return out;
}
