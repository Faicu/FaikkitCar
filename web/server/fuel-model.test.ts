import { describe, expect, it } from "vitest";

import {
  calibrate,
  estimateFuel,
  levelConsumption,
  levelRefills,
  priceAt,
  type FuelRefuel,
  type FuelSample,
} from "./fuel-model.ts";

function steady(minutes: number, speed: number, rpm: number, stepS = 5): FuelSample[] {
  const out: FuelSample[] = [];
  for (let s = 0; s <= minutes * 60; s += stepS) out.push({ t: s * 1000, speed, rpm });
  return out;
}

function refuel(id: number, at: string, liters: number, full: boolean, odo: number | null = null) {
  return { id, at, odo, liters, price: 7, full } satisfies FuelRefuel;
}

describe("estimateFuel", () => {
  it("relanti o oră ≈ 0,6 L, totul pe loc", () => {
    const e = estimateFuel(steady(60, 0, 750, 30));
    expect(e.liters).toBeGreaterThan(0.5);
    expect(e.liters).toBeLessThan(0.7);
    expect(e.idleMin).toBeCloseTo(60, 0);
  });

  it("90 km/h constant dă un consum plauzibil (4–8 L/100 km)", () => {
    const e = estimateFuel(steady(60, 90, 2500));
    const per100 = (e.liters / 90) * 100;
    expect(per100).toBeGreaterThan(4);
    expect(per100).toBeLessThan(8);
    expect(e.idleMin).toBe(0);
  });

  it("motorul oprit și golurile mari nu consumă", () => {
    expect(estimateFuel(steady(10, 0, 0)).liters).toBe(0);
    const gap: FuelSample[] = [
      { t: 0, speed: 50, rpm: 2000 },
      { t: 10 * 60_000, speed: 50, rpm: 2000 },
    ];
    expect(estimateFuel(gap).liters).toBe(0);
  });

  it("accelerarea costă mai mult decât frânarea înapoi", () => {
    const up: FuelSample[] = [
      { t: 0, speed: 0, rpm: 2500 },
      { t: 10_000, speed: 50, rpm: 2500 },
    ];
    const down: FuelSample[] = [
      { t: 0, speed: 50, rpm: 2500 },
      { t: 10_000, speed: 0, rpm: 2500 },
    ];
    expect(estimateFuel(up).liters).toBeGreaterThan(estimateFuel(down).liters * 3);
  });
});

describe("calibrate", () => {
  it("fără două plinuri rămâne necalibrat, factor 1", () => {
    const c = calibrate(
      [refuel(1, "2026-10-01", 30, true)],
      [{ start: "2026-10-02", modelLiters: 5 }],
    );
    expect(c).toMatchObject({ factor: 1, calibrated: false, intervals: [] });
  });

  it("plin → parțial → plin: litrii de după primul plin, raportați la estimare", () => {
    const c = calibrate(
      [
        refuel(1, "2026-10-01T10:00:00Z", 40, true, 1000),
        refuel(2, "2026-10-05T10:00:00Z", 10, false),
        refuel(3, "2026-10-10T10:00:00Z", 20, true, 1500),
      ],
      [
        { start: "2026-09-30T10:00:00Z", modelLiters: 99 }, // înainte de primul plin
        { start: "2026-10-02T10:00:00Z", modelLiters: 10 },
        { start: "2026-10-06T10:00:00Z", modelLiters: 10 },
        { start: "2026-10-11T10:00:00Z", modelLiters: 99 }, // după ultimul plin
      ],
    );
    expect(c.calibrated).toBe(true);
    expect(c.intervals).toHaveLength(1);
    expect(c.intervals[0]).toMatchObject({ liters: 30, modelLiters: 20, km: 500, lPer100: 6 });
    expect(c.factor).toBeCloseTo(1.5);
  });

  it("factorul e limitat când datele lipsesc", () => {
    const c = calibrate(
      [refuel(1, "2026-10-01", 40, true), refuel(2, "2026-10-10", 40, true)],
      [{ start: "2026-10-02", modelLiters: 1 }],
    );
    expect(c.factor).toBe(2.5);
  });
});

