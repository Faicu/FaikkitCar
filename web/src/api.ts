// Apelurile către API-ul FaikkitCar (aceleași pe care le folosește aplicația Panel).
// Sesiunea vine din cookie; la 401 aplicația arată ecranul de login.

import type { LogEntry } from "../server/log.ts";
import type { Position, Reminder, ReminderInput } from "../server/car.ts";
import type { FuelSummary, RefuelInput } from "../server/fuel.ts";
import type { Live } from "../server/live.ts";
import type { Place, PlaceInput } from "../server/places.ts";
import type { MonthStats, PeriodStats, Stats } from "../server/stats.ts";
import type { Trip, TripPoint } from "../server/trips.ts";

export class Unauthorized extends Error {}

async function call<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const res = await fetch(path, {
    method,
    signal,
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
  // Cu `after`, serverul răspunde abia la o stare nouă de la mașină (cel mult 25 s).
  live: (after?: string | null, signal?: AbortSignal) =>
    call<Live>("GET", after ? `/api/live?after=${encodeURIComponent(after)}` : "/api/live", undefined, signal),
  stats: () => call<Stats>("GET", "/api/stats"),
  trips: () => call<Trip[]>("GET", "/api/trips"),
  joinTrips: (start: string, end: string) => call<{ ok: true }>("POST", "/api/trips/join", { start, end }),
  splitTrip: (start: string) => call<{ ok: true }>("POST", "/api/trips/split", { start }),
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
  places: () => call<Place[]>("GET", "/api/places"),
  savePlace: (p: PlaceInput) => call<{ ok: true }>("POST", "/api/places", p),
  deletePlace: (id: number) => call<{ ok: true }>("DELETE", `/api/places/${id}`),
  fuel: () => call<FuelSummary>("GET", "/api/fuel"),
  saveRefuel: (r: RefuelInput) => call<{ ok: true }>("POST", "/api/refuels", r),
  deleteRefuel: (id: number) => call<{ ok: true }>("DELETE", `/api/refuels/${id}`),
  panelApk: () => call<{ versionCode: number; versionName?: string; size?: number }>("GET", "/api/panel/apk"),
  log: (eventsOnly: boolean) => call<LogEntry[]>("GET", `/api/log?eventsOnly=${eventsOnly ? 1 : 0}`),
};

export type { FuelSummary, Live, LogEntry, MonthStats, PeriodStats, Place, Position, Reminder, ReminderInput, RefuelInput, Trip, TripPoint };

/** „acum 5 min”, „acum 2 h”, „acum 3 zile”. */
export function relativeTime(iso: string): string {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "acum câteva secunde";
  if (s < 3600) return `acum ${Math.round(s / 60)} min`;
  if (s < 86_400) return `acum ${Math.round(s / 3600)} h`;
  const d = Math.round(s / 86_400);
  return d === 1 ? "ieri" : `acum ${d} zile`;
}

// Formatări comune paginilor.

export function day(iso: string): string {
  return new Date(iso).toLocaleDateString("ro-RO", { weekday: "short", day: "2-digit", month: "short" });
}

export function hm(iso: string): string {
  return new Date(iso).toLocaleTimeString("ro-RO", { hour: "2-digit", minute: "2-digit" });
}

export function duration(min: number): string {
  if (min < 60) return `${Math.round(min)} min`;
  return `${Math.floor(min / 60)} h ${Math.round(min % 60)} min`;
}

/** Sub 10 minute, cu secunde („1 min 30 s”, „45 s”); altfel ca duration. */
export function shortDuration(min: number): string {
  if (min >= 10) return duration(min);
  const sec = Math.round(min * 60);
  if (sec < 60) return `${sec} s`;
  return sec % 60 ? `${Math.floor(sec / 60)} min ${sec % 60} s` : `${sec / 60} min`;
}

export function liters(n: number): string {
  return `${n.toLocaleString("ro-RO", { maximumFractionDigits: n < 10 ? 2 : 1 })} L`;
}

export function lei(n: number): string {
  return `${n.toLocaleString("ro-RO", { maximumFractionDigits: n < 100 ? 2 : 0 })} lei`;
}

export function num(n: number, digits = 1): string {
  return n.toLocaleString("ro-RO", { maximumFractionDigits: digits });
}

/** „Acasă → Serviciu”, sau "" dacă niciun capăt nu e un loc salvat. */
export function route(t: { fromPlace: string | null; toPlace: string | null }): string {
  if (!t.fromPlace && !t.toPlace) return "";
  return `${t.fromPlace ?? "…"} → ${t.toPlace ?? "…"}`;
}
