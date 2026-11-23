# FaikkitCar – context pentru Claude

Utilizatorul scrie în română; răspunde în română. **Codurile OBD** (ce înseamnă, candidați, erori, progresul
scanării) sunt în `OBD-CODURI.md`: actualizează-l la fiecare descoperire sau schimbare și urcă-l pe GitHub (commit +
push), la cererea utilizatorului (21.11). Rezumatul pentru utilizator (doar starea de acum: funcții,
✅/🧪, ce urmează) e în `README.md`; istoricul (versiuni, constatări datate, migrarea, coduri CAN
renunțate) în `ISTORIC.md`. Ține-le la zi; nu pune istorie în README. Mașina: Golf 6 (1K), 1.2 TSI
77 kW (CBZB), benzină, 2012. Navigația: Teyes CC3 2K (FYT/SYU, Android 10, `sprd
ums512_1h10_Natv`, ecran lat, fără selector de fișiere).

## Proiectul

Repo `Faicu/FaikkitCar` (fost `welcometovw`/„VW Welcome”), pe server în `/opt/faikkitcar`.
Separat complet de FaikkitBox (`/opt/faikkitbox`): nimic despre mașină acolo. Migrarea din
Importul datelor din FaikkitBox în FaikkitCar s-a încheiat pe 01.10.2026 (`web/scripts/import-faikkitbox.ts`;
copie de siguranță în `/root/backups/faikkitcar-20261001-215730`); FaikkitBox rămâne o aplicație
activă, separată. Remote Control: `claude.service` (sursa în
`/opt/faikkit/deploy`) pornește câte o sesiune tmux pentru fiecare proiect.

- `app/`: **FaikkitCar**, aplicația din mașină (+ „FaikkitCar Start” pentru autostart).
  `applicationId` = `com.mapgoo.diruite` (lista albă Teyes, vezi mai jos; nu-l schimba). Pachetul
  Java e `ro.faicu.faikkitcar` (din 12.10, 1.1.49; înainte `ro.faicu.vwwelcome`). Launcher-ul și
  lista de autostart Teyes rețin componentele vechi, deci iconițele sunt `activity-alias` cu numele
  vechi (`ro.faicu.vwwelcome.MainActivity`, `ro.faicu.vwwelcome.StartActivity`) către activitățile
  noi; nu redenumi alias-urile (testat în emulator: actualizare peste versiunea veche, apoi
  `am start -n com.mapgoo.diruite/ro.faicu.vwwelcome.StartActivity` → „Pornit din autostart”).
- `panel/`: **FaikkitCar Panel**, aplicația de pe telefon (`ro.faicu.faikkitcar.panel`).
- `web/`: serverul **car.faicu.ro** + site-ul.

## Serverul și site-ul (`web/`)

- Cloudflare Tunnel → `http://192.168.1.192:3001`; systemd `faikkitcar` (`deploy/faikkitcar.service`,
  utilizator `faikkitcar`, `ProtectSystem=strict`, scrie doar în `/opt/faikkitcar/data`).
- Node rulează direct `server/index.ts` (Hono, `node:sqlite`; tipurile TS se șterg, deci doar
  sintaxă ștergibilă și importuri cu `.ts`). Site: React + Vite + Tailwind în `web/src`, construit
  în `web/dist`: `npm run build`, apoi `systemctl restart faikkitcar`. Teste: `npx vitest run`
  (`trip-math.test.ts`, `fuel-model.test.ts`, `commute.test.ts`, `unit.test.ts`, `signal.test.ts`, `music.test.ts`, `flap.test.ts`), `npx tsc --noEmit`.
- `/opt/faikkitcar/.env` (nu în git): `PORT=3001`, `ADMIN_USER`/`ADMIN_PASS` (login site + Panel,
  utilizatorul `Faicu`), `SESSION_SECRET`, `CAR_TOKEN` (= secretul GitHub `VW_LOG_TOKEN`, intră în
  APK), `CAR_TOKEN_OLD` (opțional, doar la o rotire a cheii, cât mașina are aplicația cu cheia veche; scoasă pe 18.10), `CI_TOKEN`
  (= secretul GitHub `CI_TOKEN`, doar `POST /api/*/apk`). Repo-ul e privat din 17.10 (cheia mașinii
  ajunsese în APK-urile publice).
- `/opt/faikkitcar/data`: `faikkitcar.db`, `apk/`, `tts/` (cache voce), `piper/` (binar + model
  `ro_RO-mihai-medium`, nu în git), `service/<id>/<fileId>` (bonuri).
  Tabele: `log`, `trip_point`, `car_state` (un rând), `reminder`, `refuel`, `place`, `trip_join`,
  `service`, `service_file`, `dismissed`, `car_cmd`, `unit_stat` (performanța navigației, 90 de zile), `obd_*` (erorile, scanarea). Coloane noi se adaugă prin migrare în `db.ts`.
  Jurnalul nu se șterge niciodată.
- API mașină (Bearer `CAR_TOKEN`; APK-urile se încarcă cu `CI_TOKEN`): `POST /api/car/log`, `/api/car/trip`, `/api/car/state`,
  `/api/car/tts`, `/api/car/music`; `GET /api/car/music/wait`; `GET /api/car/status` (kilometraj, mentenanță, ultimul APK, locuri),
  `/api/car/summary` (ecranul Acasă), `/api/car/route?last=1&after=` (harta), `GET|POST
  /api/car/apk`, `/api/car/apk/download`, `GET|POST /api/car/obd/scan` (scanarea OBD).
- API utilizator (cookie `fc_session` sau Bearer din `POST /api/login`): `/api/live` (long
  polling cu `?after=`), `/api/live/route`, `/api/trips`, `/api/trips/points`, `/api/trips/join|split`,
  `/api/trips/shapes`, `/api/stats`, `/api/car`, `/api/fuel`, `/api/refuels…`, `/api/reminders…`, `/api/places…`,
  `/api/geocode`, `/api/suggestions…`, `/api/service…`, `/api/log?cat=` (categoriile din
  `log-category.ts`, coloana `log.cat`; fără `cat` = evenimente, fără CAN și comenzi), `/api/cmd`
  (comenzile la distanță, jurnalul lor separat), `/api/unit/history?min=` (graficele navigației), `/api/unit/signal?days=` (semnalul mobil), `/api/music…` (muzica de pe navigație), `/api/panel/apk…`.
- Service: `service.items` (JSON: `group`, `name`, `code`, `qty`, `price` pe tot rândul, cu TVA;
  reducerile cu minus) și `service.shop`; lipsă din cerere = rămân cele salvate (Panel-ul le
  afișează, dar nu le editează). Prima lucrare: 19.10.2026, id 2.
- Pagini: Acum (`Now.tsx`: starea, călătoria în curs cu harta live `LiveTripMap.tsx`,
  rezervorul, „Unde e mașina” când nu merge, rândul „Navigația” din `live.unitHealth`), Călătorii
  (`Trips.tsx` + `TripView.tsx`), Costuri (`Costs.tsx`), Navigație (`Unit.tsx` + `UnitCard.tsx`,
  aceeași stare live), Mai mult (`More.tsx`: mentenanță, locuri, service, Panel, jurnal).
- Testarea (03.11, cererea utilizatorului): fără emulator și fără servere/simulatoare de test. Utilizatorul
  testează efectiv aplicațiile, iar pe navigație se verifică prin comenzile la distanță (`car-cmd.ts`).
  Emulatorul și imaginile lui au fost șterse; rămân doar uneltele de build. Verificări locale: `npx vitest run`,
  `npx tsc --noEmit`, build-ul Gradle. În aplicația din mașină adresa serverului e doar în `VwStatus.BASE`.
  Nu folosi `pkill -f` cu un tipar din propria comandă (omoară shell-ul).

## Aplicația din mașină (`app/`)

Java fără AndroidX, minSdk 26, targetSdk 33, compileSdk 34, AGP 8.5.2, osmdroid 6.1.20. UI din cod.

- `WelcomeService`: foreground (`START_STICKY`, `foregroundServiceType="location"`), tick la 5 s
  cu `elapsedRealtime()`; o pauză peste prag (implicit 60 s) = trezire; contor de boot schimbat
  sau ultima activitate veche = trezire. `SCREEN_ON`/`USER_PRESENT` verifică imediat.
- `Watchdog` (20.10): alarmă `ELAPSED_REALTIME` (nu trezește unitatea) la 60 s, pusă în
  `WelcomeService.onCreate`; dacă serviciul nu rulează, îl pornește („Alarma de rezerva” în jurnal).
  Testat în emulatorul Android 10 (AVD `car29`). Force-stop-ul anulează alarma, dar cu „Acces la
  notificări” sistemul reface legătura cu `KeepAliveListener` și ne repornește imediat (emulator
  20.10: kill -9 → ~5 s, force-stop → ~20 ms; pe Teyes netestat).
- `StartActivity` („FaikkitCar Start”, translucidă): lansată de lista de autostart Teyes la ACC
  ON → `WelcomeService.startAccOn` redă direct. `BootReceiver`: `BOOT_COMPLETED`,
  `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED` (fără `LOCKED_BOOT_COMPLETED`).
