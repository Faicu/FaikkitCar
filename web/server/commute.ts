// ---------------------------------------------------------------------------
// Drumul la serviciu, pe ture de 12/24: zi (08:00–20:00), noapte (20:00–08:00), liber, liber.
// Din drumurile Acasă → Serviciu și înapoi: cât durează pe fiecare tură, până la ce minut
// trebuie să pleci ca să ajungi cu cel puțin 15 minute înainte și care e următoarea tură.
// Dimineața drumul diferă mult între zilele lucrătoare și weekend / sărbători (19.11: 12–35 min
// față de 5–7 min), deci ora de plecare a unei ture ține cont și de ziua ei. Doar drumurile
// directe (fără opriri pe drum: alimentare, magazin, service). Pur, testat în commute.test.ts.
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
/** De la atâtea drumuri în același fel de zi (lucrătoare / liberă) ora ține cont de el. */
const DAY_MIN_TRIPS = 2;

export type DayType = "work" | "free";

/** Drumurile spre serviciu ale unei ture într-un fel de zi. */
export interface DayCommute {
  trips: number;
  driveMin: number;
  safeMin: number;
  leaveBy: string;
}

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
    // Pe zile lucrătoare și libere (weekend, sărbători legale); null sub DAY_MIN_TRIPS drumuri.
    byDay: Record<DayType, DayCommute | null>;
  } | null;
  fromWork: {
    trips: number;
    driveMin: number; // doar mersul și traficul (fără așteptatul cu motorul pornit pe drum)
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
  // `driveMin`/`safeMin`/`trips` sunt cele folosite pentru `leaveBy` (ale felului de zi, dacă
  // are destule drumuri, altfel ale turei); `holiday` = numele sărbătorii legale.
  next: Array<{
    shift: string;
    start: string;
    leaveBy: string | null;
    dayType: DayType;
    holiday: string | null;
    byDay: boolean; // ora ține de felul zilei (altfel de toate drumurile turei)
    trips: number;
    driveMin: number | null;
    safeMin: number | null;
  }>;
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

/** Paștele ortodox (Meeus, calendarul iulian + 13 zile, valabil 1900–2099), ca zi de la 1970. */
function orthodoxEaster(year: number): number {
  const a = year % 4;
  const b = year % 7;
  const c = year % 19;
  const d = (19 * c + 15) % 30;
  const e = (2 * a + 4 * b - d + 34) % 7;
  const month = Math.floor((d + e + 114) / 31);
  const day = ((d + e + 114) % 31) + 1;
  return Date.UTC(year, month - 1, day) / 86_400_000 + 13;
}

const FIXED_HOLIDAYS: Record<string, string> = {
  "01-01": "Anul Nou",
  "01-02": "Anul Nou",
  "01-06": "Boboteaza",
  "01-07": "Sf. Ioan",
  "01-24": "Unirea Principatelor",
  "05-01": "1 Mai",
  "06-01": "Ziua Copilului",
  "08-15": "Adormirea Maicii Domnului",
  "11-30": "Sf. Andrei",
  "12-01": "Ziua Națională",
  "12-25": "Crăciunul",
  "12-26": "Crăciunul",
};

/** Sărbătoarea legală din ziua `day` (zile de la 1970), dacă e. */
export function holidayOf(day: number): string | null {
  const d = new Date(day * 86_400_000);
  const key = `${String(d.getUTCMonth() + 1).padStart(2, "0")}-${String(d.getUTCDate()).padStart(2, "0")}`;
  if (FIXED_HOLIDAYS[key]) return FIXED_HOLIDAYS[key];
  const e = orthodoxEaster(d.getUTCFullYear());
  if (day === e - 2) return "Vinerea Mare";
  if (day === e || day === e + 1) return "Paștele";
  if (day === e + 49 || day === e + 50) return "Rusaliile";
  return null;
}

/** Zi lucrătoare sau liberă (sâmbăta, duminica, sărbătorile legale). */
export function dayTypeOf(day: number): DayType {
  const wd = new Date(day * 86_400_000).getUTCDay();
  return wd === 0 || wd === 6 || holidayOf(day) ? "free" : "work";
}

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
  // Cu opriri pe drum (alimentare, magazin) durata nu e a drumului: ele contează doar la ciclu.
  const direct = (t: Trip) => !t.via?.length && (t.parts ?? 1) === 1;
  const to = recent.filter((t) => t.fromPlace === HOME && t.toPlace === WORK);
  const from = recent.filter((t) => t.fromPlace === WORK && t.toPlace === HOME);
  if (!to.length) return null;
  const votes = new Map<number, number>(); // ziua din ciclu a turei de zi → câte drumuri o confirmă
  const vote = (startMs: number, i: number) => {
    const k = mod(local(startMs).day - i, CYCLE_DAYS);
    votes.set(k, (votes.get(k) ?? 0) + 1);
  };

  const shifts: ShiftCommute[] = SHIFTS.map((sh, i) => {
    const going: Array<{ depart: number; arrive: number; warm: number; startMs: number; type: DayType }> = [];
    for (const t of to) {
      const arrive = Date.parse(t.arriveAt);
      const startMs = shiftStartFor(arrive, sh.start, 0, TO_WORK_WINDOW);
      if (startMs === null) continue;
      vote(startMs, i);
      if (!direct(t)) continue;
      const type = dayTypeOf(local(startMs).day);
      going.push({ depart: Date.parse(t.departAt), arrive, warm: Date.parse(t.departAt) - Date.parse(t.start), startMs, type });
    }
    const back: Array<{ depart: number; arrive: number; drive: number; endMs: number }> = [];
    for (const t of from) {
      const depart = Date.parse(t.departAt);
      const startMs = shiftStartFor(depart, sh.start, SHIFT_MIN, FROM_WORK_WINDOW);
      if (startMs === null) continue;
      vote(startMs, i);
      if (!direct(t)) continue;
      const arrive = Date.parse(t.arriveAt);
      // La întoarcere se așteaptă des cu motorul pornit (~10 min) între plecare și sosire.
      const drive = t.movingMin !== undefined ? t.movingMin + (t.trafficMin ?? 0) : (arrive - depart) / 60_000;
      back.push({ depart, arrive, drive, endMs: startMs + SHIFT_MIN * 60_000 });
    }
    const min = (ms: number) => ms / 60_000;
    let toWork: ShiftCommute["toWork"] = null;
    if (going.length) {
      const drive = going.map((g) => min(g.arrive - g.depart));
      const day = (type: DayType): DayCommute | null => {
        const d = going.filter((g) => g.type === type).map((g) => min(g.arrive - g.depart));
        if (d.length < DAY_MIN_TRIPS) return null;
        const safe = Math.ceil(p90(d));
        return { trips: d.length, driveMin: round1(median(d)), safeMin: safe, leaveBy: hhmm(sh.start - MARGIN_MIN - safe) };
      };
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
        byDay: { work: day("work"), free: day("free") },
      };
    }
    const end = sh.start + SHIFT_MIN;
    const fromWork: ShiftCommute["fromWork"] = back.length
      ? {
          trips: back.length,
          driveMin: round1(median(back.map((b) => b.drive))),
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
        const tw = shifts[i].toWork;
        const dayType = dayTypeOf(d);
        const byDay = tw?.byDay[dayType] ?? null;
        const use = byDay ?? (tw?.leaveBy ? tw : null);
        next.push({
          shift: sh.name,
          start: new Date(s).toISOString(),
          leaveBy: use ? new Date(s - (MARGIN_MIN + use.safeMin) * 60_000).toISOString() : null,
          dayType,
          holiday: holidayOf(d),
          byDay: byDay !== null,
          trips: use?.trips ?? tw?.trips ?? 0,
          driveMin: use?.driveMin ?? null,
          safeMin: use?.safeMin ?? null,
        });
      });
    }
  }
  return { home: HOME, work: WORK, marginMin: MARGIN_MIN, shifts, next };
}
