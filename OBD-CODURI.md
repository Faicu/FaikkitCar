# Codurile OBD ale Golf-ului – situația

Ce știm despre codurile citite prin priza OBD (adaptorul vLinker MC+): ce înseamnă, ce e doar bănuit și cât s-a
scanat. Se actualizează la fiecare descoperire și se urcă pe GitHub. Catalogul complet, cu toate valorile și
istoricul lor, e pe site: Navigație → „Scanarea OBD” (tabelele `obd_code` și `obd_sample` de pe server).

Totul cu OBD e **de test**: comutatorul „OBD (teste)” oprește tot, iar călătoriile și costurile nu depind de el.

**Mașina:** VW Golf 6 (1K), 1.2 TSI CBZB, 2012, VIN `WVWZZZ1KZDW032311`.

**Ultima actualizare:** 23.11.2026, 09:20 (pornirea la rece: uleiul, lichidul; TRIP-ul nu e în bord; climatronicul la 46 %)

---

## 1. Scanarea

| Lucrarea | Stare (22.11, seara) |
|---|---|
| **Urmărirea candidaților** (bord 15 coduri, motor 14: doar ce nu e sigur, secțiunea 4) | 🔁 la fiecare pornire și la 2 min, înaintea scanării (din 22.11) |
| OBD standard: 01, 09, 06, 02, erorile | ✅ 22.11, 18:00–18:05 · 01: 38 de PID-uri, 06: 16 teste, erorile: 2 coduri, 09 și 02: nimic nou |
| Motor (7E0), 0000–FFFF | ✅ 22.11, 19:07 · 763 de coduri cu răspuns (2 refuzate, 0x36), 64.773 inexistente, 0 lente: toate cele 65.536 au o stare |
| Bord (714), a doua trecere 0000–FFFF | ✅ 23.11, 08:49 · aceleași 170 de coduri ca prima trecere, 65.366 inexistente, 0 lente |
| Climatronic (746), 0000–FFFF | 🔄 46 % (30.140 / 65.536) la 23.11, 09:17 · 284 de coduri (62 cu valori, 55 refuzate, 138 lente), 29.910 inexistente |
| Recitirea în buclă (motor, OBD 01, bord, climatronic) | 🔄 motorul, prima trecere: 654 / 763 (22.11, 19:30); bordul a început; reiau după bord și climatronic. Scopul: multe citiri ale fiecărui cod, comparate cu OBD / CAN (consumul) |
| Bord (714), prima trecere 0000–FFFF | ✅ 21.11 · 168 de coduri cu răspuns, 2 refuzate |

**Fără coduri pierdute (din 22.11, aplicația 1.1.110):** se notează și codurile care **nu există** (tabela `obd_absent`), deci
fiecare cod din 0000–FFFF ajunge într-una din stări: cu răspuns, refuzat, lent, inexistent. Ce n-a primit niciun răspuns
clar se recitește automat pe loc la sfârșitul intervalului. Ce e verificat nu se mai scanează a doua oară.

