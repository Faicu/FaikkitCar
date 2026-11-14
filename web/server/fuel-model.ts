// ---------------------------------------------------------------------------
// Estimarea combustibilului pe călătorie (FaikkitCar), fără senzor de consum:
// din viteză și turație, cu un model fizic simplu (linia Willans):
//   litri = lucru mecanic la roți / (energia unui litru × randament)
//         + rotații ale motorului × litri pe rotație (frecări, mers în gol)
//         + compresorul AC (după temperatura de afară) + pornirea la rece.
// Modelul dă o valoare brută; factorul de calibrare al fiecărui interval dintre
// alimentări o aduce la litrii reali. Fără dependențe de server, ca să poată fi testat.
// ---------------------------------------------------------------------------

/** Golf 6 1.2 TSI (CBZB), cu șofer. Valorile absolute contează puțin: calibrarea le corectează. */
export const FUEL_MODEL = {
  massKg: 1350,
  cdA: 0.69, // Cd 0,31 × 2,22 m²
  airDensity: 1.2,
  rollingCoef: 0.012,
  // Energia unui litru de benzină (~32 MJ) × randamentul motor + transmisie (motorul cald).
  joulesPerLiter: 32e6 * 0.31,
  // ~0,52 L/h la relanti cu motorul cald (750 rpm = 45.000 rotații/h).
  litersPerRev: 1.16e-5,
  // Compresorul AC: ~0,4 L/h la 25 °C. Climatronicul ține „AC” aprins tot anul, dar sub ~5 °C
  // compresorul nu mai lucrează, iar pe caniculă trage mai mult (0,6 L/h la 35 °C).
  acLitersPerHour: 0.4,
  acOffC: 5,
  acFullC: 25,
  acMaxLoad: 1.5,
  // Pornirea la rece (ulei gros, amestec bogat, încălzirea catalizatorului): litri în plus la
  // o pornire complet rece la 20 °C, plus atât pe fiecare grad sub 20 °C.
  coldStartLiters: 0.08,
  coldLitersPerDegree: 0.005,
  coldMinLiters: 0.04,
  // Motorul se încălzește în ~6 min de mers (constantă de timp) și se răcește în ~2,5 h oprit.
  warmUpS: 360,
  coolDownS: 2.5 * 3600,
};

/** Peste atâtea secunde între două puncte nu știm ce s-a întâmplat (motor oprit, fără date). */
const MAX_GAP_S = 60;

export interface FuelSample {
  t: number; // epoch ms
  speed: number | null; // km/h
  rpm: number | null;
  ac?: boolean; // AC pornit
  temp?: number | null; // temperatura de afară, °C
}

export interface FuelEstimate {
  liters: number; // brut, necalibrat
  idleMin: number; // pe loc cu motorul pornit
  idleLiters: number; // din `liters`, arși pe loc (relanti + AC + încălzirea motorului)
  coldEnd: number; // cât de rece e motorul la sfârșit (0 = cald, 1 = rece de tot)
}

/** Sarcina compresorului AC după temperatura de afară (1 = cea de la 25 °C; necunoscută = 1). */
export function acLoad(temp: number | null | undefined): number {
  const m = FUEL_MODEL;
  if (temp == null) return 1;
  return Math.min(m.acMaxLoad, Math.max(0, (temp - m.acOffC) / (m.acFullC - m.acOffC)));
}

/** Litrii în plus ai unei porniri complet reci, după temperatura de afară. */
export function coldStartLiters(temp: number | null | undefined): number {
  const m = FUEL_MODEL;
  return Math.max(m.coldMinLiters, m.coldStartLiters + m.coldLitersPerDegree * (20 - (temp ?? 20)));
}

/** Motorul oprit `seconds`: cât de rece ajunge, pornind de la `cold` (0 = cald, 1 = rece). */
export function coolDown(cold: number, seconds: number): number {
  if (!(seconds > 0)) return cold;
  return 1 - (1 - cold) * Math.exp(-seconds / FUEL_MODEL.coolDownS);
}

