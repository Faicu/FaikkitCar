# FaikkitCar – context pentru Claude

Utilizatorul scrie în română; răspunde în română. Rezumatul pentru utilizator (funcții, ✅/🧪,
ce urmează, istoricul versiunilor) e în `README.md`; ține-l la zi. Mașina: Golf 6 (1K), 1.2 TSI
77 kW (CBZB), benzină, 2012. Navigația: Teyes CC3 2K (FYT/SYU, Android 10, `sprd
ums512_1h10_Natv`, ecran lat, fără selector de fișiere).

## Proiectul

Repo `Faicu/FaikkitCar` (fost `welcometovw`/„VW Welcome”), pe server în `/opt/faikkitcar`.
Separat complet de FaikkitBox (`/opt/faikkitbox`): nimic despre mașină acolo. Migrarea din
FaikkitBox s-a încheiat pe 01.10.2026 (`web/scripts/import-faikkitbox.ts`; copie de siguranță în
`/root/backups/faikkitcar-20261001-215730`). Remote Control: `claude.service` pornește sesiunile
tmux `faikkitbox` și `faikkitcar`.

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
  (`trip-math.test.ts`, `fuel-model.test.ts`), `npx tsc --noEmit`.
- `/opt/faikkitcar/.env` (nu în git): `PORT=3001`, `ADMIN_USER`/`ADMIN_PASS` (login site + Panel,
  utilizatorul `faicu`), `SESSION_SECRET`, `CAR_TOKEN` (= secretul GitHub `VW_LOG_TOKEN`, intră în
  APK), `CAR_TOKEN_OLD` (opțional, doar la o rotire a cheii, cât mașina are aplicația cu cheia veche; scoasă pe 18.10), `CI_TOKEN`
  (= secretul GitHub `CI_TOKEN`, doar `POST /api/*/apk`). Repo-ul e privat din 17.10 (cheia mașinii
  ajunsese în APK-urile publice).
- `/opt/faikkitcar/data`: `faikkitcar.db`, `apk/`, `tts/` (cache voce), `piper/` (binar + model
  `ro_RO-mihai-medium`, nu în git), `service/<id>/<fileId>` (bonuri).
  Tabele: `log`, `trip_point`, `car_state` (un rând), `reminder`, `refuel`, `place`, `trip_join`,
  `service`, `service_file`, `dismissed`. Coloane noi se adaugă prin migrare în `db.ts`.
  Jurnalul nu se șterge niciodată.
- API mașină (Bearer `CAR_TOKEN`; APK-urile se încarcă cu `CI_TOKEN`): `POST /api/car/log`, `/api/car/trip`, `/api/car/state`,
  `/api/car/tts`; `GET /api/car/status` (kilometraj, mentenanță, ultimul APK, locuri),
  `/api/car/summary` (ecranul Acasă), `/api/car/route?last=1&after=` (harta), `GET|POST
  /api/car/apk`, `/api/car/apk/download`.
- API utilizator (cookie `fc_session` sau Bearer din `POST /api/login`): `/api/live` (long
  polling cu `?after=`), `/api/live/route`, `/api/trips`, `/api/trips/points`, `/api/trips/join|split`,
  `/api/stats`, `/api/car`, `/api/fuel`, `/api/refuels…`, `/api/reminders…`, `/api/places…`,
  `/api/geocode`, `/api/suggestions…`, `/api/service…`, `/api/log`, `/api/panel/apk…`.
- Service: `service.items` (JSON: `group`, `name`, `code`, `qty`, `price` pe tot rândul, cu TVA;
  reducerile cu minus) și `service.shop`; lipsă din cerere = rămân cele salvate (Panel-ul le
  afișează, dar nu le editează). Prima lucrare: 19.10.2026, id 2.
- Pagini: Acum (`Now.tsx`: starea, călătoria în curs cu harta live `LiveTripMap.tsx`,
  rezervorul, „Unde e mașina” când nu merge), Călătorii (`Trips.tsx` + `TripView.tsx`), Costuri
  (`Costs.tsx`), Mai mult (`More.tsx`: mentenanță, locuri, service, Panel, jurnal).
- Test fără mașină (12.10): copie a bazei cu `VACUUM INTO` într-un dosar temporar, al doilea server
  cu `FAIKKITCAR_DATA_DIR=<dosar> PORT=3999`, punctele unui drum mutate în prezent și stări
  trimise la 5 s la `/api/car/state`; APK-uri de test cu `https://car.faicu.ro` înlocuit cu
  `http://10.0.2.2:3999` + `usesCleartextTraffic` (apoi totul revenit; nu trimit la serverul real).
  Nu folosi `pkill -f` cu un tipar din propria comandă (omoară shell-ul).

## Aplicația din mașină (`app/`)

Java fără AndroidX, minSdk 26, targetSdk 33, compileSdk 34, AGP 8.5.2, osmdroid 6.1.20. UI din cod.

