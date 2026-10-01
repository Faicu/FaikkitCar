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
  return db;
}