- `Player`: MP3-ul ales, focus audio tranzitoriu, 1,2 s de liniște înainte (ieșirea audio Teyes
  pornește abia cu primul sunet), pauză 5 s + 2 s după hibernare (utilizatorul: +3 s), volum în %,
  anti-dublare 20 s. Redarea programată se anulează dacă a venit alta sau dacă navigația a dormit
  între timp (1.1.66: trezirile scurte cu ecranul stins după ACC OFF lăsau sunetul în `postDelayed` până
  dimineața → redat de două ori, 24.10 și 29.10). Trezirea din tick cu ecranul stins așteaptă `SCREEN_ON` ≤ 60 s. De atunci (1.1.66) autostart-ul pornea sunetul după ce tick-ul refăcuse `sleptAtTick`, deci fără cele 3 s de după hibernare („pauza 5.0 s” după 12–15 h de somn) și începutul se tăia (utilizatorul, 21.11); din 1.1.99 `hibernatedAt` ține minte hibernarea 60 s. `Prefs`: sunete în `filesDir/sounds`, istoric, jurnal (100), setări `cfg`.
- `Uploader`: jurnalul (coadă `outbox`, max 2000, loturi de 100) și punctele (`PointQueue`,
  JSONL, max 40.000, loturi de 300) la server; orice răspuns ≠ 200 păstrează datele. Conexiunile rămân în pool (fără `disconnect`, răspunsul citit
  până la capăt), ca în `LiveState`. În
  `Uploader` nu se folosește `Prefs.log`. `CrashLog` scrie „CADERE: …” în jurnal.
- `CanLink` (legătură permanentă la `com.syu.ms`, protocolul `Syu`), `TripRecorder` (GPS + CAN;
  punct la 5 s în mers, 30 s pe loc cu motorul pornit, unul la oprire; GPS oprit după 1 min cu
  motorul oprit), `LiveState` (`/api/car/state`: 5 s cu motorul pornit, 15 s doar contact, 60 s
  fără, imediat la orice schimbare de detaliu). Contactul e dedus în `CanLink.contact()`.
- `Speaker` + `Greeting`: salut după 30 s de mers („Bună seara” de la apus, calculat pentru București, cel târziu de la 20:00; 15.11 a zis „Bună ziua” pe întuneric la 17:23) (MP3 Piper de la `/api/car/tts`, altfel
  TextToSpeech local `ro-ro-x-vfv-local`, motorul `com.google.android.tts`), avertizare de ușă
  deschisă la ≥ 5 km/h (mereu vocea locală). TextToSpeech se eliberează la 30 s după ultima
  vorbire (1.1.56). Utilizatorul a dezactivat aplicația Google și TXZ, nu motorul de voce.
- `VwStatus` (`/api/car/status`, `summary`, `route`), `Updater` (actualizare din aplicație prin
  `PackageInstaller`, merge; din 1.1.74 descarcă în avans versiunea nouă în `filesDir/update.apk`, din firul
  `Uploader`, și o propune la ~1 min după ACC ON (`StartActivity` → `scheduleOffer`, cererea utilizatorului:
  încălzește motorul înainte să plece; și în mers e în regulă): o atingere, „Instalați”. La 1 min cere
  „Afișare peste alte aplicații” (SYSTEM_ALERT_WINDOW; fără ea, imediat la autostart); testat în emulatorul
  Android 10, fereastra apare peste altă aplicație; din 1.1.76 și imediat ce s-a descărcat, dacă
  contactul e pus, chiar în mers (cererea utilizatorului); starea de pe server la 10 min, nu 30; din
  1.1.78 răspunsul la `/api/car/state` are `apk` (ultima versiune), deci cu motorul pornit o vede în ~5 s
  (`Updater.onServerVersion`, cel mult o dată pe minut); 02.11: descărcată la 2 s după contact, a apărut
  imediat, deci din 1.1.79 și propunerea de după descărcare așteaptă minutul de la ACC ON (`accOnAt`); „Anulare” = nu mai întreabă 6 h; comutator Setări → Sistem „Actualizare la pornire”), `CanProbe` (Setări → „Sonda CAN (avansat)”: 10 min, „Marchează”
  trimite `CAN MARK <text> | schimbări`; lista `KNOWN` din `MainActivity` ascunde codurile
  știute, dar nu c10/c13/c27/c28 și cadrul 0x21).
- Memoria (20.10, cu comenzile): navigația are 4 GB, swap 2,2 GB plin, ~400–550 MB disponibili;
  Teyes își dă −700 (`skipkillapp.prop` −14), noi 200 (serviciu) → omorâți la somn. Tapetul
  animat al utilizatorului (`earthmoon`, ~330 MB, 25% CPU) a fost și el omorât; din 21.10 tapet static; tot pe 21.10 utilizatorul a dezactivat Google Maps, YouTube, Bunăstare, Chrome, Android Auto (după repornire: 1,7 GB disponibili la 2,5 min, înainte ~330 MB și swap plin). Restul (gesturi, FOTA, butoane, Rockchip etc.) cere ADB prin cablu USB-A–A; utilizatorul a renunțat. Dezactivate de
  utilizator: TXZ, Google; Spotify Teyes nu se poate. `KeepAliveListener` (1.1.55): „Acces la
  notificări” → adj 100 și repornire de către sistem (emulator: 200 → 100, repornit în ~5 s).
  Pe Teyes, cu accesul dat (20.10), adj rămâne 200 (ROM-ul modifică OomAdjuster). Un `kill -9`
  cu navigația trează: serviciul repornit în ~1 s (START_STICKY). Netestat: moartea în somn
  lung (dimineața 20.10) și cine ne repornește atunci. 15.11 (2 zile de la boot, Waze pornit): 1,3 GB disponibili, swap 1,15 GB folosit din 2,25, noi ~180 MB;
  `com.lsec.gesture` (gesturile): ~23% CPU în media de la boot din `ps`, dar doar 2,8% pe 30 s în Panel (17:28). YouTube Music ~400 MB, Waze 300–500 MB:
  cu ele, ~420 MB disponibili; tapetul animat e omorât primul.
- `RemoteCmd` (20.10, 1.1.54): comenzi shell de pe server, doar cu Setări → Sistem → „Comenzi de
  la distanta (teste)” pornit (implicit oprit; utilizatorul îl pornește la teste). Serverul
  (`cmd.ts`, tabela `car_cmd`) pune `cmd: <n>` în răspunsul la `/api/car/state`; aplicația ia
  comenzile (`GET /api/car/cmd`), rulează `sh -c` ca aplicația (fără root: fără `logcat` al
  altora, fără `dumpsys`), limită de timp și 256 KB, trimite `POST /api/car/cmd/<id>`. Eu rulez:
  `cd web && node --env-file=/opt/faikkitcar/.env scripts/car-cmd.ts [--timeout 30] '<cmd>'`
  (doar cât navigația e trează). Testat în emulator cu server de test pe 3999.
  Scripturile de diagnostic Teyes (21.10) sunt în `web/scripts/teyes/` (trimise prin `car-cmd.ts` sau
  `adb shell < …`): `adb-check.sh`, `mem.sh`, `pkgs.sh`, `debloat.sh` (aplicațiile dezactivate pe navigație,
  reversibil cu `pm enable`), `watch-hb.cjs` (urmărește frâna de mână în `car_state`).
- `BleProbe` (22.10): test pentru un adaptor OBD2 fără fire. Bluetooth-ul Teyes (telefonul) e un modul separat
  (`sys.fyt.bluetooth_type=7`, `/dev/BT_serial`, `com.syu.bt`), invizibil pentru aplicații; procesorul are și
  cipul lui (`wcnd`, stiva Android). Testul raportează adaptorul Android, scanează BLE 10 s și clasic 12 s
  (îl pornește și îl oprește la loc dacă era oprit). Doar comanda internă `@bluetooth` prin `car-cmd.ts`
  (`--timeout 60`); butonul din Setări scos în 1.1.72, la cererea utilizatorului. Navigația are și SIM cu LTE.
  Rezultat 22.10: Bluetooth-ul Android e pornit (nume „FAImobil”), BLE merge (a găsit `LEDCAR-01`, −43 dBm),
  deci un adaptor OBD2 BLE se poate citi direct din aplicație; ales vLinker MC+.
