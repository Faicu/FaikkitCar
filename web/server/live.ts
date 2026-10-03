// ---------------------------------------------------------------------------
// Starea de acum a mașinii: aplicația FaikkitCar trimite la ~15 s (POST /api/car/state)
// contactul dedus din cadrele de bord, turația, viteza etc. De aici: oprită / contact /
// motor pornit / în mers, călătoria în curs (din punctele de traseu) și autonomia.
// Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { readLastPosition, type Position } from "./car.ts";
import { getDb, hourKey, memo } from "./db.ts";
import { applyFuel, readFuelSummary } from "./fuel.ts";
import { TRAFFIC_MAX_MS } from "./trip-math.ts";
import { readTrips, TRIP_GAP_MS, type Trip } from "./trips.ts";

// „traffic” nu vine de la mașină: e „engine” în timpul unui drum (readLive), cu aceeași
// limită ca la călătorii (trip-math.ts).
export type CarState = "off" | "contact" | "engine" | "traffic" | "driving";

export interface IncomingState {
  t?: number;
  contact?: boolean;
  rpm?: number | null;
  cs?: number | null; // viteza CAN, km/h
  gs?: number | null; // viteza GPS, km/h
  v?: number | null;
  fuel?: number | null;
  temp?: number | null;
  odo?: number | null;
  lat?: number | null;
  lon?: number | null;
  // Valori brute de la mașină (de la 1.1.36), traduse aici în LiveData.
  x?: {
    ac?: number;
    auto?: number;
    fan?: number;
    tl?: number;
    tr?: number;
    ic?: number;
    belt?: number;
    hb?: number;
    s41?: number;
    rev?: boolean;
    doors?: number[];
    parked?: boolean; // semnale de parcare în oprirea curentă (1.1.41+)
  };
}

export interface Climate {
  ac: boolean | null;
  auto: boolean | null;
  fan: number | null; // treapta ventilatorului, cum o trimite decodorul (0 = oprit)
  setLeft: number | null; // °C setate; null = necunoscut
  setRight: number | null;
}

// Ușile, în ordinea codurilor c1…c5.
const DOORS = ["șofer", "pasager", "spate stânga", "spate dreapta", "portbagaj"];

export interface LiveData {
  rpm: number | null;
  speed: number | null;
  volt: number | null;
  fuel: number | null;
  temp: number | null;
  odo: number | null;
  climate: Climate | null; // null = aplicația din mașină nu le trimite încă (< 1.1.36)
  // Probabil consumul instantaneu al bordului (c1033 / 10), L/100 km; doar în mers.
  instantL100: number | null;
  doorsOpen: string[];
  belt: boolean | null; // centura șoferului pusă
  handbrake: boolean | null; // trasă
  reverse: boolean | null;
  lights: boolean | null; // faza scurtă (bitul 0x80 din octetul de stare 0x41/1)
  parked: boolean; // mașina a dat semne de parcare în oprirea curentă
  // Temperatura din habitaclu nu e trimisă de decodor (Raise), deci nu există aici.
}

export interface Live {
  state: CarState;
  since: string | null; // de când e în starea asta (null = necunoscut)
  at: string | null; // ultima stare primită de la mașină
  data: LiveData | null; // null cu mașina oprită
  trip: Trip | null; // călătoria în curs
  position: Position | null;
  tank: { liters: number; at: string } | null;
  // Câți km mai merge cu ce e în rezervor, la consumul mediu (real sau estimat).
  range: { km: number; lPer100: number; real: boolean } | null;
}

// Fără stare nouă atâta timp, unitatea doarme sau s-a oprit: mașina e oprită.
const STALE_MS = 90_000;

function num(x: unknown): number | null {
  return typeof x === "number" && Number.isFinite(x) ? x : null;
}

/**
 * Temperatura setată din codul decodorului: pași de 0,5 °C, 11 = 21 °C (valoarea de fabrică
 * VW). De confirmat cu afișajul climei; 0 și ≥ 31 ar fi LO / HI.
 */
function setTemp(v: number | undefined): number | null {
  if (v === undefined || v <= 0 || v >= 31) return null;
  return 15.5 + v / 2;
}

function details(p: IncomingState, state: CarState) {
  const x = p.x;
  if (!x) {
    return {
      climate: null,
      instantL100: null,
      doorsOpen: [],
      belt: null,
      handbrake: null,
      reverse: null,
      lights: null,
      parked: false,
    };
  }
  const bit = (b: number) => (x.s41 === undefined ? null : (x.s41 & b) !== 0);
  const released = bit(0x20);
  return {
    climate: {
      ac: x.ac === undefined ? null : x.ac === 1,
      auto: x.auto === undefined ? null : x.auto === 1,
      fan: x.fan ?? null,
      setLeft: setTemp(x.tl),
      setRight: setTemp(x.tr),
    },
    instantL100: state === "driving" && x.ic !== undefined ? x.ic / 10 : null,
    doorsOpen: (x.doors ?? []).map((i) => DOORS[i]).filter((d): d is string => d !== undefined),
    belt: x.belt === undefined ? null : x.belt === 0,
    // Bitul 0x20 din 0x41/1 s-a schimbat sigur la tragere (calibrarea din 01.10); c103 nu a
    // reacționat la tragerea din 02.10, deci rămâne doar rezervă.
    handbrake: released !== null ? !released : x.hb !== undefined ? x.hb === 0 : null,
    reverse: x.rev ?? null,
    lights: bit(0x80),
    parked: x.parked === true,
  };
}

