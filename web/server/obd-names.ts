// ---------------------------------------------------------------------------
// Numele codurilor UDS confirmate (OBD-CODURI.md, secțiunea 3), pentru catalogul de pe site: ce e
// codul și valoarea pe înțeles. Pur, testat în obd-names.test.ts. Doar ce e confirmat; candidații
// rămân în OBD-CODURI.md. Numele din Car Scanner (24.11) sunt traduse.
// ---------------------------------------------------------------------------

type Decode = (b: number[]) => string;

interface Known {
  name: string;
  decode?: Decode;
}

const u16 = (b: number[], i = 0) => (b[i] ?? 0) * 256 + (b[i + 1] ?? 0);
const fmt = (x: number, digits = 0, unit = "") => {
  const s = x.toLocaleString("ro-RO", { minimumFractionDigits: digits, maximumFractionDigits: digits });
  return unit ? `${s} ${unit}` : s;
};
const n1 = (unit: string, f: (x: number) => number, digits = 0): Decode => (b) => fmt(f(b[0] ?? 0), digits, unit);
const n2 = (unit: string, f: (x: number) => number, digits = 0): Decode => (b) => fmt(f(u16(b)), digits, unit);
/** Formula VW pentru temperaturi: 0,75 × x − 48 °C. */
const vwTemp = n1("°C", (x) => 0.75 * x - 48, 1);
const text: Decode = (b) => String.fromCharCode(...b.filter((x) => x >= 0x20 && x < 0x7f)).trim();
/** Al doilea octet (clapetele climatronicului: valoarea mare e al doilea octet la cele de 2). */
const last: Decode = (b) => (b.length ? (b[b.length - 1]).toString(16).toUpperCase().padStart(2, "0") : "");
const camEdge = n2("°", (x) => (x - 512) / 16, 2);

const ENGINE: Record<number, Known> = {
  0x0600: { name: "Codarea motorului" },
  0x1000: { name: "Temperatura de afară", decode: vwTemp },
  0x1001: { name: "Aerul admis", decode: vwTemp },
  0x1002: { name: "Aerul admis (copie)", decode: vwTemp },
  0x117c: { name: "Turația", decode: n2("rpm", (x) => x) },
  0x11cf: { name: "Lichidul de răcire", decode: vwTemp },
  0x11d1: { name: "Lichidul de răcire (copie)", decode: vwTemp },
  0x121c: { name: "Kilometrajul", decode: (b) => fmt(b.reduce((a, x) => a * 256 + x, 0), 0, "km") },
  0x137a: { name: "Avansul aprinderii", decode: n1("°", (x) => x * 0.75 - 84.375, 1) },
  0x1314: { name: "Masa de aer medie (Car Scanner, neconfirmată)" },
  0x1213: { name: "ENVD_CAM_IN_1 (Car Scanner)" },
  0x155a: { name: "Cuplul motorului", decode: n2("N·m", (x) => x * 0.03, 1) },
  0x15aa: { name: "Treapta selectată (0 la cutia manuală)" },
  0x15d4: { name: "Kilometrajul la pornirea drumului", decode: (b) => fmt(b.reduce((a, x) => a * 256 + x, 0) / 10, 1, "km") },
  0x173a: { name: "Axa cu came de admisie: adaptarea, frontul 1", decode: camEdge },
  0x2005: { name: "Presiunea servofrânei (fără senzor)" },
  0x2006: { name: "Nivelul uleiului (formula neclară)" },
  0x2029: { name: "Presiunea de supraalimentare cerută", decode: n2("hPa", (x) => x * 0.08) },
  0x202a: { name: "Presiunea de supraalimentare reală", decode: n2("hPa", (x) => x * 0.08) },
  0x20a1: { name: "Temperatura uleiului (calculată)", decode: n1("°C", (x) => x - 40) },
  0x2206: { name: "Litrii din rezervor", decode: n1("L", (x) => x) },
  0x392a: { name: "Lichidul de răcire (a treia copie)", decode: vwTemp },
  0xf187: { name: "Piesa", decode: text },
  0xf189: { name: "Versiunea de software", decode: text },
  0xf190: { name: "VIN", decode: text },
  0xf191: { name: "Piesa hardware", decode: text },
  0xf197: { name: "Numele modulului", decode: text },
  0xf19e: { name: "Fișierul de descriere (ODX)", decode: text },
  0xf1ad: { name: "Codul motorului", decode: text },
};
for (let i = 0; i < 4; i++) ENGINE[0x200a + i] = { name: `Retragerea aprinderii, cilindrul ${i + 1}`, decode: n1("°", (x) => x - 128) };
for (let i = 0; i < 7; i++) ENGINE[0x1189 + i] = { name: `Axa cu came de admisie: adaptarea, frontul ${i + 2}`, decode: camEdge };