Viteza: din 1.1.113 ~40–60 coduri/s la motor (cererile cu cifra „1”, secțiunea 6); înainte ~6 pe loc, ~2 în mers. Ordinea și progresul: `obd_scan_job` (detalii în `CLAUDE.md`).

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
| **2260** | **Km până la revizia de ulei** | 2 octeți, km, rotunjit în sus la 100: 0x38A4 = 14.500 → 0x3840 = 14.400 | 22.11: 15.000 − (km de acum − 245.313 de la revizia din 19.10) = 14.419 → 14.500 și 14.396 → 14.400 |
| **2261** | **Zile până la revizia de ulei** | 2 octeți: 0x014D = 333 → 0x014C = 332 | 22.11: 365 − 33 de zile de la 19.10 |
| **2243** | Km de la revizie, în sute | 0x0005 → 0x0006 | 22.11: 581 km → 604 km de la revizie |
| **2244** | Zile de la revizie | 0x0020 = 32 → 0x0021 = 33 | 22.11; 2259 / 225A / 225B au aceeași valoare (copii, probabil) |
| **22A0** / **22A1** | Intervalul reviziei: 150 (× 100 km = 15.000 km) / 365 de zile | fixe | 22.11, se potrivesc cu 2260 / 2261. Bordul e pe revizie fixă 15.000 km / 1 an (nu 8.000 km, cum ai tu în aplicație) |
| 100C / 100D | 15.000 / 30.000 | fixe | 15.000 = intervalul de ulei; 30.000 probabil inspecția |
| **F18B** | Data fabricației bordului | 12 06 18 = 18.06.2012 | 22.11 (cod UDS standard) |
| **2216** | Ceasul bordului | ora:minutul, 0x1734 = 23:52 | 21.11 |
| **2238** / **2239** | Ora / minutul | 1 octet fiecare | 21.11 |
| **F40C** | Turația ×4 (oglinda PID-ului OBD 0C) | 0x0BD8 / 4 = 758 rpm la relanti, la fel cu OBD | 21.11 |
| **F40D** | Viteza, km/h (oglinda PID-ului OBD 0D) | 00 pe loc, 05 la mers încet, la fel cu OBD | 21.11 (netestat la viteză mare) |
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
| **UDS 1000** | **Temperatura de afară**, °C | 0,75 × x − 48 (formula VW): 80 → 12, 75 → 8,25, 76 → 9, 78 → 10,5 °C, afară (CAN c139) 12 / 8,5 / 9 / 10,5 °C; 1748 la fel (2 citiri) | 22.11, 4 citiri |
| **UDS 1001 / 1002** | **Temperatura aerului admis**, °C | aceeași formulă (potrivirea dă 0,745 × x − 47,8) | 22.11: 25 de citiri, 12–52 °C, R² 0,9996 față de aerul admis OBD |
| **UDS 11CF / 11D1** | **Temperatura lichidului de răcire**, °C (două copii) | 0,75 × x − 48: 194 → 97,5, 167 → 77,25, 190 → 94,5 °C; lichidul OBD 97 / 77 / 94 °C | 22.11, 3 citiri pe 20 °C |
| **UDS 117C** | **Turația**, rpm | direct: 751 cu motorul la 751 rpm, 1.410 la ~1.420–1.600 rpm | 22.11 |
| UDS 2206 | Litrii din rezervor (la fel ca la bord), probabil | 0x34 = 52 | 22.11, ca CAN c104, dar rezervorul a stat pe 52 toată seara (o singură valoare): **rămâne în urmărire** până se schimbă |
| OBD 09 | VIN, CALID `03F906070GA 5413`, CVN C3664742, „ECM-EngineControl” | 20.11 |

Urmărirea (la pornire și la 2 min) nu mai citește ce e confirmat aici (rezervor la bord, bec, revizia, kilometrajele,
turația și temperaturile motorului, F40C / F40D): rămân doar candidații din secțiunea 4.

Motorul după 469C (scanat 22.11): doar identificarea și oglinzile OBD.
- **F1xx** identificarea UDS: F1AD „CBZB” (codul motorului), F17C „SJ8-FST07.06.1200130056”, F1AB „541354135413”,
  F1A2 „004005”, F17E „--H10---”, F18C seria (zerouri), plus cele știute (F187, F189, F190, F191, F197, F19E, F1AA).
- **F4xx = OBD 01** (F4 + PID-ul): F40C turația ×4, F405 lichidul − 40, F40F aerul admis − 40, F446 afară − 40, F442
  tensiunea ×0,001, F433 presiunea atmosferică (99 kPa), F423 rampa ×10 kPa (48 bar), F43C catalizatorul (413 °C), F41F
  secunde de la pornire, F431 km de la ștergerea erorilor (88), F430 încălziri (3). **F6xx = OBD 06** (testele), **F8xx =
  OBD 09** (VIN, CALID, CVN, numele). Nimic nou față de ce citim deja prin OBD.
- 58DE = 0x0115 (277), singurul cod necunoscut din zonă.

### Climatronic (746)

23.11, scanarea la 46 %: 284 de coduri, din care 62 cu valori în 2600–2655 (blocul de valori de măsură: perechi stânga / dreapta, câteva în jur de 0xE3 = 227), 55 refuzate „condiții” (0x22), 138 lente (modulul ocupat; recitite pe loc la sfârșit). Interpretarea după recitirea în buclă.