function stateOf(p: IncomingState): CarState {
  const speed = num(p.cs) ?? num(p.gs);
  if (speed !== null && speed >= 3) return "driving";
  if ((num(p.rpm) ?? 0) > 300) return "engine";
  return p.contact ? "contact" : "off";
}

interface Row {
  received_at: string;
  state: CarState;
  since: string;
  data: string;
}

function readRow(): Row | undefined {
  return getDb()
    .prepare(`SELECT received_at, state, since, data FROM car_state WHERE id = 1`)
    .get() as Row | undefined;
}

// Cererile care așteaptă o stare nouă (GET /api/live?after=…): eliberate la fiecare POST.
const waiters = new Set<() => void>();

/**
 * Așteaptă până vine de la mașină o stare mai nouă decât `after` (ISO), cel mult `ms`.
 * Așa site-ul și Panel-ul primesc starea imediat, fără să întrebe des.
 */
export function waitForState(after: string, ms: number): Promise<void> {
  const row = readRow();
  if (!row || row.received_at > after) return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer);
      waiters.delete(done);
      resolve();
    };
    const timer = setTimeout(done, ms);
    waiters.add(done);
  });
}

export function saveState(p: IncomingState): CarState {
  const now = new Date().toISOString();
  const state = stateOf(p);
  const prev = readRow();
  // O pauză lungă înseamnă că între timp a fost oprită: starea începe acum.
  const fresh = prev && Date.now() - new Date(prev.received_at).getTime() < STALE_MS;
  const since = fresh && prev.state === state ? prev.since : now;
  const data: LiveData = {
    rpm: num(p.rpm),
    speed: num(p.cs) ?? num(p.gs),
    volt: num(p.v),
    fuel: (num(p.fuel) ?? 0) > 0 ? num(p.fuel) : null,
    temp: num(p.temp),
    odo: (num(p.odo) ?? 0) > 0 ? num(p.odo) : null,
    ...details(p, state),
  };
  getDb()
    .prepare(
      `INSERT INTO car_state (id, received_at, state, since, data) VALUES (1, ?, ?, ?, ?)
       ON CONFLICT (id) DO UPDATE SET received_at = excluded.received_at, state = excluded.state,
         since = excluded.since, data = excluded.data`,
    )
    .run(now, state, since, JSON.stringify(data));
  for (const w of [...waiters]) w();
  return state;
}

export function readLive(): Live {
  const row = readRow();
  const stale = !row || Date.now() - new Date(row.received_at).getTime() >= STALE_MS;
  let state: CarState = "off";
  let since: string | null = null;
  let data: LiveData | null = null;
  if (row && !stale) {
    state = row.state;
    since = row.since;
    data = JSON.parse(row.data) as LiveData;
  } else if (row) {
    // Oprită de la ultima stare primită (sau de când a raportat ea „oprită”).
    since = row.state === "off" ? row.since : row.received_at;
  }

  // Călătoria în curs: ultima, dacă n-a trecut pauza care desparte călătoriile.
  const last = readTrips(1)[0];
  const trip =
    last && state !== "off" && Date.now() - new Date(last.end).getTime() < TRIP_GAP_MS
      ? applyFuel([last])[0]
      : null;
  // Oprit cu motorul pornit după ce a mers în drumul ăsta (semafor, coloană): „în trafic”, dacă
  // nu sunt semne de parcare (frâna de mână, o ușă, marșarierul, un loc salvat) și nu stă de
  // peste 10 minute. Altfel rămâne „Motor pornit, pe loc” (staționare).
  const position = readLastPosition();
  const parkedSigns =
    data?.parked === true ||
    data?.handbrake === true ||
    (data?.doorsOpen?.length ?? 0) > 0 ||
    data?.reverse === true ||
    position?.place != null;
  if (
    state === "engine" &&
    trip &&
    (trip.maxSpeed ?? 0) >= 3 &&
    since &&
    Date.now() - new Date(since).getTime() <= TRAFFIC_MAX_MS &&
    !parkedSigns
  ) {
    state = "traffic";
  }

  const fuel = readFuelSummary();
  // Nivelul din punctele de traseu (mediană pe 10 min); fără puncte recente (doar contact),
  // ultima citire din stare.
  const tankOld = !fuel.tank || Date.now() - Date.parse(fuel.tank.at) > 10 * 60_000;
  const tank =
    data?.fuel != null && tankOld ? { liters: data.fuel, at: row!.received_at } : fuel.tank;
  let range: Live["range"] = null;
  const real = fuel.avgLPer100 !== null;
  const lPer100 = fuel.avgLPer100 ?? estimatedLPer100();
  if (tank && lPer100 !== null && lPer100 > 0) {
    range = { km: Math.round((tank.liters / lPer100) * 100), lPer100: Math.round(lPer100 * 10) / 10, real };
  }
  return { state, since, at: row?.received_at ?? null, data, trip, position, tank, range };
}

/** Consumul estimat pe ultimele 60 de zile, cât nu există încă unul real din rezervor. */
const estimatedLPer100 = memo((): number | null => {
  const trips = applyFuel(readTrips(60));
  const km = trips.reduce((s, t) => s + t.distanceKm, 0);
  const liters = trips.reduce((s, t) => s + (t.fuelL ?? 0), 0);
  return km >= 5 ? (liters / km) * 100 : null;
}, hourKey);
