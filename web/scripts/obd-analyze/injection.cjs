// Testul cantității injectate (25.11): pentru fiecare cod al urmăririi „timpul de injecție”, citirile din obd_read
// (cu valorile OBD din aceeași clipă) față de litrii pe oră calculați din aer (aceeași formulă ca în aplicație).
// Cerere: consum ≈ a × valoare × rpm + b, și 0 la injecția tăiată (inj = 4, turație peste 1000, pedala în repaus).
//   runuser -u faikkitcar -- node web/scripts/obd-analyze/injection.cjs data/faikkitcar.db
const { DatabaseSync } = require("node:sqlite");
const db = new DatabaseSync(process.argv[2], { readOnly: true });
const IDS = [0x2061, 0x2062, 0x209a, 0x20eb, 0x20ec, 0x204c];
const airLh = (r) => {
  const kgS = (r.map * 1000 * 0.001197 * (r.rpm / 120) * 0.85) / (287.05 * (r.iat + 273.15));
  const lam = r.lambda && r.lambda > 0.6 && r.lambda < 1.5 ? r.lambda : 1;
  return ((kgS * 1000) / (14.7 * lam)) * 3600 / 745;
};
function fit(x, y) {
  const n = x.length, mx = x.reduce((a, b) => a + b) / n, my = y.reduce((a, b) => a + b) / n;
  let sxy = 0, sxx = 0, syy = 0;
  for (let i = 0; i < n; i++) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) ** 2; syy += (y[i] - my) ** 2; }
  const a = sxy / sxx;
  return { n, a, b: my - a * mx, r2: sxx && syy ? (sxy * sxy) / (sxx * syy) : 0 };
}
const sel = db.prepare(`SELECT id, value, rpm, map, iat, lambda, inj, speed, age_ms, at FROM obd_read
  WHERE tx = '7E0' AND svc = '22' AND id = ? AND value IS NOT NULL AND rpm IS NOT NULL AND map IS NOT NULL AND iat IS NOT NULL
    AND (age_ms IS NULL OR age_ms < 1500)`);
for (const id of IDS) {
  const rows = sel.all(id);
  const hex = id.toString(16).toUpperCase();
  if (rows.length < 20) { console.log(hex, "citiri cu context:", rows.length, "(prea puține)"); continue; }
  const v = (r) => parseInt(r.value.slice(0, 4), 16);
  const run = rows.filter((r) => r.inj !== 4);
  const cut = rows.filter((r) => r.inj === 4 && r.rpm > 1000);
  const f1 = fit(run.map((r) => v(r) * r.rpm), run.map(airLh));
  const f2 = fit(run.map(v), run.map(airLh));
  console.log(hex, "n", rows.length, "| litri ~ valoare×rpm: R²", f1.r2.toFixed(3), "a", f1.a.toExponential(2), "b", f1.b.toFixed(2),
    "| litri ~ valoare: R²", f2.r2.toFixed(3), "| la injecția tăiată:", cut.length ? `${Math.min(...cut.map(v))}–${Math.max(...cut.map(v))} (n=${cut.length})` : "nicio citire");
}