Nimic interpretat încă (25 de coduri cu răspuns până la 0A3A, 14 refuzate „condiții neîndeplinite”).

## 4. Candidați (bănuiți, neconfirmați)

| Modul / DID | Ce am văzut | Bănuială |
|---|---|---|
| Bord **1007** | 22.11 seara: **NU e consumul.** 74 min la relanti pe loc (~0,8 L după OBD) → 0 creștere; la prima citire după 12 h de oprire era 17.579 (nu aproape de 0). Creșterile nu urmează nici litrii, nici distanța: +219 pe 1,9 km la 40–70 km/h, +363 pe 1,2 km cu frânări și opriri, +114 pe 120 m de manevre la parcare, 0 la relanti. Înainte (21.11): ~2.900 pe litru estimat, dar doar în mers | crește doar în mers, mai mult cu frânări / manevre: legat de frână sau de direcție? (de verificat: frâna apăsată pe loc) |
| Bord 1009 | 603 → 624 → 647 (~0,48 pe minut de mers) | timpul din memoria calculatorului de bord, în minute? |
| Bord **2205**, bitul 0x01 din primul octet | 1 la 4 citiri pe loc cu frâna trasă, 0 la 4 citiri în mers (și 0x0028 la 41, 49, 54 km/h, 21.11) | **frâna de mână trasă** (sau „mașina stă”); de verificat cu frâna trasă în mers / eliberată pe loc |
| Bord **202F** | 23.11, pornire la rece (lichidul OBD 31 → 86 °C): 68 → 132; ieri cald 143 la lichid 95. Cu x − 50: 18 °C când lichidul avea 31, apoi rămâne în urmă și ajunge aproape de lichid la cald | **temperatura uleiului** (x − 50 °C?): rămâne în urmă la încălzire, ca uleiul. De verificat cu ecranul „Temperatura uleiului” din bord |
| Bord 1017 / 1018 / 1019 / 101A | kilometraje în formatul cu cuvintele de 16 biți inversate (`C0 62 00 03` = 245.858). 1019 = ultima intrare, 101A = cea dinainte (se mută la o intrare nouă), cu un contor 00 / 01 / 02 la 245.836, 245.858, 245.878; 245.858 și 245.878 au fost **în mijlocul drumurilor**, deci 1018 NU e „kilometrajul de la ultima oprire” (cum credeam pe 21.11); după kilometraj, mereu `10 91 11 4D` | un jurnal de evenimente al bordului (o avertizare / o eroare cu kilometrajul ei?). Ipoteză: `10 91` ar putea fi legat de eroarea climatronicului B1091 (clapeta blocată, apare în mers), neverificat |
| Bord 224B, 2250 / 2251, 2290 / 2291 | +1 deodată cu zilele reviziei (22, 65, 17) | numărători de zile de la alte evenimente |
| Motor 1154 | exact 750 la toate citirile pe loc, 800 la toate în mers (5 citiri, 2 zile, rece și cald) | turația de relanti cerută (mai mare în mers) |
| Motor 117D | 23 la relanti, 25–36 la mers încet, 43 la sarcină (MAP 109 kPa), **63 cu injecția tăiată** (frână de motor, sarcina 0) | nu e sarcina (e maximă când sarcina e 0); avansul aprinderii / un unghi? |
| Motor 1189–118F (23.11) | cu pornirea la rece se vede clar: scad cu ~1 la fiecare 5 °C de lichid (118D: R² 0,94 pe 78 °C) | o mărime care scade cu temperatura motorului (frecarea / pierderile modelate?), nu tensiuni |
| Motor 1189–118F (22.11) | 7 valori ~519–540, toate se mișcă împreună cu câteva unități: scad cât se încălzește motorul (1189: 527 rece → 519 cald) și urcă odată cu tensiunea (533 la 14,55 V) | nu temperaturi de evacuare (ar crește); tensiuni (~0,027 V pe unitate?) |
| Motor **3D73** | 0x85 → 0,75 × 133 − 48 = 51,75 °C, lichidul de răcire OBD 51 °C (o singură citire, 22.11) | temperatura motorului (formula VW) |
| Motor 4183 | 0x5D − 40 = 53 °C, lichidul OBD 53 °C (o citire) | altă temperatură a motorului |
| Motor 406A | **cu semn**: −2.500 … +1.400 la relanti (−2.500 cu motorul rece, în jur de 0…+1.300 cald, sare de la o citire la alta), **0 cu injecția tăiată**; 740 de dimineață a fost o coincidență | nu e relantiul cerut; o corecție a regulatorului de relanti (cuplu)? |
| Motor 1036 | 0x000377F7 = 227.319 | un kilometraj de la un eveniment vechi (~18.600 km în urmă)? |
| Motor 11F4–11FD, 16DF | text: „mec_open_cps”, „no_error” (×9), „SA3O6010 CBK0191” | stări ale unor teste / un cod intern |
| Bord 100A, 1014, 220C, 202F, 220D | variază singure (100A 92 → 90, 202F 93 → 97) | temperaturi, tensiuni? |
| Bord 2224, al doilea octet | 02 → 06 după schimbarea becului | necunoscut |
| Bord 2203, 2208, 220C, 2273, 2275, 2277, 2294, 2295, 2297, 2299 | diferă între citiri (2275: 0B pe loc, 07 în mers) | senzori? |
| Bord **F405** | 39 de citiri, 31–96 °C, rece și cald: ≈ 0,76 × x − 43 față de lichidul OBD (R² 0,996) | **temperatura lichidului de răcire** (în formatul bordului) |
| Bord F442 | 8B → 8D | tensiune? |
| Motor 1749 (23.11) | la pornirea la rece = aerul admis (80 → 12 °C), apoi crește odată cu motorul, mai repede decât aerul admis | temperatura din galeria de admisie (încălzită de motor)? |
| Motor 1749 (22.11) | 126 / 121 / 141 → 46,5 / 42,75 / 57,75 °C cu formula VW, cu ~6–7 °C peste aerul admis OBD (39 / 36 / 51) | temperatura din galeria de admisie (după intercooler)? |
| Motor | cantitatea injectată (consum exact) | **nu e după 469C** (acolo doar identificarea și oglinzile OBD); dacă există, e printre codurile 10xx–3Exx: se vede abia din recitirea în buclă (multe citiri, comparate cu litrii OBD) |

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
- UDS: `22 <DID>` citire, `19 02 FF` erori. Doar citiri: nimic din 04, 10, 2E, 31, 14, 11, 27, 28, 85; din 1.1.114 doar în testele
  manuale (`@obd`) `10 03` (sesiunea extinsă: valori care în sesiunea implicită răspund „nu există”, căutăm acolo cantitatea
  injectată) și `10 01`. Scanarea automată rămâne în sesiunea implicită.
  **Test 23.11, sesiunea extinsă pe motor** (7E0, `10 03` → `50 03 003201F4`, motor pornit pe loc; `3E00` ține sesiunea, la ~50 de cereri;
  grupuri de 3 cu „1”, ~36 ms): 0x1000–0x17FF și 0x2000–0x21FF dau **aceleași coduri** ca sesiunea implicită (309 găsite, toate în catalog, 0 noi).
  Rămase netestate: 0x2200–0x2BFF, 0x3900–0x3EFF, 0x4000–0x44FF, 0xF100–0xF8FF (login-ul pentru `car-cmd.ts` a dat 429).
