// Importă în catalogul OBD (obd_code, obd_sample) scanările vechi, salvate din `@obd scan status`
// înainte ca rezultatele să ajungă singure pe server (bordul complet și începutul climatronicului,
// 21.11.2026). Fișierele în ordinea în care au fost făcute: ce apare mai târziu câștigă; ora =
// data fișierului. Ce e deja în catalog pentru un cod nu se suprascrie.
// Folosire: node scripts/import-obd-scan.ts <fisier>…  (ca utilizatorul serviciului)

import { statSync, readFileSync } from "node:fs";

import { getDb } from "../server/db.ts";
import { parseScanStatus, SCAN_MODULES } from "../server/obd-scan.ts";

const files = process.argv.slice(2).sort((a, b) => statSync(a).mtimeMs - statSync(b).mtimeMs);
if (!files.length) {
  console.error("Folosire: scripts/import-obd-scan.ts <fisier>…");
  process.exit(2);
}

const found = new Map<string, { tx: string; id: number; value: string | null; nrc: number | null; at: string }>();
for (const f of files) {
  const at = statSync(f).mtime.toISOString();
  for (const [k, v] of parseScanStatus(readFileSync(f, "utf8"))) found.set(k, { ...v, at });
}

const db = getDb();
const has = db.prepare(`SELECT 1 FROM obd_code WHERE tx = ? AND svc = '22' AND id = ?`);
const ins = db.prepare(
  `INSERT INTO obd_code (tx, svc, id, value, nrc, first_at, last_at, reads, changes) VALUES (?, '22', ?, ?, ?, ?, ?, 1, 0)`,
);
const sample = db.prepare(`INSERT INTO obd_sample (tx, svc, id, at, value) VALUES (?, '22', ?, ?, ?)`);
const counts: Record<string, number> = {};
db.exec("BEGIN");
for (const v of found.values()) {
  if (!(v.tx in SCAN_MODULES) || has.get(v.tx, v.id)) continue;
  ins.run(v.tx, v.id, v.value, v.nrc, v.at, v.at);
  if (v.value !== null) sample.run(v.tx, v.id, v.at, v.value);
  counts[v.tx] = (counts[v.tx] ?? 0) + 1;
}
db.exec("COMMIT");
console.log(JSON.stringify(counts));
