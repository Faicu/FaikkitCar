# Arhiva diagnozei OBD (mutată din /root pe 27.11.2026)

Date brute și unelte din testele la mașină (21–25.11.2026). Rezultatele au fost trecute în `OBD-CODURI.md`, în tabelele
`obd_code` / `obd_sample` și în `web/server/obd-names.ts`; aici rămâne dovada și uneltele, dacă trebuie recitite.

- `obd-arhiva/` — citirile bordului (714) înainte / după acțiuni (becul ars, climatronicul), scanări parțiale vechi,
  copia lucrărilor de scanare terminate (`obd-scan-jobs-terminate-2026-11-25.json`).
- `bordsnap/` — `snap.sh <nume>` citește tot bordul prin mașină (cere navigația trează și `OBD (teste)` pornit), `diff.sh <a> <b>`
  compară două citiri; `dbcmd.cjs` pune comanda direct în `car_cmd` (ca faikkitcar). Citirile comparate pe 24.11 (`baza`, `frana`,
  `volan`, `marsarier`, `blitz`…) sunt `*.txt`; `*.raw` nu se păstrează (se refac).
- `carscanner/` — exporturile Car Scanner (24.11): erori, ECU, senzori; jurnalele adaptorului sunt comprimate (`.gz`).
