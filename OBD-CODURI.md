# Codurile OBD ale Golf-ului – situația

Ce știm despre codurile citite prin priza OBD (adaptorul vLinker MC+): ce înseamnă, ce e doar bănuit și cât s-a
scanat. Se actualizează la fiecare descoperire și se urcă pe GitHub. Catalogul complet, cu toate valorile și
istoricul lor, e pe site: Navigație → „Scanarea OBD” (tabelele `obd_code` și `obd_sample` de pe server).

Totul cu OBD e **de test**: comutatorul „OBD (teste)” oprește tot, iar călătoriile și costurile nu depind de el.

**Mașina:** VW Golf 6 (1K), 1.2 TSI CBZB, 2012, VIN `WVWZZZ1KZDW032311`.

**Ultima actualizare:** 24.11.2026, 10:45 (2294 = autonomia nerotunjită; 2299 = consumul mediu pe termen lung; 2224 = martorii: avarii, faza lungă; lumina de afară; 2297 = autonomia; bordul comparat cu ecranul: 1014 = temperatura de afară, 2205 = frâna de mână, 202F = uleiul; consumul de pe ecran față de OBD)

---

## 1. Scanarea

| Lucrarea | Stare (24.11) |
|---|---|
| **Urmărirea candidaților** (bord 14 coduri, motor 11: doar ce nu e sigur, secțiunea 5; lista se schimbă singură la pornirea serverului după `WATCH`) | 🔁 la fiecare pornire și la 2 min, înaintea scanării (din 22.11). 24.11: scoase din bord cele confirmate (1014, 2205, 202F, F405), adăugate 2294 / 2296 / 229A |
| **Citirile cu motorul din aceeași clipă** (din aplicația 1.1.121, 24.11) | Urmăririle de bord și motor la **30 s** (înainte 2 min); fiecare citire vine cu valorile OBD din clipa ei (turația, MAP, aerul admis, lichidul, sarcina, viteza, pedala, avansul, lambda, injecția, corecția) și se păstrează toată (`obd_read`), nu doar schimbările. Analiza: `web/scripts/obd-analyze/reads.cjs` (perechi exacte, deci merge și pentru aer / sarcină / avans) |
| Urmărirea „clapeta de distribuție” (climatronic 262E / 262F) | 🔁 la 30 s (din 24.11), prima în coadă; alerta „Climatronic · clapeta” |
| Seria deasă a candidaților injecției (motor, lucrarea 17) | ⏸ oprită din 23.11; 24.11: candidații ei nu sunt cantitatea injectată, iar estimarea OBD se potrivește cu bordul (secțiunea 4) |
| OBD standard: 01, 09, 06, 02, erorile | ✅ 22.11, 18:00–18:05 · 01: 38 de PID-uri, 06: 16 teste, erorile: 2 coduri, 09 și 02: nimic nou |
| Motor (7E0), 0000–FFFF | ✅ 22.11, 19:07 · 763 de coduri cu răspuns (2 refuzate, 0x36), 64.773 inexistente, 0 lente: toate cele 65.536 au o stare |
| Bord (714), a doua trecere 0000–FFFF | ✅ 23.11, 08:49 · aceleași 170 de coduri ca prima trecere, 65.366 inexistente, 0 lente |
| Climatronic (746), 0000–FFFF | ✅ 24.11, 08:13 (recitirea celor lente 08:19, 247 de coduri: 9 găsite, restul inexistente) · 175 de coduri (120 cu valori, 55 refuzate), 65.361 inexistente, 0 lente: toate cele 65.536 au o stare. După 46 % doar identificarea (F15B–F1DF) și F405 / F40C / F40D |
| Recitirea în buclă (motor, OBD 01, bord, climatronic) | 🔄 trecerea 10 a fiecăreia; motorul 453 / 763 la 24.11, 08:27 (singura care mai rulează după scanări). Scopul: multe citiri ale fiecărui cod, comparate cu OBD / CAN (consumul) |
| Bord (714), prima trecere 0000–FFFF | ✅ 21.11 · 168 de coduri cu răspuns, 2 refuzate |

**Fără coduri pierdute (din 22.11, aplicația 1.1.110):** se notează și codurile care **nu există** (tabela `obd_absent`), deci
fiecare cod din 0000–FFFF ajunge într-una din stări: cu răspuns, refuzat, lent, inexistent. Ce n-a primit niciun răspuns
clar se recitește automat pe loc la sfârșitul intervalului. Ce e verificat nu se mai scanează a doua oară.

