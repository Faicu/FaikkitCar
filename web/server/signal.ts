// ---------------------------------------------------------------------------
// Semnalul mobil al navigației (cartela Digi din Teyes), din punctele de traseu (1.1.64+,
// CellSignal.java): unde cade internetul, pe ce bandă și cu ce putere. Banda se află din
// canal (EARFCN), fiindcă Android 10 nu o dă direct. Harta: punctele adunate în pătrate de
// ~200 m, cu mediana semnalului și cât din timp a fost fără internet.
// Server-only (node:sqlite) doar `readSignal`; restul e pur (signal.test.ts).
// ---------------------------------------------------------------------------

import { getDb } from "./db.ts";
import { haversineKm } from "./trip-math.ts";

export interface SignalRow {
  t: number; // epoch ms
  lat: number | null;
  lon: number | null;
  net: number | null; // 0 fără internet, 1 date mobile, 2 Wi-Fi
  rat: string | null;
  ch: number | null;
  sig: number | null;
  op: string | null;
}

export type SignalLevel = "good" | "fair" | "weak" | "none";

export interface SignalCell {
  lat: number;
  lon: number;
  n: number; // câte puncte
  sig: number | null; // mediana, dBm
  offline: number; // 0–1, partea fără internet
  level: SignalLevel; // cel mai rău dintre semnal și internet
  band: string | null; // banda cea mai des văzută („B20 800”)
}

export interface SignalZone {
  at: string; // începutul
  lat: number;
  lon: number;
  minutes: number;
  km: number;
  band: string | null; // ultima bandă dinainte de cădere
}

export interface SignalSummary {
  since: string;
  points: number;
  offline: number; // 0–1
  levels: Record<SignalLevel, number>; // 0–1
  bands: Array<{ band: string; share: number; sig: number | null }>;
  ops: Array<{ op: string; share: number }>;
  cells: SignalCell[];
  zones: SignalZone[]; // cele mai recente căderi de internet în mers, cel mult 20
}

// Benzile LTE după canalul de coborâre (3GPP 36.101); cele folosite în România primele.
const LTE_BANDS: Array<[number, number, string]> = [
  [0, 599, "B1 2100"],
  [600, 1199, "B2 1900"],
  [1200, 1949, "B3 1800"],
  [2400, 2649, "B5 850"],
  [2750, 3449, "B7 2600"],
  [3450, 3799, "B8 900"],
  [6150, 6449, "B20 800"],
  [9210, 9659, "B28 700"],
  [9920, 10359, "B32 1500"],
  [37750, 38249, "B38 2600 TDD"],
  [38650, 39649, "B40 2300 TDD"],
  [39650, 41589, "B41 2500 TDD"],
  [41590, 43589, "B42 3500 TDD"],
];

// Benzile 5G (NR-ARFCN, 3GPP 38.104), doar cele din România.
const NR_BANDS: Array<[number, number, string]> = [
  [151600, 160600, "n28 700"],
  [361000, 376000, "n3 1800"],
  [422000, 434000, "n1 2100"],
  [524000, 538000, "n7 2600"],
  [620000, 653333, "n78 3500"],
];

export function bandOf(rat: string | null, ch: number | null): string | null {
  if (ch === null) return rat === "3g" ? "3G" : rat === "2g" ? "2G" : null;
  const table = rat === "lte" ? LTE_BANDS : rat === "nr" ? NR_BANDS : null;
  if (!table) return rat === "3g" ? "3G" : rat === "2g" ? "2G" : null;
  const b = table.find(([lo, hi]) => ch >= lo && ch <= hi);
  return b ? b[2] : `${rat === "nr" ? "NR" : "LTE"} ${ch}`;
}

const OPERATORS: Record<string, string> = {
  "22601": "Vodafone",
  "22603": "Telekom",
  "22605": "Digi",
  "22606": "Telekom",
  "22610": "Orange",
};

export function operatorName(op: string | null): string | null {
  return op === null ? null : (OPERATORS[op] ?? op);
}

/** Puterea pe scurt: la 4G/5G e RSRP (bun ≥ −95, slab < −110), la 3G e RSSI; 2G = fără internet. */
export function signalLevel(rat: string | null, sig: number | null): SignalLevel {
  // Pe 2G (GPRS, „G” pe navigație) nu se încarcă nimic, chiar dacă Android zice că are internet.
  if (rat === "-" || rat === "2g") return "none";
  if (sig === null) return rat === null ? "none" : "fair";
  const [good, weak] = rat === "lte" || rat === "nr" ? [-95, -110] : [-85, -100];
  return sig >= good ? "good" : sig >= weak ? "fair" : "weak";
}

/** Fără internet folosibil: Android zice că nu are, sau e doar pe 2G. */
export function isOffline(r: Pick<SignalRow, "net" | "rat">): boolean {
  return r.net === 0 || r.rat === "2g";
}

const RANK: Record<SignalLevel, number> = { good: 0, fair: 1, weak: 2, none: 3 };
// Pătratele hărții: ~200 m pe latitudine (la 44° N, ~160 m pe longitudine).
const CELL_DEG = 0.002;
// Între două puncte mai departe de atât, o cădere de internet se întrerupe (somn, oprire).
const ZONE_GAP_MS = 60_000;
// O cădere mai scurtă e zgomot (o trecere între celule).
const ZONE_MIN_MS = 15_000;
// Un salt mai mare între două puncte e o eroare GPS, nu drum.
const JUMP_KM = 2;

