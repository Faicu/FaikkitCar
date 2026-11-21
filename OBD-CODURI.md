# Codurile OBD ale Golf-ului – situația

Ce știm despre codurile citite prin priza OBD (adaptorul vLinker MC+): ce înseamnă, ce e doar bănuit și cât s-a
scanat. Se actualizează la fiecare descoperire și se urcă pe GitHub. Catalogul complet, cu toate valorile și
istoricul lor, e pe site: Navigație → „Scanarea OBD” (tabelele `obd_code` și `obd_sample` de pe server).

Totul cu OBD e **de test**: comutatorul „OBD (teste)” oprește tot, iar călătoriile și costurile nu depind de el.

**Mașina:** VW Golf 6 (1K), 1.2 TSI CBZB, 2012, VIN `WVWZZZ1KZDW032311`.

**Ultima actualizare:** 22.11.2026 (analiza codurilor găsite: valorile comparate cu CAN / OBD / revizia din aceeași clipă)

---

## 1. Scanarea

| Lucrarea | Stare (21.11, ~22:00) |
|---|---|
| Motor (7E0), 0000–FFFF | 🔄 până la 31B0 (19%) · 546 de coduri cu răspuns |
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
| **2260** | **Km până la revizia de ulei** | 2 octeți, km, rotunjit în sus la 100: 0x38A4 = 14.500 → 0x3840 = 14.400 | 22.11: 15.000 − (km de acum − 245.313 de la revizia din 19.10) = 14.419 → 14.500 și 14.396 → 14.400 |
| **2261** | **Zile până la revizia de ulei** | 2 octeți: 0x014D = 333 → 0x014C = 332 | 22.11: 365 − 33 de zile de la 19.10 |
| **2243** | Km de la revizie, în sute | 0x0005 → 0x0006 | 22.11: 581 km → 604 km de la revizie |
| **2244** | Zile de la revizie | 0x0020 = 32 → 0x0021 = 33 | 22.11; 2259 / 225A / 225B au aceeași valoare (copii, probabil) |
| **22A0** / **22A1** | Intervalul reviziei: 150 (× 100 km = 15.000 km) / 365 de zile | fixe | 22.11, se potrivesc cu 2260 / 2261. Bordul e pe revizie fixă 15.000 km / 1 an (nu 8.000 km, cum ai tu în aplicație) |
| 100C / 100D | 15.000 / 30.000 | fixe | 15.000 = intervalul de ulei; 30.000 probabil inspecția |
| **F18B** | Data fabricației bordului | 12 06 18 = 18.06.2012 | 22.11 (cod UDS standard) |
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
| **UDS 121C** | **Kilometrajul**, km | 4 octeți, ordinea normală: 0x0003C098 = 245.912 | 22.11: exact kilometrajul CAN din clipa citirii |
| **UDS 15D4** | Kilometrajul la ultima oprire / la pornirea drumului, în 0,1 km | 0x00258550 = 2.458.960 = 245.896,0 km | 22.11: drumul a pornit de la 245.896 (oprirea din 21.11, 02:58) |
| **UDS 2206** | Litrii din rezervor (la fel ca la bord) | 0x34 = 52 | 22.11, ca CAN c104 |
| OBD 09 | VIN, CALID `03F906070GA 5413`, CVN C3664742, „ECM-EngineControl” | 20.11 |

### Climatronic (746)

Nimic interpretat încă (25 de coduri cu răspuns până la 0A3A, 14 refuzate „condiții neîndeplinite”).

## 4. Candidați (bănuiți, neconfirmați)

| Modul / DID | Ce am văzut | Bănuială |
|---|---|---|
| Bord **1007** | crește cu ~2.900 la fiecare litru estimat din OBD (2.826 pe 24 s, 2.956 pe 47 min, 21.11 noaptea); 16 biți | **contorul de consum al bordului** (calculatorul de bord), probabil ~0,3–0,5 mL pe unitate; de calibrat după un plin |
| Bord 1009 | 603 → 624 → 647 (~0,48 pe minut de mers) | timpul din memoria calculatorului de bord, în minute? |
| Bord **2205**, bitul 0x01 din primul octet | 1 la 4 citiri pe loc cu frâna trasă, 0 la 4 citiri în mers | **frâna de mână trasă** (sau „mașina stă”); de verificat cu frâna trasă în mers / eliberată pe loc |
| Bord **202F** | 146–151; − 50 dă 96–101 °C, cu 1–3 °C peste lichidul de răcire OBD | o temperatură a motorului (lichidul, cum îl arată bordul, sau uleiul cu − 60 = 86–91 °C) |
| Bord 1018 / 1019 / 101A | un jurnal cu un contor (00, 01, 02) și kilometrajul: 245.836, 245.858, 245.878 (mijlocul drumurilor, nu opriri) | un jurnal de evenimente al bordului; evenimentul necunoscut |
| Bord 224B, 2250 / 2251, 2290 / 2291 | +1 deodată cu zilele reviziei (22, 65, 17) | numărători de zile de la alte evenimente |
| Motor **1000** | 0x50 = 80 → 0,75 × 80 − 48 = 12 °C, afară erau 12 °C (CAN) | **temperatura de afară** (formula VW 0,75 × x − 48); la fel 1748 |
| Motor **1001** / **1002** | 116 / 117 → 39 / 39,75 °C, aerul admis OBD 39 °C | **temperatura aerului admis** (doi senzori / modele) |
| Motor **117C** | 751 cu motorul la 751 rpm, 1.410 la ~1.420–1.600 rpm | **turația** |
| Motor 1154 | 750 pe loc, 800 în mers | turația de relanti cerută? |
| Motor 117D | 23 la relanti, 43 la sarcină (MAP 109 kPa) | sarcina / clapeta? |
| Motor 1189–118F | 7 valori ~490–540, toate cresc puțin în 24 s de accelerare | temperaturi modelate (evacuare / catalizator)? |
| Motor 1036 | 0x000377F7 = 227.319 | un kilometraj de la un eveniment vechi (~18.600 km în urmă)? |
| Motor 11F4–11FD, 16DF | text: „mec_open_cps”, „no_error” (×9), „SA3O6010 CBK0191” | stări ale unor teste / un cod intern |
| Bord 2205 | 0x28 la 41, 49 și 54 km/h | nu e viteza |
| Bord 100A, 1014, 220C, 202F, 220D | variază singure (100A 92 → 90, 202F 93 → 97) | temperaturi, tensiuni? |
| Bord 2224, al doilea octet | 02 → 06 după schimbarea becului | necunoscut |
| Bord 2203, 2208, 220C, 2273, 2275, 2277, 2294, 2295, 2297, 2299 | diferă între citiri (2275: 0B pe loc, 07 în mers) | senzori? |
| Bord F405, F442 | BD → B9, 8B → 8A | oglinzi OBD (temperatură, tensiune?) |
| Motor | cantitatea injectată (consum exact) | de găsit în restul scanării (31B0–FFFF) |

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
| 22.11 | Analiza codurilor: intervalul reviziei în bord (2260 / 2261 / 2243 / 2244, potrivite cu revizia din 19.10), kilometrajul în motor (121C, 15D4), temperaturile și turația în motor (1000–1002, 117C); 1007 ≈ contorul de consum, 2205 ≈ frâna de mână |