- `ObdProbe` (20.11, 1.1.90): adaptorul OBD2 BLE vLinker MC+ (se anunță „vLinker MC-IOS”, `C5:57:46:DD:88:83`, fără
  asociere). Comanda internă `@obd` (setul implicit) / `@obd <comenzi ELM…>` / `@obd reset` (Bluetooth Android oprit și
  pornit) prin `car-cmd.ts`; doar citiri (04/10/2E/31/14/11/27/28/85 refuzate; din 1.1.114 permise doar `10 03` sesiunea extinsă și `10 01` întoarcerea, pentru testul cantității injectate, 23.11). GATT: serviciul `18f0`, notificări `2af0`,
  scriere `2af1` (mai e `e7810a71…`/`bef8d6c9…`, nefolosit). STN1151 v4.3.2, ELM327 v2.2, ~40 ms pe comandă, ~100 ms
  răspunsul motorului. Protocol A6 (CAN 11 biți 500k), motorul răspunde pe 7E8. VIN WVWZZZ1KZDW032311.
  PID-uri suportate: 01 03–07 0B–0F 11 13 15 1C 1F 21 23 2E 30 31 33 34 3C 41–47 49 4A 4C 56; **fără MAF (10) și fără
  consum (5E)**, deci consumul din MAP + IAT + rpm (sau cantitatea injectată prin UDS, de găsit). **UDS da**: 22F190 = VIN,
  22F187 = 03F906070GA, 22F189 = 5413; KWP 1A9B refuzat (7F 11). 20.11, la relanti: fără coduri (03/07), MIL stins;
  testele catalizator, evaporare, sondă lambda și încălzirea ei neterminate. 1.1.90 făcea `close()` imediat după
  `disconnect()`: legătura a rămas deschisă pe navigație și adaptorul n-a mai apărut în scanare; 1.1.91 așteaptă
  deconectarea, ia întâi dispozitivele deja conectate, apoi scanarea, apoi adresa știută.
  A doua rundă (20.11, 1.1.91, relanti cald): prima conectare cod 133, a doua da (reîncercarea e necesară). Motorul: Simos
  10.12 (`22F197`), J623, hardware 03F907309R / H10, ODX `EV_ECM12TFS021`. Rampa 47 bar, catalizator 417 °C, λ cerut 1,000 /
  măsurat 0,998, sarcina absolută 26 %, pedala 14,5/14,9 % în repaus. **0130 = 1 încălzire și 0131 = 0 km de la ștergerea
  codurilor**: ECU-ul a fost resetat de curând (de aici testele neterminate). După test adaptorul nu apare în scanare
  1–3 min (și cu 1.1.91, și după `@obd reset`, deși `getConnectedDevices(GATT)` e gol); apoi revine singur. Cauza neclară.
- `Obd` (21.11, 1.1.92): legătura permanentă cu adaptorul, **fără comutator** (cererea utilizatorului): cu motorul pornit
  (turația CAN > 300) se conectează singur la adresa știută (`obd/addr`, implicit cea din test), la 60 s după oprirea
  motorului îl eliberează (Car Scanner îl poate lua; adaptorul reapare în scanare după 1–3 min). Fără adaptor: reîncearcă
  la 5 s … 15 s (din 1.1.115; înainte 10 s … 2 min), în jurnal o dată „OBD: adaptorul nu raspunde”. Init `ATZ ATE0 ATL0 ATS0 ATH0 ATSP6 ATSH7E0`, `0100`.
  Ciclul: `010C0B0F0349` (turație, MAP, aer admis, starea injecției, pedala), la 2 s `0105044406070E`, la 5 min `0101`
  (coduri / martor, în jurnal la schimbare); după 3 eșecuri trece pe PID-uri unul câte unul. Consumul din aerul aspirat:
  MAP × 1,197 L × rpm/120 × VE 0,85 / (R × T) / (14,7 × λ cerut) / 745 g/L; 0 la injecția tăiată (starea 4, pedala în
  repaus, > 1000 rpm). Pe puncte: `ol` (L de la punctul anterior), `os` (s citite), `olh`, `map`, `iat`, `ect`, `load`
  → `trip_point.obd_l`, `obd_s`, `obd_lh`, `map`, `iat`, `ect`, `obd_load`. Încă nefolosite în calcule și pe site/Panel
  (utilizatorul: întâi strângem date). `@obd` cu legătura deschisă trece prin ea (`Obd.script`). Jurnal: „OBD: conectat”,
  „OBD: deconectat (…), s, cicluri/s, L” (categoria Mașina). Pe ecranul din mașină doar costul, fără L/100 (îl arată bordul).
  21.11 în mers: 1.1.92 s-a conectat singură la 4 s după contact; ~1,5 L/h la 50 km/h (necalibrat). Cererea multiplă
  răspunde corect (`00D | 0:410C… | 1:0F4C…`, ~75 ms), dar a eșuat la pornire cât rula un `@obd` prin legătură: din
  1.1.93 reîncearcă la 1 min și scrie o dată răspunsul în jurnal. Modulele pe UDS prin priza OBD (adresare 11 biți,
  `ATSH<tx> ATCRA<rx> ATFCSH<tx> ATFCSD300000 ATFCSM1`, în ordinea asta, altfel `?`): motorul 7E0/7E8 (J623), bordul
  714/77E (J285, 5K0920873), climatronicul 746/7B0 (J255, 5K0907044GK, `EV_Climatronic`, uneori 7F 22 21 = ocupat),
  airbag-ul 715/77F (5C0959655B). Nu răspund (probabil KWP pe TP2.0): gateway 710, ABS 713, confort 70E, direcția 712,
  cutia 7E1 (manuală). Mod 09: VIN, CALID `03F906070GA 5413`, CVN C3664742, numele „ECM-EngineControl”; mod 06 are teste.
  Valorile VW (DID-urile de măsură) încă necunoscute: de scanat.
  Cererea UDS cu 3 DID-uri deodată merge pe motor, bord și climatronic (cele inexistente sunt sărite; 7F 22 31 doar dacă
  niciunul nu există). 1.1.94–1.1.101: `@obd scan <tx> <rx> <de la> <până la>` punea scanări `22` în memoria aplicației
  (se pierdeau la repornire); înlocuit de **scanarea de pe server** (21.11, vezi mai jos). Un grup cu răspuns e recitit DID cu DID.
  21.11, bordul 714 0000–101A (rezultatele parțiale în `/root/obd-scan-714-partial.txt`): de la 0x1000 multe DID-uri și
  răspunsuri 7F 22 78, ~6 DID/s, iar bucățile prea lungi lăsau puncte fără consum: oprit. 1.1.95: bucăți de cel mult 1,2 s
  cu recitirile la rând, consumul integrat peste goluri de până la 5 s. **Kilometrajul în bordul 1017/1018/1019:**
  `C0 4C 00 03` = 0x0003C04C = 245.836 km (cuvintele de 16 biți inversate).
  1.1.95 a stat 200 s pe grupul 101A (7F 22 78 / fără răspuns la nesfârșit) și tot lăsa puncte fără consum: oprit.
  1.1.96: scanarea doar pe loc (viteza CAN < 1) cu motorul pornit; un grup lent sau fără răspuns trece la recitirea DID cu
  DID, un DID fără răspuns după 3 încercări = „lent” și sărit.
  1.1.97 (utilizatorul vrea să se plimbe cât scanează): și în mers, bucăți de ~0,4 s cu răspunsul așteptat ≤ 0,5 s
  (citirile motorului sub o secundă); ce răspunde greu în mers merge în `scanSlow`, recitit pe loc. Confirmarea cadrelor
  (`ATFCSH7E0 ATFCSD300000 ATFCSM1`) pusă o dată la conectare, la schimbarea modulului doar `ATSH`/`ATCRA`/`ATFCSH`.
  1.1.97: consumul acoperit complet în mers cu scanarea (os 20,3/20, 30,3/30), dar răspunsurile întârziate se lipeau de
  cererea următoare (DID 000F = „41 0C …”, și cererea multiplă „fără răspuns”). 1.1.98: după o cerere fără „>” se
  așteaptă ≤ 1 s și se aruncă restul; un DID e acceptat doar ca „62 <DID> …” / „7F 22 xx”, prefixele 7F 22 78 scoase.
  Comenzile date chiar înainte de o actualizare le ia procesul vechi și se pierd (scanările puse atunci).
  1.1.98, 21.11: bordul 0000–1923 în 640 s (~10 DID/s în zona 0x1000+), rezultate în `/root/obd-scan-714-0000-1923.txt`;
  scanarea așteaptă următoarea pornire. Bordul 1018/1019/101A: kilometraje (`C0 62 00 03` = 245.858)
  într-un jurnal de evenimente al bordului (contor 00/01/02 la 245.836, 245.858, 245.878, în mijlocul drumurilor), **nu**
  kilometrajul de la ultima oprire (corectat 22.11; detaliile în `OBD-CODURI.md`). La 00:05 în mers: 2205 = 0x28 la 41, 49 și 54 km/h (nu e viteza); 2206 = 0x34 (52, ca rezervorul CAN);
  se schimbă: 1007 (crește repede, contor), 100A (92 → 90), 1014, 220C (7D → 7B), 202F (93 → 95). Bordul răspunde în
  ~240 ms cât scanează. Sensul lor: de înregistrat periodic într-un drum și corelat cu CAN/OBD.