Viteza: din 1.1.113 ~40–60 coduri/s la motor (cererile cu cifra „1”, secțiunea 7); înainte ~6 pe loc, ~2 în mers. Ordinea și progresul: `obd_scan_job` (detalii în `CLAUDE.md`).

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
| **2224** | **Martorii bordului**, primul octet: 0x01 **bec ars**, 0x04 **avariile**, 0x40 **faza lungă** (0x20 mereu aprins) | 20 02 normal · 21 02 bec ars · 24 02 avarii · 60 02 faza lungă | 0x01: 21.11, proiectorul dreapta față (în aplicație din 1.1.107, „Bord · bec”). 0x04 și 0x40: 24.11, pornite / oprite pe loc, câte 2 citiri. Semnalizarea simplă, faza scurtă, frâna de picior și marșarierul **nu** apar în bord |
| **224D–224F, 2250 / 2251, 2259–225B, 2290 / 2291** | **Lumina de afară** (senzorul de lumină / ploaie de la oglindă), câteva niveluri, probabil pentru iluminarea bordului | 2250: ~64 noaptea, 74 → 112 pe 24.11 dimineața, 114 → **168** cu blitzul telefonului pe senzor și înapoi la 112 imediat după; salturile 138 / 142 / 200 = soare direct | 24.11: blitz pe senzor → toată grupa crește (224D 25 → 56, 2290 40 → 53), apoi revine. Nu sunt numărători de zile (cum credeam pe 22.11) |
| **2206** | Litrii din rezervor | 1 octet, litri (0x34 = 52) | 21.11, la fel ca CAN c104 |
| **2260** | **Km până la revizia de ulei** | 2 octeți, km, rotunjit în sus la 100: 0x38A4 = 14.500 → 0x3840 = 14.400 | 22.11: 15.000 − (km de acum − 245.313 de la revizia din 19.10) = 14.419 → 14.500 și 14.396 → 14.400 |
| **2261** | **Zile până la revizia de ulei** | 2 octeți: 0x014D = 333 → 0x014C = 332 | 22.11: 365 − 33 de zile de la 19.10 |
| **2243** | Km de la revizie, în sute | 0x0005 → 0x0006 | 22.11: 581 km → 604 km de la revizie |
| **2244** | Zile de la revizie | 0x0020 = 32 → 0x0021 = 33 | 22.11 (0x24 = 36 pe 24.11). 2259 / 225A / 225B aveau atunci aceeași valoare, dar sunt lumina de afară (24.11): coincidență |
| **22A0** / **22A1** | Intervalul reviziei: 150 (× 100 km = 15.000 km) / 365 de zile | fixe | 22.11, se potrivesc cu 2260 / 2261. Bordul e pe revizie fixă 15.000 km / 1 an (nu 8.000 km, cum ai tu în aplicație) |
| 100C / 100D | 15.000 / 30.000 | fixe | 15.000 = intervalul de ulei; 30.000 probabil inspecția |
| **F18B** | Data fabricației bordului | 12 06 18 = 18.06.2012 | 22.11 (cod UDS standard) |
| **1014** | **Temperatura de afară** (cea de pe ecranul bordului) | 1 octet: x / 2 − 50 °C (0x67 = 1,5 °C) | 24.11: ecranul arăta 1,5 °C; toate cele 9 valori din 21–24.11 se potrivesc exact cu CAN c139 (0x7A = 11, 0x76 = 9, 0x72 = 7, 0x6E = 5, 0x68 = 2) |
| **2205** | **Frâna de mână**: bitul 0x01 din primul octet (1 = trasă) | `01 28` trasă → `00 28` eliberată, pe loc, cu motorul pornit | 24.11, 09:38: eliberată pe loc → 0 (deci frâna, nu „mașina stă”); al doilea octet (0x28) necunoscut |
| **202F** | **Temperatura uleiului** | 1 octet: x − 58 °C (0x90 = 144 → 86 °C) | 24.11: ecranul „Oil temperature” 86 °C la 0x90 (de două ori), apoi 87 °C la 0x91 (după o accelerare pe loc), deci 1 °C pe unitate. Se potrivește și cu pornirea la rece din 23.11 (68 → 10 °C, afară 7,5 °C) |
| **2297** | **Autonomia** de pe ecran („Range”), km, rotunjită la 10 | 2 octeți: 0x01EA = 490 | 24.11, 09:46: ecranul 490 km, codul 490 (scade de ieri: 520 → 510 → 500 → 490, din 10 în 10, ca afișajul) |
| **2299** | **Consumul mediu pe termen lung** (memoria 2), × 0,1 L/100 km | 2 octeți: 0x006B = 107 = 10,7 | 24.11, 10:16: ecranul (memoria 2) 10,7 L/100 km; codul a urcat încet 103 → 107 în 21–24.11 (zile reci, drumuri scurte). Memoria 1 (49,3 / 12,3) nu e în bord |
| **F405** | **Temperatura lichidului de răcire** (oglinda PID-ului OBD 05, în formatul bordului) | 1 octet: ≈ 0,76 × x − 43 °C (0xB6 → 95 °C) | 39 de citiri, 31–96 °C (R² 0,996); 24.11, 95 °C și la OBD |
| **2294** | **Autonomia nerotunjită**, km (2297 = ea rotunjită la 10) | 2 octeți: 0x01D9 = 473 → ecranul 470 | 24.11, analiza: în toate cele 12 perechi din 21–24.11, 2297 = 2294 rotunjit la 10 (512 → 510, 629 → 630, 558 → 560, 521 → 520, 486 → 490, 475 → 480, 473 → 470) |
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
| **UDS 392A** | **Temperatura lichidului de răcire** (a treia copie) | 0,75 × x − 48 °C | 24.11, analiza: 22 de citiri, R² 0,9993 față de lichidul OBD |
| UDS 2206 | Litrii din rezervor (la fel ca la bord) | 0x34 = 52 → 0x33 = 51 → 0x31 = 49 | 21–24.11, ca CAN c104 (scos din urmărire 23.11) |
| OBD 09 | VIN, CALID `03F906070GA 5413`, CVN C3664742, „ECM-EngineControl” | 20.11 |

