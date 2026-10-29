# FaikkitCar – istoric

Tot ce ține de trecut: constatările datate, migrarea din FaikkitBox și istoricul versiunilor.
Starea de acum e în [`README.md`](README.md).

---

## Jurnalul constatărilor

| Data | Ce s-a aflat / ce s-a întâmplat |
|---|---|
| 29.09 | Prima versiune „VW Welcome”: sunet la trezirea navigației |
| 30.09 | Navigația nu are selector de fișiere (căderea la adăugarea sunetelor). La opriri scurte navigația nu doarme deloc, deci autostart-ul redă direct. Găsite listele FYT de aplicații protejate; cu pachetul `com.mapgoo.diruite` aplicația supraviețuiește somnului |
| 30.09–01.10 | Codurile CAN găsite cu sonda și calibrările ghidate; viteza CAN vs. GPS: +0,8 km/h în medie; distanța: 16,2 km calculați față de 16 km pe kilometraj |
| 01.10 | Litrii din rezervor confirmați (c104: 21 → 38 L la alimentarea de 16,0 L). Începutul sunetului se tăia după somn lung: de la 1.1.28, 1,2 s de liniște înainte. Vocile Google sună robotic: salutul trece pe Piper „Mihai”. Contactul dedus din datele de bord se potrivește cu realitatea. Actualizarea din aplicație merge (1.1.24 → 1.1.27 → 1.1.30 → 1.1.31). Proiectul separat de FaikkitBox (vezi mai jos) |
| 02–03.10 | Frâna de mână: c103 confirmat; polaritatea bitului din 0x24 corectată. Marșarierul: m0 c68 = [1, 1] (verificat de 4 ori) |
| 07.10 | 08:57–18:29: navigația a închis aplicația și n-a mai pornit-o (17 km neînregistrați) |
| 12.10 | Consumul real: 17 L pe 180 km = 9,4 L/100; drumurile dau 9,6. Temperaturile climei (și cu SYNC) și centura confirmate în mers. c1033 nu e consumul |
| 13.10 | Ieșit cu spatele din parcare și oprit la semafor după 150 m apărea „Parcat, motor pornit” (corectat în 1.1.50) |
| 17.10 | Cheia mașinii ajunsese în APK-urile publice: repo-ul a devenit privat, cheile separate (`CAR_TOKEN`, `CI_TOKEN`) |
| 19.10 | Prima revizie în jurnalul de service (2.979 lei, cu devizul și factura) |
| 20.10 | Procesul a murit de două ori exact la intrarea în somn (09:26, 11:16) și o dată la service; autostart-ul a ratat la plecare: 3 km neînregistrați (Service → Spălătorie). Cauza probabilă: memoria (swap plin, ~400–550 MB disponibili). Au urmat alarma de rezervă (1.1.52), linia „Teyes” (1.1.53) și „Acces la notificări” (1.1.55). Plinul de 46,18 L a urcat indicatorul doar cu 40,5 L (11,5 → 52): indicatorul nu e liniar la capete, deci consumul real se ia din plinuri |
| 22.10 | Performanța navigației (1.1.62) merge prin somn și repornire. Radio-ul Teyes (`sqlfmserver`) rămăsese pornit după închidere și ținea ocupat un nucleu întreg (12,5% din procesor); a dispărut abia la repornire. După repornire: ~1,0–1,1 GB liberi cu Waze și YouTube Music, swap ~27%, ~48 °C, procesor ~25% |
| 29.10 | Primul drum cu semnalul înregistrat (Acasă → Serviciu, 19 min): internet tot timpul, dar Android-ul Teyes a dat tot drumul aceeași celulă cu −117 dBm (o copie veche). Din 1.1.65 aplicația cere modemului o citire nouă la fiecare punct; punctele înghețate au fost golite |
| 29.10 | Sunetul de bun venit pornit de două ori la plecare (și pe 24.10): după ACC OFF navigația s-a trezit singură o clipă, cu ecranul stins (28.10 14:09), aplicația a programat sunetul la 8 s, dar navigația a adormit înainte; sunetul rămas a pornit dimineața, peste cel nou. Corectat în 1.1.66 |
| 28.10 | Internetul din navigație (cartela Digi) cade pe unele drumuri. Nu sunt benzile: modemul Teyes are B1, B3, B7, B8, B20, B28A, B38, deci toate benzile 4G din România (fără B32, suplimentară). Digi are însă doar 5 MHz pe 800 MHz și 3 MHz pe 900 MHz (Orange și Vodafone câte 10 pe 800, Orange și 700 MHz) și a închis 3G-ul. De la 1.1.64 se înregistrează semnalul pe fiecare punct (harta pe fila Navigație) |
| 21.10 | Aplicația a trecut prin somnul de 18 h; omorâtă la 2,5 min după plecare, „Acces la notificări” a repornit-o în ~7 s. Tapet static, dezactivate Google Maps, YouTube, Bunăstare, Chrome, Android Auto → ~1,1–1,7 GB disponibili. Frâna de mână raportată eliberată după repornirea navigației, până la prima mișcare (1.1.58); bitul 0x20 din 0x41/1 a stat 0 cu frâna trasă (nu se mai folosește). Actualizarea instalată de două ori la o a doua apăsare (1.1.57) |