/** `cold` = cât de rece e motorul la primul punct (0 = cald; trips.ts îl duce de la un drum la altul). */
export function estimateFuel(samples: FuelSample[], cold = 0): FuelEstimate {
  const m = FUEL_MODEL;
  let work = 0;
  let revs = 0;
  let idleS = 0;
  let idleLiters = 0;
  let acLiters = 0;
  let coldLiters = 0;
  // Temperatura vine uneori abia după câteva puncte: până atunci, prima cunoscută.
  let temp: number | null = samples.find((s) => s.temp != null)?.temp ?? null;
  for (let i = 1; i < samples.length; i++) {
    const a = samples[i - 1];
    const b = samples[i];
    const dt = (b.t - a.t) / 1000;
    if (!(dt > 0)) continue;
    if (a.temp != null) temp = a.temp;
    const rpm = a.rpm ?? b.rpm;
    if (dt > MAX_GAP_S || rpm === null || rpm <= 0) {
      cold = coolDown(cold, dt); // motorul oprit: nu consumă, se răcește
      continue;
    }
    const revL = (rpm / 60) * dt * m.litersPerRev;
    const acL = a.ac ? ((acLoad(temp) * m.acLitersPerHour) / 3600) * dt : 0;
    const warmed = cold * (1 - Math.exp(-dt / m.warmUpS));
    const coldL = coldStartLiters(temp) * warmed;
    cold -= warmed;
    revs += (rpm / 60) * dt;
    acLiters += acL;
    coldLiters += coldL;
    const v1 = (a.speed ?? 0) / 3.6;
    const v2 = (b.speed ?? 0) / 3.6;
    const v = (v1 + v2) / 2;
    if (v < 0.3) {
      idleS += dt;
      idleLiters += revL + acL + coldL;
      continue;
    }
    // Puterea la roți: accelerare + aer + rulare; la frânare/rulare liberă nu se consumă lucru.
    const power =
      (m.massKg * (v2 - v1) * v) / dt +
      0.5 * m.airDensity * m.cdA * v ** 3 +
      m.rollingCoef * m.massKg * 9.81 * v;
    if (power > 0) work += power * dt;
  }
  return {
    liters: work / m.joulesPerLiter + revs * m.litersPerRev + acLiters + coldLiters,
    idleMin: idleS / 60,
    idleLiters,
    coldEnd: cold,
  };
}

export interface FuelRefuel {
  id: number;
  at: string; // ISO
  odo: number | null;
  liters: number;
  price: number | null; // lei / litru
  full: boolean;
}

/** Un interval între două alimentări, cu factorul lui (litri reali / estimarea brută). */
export interface FuelInterval {
  from: string; // ultima citire de dinaintea alimentării de la care pornește
  to: string; // la fel, pentru alimentarea care îl încheie
  fromId: number | null; // alimentările din jurnal de la capete (null = văzută doar în rezervor)
  toId: number | null;
  liters: number; // consumați: nivelul de dinainte + alimentat − nivelul de dinaintea următoarei
  modelLiters: number; // estimarea brută a călătoriilor dintre ele (extinsă la km de pe kilometraj)
  km: number | null;
  lPer100: number | null;
  factor: number;
  exact: boolean; // plin → plin: litrii de pe bonuri, fără indicator
}

export interface FuelCalibration {
  factor: number; // pentru drumurile de după ultimul interval încheiat
  calibrated: boolean; // false = încă fără destule date
  source: "level" | "refuels" | null; // "refuels" = intervalele dintre alimentări
  intervals: FuelInterval[];
  lPer100: number | null; // consumul real recent (aceleași intervale ca factorul)
  level: LevelConsumption | null;
}

/** O citire a nivelului din rezervor (CAN c104, litri întregi), cu motorul pornit. */
export interface LevelReading {
  t: string; // ISO
  fuel: number;
  odo: number | null;
}

export interface LevelConsumption {
  from: string;
  to: string;
  liters: number; // consumați: nivelul de la început − cel de la sfârșit + alimentările
  refills: number; // litri alimentați (salturile în sus)
  km: number | null;
  lPer100: number | null;
}

/** Un salt în sus de atâția litri e o alimentare; sub atât e combustibil care se mișcă. */
const REFILL_JUMP = 3;
/** Sub atâția litri consumați, rezoluția de 1 L a nivelului strică factorul. */
const MIN_LEVEL_LITERS = 8;
/** Nivelul la un moment dat = mediana citirilor din atâtea minute (oscilează 37/38 L). */
const LEVEL_WINDOW_MS = 10 * 60_000;
/** O alimentare din jurnal se potrivește cu saltul cel mai apropiat, la cel mult atâtea ore. */
export const REFUEL_MATCH_MS = 12 * 3_600_000;
/**
 * Data pusă greșit (alimentarea trecută a doua zi cu ora de atunci): saltul rămas fără pereche
 * la cel mult atâtea ore, dacă și litrii seamănă (indicatorul arată mai puțin la plin).
 */
