import { describe, expect, it } from "vitest";
import { describeDtc, dtcCode, parseDtcs } from "./dtc.ts";

describe("dtcCode", () => {
  it("decodes the system letter and digits", () => {
    expect(dtcCode(0x03, 0x01)).toBe("P0301");
    expect(dtcCode(0x01, 0x71)).toBe("P0171");
    expect(dtcCode(0x22, 0x79)).toBe("P2279");
    expect(dtcCode(0x41, 0x23)).toBe("C0123");
    expect(dtcCode(0x81, 0x00)).toBe("B0100");
    expect(dtcCode(0xc1, 0x00)).toBe("U0100");
    expect(dtcCode(0x10, 0x0a)).toBe("P100A");
  });
});

describe("parseDtcs", () => {
  it("reads an empty list (the car on 20.11)", () => {
    expect(parseDtcs("4300", 3)).toEqual([]);
    expect(parseDtcs("4700", 7)).toEqual([]);
  });

  it("reads the codes after the count, skipping padding", () => {
    expect(parseDtcs("4302030101710000", 3)).toEqual(["P0301", "P0171"]);
    expect(parseDtcs("470122790000", 7)).toEqual(["P2279"]);
  });

  it("rejects the wrong mode or garbage", () => {
    expect(parseDtcs("4300", 7)).toBeNull();
    expect(parseDtcs("NO DATA", 3)).toBeNull();
    expect(parseDtcs("", 3)).toBeNull();
    expect(parseDtcs(undefined, 3)).toBeNull();
  });
});

describe("describeDtc", () => {
  it("knows the common codes", () => {
    expect(describeDtc("P0301")).toEqual({ code: "P0301", text: "Rateuri la cilindrul 1", known: true });
  });

  it("falls back to the family", () => {
    const d = describeDtc("P0399");
    expect(d.known).toBe(false);
    expect(d.text).toMatch(/Aprinderea/);
    expect(describeDtc("U0155").text).toMatch(/Comunicarea/);
  });
});
