// ---------------------------------------------------------------------------
// Estimarea combustibilului pe călătorie (FaikkitCar), fără senzor de consum:
// din viteză și turație, cu un model fizic simplu (linia Willans):
//   litri = lucru mecanic la roți / (energia unui litru × randament)
//         + rotații ale motorului × litri pe rotație (frecări, mers în gol).
// Modelul dă o valoare brută; factorul de calibrare din alimentări (plin → plin)
// o aduce la litrii reali. Fără dependențe de server, ca să poată fi testat.
// ---------------------------------------------------------------------------

/** Golf 6 1.2 TSI (CBZB), cu șofer. Valorile absolute contează puțin: calibrarea le corectează. */
export const FUEL_MODEL = {
  massKg: 1350,
  cdA: 0.69, // Cd 0,31 × 2,22 m²
  airDensity: 1.2,
  rollingCoef: 0.012,
  // Energia unui litru de benzină (~32 MJ) × randamentul motor + transmisie.
  joulesPerLiter: 32e6 * 0.27,
  // ~0,6 L/h la relanti (750 rpm = 45.000 rotații/h).
  litersPerRev: 1.33e-5,
  // Compresorul AC: ~1,5 kW mediu (pornit/oprit ciclic) ≈ 0,4 L/h în plus cu motorul pornit.
  acLitersPerHour: 0.4,
};

/** Peste atâtea secunde între două puncte nu știm ce s-a întâmplat (motor oprit, fără date). */
const MAX_GAP_S = 60;

export interface FuelSample {
  t: number; // epoch ms
  speed: number | null; // km/h
  rpm: number | null;
  ac?: boolean; // AC pornit
}

export interface FuelEstimate {
  liters: number; // brut, necalibrat
  idleMin: number; // pe loc cu motorul pornit
}

