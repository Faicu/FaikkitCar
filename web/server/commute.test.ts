import { describe, expect, it } from "vitest";

import { commute, dayTypeOf, holidayOf } from "./commute.ts";
import type { Trip } from "./trips.ts";

/** Un drum: plecarea și sosirea la ora României (UTC+3 în octombrie). */
function trip(from: string, to: string, date: string, depart: string, arrive: string, warmMin = 0, via: string[] = []): Trip {
  const at = (hm: string) => new Date(`${date}T${hm}:00+03:00`).getTime();
  return {
    idle: false,
    via,
    parts: via.length ? 2 : 1,
    fromPlace: from,
    toPlace: to,
    start: new Date(at(depart) - warmMin * 60_000).toISOString(),
    departAt: new Date(at(depart)).toISOString(),
    arriveAt: new Date(at(arrive)).toISOString(),
  } as Trip;
}

const NOW = Date.parse("2026-10-21T21:00:00+03:00");

describe("commute", () => {
  // Ciclul: zi pe 09, 13, 17; noapte pe 10, 14, 18.
  const trips = [
    trip("Acasă", "Serviciu", "2026-10-09", "07:30", "07:45", 3),
    trip("Acasă", "Serviciu", "2026-10-13", "07:35", "07:52", 3),
    trip("Acasă", "Serviciu", "2026-10-17", "07:38", "08:02", 3), // întârziere
    trip("Serviciu", "Acasă", "2026-10-17", "20:05", "20:17"),
    trip("Acasă", "Serviciu", "2026-10-10", "19:35", "19:47"),
    trip("Acasă", "Serviciu", "2026-10-14", "19:36", "19:48"),
    trip("Acasă", "Serviciu", "2026-10-18", "19:40", "19:51"),
    trip("Serviciu", "Acasă", "2026-10-19", "08:02", "08:14"), // după tura de noapte din 18
    trip("Acasă", "Dedeman", "2026-10-16", "14:00", "14:20"),
    trip("Serviciu", "Acasă", "2026-10-14", "08:05", "09:30", 0, ["Petrom"]), // cu oprire: nu intră în durată
  ];
  const c = commute(trips, NOW)!;

  it("ora de plecare: tura − 15 min − drumul cel mai lung", () => {
    const zi = c.shifts[0].toWork!;
    expect(zi.trips).toBe(3);
    expect(zi.safeMin).toBe(24);
    expect(zi.leaveBy).toBe("07:21");
    expect(zi.late).toBe(1);
    expect(zi.tight).toBe(2);
    expect(zi.warmupMin).toBe(3);
    expect(c.shifts[1].toWork!.leaveBy).toBe("19:33");
  });

  it("întoarcerea ține de tura terminată", () => {
    expect(c.shifts[0].fromWork!.usualArrive).toBe("20:17");
    expect(c.shifts[1].fromWork!.usualLeave).toBe("08:02");
  });

  it("următoarele ture din ciclul de 4 zile", () => {
    // 21.10 a fost zi, deci noapte pe 22, apoi zi pe 25 (după trecerea la ora de iarnă).
    expect(c.next[0]).toMatchObject({
      shift: "Tura de noapte",
      start: "2026-10-22T17:00:00.000Z",
      leaveBy: "2026-10-22T16:33:00.000Z",
    });
    expect(c.next[1].start).toBe("2026-10-25T06:00:00.000Z");
  });

  it("drumurile cu opriri nu intră în durată", () => {
    expect(c.shifts[1].fromWork!.trips).toBe(1);
  });

  it("zilele lucrătoare și cele libere separat", () => {
    // 17.10 e sâmbătă (cu întârzierea), 09 și 13 lucrătoare.
    expect(c.shifts[0].toWork!.byDay.work).toEqual({ trips: 2, driveMin: 16, safeMin: 17, leaveBy: "07:28" });
    expect(c.shifts[0].toWork!.byDay.free).toBeNull();
    // Noaptea: 10.10 sâmbătă, 18.10 duminică, 14.10 miercuri.
    expect(c.shifts[1].toWork!.byDay.free).toEqual({ trips: 2, driveMin: 11.5, safeMin: 12, leaveBy: "19:33" });
    // 22.10 e joi: o singură noapte lucrătoare, deci ora vine din toate drumurile turei.
    expect(c.next[0]).toMatchObject({ dayType: "work", byDay: false, trips: 3 });
    // 25.10 e duminică, iar ziua liberă n-are destule drumuri: tot din toate.
    expect(c.next[1]).toMatchObject({ dayType: "free", byDay: false, leaveBy: "2026-10-25T05:21:00.000Z" });
  });

  it("ora turei de weekend din drumurile de weekend", () => {
    const more = [
      ...trips,
      trip("Acasă", "Serviciu", "2026-10-04", "07:45", "07:51"), // duminică
      trip("Acasă", "Serviciu", "2026-10-10", "07:44", "07:50"), // sâmbătă (pe lângă noapte, doar pentru test)
    ];
    const w = commute(more, NOW)!;
    expect(w.shifts[0].toWork!.byDay.free).toMatchObject({ trips: 3, leaveBy: "07:21" }); // p90 = 24 min (17.10)
    const w2 = commute(more.filter((t) => !t.start.startsWith("2026-10-17T04")), NOW)!;
    expect(w2.shifts[0].toWork!.byDay.free).toMatchObject({ trips: 2, safeMin: 6, leaveBy: "07:39" });
    expect(w2.next.find((n) => n.shift === "Tura de zi")).toMatchObject({
      dayType: "free",
      byDay: true,
      leaveBy: "2026-10-25T05:39:00.000Z",
      driveMin: 6,
      safeMin: 6,
      trips: 2,
    });
  });

  it("sărbătorile legale (și Paștele ortodox) sunt zile libere", () => {
    const day = (iso: string) => Date.parse(`${iso}T00:00:00Z`) / 86_400_000;
    expect(holidayOf(day("2026-11-30"))).toBe("Sf. Andrei");
    expect(holidayOf(day("2026-04-13"))).toBe("Paștele");
    expect(holidayOf(day("2027-05-03"))).toBe("Paștele");
    expect(holidayOf(day("2026-05-31"))).toBe("Rusaliile");
    expect(dayTypeOf(day("2026-12-01"))).toBe("free"); // marți, Ziua Națională
    expect(dayTypeOf(day("2026-11-24"))).toBe("work"); // marți
    expect(dayTypeOf(day("2026-11-22"))).toBe("free"); // duminică
  });

  it("fără drumuri spre serviciu, nimic", () => {
    expect(commute([trips[8]], NOW)).toBeNull();
  });
});
