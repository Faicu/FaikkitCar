import { describe, expect, it } from "vitest";

import { commute } from "./commute.ts";
import type { Trip } from "./trips.ts";

/** Un drum: plecarea și sosirea la ora României (UTC+3 în octombrie). */
function trip(from: string, to: string, date: string, depart: string, arrive: string, warmMin = 0): Trip {
  const at = (hm: string) => new Date(`${date}T${hm}:00+03:00`).getTime();
  return {
    idle: false,
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
    expect(c.next[0]).toEqual({
      shift: "Tura de noapte",
      start: "2026-10-22T17:00:00.000Z",
      leaveBy: "2026-10-22T16:33:00.000Z",
    });
    expect(c.next[1].start).toBe("2026-10-25T06:00:00.000Z");
  });

  it("fără drumuri spre serviciu, nimic", () => {
    expect(commute([trips[8]], NOW)).toBeNull();
  });
});