- Răspunsul poate începe cu `7F <serviciul> 78` (așteaptă) sau `7F 22 21` (ocupat): se aruncă, vine răspunsul după.
- `7F 22 31` = codul nu există; `7F 22 22` = există, dar condiții neîndeplinite; `7F 22 33` = acces refuzat.
- Mai multe PID-uri OBD pe o cerere: cifra de la sfârșit = numărul de cadre CAN așteptate (`010C0B0F03492`,
  `0105044406070E3`); cu „1” vine doar primul cadru.
- Mai multe DID-uri pe o cerere (până la 3) merg la motor, bord și climatronic. Peste 3 (cerere > 7 octeți, adică pe
  mai multe cadre CAN) motorul nu răspunde deloc (testat 22.11 cu 4–8).
- Cifra „1” la sfârșitul unei cereri `22` (`22<DID><DID><DID>1`): adaptorul predă după primul răspuns și nu mai
  așteaptă ~200 ms, deci 36–38 ms în loc de 244. Răspunsul pe mai multe cadre vine tăiat (doar primul), așa că
  scanarea (1.1.113) acceptă din cererea rapidă doar `7F 22 31`; orice altceva se reciteste fără cifră.
- Stiva Bluetooth a navigației se poate bloca (adaptorul se vede, dar nu acceptă legătura): aplicația repornește
  singură Bluetooth-ul Android (din 1.1.104).