- `WelcomeService`: foreground (`START_STICKY`, `foregroundServiceType="location"`), tick la 5 s
  cu `elapsedRealtime()`; o pauză peste prag (implicit 60 s) = trezire; contor de boot schimbat
  sau ultima activitate veche = trezire. `SCREEN_ON`/`USER_PRESENT` verifică imediat.
- `StartActivity` („FaikkitCar Start”, translucidă): lansată de lista de autostart Teyes la ACC
  ON → `WelcomeService.startAccOn` redă direct. `BootReceiver`: `BOOT_COMPLETED`,
  `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED` (fără `LOCKED_BOOT_COMPLETED`).
- `Player`: MP3-ul ales, focus audio tranzitoriu, 1,2 s de liniște înainte (ieșirea audio Teyes
  pornește abia cu primul sunet), pauză 5 s + 2 s după hibernare (utilizatorul: +3 s), volum în %,
  anti-dublare 20 s. `Prefs`: sunete în `filesDir/sounds`, istoric, jurnal (100), setări `cfg`.
- `Uploader`: jurnalul (coadă `outbox`, max 2000, loturi de 100) și punctele (`PointQueue`,
  JSONL, max 40.000, loturi de 300) la server; orice răspuns ≠ 200 păstrează datele. În
  `Uploader` nu se folosește `Prefs.log`. `CrashLog` scrie „CADERE: …” în jurnal.
- `CanLink` (legătură permanentă la `com.syu.ms`, protocolul `Syu`), `TripRecorder` (GPS + CAN;
  punct la 5 s în mers, 30 s pe loc cu motorul pornit, unul la oprire; GPS oprit după 1 min cu
  motorul oprit), `LiveState` (`/api/car/state`: 5 s cu motorul pornit, 15 s doar contact, 60 s
  fără, imediat la orice schimbare de detaliu). Contactul e dedus în `CanLink.contact()`.
- `Speaker` + `Greeting`: salut după 30 s de mers (MP3 Piper de la `/api/car/tts`, altfel
  TextToSpeech local `ro-ro-x-vfv-local`), avertizare de ușă deschisă la ≥ 5 km/h.
- `VwStatus` (`/api/car/status`, `summary`, `route`), `Updater` (actualizare din aplicație prin
  `PackageInstaller`, merge), `CanProbe` (Setări → „Sonda CAN (avansat)”: 10 min, „Marchează”
  trimite `CAN MARK <text> | schimbări`; lista `KNOWN` din `MainActivity` ascunde codurile
  știute, dar nu c10/c13/c27/c28 și cadrul 0x21).
- Ecranul (din 12.10, 1.1.47): bara de file jos, compactă (iconiță, Acasă / Sunete / Setări /
  Jurnal, versiunea, starea serviciului). Acasă: harta mare (`RouteMap`, osmdroid; călătoria în
  curs sau ultima, verde = plecare, albastru = mașina, galben = opriri; urmărește mașina până o
  tragi, apoi „Urmărește mașina”; aceeași MapView mutată între redesenări,
  `setDestroyMode(false)`) și alături doar ce nu arată bordul (cererea utilizatorului): viteza mare,
  starea („In mers”, „Oprit in trafic · 45 s”, „Parcat, motor pornit”…), bateria doar în afara
  limitelor, costul / litrii / traficul călătoriei în curs (sau ultima pe scurt), „Azi”, apoi
  actualizarea, mentenanța scadentă și setările lipsă. Turația, temperatura, clima, rezervorul,
  autonomia, kilometrajul, distanța și durata nu mai apar acolo (le arată bordul).
  În `onStop` harta se eliberează (`onDetach`, fără descărcări sau memorie în fundal) și se reface la
  revenire; în fundal rămâne doar serviciul (CAN, GPS cu motorul pornit, trimiterea la server).
- `UnitSensors` (1.1.48): la pornirea serviciului, o linie „Temperaturi unitate: …” (senzor de aer
  Android, temperatura „bateriei”, zonele termice) — căutăm temperatura din habitaclu fără OBD2.

## Somnul Teyes (confirmat din jurnal)

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
mână (0 = trasă, 1 = eliberată; sursa principală); c11 AC; c49 AUTO; c21 ventilator (0 =
climatronic oprit); c27/c28 temperatura setată stânga/dreapta = 17,5 + v/2 °C (confirmat 12.10:
11 = 23 °C, 7 = 21 °C; urmează și SYNC-ul, care n-are cod propriu); cadrul 0x21 = clima [5, 200,
ventilator, stânga, dreapta, 0, sumă]; cadrul 0x14 al doilea octet = iluminarea bordului =
luminile (0 / 76–84, confirmat 12.10 pe AUTO); cadrul 0x24 bitul 0x02 = frâna de mână trasă;
0x41/1 = biți de stare (0x20 = frâna trasă; 0x80 NU e faza scurtă); 0x41/2 = rpm, viteză,
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
- `trips.ts`: gruparea (pauze > 5 min), combinările (`trip_join`), locurile (`placeAt`), cache
  `memo` refăcut la `dataChanged()`. `live.ts`: starea live (fără stare 90 s = oprită, dar după mers /
  motor pornit „nosignal” = „Fără semnal” până la 1 h; călătoria în curs = ultima, terminată < 5 min
  înainte de acum sau, fără semnal, de ultima stare; „Oprit în trafic” calculat în `readLive`),
  `readLiveRoute`, `readCarSummary`.
