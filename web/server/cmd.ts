// ---------------------------------------------------------------------------
// Comenzile shell rulate de aplicația din mașină (tabela car_cmd): utilizatorul le pune în
// coadă, mașina află de ele din răspunsul la /api/car/state, le ia, le rulează cu drepturile
// aplicației (fără root) și trimite rezultatul. Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { HTTPException } from "hono/http-exception";

import { getDb } from "./db.ts";

export interface CarCmd {
  id: number;
  cmd: string;
  timeoutS: number;
  createdAt: string;
  sentAt: string | null;
  doneAt: string | null;
  exitCode: number | null;
  output: string | null;
}

const MAX_CMD = 4_000;
const MAX_OUTPUT = 512 * 1024;
/** O comandă luată de mașină, dar fără rezultat după atât, se poate lua din nou (ex. a murit procesul). */
const RESEND_MS = 10 * 60_000;

interface Row {
  id: number;
  cmd: string;
  timeout_s: number;
  created_at: string;
  sent_at: string | null;
  done_at: string | null;
  exit_code: number | null;
  output: string | null;
}

function toCmd(r: Row): CarCmd {
  return {
    id: r.id,
    cmd: r.cmd,
    timeoutS: r.timeout_s,
    createdAt: r.created_at,
    sentAt: r.sent_at,
    doneAt: r.done_at,
    exitCode: r.exit_code,
    output: r.output,
  };
}

export function queueCmd(cmd: unknown, timeoutS: unknown): number {
  if (typeof cmd !== "string" || !cmd.trim()) throw new HTTPException(400, { message: "Lipsește comanda" });
  if (cmd.length > MAX_CMD) throw new HTTPException(400, { message: "Comanda e prea lungă" });
  const t = typeof timeoutS === "number" && timeoutS >= 1 && timeoutS <= 600 ? Math.round(timeoutS) : 30;
  const r = getDb()
    .prepare(`INSERT INTO car_cmd (cmd, timeout_s, created_at) VALUES (?, ?, ?)`)
    .run(cmd, t, new Date().toISOString());
  return Number(r.lastInsertRowid);
}

export function readCmds(limit = 50): CarCmd[] {
  return (
    getDb().prepare(`SELECT * FROM car_cmd ORDER BY id DESC LIMIT ?`).all(limit) as unknown as Row[]
  ).map(toCmd);
}

export function readCmd(id: number): CarCmd | null {
  const r = getDb().prepare(`SELECT * FROM car_cmd WHERE id = ?`).get(id) as unknown as Row | undefined;
  return r ? toCmd(r) : null;
}

const PENDING = `done_at IS NULL AND (sent_at IS NULL OR sent_at < ?)`;

/** Câte comenzi așteaptă mașina (în răspunsul la /api/car/state). */
export function pendingCmdCount(): number {
  const cut = new Date(Date.now() - RESEND_MS).toISOString();
  return (getDb().prepare(`SELECT COUNT(*) AS n FROM car_cmd WHERE ${PENDING}`).get(cut) as { n: number }).n;
}

/** Mașina ia comenzile în așteptare; rămân „trimise” până vine rezultatul. */
export function takeCmds(): Array<{ id: number; cmd: string; timeoutS: number }> {
  const db = getDb();
  const cut = new Date(Date.now() - RESEND_MS).toISOString();
  const rows = db
    .prepare(`SELECT * FROM car_cmd WHERE ${PENDING} ORDER BY id LIMIT 10`)
    .all(cut) as unknown as Row[];
  const now = new Date().toISOString();
  const mark = db.prepare(`UPDATE car_cmd SET sent_at = ? WHERE id = ?`);
  for (const r of rows) mark.run(now, r.id);
  return rows.map((r) => ({ id: r.id, cmd: r.cmd, timeoutS: r.timeout_s }));
}

export function saveCmdResult(id: number, exitCode: unknown, output: unknown): void {
  const out = typeof output === "string" ? output.slice(0, MAX_OUTPUT) : "";
  const code = typeof exitCode === "number" && Number.isInteger(exitCode) ? exitCode : null;
  const r = getDb()
    .prepare(`UPDATE car_cmd SET done_at = ?, exit_code = ?, output = ? WHERE id = ? AND done_at IS NULL`)
    .run(new Date().toISOString(), code, out, id);
  if (r.changes === 0) throw new HTTPException(404, { message: "Comandă necunoscută sau deja terminată" });
}