Urmărirea (la pornire și la 2 min) nu mai citește ce e confirmat aici (rezervor la bord, bec, revizia, kilometrajele,
turația și temperaturile motorului, F40C / F40D; din 24.11 și afară 1014, frâna de mână 2205, uleiul 202F, lichidul F405): rămân doar candidații din secțiunea 5.

Motorul după 469C (scanat 22.11): doar identificarea și oglinzile OBD.
- **F1xx** identificarea UDS: F1AD „CBZB” (codul motorului), F17C „SJ8-FST07.06.1200130056”, F1AB „541354135413”,
  F1A2 „004005”, F17E „--H10---”, F18C seria (zerouri), plus cele știute (F187, F189, F190, F191, F197, F19E, F1AA).
- **F4xx = OBD 01** (F4 + PID-ul): F40C turația ×4, F405 lichidul − 40, F40F aerul admis − 40, F446 afară − 40, F442
  tensiunea ×0,001, F433 presiunea atmosferică (99 kPa), F423 rampa ×10 kPa (48 bar), F43C catalizatorul (413 °C), F41F
  secunde de la pornire, F431 km de la ștergerea erorilor (88), F430 încălziri (3). **F6xx = OBD 06** (testele), **F8xx =
  OBD 09** (VIN, CALID, CVN, numele). Nimic nou față de ce citim deja prin OBD.
- 58DE = 0x0115 (277), singurul cod necunoscut din zonă.

### Climatronic (746 / 7B0, 5K0907044GK)

Citire: UDS `22 <DID>`, câte un DID pe cerere (ATSH746 ATCRA7B0 ATFCSH746 ATFCSD300000 ATFCSM1). 91 de coduri au valori (0100–1000, 2600–27DA). Scanarea completă s-a terminat pe 24.11 (175 de coduri; peste 27DA doar identificarea și F405 / F40C / F40D). Confirmate pe 23.11, parcat, motorul pornit, câte o schimbare la butoane și o citire (`/root/clima-*.txt`).

**Confirmate** (valoare mare = al doilea octet; ambele octeți urmează aceeași mișcare):

