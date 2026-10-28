import { describe, expect, it } from "vitest";

import { bandOf, operatorName, signalLevel, summarizeSignal, type SignalRow } from "./signal.ts";

const T0 = Date.parse("2026-10-28T08:00:00Z");

function row(i: number, over: Partial<SignalRow> = {}): SignalRow {
  return { t: T0 + i * 5_000, lat: 44.44 + i * 0.0005, lon: 26.03, net: 1, rat: "lte", ch: 6175, sig: -90, op: "22605", ...over };
}

describe("bandOf", () => {
  it("află banda LTE din EARFCN", () => {
    expect(bandOf("lte", 6175)).toBe("B20 800");
    expect(bandOf("lte", 78)).toBe("B1 2100");
    expect(bandOf("lte", 3150)).toBe("B7 2600");
    expect(bandOf("lte", 3578)).toBe("B8 900");
    expect(bandOf("lte", 9410)).toBe("B28 700");
    expect(bandOf("lte", 37902)).toBe("B38 2600 TDD");
    expect(bandOf("lte", 5000)).toBe("LTE 5000");
  });
  it("5G, 3G, 2G și fără canal", () => {
    expect(bandOf("nr", 636666)).toBe("n78 3500");
    expect(bandOf("3g", 10700)).toBe("3G");
    expect(bandOf("2g", null)).toBe("2G");
    expect(bandOf("lte", null)).toBeNull();
    expect(bandOf("-", null)).toBeNull();
  });
});

describe("operatorName / signalLevel", () => {
  it("operatorii din România", () => {
    expect(operatorName("22605")).toBe("Digi");
    expect(operatorName("22610")).toBe("Orange");
    expect(operatorName("99999")).toBe("99999");
  });
  it("praguri RSRP", () => {
    expect(signalLevel("lte", -85)).toBe("good");
    expect(signalLevel("lte", -100)).toBe("fair");
    expect(signalLevel("lte", -118)).toBe("weak");
    expect(signalLevel("-", null)).toBe("none");
    expect(signalLevel("2g", -80)).toBe("good");
  });
});

describe("summarizeSignal", () => {
  it("găsește căderea de internet, cu banda de dinainte", () => {
    const rows = [
      row(0),
      row(1),
      row(2, { net: 0, rat: "-", ch: null, sig: null, op: null }),
      row(3, { net: 0, rat: "-", ch: null, sig: null, op: null }),
      row(4, { net: 0, rat: "-", ch: null, sig: null, op: null }),
      row(5, { net: 0, rat: "-", ch: null, sig: null, op: null }),
      row(6, { ch: 78, sig: -100 }),
    ];
    const s = summarizeSignal(rows, "x");
    expect(s.points).toBe(7);
    expect(s.offline).toBeCloseTo(4 / 7);
    expect(s.zones).toHaveLength(1);
    expect(s.zones[0].minutes).toBeCloseTo(0.25);
    expect(s.zones[0].band).toBe("B20 800");
    expect(s.zones[0].km).toBeGreaterThan(0.1);
    expect(s.bands[0]).toEqual({ band: "B20 800", share: 2 / 7, sig: -90 });
    expect(s.ops).toEqual([{ op: "Digi", share: 3 / 7 }]);
    expect(s.cells.some((c) => c.level === "none")).toBe(true);
  });
  it("ignoră căderile scurte și le taie la pauzele lungi", () => {
    const rows = [
      row(0),
      row(1, { net: 0 }),
      row(2, { net: 0, t: T0 + 10 * 60_000 }),
      row(3, { net: 0, t: T0 + 10 * 60_000 + 5_000 }),
      row(4, { net: 0, t: T0 + 10 * 60_000 + 10_000 }),
      row(5, { net: 0, t: T0 + 10 * 60_000 + 20_000 }),
    ];
    const s = summarizeSignal(rows, "x");
    expect(s.zones).toHaveLength(1);
    expect(s.zones[0].at).toBe(new Date(T0 + 10 * 60_000).toISOString());
  });
});