export function estimateFuel(samples: FuelSample[]): FuelEstimate {
  const m = FUEL_MODEL;
  let work = 0;
  let revs = 0;
  let idleS = 0;
  let acLiters = 0;
  for (let i = 1; i < samples.length; i++) {
    const a = samples[i - 1];
    const b = samples[i];
    const dt = (b.t - a.t) / 1000;
    if (!(dt > 0) || dt > MAX_GAP_S) continue;
    const rpm = a.rpm ?? b.rpm;
    if (rpm === null || rpm <= 0) continue; // motorul oprit: nu consumă
    revs += (rpm / 60) * dt;
    if (a.ac) acLiters += (m.acLitersPerHour / 3600) * dt;
    const v1 = (a.speed ?? 0) / 3.6;
    const v2 = (b.speed ?? 0) / 3.6;
    const v = (v1 + v2) / 2;
    if (v < 0.3) {
      idleS += dt;
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
    liters: work / m.joulesPerLiter + revs * m.litersPerRev + acLiters,
    idleMin: idleS / 60,
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

export interface FuelInterval {
  fromId: number;
  toId: number;
  liters: number; // alimentat între două plinuri
  modelLiters: number; // estimarea brută a călătoriilor dintre ele
  km: number | null;
  lPer100: number | null;
}

export interface FuelCalibration {
  factor: number;
  calibrated: boolean; // false = încă fără destule date (nivel sau două plinuri)
  source: "level" | "refuels" | null; // de unde vine factorul
  intervals: FuelInterval[];
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
/** O alimentare din jurnal se potrivește cu un salt al nivelului la cel mult atâtea ore. */
const REFUEL_MATCH_MS = 3 * 3_600_000;

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
  const jumps: number[] = [];
  for (let i = 1; i < readings.length; i++) {
    if (readings[i].fuel - readings[i - 1].fuel >= REFILL_JUMP) jumps.push(i);
  }
  const used = new Set<number>();
  let refills = 0;
  jumps.forEach((i, n) => {
    const t = Date.parse(readings[i].t);
    const k = refuels.findIndex(
      (r, idx) => !used.has(idx) && Math.abs(Date.parse(r.at) - t) <= REFUEL_MATCH_MS,
    );
    if (k >= 0) {
      used.add(k);
      refills += refuels[k].liters;
    } else {
      const before = levelAround(readings, i - 1, -1, n > 0 ? jumps[n - 1] : 0);
      const after = levelAround(readings, i, 1, n + 1 < jumps.length ? jumps[n + 1] - 1 : readings.length - 1);
      refills += Math.max(0, after - before);
    }
  });
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

/** O alimentare văzută în nivelul rezervorului (un salt ≥ 3 L). */
export interface LevelRefill {
  at: string; // prima citire după salt
  before: number; // nivelul de dinainte (mediană pe 10 minute)
  after: number; // nivelul de după
  liters: number; // ≈ after − before (nivelul are pași de 1 L)
  odo: number | null;
}

/**
 * Alimentările din nivelul rezervorului: salturi ≥ 3 L între citiri consecutive, cu nivelurile
 * din jur ca mediane pe 10 minute (citirile oscilează cu 1 L). `readings` crescător, fuel > 0.
 */
export function levelRefills(readings: LevelReading[]): LevelRefill[] {
  const jumps: number[] = [];
  for (let i = 1; i < readings.length; i++) {
    if (readings[i].fuel - readings[i - 1].fuel >= REFILL_JUMP) jumps.push(i);
  }
  return jumps
    .map((i, n) => {
      const before = levelAround(readings, i - 1, -1, n > 0 ? jumps[n - 1] : 0);
      const after = levelAround(
        readings,
        i,
        1,
        n + 1 < jumps.length ? jumps[n + 1] - 1 : readings.length - 1,
      );
      return { at: readings[i].t, before, after, liters: after - before, odo: readings[i].odo };
    })
    .filter((r) => r.liters >= REFILL_JUMP);
}

/** Câte intervale plin → plin recente intră în factor (consumul se schimbă cu anotimpul). */
const CALIBRATION_INTERVALS = 5;
const FACTOR_MIN = 0.4;
const FACTOR_MAX = 2.5;

/**
 * Intervalele plin → plin și factorul de calibrare. `refuels` crescător după dată; `trips`
 * cu începutul și estimarea brută. Litrii unui interval = toate alimentările de după primul
 * plin, până la plinul următor inclusiv (și cele parțiale dintre ele).
 */
export function calibrate(
  refuels: FuelRefuel[],
  trips: Array<{ start: string; modelLiters: number }>,
  readings: LevelReading[] = [],
): FuelCalibration {
  const intervals: FuelInterval[] = [];
  let from: FuelRefuel | null = null;
  let liters = 0;
  for (const r of refuels) {
    if (from) liters += r.liters;
    if (!r.full) continue;
    if (from) {
      const a = from.at;
      const b = r.at;
      const modelLiters = trips
        .filter((t) => t.start >= a && t.start < b)
        .reduce((s, t) => s + t.modelLiters, 0);
      const km = from.odo !== null && r.odo !== null && r.odo > from.odo ? r.odo - from.odo : null;
      intervals.push({
        fromId: from.id,
        toId: r.id,
        liters,
        modelLiters,
        km,
        lPer100: km !== null ? (liters / km) * 100 : null,
      });
    }
    from = r;
    liters = 0;
  }
  const used = intervals.filter((i) => i.modelLiters > 0).slice(-CALIBRATION_INTERVALS);
  const model = used.reduce((s, i) => s + i.modelLiters, 0);
  const real = used.reduce((s, i) => s + i.liters, 0);
  const clamp = (x: number) => Math.min(FACTOR_MAX, Math.max(FACTOR_MIN, x));
  // Nivelul din rezervor are prioritate: nu cere plinuri și acoperă toate drumurile.
  const level = levelConsumption(readings, refuels);
  if (level && level.liters >= MIN_LEVEL_LITERS) {
    const levelModel = trips
      .filter((t) => t.start >= level.from && t.start <= level.to)
      .reduce((s, t) => s + t.modelLiters, 0);
    if (levelModel > 0) {
      return {
        factor: clamp(level.liters / levelModel),
        calibrated: true,
        source: "level",
        intervals,
        level,
      };
    }
  }
  return {
    factor: model > 0 ? clamp(real / model) : 1,
    calibrated: model > 0,
    source: model > 0 ? "refuels" : null,
    intervals,
    level,
  };
}

/** Prețul ultimei alimentări de dinainte de `at` (sau al primei, dacă toate sunt după). */
export function priceAt(refuels: FuelRefuel[], at: string): number | null {
  let price: number | null = null;
  for (const r of refuels) {
    if (r.price === null) continue;
    if (r.at <= at || price === null) price = r.price;
    if (r.at > at) break;
  }
  return price;
}