- **Scanarea OBD de pe server** (21.11, cererea utilizatorului: tot ce dă mașina, într-o bază, fără să se mai piardă
  nimic). Serverul (`obd-scan.ts`, `obd-scan.test.ts`) ține ordinea în `obd_scan_job` (`ord`; kind `range` first..last,
  `list` ids, `loop` = recitirea codurilor găsite, ids luate din catalog la sfârșitul fiecărei treceri, `round`), catalogul
  `obd_code` (tx, svc, id: valoarea fără ecou, `nrc` refuzul, 256 = lent / fără răspuns, `changes`) și istoricul
  `obd_sample` (prima valoare și fiecare schimbare). svc: `22` (UDS), `01`/`09`/`06` (`<svc><id>`), `02` (`02<id>00`),
  `dtc` (id = serviciul 03/07/0A). Ordinea (`SEED`, pusă o dată la pornirea serverului): motor 0000–FFFF, OBD 01, 09, 06,
  02, erorile, climatronic 0A3B–FFFF, apoi buclele (motor, OBD 01, bord, climatronic). Airbag-ul nu (utilizatorul).
  După un interval terminat, codurile rămase lente intră într-o lucrare `list` `parked` (doar pe loc), imediat după.
  Bordul complet (170 de coduri) și climatronicul 0000–0A3A importate din `/root/obd-scan-*.txt`
  (`scripts/import-obd-scan.ts`, rulat ca `faikkitcar`). Mașina (`Obd`, 1.1.102): `GET /api/car/obd/scan` (primele 3,
  la 60 s, într-un fir separat), prima potrivită (cele `parked` doar pe loc, modulele tăcute în sesiunea asta sărite);
  rezultatele `{t:"r", j, x, s, i, v|n, at}` și progresul `{t:"p", j, r, n, d}` în coada de pe disc `obd_scan.jsonl`
  (`JsonlQueue`, separată de punctele de traseu), trimise de `Uploader.flushScan` la `POST /api/car/obd/scan`; progresul și pe disc
  (`obd/scanJob`, `scanNext`, la ~15 s), deci după o repornire se reia de la cel mai departe dintre server și mașină. Progresul unei
  treceri vechi a buclei e ignorat. 20 de grupuri fără răspuns la rând = modulul tace: lucrarea așteaptă reconectarea
  (reluată de la primul grup tăcut). `@obd scan` = starea. Site: `ObdScanCard.tsx` pe Navigație (lucrările cu progresul,
  „Oprește”/„Reia” = `POST /api/obd/scan/:id {paused}`, modulele, catalogul `GET /api/obd/codes/:tx`, istoricul
  `GET /api/obd/samples/:tx/:svc/:id`). Panel: `renderObdScan` (lucrările, modulele; fără catalog).
  22.11 (revizia cozii, cererea utilizatorului): lucrările `watch` (`WATCH`, `seedWatchJobs`, coloanele `every_s`/`pass_at`):
  candidații din bord și motor (`OBD-CODURI.md`) recitiți la fiecare pornire și apoi la 2 min, înaintea scanării (mașina le
  vede ca `loop`; între treceri serverul nu le trimite). Buclele recitesc și refuzurile 0x21/0x22/0x24. Din 1.1.109:
  grupurile fără niciun răspuns intră la „lente” (până atunci erau sărite, deci coduri pierdute la oprirea motorului /
  modul ocupat; ce s-a pierdut înainte nu se mai știe); o recitire care găsește 7F 22 31 trimite `n: 0x31` și serverul
  scoate codul lent din catalog; `reachedHere` ține progresul fiecărei lucrări în proces (fără el, după fiecare urmărire
  scanarea s-ar fi reluat de la `next` din lista veche de un minut).
  **Acoperirea completă** (22.11, cererea utilizatorului: să nu pierdem coduri și să nu repetăm munca): din 1.1.110
  aplicația trimite și codurile care nu există (`n: 0x31`, `c` = câte la rând, un grup de 3) → tabela `obd_absent`;
  orice cod e deci bun / refuzat / lent (`obd_code`) sau inexistent (`obd_absent`), iar ce nu e în niciuna n-a primit
  răspuns. La sfârșitul unui interval, `unresolved()` (lente + negăsite nicăieri) intră în recitirea doar pe loc. Aplicațiile
  fără `?cap=absent` la `GET /api/car/obd/scan` primesc doar urmăririle (`carScanJobsFor`). Progresul cu altă trecere
  (`r` ≠ `round`) e ignorat la orice lucrare. Refăcute pe 22.11 (copie `/root/backups/faikkitcar-before-requeue-20261122.db`):
  motorul de la 0000 (trecerea 1: 0000–469C fuseseră scanate fără inexistente și cu grupuri sărite; codurile găsite rămân),
  climatronicul 0000–FFFF (0000–0A3A importat din scanarea veche din aplicație, care sărea la fel), bordul încă o dată
  0000–FFFF (lucrarea 14). Pe site / Panel la module: „N verificate că nu există”. Ordinea de acum (22.11, utilizatorul):
  urmăririle, OBD standard (01/09/06/02 + erorile, ~10 min), motorul, bordul (a doua trecere), climatronicul, buclele.
  Verificarea de după (22.11): la OBD-ul standard (01/09/06/02) recitirea de la sfârșit are doar cele lente (ce nu e
  suportat nu răspunde, nu e „pierdut”); o buclă / urmărire oprită de utilizator rămâne oprită după trecere; din 1.1.111
  o listă (urmărire, recitire) la care 5 coduri la rând nu primesc niciun răspuns trece modulul la „tace” până la
  reconectare (altfel bordul tăcut ar fi ținut scanarea pe loc ~1,5 min la fiecare 2 min).
  **Scanarea rapidă** (1.1.113, 22.11): cererea `22` de grup cu cifra „1” la sfârșit (36 ms în loc de 244); din ea se
  acceptă doar `7F 22 31`, orice altceva se recitește fără cifră (verificat pe motor 0000–21FC față de o copie a bazei:
  identic). ~40–60 coduri/s la motor; peste 3 DID-uri pe cerere motorul nu răspunde. Serverul măsoară ritmul din progres
  (`noteProgress`, ferestre de 30 s, în memorie) → `etaS`/`endsS` la lucrări (site și Panel: „~9 min · gata peste …”) și
  `live.obd.scanHz` („scanează N coduri/s” în locul citirilor/s). Urmăririle: doar codurile nesigure (bord 15, motor 14).
  **Clapeta climatronicului** (23.11): motorașul V426 (B1091) se blochează intermitent (punct mort). Urmărirea
  „clapeta de distribuție” (746, 262E reală / 262F cerută, la 1 min, prima în coadă) → `flap.ts` (`obd_flap`, testat în
  `flap.test.ts`): reală − cerută în afara 0x10–0x28, cu aceeași cerere, la 2 citiri la ≥ 30 s = defect „Climatronic ·
  clapeta” în `live.obd.faults` până la o citire bună. Fără aplicație nouă (urmărirea merge din 1.1.110).
  Urmărirea fără pauză (`every_s` 0, seria deasă a candidaților injecției): serverul o trimite cu `again`, iar mașina
  (din 1.1.117, `Obd.nextPass`) o reia singură imediat; înainte, o trecere pe minut (lista se ia la 60 s). Ține pe loc
  lucrările de după ea (o oprești din site / Panel).
  Stare 23.11: motorul și bordul complete (fiecare cod din 0000–FFFF are o stare), climatronicul **oprit la 46 %** ca
  „Recitire: motor” să ruleze întâi: **ținta utilizatorului e consumul exact (cantitatea injectată)**. Analiza: scripturile
  `web/scripts/obd-analyze/analyze.cjs` (fiecare cod care se schimbă, potrivit liniar cu OBD/CAN din `trip_point`) și
  `series.cjs` (seria unui cod cu OBD/CAN alături): `runuser -u faikkitcar -- node web/scripts/obd-analyze/analyze.cjs
  data/faikkitcar.db 7E0 5`. Dacă bucla nu găsește cantitatea injectată: testul cu sesiunea extinsă (`@obd 1003 22…`,
  permisă din 1.1.114 doar în testele manuale).
- **Totul cu OBD e de test, separat de restul (cererea utilizatorului, 21.11):** fără adaptor sau cu comutatorul oprit,
  călătoriile, costurile și aplicațiile merg ca înainte. Călătoriile și consumul vin din GPS + CAN; litrii OBD doar se
  strâng pe puncte (`Obd.take()` → null când e oprit). Nu se leagă nimic de bază de OBD: coada punctelor (`PointQueue`)
  rămâne a ei, scanarea are `JsonlQueue` separat, trimisă ultima în `Uploader.flushScan` (cel mult 4 loturi, fără
  starea trimiterii, o eroare nu oprește nimic), ecranul Acasă nu numără nimic din OBD, `seedScanJobs` în try/catch.
  **Comutatorul „OBD (teste)”** (1.1.103, pornit implicit): pe server în `setting` (`obd_enabled`, `obdEnabled`/
  `setObdEnabled` în `obd.ts`), schimbat din site (`ObdCard`), Panel (`renderObd`) sau mașină (Setări → Sistem,
  `POST /api/car/obd/enabled`; utilizator `POST /api/obd/enabled {enabled}`). Mașina îl ia din răspunsul la starea
  live (`obd`, lipsă = pornit), îl ține în `obd/enabled`; oprit = deconectat, starea „x” (`disabled`), nicio citire,
  nicio scanare, `@obd` refuzat. Cu el oprit, cardul de erori și cardul scanării nu apar. Înlocuiește „Eliberează
  10 min” (același efect pentru Car Scanner, dar până îl pornești înapoi).
