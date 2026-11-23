// Analiza codurilor motorului: pentru fiecare cod care se schimbă, valoarea decodată în mai multe feluri
// e potrivită liniar cu mărimile cunoscute (OBD / CAN) din punctele de traseu cele mai apropiate în timp.
const { DatabaseSync } = require("node:sqlite");
const db = new DatabaseSync(process.argv[2], { readOnly: true });
const tx = process.argv[3] || "7E0";
const minSamples = Number(process.argv[4] || 3);

const pts = db.prepare(`SELECT device_at, rpm, can_speed, volt, temp, fuel, map, iat, ect, obd_load, obd_lh
  FROM trip_point WHERE device_at >= '2026-11-20' ORDER BY device_at`).all()
  .map((p) => ({ ...p, t: Date.parse(p.device_at) }));
const REF = ["rpm", "can_speed", "volt", "temp", "fuel", "map", "iat", "ect", "obd_load", "obd_lh"];

function nearest(t, key) {
  let lo = 0, hi = pts.length - 1;
  while (lo < hi) { const m = (lo + hi) >> 1; if (pts[m].t < t) lo = m + 1; else hi = m; }
  let best = null;
  for (const i of [lo - 2, lo - 1, lo, lo + 1, lo + 2]) {
    const p = pts[i];
    if (!p || p[key] === null || p[key] === undefined) continue;
    const dt = Math.abs(p.t - t);
    if (dt <= 15000 && (!best || dt < best.dt)) best = { v: p[key], dt };
  }
  return best ? best.v : null;
}

function decoders(hex) {
  const b = hex.match(/../g).map((x) => parseInt(x, 16));
  const out = {};
  for (let i = 0; i < Math.min(b.length, 8); i++) out[`u8@${i}`] = b[i];
  for (let i = 0; i + 1 < Math.min(b.length, 8); i += 1) {
    const u = (b[i] << 8) | b[i + 1];
    out[`u16@${i}`] = u;
    out[`s16@${i}`] = u >= 0x8000 ? u - 0x10000 : u;
  }
  if (b.length >= 4) out["u32@0"] = ((b[0] << 24) >>> 0) + (b[1] << 16) + (b[2] << 8) + b[3];
  return out;
}

function fit(xs, ys) {
  const n = xs.length;
  const mx = xs.reduce((a, b) => a + b, 0) / n, my = ys.reduce((a, b) => a + b, 0) / n;
  let sxx = 0, sxy = 0, syy = 0;
  for (let i = 0; i < n; i++) { sxx += (xs[i] - mx) ** 2; sxy += (xs[i] - mx) * (ys[i] - my); syy += (ys[i] - my) ** 2; }
  if (sxx === 0 || syy === 0) return null;
  const a = sxy / sxx, b = my - a * mx;
  const r2 = (sxy * sxy) / (sxx * syy);
  return { a, b, r2 };
}

const NICE = [1, 0.1, 0.01, 0.001, 0.25, 0.5, 0.75, 2, 4, 10, 0.125, 1 / 128, 1 / 256, 1 / 1024, 0.02, 0.05, 0.2, 0.4];
const nice = (a) => NICE.find((k) => Math.abs(Math.abs(a) / k - 1) < 0.04);

const codes = db.prepare(`SELECT id FROM obd_code WHERE tx = ? AND svc = '22' AND changes > 0`).all(tx);
const samplesOf = db.prepare(`SELECT at, value FROM obd_sample WHERE tx = ? AND svc = '22' AND id = ? ORDER BY at`);
const results = [];
for (const { id } of codes) {
  const s = samplesOf.all(tx, id).filter((x) => x.value && x.value.length >= 2);
  if (s.length < minSamples) continue;
  const decs = s.map((x) => decoders(x.value));
  const keys = Object.keys(decs[0]).filter((k) => decs.every((dd) => k in dd));
  for (const ref of REF) {
    const ys = s.map((x) => nearest(Date.parse(x.at), ref));
    const idx = ys.map((y, i) => (y === null ? -1 : i)).filter((i) => i >= 0);
    if (idx.length < minSamples) continue;
    const yv = idx.map((i) => ys[i]);
    const range = Math.max(...yv) - Math.min(...yv);
    if (range === 0) continue;
    for (const k of keys) {
      const xv = idx.map((i) => decs[i][k]);
      if (new Set(xv).size < Math.min(3, idx.length)) continue;
      const f = fit(xv, yv);
      if (!f || f.r2 < 0.97) continue;
      results.push({ id: id.toString(16).toUpperCase().padStart(4, "0"), ref, k, n: idx.length, r2: +f.r2.toFixed(4),
        a: +f.a.toPrecision(4), b: +f.b.toPrecision(4), nice: nice(f.a) ?? null, range: +range.toPrecision(3) });
    }
  }
}
results.sort((x, y) => y.n - x.n || y.r2 - x.r2);
for (const r of results) console.log(JSON.stringify(r));