- Combustibilul: estimarea brută pe drum (`fuel-model.ts`, linia Willans + AC 0,4 L/h) × factorul
  de calibrare. Factorul vine întâi din plinuri (plin → plin, ultimele 5 intervale, extins la km
  de pe kilometraj; din 20.10, pentru că indicatorul nu e liniar la capete: plinul din 20.10 a
  urcat indicatorul 11,5 → 52 L, adică +40,5, cu 46,18 L pe bon), altfel din nivelul rezervorului (`levelConsumption`, 90 de zile: nivelul de
  la început − cel de la sfârșit + alimentările; mediane pe 10 min; o alimentare = salt ≥ 3 L, cu
  litrii de pe bon dacă e în jurnal la ≤ 3 h), de la 8 L consumați; estimarea se extinde la km de
  pe kilometraj, ca drumurile neînregistrate să nu umfle factorul. Altfel 1.
  Verificat 12.10: 17 L pe 180 km = 9,4 L/100 real; drumurile dau 9,6 (factor 0,94). Costul =
  litri × prețul ultimei alimentări (cel plătit efectiv, după reducere). Autonomia = litrii ÷
  consumul real; rezervorul de acum = `currentLevel` (mediana pe 10 min, doar după ultimul salt).
- Costuri (`stats.ts`): 30 de zile, pe luni, raportul lunii, „Drumurile tale” între locuri salvate
  (ora cea mai bună / proastă), staționarea pe lună. Propuneri (`suggestions.ts`): alimentări
  văzute în rezervor, locuri unde parchezi des. Locuri: după adresă (Nominatim, ≤ 1 cerere/s),
  pin tras pe hartă, raza 50–250 m. Salvate: Acasă 44.44058, 26.03458; Serviciu 44.44166, 26.06091.

## Panel (`panel/`)

Java fără AndroidX, minSdk 26 / target 34, aceeași cheie de semnare, osmdroid (`MapBox`). Login →
token Bearer (`Store`); `Api` = aceleași rute ca site-ul. File jos: Acum (long polling pe fir
separat, `startLive`; harta live `MapBox.LiveRoute`, aceeași MapView mutată, `render()` așteaptă
cât e atinsă), Călătorii (listă + detalii: `tripSummary`, harta, opririle, `tripSections`,
combinarea), Costuri, Mai mult. Același aspect ca site-ul. Actualizare din aplicație (`Updater`,
`/api/panel/apk`).

## Build și publicare

- Local: JDK 17, Gradle 8.7 și Android SDK 34 în `/root/android` (`local.properties` →
  `sdk.dir=/root/android/sdk`): `JAVA_HOME=/root/android/jdk /root/android/gradle-8.7/bin/gradle
  :app:assembleDebug :panel:assembleDebug`. Emulator: AVD `panel` (`emulator -avd panel
  -no-window`), test prin `adb`; ecran ca al navigației: `adb shell wm size 2000x1200`,
  `wm density 300`.
- CI: `.github/workflows/build.yml` (aplicația: la push pe main, nu pentru `web/**`, `deploy/**`,
  `*.md`; release `build-<N>`, `versionName` = `1.1.<N>`, publicat la `POST /api/car/apk`) și
  `panel.yml` (release `panel-<N>`, `1.0.<N>`, `POST /api/panel/apk`). `[skip ci]` în mesaj
  sare build-ul. Secrete: `VW_LOG_TOKEN`, `CI_TOKEN`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` (PKCS12, alias
  `vwwelcome`; keystore-ul nu e în repo). Fără `VW_LOG_TOKEN` trimiterea e dezactivată.
- Descărcare manuală: https://github.com/Faicu/FaikkitCar/releases/latest/download/FaikkitCar.apk

## Stare (12.10.2026)

- În mașină FaikkitCar 1.1.51 (din 18.10, cu cheia nouă; actualizat din aplicație), pe telefon Panel (din aplicație).
- Doar contact, fără motor: nu se înregistrează puncte, deci nu apare nicio călătorie (confirmat
  din date pe 12.10, de mai multe ori). Motorul pornit fără mers face o „pornire pe loc” (ascunsă).
- Netestat încă în mașină: avertizarea de ușă, contact pus cu motorul oprit, harta live pe drum.
- Idei neîncepute: alertă pe telefon la pornirea mașinii, ore de liniște, sunet după ora zilei,
  adaptor OBD2 pentru consum instantaneu.