- **21.11 seara, prima pornire cu 1.1.103** (de la 1.1.99, mașina nu primise 1.1.100–1.1.102): stiva Bluetooth a navigației
  rămăsese blocată (adaptorul apărea în `@bluetooth` la −46 dBm, dar orice `connectGatt` expira fără callback, și din
  `@obd`; „cod 0”); `@obd reset` a rezolvat-o (conectat la ~20 s). Din 1.1.104 `Obd.unstick()`: după 4 conectări la rând
  fără niciun răspuns (`noAnswer`), oprește și pornește Bluetooth-ul Android,
  cel mult o dată la 30 min (fără adaptor nu face nimic; în jurnal „OBD: Bluetooth repornit …” / „nu se vede”).
  Tot atunci: cererile cu mai multe PID-uri cu „1” la sfârșit (1.1.100) primeau doar primul cadru CAN (turația și MAP,
  fără aer / injecție / pedală, deci niciun litru OBD pe puncte); cifra = numărul de cadre: `010C0B0F03492` (13 octeți,
  2 cadre), `0105044406070E3` (14 octeți, 3 cadre), confirmat 60–75 ms. Scanarea motorului în mers: ~2 DID/s.
  Tot pe 21.11: scanarea stătea la ~1 min după conectare (1.1.103–1.1.104): `refreshJobs` reface lista cu obiecte noi,
  iar buclele din `scanBlock` comparau obiectul (`job == j`); din 1.1.105 se lucrează pe obiectul în curs (`j = job`).
  Erorile UDS ale motorului și airbag-ului (`19 02 FF`, din 1.1.101, netestat până azi) păreau „n-a răspuns”: amândouă
  răspund întâi `7F 19 78` (așteaptă), iar `strip` curăța doar `7F 22 …`; din 1.1.106 orice serviciu, iar `bytes()` aplică
  lungimea mesajului pe mai multe cadre doar de unde începe (înainte se tăiau ultimii 3 octeți după un `7F 19 78`).
  Răspunsul real al motorului (21.11): 63 de intrări, toate 0x40/0x50 (test neterminat de la ștergere), nicio eroare;
  airbag-ul una, 0x10 (testat în `dtc.test.ts`).
  **Becul ars** (21.11, proiectorul dreapta față): becurile le supraveghează BCM-ul (TP2.0, necitit), bordul doar afișează
  mesajul, fără eroare UDS. Găsit prin citiri ale bordului înainte / după (`/root/obd-714-snapshot-2…4`): **DID 2224,
  bitul 0x01 din primul octet** (20 02 bun → 21 02 ars → 20 06 schimbat; al doilea octet necunoscut). Din 1.1.107 se
  citește odată cu erorile (15 min, `o.u.l`), serverul îl ține în `obd_lamp` (`since` = de când e aprins, `lampWarning`
  testat) și îl pune în `faults` ca „Bord · bec · Bec ars: vezi pe bord care” (cardul de pe Navigație). Doar proiectorul
  confirmat; celelalte becuri probabil la fel. Tot din bord: 2216 = ora:minutul (0x1734 = 23:52), 2238 ora, 2239 minutul.
- `MediaProbe` (02.11): test pentru controlul muzicii (YouTube Music) din Panel. Cu accesul la notificări
  (`KeepAliveListener`), `MediaSessionManager.getActiveSessions` + `MediaController`. Doar comanda internă
  `@media [play|pause|toggle|next|prev|stop|seek <s>|search <text>|key <tasta>|vol [n]]` prin `car-cmd.ts`.
  Rezultat 02.11 (1.1.80, YouTube Music): sesiunea se vede (titlu, artist, album, durata, poziția, coperta ca
  bitmap, fără URI; acțiuni 0x27afb7); `next`/`pause`/`play` merg imediat, `key next` merge (1–2 s „pauza” cât se
  încarcă); `search` nu face nimic; `setStreamVolume` e ignorat (rămâne 25/25: volumul îl ține Teyes, `com.syu.ms`).
  Căutarea (02.11): MediaBrowser refuzat (YouTube Music acceptă doar Android Auto etc.), `MEDIA_PLAY_FROM_SEARCH`
  nu schimbă piesa, dar `ACTION_VIEW` `https://music.youtube.com/watch?v=<id>` cu `setPackage` o pornește
  (`MusicServiceDeepLinkActivity`, ~6 s; avem „peste alte aplicații”). ID-urile vin de pe server: `POST
  https://music.youtube.com/youtubei/v1/search` (client `WEB_REMIX`, fără cheie; `params` pentru doar piese).
  Volumul (02.11, confirmat): Android îl ignoră (`setStreamVolume`/`adjustStreamVolume`, rămâne 25/25); Teyes îl
  ține în modulul SOUND (4): update m4 c2 = [volumul] (și m4 c67 = [0, volumul]), comanda `cmd(4, 0, [v])` îl
  pune (5 → 4 → 7 → 6, confirmat de m4 c2). `cmd(4, 2, …)` și `cmd(4, 67, …)` nu fac nimic. Maximul e 36 (utilizatorul).
  m0 c138 = aplicația din prim-plan (`[pachet, activitate]`), m0 c146 / c115 variază singure.
  Făcut (02.11): `Music.java` (din `WelcomeService.onCreate`): sesiunea prin `addOnActiveSessionsChangedListener`
  (cea care redă, altfel prima), starea la `POST /api/car/music` la schimbare (300 ms) și la 30 s, coperta
  JPEG 200 px în base64 doar la piesă nouă; volumul m4 c2, prim-planul m0 c138; comenzile prin `GET
  /api/car/music/wait` (long polling 25 s, doar cu ecranul aprins). `open` = linkul piesei (11 caractere) sau
  al listei; dacă YouTube Music a ieșit în față peste altă aplicație, după 7 s o aduce înapoi pe aceea.
  Server `music.ts` (totul în memorie; starea expiră la 75 s, comenzile la 20 s; `parseSongs` testat în
  `music.test.ts`): `GET /api/music?after=<seq>`, `POST /api/music {action, value}`, `/api/music/search?q=`,
  `/api/music/art/:id`. Panel `MusicCard` (pe Acum, aceeași vedere între redesenări, firul lui de long polling;
volumul pe o bară 0–36 cu −/+; căutarea pornește singură la 400 ms după ultima tastă, tastatura deschisă).
  Teste în mașină 02.11 (seara): cardul apare, comenzile aproape instant; după o piesă din căutare YouTube Music
  rămânea în față (revenirea nu mergea). Din 1.1.85: revine și dacă m0 c138 n-a raportat schimbarea și scrie în
  jurnal „Muzica: piesa din Panel; …” (ce era în față, ce a făcut). La pornire c138 = `com.android.launcher8`.
  1.1.85 (02.11): tot ecranul principal, nu Waze: m0 c138 NU e aplicația din față (cu Waze în față raporta
  launcher8; YouTube Music îl schimbă). Din 1.1.86: aplicația din față din datele de utilizare (`Music.foreground`,
  `UsageEvents` MOVE_TO_FOREGROUND, permisiunea PACKAGE_USAGE_STATS dată din Setări → Sistem „Acces la datele de
  utilizare”), revenirea cu `getLaunchIntentForPackage` (aduce Waze cum era); merge pe Teyes (02.11). Din 1.1.87
  revine imediat ce YouTube Music e în față și cântă piesa nouă (verificat la 300 ms, cel mult 7 s; jurnalul scrie după cât).
  02.11: prima piesă după actualizarea aplicației s-a pierdut (comanda dată cererii rămase deschise a procesului
  vechi). Din 1.1.88 comenzile se confirmă: `GET /api/car/music/wait?ack=<ultima primită>&epoch=<al serverului>`
  → `{epoch, cmds}`; serverul le ține până la confirmare (cel mult 20 s) și le retrimite; mașina sare peste id-urile
  deja făcute. Fără `ack` (aplicația veche), răspunsul rămâne lista simplă. Testat pe un server pe 3999.
- Starea live la 1 s cât cineva se uită (02.11, bordul din Panel rămânea ~5 s în urmă): `/api/live` marchează
  privitorul (`markViewer`), răspunsul la `/api/car/state` are `fast` (privitor în ultimele 30 s), `LiveState.fast()`
  20 s după ultimul `fast`, `TripRecorder.liveTick` trimite la 1 s (fără puncte noi). Panel păstrează harta parcării.
- `SyuProbe` (02.11): `@syu watch <s> [module]` (abonare la codurile 0..199, valorile inițiale și apoi doar
  valorile noi), `@syu get <m> <cod>`, `@syu cmd <m> <cod> [int...]` (`Syu.cmd`/`get`/`unregister`).
  Când navigația nu e accesibilă prin `car-cmd.ts` (limita de login), comenzile se pot pune direct în `car_cmd`.
- `UnitStats` (22.10, 1.1.62): performanța navigației în cheia `u` a stării live (`/proc/stat` între
  trimiteri, `meminfo`, `loadavg`, `uptime`, zona termică cea mai caldă fără `fgu`, `statm` și
  `oom_score_adj` proprii, spațiul liber; la 30 s procesele de top după procesor și memorie
  proprie). Serverul (`unit.ts`): ultima în memorie (`live.unit`), istoricul la 30 s în `unit_stat`.
  `unitHealth` (pur, `unit.test.ts`) dă starea pe scurt; numele proceselor (`procLabel`) tot acolo.
  Site `UnitCard.tsx`, Panel `UnitCard.java`. Emulatorul (Android 10) nu lasă aplicația să citească
  `/proc/stat`, `loadavg`, `uptime`; pe Teyes merg (comenzile din 20–21.10).
