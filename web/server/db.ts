// ---------------------------------------------------------------------------
// Baza de date FaikkitCar (node:sqlite), în data/faikkitcar.db. Schema se creează
// la prima deschidere; migrările viitoare cresc PRAGMA user_version.
// ---------------------------------------------------------------------------

import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";

export function dataDir(): string {
  return process.env.FAIKKITCAR_DATA_DIR ?? "/opt/faikkitcar/data";
}

let db: DatabaseSync | null = null;

// Versiunea datelor din care se calculează călătoriile (puncte, combinări, locuri,
// alimentări): crește la fiecare scriere, iar calculele scumpe se refac doar atunci.
let version = 0;

export function dataChanged(): void {
  version++;
}

/**
 * Rezultatul lui `fn`, refăcut doar după o schimbare a datelor (dataChanged) sau a cheii
 * `extra` (ex. ziua, pentru ferestrele de timp ca „ultimele 30 de zile”).
 */
export function memo<T>(fn: () => T, extra: () => string = () => ""): () => T {
  let at = "";
  let value: T;
  return () => {
    const key = `${version}|${extra()}`;
    if (at !== key) {
      value = fn();
      at = key;
    }
    return value;
  };
}

/** Cheie care se schimbă la fiecare oră (ferestrele de timp ale statisticilor). */
export function hourKey(): string {
  return new Date().toISOString().slice(0, 13);
}

export function getDb(): DatabaseSync {
  if (db) return db;
  const path = process.env.FAIKKITCAR_DB ?? `${dataDir()}/faikkitcar.db`;
  mkdirSync(dirname(path), { recursive: true });
  db = new DatabaseSync(path);
  db.exec("PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000;");
  db.exec(`
    -- Jurnalul aplicației din mașină (POST /api/car/log). UNIQUE: aplicația retrimite
    -- lotul dacă nu primește răspuns, iar duplicatele se ignoră.
    CREATE TABLE IF NOT EXISTS log (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_at TEXT NOT NULL,
      received_at TEXT NOT NULL,
      version TEXT,
      line TEXT NOT NULL,
      UNIQUE (device_at, line)
    );
    CREATE INDEX IF NOT EXISTS idx_log_device_at ON log(device_at DESC);

    -- Punctele de traseu (POST /api/car/trip): GPS + datele mașinii din MainServer.
    -- Călătoriile nu se stochează: se obțin la citire, tăind la pauzele de peste 5 minute.
    CREATE TABLE IF NOT EXISTS trip_point (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_at TEXT NOT NULL UNIQUE,
      received_at TEXT NOT NULL,
      lat REAL,
      lon REAL,
      alt REAL,
      acc REAL,
      gps_speed REAL,
      can_speed REAL,
      rpm INTEGER,
      volt REAL,
      temp REAL,
      odo INTEGER,
      fuel REAL
    );

    -- Mentenanța: scadența la last_km + every_km și/sau due_date sau last_date + every_months.
    CREATE TABLE IF NOT EXISTS reminder (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      title TEXT NOT NULL,
      every_km INTEGER,
      every_months INTEGER,
      last_km INTEGER,
      last_date TEXT,
      due_date TEXT,
      created_at TEXT NOT NULL
    );

    -- Starea de acum a mașinii (POST /api/car/state, la ~15 s cât unitatea e trează):
    -- un singur rând; since = de când e în starea state.
    CREATE TABLE IF NOT EXISTS car_state (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      received_at TEXT NOT NULL,
      state TEXT NOT NULL,
      since TEXT NOT NULL,
      data TEXT NOT NULL
    );

    -- Călătorii combinate de utilizator (ex. dus-întors cu o oprire): bucățile care încep
    -- între start și end se arată ca una singură. Punctele rămân neschimbate.
    CREATE TABLE IF NOT EXISTS trip_join (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      start TEXT NOT NULL,
      end TEXT NOT NULL,
      created_at TEXT NOT NULL
    );

    -- Locurile salvate (Acasă, Serviciu...): călătoriile și parcarea se denumesc după ele.
    CREATE TABLE IF NOT EXISTS place (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      lat REAL NOT NULL,
      lon REAL NOT NULL,
      radius INTEGER NOT NULL DEFAULT 100,
      created_at TEXT NOT NULL
    );

    -- Propunerile respinse (alimentări văzute în rezervor, locuri unde parchează des), ca să
    -- nu mai apară: cheia e ora alimentării sau poziția locului (lat, lon).
    CREATE TABLE IF NOT EXISTS dismissed (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      kind TEXT NOT NULL,
      key TEXT NOT NULL,
      lat REAL,
      lon REAL,
      created_at TEXT NOT NULL,
      UNIQUE (kind, key)
    );

    -- Jurnalul de service: lucrări (ulei, piese, ITP...) cu data, kilometrajul și costul,
    -- plus fișierele atașate (bonuri, facturi, poze) în data/service/.
    CREATE TABLE IF NOT EXISTS service (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      date TEXT NOT NULL,
      odo INTEGER,
      title TEXT NOT NULL,
      cost REAL,
      notes TEXT,
      created_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS service_file (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      service_id INTEGER NOT NULL,
      name TEXT NOT NULL,
      mime TEXT NOT NULL,
      size INTEGER NOT NULL,
      created_at TEXT NOT NULL
    );

    -- Alimentările: prețul dă costul drumurilor; plinurile sunt rezerva calibrării.
    CREATE TABLE IF NOT EXISTS refuel (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      at TEXT NOT NULL,
      odo INTEGER,
      liters REAL NOT NULL,
      price REAL,
      full INTEGER NOT NULL DEFAULT 1,
      note TEXT,
      created_at TEXT NOT NULL
    );
  `);
  // Migrări pentru coloanele adăugate după crearea tabelelor.
  const cols = (table: string) =>
    (db!.prepare(`PRAGMA table_info(${table})`).all() as Array<{ name: string }>).map((c) => c.name);
  // Probabil consumul instantaneu al bordului (c1033), L/100 km x10; doar în mers (1.1.36+).
  if (!cols("trip_point").includes("cons")) db.exec(`ALTER TABLE trip_point ADD COLUMN cons REAL`);
  // Tensiunea minimă la pornirea motorului (demarorul), măsurată în mașină (1.1.39+).
  if (!cols("trip_point").includes("crank")) db.exec(`ALTER TABLE trip_point ADD COLUMN crank REAL`);
  // Clima și centura pe fiecare punct (1.1.40+): AC 1/0, treapta ventilatorului, centura pusă 1/0.
  for (const [col, type] of [
    ["ac", "INTEGER"],
    ["fan", "INTEGER"],
    ["belt", "INTEGER"],
    // Semnalele de parcare (1.1.41+): frâna de mână trasă, o ușă deschisă, marșarierul, 1/0.
    ["hb", "INTEGER"],
    ["door", "INTEGER"],
    ["rev", "INTEGER"],
  ]) {
    if (!cols("trip_point").includes(col)) db.exec(`ALTER TABLE trip_point ADD COLUMN ${col} ${type}`);
  }
  return db;
}
