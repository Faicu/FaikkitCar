# Codurile OBD ale Golf-ului – situația

Ce știm despre codurile citite prin priza OBD (adaptorul vLinker MC+): ce înseamnă, ce e doar bănuit și cât s-a
scanat. Se actualizează la fiecare descoperire și se urcă pe GitHub. Catalogul complet, cu toate valorile și
istoricul lor, e pe site: Navigație → „Scanarea OBD” (tabelele `obd_code` și `obd_sample` de pe server).

Totul cu OBD e **de test**: comutatorul „OBD (teste)” oprește tot, iar călătoriile și costurile nu depind de el.

**Mașina:** VW Golf 6 (1K), 1.2 TSI CBZB, 2012, VIN `WVWZZZ1KZDW032311`.

**Ultima actualizare:** 21.11.2026

---

## 1. Scanarea

| Lucrarea | Stare (21.11, ~22:00) |
|---|---|
| Motor (7E0), 0000–FFFF | 🔄 19% · 546 de coduri cu răspuns |
| OBD standard: 01, 09, 06, 02, erorile | ⏳ la rând |
| Climatronic (746), 0A3B–FFFF (0000–0A3A gata) | ⏳ la rând |
| Recitirea în buclă (motor, OBD 01, bord, climatronic) | ⏳ după scanări |
| Bord (714), 0000–FFFF | ✅ 21.11 · 168 de coduri cu răspuns, 2 refuzate |

Viteza: ~6 coduri/s pe loc, ~2 coduri/s în mers. Ordinea și progresul: `obd_scan_job` (detalii în `CLAUDE.md`).

## 2. Modulele

| Adresă (cerere / răspuns) | Modul | Piesa | Prin priză |
|---|---|---|---|
| 7E0 / 7E8 | Motor (J623), Simos 10.12 | 03F906070GA, software 5413, hardware 03F907309R H10, ODX `EV_ECM12TFS021` | ✅ OBD + UDS |
| 714 / 77E | Bord (J285), Continental VDO | 5K0920873, software 0705, hardware H05, `EV_Kombi_UDS_VDD_RM09` | ✅ UDS |
| 746 / 7B0 | Climatronic (J255) | 5K0907044GK, `EV_Climatronic` | ✅ UDS (uneori „ocupat”, 7F 22 21) |
| 715 / 77F | Airbag | 5C0959655B | ✅ UDS (nu-l scanăm: utilizatorul nu vrea) |
| 710, 713, 70E, 712 | Gateway, ABS, confort / BCM (luminile), direcția | – | ❌ nu răspund (protocolul vechi VW, TP2.0) |
| 7E1 | Cutia | – | ❌ manuală, nu există |

## 3. Coduri cu înțeles confirmat

### Bord (714)

| DID | Ce e | Cum se citește | Confirmat |
|---|---|---|---|
| **2224** | Avertizarea de **bec ars** de pe bord | bitul 0x01 din primul octet (20 02 bun → 21 02 ars → 20 06 schimbat) | 21.11, proiectorul dreapta față. Folosit în aplicație din 1.1.107: „Bord · bec” pe Navigație |
| **2206** | Litrii din rezervor | 1 octet, litri (0x34 = 52) | 21.11, la fel ca CAN c104 |
| **1018** | Kilometrajul **de la ultima oprire** (nu cel live) | primii 4 octeți, cuvintele de 16 biți inversate: `C0 62 00 03` = 0x0003C062 = 245.858 km | 21.11, după o oprire |
| **1017**, **1019**, **101A** | Conțin și ele kilometraje în același format | – | 21.11, sensul exact neclar |
| **2216** | Ceasul bordului | ora:minutul, 0x1734 = 23:52 | 21.11 |
| **2238** / **2239** | Ora / minutul | 1 octet fiecare | 21.11 |
| **F40C** | Turația ×4 (probabil oglinda PID-ului OBD 0C) | 0x0BD8 / 4 = 758 rpm la relanti | 21.11, probabil |
| **F40D** | Viteza, km/h (probabil oglinda PID-ului 0D) | 00 pe loc, 05 la mers încet | 21.11, probabil |
| F187 / F191 | Piesa / hardware | text: „5K0920873” | ✅ |
| F189 | Versiunea de software | „0705” | ✅ |
| F190 | VIN | text | ✅ |
| F197 | Numele modulului | „KOMBI” | ✅ |
| F19E | Fișierul de descriere (ODX) | „EV_Kombi_UDS_VDD_RM09” | ✅ |
| F1AA | Codul componentei | „J285” | ✅ |
| F192 | Piesa furnizorului | „A2C34535101” (Continental VDO) | ✅ |
| F1A3 | Versiunea de hardware | „H05” | ✅ |

### Motor (7E0)