## 7. Istoric

| Data | Ce s-a aflat |
|---|---|
| 20.11 | Adaptorul în mașină; UDS la motor; fără MAF / consum în OBD; modulele care răspund |
| 21.11 | Bordul scanat complet; kilometraje în 1017–101A; litrii în 2206; scanarea mutată pe server |
| 21.11 seara | Erorile climatronicului (B1091); erorile UDS ale motorului / airbag-ului citite (7F 19 78); becul ars = 2224 bitul 0x01; ceasul în 2216 / 2238 / 2239 |
| 22.11 | Ordinea cozii: OBD standard întâi (scurt), apoi motorul, bordul, climatronicul |
| 22.11 | Acoperirea completă: codurile inexistente notate (`obd_absent`), golurile recitite la sfârșit; motorul, climatronicul și bordul reluate de la 0000 cu noua aplicație (1.1.110), fiindcă scanările de până acum nu notau ce lipsește și săreau grupurile fără răspuns |
| 22.11 | Revizia cozii: urmărirea candidaților (1007, 2205, 202F … la pornire și la 2 min); buclele recitesc și codurile refuzate de condiții; din 1.1.108 grupurile fără răspuns nu se mai pierd (intră la „lente”) |
| 22.11 dimineața | Motorul 31B0–469C în drumul de 3 km (~11 coduri/s, cele inexistente răspund repede); 467D–469C „lente” doar pentru că motorul se oprise; bordul nu s-a citit (bucla lui vine după scanări), deci testul pentru 1007 n-a prins oprirea de 7,6 h |
| 22.11 seara | Scanarea rapidă (1.1.113): ~62 coduri/s în loc de ~8. Verificată pe motorul 0000–21FC (lucrarea 15, ~8 min), comparată cu o copie a bazei de dinainte: 518 coduri comune, niciunul pierdut, niciunul nou, nicio stare diferită, 0 coduri fără răspuns; 17 coduri „lente” (grupuri fără răspuns la prima trecere) rezolvate ca inexistente; valorile diferite = măsurători live |
| 23.11 | Pornire la rece: bord F405 = lichidul de răcire, 202F probabil uleiul (x − 50); motorul 1189–118F scad cu temperatura; TRIP-ul (80,4 / 80,6) și consumul mediu (49,9) NU sunt în codurile bordului citite în sesiunea implicită; climatronicul la 46 % |
| 22.11 seara | Bord 1007 NU e contorul de consum (nu crește la relanti, nu se resetează după 12 h) |
| 22.11 seara | Motorul complet (763 de coduri). Analiza cu OBD / CAN din aceleași clipe: 11CF / 11D1 = lichidul de răcire și 1001 / 1002 = aerul admis (confirmate), 1000 / 1748 = afară; 406A NU e relantiul cerut (corecție cu semn, 0 la injecția tăiată); după 469C doar identificare și oglinzi OBD (F4xx, F6xx, F8xx) |
| 22.11 seara | Urmărirea redusă la ce nu e sigur (bord 21 → 15 coduri, motor 19 → 13); confirmate și mutate în secțiunea 3: motor 1000, 1001 / 1002, 117C |
| 22.11 | Analiza codurilor: intervalul reviziei în bord (2260 / 2261 / 2243 / 2244, potrivite cu revizia din 19.10), kilometrajul în motor (121C, 15D4), temperaturile și turația în motor (1000–1002, 117C); 1007 ≈ contorul de consum, 2205 ≈ frâna de mână |
