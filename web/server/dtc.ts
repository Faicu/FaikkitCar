// ---------------------------------------------------------------------------
// Codurile de eroare OBD2 (mod 03 = memorate, mod 07 = în așteptare): aplicația din mașină
// trimite răspunsul brut în hex („4302010301 33…”), aici îl transformăm în coduri (P0301) și
// le dăm o descriere pe înțeles. Pur (fără bază de date), testat în dtc.test.ts.
// ---------------------------------------------------------------------------

export interface Dtc {
  code: string; // „P0301”
  text: string; // descrierea pe înțeles (din listă sau după familie)
  known: boolean; // descrierea e a codului exact, nu doar a familiei
}

/**
 * Răspunsul brut la mod 03 / 07 (hex, fără spații): pe CAN „43 <număr> <A B>…” (47 la mod 07),
 * câte doi octeți pe cod; perechile 0000 sunt umplutură. Altceva = nicio listă (null).
 */
export function parseDtcs(hex: string | null | undefined, mode: 3 | 7): string[] | null {
  if (typeof hex !== "string" || !/^([0-9A-Fa-f]{2})+$/.test(hex)) return null;
  const b = hex.match(/../g)!.map((x) => parseInt(x, 16));
  if (b[0] !== 0x40 + mode) return null;
  // Pe CAN al doilea octet e numărul de coduri; perechile urmează după el.
  const count = b.length >= 2 ? b[1] : 0;
  const out: string[] = [];
  for (let i = 2; i + 1 < b.length && out.length < count; i += 2) {
    const a = b[i];
    const c = b[i + 1];
    if (a === 0 && c === 0) continue;
    out.push(dtcCode(a, c));
  }
  return out;
}

/** Doi octeți → „P0301”: primii 2 biți = sistemul (P/C/B/U), apoi 14 biți în hex. */
export function dtcCode(a: number, b: number): string {
  const sys = "PCBU"[a >> 6];
  const d1 = (a >> 4) & 0x3;
  const rest = ((a & 0x0f) << 8) | b;
  return `${sys}${d1}${rest.toString(16).toUpperCase().padStart(3, "0")}`;
}