---

## Migrarea din FaikkitBox (01.10.2026)

Până pe 01.10, serverul aplicației era o parte din FaikkitBox (`status.faicu.ro`, paginile
`/vw` și `/calatorii`). Acum proiectele sunt complet separate:

1. **Copie de siguranță completă**, înainte de orice schimbare:
   `/root/backups/faikkitcar-20261001-215730` (baza FaikkitBox întreagă, APK, voce,
   Piper, `.env`).
2. **Copiere** cu `web/scripts/import-faikkitbox.ts`. Se poate rula de oricâte ori, fără
   să dubleze sau să suprascrie, și verifică la final rând cu rând că nu lipsește nimic:
   10.171 de linii de jurnal, 321 de puncte de drum, 1 alimentare.
3. **Rutele mașinii scoase din FaikkitBox** (acum 404). Aplicația din mașină păstrează
   datele în coadă la orice răspuns ≠ 200, deci nu pierde nimic până la actualizare.
4. **Copiere finală și verificare** (0 rânduri noi, 0 lipsă).
5. **Ștergere în FaikkitBox:** codul, fila „VW”, tabelele `vw_*`, fișierele din `data/`,
   `VW_LOG_TOKEN`, `leaflet` (commit local `2c986a0`).

---

## Coduri CAN renunțate

| Cod | Ce părea | De ce nu se mai folosește |
|---|---|---|
| m7 raw 0x14 | Luminile aprinse (iluminarea bordului: 0 ziua / 76–84 cu farurile) | Folosit în 1.1.42–1.1.58; vine doar la schimbare, deci după o repornire a aplicației starea lipsea |
| m7 raw 0x41/1, bitul 0x20 | Frâna de mână trasă | Pe 21.10 a stat 0 cu frâna trasă |
| m7 raw 0x41/1, bitul 0x80 | Faza scurtă | Stă pus și ziua, cu luminile pe AUTO |
| m7 c1033 | Consumul instantaneu al bordului | Integrat dă 17,5 L/100 față de 9,4 real și abia variază cu viteza; se păstrează doar brut în `trip_point.cons` |

---

## Istoricul versiunilor

### FaikkitCar (aplicația din mașină)

