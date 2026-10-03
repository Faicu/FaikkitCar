// ---------------------------------------------------------------------------
// Calculele unei călătorii, din punctele ei (fără bază de date, ca să poată fi testate).
// Totul pornește de la segmentele dintre două puncte consecutive:
//   - gol > POINT_GAP_MS: motorul a fost oprit (pe loc punctele vin la 30 s, în mers la 5 s);
//   - ambele capete pe loc (< 1 km/h): mașina stă, distanța e 0 (fără zgomotul GPS);
//   - altfel mers: distanța din GPS între două poziții bune, iar unde lipsește GPS-ul (tunel,
//     fix întârziat) din viteză (GPS sau CAN × scala CAN, vitezometrul arată ~6% în plus).
// Kilometrajul are rezoluție de 1 km, deci rămâne doar informativ.
// ---------------------------------------------------------------------------

import { estimateFuel } from "./fuel-model.ts";

export interface PointRow {
  device_at: string;
  lat: number | null;
  lon: number | null;
  acc: number | null;
  gps_speed: number | null;
  can_speed: number | null;
  rpm: number | null;
  volt: number | null;
  temp: number | null;
  odo: number | null;
  fuel: number | null;
  cons: number | null; // probabil consumul instantaneu al bordului (c1033), L/100 km ×10
  crank: number | null; // tensiunea minimă la pornire, măsurată în mașină (1.1.39+)
  ac: number | null; // AC pornit 1/0 (1.1.40+)
  belt: number | null; // centura șoferului pusă 1/0 (1.1.40+)
  // Semnale de parcare (1.1.41+): frâna de mână trasă, o ușă deschisă, marșarierul, 1/0.
  hb: number | null;
  door: number | null;
  rev: number | null;
}

/** O oprire cu motorul oprit în mijlocul călătoriei (ex. magazin, sau între părți combinate). */
export interface TripStop {
  from: string; // ultimul punct înainte de oprire
  to: string; // primul punct după
  minutes: number;
  pos: [number, number] | null;
  place: string | null; // locul salvat, dacă oprirea e într-unul (completat în trips.ts)
}

export interface TripMetrics {
  start: string;
  end: string;
  points: number;
  distanceKm: number;
  durationMin: number; // de la plecare la sosire, cu tot cu opririle
  stopMin: number; // opririle cu motorul oprit
  stops: TripStop[];
  movingMin: number; // doar în mișcare
  trafficMin: number; // oprit cu motorul pornit între două porțiuni de mers (≤ 5 min)
  trafficStops: number; // cele de cel puțin 5 s
  trafficMaxMin: number;
  standMin: number; // oprit cu motorul pornit la plecare / sosire, cu semne de parcare sau > 5 min
  maxSpeed: number | null;
  avgSpeed: number | null; // km/h, pe durata fără opririle cu motorul oprit
  movingAvgSpeed: number | null; // km/h, doar pe timpul în mișcare
  maxRpm: number | null;
  odoStart: number | null;
  odoEnd: number | null;
  fuelStart: number | null; // litri în rezervor (CAN), la plecare și la sosire
  fuelEnd: number | null;
  tempC: number | null; // la sosire
  tempStartC: number | null; // la plecare
  // Clima și centura (de la 1.1.40; null la drumurile mai vechi): minutele cu AC pornit (motorul
  // pornit) și cele în mers fără centura șoferului.
  acMin: number | null;
  noBeltMin: number | null;
  // Bateria: căderea de la demaror (sub ~9,6 V = baterie slabă), din măsurarea mașinii sau, la
  // drumurile vechi, din primele 30 s după pornire dacă s-a prins o cădere (< 12 V); și mediana
  // cu motorul pornit de cel puțin un minut (încărcarea, 13,8–14,7 V).
  crankVolt: number | null;
  runVolt: number | null;
  startPos: [number, number] | null;
  endPos: [number, number] | null;
  modelLiters: number; // estimarea brută, necalibrată (fuel-model.ts)
  boardLPer100: number | null; // consumul bordului (c1033) în mers, dacă acoperă ≥ 80% din km
}

