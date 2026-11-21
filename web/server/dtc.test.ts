import { describe, expect, it } from "vitest";
import { describeDtc, dtcCode, parseDtcs, parseUdsDtcs } from "./dtc.ts";
import { lampWarning } from "./obd.ts";

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

describe("parseUdsDtcs", () => {
  it("reads an empty list", () => {
    expect(parseUdsDtcs("5902FF")).toEqual([]);
  });

  it("reads codes with their status", () => {
    // U1013 00, confirmat + defect acum (0x09); P0301 00 doar în așteptare (0x04); fără stare = sărit.
    const r = parseUdsDtcs("5902FF" + "D0130009" + "03010004" + "01710000")!;
    expect(r.map((d) => d.code)).toEqual(["U1013 00", "P0301 00"]);
    expect(r[0]).toMatchObject({ sae: "U1013", active: true, confirmed: true, pending: false });
    expect(r[1]).toMatchObject({ active: false, confirmed: false, pending: true });
  });

  it("rejects a negative answer", () => {
    expect(parseUdsDtcs("7F1931")).toBeNull();
    expect(parseUdsDtcs("-")).toBeNull();
  });
});

describe("parseUdsDtcs on the real answers (21.11, after the ECU was reset)", () => {
  it("shows nothing for the engine: 63 entries, all 'test not completed' (0x40/0x50)", () => {
    const engine = "5902FF002B065000199450001995500018185000191D400017F94000193D5000193F500019015000190250001839500017F6500019EA5000178250002E12500017C8500017C9500017AA500017AB500017BE500017BF500017AE500017AF50002AC350002AC450001D1F50001D3F50001C6F50001C6E50002E3750002E3850002AC950001D4E50001801500018025000180350001DB95000198C500017E9500017EA500017ED500017EE500017EF500017F0500017EB500017EC5000188A500018575000185850002C9850002C9950002E365000196B5000177C50001ECA50001EC9500019E8500019E5500019E450001C19500019E950001C2050001C2150";
    expect(parseUdsDtcs(engine)).toEqual([]);
  });
  it("shows nothing for the airbag (one entry with status 0x10)", () => {
    expect(parseUdsDtcs("59029990161210")).toEqual([]);
  });
});

describe("lampWarning (cluster DID 2224)", () => {
  it("reads the bulb warning bit as confirmed on 21.11", () => {
    expect(lampWarning("2002")).toBe(false); // 21.11 night, bulbs ok
    expect(lampWarning("2102")).toBe(true); // front right fog light burned
    expect(lampWarning("2006")).toBe(false); // bulb replaced
    expect(lampWarning("-")).toBe(null);
  });
});