| DID | Ce e | Valori citite |
|---|---|---|
| **262E / 262F** | **Clapeta de distribuție: 262E = poziția reală (al doilea octet), 262F = poziția cerută.** Când merge, 262E ≈ 262F + 0x1D (parbriz 19/00 … față DB/BE). 23.11 18:00, „față” + LO: **262E 89 / 262F BE** = clapeta rămasă pe drum, gurile din mijloc fără aer, B1091 71 din nou activă (stare 09). Motorul pe care îl are B1091 | parbriz (MAX) 19/00 · AUTO la încălzire 72–85 / 55–6B · doar picioare 96/78 · față + picioare C4/A5 · doar față, AUTO la 18 °C, LO DB/BE (gurile din mijloc deschise la ≥ ~C4) |
| **261E / 261F** | **Clapeta de recirculare** | aer proaspăt 0071 / 59 · recirculare (și LO) 0018 / 00; 263B = 00 → 03–04 odată cu ea |
| **2601 / 2602 / 2603 / 2604** | **Compresorul / cererea de A/C** | A/C pornit ~67 / 67 / 09 / 0B (cresc ușor la cerere mare); A/C oprit 00 / 00 / 00 / 00 (2600: 00 → 06) |
| **2617 / 2618** | **Comanda ventilatorului** | minim 22/0A · AUTO 2A/1F–31/32 · HI 45/66 · MAX parbriz 59/9B · ventilator maxim sau LO 7D/FA |

**Aproape sigure** (au mers la capete la LO / HI / A/C oprit, dar în AUTO se mișcă cu cererea calculatorului, deci nu sunt un punct fix):

| DID | Ce e | Valori |
|---|---|---|
| 2622 / 2623 (stânga), 2626 / 2627 (dreapta) | clapetele de temperatură | LO 17/00 și 1A/00 · A/C oprit BF/A3 și C9/AE · AUTO 23 °C E0–E4 / C6–C7 (coboară spre C1 pe măsură ce cabina se încălzește) · HI E6/C8 și E3/C8 |

**Neclare** (se schimbă, fără înțeles):

| DID | Valori |
|---|---|
| 2606 / 2607 | 19/00 în AUTO și în distribuțiile manuale (față, picioare); 3F/32 la MAX, recirculare, LO, HI, ventilator minim manual |
| 2632 / 2633 | 0055/3B AUTO 23 °C · 003C/22 AUTO 18 °C · 001B/00 manual și LO · 00D6/BA MAX · 007D/62 HI |

**Candidați (24.11, din istoric):** 0285 / 0286 stau la 0x89–0x92 (cu x / 2 − 50: 18,5–23 °C) indiferent de afară (1,5–12 °C) și de motor, chiar după o noapte la frig (21 °C la 06:14); o singură dată 0x80 (14 °C, 23.11 11:19, în testele cu butoanele). Nu e temperatura din habitaclu (ar fi fost rece dimineața); poate o temperatură cerută / calculată de climatronic.

**Erori:** B1091 71 / 72: motorașul clapetei de distribuție față. După calibrarea din butoane (23.11, ~16:50) `71` a trecut la memorată (stare 8) și clapeta a parcurs toată plaja ~1 h; la 18:00 s-a blocat iar (262E 89 față de 262F BE) și `71` a redevenit activă (09). **Defect intermitent al motorașului / angrenajului**, calibrarea l-a ascuns doar temporar. În AUTO la încălzire calculatorul cere oricum gurile din mijloc închise; se deschid sub ~18 °C sau cu butonul „față”.

**Diagnostic 23.11 seara:** motorașul se oprește în anumite poziții fără niciun zgomot și pornește din nou după o mișcare mică a rotiței cu mâna (când se aude, se și mișcă; angrenajul nu sare) = punct mort în motoraș (perii / colector). De schimbat: **V426**, motorul clapetei de distribuție față, cu potențiometrul G642 (manualul Golf 6 Climatronic: se scoate planșa de bord, apoi „basic setting” cu tester; calibrarea din butoane + 262E/262F pentru verificare).

Poza rotiței albe (cama clapetei, văzută prin locașul navigației, sub traversa metalică a bordului): `docs/foto/2026-11-23-climatronic-rotita-clapeta-distributie.jpg`. Motorașul nu se vede de acolo.

**Decizia utilizatorului (23.11):** motorașul nu se schimbă (montajul cere planșa de bord jos); se descurcă cu butonul „față” / rotița albă, iar alerta „clapeta” îl anunță când se blochează.

