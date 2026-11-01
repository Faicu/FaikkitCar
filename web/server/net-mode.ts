// Tipul de rețea preferat al modemului (valorile RILConstants din Android), pe înțeles.
// Pur: folosit și de site (UnitCard.tsx).

/** Tipul de rețea pe înțeles (valorile RILConstants din Android). */
export function netModeLabel(code: number | null | undefined): string | null {
  if (code === null || code === undefined) return null;
  const names: Record<number, string> = {
    0: "3G/2G",
    1: "doar 2G",
    3: "2G/3G",
    9: "4G/3G/2G automat",
    11: "doar 4G (LTE only)",
    12: "4G/3G",
    26: "5G/4G/3G/2G automat",
    33: "5G/4G/3G/2G automat",
  };
  return names[code] ?? `cod ${code}`;
}