- `CellSignal` (28.10, 1.1.64): pe fiecare punct de traseu `net` (0 fără internet, 1 date
  mobile, 2 Wi-Fi), `rat` (lte/nr/3g/2g/„-”), `ch` (EARFCN…), `sig` (dBm, RSRP la 4G), `op`
  (MCC+MNC, 22605 Digi); din `getAllCellInfo` (doar permisiunea de locație). Serverul
  (`signal.ts`, pur + `signal.test.ts`): banda din canal, harta pe pătrate de ~200 m, căderile
  de internet; `GET /api/unit/signal?days=`, site `SignalCard.tsx` pe fila Navigație, Panel
  `renderSignal` + `MapBox.signal` (pătratele desenate direct pe canvas, harta păstrată între redesenări).
  Confirmat pe Teyes 28.10: Digi pe EARFCN 3650 (B8 900).
  29.10: `getAllCellInfo` a dat 19 min aceeași celulă (EARFCN 525, −117 dBm) în mers = copie veche;
  din 1.1.65 `requestCellInfoUpdate` la fiecare punct, puterea din `getSignalStrength`, `ca` = vechimea
  citirii (coloana `cell_age`). Cele 134 de puncte înghețate: câmpurile de semnal golite (copie în
  `/root/backups/faikkitcar-semnal-inghetat-20261029.json`). Confirmat cu 1.1.66 (29.10): `ca` 5 s în mers, semnal variabil.
  Panel: harta semnalului are `setDestroyMode(false)` (fără ea rămânea goală după prima redesenare, 1.0.27).
  30.10: utilizatorul vede „G” când pică internetul = modemul coboară pe 2G (GPRS); `signal.ts` socotește
  2G drept fără internet. 1.1.67: Setări → Sistem arată tipul de rețea preferat (`preferred_network_mode`)
  și butonul „Informatii telefon” (`com.android.settings/.RadioInfo`, testat în emulatorul Android 10)
  pentru „LTE only”. Pe drumurile din oraș 29–30.10: niciodată fără internet, ~−100 dBm medie.
  01.11, 18:46: utilizatorul a pus „LTE only” din butonul „Informatii telefon” (meniul Teyes de rețea
  mobilă are doar „LTE (recomandat)”/3G/2G, adică automat). Înainte: un 2G prins pe 31.10 08:11 (GSM
  ARFCN 16, ~5 s, după LTE −123 dBm); după: niciun 2G. Netestat: dacă setarea rămâne după o repornire.
  2G rămâne în monitorizare ca semnal că setarea s-a pierdut. 02.11: a trecut somnul de 8,5 h (22:51–07:23). A rămas după repornire (01.11). Setarea e pe cartelă:
  `preferred_network_mode<subId>` (`preferred_network_mode` general rămâne 9 pe Teyes); 1.1.68 o citește de
  acolo și scrie „Retea mobila: tipul preferat …” în jurnal la pornire și la schimbare. Comenzile de la
  distanță nu pot citi setările (`settings`/`content` cer permisiuni de shell).
  01.11: „LTE only” a trecut repornirea (19:05), dar la 21:40 era iar 9 (și în „Informații telefon”):
  ceva îl reface între timp, probabil trezirea din somn (`sprd_networkcontrol`, `ro.telephony.default_network=9`).
  1.1.69: verificarea în tick (5 s), ca jurnalul să arate momentul exact. Dacă se confirmă: o aplicație
  nu-l poate seta (MODIFY_PHONE_STATE); variantă posibilă, un AccessibilityService care alege „LTE only”
  în RadioInfo. Oprirea scurtă (4 min 45 s, 01.11) nu l-a schimbat. 1.1.70: codul trimis în
  starea live (`u.nm` → `live.unit.netMode`, `net-mode.ts`), afișat pe site/Panel în cardul Navigația;
  `unitHealth` avertizează când nu e 11. 1.1.71: la salutul de 30 s, dacă nu e 11, se adaugă „Atenție! Rețeaua mobilă
  a revenit pe automat…” (și separat dacă salutul e oprit); pe Acasă cardul „Reteaua mobila nu e pe LTE only”
  cu butonul „Pune LTE only” (`CellSignal.openRadioInfo`). Comutator în Setări → In mers: „Avertizare retea
  mobila” (`Prefs.netAlertEnabled`, implicit pornit, 1.1.73). Modemul Teyes are toate benzile 4G din România; Digi e slab pe 800/900 MHz.
- Ecranul (din 12.10, 1.1.47): bara de file jos, compactă (iconiță, Acasă / Sunete / Setări /
  Jurnal, versiunea, starea serviciului). Acasă: harta mare (`RouteMap`, osmdroid; călătoria în
  curs sau ultima, verde = plecare, albastru = mașina, galben = opriri; urmărește mașina până o
  tragi, apoi „Urmărește mașina”; aceeași MapView mutată între redesenări,
  `setDestroyMode(false)`; „↻ Actualizari” în bara de jos din 1.1.72, scos din Setări) și alături doar ce nu arată bordul (cererea utilizatorului): viteza mare,
  starea („In mers”, „Oprit in trafic · 45 s”, „Parcat, motor pornit”…), bateria doar în afara
  limitelor, costul / litrii / traficul călătoriei în curs (sau ultima pe scurt), „Azi”, apoi
  actualizarea, mentenanța scadentă și setările lipsă. Turația, temperatura, clima, rezervorul,
  autonomia, kilometrajul, distanța și durata nu mai apar acolo (le arată bordul).
  În `onStop` harta se eliberează (`onDetach`, fără descărcări sau memorie în fundal) și se reface la
  revenire; în fundal rămâne doar serviciul (CAN, GPS cu motorul pornit, trimiterea la server).
- Jurnalul din aplicație (1.1.61): fără „Temperaturi unitate” (`UnitSensors` șters: dădea doar temperaturile
  procesorului), „Pornire motor: baterie minim”, „Rezervor”, „Calatorii: GPS pornit/oprit” (datele sunt în
  punctele de traseu); volumul intră în „Redare pornita”, fără „Redare terminata”. Comenzile la distanță nu
  intră în jurnal (1.1.60).

## Somnul Teyes (confirmat din jurnal)

- 20.10: procesul a murit de două ori exact la intrarea în somn (~10 min după ACC OFF, 09:26 și
  11:16, fără „Ecran stins”), iar autostart-ul n-a pornit aplicația la ACC ON. `TeyesCheck`
  (linia „Teyes: …” la pornirea serviciului) arată dacă suntem încă pe listele Teyes.
- 20.10: procesul a murit în somnul de la service, fără „Serviciu oprit” și fără repornirea
  START_STICKY (deci probabil force-stop), iar autostart-ul a ratat la plecare: 3 km pierduți.
- ACC OFF scurt (sub ~10 min): procesorul nu doarme, nu vine SCREEN_OFF; doar autostart-ul
  prinde ACC ON. ACC OFF lung: somn la ~10 min (SCREEN_OFF). Cu `com.mapgoo.diruite` (în
  `unkill_app.txt` din com.syu.ms și `LaunchWhiteList` din `/oem/app/pwctl_config.xml`) serviciul
  supraviețuiește somnului. Autostart-ul ratează uneori; detecția prin pauză acoperă cazurile cu somn.
- 07.10, 08:57–18:29: navigația a închis aplicația și n-a mai pornit-o (17 km neînregistrați).
- Din forumuri: somn ≤ 9 mA, oprire completă după ~72 h, reporniri complete periodice.

## Codurile CAN (modulul 7 = CANBUS, decodor Raise; c1019 = cadre brute 0x2E, cmd, len, date, sumă)

Confirmate: c110 / c1032 turație; c1031 viteză km/h (~6% peste GPS); c109 viteză ×100; c105
tensiune ×100; c106 kilometraj; c139 temperatura exterioară ×10; c104 litri în rezervor (0 cu
motorul oprit, se ignoră ≤ 0; oscilează cu 1 L); c1–c5 uși (șofer, pasager, spate st., spate dr.,
portbagaj; 1 = deschis); c101 centura șoferului desfăcută (1, confirmat 12.10); c103 frâna de
mână (0 = trasă, 1 = eliberată; sursa principală; după repornirea navigației vine 1 și cu frâna
trasă până la prima schimbare, deci din 1.1.58 valoarea 1 contează doar după o schimbare în boot-ul
curent; 15.11: tras/eliberat de 8 ori la rând, c103 a urmat fiecare mișcare, Panel corect, iar bitul 0x20 din 0x41/1 a rămas în urmă); c11 AC; c49 AUTO; c21 ventilator (0 =
climatronic oprit); c27/c28 temperatura setată stânga/dreapta = 17,5 + v/2 °C (confirmat 12.10:
11 = 23 °C, 7 = 21 °C; urmează și SYNC-ul, care n-are cod propriu); cadrul 0x21 = clima [5, 200,
ventilator, stânga, dreapta, 0, sumă]; cadrul 0x14 al doilea octet = iluminarea bordului =
luminile (0 / 76–84, confirmat 12.10 pe AUTO; vine doar la schimbare, deci după o repornire a
aplicației starea rămânea necunoscută: scos din 1.1.59, la cererea utilizatorului); cadrul 0x24 bitul 0x02 = frâna de mână trasă;
0x41/1 = biți de stare (0x20 părea frâna trasă, dar pe 21.10 a stat 0 cu frâna trasă, deci nu se mai
folosește; 0x80 NU e faza scurtă); 0x41/2 = rpm, viteză,
tensiune, temp., km (3 B), litri; m0 c68 = marșarierul ([1, 1] băgat); m0 c179 = GPS; m0 c77 =
atingerile pe ecran.
Nu sunt transmise: pedala de frână, semnalizarea, avariile, ștergătoarele, temperatura din
habitaclu, consumul. c1033 NU e consumul (integrat dă 17,5 L/100 față de 9,4 real și abia variază
cu viteza); se mai păstrează brut în `trip_point.cons`. c107 neclar (stă pe 1). Pentru consum
instantaneu ar trebui un adaptor OBD2 Bluetooth (MAP + IAT + rpm).