**24.11 seara:** la pornire (19:10–19:15, AUTO la încălzire) reala a rămas în urmă: 21 la cererea 06 și tot 21 la cererea 20, apoi 58 la 4B, ajunsă la ~19:15. Butonul „față” la ~19:49: cererea BE, reala tot 7B cel puțin până la 19:49:50 (fără aer în primele secunde, spune utilizatorul), la 19:50:44 DB, adică s-a deblocat singură în ≤ 1 min. Dimineața (07:10–08:19) reala a stat pe 82/83, cu cererea între 61 și 6D (în toleranță, deci neclar). Alerta: din 24.11 și când reala nu se mișcă deloc între două citiri, deși cererea s-a schimbat; urmărirea la 30 s.

**Cum verifici:** 262E − 262F (al doilea octet) trebuie să fie ~0x19–0x1F; altfel clapeta nu a ajuns unde i s-a cerut. **Alerta automată** (23.11): urmărirea „clapeta de distribuție” le citește la 1 minut; o nepotrivire cu aceeași cerere la ≥ 30 s apare pe site / Panel ca „Climatronic · clapeta” (server `flap.ts`).

## 4. Consumul: estimarea OBD față de bord

Estimarea din aerul aspirat (MAP × rpm × aerul admis, VE 0,85, λ cerut; aplicația, pe fiecare punct) comparată pe 24.11
cu calculatorul de bord. Bordul primește consumul de la motor, deci e referința cea mai bună pe care o avem.

| Situația | Bordul | OBD | |
|---|---|---|---|
| Relanti cald, 747 rpm, MAP 31 kPa | 0,7 L/h | 0,69–0,72 L/h | ✅ exact |
| ~2.090 rpm pe loc, fără sarcină, MAP 24 kPa | 1,3 L/h (1,6 cât urca turația) | ~1,5 L/h | ~15 % peste: VE fix 0,85 prea mare la MAP mic? |
| Memoria 1, 07:59–09:46 (pornire la rece, 3 km, mult relanti) | 49,3 L/100 × 3 km = 1,48 L (distanța rotunjită: 1,2–1,7 L) | 1,49 L | ✅ |
| Drum scurt prin oraș după resetarea memoriei 1 (< 1 km) | 12,3 L/100 | 12,4–13,3 L/100 | ✅ ±8 % |
| Termen lung | memoria 2: 10,7 L/100 (bord 2299) | – | alimentările dau 10,4–10,8 |

**Concluzie:** fără nicio calibrare, estimarea OBD urmează bordul. Căutarea cantității injectate în codurile motorului
nu mai e necesară pentru consum (seria deasă oprită). Rămâne: un drum mai lung cu memoria 1 resetată la plecare, ca să
vedem abaterea în mers, cu sarcină; după aceea, eventual VE în funcție de MAP. Bordul 2299 (media lungă) se poate urmări
în timp față de litrii OBD.

## 5. Candidați (bănuiți, neconfirmați)

### Bord (714)

