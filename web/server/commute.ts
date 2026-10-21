// ---------------------------------------------------------------------------
// Drumul la serviciu, pe ture de 12/24: zi (08:00–20:00), noapte (20:00–08:00), liber, liber.
// Din drumurile Acasă → Serviciu și înapoi: cât durează pe fiecare tură, până la ce minut
// trebuie să pleci ca să ajungi cu cel puțin 15 minute înainte și care e următoarea tură.
// Pur (fără bază de date), testat în commute.test.ts.
// ---------------------------------------------------------------------------

import type { Trip } from "./trips.ts";

const TZ = "Europe/Bucharest";
export const HOME = "Acasă";
export const WORK = "Serviciu";
/** Cu atât înainte de începerea turei vrei să fii la serviciu. */
export const MARGIN_MIN = 15;
const SHIFT_MIN = 12 * 60;
/** Turele, în ordinea din ciclu (ziua 0 = zi, ziua 1 = noapte, apoi `CYCLE_DAYS - 2` libere). */
export const SHIFTS = [
  { name: "Tura de zi", start: 8 * 60 },
  { name: "Tura de noapte", start: 20 * 60 },
];
const CYCLE_DAYS = 4;
const DAYS = 90;
// Un drum spre serviciu ține de tura care începe la cel mult 3 h după sosire (sau a început
// de cel mult 1 h: întârziere); cel de întoarcere, de tura terminată cu ≤ 1 h înainte / ≤ 3 h după.
const TO_WORK_WINDOW = [-180, 60];
const FROM_WORK_WINDOW = [-60, 180];
/** Sub atâtea drumuri pe o tură nu se dă o oră de plecare. */
const MIN_TRIPS = 3;

export interface ShiftCommute {
  name: string;
  start: string; // "08:00"
  end: string;
  toWork: {
    trips: number;
    driveMin: number; // mediana, de la plecarea de pe loc la oprirea la serviciu
    safeMin: number; // cât să-ți iei (percentila 90: și zilele proaste)
    leaveBy: string | null; // ora până la care să pleci (null sub MIN_TRIPS drumuri)
    usualLeave: string; // cum pleci de obicei (mediane)
    usualArrive: string;
    warmupMin: number; // cât stai cu motorul pornit înainte să pleci (mediana)
    tight: number; // ajuns cu mai puțin de MARGIN_MIN înainte (inclusiv întârzierile)
    late: number; // ajuns după începerea turei
  } | null;
  fromWork: {
    trips: number;
    driveMin: number;
    usualLeave: string;
    usualArrive: string;
  } | null;
}

export interface Commute {
  home: string;
  work: string;
  marginMin: number;
  shifts: ShiftCommute[];
  // Turele următoare (din ciclul dedus din drumuri), cu ora până la care să pleci.
  next: Array<{ shift: string; start: string; leaveBy: string | null }>;
}