/** Peste atâtea ms fără puncte, motorul a fost oprit. */
export const POINT_GAP_MS = 60_000;
/** O oprire cu motorul pornit la mijlocul drumului, mai lungă de atât, nu mai e trafic. */
export const TRAFFIC_MAX_MS = 5 * 60_000;
/** Marșarierul cu atât înainte de oprire = manevră de parcare. */
const REVERSE_BEFORE_MS = 60_000;
/** Pe loc: sub atât (km/h). */
const STILL_KMH = 1;
/** GPS-ul între două puncte se folosește doar dacă sunt la cel mult atâtea ms. */
const GPS_SEGMENT_MS = 15_000;
/** Un fix GPS mai imprecis de atât (m) nu e folosit. */
const MAX_ACC_M = 60;
/** Sub atât (V) în primele 30 s după pornire, punctul a prins căderea de la demaror. */
const CRANK_SEEN_V = 12;
/** Scala CAN implicită (distanța GPS / viteza CAN integrată), cât nu avem destule date. */
export const DEFAULT_CAN_SCALE = 0.95;

export function pos(r: PointRow): [number, number] | null {
  if (r.lat === null || r.lon === null) return null;
  if (r.acc !== null && r.acc > MAX_ACC_M) return null;
  return [r.lat, r.lon];
}

export function haversineKm(a: [number, number], b: [number, number]): number {
  const rad = Math.PI / 180;
  const dLat = (b[0] - a[0]) * rad;
  const dLon = (b[1] - a[1]) * rad;
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * rad) * Math.cos(b[0] * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.sqrt(h));
}

/** Viteza unui punct: a mașinii (CAN), altfel GPS. */
export function speedOf(r: PointRow): number | null {
  return r.can_speed ?? r.gps_speed;
}