| DID | Ce am văzut | Bănuială |
|---|---|---|
| **2296** | ≈ 57 × litrii CAN − 312 (r 0,97, 10 citiri); 2.647 la 52 L, 2.467 la 49 L; o dată 0 | **benzina din rezervor în 0,02 L** (2296 / 50 = 52,9 / 49,3 L), mai fină decât 2206, folosită la autonomie? |
| **229A** | ≈ 7,6 × litrii CAN − 8 (r 0,99, 12 citiri): 388 la 52 L, 363 la 49 L | tot nivelul rezervorului, altă scară (senzorul brut?) |
| **1006** | crește cu motorul pornit (~70–80 pe minut la relanti, ~230 pe minut în mers, 24.11), a stat pe 65.535 (FFFF, plafon) pe 22.11 și a pornit din nou de jos până pe 24.11 | un contor (combustibil / timp cu motorul pornit?) care se resetează; în urmărire din 24.11 |
| **1007** | crește doar în mers, mai mult la frânări și manevre (+219 pe 1,9 km, +363 pe 1,2 km cu opriri, +114 pe 120 m de parcare); 0 la relanti; nu se resetează după 12 h; pe loc nu se schimbă la frâna de picior, volan, marșarier (24.11) | nu e consumul (22.11); un contor legat de mers (impulsuri de roată? frâna în mers?) |
| 1009 | 629–646 de 3 zile, urcă și coboară | nu e timpul de mers |
| 100A | 7F rece → 99 cald, apoi iar 7F (24.11) | nu e uleiul; o temperatură? |
| 220C | 67 → 77 lent cât stă motorul pornit; r 0,67 cu temperatura de afară, slab cu restul | o temperatură din bord? |
| 2208 / 2273 | 2208 sare între 4A și 73; 2273 0x32–0x53 | senzori? |
| 220D, 2224 al doilea octet | 220D 5565 ↔ 5465 și 2224 `02` ↔ `06` (bitul 0x04) odată cu blitzul pe senzorul de lumină, și din când în când singure | legate de lumina de afară („zi”?), nu de becul schimbat |
| 2275 | `0B` → `0A` în timpul testelor cu luminile (24.11), apoi `0A` la orice poziție a comutatorului; `07` în mers | nu e comutatorul luminilor |
| 2203 | se schimbă rar | necunoscut |
| F442 | 0x87–0x8D cu tensiunea CAN 14,0–14,3 V; r doar 0,62 pe 81 de citiri (și −0,6 cu temperaturile motorului) | tensiunea bordului × 0,1, nu chiar cea de la baterie? |
| 1017 / 1018 / 1019 / 101A | kilometraje cu cuvintele de 16 biți inversate (`C0 B2 00 03` = 245.938); 1019 = ultima intrare, 101A = cea dinainte, un contor 00 / 01 / 02; intrări și în mijlocul drumurilor; după kilometraj mereu `10 91 11 4A/4D` | un jurnal de evenimente al bordului; `10 91` poate fi B1091 (clapeta, apare în mers), neverificat. Analiza 24.11, cele 6 intrări (`<2 octeți> 21 05 00 30 0E 00 HH 1F MM SS <km> 10 9x 11 4x`): dacă HH:MM:SS e ora locală, două se potrivesc exact cu kilometrajul CAN din clipa aceea (22.11 07:45:46 la 245.918, 24.11 08:03:12 la 245.938, ambele la câteva minute după pornire), două aproape (21.11 23:14 la 245.907 față de 245.905), una nu (23.11 08:04 la 245.925, când mașina stătea la 245.924). Afară era 1,5–11,5 °C, deci nu e avertizarea de polei. Coada trece din `10 91` (20–21.11) în `10 90` (din 22.11), iar `11 4D` → `11 4C` → `11 4A` scade încet |
| 1016 | `03 1C 2A 5E 00…`: 0x031C = 796 = distanța memoriei 2 pe 24.11, dar tot 0x031C și pe 21.11 (~50 km mai puțin); 2A58 → 2A5E, ~+1 pe zi | coincidență pentru 796; 2A5x o numărătoare de zile / o dată? |
| 224B | +1 deodată cu zilele reviziei | o numărătoare de zile |

**Nu sunt în codurile bordului** (sesiunea implicită, 24.11): kilometrajul (doar în motor, 121C), TRIP-ul, memoria 1 (timp, distanță,
consum mediu, viteză medie), distanța și timpul memoriei 2, avertizarea de polei, semnalizarea simplă, faza scurtă, frâna de picior,
marșarierul.

### Motor (7E0)

| Cod | Ce am văzut | Bănuială |
|---|---|---|
| 1154 | 750 la toate citirile pe loc, 800 în mers | turația de relanti cerută |
| 117D | 23 la relanti, 25–36 la mers încet, 43 la sarcină, **63 cu injecția tăiată** | avansul aprinderii / un unghi? |
| 1189–118F | ~519–540, scad cu ~1 la fiecare 5 °C de lichid (118D: R² 0,94), urcă puțin cu tensiunea | o mărime modelată după temperatura motorului |
| 1749 | la pornirea la rece = aerul admis, apoi crește mai repede decât el (cu formula VW, ~6–7 °C peste aerul admis la cald) | temperatura din galeria de admisie |
| 3D73 / 4183 | câte o citire, egale cu lichidul de răcire (formula VW / x − 40) | temperaturi ale motorului |
| 406A | cu semn, −2.500 … +7.300, sare între citiri, exact 0 doar cu injecția tăiată | corecția regulatorului de relanti; nu e cantitatea injectată |
| 2061 / 2062 | 2061 = 2 × 2062; relanti 155, accelerând 265–270, la 2.090 rpm pe loc 246; 742–801 la sarcină mare | urmărește pedala; nu e cantitatea pe cursă și nici L/h (24.11): timpul de injecție cu decalaj fix? |
| 20EB / 20EC | relanti 600–650, ~1.000 cu injecția tăiată, plafon 11.127 / 11.085 la sarcină plină | o presiune / o limită, nu injecția |
| 209A / 204C | 209A: relanti ~8.400–9.000, sare mult între citiri (15.000–17.000 la 2.090 rpm pe loc), 30.000–41.000 la sarcină | necunoscut |
| **16E2** | relanti ~8.200–8.600; MAP 74 kPa → 3.429, MAP 60 → 5.038, MAP 27 (frână de motor) → 9.209; ≈ 117 × (103 − MAP) | **depresiunea din galerie** (presiunea atmosferică − MAP; pentru servofrână?) |
| 39CB | ≈ 9 × MAP: 258–297 la relanti (MAP 30–33), 327 la MAP 35, 626 la MAP 72 (R² 0,99, 21 de citiri) | o presiune sau aerul pe cursă (mg?) |
| 1200, 29D1, 13B1, 39C3 | urmează MAP / sarcina (R² 0,98–0,99, dar aproape toate citirile la relanti) | mărimi de aer / sarcină; de văzut în sarcină |
| 4111 | relanti 605–837, **11.106 la MAP 133 kPa** (sarcină plină, turbo), ca plafonul lui 20EB / 20EC | tot o mărime de cuplu / sarcină cu plafon |
| 1036 | 0x000377F7 = 227.319 | kilometrajul unui eveniment vechi? |
| 11F4–11FD, 16DF | text: „mec_open_cps”, „no_error” (×9), „SA3O6010 CBK0191” | stări ale unor teste interne |

