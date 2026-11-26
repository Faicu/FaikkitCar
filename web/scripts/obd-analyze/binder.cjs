// Cine ține obiecte ale lui system_server (binder) pe măsură ce crește sysMb (1.1.132+).
// Folosire: runuser -u faikkitcar -- node web/scripts/obd-analyze/binder.cjs data/faikkitcar.db [de la ISO] [pas min]
const { DatabaseSync } = require("node:sqlite");
const db = new DatabaseSync(process.argv[2], { readOnly: true });
const from = process.argv[3] || new Date(Date.now() - 86400000).toISOString();
const stepMs = (Number(process.argv[4]) || 20) * 60000;
const rows = db.prepare("select at, data from unit_stat where at > ? order by at").all(from).map((r) => ({ t: Date.parse(r.at), d: JSON.parse(r.data) })).filter((r) => r.d.bd);
if (!rows.length) { console.log("fără date binder (aplicația 1.1.132+ trebuie să ruleze)"); process.exit(); }
const first = {};
for (const x of rows[0].d.bd.c) first[x.n] = x.c;
let last = 0;
for (const r of rows) {
  if (r.t - last < stepMs && r !== rows[rows.length - 1]) continue;
  last = r.t;
  const bd = r.d.bd;
  console.log(r.d.at.slice(5, 16), "sys", r.d.sysMb, "MB | noduri", bd.n, "ref", bd.r, "buf", bd.b, "|", bd.c.slice(0, 6).map((x) => `${x.n.replace(/^com\.(google\.android\.|android\.)?/, "")} ${x.c}`).join(", "));
}
const a = rows[0].d.bd.c, b = rows[rows.length - 1].d.bd.c;
console.log("\nSchimbare prima → ultima citire:");
const m = new Map(a.map((x) => [x.n, x.c]));
for (const x of b) console.log(" ", x.n.padEnd(45), m.get(x.n) ?? 0, "→", x.c, x.c - (m.get(x.n) ?? 0) >= 0 ? "+" + (x.c - (m.get(x.n) ?? 0)) : x.c - (m.get(x.n) ?? 0));