| Cod | Ce e | Confirmat |
|---|---|---|
| UDS F190 | VIN | 20.11 |
| UDS F187 / F189 / F197 | 03F906070GA / 5413 / Simos 10.12 | 20.11 |
| OBD 01: PID-urile suportate | 03–07, 0B–0F, 11, 13, 15, 1C, 1F, 21, 23, 2E, 30, 31, 33, 34, 3C, 41–47, 49, 4A, 4C, 56; **fără MAF (10) și fără consum (5E)** | 20.11 |
| OBD 01 citite permanent | 0C turația, 0B presiunea din galerie, 0F aerul admis, 03 starea injecției, 49 pedala; la 2 s 05 răcirea, 04 sarcina, 44 lambda cerut, 06/07 corecții, 0E avansul. Consumul (test) = din aerul aspirat | 21.11 |
| OBD 09 | VIN, CALID `03F906070GA 5413`, CVN C3664742, „ECM-EngineControl” | 20.11 |

### Climatronic (746)

Nimic interpretat încă (25 de coduri cu răspuns până la 0A3A, 14 refuzate „condiții neîndeplinite”).

## 4. Candidați (bănuiți, neconfirmați)

| Modul / DID | Ce am văzut | Bănuială |
|---|---|---|
| Bord **1007** | crește repede în mers | un contor; **candidat pentru consumul de combustibil** (de comparat cu litrii pe un drum) |
| Bord 2205 | 0x28 la 41, 49 și 54 km/h | nu e viteza |
| Bord 100A, 1014, 220C, 202F, 220D | variază singure (100A 92 → 90, 202F 93 → 97) | temperaturi, tensiuni? |
| Bord 2243, 2244, 224B, 2250, 2251, 2259, 225A, 225B, 2290, 2291 | toate +1 între 21.11 noaptea și 21.11 seara; nu se schimbă la o repornire a motorului | contoare (nu de porniri) |
| Bord 2224, al doilea octet | 02 → 06 după schimbarea becului | necunoscut |
| Bord 2203, 2260, 2261, 2294, 2295, 2297, 2298, 2299 | diferă între citiri | senzori? |
| Bord F405, F442 | BD → B9, 8B → 8A | oglinzi OBD (temperatură, tensiune?) |
| Motor | cantitatea injectată (consum exact) | de găsit în scanare |

## 5. Erorile citite (stare la 21.11)

| Modul | Cod | Ce e | Stare |
|---|---|---|---|
| Climatronic | **B1091 71** | motorașul clapetei de distribuție a aerului față: blocat | prezentă; simptom confirmat (aer la centru lipsă), de calibrat / reparat |
| Climatronic | B1091 72 | același motoraș, blocat deschis | memorată |
| Motor | – | 63 de intrări, toate „test neterminat” (0x40/0x50): calculatorul resetat de curând | fără erori |
| Airbag | – | o intrare, status 0x10 | fără erori |
| Bord | bec ars (2224) | proiectorul dreapta față | ✅ schimbat 21.11 |

## 6. Cum se citesc (detalii tehnice)

- CAN 11 biți 500k (protocolul ELM 6). Alt modul: `ATSH<tx> ATCRA<rx> ATFCSH<tx>` (în ordinea asta).
- UDS: `22 <DID>` citire, `19 02 FF` erori. Doar citiri: nimic din 04, 10, 2E, 31, 14, 11, 27, 28, 85.
- Răspunsul poate începe cu `7F <serviciul> 78` (așteaptă) sau `7F 22 21` (ocupat): se aruncă, vine răspunsul după.
- `7F 22 31` = codul nu există; `7F 22 22` = există, dar condiții neîndeplinite; `7F 22 33` = acces refuzat.
- Mai multe PID-uri OBD pe o cerere: cifra de la sfârșit = numărul de cadre CAN așteptate (`010C0B0F03492`,
  `0105044406070E3`); cu „1” vine doar primul cadru.
- Mai multe DID-uri pe o cerere (până la 3) merg la motor, bord și climatronic.
- Stiva Bluetooth a navigației se poate bloca (adaptorul se vede, dar nu acceptă legătura): aplicația repornește
  singură Bluetooth-ul Android (din 1.1.104).

## 7. Istoric

| Data | Ce s-a aflat |
|---|---|
| 20.11 | Adaptorul în mașină; UDS la motor; fără MAF / consum în OBD; modulele care răspund |
| 21.11 | Bordul scanat complet; kilometrajul în 1018; litrii în 2206; scanarea mutată pe server |
| 21.11 seara | Erorile climatronicului (B1091); erorile UDS ale motorului / airbag-ului citite (7F 19 78); becul ars = 2224 bitul 0x01; ceasul în 2216 / 2238 / 2239 |
