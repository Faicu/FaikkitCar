import { describe, expect, it } from "vitest";

import { knownCode } from "./obd-names.ts";

describe("obd-names", () => {
  it("decodes the engine values named by Car Scanner (24.11) with its readings", () => {
    expect(knownCode("7E0", "22", 0x137a, "7B")?.shown).toBe("7,9 °");
    expect(knownCode("7E0", "22", 0x2029, "1550")?.shown).toBe("436 hPa");
    expect(knownCode("7E0", "22", 0x202a, "2FAD")?.shown).toBe("976 hPa");
    expect(knownCode("7E0", "22", 0x20a1, "7F")?.shown).toBe("87 °C");
    expect(knownCode("7E0", "22", 0x173a, "0223")?.shown).toBe("2,19 °");
    expect(knownCode("7E0", "22", 0x118f, "01ED")?.shown).toBe("-1,19 °");
    expect(knownCode("7E0", "22", 0x200c, "80")).toEqual({ name: "Retragerea aprinderii, cilindrul 3", shown: "0 °" });
    expect(knownCode("7E0", "22", 0x155a, "0268")?.name).toBe("Cuplul motorului");
  });

  it("decodes the confirmed cluster values", () => {
    expect(knownCode("714", "22", 0x1014, "67")?.shown).toBe("1,5 °C");
    expect(knownCode("714", "22", 0x202f, "90")?.shown).toBe("86 °C");
    expect(knownCode("714", "22", 0x2260, "3840")?.shown).toBe("14.400 km");
    expect(knownCode("714", "22", 0x22a0, "0050")?.shown).toBe("8.000 km");
    expect(knownCode("714", "22", 0x2299, "006B")?.shown).toBe("10,7 L/100 km");
    expect(knownCode("714", "22", 0x2216, "1734")?.shown).toBe("23:52");
    expect(knownCode("714", "22", 0x2224, "2102")?.shown).toBe("bec ars");
    expect(knownCode("714", "22", 0x2205, "0128")?.shown).toBe("trasă");
    expect(knownCode("714", "22", 0xf18b, "120618")?.shown).toBe("18.06.12");
    expect(knownCode("714", "22", 0xf197, "4B4F4D4249")?.shown).toBe("KOMBI");
  });

  it("leaves unknown codes, other services and refusals alone", () => {
    expect(knownCode("7E0", "22", 0x58de, "0115")).toBeNull();
    expect(knownCode("7E0", "01", 0x0c, "0BB8")).toBeNull();
    expect(knownCode("7E0", "22", 0x2029, null)).toEqual({ name: "Presiunea de supraalimentare cerută", shown: null });
  });
});