| Build | Data | Ce a adus |
|---|---|---|
| 62 | 22.10 | Performanța navigației trimisă cu starea live (`UnitStats`): procesorul, memoria, swap-ul, temperatura, memoria și adj-ul aplicației, spațiul liber, procesele care consumă |
| 61 | 21.10 | Jurnal mai curat: fără temperaturile unității, tensiunea la demaror, litrii din rezervor și pornirea / oprirea GPS-ului (toate sunt în punctele de traseu); volumul apare în linia „Redare pornita”, fără „Redare terminata” |
| 60 | 21.10 | Comenzile la distanță nu mai intră în jurnal: sunt în jurnalul separat „Comenzi la distanță” (site și Panel), cu rezultatul |
| 59 | 21.10 | Luminile nu se mai citesc (nici în jurnal, nici în starea live): CAN-ul le trimite doar la schimbare, deci după o repornire a aplicației lipseau ore întregi. Intrările vechi din jurnal rămân |
| 58 | 21.10 | Frâna de mână: după repornirea navigației, CAN-ul o raporta eliberată chiar trasă, până la prima mișcare a manetei. Acum valoarea „eliberată” se folosește doar după ce frâna s-a mișcat măcar o dată de la pornirea navigației; până atunci Panel-ul și site-ul n-o mai afișează |
| 57 | 21.10 | Actualizarea: după confirmare, Android mai verifică aplicația ~25 s fără nimic pe ecran, iar o nouă apăsare pornea a doua instalare. Acum butonul arată „Se instalează...” și o nouă apăsare nu mai pornește alta |
| 56 | 20.10 | Vocea locală se eliberează la 30 s după ce termină; punctele de traseu se trimit pe aceeași conexiune păstrată deschisă, ca starea live. Verificat în emulator: după kill -9 serviciul revine în ~5 s, iar după force-stop „Acces la notificări” îl repornește imediat |
| 55 | 20.10 | „Acces la notificări” (Setări → Sistem): prioritatea urcă de la 200 la 100 și, dacă e omorâtă, sistemul o repornește în câteva secunde (testat în emulator); harta golește depozitul de dale la ieșire; linia „Teyes” arată și accesul |
| 54 | 20.10 | Comenzi de la distanță pentru teste (Setări → Sistem, oprit implicit); jurnalul notează fiecare pas al actualizării din aplicație |
| 53 | 20.10 | La pornirea serviciului, jurnalul arată dacă suntem încă pe listele Teyes (`unkill_app.txt`, `pwctl_config.xml`, `skipkillapp.prop`, `protected_app.txt`), versiunea MainServer-ului și memoria liberă |
| 52 | 20.10 | Alarmă de rezervă: dacă procesul e omorât, serviciul repornește la prima trezire a navigației, în cel mult ~1 min. O oprire forțată anulează și alarma |
| 51 | 17.10 | Revizuire: cheia nouă a mașinii; harta Acasă nu mai rămâne cu traseul pe jumătate; pornirea fără căderea de la demaror nu mai dă o tensiune „bună” falsă; coada de puncte rescrisă de 10× mai rar după zile fără internet; actualizarea eșuată nu mai lasă sesiunea de instalare deschisă |
| 50 | 13.10 | Semaforul imediat după ieșirea cu spatele din parcare nu mai apare „Parcat, motor pornit” |
| 49 | 12.10 | Codul mutat în pachetul `ro.faicu.faikkitcar`; iconițele și autostart-ul Teyes rămân aceleași |
| 48 | 12.10 | Harta nu mai ține memorie cât aplicația e în fundal; verificarea senzorilor de temperatură ai navigației (în jurnal) |
| 47 | 12.10 | Ecran nou: harta mare, doar ce nu arată bordul, meniul compact jos |
| 46 | 12.10 | Harta călătoriei în curs pe Acasă |
| 45 | 12.10 | Sonda CAN arată și codurile climei (căutarea SYNC-ului) |
| 44 | 12.10 | Temperatura setată a climei corectată (+2 °C) |
| 34–43 | 01.10–03.10 | Starea live, clima și detaliile, frâna de mână corectată, bateria la pornire, opririle în trafic, locurile, Acasă ca tablou de bord, iconița nouă (34) |
| 33 | 01.10 | Fără schimbări în aplicație (build declanșat de modificările CI-ului) |
| 32 | 01.10 | **FaikkitCar:** nume nou, totul pe car.faicu.ro, calibrarea redusă la c107 și marșarier (în Setări) |
| 31 | 01.10 | Calibrare doar pentru ce e nesigur, cu motorul pornit |
| 30 | 01.10 | Salutul cu vocea Piper de pe server (mult mai naturală) |
| 29 | 01.10 | Avertizările vorbesc cu vocea locală (fără întârzierea vocii online) |
| 28 | 01.10 | 1,2 s de liniște înaintea sunetului: începutul nu se mai taie după somn lung |
| 27 | 01.10 | Litrii din rezervor (CAN c104) la fiecare punct de drum; alimentările se văd în jurnal |
| 26 | 01.10 | Vocea: se alege cea mai bună voce română, lista vocilor în jurnal |
| 25 | 01.10 | Curățenie: scos diagnosticul Teyes și sonda separată; rezervorul prin capturi înainte/după alimentare |
| 24 | 30.09 | Calibrare doar pentru necunoscute, cu schimbările afișate live; derularea nu mai sare sus |
| 23 | 30.09 | Curățenie: motorul oprit detectat corect (GPS econom, resetarea salutului), cod mort scos |
| 22 | 30.09 | Sonda CAN extinsă (căutarea nivelului de combustibil) |
| 21 | 30.09 | Salut vorbit, avertizare ușă, mentenanță, „unde e mașina”, actualizare din aplicație |
| 19–20 | 30.09 | Călătorii (GPS + CAN), calibrare CAN, iconița proprie |
| 17–18 | 30.09 | Interfață nouă, sonda CAN |
| 16 | 30.09 | +2 s pauză după hibernare |
| 15 | 30.09 | **Pachetul `com.mapgoo.diruite`:** aplicația supraviețuiește somnului |
| 13–14 | 30.09 | Diagnostic Teyes: găsite listele FYT de aplicații protejate |
| 12 | 30.09 | Autostart-ul redă direct: la opriri scurte navigația nu doarme deloc |
| 11 | 30.09 | Reparat: căderea la adăugarea sunetelor (navigația nu are selector de fișiere) |
| 8–10 | 29.09 | Diagnostic extins, „VW Welcome Start” pentru autostart, jurnal trimis la server |
| 1–7 | 29.09 | Prima versiune: sunet la trezire, mai multe sunete, istoric, jurnal, semnare fixă |

### FaikkitCar Panel (telefonul)