// Codurile standard (SAE) cele mai întâlnite, în special la un motor TSI cu injecție directă.
const KNOWN: Record<string, string> = {
  P0010: "Ax cu came admisie (bancul 1): circuitul actuatorului",
  P0011: "Ax cu came admisie (bancul 1): calare prea avansată sau performanță slabă",
  P0012: "Ax cu came admisie (bancul 1): calare prea întârziată",
  P0016: "Corelare arbore cotit / ax cu came (bancul 1, senzorul A)",
  P0030: "Sonda lambda înainte de catalizator: circuitul de încălzire",
  P0036: "Sonda lambda după catalizator: circuitul de încălzire",
  P0045: "Turbo: circuitul supapei de reglare a presiunii",
  P0068: "Presiunea din galerie / clapeta: necorelate",
  P0087: "Presiune benzină pe rampă prea mică",
  P0088: "Presiune benzină pe rampă prea mare",
  P0089: "Regulatorul presiunii benzinei: performanță",
  P0090: "Regulatorul presiunii benzinei: circuitul",
  P0100: "Debitmetrul de aer: circuitul",
  P0101: "Debitmetrul de aer: valori în afara domeniului",
  P0105: "Senzorul de presiune din galerie: circuitul",
  P0106: "Senzorul de presiune din galerie: valori în afara domeniului",
  P0107: "Senzorul de presiune din galerie: semnal prea mic",
  P0108: "Senzorul de presiune din galerie: semnal prea mare",
  P0110: "Senzorul de temperatură a aerului admis: circuitul",
  P0111: "Senzorul de temperatură a aerului admis: valori în afara domeniului",
  P0112: "Senzorul de temperatură a aerului admis: semnal prea mic",
  P0113: "Senzorul de temperatură a aerului admis: semnal prea mare",
  P0115: "Senzorul de temperatură a lichidului de răcire: circuitul",
  P0116: "Senzorul de temperatură a lichidului de răcire: valori în afara domeniului",
  P0117: "Senzorul de temperatură a lichidului de răcire: semnal prea mic",
  P0118: "Senzorul de temperatură a lichidului de răcire: semnal prea mare",
  P0121: "Clapeta / pedala (senzorul A): valori în afara domeniului",
  P0122: "Clapeta / pedala (senzorul A): semnal prea mic",
  P0123: "Clapeta / pedala (senzorul A): semnal prea mare",
  P0125: "Motorul se încălzește prea greu pentru reglajul în buclă închisă",
  P0128: "Termostat: lichidul de răcire sub temperatura de lucru",
  P0130: "Sonda lambda înainte de catalizator: circuitul",
  P0131: "Sonda lambda înainte de catalizator: tensiune prea mică",
  P0132: "Sonda lambda înainte de catalizator: tensiune prea mare",
  P0133: "Sonda lambda înainte de catalizator: răspuns lent",
  P0134: "Sonda lambda înainte de catalizator: niciun semnal",
  P0135: "Sonda lambda înainte de catalizator: încălzirea",
  P0136: "Sonda lambda după catalizator: circuitul",
  P0137: "Sonda lambda după catalizator: tensiune prea mică",
  P0138: "Sonda lambda după catalizator: tensiune prea mare",
  P0140: "Sonda lambda după catalizator: niciun semnal",
  P0141: "Sonda lambda după catalizator: încălzirea",
  P0169: "Compoziția benzinei incorectă",
  P0171: "Amestec prea sărac (bancul 1)",
  P0172: "Amestec prea bogat (bancul 1)",
  P0190: "Senzorul presiunii benzinei pe rampă: circuitul",
  P0191: "Senzorul presiunii benzinei pe rampă: valori în afara domeniului",
  P0192: "Senzorul presiunii benzinei pe rampă: semnal prea mic",
  P0193: "Senzorul presiunii benzinei pe rampă: semnal prea mare",
  P0201: "Injectorul cilindrului 1: circuitul",
  P0202: "Injectorul cilindrului 2: circuitul",
  P0203: "Injectorul cilindrului 3: circuitul",
  P0204: "Injectorul cilindrului 4: circuitul",
  P0221: "Clapeta / pedala (senzorul B): valori în afara domeniului",
  P0222: "Clapeta / pedala (senzorul B): semnal prea mic",
  P0223: "Clapeta / pedala (senzorul B): semnal prea mare",
  P0234: "Turbo: presiune prea mare (supraalimentare)",
  P0236: "Senzorul presiunii turbo: valori în afara domeniului",
  P0243: "Turbo: supapa wastegate, circuitul",
  P0299: "Turbo: presiune prea mică (subalimentare)",
  P0300: "Rateuri la mai mulți cilindri (aleatorii)",
  P0301: "Rateuri la cilindrul 1",
  P0302: "Rateuri la cilindrul 2",
  P0303: "Rateuri la cilindrul 3",
  P0304: "Rateuri la cilindrul 4",
  P0322: "Senzorul de turație: niciun semnal",
  P0324: "Senzorii de detonație: eroare de sistem",
  P0325: "Senzorul de detonație 1: circuitul",
  P0327: "Senzorul de detonație 1: semnal prea mic",
  P0328: "Senzorul de detonație 1: semnal prea mare",
  P0335: "Senzorul de poziție a arborelui cotit: circuitul",
  P0336: "Senzorul de poziție a arborelui cotit: valori în afara domeniului",
  P0340: "Senzorul axului cu came: circuitul",
  P0341: "Senzorul axului cu came: valori în afara domeniului",
  P0351: "Bobina de inducție a cilindrului 1: circuitul",
  P0352: "Bobina de inducție a cilindrului 2: circuitul",
  P0353: "Bobina de inducție a cilindrului 3: circuitul",
  P0354: "Bobina de inducție a cilindrului 4: circuitul",
  P0410: "Aerul secundar: sistemul",
  P0420: "Catalizatorul (bancul 1): eficiență sub prag",
  P0440: "Recuperarea vaporilor de benzină (EVAP): sistemul",
  P0441: "Recuperarea vaporilor de benzină: debit de purjare incorect",
  P0442: "Recuperarea vaporilor de benzină: scurgere mică",
  P0443: "Supapa de purjare a canistrei: circuitul",
  P0455: "Recuperarea vaporilor de benzină: scurgere mare (bușonul rezervorului?)",
  P0456: "Recuperarea vaporilor de benzină: scurgere foarte mică",
  P0480: "Ventilatorul de răcire: circuitul de comandă",
  P0500: "Senzorul de viteză a mașinii",
  P0501: "Senzorul de viteză a mașinii: valori în afara domeniului",
  P0505: "Reglajul turației de relanti",
  P0506: "Turația de relanti mai mică decât cea cerută",
  P0507: "Turația de relanti mai mare decât cea cerută",
  P0560: "Tensiunea de alimentare (bateria)",
  P0562: "Tensiune de alimentare prea mică",
  P0563: "Tensiune de alimentare prea mare",
  P0571: "Contactul de la pedala de frână: circuitul",
  P0600: "Comunicarea între calculatoare (CAN)",
  P0601: "Calculatorul motorului: memoria (sumă de control)",
  P0604: "Calculatorul motorului: memoria RAM",
  P0606: "Calculatorul motorului: procesorul",
  P0627: "Pompa de benzină: circuitul de comandă",
  P0638: "Clapeta de accelerație: actuatorul, domeniul",
  P0641: "Tensiunea de referință a senzorilor (A)",
  P0651: "Tensiunea de referință a senzorilor (B)",
  P2004: "Clapetele din galeria de admisie: blocate deschise",
  P2015: "Clapetele din galeria de admisie: senzorul de poziție",
  P2101: "Clapeta de accelerație: motorul de acționare, performanță",
  P2106: "Clapeta de accelerație: funcționare de avarie forțată",
  P2135: "Clapeta / pedala: senzorii A și B necorelați",
  P2138: "Pedala de accelerație: senzorii D și E necorelați",
  P2177: "Amestec prea sărac în afara relantiului",
  P2178: "Amestec prea bogat în afara relantiului",
  P2187: "Amestec prea sărac la relanti",
  P2188: "Amestec prea bogat la relanti",
  P2195: "Sonda lambda înainte de catalizator: blocată pe sărac",
  P2196: "Sonda lambda înainte de catalizator: blocată pe bogat",
  P2237: "Sonda lambda înainte de catalizator: circuitul de pompare",
  P2270: "Sonda lambda după catalizator: blocată pe sărac",
  P2271: "Sonda lambda după catalizator: blocată pe bogat",
  P2279: "Priză de aer în admisie",
  P2293: "Regulatorul presiunii benzinei: performanță",
  P2563: "Turbo: senzorul de poziție al supapei de reglare",
  // Climatronic Golf 6 (746), 23.11: motorașul V426 se blochează (punct mort), clapeta nu ajunge unde i se cere.
  B1091: "Climatronic: motorașul clapetei de distribuție față (V426)",
};

