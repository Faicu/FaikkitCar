import { describe, expect, it } from "vitest";

import { procLabel, unitHealth, type UnitStats } from "./unit.ts";

const NOW = Date.parse("2026-10-22T14:16:20Z");

function unit(over: Partial<UnitStats> = {}): UnitStats {
  return {
    at: new Date(NOW - 5_000).toISOString(),
    cpu: 23.4,
    load: 8.3,
    cores: 8,
    memTotal: 4096,
    memAvail: 1075,
    swapTotal: 2304,
    swapFree: 1683,
    temp: 48.6,
    uptime: 650,
    awake: 650,
    appMb: 25,
    appAdj: 200,
    diskGb: 2.4,
    procs: [{ name: "com.waze", label: "Waze", cpu: 1.3, mb: 176 }],
    ...over,
  };
}

describe("unitHealth", () => {
  it("merge bine, cu memoria, procesorul (media) și temperatura", () => {
    expect(unitHealth(unit(), 25, NOW)).toEqual({ level: "ok", text: "merge bine · 1\u00a0GB liberi · procesor 25% · 49\u00a0°C" });
  });

  it("null când navigația doarme (fără măsurare de 90 s)", () => {
    expect(unitHealth(unit({ at: new Date(NOW - 120_000).toISOString() }), 25, NOW)).toBeNull();
    expect(unitHealth(null, null, NOW)).toBeNull();
  });

  it("memoria puțină: avertisment sub 600 MB, grav sub 300 MB", () => {
    expect(unitHealth(unit({ memAvail: 450 }), 25, NOW)).toEqual({ level: "warn", text: "memorie puțină (450\u00a0MB liberi)" });
    expect(unitHealth(unit({ memAvail: 250 }), 25, NOW)?.level).toBe("bad");
  });

  it("un proces care ține ocupat un nucleu (Radio rămas pornit)", () => {
    const procs = [{ name: "sqlfmserver", label: procLabel("sqlfmserver"), cpu: 12.5, mb: 0 }];
    expect(unitHealth(unit({ procs }), 25, NOW)).toEqual({
      level: "warn",
      text: "Radio (sqlfmserver) ține ocupat un nucleu",
    });
  });

  it("la pornire procesorul ocupat nu e o problemă", () => {
    const h = unitHealth(unit({ uptime: 31, cpu: 92.5 }), 92.5, NOW);
    expect(h?.level).toBe("ok");
    expect(h?.text.startsWith("tocmai a pornit")).toBe(true);
    expect(unitHealth(unit(), 85, NOW)?.text).toBe("procesor ocupat (85%)");
  });

  it("swap plin, temperatura, spațiul", () => {
    expect(unitHealth(unit({ swapFree: 100, temp: 72, diskGb: 0.6 }), 25, NOW)?.text).toBe(
      "swap plin · caldă (72\u00a0°C) · spațiu puțin (0,6\u00a0GB)",
    );
  });
});
