// ---------------------------------------------------------------------------
// Jurnalul de service: lucrările făcute la mașină (schimb de ulei, piese, ITP...) cu data,
// kilometrajul și costul, plus bonurile / facturile / pozele atașate (fișiere în
// data/service/<id>). Costurile intră în raportul lunii. Server-only (node:sqlite).
// ---------------------------------------------------------------------------

import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";

import { readOdometer } from "./car.ts";
import { dataChanged, dataDir, getDb } from "./db.ts";

export interface ServiceFile {
  id: number;
  name: string;
  mime: string;
  size: number;
}

export interface ServiceEntry {
  id: number;
  date: string; // YYYY-MM-DD
  odo: number | null;
  title: string;
  cost: number | null;
  notes: string | null;
  files: ServiceFile[];
}

export interface ServiceInput {
  id?: number;
  date: string;
  odo: number | null; // null = kilometrajul curent
  title: string;
  cost: number | null;
  notes: string | null;
}

const MAX_FILE = 15 * 1024 * 1024;
const MIMES = ["image/jpeg", "image/png", "image/webp", "image/heic", "application/pdf"];

function fileDir(): string {
  return `${dataDir()}/service`;
}

export function readService(): ServiceEntry[] {
  const db = getDb();
  const rows = db
    .prepare(`SELECT id, date, odo, title, cost, notes FROM service ORDER BY date DESC, id DESC`)
    .all() as unknown as Omit<ServiceEntry, "files">[];
  const files = db
    .prepare(`SELECT id, service_id, name, mime, size FROM service_file ORDER BY id`)
    .all() as unknown as Array<ServiceFile & { service_id: number }>;
  return rows.map((r) => ({
    ...r,
    files: files.filter((f) => f.service_id === r.id).map(({ service_id: _, ...f }) => f),
  }));
}

function numOrNull(x: unknown): number | null {
  const n = typeof x === "number" ? x : typeof x === "string" && x.trim() ? Number(x.replace(",", ".")) : NaN;
  return Number.isFinite(n) ? n : null;
}

/** Salvează o lucrare; întoarce id-ul ei (pentru fișierele atașate apoi). */
export function saveService(input: ServiceInput): number {
  const title = String(input.title ?? "").trim().slice(0, 80);
  if (!title) throw new Error("Scrie ce s-a făcut");
  const date = String(input.date ?? "");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error("Data lipsește");
  const cost = numOrNull(input.cost);
  if (cost !== null && (cost < 0 || cost > 100_000)) throw new Error("Costul pare greșit");
  const odoIn = numOrNull(input.odo);
  const odo = odoIn !== null ? Math.round(odoIn) : readOdometer();
  const notes = String(input.notes ?? "").trim().slice(0, 500) || null;
  const db = getDb();
  let id = Number(input.id) || 0;
  if (id) {
    db.prepare(`UPDATE service SET date = ?, odo = ?, title = ?, cost = ?, notes = ? WHERE id = ?`).run(
      date,
      odo,
      title,
      cost,
      notes,
      id,
    );
  } else {
    const r = db
      .prepare(`INSERT INTO service (date, odo, title, cost, notes, created_at) VALUES (?, ?, ?, ?, ?, ?)`)
      .run(date, odo, title, cost, notes, new Date().toISOString());
    id = Number(r.lastInsertRowid);
  }
  dataChanged();
  return id;
}

export function deleteService(id: number): void {
  const db = getDb();
  db.prepare(`DELETE FROM service_file WHERE service_id = ?`).run(id);
  db.prepare(`DELETE FROM service WHERE id = ?`).run(id);
  rmSync(`${fileDir()}/${id}`, { recursive: true, force: true });
  dataChanged();
}

/** Atașează un fișier (bon, factură, poză) la o lucrare. */
export function addServiceFile(serviceId: number, name: string, mime: string, data: Buffer): ServiceFile {
  if (!getDb().prepare(`SELECT id FROM service WHERE id = ?`).get(serviceId)) throw new Error("Lucrarea nu există");
  if (data.length === 0 || data.length > MAX_FILE) throw new Error("Fișierul e gol sau mai mare de 15 MB");
  if (!MIMES.includes(mime)) throw new Error("Doar poze (JPEG, PNG, WEBP, HEIC) sau PDF");
  const clean = name.replace(/[^\w.\- ăâîșțĂÂÎȘȚ]/g, "_").slice(0, 80) || "fisier";
  const r = getDb()
    .prepare(`INSERT INTO service_file (service_id, name, mime, size, created_at) VALUES (?, ?, ?, ?, ?)`)
    .run(serviceId, clean, mime, data.length, new Date().toISOString());
  const id = Number(r.lastInsertRowid);
  mkdirSync(`${fileDir()}/${serviceId}`, { recursive: true });
  writeFileSync(`${fileDir()}/${serviceId}/${id}`, data);
  return { id, name: clean, mime, size: data.length };
}

export function readServiceFile(id: number): { file: ServiceFile; data: Buffer } | null {
  const f = getDb()
    .prepare(`SELECT id, service_id, name, mime, size FROM service_file WHERE id = ?`)
    .get(id) as (ServiceFile & { service_id: number }) | undefined;
  if (!f) return null;
  try {
    return { file: { id: f.id, name: f.name, mime: f.mime, size: f.size }, data: readFileSync(`${fileDir()}/${f.service_id}/${f.id}`) };
  } catch {
    return null;
  }
}

export function deleteServiceFile(id: number): void {
  const f = getDb().prepare(`SELECT service_id FROM service_file WHERE id = ?`).get(id) as
    | { service_id: number }
    | undefined;
  if (!f) return;
  getDb().prepare(`DELETE FROM service_file WHERE id = ?`).run(id);
  rmSync(`${fileDir()}/${f.service_id}/${id}`, { force: true });
}
