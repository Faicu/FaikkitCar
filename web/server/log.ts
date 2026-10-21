// ---------------------------------------------------------------------------
// Jurnalul aplicației FaikkitCar (navigația Teyes din mașină). Aplicația
// trimite liniile de diagnostic prin POST /api/car/log;
// pagina Jurnal le afișează. Server-only: importă node:sqlite prin getDb.
// ---------------------------------------------------------------------------

import { getDb } from "./db.ts";
import { EVENT_CATEGORIES, LOG_CATEGORIES, logCategory, type LogCategory } from "./log-category.ts";

export interface LogEntry {
  id: number;
  deviceAt: string; // ora de pe navigație (poate fi greșită după un boot la rece, fără GPS)
  receivedAt: string;
  version: string | null;
  line: string;
  cat: LogCategory;
}

export interface IncomingLine {
  t: number; // epoch ms, ceasul navigației
  text: string;
}

const MAX_LINE_LEN = 1000;
export const MAX_LINES_PER_REQUEST = 200;

/** Inserează un lot; întoarce câte linii noi au intrat (duplicatele se ignoră). */
export function insertLines(lines: IncomingLine[], version: string | null): number {
  const db = getDb();
  const now = new Date().toISOString();
  const stmt = db.prepare(
    `INSERT OR IGNORE INTO log (device_at, received_at, version, line, cat) VALUES (?, ?, ?, ?, ?)`,
  );
  let added = 0;
  db.exec("BEGIN");
  try {
    for (const l of lines) {
      // O oră în afara intervalului Date ar arunca și ar respinge tot lotul la nesfârșit.
      const at = new Date(l.t);
      if (!Number.isFinite(l.t) || Number.isNaN(at.getTime()) || typeof l.text !== "string") continue;
      const deviceAt = at.toISOString();
      const text = l.text.slice(0, MAX_LINE_LEN);
      const r = stmt.run(deviceAt, now, version, text, logCategory(text));
      added += Number(r.changes);
    }
    // Jurnalul nu se șterge niciodată (câteva zeci de MB pe an).
    db.exec("COMMIT");
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
  return added;
}

/**
 * Liniile unei categorii (log-category.ts) sau, fără ea, „Evenimente”: toate în afară de CAN /
 * diagnostic și comenzi. `cat = "toate"` le ia pe toate.
 */
export function readLog(cat: string | undefined, limit = 1000): LogEntry[] {
  const cats: string[] =
    cat === "toate"
      ? LOG_CATEGORIES.map((c) => c.id)
      : LOG_CATEGORIES.some((c) => c.id === cat)
        ? [cat as string]
        : EVENT_CATEGORIES;
  const rows = getDb()
    .prepare(
      `SELECT id, device_at, received_at, version, line, cat FROM log
       WHERE cat IN (${cats.map(() => "?").join(", ")})
       ORDER BY device_at DESC, id DESC LIMIT ?`,
    )
    .all(...cats, limit) as Array<{
    id: number;
    device_at: string;
    received_at: string;
    version: string | null;
    line: string;
    cat: LogCategory;
  }>;
  return rows.map((r) => ({
    id: r.id,
    deviceAt: r.device_at,
    receivedAt: r.received_at,
    version: r.version,
    line: r.line,
    cat: r.cat,
  }));
}