## Calculele

- Călătoriile (`trip-math.ts`, pur, testat): din segmentele dintre puncte consecutive. Gol > 60 s
  pe loc = oprire cu motorul oprit; ambele capete < 1 km/h = pe loc; altfel distanța GPS între
  fixuri bune la ≤ 15 s, altfel din viteză (GPS sau CAN × `canScale`, 0,85–1,05, implicit 0,95).
  Distanța se potrivește cu kilometrajul la ±1 km. Opririle cu motorul pornit: trafic dacă sunt
  între două porțiuni de mers, ≤ 5 min și fără semne de parcare (frâna de mână, o ușă, marșarierul
  cu ≤ 60 s înainte dacă de atunci n-a trecut de 12 km/h, un loc salvat; centura NU e semnal);
  altfel staționare. (13.10: ieșit cu spatele din parcare și oprit la semafor după 150 m apărea
  „Parcat, motor pornit”; corectat și în aplicație, `TripRecorder.drovePastReverse`, 1.1.50.) mers + trafic +
  staționare + opriri = durata. Bateria: `crankVolt` (demarorul, din `trip_point.crank`; sub 9,6 V
  slabă), `runVolt` (mediana cu motorul pornit, normal 13,5–14,9 V).
- Drumurile neînregistrate (`findGaps` în `trip-math.ts`, `readGaps`, `/api/trips/gaps`): kilometrajul
  sare cu ≥ 2 km între două călătorii; litrii = km × consumul real al intervalului dintre alimentări în
  care cad (`lPer100At`), costul cu prețul benzinei din rezervor. Afișate în lista de călătorii (site și
  Panel); în statistici doar separat (`gapKm`, `gapLiters`, `gapCost` pe lună și pe 30 de zile), nu în totaluri.
- `trips.ts`: gruparea (pauze > 5 min, apoi unite automat cu `sameTrip` din `trip-math.ts`: oprire ≤ 30 min Acasă / la Serviciu
  (16.11; 1 h lipea venirea de la tură de ieșirea de după, 07.10) sau ≤ 3 h în altă parte (`HOME`/`WORK` din `commute.ts`), fără GPS = despărțite; 15.11 utilizatorul:
  o ieșire Acasă → Lidl → bancă → schimb → Acasă e o singură călătorie; prima variantă, „≤ 90 min doar în locuri
  nesalvate”, despărțea la Lidl), combinările (`trip_join`), despărțirile utilizatorului
  (`trip_split`: ora primului punct de după oprire; „Desparte” le scrie pe toate din `Trip.bounds`, „Combină” le șterge), `Trip.via` (`viaPlaces`: locurile salvate ale opririlor, în titlu „Acasă → Academia → Acasă”,
  `route()` pe site, `Fmt.route` în Panel), locurile (`placeAt`), cache
  `memo` refăcut la `dataChanged()`. `live.ts`: starea live (fără stare 90 s = oprită, dar după mers /
  motor pornit „nosignal” = „Fără semnal” până la 1 h; călătoria în curs = ultima, terminată < 5 min
  înainte de acum sau, fără semnal, de ultima stare; „Oprit în trafic” calculat în `readLive`),
  `readLiveRoute`, `readCarSummary`.
- Combustibilul (refăcut 15.11, `fuel-model.ts` pur + `fuel-model.test.ts`; `fuel.ts` = `fuelState`, memo):
  estimarea brută pe drum × factorul intervalului dintre alimentări în care a început drumul (`factorAt`).
  Modelul: linia Willans (randament 0,31, `litersPerRev` 1,16e-5 ≈ 0,52 L/h la relanti, viteza CAN × `canScale`)
  + AC după temperatura de afară (0,4 L/h × clamp((T − 5)/20, 0, 1,5); fără temperatură = 1) + pornirea la rece
  (`coldStartLiters`: max(0,04, 0,08 + 0,005 × (20 − T)) L, se consumă cu τ 6 min de mers; `coolDown` τ 2,5 h,
  starea `cold` dusă de la o călătorie la alta în `trips.ts`). Litrii staționării = `idleLiters` (cu AC și rece).
  Calibrarea (`calibrate`): ancorele = salturile indicatorului (≥ 3 L, mediane pe 10 min) potrivite cu
  alimentările din jurnal (`matchRefuels`: cea mai apropiată la ±12 h; apoi ±72 h dacă litrii diferă cu ≤ 8 L
  = `late`, adică data scrisă greșit; `seen`/`gaugeAt` în `/api/fuel`, avertizare pe site și în Panel).
  Consumat într-un interval = nivelul dinaintea alimentării + litrii de pe bon − nivelul dinaintea următoarei
  (indicatorul nu se citește imediat după alimentare: nu e liniar la capete, plinul din 20.10 l-a urcat cu
  40,5 L la 46,18 L pe bon); plin → plin = doar litrii de pe bon (`exact`). Intervalele se unesc până la ≥ 20 L;
  cele închise își păstrează factorul, cel deschis ia media ponderată a ultimelor 3; factorul între 0,4 și 2,5;
  estimarea se extinde la km de pe kilometraj (drumurile neînregistrate). Fără intervale: metoda veche pe
  toată perioada (`levelConsumption`, de la 8 L, `source: "level"`), altfel 1. Nivelul se citește pe 400 de zile.
  Costul = litri × prețul mediu al benzinei din rezervor la acel moment (`tankPrices`/`priceAt`: ce era în
  rezervor la prețul vechi + bonul; `tankPrice` în `/api/fuel`). Formularele (site, Panel, propuneri) cer
  litrii și totalul plătit; prețul pe litru = total / litri (4 zecimale); „Plin” nebifat din start.
  Pe datele reale (15.11): 01.10 → 20.10 ≈ 26 L pe 249 km = 10,4 L/100, factor 1,01; 20.10 → 14.11 46,18 L
  pe 426 km = 10,8 L/100, factor 0,96 (înainte un singur factor, 0,82, cu AC fix); drumurile înregistrate
  67,8 → 70,0 L, 673 → 696 lei; cele scurte mai scumpe (sub 2 km 16,8 → 21 L/100), cele lungi mai ieftine
  (peste 10 km 9,4 → 8,7). Autonomia = litrii ÷ consumul real; rezervorul de acum = `currentLevel` (mediana
  pe 10 min, doar după ultimul salt). Alimentări: 01.10 (parțială, 16 L, id 1), 20.10 (plin, id 2), 14.11 (parțială, 31 L, id 3), 20.11 (plin până la oprirea pistolului, 25,13 L, 225,67 lei, id 4).
  Plin → parțial(e) → plin cu sub 20 L după ultimul parțial: restul = bonurile de la plinul anterior − intervalele
  încheiate de atunci (20.11: indicatorul arăta 35 L înainte, +17 la 25,13 L pe bon; 31 + 25,13 − 46,18 = 9,95 L pe 90 km).
- Costuri (`stats.ts`): 30 de zile, pe luni, raportul lunii (cu drumurile neînregistrate pe rând separat), „Drumurile tale” între locuri salvate
  (doar de la 10 deplasări în 90 de zile; durata = `arriveAt − departAt` fără opriri), staționarea pe lună.
- Turele (`commute.ts`, 21.10): utilizatorul lucrează 12/24: zi 08–20, noapte 20–08, liber, liber.
  Drumurile Acasă → Serviciu se leagă de tura după sosire (−3 h … +1 h față de început), cele
  înapoi după plecare față de sfârșit (−1 h … +3 h); ora de plecare = început − 15 min − percentila
  90 a drumului (`departAt` → `arriveAt`); ciclul de 4 zile dedus prin vot din drumuri (`next`).
  19.11: doar drumurile directe (fără `via`, `parts` 1) intră în durate; cele cu opriri doar votează ciclul.
  Ora pe zile lucrătoare / libere (weekend + sărbătorile legale, `holidayOf`, Paștele ortodox calculat),
  `toWork.byDay`, de la 2 drumuri; altfel din toate drumurile turei. Dimineața: lucrătoare 12–35 min
  (mediana 17), weekend 5–7 min. `next[]` are `dayType`, `holiday`, `byDay`, `trips`, `driveMin`, `safeMin`
  (cele folosite pentru `leaveBy`; site și Panel le afișează de acolo). Întoarcerea: durata = mers + trafic
  (între plecare și sosire se stă des ~10 min cu motorul pornit). 17–18.11: Acasă → Standox, nu Serviciu.
  Nu mai afișa „cea mai bună oră de plecare”: n-are sens la ture fixe (cererea utilizatorului). Propuneri (`suggestions.ts`): alimentări
  văzute în rezervor, locuri unde parchezi des. Locuri: după adresă (Nominatim, ≤ 1 cerere/s),
  pin tras pe hartă, raza 50–250 m. Un loc poate avea mai multe poziții = rânduri `place` cu același nume (27.10: a doua parcare
  „Acasă”, butonul „+ Poziție”); `savePlace` aduce un nume scris altfel („acasa”) la numele existent. Salvate: Acasă 44.44058, 26.03458; Serviciu 44.44166, 26.06091.