const fmt = new Intl.DateTimeFormat("en-CA", {
  timeZone: TZ,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/** Data (zile de la 1970) și minutul din zi, la ora României. */
function local(ms: number): { day: number; min: number } {
  const p = Object.fromEntries(fmt.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return {
    day: Date.UTC(+p.year, +p.month - 1, +p.day) / 86_400_000,
    min: +p.hour * 60 + +p.minute,
  };
}

/** Momentul `min` din ziua `day`, ora României (ora de vară inclusă). */
function atLocal(day: number, min: number): number {
  let ms = day * 86_400_000 + min * 60_000;
  for (let i = 0; i < 2; i++) {
    const l = local(ms);
    ms += day * 86_400_000 + min * 60_000 - (l.day * 86_400_000 + l.min * 60_000);
  }
  return ms;
}

const hhmm = (min: number) => {
  const m = ((Math.floor(min) % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
};
const mod = (a: number, n: number) => ((a % n) + n) % n;
const median = (xs: number[]) => {
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};
/** Percentila 90 (rangul cel mai apropiat): cu sub 10 drumuri e cel mai lung. */
const p90 = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.ceil(xs.length * 0.9) - 1];
const round1 = (x: number) => Math.round(x * 10) / 10;

/** Începutul turei `shift` de care ține momentul `ms`, dacă `ms - început` e în fereastră. */
function shiftStartFor(ms: number, start: number, offsetMin: number, window: number[]): number | null {
  const today = local(ms).day;
  for (const d of [today - 1, today, today + 1]) {
    const s = atLocal(d, start);
    const delta = (ms - s - offsetMin * 60_000) / 60_000;
    if (delta >= window[0] && delta <= window[1]) return s;
  }
  return null;
}

export function commute(trips: Trip[], now = Date.now()): Commute | null {
  const cutoff = new Date(now - DAYS * 86_400_000).toISOString();
  const recent = trips.filter((t) => !t.idle && t.start >= cutoff);
  const to = recent.filter((t) => t.fromPlace === HOME && t.toPlace === WORK);
  const from = recent.filter((t) => t.fromPlace === WORK && t.toPlace === HOME);
  if (!to.length) return null;
  const votes = new Map<number, number>(); // ziua din ciclu a turei de zi → câte drumuri o confirmă
  const vote = (startMs: number, i: number) => {
    const k = mod(local(startMs).day - i, CYCLE_DAYS);
    votes.set(k, (votes.get(k) ?? 0) + 1);
  };

  const shifts: ShiftCommute[] = SHIFTS.map((sh, i) => {
    const going: Array<{ depart: number; arrive: number; warm: number; startMs: number }> = [];
    for (const t of to) {
      const arrive = Date.parse(t.arriveAt);
      const startMs = shiftStartFor(arrive, sh.start, 0, TO_WORK_WINDOW);
      if (startMs === null) continue;
      going.push({ depart: Date.parse(t.departAt), arrive, warm: Date.parse(t.departAt) - Date.parse(t.start), startMs });
      vote(startMs, i);
    }
    const back: Array<{ depart: number; arrive: number; endMs: number }> = [];
    for (const t of from) {
      const depart = Date.parse(t.departAt);
      const startMs = shiftStartFor(depart, sh.start, SHIFT_MIN, FROM_WORK_WINDOW);
      if (startMs === null) continue;
      back.push({ depart, arrive: Date.parse(t.arriveAt), endMs: startMs + SHIFT_MIN * 60_000 });
      vote(startMs, i);
    }
    const min = (ms: number) => ms / 60_000;
    let toWork: ShiftCommute["toWork"] = null;
    if (going.length) {
      const drive = going.map((g) => min(g.arrive - g.depart));
      const margins = going.map((g) => min(g.startMs - g.arrive));
      const safe = Math.ceil(p90(drive));
      toWork = {
        trips: going.length,
        driveMin: round1(median(drive)),
        safeMin: safe,
        leaveBy: going.length >= MIN_TRIPS ? hhmm(sh.start - MARGIN_MIN - safe) : null,
        usualLeave: hhmm(sh.start + median(going.map((g) => min(g.depart - g.startMs)))),
        usualArrive: hhmm(sh.start - median(margins)),
        warmupMin: round1(median(going.map((g) => min(g.warm)))),
        tight: margins.filter((m) => m < MARGIN_MIN).length,
        late: margins.filter((m) => m < 0).length,
      };
    }
    const end = sh.start + SHIFT_MIN;
    const fromWork: ShiftCommute["fromWork"] = back.length
      ? {
          trips: back.length,
          driveMin: round1(median(back.map((b) => min(b.arrive - b.depart)))),
          usualLeave: hhmm(end + median(back.map((b) => min(b.depart - b.endMs)))),
          usualArrive: hhmm(end + median(back.map((b) => min(b.arrive - b.endMs)))),
        }
      : null;
    return { name: sh.name, start: hhmm(sh.start), end: hhmm(end), toWork, fromWork };
  });

  // Ciclul: ziua (mod 4) în care pică tura de zi, după cele mai multe drumuri.
  const best = [...votes.entries()].sort((a, b) => b[1] - a[1])[0];
  const next: Commute["next"] = [];
  if (best && best[1] >= MIN_TRIPS) {
    const today = local(now).day;
    for (let d = today - 1; d <= today + CYCLE_DAYS + 1 && next.length < 3; d++) {
      SHIFTS.forEach((sh, i) => {
        if (mod(d - i, CYCLE_DAYS) !== best[0]) return;
        const s = atLocal(d, sh.start);
        // Și tura începută de cel mult o oră: lista se recalculează din oră în oră, iar
        // clientul o ascunde pe cea trecută.
        if (s <= now - 3_600_000 || next.length >= 3) return;
        const leave = shifts[i].toWork?.leaveBy ? s - (MARGIN_MIN + shifts[i].toWork!.safeMin) * 60_000 : null;
        next.push({
          shift: sh.name,
          start: new Date(s).toISOString(),
          leaveBy: leave === null ? null : new Date(leave).toISOString(),
        });
      });
    }
  }
  return { home: HOME, work: WORK, marginMin: MARGIN_MIN, shifts, next };
}