const LATE_MATCH_MS = 72 * 3_600_000;
const LATE_MATCH_LITERS = 8;

function median(xs: number[]): number {
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

/**
 * Mediana nivelului pornind de la citirea `i`, în direcția `dir`, pe cel mult LEVEL_WINDOW_MS
 * și fără să treacă de `limit` (indexul unei alimentări).
 */
function levelAround(readings: LevelReading[], i: number, dir: 1 | -1, limit: number): number {
  const t0 = Date.parse(readings[i].t);
  const xs: number[] = [];
  for (let k = i; k >= 0 && k < readings.length; k += dir) {
    if (dir === 1 ? k > limit : k < limit) break;
    if (Math.abs(Date.parse(readings[k].t) - t0) > LEVEL_WINDOW_MS) break;
    xs.push(readings[k].fuel);
  }
  return median(xs);
}

/** O alimentare văzută în nivelul rezervorului (un salt ≥ 3 L). */
export interface LevelRefill {
  at: string; // prima citire după salt
  since: string; // ultima citire de dinainte (alimentarea e între ele)
  before: number; // nivelul de dinainte (mediană pe 10 minute)
  after: number; // nivelul de după
  liters: number; // ≈ after − before (nivelul are pași de 1 L)
  odo: number | null;
}

/** Salturile brute (indexul primei citiri de după) și alimentările care rămân după mediane. */
function scanLevels(readings: LevelReading[]): { jumps: number[]; refills: LevelRefill[] } {
  const jumps: number[] = [];
  for (let i = 1; i < readings.length; i++) {
    if (readings[i].fuel - readings[i - 1].fuel >= REFILL_JUMP) jumps.push(i);
  }
  const refills = jumps
    .map((i, n) => {
      const before = levelAround(readings, i - 1, -1, n > 0 ? jumps[n - 1] : 0);
      const after = levelAround(
        readings,
        i,
        1,
        n + 1 < jumps.length ? jumps[n + 1] - 1 : readings.length - 1,
      );
      return {
        at: readings[i].t,
        since: readings[i - 1].t,
        before,
        after,
        liters: after - before,
        odo: readings[i].odo,
      };
    })
    .filter((r) => r.liters >= REFILL_JUMP);
  return { jumps, refills };
}

/**
 * Alimentările din nivelul rezervorului: salturi ≥ 3 L între citiri consecutive, cu nivelurile
 * din jur ca mediane pe 10 minute (citirile oscilează cu 1 L). `readings` crescător, fuel > 0.
 */
export function levelRefills(readings: LevelReading[]): LevelRefill[] {
  return scanLevels(readings).refills;
}

export interface RefuelMatch {
  byRefill: number[]; // pentru fiecare salt: indexul alimentării din jurnal sau -1
  byRefuel: number[]; // pentru fiecare alimentare din jurnal: indexul saltului sau -1
  late: Set<number>; // alimentările (index) potrivite doar la a doua trecere: data nu se potrivește
}

/**
 * Potrivește alimentările din jurnal cu salturile nivelului, una la una, perechile cele mai
 * apropiate întâi: la ±12 h, apoi (data pusă greșit) la ±72 h dacă litrii seamănă.
 */
export function matchRefuels(
  refills: LevelRefill[],
  refuels: Array<{ at: string; liters: number }>,
): RefuelMatch {
  const byRefill = refills.map(() => -1);
  const byRefuel = refuels.map(() => -1);
  const late = new Set<number>();
  const pass = (maxMs: number, sameLiters: boolean) => {
    const pairs: Array<{ i: number; k: number; d: number }> = [];
    refills.forEach((j, i) => {
      if (byRefill[i] >= 0) return;
      refuels.forEach((r, k) => {
        if (byRefuel[k] >= 0) return;
        const d = Math.abs(Date.parse(r.at) - Date.parse(j.at));
        if (d > maxMs) return;
        if (sameLiters && Math.abs(r.liters - j.liters) > LATE_MATCH_LITERS) return;
        pairs.push({ i, k, d });
      });
    });
    pairs.sort((a, b) => a.d - b.d);
    for (const { i, k } of pairs) {
      if (byRefill[i] >= 0 || byRefuel[k] >= 0) continue;
      byRefill[i] = k;
      byRefuel[k] = i;
      if (sameLiters) late.add(k);
    }
  };
  pass(REFUEL_MATCH_MS, false);
  pass(LATE_MATCH_MS, true);
  return { byRefill, byRefuel, late };
}

/**
 * Consumul real din nivelul rezervorului: nivelul de la început − cel de la sfârșit + ce s-a
 * alimentat. Nivelurile sunt mediane pe 10 minute (citirile oscilează cu 1 L); o alimentare
 * e un salt ≥ 3 L, cu litrii de pe bon dacă e în jurnal (`refuels`), altfel cu diferența
 * nivelurilor din jurul saltului. Eroarea rămâne ~1 L oricât de lungă e perioada.
 * `readings` crescător după timp, fuel > 0.
 */
export function levelConsumption(
  readings: LevelReading[],
  refuels: Array<{ at: string; liters: number }> = [],
): LevelConsumption | null {
  if (readings.length < 2) return null;
  const { jumps, refills: seen } = scanLevels(readings);
  const match = matchRefuels(seen, refuels);
  const refills = seen.reduce(
    (s, j, i) => s + (match.byRefill[i] >= 0 ? refuels[match.byRefill[i]].liters : j.liters),
    0,
  );
  const last = readings.length - 1;
  const startLevel = levelAround(readings, 0, 1, jumps.length ? jumps[0] - 1 : last);
  const endLevel = levelAround(readings, last, -1, jumps.length ? jumps[jumps.length - 1] : 0);
  const liters = Math.round((startLevel - endLevel + refills) * 10) / 10;
  const odos = readings.map((r) => r.odo).filter((o): o is number => o !== null && o > 0);
  const km =
    odos.length >= 2 && odos[odos.length - 1] > odos[0] ? odos[odos.length - 1] - odos[0] : null;
  return {
    from: readings[0].t,
    to: readings[last].t,
    liters,
    refills: Math.round(refills * 10) / 10,
    km,
    lPer100: km !== null && km >= 50 ? (liters / km) * 100 : null,
  };
}

/**
 * Nivelul de acum: mediana ultimelor 10 minute, dar doar după ultima alimentare (altfel, imediat
 * după plin, mediana ar lua nivelul de dinainte). `readings` crescător, fuel > 0.
 */
export function currentLevel(readings: LevelReading[]): number | null {
  if (!readings.length) return null;
  let jump = 0;
  for (let i = readings.length - 1; i > 0; i--) {
    if (readings[i].fuel - readings[i - 1].fuel >= REFILL_JUMP) {
      jump = i;
      break;
    }
  }
  return levelAround(readings, readings.length - 1, -1, jump);
}

/** Câte intervale recente intră în factorul de acum (consumul se schimbă cu anotimpul). */
const CALIBRATION_INTERVALS = 3;
/**
 * Un interval se încheie abia de la atâția litri consumați (indicatorul are pași de 1 L, deci
 * ~±1,5 L pe interval); alimentările mai dese se adună în același interval.
 */
const MIN_INTERVAL_LITERS = 20;
const FACTOR_MIN = 0.4;
const FACTOR_MAX = 2.5;

/** Un capăt de interval: o alimentare (salt în rezervor și/sau rând în jurnal) sau prima citire. */
interface Anchor {
  since: string; // drumurile începute după acest moment sunt după alimentare
  before: number | null; // nivelul de dinainte (null = fără citiri)
  liters: number; // de pe bon, altfel din indicator
  refuel: FuelRefuel | null;
  odo: number | null;
  first: boolean; // prima citire, nu o alimentare
}

type CalTrip = { start: string; end?: string; modelLiters: number; distanceKm?: number };

/**
 * Intervalele dintre alimentări și factorii lor. Fiecare interval are consumul lui real:
 * nivelul de dinaintea alimentării + litrii de pe bon − nivelul de dinaintea alimentării
 * următoare (indicatorul nu se folosește imediat după alimentare, unde stă pe 52 L sau minte);
 * între două plinuri, direct litrii de pe bonuri. Drumurile unui interval încheiat își
 * păstrează factorul; cele de după ultimul folosesc media ultimelor intervale. Fără niciun
 * interval încheiat: nivelul rezervorului pe toată perioada (de la 8 L consumați), altfel 1.
 * `refuels` și `readings` crescător după dată; `trips` cu începutul și estimarea brută.
 */
export function calibrate(
  refuels: FuelRefuel[],
  trips: CalTrip[],
  readings: LevelReading[] = [],
): FuelCalibration {
  const clamp = (x: number) => Math.min(FACTOR_MAX, Math.max(FACTOR_MIN, x));
  const level = levelConsumption(readings, refuels);
  const { jumps, refills } = scanLevels(readings);
  const match = matchRefuels(refills, refuels);
  const anchors: Anchor[] = [];
  // Alimentări din jurnal fără salt, între citiri (prea mici ca să se vadă): doar litrii.
  const extras: Array<{ at: string; liters: number }> = [];
  if (readings.length) {
    const last = readings.length - 1;
    anchors.push({
      since: readings[0].t,
      before: levelAround(readings, 0, 1, jumps.length ? jumps[0] - 1 : last),
      liters: 0,
      refuel: null,
      odo: readings.find((r) => r.odo !== null && r.odo > 0)?.odo ?? null,
      first: true,
    });
    refills.forEach((j, i) => {
      const r = match.byRefill[i] >= 0 ? refuels[match.byRefill[i]] : null;
      anchors.push({
        since: j.since,
        before: j.before,
        liters: r ? r.liters : j.liters,
        refuel: r,
        odo: j.odo ?? r?.odo ?? null,
        first: false,
      });
    });
    refuels.forEach((r, k) => {
      if (match.byRefuel[k] >= 0 || r.at < readings[0].t) return;
      if (r.at <= readings[last].t) {
        extras.push({ at: r.at, liters: r.liters });
        return;
      }
      // Trecută în jurnal, dar mașina n-a mai pornit de atunci: nivelul de dinainte e ultimul.
      anchors.push({
        since: r.at,
        before: currentLevel(readings),
        liters: r.liters,
        refuel: r,
        odo: r.odo,
        first: false,
      });
    });
  } else {
    // Fără citiri de nivel rămân doar intervalele plin → plin.
    for (const r of refuels) {
      anchors.push({ since: r.at, before: null, liters: r.liters, refuel: r, odo: r.odo, first: false });
    }
  }

  const intervals: FuelInterval[] = [];
  const isFull = (a: Anchor) => a.refuel?.full === true;
  let i = 0;
  while (i < anchors.length && anchors[i].before === null && !isFull(anchors[i])) i++;
  for (let j = i + 1; j < anchors.length; j++) {
    const a = anchors[i];
    const b = anchors[j];
    const exact = isFull(a) && isFull(b);
    if (!exact && (a.before === null || b.before === null)) continue;
    let liters = extras
      .filter((e) => e.at > a.since && e.at <= b.since)
      .reduce((s, e) => s + e.liters, 0);
    if (exact) {
      for (let k = i + 1; k <= j; k++) liters += anchors[k].liters;
    } else {
      liters += a.before! - b.before!;
      for (let k = i; k < j; k++) liters += anchors[k].liters;
    }
    if (liters < MIN_INTERVAL_LITERS) {
      // Un plin după prea puțini litri: de la el pornește un interval exact, restul se lasă.
      if (!exact && isFull(b)) i = j;
      continue;
    }
    const inside = trips.filter((t) =>
      a.first
        ? (t.end ?? t.start) >= a.since && t.start <= b.since
        : t.start > a.since && t.start <= b.since,
    );
    let modelLiters = inside.reduce((s, t) => s + t.modelLiters, 0);
    if (!(modelLiters > 0)) continue; // fără drumuri înregistrate: se adună cu următorul
    const km = a.odo !== null && b.odo !== null && b.odo > a.odo ? b.odo - a.odo : null;
    // Drumurile neînregistrate (aplicația oprită, 07.10: 17 km) consumă și ele: estimarea se
    // extinde la km de pe kilometraj, cu același consum pe km.
    const tripKm = inside.reduce((s, t) => s + (t.distanceKm ?? 0), 0);
    if (km !== null && tripKm > 0 && km > tripKm) modelLiters *= km / tripKm;
    intervals.push({
      from: a.since,
      to: b.since,
      fromId: a.refuel?.id ?? null,
      toId: b.refuel?.id ?? null,
      liters: Math.round(liters * 100) / 100,
      modelLiters,
      km,
      lPer100: km !== null ? (liters / km) * 100 : null,
      factor: clamp(liters / modelLiters),
      exact,
    });
    i = j;
  }

  const recent = intervals.slice(-CALIBRATION_INTERVALS);
  if (recent.length) {
    const real = recent.reduce((s, x) => s + x.liters, 0);
    const model = recent.reduce((s, x) => s + x.modelLiters, 0);
    const withKm = recent.filter((x) => x.km !== null);
    const km = withKm.reduce((s, x) => s + (x.km ?? 0), 0);
    return {
      factor: clamp(real / model),
      calibrated: true,
      source: "refuels",
      intervals,
      lPer100: km >= 50 ? (withKm.reduce((s, x) => s + x.liters, 0) / km) * 100 : (level?.lPer100 ?? null),
      level,
    };
  }
  // Încă niciun interval încheiat: nivelul din rezervor pe toată perioada.
  if (level && level.liters >= MIN_LEVEL_LITERS) {
    const inside = trips.filter((t) => (t.end ?? t.start) >= level.from && t.start <= level.to);
    let levelModel = inside.reduce((s, t) => s + t.modelLiters, 0);
    const tripKm = inside.reduce((s, t) => s + (t.distanceKm ?? 0), 0);
    if (level.km !== null && tripKm > 0 && level.km > tripKm) levelModel *= level.km / tripKm;
    if (levelModel > 0) {
      return {
        factor: clamp(level.liters / levelModel),
        calibrated: true,
        source: "level",
        intervals,
        lPer100: level.lPer100,
        level,
      };
    }
  }
  return { factor: 1, calibrated: false, source: null, intervals, lPer100: level?.lPer100 ?? null, level };
}

/** Intervalul încheiat în care cade `at` (cele de dinaintea primului țin de primul). */
function intervalAt(cal: FuelCalibration, at: string): FuelInterval | null {
  for (const iv of cal.intervals) if (at <= iv.to) return iv;
  return null;
}

/** Factorul unui drum început la `at`: al intervalului lui, altfel cel de acum. */
export function factorAt(cal: FuelCalibration, at: string): number {
  return intervalAt(cal, at)?.factor ?? cal.factor;
}

/** Consumul real (L/100 km) din perioada lui `at`, pentru drumurile neînregistrate. */
export function lPer100At(cal: FuelCalibration, at: string): number | null {
  return intervalAt(cal, at)?.lPer100 ?? cal.lPer100;
}

export interface TankPrice {
  at: string;
  price: number; // lei / litru, media a ce e în rezervor după alimentare
}

/**
 * Prețul benzinei din rezervor după fiecare alimentare: media ponderată între ce era înăuntru
 * (nivelul de dinainte, la prețul de până atunci) și ce s-a pus. Fără nivel cunoscut, prețul
 * alimentării. `refuels` și `readings` crescător după dată.
 */
export function tankPrices(refuels: FuelRefuel[], readings: LevelReading[] = []): TankPrice[] {
  const refills = levelRefills(readings);
  const match = matchRefuels(refills, refuels);
  const out: TankPrice[] = [];
  let price: number | null = null;
  let n = 0;
  refuels.forEach((r, k) => {
    if (r.price === null) return;
    let before = match.byRefuel[k] >= 0 ? refills[match.byRefuel[k]].before : null;
    if (before === null) {
      while (n < readings.length && readings[n].t <= r.at) n++;
      before = n > 0 ? readings[n - 1].fuel : 0;
    }
    price = price === null ? r.price : (before * price + r.liters * r.price) / (before + r.liters);
    out.push({ at: r.at, price });
  });
  return out;
}

/** Prețul de la ultima alimentare de dinainte de `at` (sau de la prima, dacă toate sunt după). */
export function priceAt(prices: Array<{ at: string; price: number | null }>, at: string): number | null {
  let price: number | null = null;
  for (const r of prices) {
    if (r.price === null) continue;
    if (r.at <= at || price === null) price = r.price;
    if (r.at > at) break;
  }
  return price;
}