| Build | Data | Ce a adus |
|---|---|---|
| 25 | 27.10 | „+ Poziție” la locurile salvate: încă o poziție cu același nume (a doua parcare „Acasă”) |
| 24 | 22.10 | Fila Navigație (performanța pe larg); pe Acum doar un rând cu starea pe scurt, care duce acolo |
| 23 | 22.10 | Cardul „Navigația” pe fila Acum (grafice pe ultima oră, procesele); reparată căderea pe Android sub 13 (`URLEncoder.encode` cu `Charset`) |
| 21 | 21.10 | Jurnalul pe bucăți: Evenimente, Pornire și somn, Mașina, GPS, Sunet și voce, Actualizări, CAN și diagnostic, Comenzi la distanță (cu rezultatul fiecăreia), Tot |
| 20 | 21.10 | Luminile nu mai apar pe ecranul Acum |
| — | 21.10 | „La serviciu”: următoarea tură și ora de plecare; „Drumurile tale” doar de la 10 deplasări, fără „cea mai bună oră” |
| 19 | 20.10 | Drumurile neînregistrate apar în lista de călătorii |
| — | 19.10 | Jurnalul de service arată articolele lucrării pe grupe (cod, preț, subtotal) și locul |
| — | 17.10 | Nu mai întreabă serverul în fundal după ieșirea din aplicație; harta live fără puncte dublate; actualizarea eșuată nu mai lasă sesiunea de instalare deschisă |
| — | 16.10 | Starea „Fără semnal” pe fila Acum |
| 14 | 12.10 | Scos „Consum acum” / „Bordul” (valoarea nu era consumul) |
| 13 | 12.10 | Harta live a călătoriei în curs |
| 2–12 | 01.10–03.10 | Patru file ca site-ul, starea live, combinarea călătoriilor (3), locuri, propuneri, raportul lunii, jurnal de service |
| 1 | 01.10 | Prima versiune: login, poziția, totaluri, călătorii cu hartă și grafic, alimentări, mentenanță, jurnal, actualizare din aplicație |

### Serverul

| Data | Ce s-a schimbat |
|---|---|
| 27.10 | Locurile cu mai multe poziții (rânduri `place` cu același nume; „acasa” se potrivește cu „Acasă”); pe site butonul „Încă o poziție” |
| 22.10 | Starea pe scurt a navigației (`unitHealth`, `live.unitHealth`, testată în `unit.test.ts`), numele proceselor pe server (`label`); fila Navigație pe site |
| 22.10 | Performanța navigației (`unit.ts`, tabela `unit_stat`, 90 de zile; `live.unit`, `/api/unit/history`); cardul „Navigația” pe Acum |
| 21.10 | Drumul la serviciu pe ture (`commute.ts`, `stats.commute`): ora de plecare pentru 15 min rezervă și următoarea tură; călătoriile au `departAt`/`arriveAt`; „Drumurile tale” de la 10 deplasări, fără ora cea mai bună, durata fără staționări |
| 21.10 | Frâna de mână doar din c103 (fără bitul din 0x41); luminile scoase; jurnalul pe categorii (`log.cat`, `log-category.ts`, `/api/log?cat=`), comenzile la distanță cu jurnal separat |
| 20.10 | Comenzile pentru navigație (`car_cmd`, `/api/cmd`, `/api/car/cmd`; `scripts/car-cmd.ts`) |
| 20.10 | Rezervorul de acum ia doar citirile de după ultima alimentare (după plin arăta 12 L în loc de 52); plinurile au prioritate la consumul real; drumurile neînregistrate (`/api/trips/gaps`) |
| 19.10 | Jurnalul de service pe articole (`service.items`) și locul (`service.shop`); pe site carduri cu data, totalul, grupele cu subtotal și fișierele |
| 17.10 | Revizuire: `CI_TOKEN` separat și tranziția cheii mașinii (`CAR_TOKEN_OLD`, scoasă pe 18.10); erorile de validare dau 400, nu 500; un lot cu o oră imposibilă nu mai e respins la nesfârșit; tensiunea la pornire ≥ 12 V ignorată; „Făcut” la o dată fixă fără interval nu mai șterge data; Piper fără conflict la cereri simultane; numele locurilor escapate pe hartă; jurnalul nu se mai taie la 200.000 de linii |
| 16.10 | Starea live „Fără semnal” (tăcere < 1 h după mers / motor pornit), cu călătoria în curs păstrată |
| 12.10 | Consumul nu mai e umflat de drumurile neînregistrate; scos consumul „de pe bord”; traseul live (`/api/live/route`, `/api/car/route`) |
| 01.10 | **car.faicu.ro** separat (`/opt/faikkitcar`), date migrate, FaikkitBox curățat |
| 29.09–01.10 | În FaikkitBox (`status.faicu.ro/vw`, `/calatorii`): jurnal, călătorii, mentenanță, APK, alimentări, consum, vocea Piper, rezervorul |