describe("priceAt", () => {
  const list = [
    { ...refuel(1, "2026-10-01", 30, true), price: 7 },
    { ...refuel(2, "2026-10-10", 30, true), price: 7.5 },
  ];
  it("ia ultima alimentare de dinainte", () => {
    expect(priceAt(list, "2026-10-05")).toBe(7);
    expect(priceAt(list, "2026-10-12")).toBe(7.5);
  });
  it("înainte de prima alimentare folosește prima", () => {
    expect(priceAt(list, "2026-09-01")).toBe(7);
    expect(priceAt([], "2026-09-01")).toBeNull();
  });
});

describe("levelConsumption", () => {
  const r = (t: string, fuel: number, odo: number) => ({ t, fuel, odo });

  const real = [
    r("2026-10-01T17:01:00Z", 22, 245070),
    r("2026-10-01T17:12:00Z", 21, 245071),
    r("2026-10-01T17:32:00Z", 38, 245073),
    r("2026-10-01T17:35:00Z", 37, 245074),
  ];

  it("drumul real din 01.10, fără bon: saltul din mediana nivelurilor (21 → 37,5)", () => {
    expect(levelConsumption(real)).toMatchObject({ liters: 1, refills: 16.5, km: 4, lPer100: null });
  });

  it("drumul real din 01.10, cu bonul de 16 L din jurnal", () => {
    expect(levelConsumption(real, [{ at: "2026-10-01T17:32:00Z", liters: 16 }])).toMatchObject({
      liters: 0.5,
      refills: 16,
    });
  });

  it("oscilațiile de 1 L se anulează: capetele sunt mediane pe 10 minute", () => {
    const c = levelConsumption([
      r("2026-10-01T10:00:00Z", 40, 1000),
      r("2026-10-01T10:03:00Z", 39, 1000),
      r("2026-10-01T10:06:00Z", 40, 1001),
      r("2026-10-01T12:00:00Z", 30, 1150),
      r("2026-10-01T12:04:00Z", 31, 1200),
      r("2026-10-01T12:08:00Z", 30, 1200),
    ]);
    expect(c).toMatchObject({ liters: 10, refills: 0, km: 200, lPer100: 5 });
  });
});

describe("calibrate cu nivelul rezervorului", () => {
  it("nivelul are prioritate față de plinuri când s-au consumat destui litri", () => {
    const c = calibrate(
      [],
      [{ start: "2026-10-02T00:00:00Z", modelLiters: 5 }],
      [
        { t: "2026-10-01T00:00:00Z", fuel: 40, odo: 1000 },
        { t: "2026-10-03T00:00:00Z", fuel: 30, odo: 1150 },
      ],
    );
    expect(c).toMatchObject({ calibrated: true, source: "level" });
    expect(c.factor).toBeCloseTo(2);
  });

  it("sub 8 L consumați rămâne necalibrat", () => {
    const c = calibrate(
      [],
      [{ start: "2026-10-02T00:00:00Z", modelLiters: 5 }],
      [
        { t: "2026-10-01T00:00:00Z", fuel: 40, odo: 1000 },
        { t: "2026-10-03T00:00:00Z", fuel: 36, odo: 1050 },
      ],
    );
    expect(c).toMatchObject({ calibrated: false, source: null, factor: 1 });
  });
});

describe("levelRefills", () => {
  it("alimentarea din 01.10: 21 → 37,5 L", () => {
    const r = levelRefills([
      { t: "2026-10-01T17:01:00Z", fuel: 22, odo: 245070 },
      { t: "2026-10-01T17:12:00Z", fuel: 21, odo: 245071 },
      { t: "2026-10-01T17:32:00Z", fuel: 38, odo: 245073 },
      { t: "2026-10-01T17:35:00Z", fuel: 37, odo: 245074 },
    ]);
    expect(r).toEqual([
      { at: "2026-10-01T17:32:00Z", before: 21, after: 37.5, liters: 16.5, odo: 245073 },
    ]);
  });

  it("oscilațiile de 1 L nu sunt alimentări", () => {
    const xs = [37, 38, 37, 38, 37].map((fuel, i) => ({ t: `2026-10-02T10:0${i}:00Z`, fuel, odo: null }));
    expect(levelRefills(xs)).toEqual([]);
  });
});
