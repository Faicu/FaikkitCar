import { describe, expect, it } from "vitest";

import {
  acLoad,
  calibrate,
  coldStartLiters,
  coolDown,
  currentLevel,
  estimateFuel,
  factorAt,
  levelConsumption,
  levelRefills,
  lPer100At,
  matchRefuels,
  priceAt,
  tankPrices,
  type FuelRefuel,
  type FuelSample,
  type LevelReading,
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
  it("relanti o oră ≈ 0,5 L, totul pe loc", () => {
    const e = estimateFuel(steady(60, 0, 750, 30));
    expect(e.liters).toBeGreaterThan(0.45);
    expect(e.liters).toBeLessThan(0.6);
    expect(e.idleMin).toBeCloseTo(60, 0);
    expect(e.idleLiters).toBeCloseTo(e.liters);
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

  it("AC: după temperatura de afară (nimic sub 5 °C, 0,4 L/h la 25 °C, mai mult pe caniculă)", () => {
    expect(acLoad(0)).toBe(0);
    expect(acLoad(15)).toBeCloseTo(0.5);
    expect(acLoad(25)).toBe(1);
    expect(acLoad(45)).toBe(1.5);
    expect(acLoad(null)).toBe(1);
    const hour = (temp: number | null, ac: boolean) =>
      estimateFuel(steady(60, 0, 750, 30).map((x) => ({ ...x, ac, temp }))).liters;
    const off = hour(25, false);
    expect(hour(25, true) - off).toBeCloseTo(0.4);
    expect(hour(15, true) - off).toBeCloseTo(0.2);
    expect(hour(2, true) - off).toBeCloseTo(0);
    expect(hour(null, true) - off).toBeCloseTo(0.4);
  });

  it("pornirea la rece: litri în plus, mai mulți pe frig, aproape toți în primele 20 de minute", () => {
    expect(coldStartLiters(20)).toBeCloseTo(0.08);
    expect(coldStartLiters(0)).toBeCloseTo(0.18);
    expect(coldStartLiters(40)).toBeCloseTo(0.04);
    const drive = (min: number, temp: number) => steady(min, 50, 2000).map((x) => ({ ...x, temp }));
    const warm = estimateFuel(drive(30, 0));
    const cold = estimateFuel(drive(30, 0), 1);
    expect(warm.coldEnd).toBe(0);
    expect(cold.liters - warm.liters).toBeGreaterThan(0.17);
    expect(cold.liters - warm.liters).toBeLessThan(0.18);
    expect(cold.coldEnd).toBeLessThan(0.01);
    // Un drum de 3 minute plătește doar o parte și lasă motorul încă rece.
    const short = estimateFuel(drive(3, 0), 1);
    expect(short.liters - estimateFuel(drive(3, 0)).liters).toBeCloseTo(0.18 * (1 - Math.exp(-0.5)), 3);
    expect(short.coldEnd).toBeCloseTo(Math.exp(-0.5), 3);
  });

  it("motorul oprit se răcește în câteva ore", () => {
    expect(coolDown(0, 0)).toBe(0);
    expect(coolDown(0, 10 * 60)).toBeLessThan(0.1);
    expect(coolDown(0, 2.5 * 3600)).toBeCloseTo(0.632, 2);
    expect(coolDown(0, 12 * 3600)).toBeGreaterThan(0.99);
    expect(coolDown(0.5, 2.5 * 3600)).toBeCloseTo(0.816, 2);
  });
});

/** Citiri de nivel: [ora ISO, litri, kilometraj]. */
function levels(...xs: Array<[string, number, number]>): LevelReading[] {
  return xs.map(([t, fuel, odo]) => ({ t, fuel, odo }));
}

describe("calibrate", () => {
  it("fără două plinuri rămâne necalibrat, factor 1", () => {
    const c = calibrate(
      [refuel(1, "2026-10-01", 30, true)],
      [{ start: "2026-10-02", modelLiters: 5 }],
    );
    expect(c).toMatchObject({ factor: 1, calibrated: false, intervals: [] });
  });

  it("fără nivel, plin → parțial → plin: litrii de după primul plin, raportați la estimare", () => {
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
    expect(c.source).toBe("refuels");
    expect(c.intervals).toHaveLength(1);
    expect(c.intervals[0]).toMatchObject({
      liters: 30,
      modelLiters: 20,
      km: 500,
      lPer100: 6,
      exact: true,
      fromId: 1,
      toId: 3,
    });
    expect(c.factor).toBeCloseTo(1.5);
  });

  it("nivel: km neînregistrați (kilometraj > călătorii) intră în estimare", () => {
    const c = calibrate(
      [],
      [{ start: "2026-10-02T10:00:00Z", modelLiters: 8, distanceKm: 80 }],
      levels(["2026-10-01T10:00:00Z", 40, 1000], ["2026-10-09T10:00:00Z", 30, 1100]),
    );
    expect(c.source).toBe("level");
    expect(c.factor).toBeCloseTo(1); // 10 L reali / (8 L × 100 / 80 km)
  });

  it("plin → plin: litrii de pe bon, nu indicatorul (care nu e liniar la capete)", () => {
    const c = calibrate(
      [
        refuel(1, "2026-10-02T10:00:00Z", 25, true),
        refuel(2, "2026-10-09T10:00:00Z", 40, true),
      ],
      [
        { start: "2026-10-01T09:00:00Z", modelLiters: 99, distanceKm: 10 }, // înainte de primul plin
        { start: "2026-10-03T10:00:00Z", modelLiters: 32, distanceKm: 400 },
      ],
      levels(
        ["2026-10-01T10:00:00Z", 30, 1000],
        ["2026-10-02T09:55:00Z", 30, 1010],
        ["2026-10-02T10:05:00Z", 52, 1010],
        ["2026-10-09T09:55:00Z", 20, 1410], // indicatorul: 32 L consumați
        ["2026-10-09T10:05:00Z", 52, 1410], // bonul: 40 L
      ),
    );
    expect(c.intervals).toHaveLength(1);
    expect(c.intervals[0]).toMatchObject({ liters: 40, modelLiters: 32, km: 400, exact: true });
    expect(c.factor).toBeCloseTo(1.25);
  });

  it("alimentări parțiale: nivelul de dinainte + bonul − nivelul de dinaintea următoarei", () => {
    // Ca în 10–11.2026: 21 L + 16 L pe bon, apoi plin la 12 L, apoi parțial tot la 12 L.
    const c = calibrate(
      [
        refuel(1, "2026-10-01T17:32:00Z", 16, false),
        refuel(2, "2026-10-20T08:04:00Z", 46.18, true),
        refuel(3, "2026-11-14T18:17:00Z", 31, false),
      ],
      [
        { start: "2026-10-01T17:00:00Z", end: "2026-10-01T17:28:00Z", modelLiters: 1, distanceKm: 2 },
        { start: "2026-10-05T10:00:00Z", modelLiters: 24, distanceKm: 247 },
        { start: "2026-10-20T08:10:00Z", modelLiters: 23, distanceKm: 200 },
        { start: "2026-11-01T10:00:00Z", modelLiters: 25, distanceKm: 226 },
        { start: "2026-11-14T18:30:00Z", modelLiters: 3, distanceKm: 20 },
      ],
      levels(
        ["2026-10-01T17:01:00Z", 21, 245070],
        ["2026-10-01T17:28:00Z", 21, 245072],
        ["2026-10-01T17:33:00Z", 37, 245072],
        ["2026-10-20T07:59:00Z", 12, 245319],
        ["2026-10-20T08:05:00Z", 52, 245319],
        ["2026-11-14T18:14:00Z", 12, 245745],
        ["2026-11-14T18:19:00Z", 45, 245745],
        ["2026-11-14T18:40:00Z", 44, 245765],
      ),
    );
    expect(c.source).toBe("refuels");
    expect(c.intervals).toHaveLength(2);
    // Prima alimentare e sub 20 L de la prima citire: intervalul se adună până la plin.
    expect(c.intervals[0]).toMatchObject({ liters: 25, modelLiters: 25, km: 249, fromId: null, toId: 2 });
    expect(c.intervals[0].factor).toBeCloseTo(1);
    // 12 L + 46,18 L − 12 L: indicatorul la fel la ambele capete, deci litrii de pe bon.
    expect(c.intervals[1]).toMatchObject({ liters: 46.18, modelLiters: 48, km: 426, fromId: 2, toId: 3 });
    expect(c.intervals[1].factor).toBeCloseTo(0.962, 3);
    // Fiecare drum cu factorul intervalului lui; după ultima alimentare, media intervalelor.
    expect(factorAt(c, "2026-10-05T10:00:00Z")).toBeCloseTo(1);
    expect(factorAt(c, "2026-11-01T10:00:00Z")).toBeCloseTo(0.962, 3);
    expect(c.factor).toBeCloseTo(71.18 / 73, 4);
    expect(factorAt(c, "2026-11-14T18:30:00Z")).toBe(c.factor);
    expect(lPer100At(c, "2026-10-07T09:00:00Z")).toBeCloseTo((25 / 249) * 100);
    expect(lPer100At(c, "2026-11-20T09:00:00Z")).toBeCloseTo((71.18 / 675) * 100);
  });

  it("o alimentare trecută în jurnal înainte ca mașina să pornească închide intervalul", () => {
    const c = calibrate(
      [refuel(1, "2026-10-09T11:00:00Z", 30, false, 1300)],
      [{ start: "2026-10-02T10:00:00Z", modelLiters: 20, distanceKm: 300 }],
      levels(["2026-10-01T10:00:00Z", 40, 1000], ["2026-10-09T10:00:00Z", 15, 1300]),
    );
    expect(c.intervals).toHaveLength(1);
    expect(c.intervals[0]).toMatchObject({ liters: 25, km: 300, toId: 1 });
    expect(c.factor).toBeCloseTo(1.25);
  });

  it("o alimentare mică, nevăzută în rezervor, se adună la interval", () => {
    const c = calibrate(
      [refuel(1, "2026-10-05T10:00:00Z", 2, false)],
      [{ start: "2026-10-02T10:00:00Z", modelLiters: 27 }],
      levels(
        ["2026-10-01T10:00:00Z", 40, 1000],
        ["2026-10-09T10:00:00Z", 15, 1300],
        ["2026-10-09T10:10:00Z", 45, 1300],
      ),
    );
    expect(c.intervals[0]).toMatchObject({ liters: 27, factor: 1 });
  });

  it("factorul e limitat când datele lipsesc", () => {
    const c = calibrate(
      [refuel(1, "2026-10-01", 40, true), refuel(2, "2026-10-10", 40, true)],
      [{ start: "2026-10-02", modelLiters: 1 }],
    );
    expect(c.factor).toBe(2.5);
  });
});

describe("matchRefuels", () => {
  const jump = (at: string, liters: number) => ({ at, since: at, before: 10, after: 10 + liters, liters, odo: null });

  it("perechea cea mai apropiată, la cel mult 12 ore", () => {
    const m = matchRefuels(
      [jump("2026-10-01T17:32:00Z", 16), jump("2026-10-02T08:00:00Z", 20)],
      [
        { at: "2026-10-01T09:00:00Z", liters: 16 }, // ora pusă din memorie, cu 8 h mai devreme
        { at: "2026-10-02T08:10:00Z", liters: 21 },
      ],
    );
    expect(m.byRefill).toEqual([0, 1]);
    expect(m.byRefuel).toEqual([0, 1]);
    expect(m.late.size).toBe(0);
  });

  it("data greșită cu o zi: se potrivește doar dacă și litrii seamănă, și e semnalată", () => {
    const jumps = [jump("2026-10-01T17:32:00Z", 30)];
    const m = matchRefuels(jumps, [{ at: "2026-10-02T17:30:00Z", liters: 33 }]);
    expect(m.byRefuel).toEqual([0]);
    expect([...m.late]).toEqual([0]);
    const no = matchRefuels(jumps, [{ at: "2026-10-02T17:30:00Z", liters: 10 }]);
    expect(no.byRefuel).toEqual([-1]);
    expect(no.byRefill).toEqual([-1]);
  });

  it("un salt fără alimentare în jurnal rămâne liber", () => {
    expect(matchRefuels([jump("2026-10-01T17:32:00Z", 16)], []).byRefill).toEqual([-1]);
  });
});

describe("tankPrices", () => {
  it("prețul din rezervor = media ponderată cu ce era înăuntru", () => {
    const refuels = [
      { ...refuel(1, "2026-10-01T10:00:00Z", 20, false), price: 10 },
      { ...refuel(2, "2026-10-10T10:00:00Z", 30, false), price: 9 },
      { ...refuel(3, "2026-10-20T10:00:00Z", 30, false), price: null },
    ];
    const p = tankPrices(
      refuels,
      levels(
        ["2026-10-01T09:55:00Z", 5, 1000],
        ["2026-10-01T10:05:00Z", 25, 1000],
        ["2026-10-10T09:55:00Z", 10, 1200],
        ["2026-10-10T10:05:00Z", 40, 1200],
      ),
    );
    // 10 L rămași la 10 lei + 30 L la 9 lei = 9,25; alimentarea fără preț nu schimbă nimic.
    expect(p).toEqual([
      { at: "2026-10-01T10:00:00Z", price: 10 },
      { at: "2026-10-10T10:00:00Z", price: 9.25 },
    ]);
    expect(priceAt(p, "2026-10-05T00:00:00Z")).toBe(10);
    expect(priceAt(p, "2026-10-25T00:00:00Z")).toBe(9.25);
  });

  it("fără nivel: prețul fiecărei alimentări", () => {
    const p = tankPrices([
      { ...refuel(1, "2026-10-01", 20, true), price: 10 },
      { ...refuel(2, "2026-10-10", 30, true), price: 9 },
    ]);
    expect(p.map((x) => x.price)).toEqual([10, 9]);
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
      {
        at: "2026-10-01T17:32:00Z",
        since: "2026-10-01T17:12:00Z",
        before: 21,
        after: 37.5,
        liters: 16.5,
        odo: 245073,
      },
    ]);
  });

  it("oscilațiile de 1 L nu sunt alimentări", () => {
    const xs = [37, 38, 37, 38, 37].map((fuel, i) => ({ t: `2026-10-02T10:0${i}:00Z`, fuel, odo: null }));
    expect(levelRefills(xs)).toEqual([]);
  });
});

describe("currentLevel", () => {
  const r = (t: string, fuel: number) => ({ t, fuel, odo: null });

  it("imediat după plin ia doar citirile de după salt (20.10: 12 → 52)", () => {
    const xs = [
      ...Array.from({ length: 40 }, (_, i) =>
        r(`2026-10-20T07:5${Math.floor(i / 6)}:${String((i % 6) * 10).padStart(2, "0")}Z`, 12),
      ),
      r("2026-10-20T08:04:47Z", 52),
      r("2026-10-20T08:05:47Z", 52),
      r("2026-10-20T08:06:27Z", 52),
    ];
    expect(currentLevel(xs)).toBe(52);
  });

  it("fără alimentare: mediana ultimelor 10 minute", () => {
    expect(
      currentLevel([
        r("2026-10-01T10:00:00Z", 40),
        r("2026-10-01T10:15:00Z", 37),
        r("2026-10-01T10:16:00Z", 38),
        r("2026-10-01T10:17:00Z", 38),
      ]),
    ).toBe(38);
    expect(currentLevel([])).toBeNull();
  });
});
