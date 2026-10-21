// ---------------------------------------------------------------------------
// Categoriile jurnalului: fiecare linie trimisă de aplicația din mașină intră într-o bucată,
// după cum începe. Pur (fără bază de date), folosit și de site pentru etichete.
// ---------------------------------------------------------------------------

export const LOG_CATEGORIES = [
  { id: "sistem", label: "Pornire și somn" },
  { id: "masina", label: "Mașina" },
  { id: "calatorii", label: "GPS" },
  { id: "sunet", label: "Sunet și voce" },
  { id: "actualizari", label: "Actualizări" },
  { id: "can", label: "CAN și diagnostic" },
  { id: "comenzi", label: "Comenzi la distanță" },
] as const;

export type LogCategory = (typeof LOG_CATEGORIES)[number]["id"];

/** Categoriile arătate în „Evenimente” (implicit): toate, fără CAN / diagnostic și comenzi. */
export const EVENT_CATEGORIES: LogCategory[] = ["sistem", "masina", "calatorii", "sunet", "actualizari"];

const RULES: Array<[RegExp, LogCategory]> = [
  [/^(CAN|DIAG) /, "can"],
  [/^(Comanda #|Comenzi de la distanta)/, "comenzi"],
  [/^Actualizare/, "actualizari"],
  [/^Calatorii/, "calatorii"],
  [/^(Redare|Volum|Focus audio|Lista audio|Voce|Pauza|Usa deschisa|Selector fisiere)/, "sunet"],
  [/^(Frana de mana|Contact|Pornire motor|Rezervor|Lumini|Date masina)/, "masina"],
];

/** Tot ce nu se potrivește (pornirea serviciului, treziri, somn, ecran, căderi...) e „sistem”. */
export function logCategory(line: string): LogCategory {
  for (const [re, cat] of RULES) if (re.test(line)) return cat;
  return "sistem";
}