const CLUSTER: Record<number, Known> = {
  0x1014: { name: "Temperatura de afară", decode: n1("°C", (x) => x / 2 - 50, 1) },
  0x100c: { name: "Intervalul de ulei (fix)", decode: n2("km", (x) => x) },
  0x202f: { name: "Temperatura uleiului", decode: n1("°C", (x) => x - 58) },
  0x2205: { name: "Frâna de mână (bitul 0x01)", decode: (b) => ((b[0] ?? 0) & 1 ? "trasă" : "eliberată") },
  0x2206: { name: "Litrii din rezervor", decode: n1("L", (x) => x) },
  0x2216: { name: "Ceasul bordului", decode: (b) => `${String(b[0] ?? 0).padStart(2, "0")}:${String(b[1] ?? 0).padStart(2, "0")}` },
  0x2224: {
    name: "Martorii bordului",
    decode: (b) => {
      const x = b[0] ?? 0;
      const on = [x & 0x01 && "bec ars", x & 0x04 && "avarii", x & 0x40 && "faza lungă"].filter(Boolean);
      return on.length ? on.join(", ") : "nimic aprins";
    },
  },
  0x2238: { name: "Ora", decode: n1("", (x) => x) },
  0x2239: { name: "Minutul", decode: n1("", (x) => x) },
  0x2243: { name: "Km de la revizie (în sute)", decode: n2("km", (x) => x * 100) },
  0x2244: { name: "Zile de la revizie", decode: n2("zile", (x) => x) },
  0x2250: { name: "Lumina de afară" },
  0x2260: { name: "Km până la revizia de ulei", decode: n2("km", (x) => x) },
  0x2261: { name: "Zile până la revizia de ulei", decode: n2("zile", (x) => x) },
  0x2294: { name: "Autonomia (nerotunjită)", decode: n2("km", (x) => x) },
  0x2297: { name: "Autonomia de pe ecran", decode: n2("km", (x) => x) },
  0x2299: { name: "Consumul mediu pe termen lung", decode: n2("L/100 km", (x) => x / 10, 1) },
  0x22a0: { name: "Intervalul reviziei", decode: n2("km", (x) => x * 100) },
  0x22a1: { name: "Intervalul reviziei, zile", decode: n2("zile", (x) => x) },
  0xf187: { name: "Piesa", decode: text },
  0xf189: { name: "Versiunea de software", decode: text },
  0xf18b: { name: "Data fabricației", decode: (b) => b.map((x) => x.toString(16).padStart(2, "0")).reverse().join(".") },
  0xf190: { name: "VIN", decode: text },
  0xf191: { name: "Piesa hardware", decode: text },
  0xf197: { name: "Numele modulului", decode: text },
  0xf19e: { name: "Fișierul de descriere (ODX)", decode: text },
  0xf405: { name: "Lichidul de răcire", decode: n1("°C", (x) => 0.76 * x - 43) },
  0xf40c: { name: "Turația", decode: n2("rpm", (x) => x / 4) },
  0xf40d: { name: "Viteza", decode: n1("km/h", (x) => x) },
};

const CLIMATE: Record<number, Known> = {
  0x2601: { name: "Compresorul / cererea de A/C" },
  0x2617: { name: "Comanda ventilatorului", decode: last },
  0x2618: { name: "Comanda ventilatorului (2)", decode: last },
  0x261e: { name: "Clapeta de recirculare: reală", decode: last },
  0x261f: { name: "Clapeta de recirculare: cerută", decode: last },
  0x262e: { name: "Clapeta de distribuție față: reală", decode: last },
  0x262f: { name: "Clapeta de distribuție față: cerută", decode: last },
  0xf187: { name: "Piesa", decode: text },
  0xf197: { name: "Numele modulului", decode: text },
  0xf19e: { name: "Fișierul de descriere (ODX)", decode: text },
};

const MODULES: Record<string, Record<number, Known>> = { "7E0": ENGINE, "714": CLUSTER, "746": CLIMATE };

/** Numele și valoarea pe înțeles a unui cod UDS (svc 22), sau null dacă nu e confirmat. */
export function knownCode(tx: string, svc: string, id: number, value: string | null): { name: string; shown: string | null } | null {
  if (svc !== "22") return null;
  const k = MODULES[tx]?.[id];
  if (!k) return null;
  let shown: string | null = null;
  if (k.decode && value && /^([0-9A-Fa-f]{2})+$/.test(value)) {
    try {
      shown = k.decode(value.match(/../g)!.map((h) => parseInt(h, 16))) || null;
    } catch {
      shown = null;
    }
  }
  return { name: k.name, shown };
}
