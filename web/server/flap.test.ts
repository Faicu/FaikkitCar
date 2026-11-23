import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

// Baza de date de test, înainte de prima folosire a lui getDb.
process.env.FAIKKITCAR_DB = join(mkdtempSync(join(tmpdir(), "flap-")), "t.db");

const flap = await import("./flap.ts");
const scan = await import("./obd-scan.ts");
const { readObd } = await import("./obd.ts");

describe("flap", () => {
  it("reads the position bytes", () => {
    expect(flap.flapByte("00DB")).toBe(0xdb);
    expect(flap.flapByte("BE")).toBe(0xbe);
    expect(flap.flapByte("x")).toBeNull();
  });

  it("accepts every position seen working on 23.11 and rejects the stuck ones", () => {
    for (const [a, d] of [[0x19, 0x00], [0x7d, 0x60], [0x96, 0x78], [0xc4, 0xa5], [0xdb, 0xbe], [0xdc, 0xbe], [0x72, 0x55]]) {
      expect(flap.flapOk(a, d)).toBe(true);
    }
    expect(flap.flapOk(0x89, 0xbe)).toBe(false);
    expect(flap.flapOk(0x1a, 0x94)).toBe(false);
  });

  it("confirms a stuck flap only after 30 s with the same demand, and clears on a good read", () => {
    const t = (s: number) => new Date(Date.parse("2026-11-23T16:00:00Z") + s * 1000).toISOString();
    let r = flap.nextFlap(null, t(0), 0x89, 0xbe);
    expect(r.since).toBeNull();
    r = flap.nextFlap(r, t(10), 0x89, 0xbe);
    expect(r.since).toBeNull();
    // Altă cerere: clapeta abia pleacă, se numără de la capăt.
    r = flap.nextFlap(r, t(20), 0x89, 0x40);
    expect(r.badFrom).toBe(t(20));
    r = flap.nextFlap(r, t(60), 0x89, 0x41);
    expect(r.since).toBe(t(20));
    r = flap.nextFlap(r, t(120), 0x5d, 0x41);
    expect(r).toMatchObject({ badFrom: null, since: null });
  });

  it("turns scan results into a Climatronic fault", () => {
    const at = Date.parse("2026-11-23T16:00:00Z");
    const pair = (s: number, a: string, d: string) => [
      { t: "r", j: 1, x: "746", s: "22", i: 0x262e, v: a, at: at + s * 1000 },
      { t: "r", j: 1, x: "746", s: "22", i: 0x262f, v: d, at: at + s * 1000 + 300 },
    ];
    scan.saveScanItems(pair(0, "0089", "BE"));
    expect(readObd().faults.some((f) => f.code === "clapeta")).toBe(false);
    scan.saveScanItems(pair(60, "0089", "BE"));
    expect(readObd().faults.find((f) => f.code === "clapeta")).toMatchObject({ module: "Climatronic", state: "active" });
    scan.saveScanItems(pair(120, "00DB", "BE"));
    expect(readObd().faults.some((f) => f.code === "clapeta")).toBe(false);
  });
});
