import { describe, expect, it } from "vitest";

import { analyze, canScale, type PointRow } from "./trip-math.ts";

const T0 = Date.parse("2026-10-02T04:40:00Z");

/** Un punct la `s` secunde de la început; poziția pe o linie spre est (1 grad lon ≈ 79,4 km). */
function p(s: number, speed: number, lonKm: number | null, extra: Partial<PointRow> = {}): PointRow {
  return {
    device_at: new Date(T0 + s * 1000).toISOString(),
    lat: lonKm === null ? null : 44.44,
    lon: lonKm === null ? null : 26 + lonKm / 79.4,
    acc: 4,
    gps_speed: speed,
    can_speed: speed,
    rpm: speed > 0 ? 1500 : 750,
    volt: 14,
    temp: 12,
    odo: 245000,
    fuel: 37,
    cons: null,
    crank: null,
    ac: null,
    belt: null,
    ...extra,
  };
}

describe("analyze", () => {
  it("distanța din GPS în mers, fără zgomotul de pe loc", () => {
    const rows = [
      p(0, 0, 0),
      p(30, 0, 0.01), // zgomot GPS pe loc: nu se adună
      p(60, 0, 0),
      p(65, 36, 0.05),
      p(70, 36, 0.1),
      p(75, 36, 0.15),
      p(80, 0, 0.17),
    ];
    const m = analyze(rows);
    expect(m.distanceKm).toBeCloseTo(0.2, 1);
    expect(m.standMin).toBeCloseTo(1.1, 1); // la plecare, până la primul punct în mers (65 s)
    expect(m.trafficStops).toBe(0);
    // Mers + opriri = durata, fără să se numere nimic de două ori.
    // (fiecare valoare e rotunjită la 0,1 min, deci suma poate diferi cu puțin)
    expect(Math.abs(m.movingMin + m.standMin + m.trafficMin + m.stopMin - m.durationMin)).toBeLessThan(0.15);
  });

  it("fără GPS, distanța din viteză (CAN × scală)", () => {
    const rows = [p(0, 0, null), p(5, 72, null, { gps_speed: null }), p(10, 72, null, { gps_speed: null }), p(15, 0, null)];
    // 5 s × 36 + 5 s × 72 + 5 s × 36 km/h = 0,2 km din CAN, × 0,95
    expect(analyze(rows, 0.95).distanceKm).toBeCloseTo(0.2, 1);
  });

  it("oprirea între două porțiuni de mers e în trafic, chiar de 4–5 minute", () => {
    const rows = [
      p(0, 30, 0),
      p(5, 30, 0.04),
      p(10, 0, 0.06),
      p(40, 0, 0.06),
      p(280, 0, 0.06),
    ];
    // Un gol de 240 s fără puncte, cu mașina pe loc = motorul oprit, deci nu e trafic.
    expect(analyze(rows).stopMin).toBeCloseTo(4, 0);
    const busy = [p(0, 30, 0), p(5, 30, 0.04), p(10, 0, 0.06)];
    for (let s = 40; s <= 280; s += 30) busy.push(p(s, 0, 0.06));
    busy.push(p(285, 20, 0.08), p(290, 30, 0.12));
    const m = analyze(busy);
    expect(m.trafficStops).toBe(1);
    expect(m.trafficMin).toBeCloseTo(4.6, 1);
    expect(m.stopMin).toBe(0);
  });

  it("o oprire cu motorul oprit apare la opriri și nu intră în viteza medie", () => {
    const rows = [
      p(0, 36, 0),
      p(5, 36, 0.05),
      p(10, 0, 0.06),
      p(130, 0, 0.06), // 2 minute cu motorul oprit (magazin)
      p(135, 36, 0.1),
      p(140, 36, 0.15),
    ];
    const m = analyze(rows);
    expect(m.stops).toHaveLength(1);
    expect(m.stops[0].minutes).toBe(2);
    expect(m.stopMin).toBe(2);
    expect(m.durationMin).toBeCloseTo(2.3, 1);
  });

  it("consumul bordului (c1033) pe porțiunile în mers", () => {
    const rows: PointRow[] = [];
    for (let i = 0; i <= 12; i++) rows.push(p(i * 5, 36, i * 0.05, { cons: 60 }));
    expect(analyze(rows).boardLPer100).toBe(6); // 60 = 6,0 L/100 km, pe 0,6 km
  });
});

describe("bateria", () => {
  it("căderea de la pornire separat de încărcarea în mers", () => {
    const rows = [
      p(0, 0, 0, { volt: 10.45 }),
      p(30, 0, 0, { volt: 14.4 }),
      p(60, 0, 0, { volt: 14.5 }),
      p(65, 30, 0.04, { volt: 14.3 }),
      p(70, 30, 0.08, { volt: 14.4 }),
    ];
    const m = analyze(rows);
    expect(m.crankVolt).toBe(10.45);
    expect(m.runVolt).toBe(14.4);
  });

  it("fără cădere prinsă (alternatorul încărca deja), nu inventează o valoare la pornire", () => {
    const rows = [p(0, 0, 0, { volt: 14 }), p(30, 0, 0, { volt: 14.4 }), p(35, 30, 0.04, { volt: 14.3 })];
    expect(analyze(rows).crankVolt).toBeNull();
  });

  it("măsurarea din mașină are prioritate", () => {
    const rows = [p(0, 0, 0, { volt: 14, crank: 9.8 }), p(35, 30, 0.04, { volt: 14.3 })];
    expect(analyze(rows).crankVolt).toBe(9.8);
  });
});

describe("clima și centura", () => {
  it("minutele cu AC și cele în mers fără centură", () => {
    const rows = [
      p(0, 0, 0, { ac: 1, belt: 1 }),
      p(30, 30, 0.04, { ac: 1, belt: 0 }),
      p(90, 30, 0.5, { ac: 0, belt: 0 }),
      p(120, 30, 0.75, { ac: 0, belt: 1 }),
    ];
    const m = analyze(rows);
    expect(m.acMin).toBe(1.5); // 0–90 s
    expect(m.noBeltMin).toBe(1.5); // 30–120 s, în mers
  });

  it("drumurile vechi, fără date de climă: null, nu 0", () => {
    const m = analyze([p(0, 30, 0), p(5, 30, 0.04)]);
    expect(m.acMin).toBeNull();
    expect(m.noBeltMin).toBeNull();
  });
});

describe("canScale", () => {
  it("implicit sub 5 km", () => {
    expect(canScale([p(0, 36, 0), p(5, 36, 0.05)])).toBe(0.95);
  });
});
