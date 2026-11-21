// ---------------------------------------------------------------------------
// Baza de date FaikkitCar (node:sqlite), în data/faikkitcar.db. Schema se creează
// la prima deschidere; migrările viitoare cresc PRAGMA user_version.
// ---------------------------------------------------------------------------

import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";

import { logCategory } from "./log-category.ts";

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
    -- Unde a despărțit utilizatorul o călătorie unită automat (opririle scurte sau în locuri
    -- nesalvate, trip-math.ts sameTrip): ora primului punct de după oprire.
    CREATE TABLE IF NOT EXISTS trip_split (
      at TEXT PRIMARY KEY,
      created_at TEXT NOT NULL
    );

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

    -- Comenzi shell rulate de aplicație pe navigație (teste, diagnostic), cu rezultatul.
    CREATE TABLE IF NOT EXISTS car_cmd (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      cmd TEXT NOT NULL,
      timeout_s INTEGER NOT NULL,
      created_at TEXT NOT NULL,
      sent_at TEXT,
      done_at TEXT,
      exit_code INTEGER,
      output TEXT
    );

    -- Performanța navigației (unit.ts), la cel mult 30 s cât e trează; păstrată 90 de zile.
    CREATE TABLE IF NOT EXISTS unit_stat (
      at TEXT PRIMARY KEY,
      data TEXT NOT NULL
    );

    -- Codurile de eroare ale motorului (adaptorul OBD, 1.1.100+): memorate (03) sau în așteptare
    -- (07). Activ = cleared_at NULL; când nu mai apare la o citire, cleared_at = ora citirii.
    CREATE TABLE IF NOT EXISTS obd_dtc (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      code TEXT NOT NULL,
      kind TEXT NOT NULL,
      first_at TEXT NOT NULL,
      last_at TEXT NOT NULL,
      cleared_at TEXT
    );
    -- Erorile tuturor modulelor prin UDS (19 02, 1.1.101+): motor, bord, climatronic, airbag.
    -- status = octetul de stare ISO 14229 la ultima citire; activ = cleared_at NULL.
    CREATE TABLE IF NOT EXISTS obd_module_dtc (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      module TEXT NOT NULL,
      code TEXT NOT NULL,
      status INTEGER NOT NULL,
      first_at TEXT NOT NULL,
      last_at TEXT NOT NULL,
      cleared_at TEXT
    );
    -- Ultima citire a fiecărui modul: când și dacă a răspuns.
    CREATE TABLE IF NOT EXISTS obd_module_check (
      module TEXT PRIMARY KEY,
      at TEXT NOT NULL,
      ok INTEGER NOT NULL
    );
    -- Ultima citire a codurilor (un rând): când și dacă martorul motorului era aprins.
    CREATE TABLE IF NOT EXISTS obd_check (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      at TEXT NOT NULL,
      mil INTEGER NOT NULL
    );

    -- Scanarea OBD (obd-scan.ts): lucrările în ordine (ord); mașina le ia de aici și raportează
    -- progresul (next) prin aceeași coadă ca rezultatele. kind: range = first..last, list = ids
    -- (JSON), loop = recitirea codurilor găsite, la nesfârșit (round = a câta trecere).
    CREATE TABLE IF NOT EXISTS obd_scan_job (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ord REAL NOT NULL,
      label TEXT NOT NULL,
      tx TEXT NOT NULL,
      rx TEXT NOT NULL,
      svc TEXT NOT NULL,
      kind TEXT NOT NULL,
      first INTEGER,
      last INTEGER,
      ids TEXT,
      next INTEGER NOT NULL DEFAULT 0,
      round INTEGER NOT NULL DEFAULT 0,
      parked INTEGER NOT NULL DEFAULT 0,
      parent INTEGER,
      status TEXT NOT NULL,
      created_at TEXT NOT NULL,
      started_at TEXT,
      updated_at TEXT,
      done_at TEXT
    );
    -- Catalogul: fiecare cod care a răspuns (svc 22 = DID UDS, 01/02/06/09 = OBD standard,
    -- dtc = 03/07/0A). value = datele fără ecou (hex); nrc = refuzul (0x22, 0x33…), 256 = lent /
    -- fără răspuns, NULL = răspuns bun. changes = de câte ori s-a schimbat valoarea.
    CREATE TABLE IF NOT EXISTS obd_code (
      tx TEXT NOT NULL,
      svc TEXT NOT NULL,
      id INTEGER NOT NULL,
      value TEXT,
      nrc INTEGER,
      first_at TEXT NOT NULL,
      last_at TEXT NOT NULL,
      reads INTEGER NOT NULL DEFAULT 1,
      changes INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (tx, svc, id)
    );
    -- Istoricul valorilor: un rând la prima valoare și la fiecare schimbare.
    CREATE TABLE IF NOT EXISTS obd_sample (
      tx TEXT NOT NULL,
      svc TEXT NOT NULL,
      id INTEGER NOT NULL,
      at TEXT NOT NULL,
      value TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_obd_sample ON obd_sample(tx, svc, id, at);

    -- Setări simple (cheie → valoare): obd_enabled = comutatorul „OBD (teste)” („0” = oprit).
    CREATE TABLE IF NOT EXISTS setting (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
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
    // Semnalul mobil al navigației (1.1.64+): internet 0/1/2, tehnologia, canalul, dBm, operatorul.
    ["net", "INTEGER"],
    ["rat", "TEXT"],
    ["ch", "INTEGER"],
    ["sig", "INTEGER"],
    ["op", "TEXT"],
    // Vechimea citirii celulei în secunde (1.1.65+): pe Teyes getAllCellInfo dădea o copie veche.
    ["cell_age", "INTEGER"],
    // Adaptorul OBD2 (1.1.92+): litrii din aerul aspirat de la punctul anterior și câte secunde au
    // fost citite, consumul de acum (L/h), presiunea din galerie, aerul admis, lichidul de răcire, sarcina.
    ["obd_l", "REAL"],
    ["obd_s", "REAL"],
    ["obd_lh", "REAL"],
    ["map", "REAL"],
    ["iat", "REAL"],
    ["ect", "REAL"],
    ["obd_load", "REAL"],
  ]) {
    if (!cols("trip_point").includes(col)) db.exec(`ALTER TABLE trip_point ADD COLUMN ${col} ${type}`);
  }
  // Lucrările de service pe articole (piese, manoperă, reduceri; JSON) și unde s-au făcut.
  if (!cols("service").includes("items")) db.exec(`ALTER TABLE service ADD COLUMN items TEXT`);
  if (!cols("service").includes("shop")) db.exec(`ALTER TABLE service ADD COLUMN shop TEXT`);
  // Categoria fiecărei linii din jurnal (log-category.ts), completată și pentru liniile vechi.
  if (!cols("log").includes("cat")) db.exec(`ALTER TABLE log ADD COLUMN cat TEXT`);
  db.exec(`CREATE INDEX IF NOT EXISTS idx_log_cat ON log(cat, device_at DESC)`);
  const uncategorized = db.prepare(`SELECT id, line FROM log WHERE cat IS NULL`).all() as Array<{ id: number; line: string }>;
  if (uncategorized.length > 0) {
    const set = db.prepare(`UPDATE log SET cat = ? WHERE id = ?`);
    db.exec("BEGIN");
    for (const r of uncategorized) set.run(logCategory(r.line), r.id);
    db.exec("COMMIT");
  }
  return db;
}
