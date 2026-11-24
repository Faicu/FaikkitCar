// Analiza citirilor din urmăriri cu valorile OBD din aceeași clipă (tabela obd_read, din 1.1.121, 24.11):
// pentru fiecare cod, valoarea decodată în mai multe feluri e potrivită liniar cu fiecare mărime de lângă
// citire. Spre deosebire de analyze.cjs (cel mai apropiat punct de traseu, la până la 15 s), aici perechea e
// exactă, deci merge și pentru mărimile rapide (aer, sarcină, avans, injecție).
// Folosire: runuser -u faikkitcar -- node web/scripts/obd-analyze/reads.cjs data/faikkitcar.db [tx] [min citiri] [R² minim]
const { DatabaseSync } = require("node:sqlite");
const db = new DatabaseSync(process.argv[2], { readOnly: true });
const txFilter = process.argv[3] || null;
const minN = Number(process.argv[4] || 10);
const minR2 = Number(process.argv[5] || 0.95);
const MAX_AGE_MS = 1500;
const REF = ["rpm", "map", "iat", "ect", "load", "speed", "pedal", "adv", "lambda", "inj", "stft"];

function decoders(hex) {
  const b = hex.match(/../g).map((x) => parseInt(x, 16));
  const out = {};
  for (let i = 0; i < Math.min(b.length, 8); i++) out[`u8@${i}`] = b[i];
  for (let i = 0; i + 1 < Math.min(b.length, 8); i++) {
    const u = (b[i] << 8) | b[i + 1];
    out[`u16@${i}`] = u;
    out[`s16@${i}`] = u >= 0x8000 ? u - 0x10000 : u;
  }
  return out;
}

function fit(xs, ys) {
  const n = xs.length;
  const mx = xs.reduce((a, b) => a + b, 0) / n, my = ys.reduce((a, b) => a + b, 0) / n;
  let sxx = 0, sxy = 0, syy = 0;
  for (let i = 0; i < n; i++) { sxx += (xs[i] - mx) ** 2; sxy += (xs[i] - mx) * (ys[i] - my); syy += (ys[i] - my) ** 2; }
  if (sxx === 0 || syy === 0) return null;
  const a = sxy / sxx;
  return { a, b: my - a * mx, r2: (sxy * sxy) / (sxx * syy) };
}

const codes = db.prepare(
  `SELECT tx, id, COUNT(*) AS n FROM obd_read WHERE value IS NOT NULL ${txFilter ? "AND tx = ?" : ""}
   GROUP BY tx, id HAVING n >= ? ORDER BY tx, id`,
).all(...(txFilter ? [txFilter, minN] : [minN]));
const readsOf = db.prepare(
  `SELECT * FROM obd_read WHERE tx = ? AND id = ? AND value IS NOT NULL AND (age_ms IS NULL OR age_ms <= ?) ORDER BY at`,
);
for (const { tx, id } of codes) {
  const rows = readsOf.all(tx, id, MAX_AGE_MS).filter((r) => r.value.length >= 2);
  if (rows.length < minN) continue;
  const decs = rows.map((r) => decoders(r.value));
  const keys = Object.keys(decs[0]).filter((k) => decs.every((d) => k in d));
  const best = [];
  for (const ref of REF) {
    const idx = rows.map((r, i) => (r[ref] === null || r[ref] === undefined ? -1 : i)).filter((i) => i >= 0);
    if (idx.length < minN) continue;
    const ys = idx.map((i) => rows[i][ref]);
    if (Math.max(...ys) === Math.min(...ys)) continue;
    let top = null;
    for (const k of keys) {
      const xs = idx.map((i) => decs[i][k]);
      if (new Set(xs).size < 3) continue;
      const f = fit(xs, ys);
      if (f && (!top || f.r2 > top.r2)) top = { ref, k, n: idx.length, ...f };
    }
    if (top && top.r2 >= minR2) best.push(top);
  }
  const values = new Set(rows.map((r) => r.value)).size;
  const label = `${tx} ${id.toString(16).toUpperCase().padStart(4, "0")} (${rows.length} citiri, ${values} valori)`;
  if (!best.length) {
    console.log(`${label}: nicio potrivire ≥ ${minR2}`);
    continue;
  }
  best.sort((a, b) => b.r2 - a.r2);
  console.log(`${label}: ` + best.slice(0, 3).map((f) =>
    `${f.ref} = ${f.a.toPrecision(4)} × ${f.k} + ${f.b.toPrecision(4)} (R² ${f.r2.toFixed(4)}, n ${f.n})`).join(" | "));
}