function median(xs: number[]): number | null {
  if (xs.length === 0) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : Math.round((s[m - 1] + s[m]) / 2);
}

function mostCommon(xs: Array<string | null>): string | null {
  const count = new Map<string, number>();
  for (const x of xs) if (x !== null) count.set(x, (count.get(x) ?? 0) + 1);
  let best: string | null = null;
  for (const [k, n] of count) if (best === null || n > count.get(best)!) best = k;
  return best;
}

/** Rezumatul din punctele cu semnal (ordonate după timp). Pur. */
export function summarizeSignal(rows: SignalRow[], since: string): SignalSummary {
  const n = rows.length;
  const levels: Record<SignalLevel, number> = { good: 0, fair: 0, weak: 0, none: 0 };
  const bandSig = new Map<string, number[]>();
  const bandCount = new Map<string, number>();
  const opCount = new Map<string, number>();
  const grid = new Map<string, SignalRow[]>();
  let offline = 0;
  for (const r of rows) {
    levels[signalLevel(r.rat, r.sig)]++;
    if (isOffline(r)) offline++;
    const band = bandOf(r.rat, r.ch);
    if (band) {
      bandCount.set(band, (bandCount.get(band) ?? 0) + 1);
      if (r.sig !== null) {
        const sigs = bandSig.get(band);
        if (sigs) sigs.push(r.sig);
        else bandSig.set(band, [r.sig]);
      }
    }
    const op = operatorName(r.op);
    if (op) opCount.set(op, (opCount.get(op) ?? 0) + 1);
    if (r.lat !== null && r.lon !== null) {
      const key = `${Math.floor(r.lat / CELL_DEG)}:${Math.floor(r.lon / CELL_DEG)}`;
      const list = grid.get(key);
      if (list) list.push(r);
      else grid.set(key, [r]);
    }
  }
  const share = (x: number) => (n > 0 ? x / n : 0);

  const cells: SignalCell[] = [...grid.values()].map((list) => {
    const sig = median(list.map((r) => r.sig).filter((s): s is number => s !== null));
    const off = list.filter(isOffline).length / list.length;
    const rat = mostCommon(list.map((r) => r.rat));
    let level = signalLevel(rat, sig);
    if (off >= 0.5) level = "none";
    else if (off > 0 && RANK[level] < RANK.weak) level = "weak";
    return {
      lat: list.reduce((a, r) => a + r.lat!, 0) / list.length,
      lon: list.reduce((a, r) => a + r.lon!, 0) / list.length,
      n: list.length,
      sig,
      offline: off,
      level,
      band: mostCommon(list.map((r) => bandOf(r.rat, r.ch))),
    };
  });

  // Căderile de internet: puncte consecutive fără internet, cu cel mult ZONE_GAP_MS între ele.
  const zones: SignalZone[] = [];
  let start = -1;
  const close = (end: number) => {
    if (start < 0) return;
    const first = rows[start];
    const last = rows[end];
    let km = 0;
    let at: [number, number] | null = null;
    let prev: [number, number] | null = null;
    for (let i = start; i <= end; i++) {
      const r = rows[i];
      if (r.lat === null || r.lon === null) continue;
      const here: [number, number] = [r.lat, r.lon];
      if (prev) {
        const d = haversineKm(prev, here);
        if (d <= JUMP_KM) km += d;
      } else at = here;
      prev = here;
    }
    const before = start > 0 ? rows[start - 1] : null;
    if (last.t - first.t >= ZONE_MIN_MS && at) {
      zones.push({
        at: new Date(first.t).toISOString(),
        lat: at[0],
        lon: at[1],
        minutes: (last.t - first.t) / 60_000,
        km: Math.round(km * 10) / 10,
        band: before ? bandOf(before.rat, before.ch) : null,
      });
    }
    start = -1;
  };
  for (let i = 0; i < n; i++) {
    const r = rows[i];
    if (start >= 0 && r.t - rows[i - 1].t > ZONE_GAP_MS) close(i - 1);
    if (isOffline(r)) {
      if (start < 0) start = i;
    } else close(i - 1);
  }
  close(n - 1);

  return {
    since,
    points: n,
    offline: share(offline),
    levels: {
      good: share(levels.good),
      fair: share(levels.fair),
      weak: share(levels.weak),
      none: share(levels.none),
    },
    bands: [...bandCount]
      .sort((a, b) => b[1] - a[1])
      .map(([band, c]) => ({ band, share: share(c), sig: median(bandSig.get(band) ?? []) })),
    ops: [...opCount].sort((a, b) => b[1] - a[1]).map(([op, c]) => ({ op, share: share(c) })),
    cells,
    zones: zones.reverse().slice(0, 20),
  };
}

/** Semnalul pe ultimele `days` zile, doar din punctele care îl au (1.1.64+). */
export function readSignal(days: number): SignalSummary {
  const since = new Date(Date.now() - days * 86_400_000).toISOString();
  const rows = getDb()
    .prepare(
      `SELECT device_at, lat, lon, net, rat, ch, sig, op FROM trip_point
       WHERE device_at >= ? AND net IS NOT NULL ORDER BY device_at`,
    )
    .all(since) as Array<Omit<SignalRow, "t"> & { device_at: string }>;
  return summarizeSignal(
    rows.map(({ device_at, ...r }) => ({ ...r, t: Date.parse(device_at) })),
    since,
  );
}