function medianOf(xs: number[]): number {
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

function round(n: number, digits = 1): number {
  const f = 10 ** digits;
  return Math.round(n * f) / f;
}

/**
 * Scala CAN din istoric: distanța GPS / viteza CAN integrată, pe segmentele în mers care au
 * ambele. Pe 30.09–02.10: 16,0 km GPS (= kilometrajul) față de 16,9 km din CAN.
 */
export function canScale(rows: PointRow[]): number {
  let gps = 0;
  let can = 0;
  for (let i = 1; i < rows.length; i++) {
    const a = rows[i - 1];
    const b = rows[i];
    const dt = Date.parse(b.device_at) - Date.parse(a.device_at);
    if (!(dt > 0) || dt > GPS_SEGMENT_MS) continue;
    const pa = pos(a);
    const pb = pos(b);
    if (!pa || !pb || a.can_speed === null || b.can_speed === null) continue;
    if (a.can_speed < STILL_KMH && b.can_speed < STILL_KMH) continue;
    const d = haversineKm(pa, pb);
    const c = (((a.can_speed + b.can_speed) / 2) * dt) / 3_600_000;
    if (d > c * 2 + 0.05) continue; // salt GPS
    gps += d;
    can += c;
  }
  if (can < 5) return DEFAULT_CAN_SCALE; // sub 5 km, prea puțin ca să ne bazăm pe raport
  return Math.min(1.05, Math.max(0.85, gps / can));
}

/** Toate valorile unei călătorii; `rows` crescător după timp, cel puțin un punct. */
/**
 * Toate valorile unei călătorii; `rows` crescător după timp, cel puțin un punct. `isPlace`
 * spune dacă o poziție e într-un loc salvat (o oprire acolo e staționare, nu trafic).
 */
export function analyze(
  rows: PointRow[],
  scale = DEFAULT_CAN_SCALE,
  isPlace: (pos: [number, number]) => boolean = () => false,
): TripMetrics {
  const t = rows.map((r) => Date.parse(r.device_at));
  let km = 0;
  let movingMs = 0;
  let stopMs = 0;
  const stops: TripStop[] = [];
  let lastPos: [number, number] | null = null;
  let acMs = 0;
  let noBeltMs = 0;
  let climateKnown = false;
  let beltKnown = false;
  let boardL = 0;
  let boardKm = 0;
  let movingKm = 0;

  // Opririle cu motorul pornit (episoade de puncte pe loc), clasificate la sfârșit.
  // `parked`: semne de parcare în oprire (frâna de mână, o ușă, marșarierul, un loc salvat).
  type Ep = { from: number; to: number; movedBefore: boolean; movedAfter: boolean; parked: boolean };
  const eps: Ep[] = [];
  let ep: Ep | null = null;
  let moved = false;
  let lastReverseAt = -Infinity;

  for (let i = 0; i < rows.length; i++) {
    const r = rows[i];
    const p = pos(r);
    const v = speedOf(r) ?? 0;
    if (i > 0) {
      const a = rows[i - 1];
      const dt = t[i] - t[i - 1];
      const va = speedOf(a) ?? 0;
      const still = va < STILL_KMH && v < STILL_KMH;
      // Starea de la începutul segmentului ține tot segmentul (punctele vin la 5–30 s).
      if (dt <= POINT_GAP_MS) {
        if (a.ac !== null) climateKnown = true;
        if (a.ac === 1 && (a.rpm ?? 0) > 0) acMs += dt;
        if (a.belt !== null) beltKnown = true;
        if (a.belt === 0 && va >= STILL_KMH) noBeltMs += dt;
      }
      if (dt > POINT_GAP_MS && still) {
        // Motorul oprit între puncte: o oprire, iar episodul pe loc de dinainte se încheie.
        stopMs += dt;
        stops.push({
          from: a.device_at,
          to: r.device_at,
          minutes: round(dt / 60_000),
          pos: lastPos ?? p, // unde a parcat: ultima poziție bună dinainte
          place: null,
        });
        if (ep) {
          eps.push(ep);
          ep = null;
        }
        moved = false;
      } else if (!still) {
        // Mers (sau date lipsă în mers, ex. aplicația repornită): distanța pe segment.
        const pa = pos(a);
        const bySpeed = (sa: number | null, sb: number | null, k: number) =>
          sa !== null && sb !== null ? (((sa + sb) / 2) * k * dt) / 3_600_000 : null;
        const speedKm =
          bySpeed(a.gps_speed, r.gps_speed, 1) ?? bySpeed(a.can_speed, r.can_speed, scale) ?? 0;
        let d = speedKm;
        if (pa && p && dt <= GPS_SEGMENT_MS) {
          const g = haversineKm(pa, p);
          if (g <= speedKm * 2 + 0.05) d = g; // altfel e un salt GPS
        } else if (pa && p && dt > GPS_SEGMENT_MS) {
          // Gol în mers: linia dreaptă e minimul; viteza estimează mai bine curbele.
          d = Math.max(haversineKm(pa, p), speedKm);
        }
        km += d;
        // Timpul se împarte după punctul de la început: dacă mașina stătea (plecare de pe loc),
        // segmentul e deja în oprirea care ține până la primul punct în mers.
        if (va >= STILL_KMH) movingMs += dt;
        movingKm += d;
        if (a.cons !== null) {
          boardL += (a.cons / 1000) * d; // (cons / 10) L/100 km × d km / 100
          boardKm += d;
        }
      }
    }
    // Episoadele pe loc cu motorul pornit (de la primul punct pe loc la primul în mers).
    const engine = (r.rpm ?? 0) > 0;
    if (r.rev === 1) lastReverseAt = t[i];
    if (v < STILL_KMH && engine) {
      if (!ep) {
        ep = {
          from: t[i],
          to: t[i],
          movedBefore: moved,
          movedAfter: false,
          parked: t[i] - lastReverseAt <= REVERSE_BEFORE_MS,
        };
      }
      ep.to = t[i];
      if (r.hb === 1 || r.door === 1 || r.rev === 1 || (p !== null && isPlace(p))) ep.parked = true;
    } else if (v >= STILL_KMH) {
      if (ep) {
        ep.to = t[i];
        ep.movedAfter = true;
        eps.push(ep);
        ep = null;
      }
      moved = true;
    }
    lastPos = p ?? lastPos;
  }
  if (ep) eps.push(ep);

  let trafficMs = 0;
  let standMs = 0;
  let trafficMaxMs = 0;
  let trafficStops = 0;
  for (const e of eps) {
    const ms = e.to - e.from;
    if (e.movedBefore && e.movedAfter && ms <= TRAFFIC_MAX_MS && !e.parked) {
      trafficMs += ms;
      trafficMaxMs = Math.max(trafficMaxMs, ms);
      if (ms >= 5_000) trafficStops++;
    } else {
      standMs += ms;
    }
  }

  let maxSpeed: number | null = null;
  let maxRpm: number | null = null;
  let crankVolt: number | null = null;
  const runVolts: number[] = [];
  let engineSince: number | null = null; // pornirea motorului (prima citire cu turație)
  let startPos: [number, number] | null = null;
  let endPos: [number, number] | null = null;
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i];
    const v = speedOf(r);
    if (v !== null) maxSpeed = Math.max(maxSpeed ?? 0, v);
    if (r.rpm !== null) maxRpm = Math.max(maxRpm ?? 0, r.rpm);
    // O pauză fără puncte (motor oprit) sau turația 0 = următoarea citire cu turație e o pornire.
    if (i > 0 && t[i] - t[i - 1] > POINT_GAP_MS) engineSince = null;
    if ((r.rpm ?? 0) <= 0) engineSince = null;
    else engineSince ??= t[i];
    if (r.volt !== null && r.volt > 5 && engineSince !== null) {
      const sinceStart = t[i] - engineSince;
      if (sinceStart <= 30_000) crankVolt = Math.min(crankVolt ?? 99, r.volt);
      else if (sinceStart >= 60_000) runVolts.push(r.volt);
    }
    const p = pos(r);
    if (p) {
      startPos ??= p;
      endPos = p;
    }
  }
  const odos = rows.map((r) => r.odo).filter((o): o is number => o !== null && o > 0);
  const tank = rows.map((r) => r.fuel).filter((f): f is number => f !== null && f > 0);
  const temps = rows.map((r) => r.temp).filter((x): x is number => x !== null);
  const durationMs = t[t.length - 1] - t[0];
  const drivingMs = durationMs - stopMs; // fără opririle cu motorul oprit
  const fuel = estimateFuel(
    rows.map((r, i) => ({ t: t[i], speed: speedOf(r), rpm: r.rpm, ac: r.ac === 1 })),
  );
  // Măsurarea din mașină are prioritate; altfel doar o cădere reală prinsă într-un punct.
  const measured = rows.map((r) => r.crank).filter((c): c is number => c !== null && c > 5);
  if (measured.length) crankVolt = Math.min(...measured);
  else if (crankVolt !== null && crankVolt >= CRANK_SEEN_V) crankVolt = null;
  return {
    start: rows[0].device_at,
    end: rows[rows.length - 1].device_at,
    points: rows.length,
    distanceKm: round(km),
    durationMin: round(durationMs / 60_000),
    stopMin: round(stopMs / 60_000),
    stops,
    movingMin: round(movingMs / 60_000),
    trafficMin: round(trafficMs / 60_000),
    trafficStops,
    trafficMaxMin: round(trafficMaxMs / 60_000),
    standMin: round(standMs / 60_000),
    maxSpeed: maxSpeed === null ? null : Math.round(maxSpeed),
    avgSpeed: drivingMs > 30_000 ? Math.round((km / drivingMs) * 3_600_000) : null,
    movingAvgSpeed: movingMs > 30_000 ? Math.round((km / movingMs) * 3_600_000) : null,
    maxRpm,
    odoStart: odos.length ? odos[0] : null,
    odoEnd: odos.length ? odos[odos.length - 1] : null,
    fuelStart: tank.length ? tank[0] : null,
    fuelEnd: tank.length ? tank[tank.length - 1] : null,
    tempC: temps.length ? temps[temps.length - 1] : null,
    tempStartC: temps.length ? temps[0] : null,
    acMin: climateKnown ? round(acMs / 60_000) : null,
    noBeltMin: beltKnown ? round(noBeltMs / 60_000) : null,
    crankVolt: crankVolt === null ? null : round(crankVolt, 2),
    runVolt: runVolts.length ? round(medianOf(runVolts), 2) : null,
    startPos,
    endPos,
    modelLiters: fuel.liters,
    boardLPer100:
      movingKm >= 0.5 && boardKm >= movingKm * 0.8 ? round((boardL / boardKm) * 100) : null,
  };
}
