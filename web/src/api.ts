// Apelurile către API-ul FaikkitCar (aceleași pe care le folosește aplicația Panel).
// Sesiunea vine din cookie; la 401 aplicația arată ecranul de login.

import type { LogEntry } from "../server/log.ts";
import type { Position, Reminder, ReminderInput } from "../server/car.ts";
import type { FuelSummary, RefuelInput } from "../server/fuel.ts";
import type { Trip, TripPoint } from "../server/trips.ts";

export class Unauthorized extends Error {}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
  });
  if (res.status === 401 && path !== "/api/login") throw new Unauthorized();
  const data = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error(data.error ?? `Eroare ${res.status}`);
  return data;
}

export const api = {
  me: () => call<{ user: boolean }>("GET", "/api/me"),
  login: (user: string, pass: string) => call<{ ok: true }>("POST", "/api/login", { user, pass }),
  logout: () => call<{ ok: true }>("POST", "/api/logout"),
  trips: () => call<Trip[]>("GET", "/api/trips"),
  tripPoints: (start: string, end: string) =>
    call<TripPoint[]>(
      "GET",
      `/api/trips/points?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`,
    ),
  car: () =>
    call<{ position: Position | null; odometer: number | null; reminders: Reminder[] }>(
      "GET",
      "/api/car",
    ),
  saveReminder: (r: ReminderInput) => call<{ ok: true }>("POST", "/api/reminders", r),
  deleteReminder: (id: number) => call<{ ok: true }>("DELETE", `/api/reminders/${id}`),
  reminderDone: (id: number) => call<{ ok: true }>("POST", `/api/reminders/${id}/done`),
  fuel: () => call<FuelSummary>("GET", "/api/fuel"),
  saveRefuel: (r: RefuelInput) => call<{ ok: true }>("POST", "/api/refuels", r),
  deleteRefuel: (id: number) => call<{ ok: true }>("DELETE", `/api/refuels/${id}`),
  log: (eventsOnly: boolean) => call<LogEntry[]>("GET", `/api/log?eventsOnly=${eventsOnly ? 1 : 0}`),
};

export type { FuelSummary, LogEntry, Position, Reminder, ReminderInput, RefuelInput, Trip, TripPoint };

/** „acum 5 min”, „acum 2 h”, „acum 3 zile”. */
export function relativeTime(iso: string): string {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "acum câteva secunde";
  if (s < 3600) return `acum ${Math.round(s / 60)} min`;
  if (s < 86_400) return `acum ${Math.round(s / 3600)} h`;
  const d = Math.round(s / 86_400);
  return d === 1 ? "ieri" : `acum ${d} zile`;
}