// După familie, cât nu e în listă (cifrele 2–3 ale codurilor P0 / P2).
const FAMILY: Array<[RegExp, string]> = [
  [/^P[02]0/, "Amestecul, aerul și injecția (aerul secundar, calarea)"],
  [/^P[02]1/, "Măsurarea aerului și amestecul (senzori)"],
  [/^P[02]2/, "Injecția și turbo"],
  [/^P[02]3/, "Aprinderea și rateurile"],
  [/^P[02]4/, "Controlul emisiilor (catalizator, vapori de benzină)"],
  [/^P[02]5/, "Viteza, relantiul și alimentarea"],
  [/^P[02]6/, "Calculatorul motorului și ieșirile lui"],
  [/^P[02]7/, "Cutia de viteze"],
  [/^P1/, "Cod propriu VW (motor)"],
  [/^P3/, "Cod propriu (motor)"],
  [/^C/, "Șasiu (frâne, ABS, direcție)"],
  [/^B/, "Caroserie (airbag, confort, clima)"],
  [/^U/, "Comunicarea între module"],
];

export function describeDtc(code: string): Dtc {
  const text = KNOWN[code];
  if (text) return { code, text, known: true };
  const fam = FAMILY.find(([re]) => re.test(code));
  return { code, text: fam ? `${fam[1]}: caută după cod` : "Cod necunoscut: caută după cod", known: false };
}

// ---------------------------------------------------------------------------
// Erorile modulelor prin UDS (19 02 = după stare): „59 02 <masca> (<3 octeți cod> <stare>)…”.
// Codul: primii 2 octeți ca la OBD (P/C/B/U + 4 cifre), al treilea = felul defectului (VW îl
// afișează după cod, „U1013 00”). Starea (ISO 14229): bitul 0 = defect acum, 2 = în așteptare,
// 3 = confirmat (memorat), 7 = cere martorul aprins.
// ---------------------------------------------------------------------------

export interface UdsDtc {
  code: string; // „U1013 00”
  sae: string; // „U1013” (pentru descriere și pentru a nu-l dubla cu OBD)
  status: number;
  active: boolean; // defectul e prezent acum
  confirmed: boolean; // memorat
  pending: boolean;
  mil: boolean; // modulul cere martorul aprins
}

export function parseUdsDtcs(hex: string | null | undefined): UdsDtc[] | null {
  if (typeof hex !== "string" || !/^([0-9A-Fa-f]{2})+$/.test(hex)) return null;
  const b = hex.match(/../g)!.map((x) => parseInt(x, 16));
  if (b[0] !== 0x59 || b[1] !== 0x02 || b.length < 3) return null;
  const out: UdsDtc[] = [];
  for (let i = 3; i + 3 < b.length; i += 4) {
    const status = b[i + 3];
    if ((b[i] === 0 && b[i + 1] === 0 && b[i + 2] === 0) || (status & 0x0d) === 0) continue; // fără nimic de arătat
    const sae = dtcCode(b[i], b[i + 1]);
    out.push({
      code: `${sae} ${b[i + 2].toString(16).toUpperCase().padStart(2, "0")}`,
      sae,
      status,
      active: (status & 0x01) !== 0,
      confirmed: (status & 0x08) !== 0,
      pending: (status & 0x04) !== 0,
      mil: (status & 0x80) !== 0,
    });
  }
  return out;
}

/** Modulele citite prin UDS (adresa de trimitere → numele de pe ecran). */
export const UDS_MODULES: Record<string, string> = {
  "7E0": "Motor",
  "714": "Bord",
  "746": "Climatronic",
  "715": "Airbag",
};