Analiza (23.11, `web/scripts/obd-analyze/`): codurile se salvează doar la schimbare, la 2 min, deci cele mai multe au
puține citiri; pentru o mărime rapidă trebuie o serie deasă, ca cea din 24.11 (`@obd` cu 45 de citiri la 0,5 s).

## 6. Erorile citite

| Modul | Cod | Ce e | Stare |
|---|---|---|---|
| Climatronic | **B1091 71** | motorașul clapetei de distribuție a aerului față (V426): se blochează | intermitent: după calibrarea din 23.11 a trecut la memorată, la 18:00 iar activă (09). Motorașul nu se schimbă (decizia utilizatorului); alerta „Climatronic · clapeta” anunță blocarea |
| Climatronic | B1091 72 | același motoraș, blocat deschis | memorată |
| Motor | – | 63 de intrări, toate „test neterminat” (0x40/0x50): calculatorul resetat de curând | fără erori |
| Airbag | – | o intrare, status 0x10 | fără erori |
| Bord | bec ars (2224) | proiectorul dreapta față | ✅ schimbat 21.11 |

## 7. Cum se citesc (detalii tehnice)

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

## 8. Istoric

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
| 24.11 11:40 | Urmăririle la 30 s, cu valorile OBD din clipa fiecărei citiri (`obd_read`, aplicația 1.1.121): ca să confirmăm din câteva drumuri candidații rapizi ai motorului (aer, sarcină, avans) |
| 24.11 11:15 | Jurnalul de evenimente al bordului (1018–101A) descifrat parțial: ora (HH 1F MM SS) și kilometrajul fiecărei intrări; ce eveniment e, încă necunoscut |
| 24.11 11:05 | Climatronic: 0285 / 0286 ≈ 21 °C constant (x / 2 − 50), nu habitaclul; restul codurilor lui au prea puține citiri pentru analiză |
| 24.11 11:00 | Analiza motorului: **392A = lichidul de răcire** (a treia copie); 16E2 ≈ depresiunea din galerie; 39CB, 1200, 29D1, 13B1, 39C3, 4111 urmează MAP / sarcina: puși în urmărirea motorului (2 min) ca să aibă citiri în sarcină |
| 24.11 10:45 | Analiza istoricului: **2294 = autonomia nerotunjită** (12 perechi cu 2297); 2296 și 229A urmează benzina din rezervor (r 0,97 / 0,99); 1006 = contor cu motorul pornit, pus în urmărire |
| 24.11 22:38 | Memoria 1 resetată la ~22:17, drum prin oraș de 21 min (frâne de motor, semafoare): bordul 11 km, 6,4 L/100 ≈ 0,70 L (0,67–0,74 cu rotunjirea); OBD pe aceleași 21 min 0,69–0,73 L, acoperire completă. **Consumul OBD din aerul aspirat se potrivește cu bordul și pe un drum întreg (±3 %)** |
| 24.11 10:30 | Revizia după testele de azi: urmărirea bordului fără codurile confirmate (1014, 2205, 202F, F405), cu 2294 / 2296 / 229A; serverul schimbă singur lista lucrărilor de urmărire existente când se schimbă `WATCH`; secțiunea nouă „Consumul”; candidații refăcuți pe module |
| 24.11 10:16 | Memoria 2: **2299 = consumul mediu pe termen lung** (10,7); distanța (796 km) și timpul (62 h 53 min) negăsite sigur |
| 24.11 10:20 | Teste pe loc (citirea întregului bord înainte / după): **2224 0x04 = avariile, 0x40 = faza lungă**; grupa 224D…2291 = **lumina de afară** (blitz pe senzor); semnalizarea, faza scurtă, frâna de picior, marșarierul, volanul nu apar în bord |
| 24.11 09:57 | Memoria 1 resetată, drum scurt: consumul mediu pe ecran 12,3 L/100, estimarea OBD 12,4–13,3 L/100 (în mers, prima comparație) |
| 24.11 09:47 | **2297 = autonomia** (490 km, ca pe ecran); memoria 1 (3 km, 49,3 L/100 = 1,48 L) față de OBD 1,49 L de la 07:59 |
| 24.11 09:45 | Consumul de pe ecran („Consumption” L/h) față de OBD: relanti 0,7 / 0,7, ~2.090 rpm fără sarcină 1,3 / ~1,5; 2061 / 2062 nu sunt cantitatea injectată; timpul de mers al memoriei 1 nu e în bord |
| 24.11 09:40 | Bordul față de ecran (pe loc, motorul pornit): **1014 = temperatura de afară** (x / 2 − 50), **2205 bitul 0x01 = frâna de mână** (eliberată pe loc → 0), **202F = uleiul** (x − 58, nu x − 50: ecranul 86 °C la 0x90, 87 °C la 0x91); kilometrajul (245.942) și TRIP-ul (97,9) de pe ecran nu sunt în codurile bordului |
| 24.11 | Climatronicul complet (08:13): nimic nou între 27DB și F15A; identificarea: F187 / F191 = 5K0907044GK, F189 = software 1212, F1A3 = hardware H19, F197 = „Climatronic”, F19E = `EV_Climatronic`, F1AA = J255, F18B = 12 06 16 (data fabricației?), F17C = seria `BHB-00116.06.1200980656` |
| 23.11 | Pornire la rece: bord F405 = lichidul de răcire, 202F probabil uleiul (x − 50); motorul 1189–118F scad cu temperatura; TRIP-ul (80,4 / 80,6) și consumul mediu (49,9) NU sunt în codurile bordului citite în sesiunea implicită; climatronicul la 46 % |
| 23.11 18:00 | Climatronic: 262E = poziția reală, 262F = cea cerută; clapeta blocată iar (89 / BE), B1091 71 activă din nou: motorașul e defect intermitent |
| 23.11 17:50 | Drumul de test pentru injecție: 20EB/20EC nu sunt cantitatea injectată (nu scad la 0 la injecția tăiată, plafon 11127); 2061/2062 candidatul principal; seria deasă e citită doar o dată pe minut |
| 23.11 17:00 | Climatronic: clapeta spre față = 262E/262F; B1091 71 nu mai e activă după calibrarea din butoane; centrul închis în AUTO la 23 °C e normal, se deschide la 18 °C |
| 22.11 seara | Bord 1007 NU e contorul de consum (nu crește la relanti, nu se resetează după 12 h) |
| 22.11 seara | Motorul complet (763 de coduri). Analiza cu OBD / CAN din aceleași clipe: 11CF / 11D1 = lichidul de răcire și 1001 / 1002 = aerul admis (confirmate), 1000 / 1748 = afară; 406A NU e relantiul cerut (corecție cu semn, 0 la injecția tăiată); după 469C doar identificare și oglinzi OBD (F4xx, F6xx, F8xx) |
| 22.11 seara | Urmărirea redusă la ce nu e sigur (bord 21 → 15 coduri, motor 19 → 13); confirmate și mutate în secțiunea 3: motor 1000, 1001 / 1002, 117C |
| 22.11 | Analiza codurilor: intervalul reviziei în bord (2260 / 2261 / 2243 / 2244, potrivite cu revizia din 19.10), kilometrajul în motor (121C, 15D4), temperaturile și turația în motor (1000–1002, 117C); 1007 ≈ contorul de consum, 2205 ≈ frâna de mână |
