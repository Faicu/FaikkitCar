// Copiază datele aplicației din baza FaikkitBox (tabelele vw_*) în faikkitcar.db.
// Se poate rula de oricâte ori: rândurile existente (aceeași oră + linie, aceeași oră a
// punctului, același id) sunt sărite, deci nu se dublează și nu se suprascrie nimic.
// Folosire: node scripts/import-faikkitbox.ts [/opt/faikkitbox/data/faikkitbox.db]

import { getDb } from "../server/db.ts";

const src = process.argv[2] ?? "/opt/faikkitbox/data/faikkitbox.db";
const db = getDb();
db.exec(`ATTACH DATABASE '${src.replaceAll("'", "''")}' AS box`);

const copies: Array<[string, string, string]> = [
  ["vw_log", "log", "device_at, received_at, version, line"],
  [
    "vw_trip_point",
    "trip_point",
    "device_at, received_at, lat, lon, alt, acc, gps_speed, can_speed, rpm, volt, temp, odo, fuel",
  ],
  ["vw_reminder", "reminder", "id, title, every_km, every_months, last_km, last_date, due_date, created_at"],
  ["vw_refuel", "refuel", "id, at, odo, liters, price, full, note, created_at"],
];

db.exec("BEGIN");
try {
  for (const [from, to, cols] of copies) {
    // Jurnalul și punctele: în ordinea originală, ca id-urile noi să urmeze cronologia primirii.
    const order = from === "vw_log" || from === "vw_trip_point" ? "ORDER BY id" : "";
    const r = db
      .prepare(`INSERT OR IGNORE INTO main.${to} (${cols}) SELECT ${cols} FROM box.${from} ${order}`)
      .run();
    console.log(`${from} → ${to}: ${r.changes} rânduri noi`);
  }
  db.exec("COMMIT");
} catch (e) {
  db.exec("ROLLBACK");
  throw e;
}

// Verificare: fiecare rând din FaikkitBox trebuie să existe aici.
const check: Array<[string, string]> = [
  ["SELECT count(*) n FROM box.vw_log b WHERE NOT EXISTS (SELECT 1 FROM main.log m WHERE m.device_at = b.device_at AND m.line = b.line)", "log"],
  ["SELECT count(*) n FROM box.vw_trip_point b WHERE NOT EXISTS (SELECT 1 FROM main.trip_point m WHERE m.device_at = b.device_at)", "trip_point"],
  ["SELECT count(*) n FROM box.vw_reminder b WHERE NOT EXISTS (SELECT 1 FROM main.reminder m WHERE m.id = b.id)", "reminder"],
  ["SELECT count(*) n FROM box.vw_refuel b WHERE NOT EXISTS (SELECT 1 FROM main.refuel m WHERE m.id = b.id)", "refuel"],
];
let missing = 0;
for (const [sql, name] of check) {
  const n = (db.prepare(sql).get() as { n: number }).n;
  missing += n;
  console.log(`lipsă în ${name}: ${n}`);
}
db.exec("DETACH DATABASE box");
process.exit(missing === 0 ? 0 : 1);