## Panel (`panel/`)

Java fără AndroidX, minSdk 26 / target 34, aceeași cheie de semnare, osmdroid (`MapBox`). Login →
token Bearer (`Store`); `Api` = aceleași rute ca site-ul. File jos: Acum (long polling pe fir
separat, `startLive`; harta live `MapBox.LiveRoute`, aceeași MapView mutată, `render()` așteaptă
cât e atinsă), Călătorii (listă + detalii: `tripSummary`, harta, opririle, `tripSections`,
combinarea), Costuri, Navigație (`UnitCard`, tot din starea live), Mai mult. Același aspect ca site-ul. Actualizare din aplicație (`Updater`,
`/api/panel/apk`). Design (1.0.39): iconițele desenate din cod (`Icon`, aceeași grilă 24×24: filele, muzica,
„⋯”, reîmprospătarea), barele `Slider`, titlurile de card 18 sp (`Ui.title`/`Ui.section`), acțiunile de pe
rânduri în meniul „⋯” (`rowMenu`; atingerea rândului = Modifică), fără emoji; versiunea nouă = bandă subțire.
1.0.40: miniaturile traseelor în listă (`RouteThumb`, din `GET /api/trips/shapes` = `readTripShapes`, ≤ 32 de
puncte pe călătorie, `shapeOf` testat), „Pe luni” cu bare verticale (`BarChart`, km/lei, luna aleasă dedesubt),
detaliile călătoriei într-un card cu grupe (`group`), graficele navigației pe rânduri late cu axa timpului.
1.0.41: bordul pe Acum (`SpeedGauge`: viteza pe arcul mare, turația pe cel mic), autentificarea refăcută.
Site-ul (02.11), la fel: `MusicCard.tsx` pe Acum, `RouteThumb.tsx` + zilele în lista de călătorii, „Pe luni” cu
bare verticale (`Costs.tsx`), acțiunile în „⋯” (`RowMenu.tsx`: alimentări, mentenanță, locuri), autentificarea.
Din 1.0.30 actualizarea e automată, fără confirmare (Android 12+, `setRequireUserAction`,
`UPDATE_PACKAGES_WITHOUT_USER_ACTION`), la deschidere și zilnic (`AutoUpdateJob`); testat în emulatorul
Android 14 (Android permite una la 30 s pe aplicație). Pe telefonul utilizatorului (02.11) instalarea fără
confirmare e refuzată („INSTALL_FAILED_ABORTED: Permission denied”, și după Play Protect): din 1.0.32 butonul
cere mereu confirmarea, iar din 1.0.34 o încercare automată eșuată (`silentRefused`) arată butonul și nu se mai repetă.
Din 1.0.35: automat doar dacă instalatorul versiunii de acum e Panel însuși (`Updater.installer`; 1.0.32 a fost instalat
din browser), refuzul ține minte instalatorul (`silentRefusedBy`); altfel, la deschidere pornește singură instalarea
cu confirmare (o dată pe versiune la 6 h). Confirmat 02.11 (1.0.35 → 1.0.36, instalată din Panel): la revenirea în
aplicație apare singură scanarea Play Protect, apoi „Instalează”, apoi scanarea de securitate Xiaomi; după ea
Panel se redeschide singur pe versiunea nouă. Fără nicio atingere nu se poate pe acest telefon (HyperOS).
15.11 (versiunea de după 1.0.41): formularul de alimentare cu totalul plătit, avertizarea alimentării nevăzute în
rezervor, prețul benzinei din rezervor, drumurile neînregistrate pe Costuri (ca pe site); confirmat pe telefon 15.11 (1.0.44).

## Build și publicare

- Local: JDK 17, Gradle 8.7 și Android SDK 34 în `/root/android` (`local.properties` →
  `sdk.dir=/root/android/sdk`): `JAVA_HOME=/root/android/jdk /root/android/gradle-8.7/bin/gradle
  :app:assembleDebug :panel:assembleDebug`.
- CI: `.github/workflows/build.yml` (aplicația: la push pe main, nu pentru `web/**`, `deploy/**`,
  `*.md`; release `build-<N>`, `versionName` = `1.1.<N>`, publicat la `POST /api/car/apk`) și
  `panel.yml` (release `panel-<N>`, `1.0.<N>`, `POST /api/panel/apk`). `[skip ci]` în mesaj
  sare build-ul. Secrete: `VW_LOG_TOKEN`, `CI_TOKEN`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` (PKCS12, alias
  `vwwelcome`; keystore-ul nu e în repo). Fără `VW_LOG_TOKEN` trimiterea e dezactivată.
- Descărcare manuală: https://github.com/Faicu/FaikkitCar/releases/latest/download/FaikkitCar.apk

## Stare (12.10.2026)

- 22.10: în mașină FaikkitCar 1.1.62 (1.1.63 publicat: testul Bluetooth) (performanța navigației, confirmată), pe telefon Panel 1.0.23; 1.0.24 publicat (fila Navigație).
- În mașină FaikkitCar 1.1.56 (din 21.10, cu „Acces la notificări” dat), pe telefon Panel 1.0.19 (1.0.21 publicat pe 21.10: jurnalul pe categorii).
- 21.10: aplicația a supraviețuit somnului de 18 h („trezire din hibernare”); omorâtă la 2,5 min
  după plecare (memorie), repornită de `KeepAliveListener` în ~7 s. Actualizarea: după confirmare
  instalarea mai durează ~25 s (Play Protect) fără nimic pe ecran; a doua apăsare făcea a doua
  sesiune și o a doua instalare. Din 1.1.57 `Updater.busy` blochează apăsarea și butonul arată
  „Se instaleaza...”.
- De urmărit: cât de des moare procesul imediat după plecare (aplicațiile pornite la trezire).
- Doar contact, fără motor: nu se înregistrează puncte, deci nu apare nicio călătorie (confirmat
  din date pe 12.10, de mai multe ori). Motorul pornit fără mers face o „pornire pe loc” (ascunsă).
- Contact pus cu motorul oprit: confirmat 15.11 (Panel 1.0.44 → „Doar contact”, 13,2 V, 0 rpm).
- Avertizarea de ușă: confirmată 15.11 (ușa șoferului, vocea locală la ~1,7 s, cât pornește TextToSpeech).
- Netestat încă în mașină: harta live pe drum.
- 1.1.100 (21.11): cererile motorului cu „1” la sfârșit (un singur răspuns: adaptorul nu mai așteaptă după răspuns;
  după răspunsurile lente ale bordului ajunsese la ~240 ms pe cerere, ~3 citiri/s). Codurile de eroare (`01011`, `03`,
  `07`) la conectare și la 5 min, brute în starea live (`o.d`); serverul `dtc.ts` (pur, `dtc.test.ts`: coduri + descrieri
  în română, altfel după familie) și `obd.ts` (`obd_dtc` activ = `cleared_at` NULL, `obd_check` = ultima citire, martorul).
  `live.obd` = {status, hz, enabled, checkedAt, mil, codes, faults, modules}. Site: `ObdCard.tsx` (`DtcCard` pe Navigație, de pe Acum mutat 21.11 la cererea utilizatorului, doar
  cu erori, `ObdCard` pe Navigație); Panel: `renderDtc`/`renderObd`. Cardul de erori fără notificări (utilizatorul).
  „Eliberează 10 min” (1.1.100–1.1.102) a fost înlocuit de comutator (mai jos).
  1.1.101: erorile tuturor modulelor UDS (cererea utilizatorului: orice eroare, nu doar martorii): `19 02 FF` la motor
  7E0, bord 714, climatronic 746, airbag 715, la conectare și la 15 min (`o.u` = {at, m: {modul: hex sau „-”}}).
  `parseUdsDtcs` (cod de 3 octeți „U1013 00” + starea ISO 14229: 0x01 acum, 0x04 în așteptare, 0x08 memorat, 0x80
  martorul); `obd_module_dtc` / `obd_module_check`; `live.obd.faults` (toate, OBD-ul motorului doar dacă UDS nu-l are),
  `live.obd.modules`. ABS, confort, gateway, direcția (TP2.0) nu se citesc.
- Idei neîncepute: alertă pe telefon la pornirea mașinii, ore de liniște, sunet după ora zilei,
  adaptor OBD2 pentru consum instantaneu.
