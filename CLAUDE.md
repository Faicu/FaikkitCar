# FaikkitCar – context pentru Claude

Utilizatorul scrie în română; răspunde în română.

## Proiectul (de la 01.10.2026, fost „VW Welcome”)

Repo `Faicu/faikkitcar` (fost `welcometovw`; GitHub redirecționează), pe server în
`/opt/faikkitcar`. Proiect complet separat de FaikkitBox (`/opt/faikkitbox`): nimic despre
mașină nu mai trebuie să rămână acolo.

- `app/`: aplicația din mașină, **FaikkitCar** (+ „FaikkitCar Start” pentru autostart).
  `applicationId` rămâne `com.mapgoo.diruite` (lista albă Teyes) și pachetul Java rămâne
  `ro.faicu.vwwelcome` (lista de autostart Teyes reține componenta exactă).
- `web/`: serverul **car.faicu.ro** (Cloudflare Tunnel → `http://192.168.1.192:3001`),
  serviciul systemd `faikkitcar` (unitatea în `deploy/faikkitcar.service`, utilizator
  `faikkitcar`, `ProtectSystem=strict`, scrie doar în `/opt/faikkitcar/data`). Node 22
  rulează direct `server/index.ts` (Hono, `node:sqlite`, tipurile TS se șterg, deci doar
  sintaxă ștergibilă și importuri cu `.ts`); site-ul e React + Vite + Tailwind în `web/src`,
  construit în `web/dist` (`npm run build`, apoi `systemctl restart faikkitcar`).
  Config în `/opt/faikkitcar/.env` (nu în git): `PORT=3001`, `ADMIN_USER`/`ADMIN_PASS`
  (login site + Panel), `SESSION_SECRET`, `CAR_TOKEN` (= secretul GitHub `VW_LOG_TOKEN`).
  Date în `/opt/faikkitcar/data`: `faikkitcar.db` (tabele `log`, `trip_point`, `reminder`,
  `refuel`), `apk/`, `tts/` (cache voce), `piper/` (binar + model, nu în git).
  API mașină (Bearer `CAR_TOKEN`): `POST /api/car/log`, `POST /api/car/trip`,
  `GET /api/car/status`, `POST /api/car/tts`, `GET|POST /api/car/apk`,
  `GET /api/car/apk/download`. API utilizator (cookie `fc_session` sau Bearer din
  `POST /api/login`): `/api/trips`, `/api/trips/points`, `/api/car`, `/api/reminders…`,
  `/api/fuel`, `/api/refuels…`, `/api/log`. Fără ștergerea jurnalului (nu se pierde nimic).
  Migrarea din FaikkitBox s-a încheiat pe 01.10.2026: datele copiate cu
  `web/scripts/import-faikkitbox.ts` (idempotent, verificare rând cu rând: 10.171 linii,
  321 de puncte, 1 alimentare), apoi în FaikkitBox s-au șters codul, tabelele `vw_*`,
  `data/vw-apk`, `data/vw-tts`, `data/piper` și `VW_LOG_TOKEN` (commit local `2c986a0`,
  publicat de utilizator din Tehnic). Copie de siguranță completă (baza FaikkitBox de
  dinainte, fișierele, `.env`): `/root/backups/faikkitcar-20261001-215730`.
  Mașina mai rula 1.1.31 (trimite la `status.faicu.ro/api/vw-*`, acum 404): aplicația
  păstrează datele în coadă la orice răspuns ≠ 200 (40.000 de puncte, 2.000 de linii) și le
  trimite la car.faicu.ro după instalarea manuală a FaikkitCar ≥ 1.1.32 (1.1.31 nu mai vede
  actualizări, căutându-le pe vechiul server).
- `panel/`: aplicația nativă **FaikkitCar Panel** pentru telefon (`ro.faicu.faikkitcar.panel`,
  Java fără AndroidX, minSdk 26 / target 34, UI din cod ca `app/`, aceeași cheie de semnare).
  Login → token Bearer în SharedPreferences (`Store`); `Api` = aceleași rute ca site-ul.
  Fila Mașina (poziție + hartă osmdroid/OpenStreetMap, totaluri 30 de zile, călătoria aleasă
  cu traseu și `ChartView`, alimentări și mentenanță cu dialoguri, lista cu „ascunde pornirile
  pe loc”) și fila Jurnal; reîmprospătare la 30 s, redesenare doar la date schimbate.
  Actualizare din aplicație (`Updater`): `GET /api/panel/apk` + `/api/panel/apk/download` (login);
  CI-ul separat `.github/workflows/panel.yml` publică la `POST /api/panel/apk` (CAR_TOKEN),
  release `panel-<N>`, `versionName` = `1.0.<N>`. Site-ul are link „Descarcă APK” pe fila Mașina.
- Build local (fără CI): JDK 17, Gradle 8.7 și Android SDK 34 în `/root/android`
  (`local.properties` → `sdk.dir=/root/android/sdk`); `JAVA_HOME=/root/android/jdk
  /root/android/gradle-8.7/bin/gradle :app:assembleDebug` / `:panel:assembleDebug`. Emulator:
  AVD `panel` (Android 14, KVM), `emulator -avd panel -no-window`; test prin `adb` +
  `uiautomator dump` (capturi cu `adb exec-out screencap -p`).
- Remote Control: `claude.service` pornește două sesiuni tmux, `faikkitbox` și `faikkitcar`.

## Aplicația din mașină

Aplicație Android nativă în Java (fără AndroidX, minSdk 26, targetSdk 33, compileSdk 34,
AGP 8.5.2; namespace/cod Java `ro.faicu.vwwelcome`, dar `applicationId` =
`com.mapgoo.diruite`, vezi mai jos) pentru navigația Teyes CC3 2K. Redă un MP3 ales de
utilizator la fiecare trezire din hibernare (ACC ON). Detaliile de funcționare sunt în
`README.md`.

- `WelcomeService`: serviciu foreground (`START_STICKY`), tick la 5 s cu
  `SystemClock.elapsedRealtime()`; un gap peste prag (implicit 60 s, minim 30) = trezire.
  În `onCreate`: contor de boot schimbat (`Settings.Global.BOOT_COUNT`) = trezire; altfel
  ultima oră activă din `SharedPreferences` mai veche decât pragul = trezire.
  `SCREEN_ON` și `USER_PRESENT` (înregistrate dinamic) fac verificarea imediat;
  `SCREEN_OFF` doar se notează în jurnal.
- `BootReceiver`: `BOOT_COMPLETED`, `QUICKBOOT_POWERON` (android și htc),
  `MY_PACKAGE_REPLACED` pornesc serviciul. `LOCKED_BOOT_COMPLETED` e omis intenționat
  (ar cere directBootAware, iar setările sunt în stocarea criptată).
- `Player`: `MediaPlayer` ținut static, `AUDIOFOCUS_GAIN_TRANSIENT`, 1,2 s de liniște înainte
  (pornește ieșirea audio), pauză configurabilă
  (0–15 s, implicit 2,5 s), volum opțional în % din `STREAM_MUSIC` (0 = volumul sistemului),
  refăcut după redare; protecție anti-dublare de 20 s (butonul Test o ocolește).
  Schimbările de focus audio din timpul redării se notează în jurnal (doar notare).
- `Prefs`: sunete multiple în `filesDir/sounds` (`<millis>_<nume>`), la rând sau aleatoriu;
  istoric treziri (20); jurnal de diagnostic (100 de evenimente). Setările stau în
  preferințele `cfg` (rescrise la ~15 s de `lastAlive`), jurnalul și istoricul în
  `journal` (fără migrare: pachetul `com.mapgoo.diruite` a pornit curat). `deviceInfo()` dă antetul
  jurnalului copiat (versiuni, model, optimizare baterie, notificări, setări).
- `Uploader`: trimite jurnalul la `https://car.faicu.ro/api/car/log` (fila Jurnal, tabela
  `log`; citibil direct din `/opt/faikkitcar/data/faikkitcar.db`). Coadă `outbox` în
  `journal` (max 2000), loturi de 100, `Authorization: Bearer` cu `BuildConfig.VW_LOG_TOKEN`
  (secret GitHub `VW_LOG_TOKEN` = `CAR_TOKEN` din `/opt/faikkitcar/.env`). Declanșat la fiecare
  `Prefs.log`, la `onAvailable` al rețelei și la ~30 s din tick. În `Uploader` nu se
  folosește `Prefs.log` (ar reintra în coadă). Serviciul scrie la pornire o linie `Info:`.
- `MainActivity` + `Ui`: UI construit din cod (fără XML), temă întunecată, antet cu stare
  și file Acasă / Sunete / Setări / Jurnal; pornește serviciul în `onCreate`. Iconița e
  imaginea desenată de utilizator („FAIKKITVW”, săgeată cu sigla VW), ca strat față adaptiv
  (`drawable-nodpi/ic_launcher_fg.png`, 432 px, imaginea pe ~80 dp) pe fundal #204671.
- `CanProbe`: pornită doar de calibrare (10 min, `can_probe_until`, verificată din tick).
  Se leagă la `com.syu.ms/app.ToolkitService` (acțiunea `com.syu.ms.toolkit`, AIDL
  `com.syu.ipc`: getRemoteModule=1, register=3 cu (callback, cod, 1), callback update=1) și
  ascultă modulul 0 (0–199), 7 CANBUS (0–1999) și 1–17 (0–199). Ține local valorile și
  schimbările; la server trimite doar `CAN MARK …` (calibrarea) și `CAN SNAP <etichetă> <cheie>
  <valoare>` (captura completă de la pasul rezervorului), nu fiecare schimbare (până la 1.1.24
  trimitea `CAN m<modul> c<cod> i=[…] f=[…] s=[…]`). Referințe: AxesOfEvil/FYTCanbusMonitor,
  chrisuthe/7870-Projects. Mașina: Golf 6 (1K), 1.2 TSI 77 kW (CBZB), benzină, 2012.
- Adăugarea sunetelor (`MainActivity`): `ACTION_OPEN_DOCUMENT` → `ACTION_GET_CONTENT` → listă proprie din
  `MediaStore.Audio` (cere `READ_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO`). Pe Teyes-ul
  utilizatorului selectorul standard lipsea și aplicația cădea (1.1.10).
- `Diagnostics` (butonul „Diagnostic Teyes”, linii `DIAG …` pe server, `QUERY_ALL_PACKAGES`)
  a fost scos după 1.1.24; rezultatele (listele FYT `/oem/app/skipkillapp.prop`,
  `protected_app.txt`, `pwctl_config.xml`, `unkill_app.txt` din com.syu.ms) sunt mai jos.
- De la 1.1.15, `applicationId` = `com.mapgoo.diruite`: nume dintr-o aplicație chinezească
  neinstalată care apare atât în `unkill_app.txt` (assets/property din com.syu.ms, citit cu
  diagnosticul), cât și în `LaunchWhiteList` din `/oem/app/pwctl_config.xml`. Scop: să nu
  mai fim închiși la somn și să avem voie la autostart. Neconfirmat încă pe unitate.
  Aplicația veche `ro.faicu.vwwelcome` trebuie dezinstalată manual. (`com.faicu.welcome`
  de pe unitate e o încercare mai veche a utilizatorului, nefolosită.)
- Coduri CAN găsite cu sonda (Golf 6, 30.09, 5 min cu mers scurt; modul 7 = CANBUS):
  c110 = c1032 turație (rpm, relanti ~750); c1031 viteză km/h; c109 viteză ×100;
  c105 tensiune ×100 (V); c106 kilometraj (km, 245066); c139 temp. exterioară ×10 (probabil);
  m0 c179 f=[lon, lat, alt] GPS. Din calibrarea ghidată (30.09): c1 ușă șofer, c2 ușă
  pasager față, c3 ușă spate stânga, c4 ușă spate dreapta, c5 portbagaj (1 = deschis);
  Calibrarea „doar necunoscutele” (01.10, 1.1.27, motor oprit la început): c101 = centura
  șoferului desfăcută (1) ✓; c103 = frâna de mână, 1 = eliberată ✓ (confirmat: la
  pornirea motorului utilizatorul a eliberat-o), cu bitul 0x02 din cadrul brut 0x24; c107 0→1
  la eliberarea frânei de mână și n-a mai revenit; pedala de frână, marșarierul (cu motorul
  oprit) și ștergătoarele NU apar ca cod; marșarierul se vede doar indirect (radarul 0x22 și
  m0 c12/c68/c70, camera). Clima: c10 și c11 0→1 (+ c13 1→0, c21 0→1) la pasul AC, dar utilizatorul a apăsat de
  fapt AUTO (care pornește și AC-ul), deci c10/c11 = AC/AUTO încă nedespărțite; c21 = treapta
  ventilatorului (1→3 la +2), c27/c28 = temperatura stânga/dreapta (9→11 la +1 °C, deci
  pași de 0,5 °C), cadrul brut 0x21 o conține pe toată. Lumini, semnalizare, avarii: decodorul NU le trimite.
  m0 c77 = atingerile pe ecran [apăsat, x, y] (de ignorat). Cadrul brut 0x41 sub 2 conține
  turație, viteză, tensiune, temp. ext. și kilometraj (3 octeți); sub 1 = o valoare care
  crește lent (132→136), posibil temp. lichid de răcire. c21 (0–3), c27/c28 (5/7/9) încă
  nemapate; c1033 neidentificat (33–301); c1049 [?, ~viteză];
  c104 = litri în rezervor (22 pe 30.09; ultimul octet din 0x41/2: rpm 2 B, viteză 2 B, tensiune 2 B,
  temp 2 B, km 3 B, litri 1 B; CONFIRMAT la alimentarea din 01.10: 21 → 38 L pentru 16,0 L pe
  bon; cu motorul oprit decodorul trimite 0, deci se ignoră valorile ≤ 0);
  c1019 = cadre brute Raise (0x2E, cmd, len, date, checksum): 0x14, 0x21 clima, 0x22/0x23
  radar, 0x24 uși, 0x26 unghi volan, 0x41 date bord, 0x7D frecvent. Combustibil/consum: încă negăsite.
- Platforma e FYT/SYU: `com.syu.ms` (MainServer) gestionează ACC (`U_ACC_ON`) și, conform
  XDA, la somnul adânc închide tot ce nu e în `skipkillapp.prop` (valori negative = nu se
  închide) / `unkillapp.txt` (în APK-ul com.syu.ms) / `protected_app.txt`.
- Călătorii: `CanLink` (legătură permanentă la modulul CANBUS: c110/c1031/c109/c105/c106/c139/c104;
  c104 = litrii din rezervor, jurnal „Rezervor: N L” la prima valoare și la salturi ≥ 3 L),
  `TripRecorder` (GPS `LocationManager` + CanLink; punct la 5 s în mers, 30 s cu motorul pe loc,
  nimic cu motorul oprit, plus unul la oprire), `PointQueue` (fișier JSONL în filesDir, max
  40.000), trimise de `Uploader` la `/api/car/trip` în loturi de 300. Serverul (tabela `trip_point`,
  fila Mașina) împarte în călătorii la pauze > 5 min.
  Serviciul are `foregroundServiceType="location"`; permisiunea se cere din aplicație.
- Rezultate calibrare 1.1.31 (01.10 seara, motor pornit): c11 = AC ✓ (bitul 0x40 din 0x21),
  c49 = AUTO ✓ (0 la ventilator manual), c139 = temperatura exterioară ×10 ✓ (19 °C pe bord
  = 190); faza scurtă = cadrul brut 0x14 (al doilea octet 0 → 84, iluminarea); bitul 0x02
  din 0x24 = frâna de mână (cu c103). NU sunt transmise: pedala de frână, semnalizarea,
  ștergătoarele. Marșarierul nu are cod curat (doar m0 c68 și radarul 0x22). c107 rămâne
  neclar (stă pe 1). Rezervorul a scăzut 22 → 21 L după drum, coerent cu c104.
- Calibrare CAN de la 1.1.32: doar c107 (frâna de mână) și marșarierul, în Setări („avansat”);
  restul e confirmat. 1.1.31 (motor pornit, mașina parcată): AC / AUTO separat, pedala de
  frână, frâna de mână (pentru c107), marșarierul, faza scurtă, semnalizarea, temperatura de
  pe bord (număr introdus → `findValue`, confirmă c139) și rezervorul (opțional + captură).
  Pașii cu număr au `Step.ask`. Varianta 1.1.24–1.1.30, descrisă mai jos: pedala de frână vs.
  frâna de mână, marșarierul, centura (cu motorul oprit și o treaptă băgată), apoi pornirea
  motorului, AC, ventilator, temperatură, ștergătoare și rezervorul. La rezervor litrii sunt
  opționali (Car Info nu mai afișează nimic din 01.10; dacă sunt, `CanProbe.findValue` caută
  codurile cu acea valoare, ×1/×10/×100) și se trimite mereu `CanProbe.snapshot`; butonul
  „Doar rezervorul” sare direct la acest pas (captură înainte/după alimentare, comparate pe
  server din `log`). Sonda 10 min;
  la fiecare pas UI-ul arată live schimbările (`CanProbe.changesSince`, fără codurile din
  `KNOWN`), iar marcajul „CAN MARK n GATA: … | schimbări” le trimite la server. Cadrele brute
  Raise au cheie proprie pe comandă („m7 raw 0x21”, „m7 raw 0x41/2”).
- `VwStatus`: `/api/car/status` (kilometraj, mentenanță, ultimul APK), reîmprospătat de
  `Uploader` după trimitere dacă e mai vechi de 30 min și din aplicație; salvat în Prefs.
- `Updater`: dacă `apk.versionCode` de pe server > `BuildConfig.VERSION_CODE`, butonul
  „Actualizează acum” descarcă `/api/car/apk/download` și instalează prin `PackageInstaller`
  (confirmare Android prin `Updater$Result`; prima dată cere „surse necunoscute”). CI
  publică APK-ul la `POST /api/car/apk` după fiecare build pe main.
- `Speaker` (TextToSpeech, ro-RO, ca ghidare de navigație; bip dacă nu există voce; la
  fiecare mesaj alege cea mai bună voce română: calitate, apoi online dacă rețeaua e validată;
  scrie lista vocilor în jurnal, „Voce: voci romane …”; vocea implicită suna robotic) +
  `Greeting`: salut vorbit după 3 min de mers efectiv (ora zilei, temperatura CAN,
  mentenanța scadentă), avertizare „ușă deschisă” la ≥ 5 km/h (uși c1–c5 din `CanLink`).
  Logica e în `TripRecorder.monitor` (deci merge doar cu călătoriile pornite); tot acolo
  GPS-ul se oprește după 1 min cu motorul oprit (rpm 0) și repornește la turație/mers.
- Pe car.faicu.ro, fila Mașina: buton „Ascunde pornirile pe loc” (< 0,3 km și
  < 8 km/h; totalurile le includ), „Unde e mașina” (ultimul punct GPS),
  Mentenanța (tabela `reminder`) și Alimentările (tabela `refuel`). Combustibilul pe
  călătorie e estimat în `web/server/fuel-model.ts` (linia
  Willans: lucru la roți din viteză + rotații × L/rotație, Golf 6 1.2 TSI) și înmulțit cu
  factorul din intervalele plin → plin (ultimele 5, limitat la 0,4–2,5); costul = litri ×
  prețul ultimei alimentări. Drumul din 01.10 (3 km, 9 min pe loc) ≈ 0,38 L brut.
  Etapa 2, dacă trebuie mai precis: adaptor OBD2 Bluetooth (MAP + IAT + rpm).
- `Syu`: protocolul com.syu.ipc scris cu Parcel, folosit de `CanProbe` și `CanLink`.
- `CrashLog`: handler global care scrie „CADERE: …” (excepție + 6 cadre) în jurnal cu
  `commit`, trimis la server la pornirea următoare.
- Pornirea serviciului din `MainActivity` (`Prefs.markUiStart`, fereastră 5 s) nu mai contează
  ca trezire „pornire proces”. Pauza implicită e 5 s, plus `sleep_extra` (implicit 2 s,
  setabil în UI) când procesorul a dormit ≥60 s de la ultimul tick (`sleptAtTick`): după o
  hibernare de ~1 h se pierdea ~1 s din sunet la 5 s pauză.
- `StartActivity` („FaikkitCar Start”, a doua iconiță, translucidă, fără UI): pentru
  lista de autostart Teyes, care o lansează la ACC ON. Pornește serviciul cu extra
  `acc_on` (`WelcomeService.startAccOn`) → `onStartCommand` redă direct („autostart
  (ACC ON)”), fără să ceară o pauză. Motiv: la primul test real (30.09) o oprire scurtă de
  contact NU a suspendat procesorul (serviciul a rămas viu, fără pauză, fără SCREEN_OFF),
  dar autostart-ul a venit la ACC ON. Jurnalul notează „somn total de la boot”
  (`elapsedRealtime - uptimeMillis`), ca să se vadă dacă unitatea a dormit.

## Ce știm despre somnul Teyes CC3 (forumuri XDA/4PDA/Drive2, neverificat pe unitate)

- ACC OFF → somn (suspend-to-RAM, ≤9 mA); după ~72 h oprire completă; reporniri complete
  periodice (la 10–50 de porniri). Sleep mode: Factory → `168`; „Delayed shutdown” în
  General settings amână somnul.
- Firmware-ul standard închide toate aplicațiile la intrarea în somn (firmware modificat
  „Gordgelin” are „no kill”; o sursă pomenește o listă „UnKill”). Deci serviciul probabil
  moare, iar după trezire nu vine `BOOT_COMPLETED`.
- Unitatea utilizatorului are listă de autostart, dar „nu pornește de fiecare dată”.
- Alte căi găsite: aplicație pornită de Android la atașarea unui stick USB (StartTasker,
  XDA), rezultate mixte. Broadcast-uri ACC gen `com.fyt.boot.ACCON` nu ajung la aplicații
  de la Android 8.

## Build

- Nu există Gradle wrapper; CI folosește Gradle 8.7 / JDK 17 (`gradle assembleDebug`).
- `.github/workflows/build.yml` rulează la push și `workflow_dispatch`, publică artifactul
  `faikkitcar-apk` și un GitHub Release `build-<run_number>` marcat latest (nu rulează pentru
  `web/**`, `deploy/**`, `*.md`). Descărcare:
  https://github.com/Faicu/faikkitcar/releases/latest/download/FaikkitCar.apk
- Secretul `VW_LOG_TOKEN` e transmis la `gradle assembleDebug`; fără el build-ul merge,
  dar trimiterea jurnalului e dezactivată.
- Semnare: secretele repo `KEYSTORE_BASE64` și `KEYSTORE_PASSWORD` (deja adăugate; PKCS12,
  alias `vwwelcome`). `app/build.gradle` citește `SIGNING_KEYSTORE` / `SIGNING_PASSWORD`;
  `versionCode` = `GITHUB_RUN_NUMBER`, `versionName` = `1.1.<N>`. Keystore-ul nu e în repo.

## Stare (01.10.2026, seara)

Rezumatul pentru utilizator (funcții, ✅/🧪, ce urmează) e în `README.md`; ține-l la zi.

- Pe navigație: `com.mapgoo.diruite` **1.1.31** (încă „VW Welcome”, trimite la vechiul
  `status.faicu.ro/api/vw-*`, acum 404; datele stau în coadă). Utilizatorul instalează
  manual FaikkitCar 1.1.33 din GitHub Releases; apoi de verificat în `log` că a sosit coada
  (linii cu `version` 1.1.31 și ore din trecut) și că punctele noi au `fuel`.
  Unitate: Android 10 (API 29), `sprd ums512_1h10_Natv`, fără selector de fișiere.
- Pe telefon: FaikkitCar Panel 1.0.1 instalat, login și date ok (confirmat de utilizator).
- Rămas de la utilizator: publicarea commit-ului FaikkitBox `2c986a0` din Tehnic.
  `deploy/claude.service` e instalat (sesiunile tmux `faikkitbox` și `faikkitcar`), iar
  clona veche `/opt/welcometovw` a fost ștearsă (01.10).
- Login site/Panel: utilizatorul `faicu`, parola în `/opt/faikkitcar/.env` (`ADMIN_PASS`).
- Pe mașină, Setări: pauza după hibernare 3 s (pusă de utilizator pe 01.10).
- Comportament Teyes confirmat din jurnal: ACC OFF scurt (sub ~10 min) → procesorul NU
  doarme, nu vine SCREEN_OFF, doar autostart-ul prinde ACC ON; ACC OFF lung → somn la ~10
  min după ACC OFF (atunci vine SCREEN_OFF). Cu pachetul vechi, aplicația era oprită forțat
  la somn (fără START_STICKY după trezire); cu `com.mapgoo.diruite` supraviețuiește. Autostart-ul
  ratează uneori (o dată din câteva), iar detecția prin pauză acoperă cazurile cu somn.
- Confirmat de utilizator: sunetul se aude întreg cu 5 s pauză (+2 s după hibernare);
  iconița proprie apare corect.
  Excepție (01.10): după 17,7 h de somn s-a pierdut ~0,5 s din început; utilizatorul a pus
  +3 s după hibernare, dar seara (12 h somn, pauză 8 s) s-a pierdut din nou. Deci nu e
  timpul de la trezire: ieșirea audio Teyes pornește abia cu primul sunet (și/sau MCU-ul
  taie la schimbarea volumului). De la 1.1.28 `Player` redă 1,2 s de liniște (`AudioTrack`,
  aceleași atribute) după setarea volumului și abia apoi pornește MP3-ul.
- Verificat pe drumul din 01.10: viteza CAN vs GPS +0,8 km/h în medie (abatere 2,2),
  relanti 635–750 rpm, 1100–1700 rpm la 25–50 km/h, 13,9–14,55 V cu motorul pornit,
  12,5–14 °C dimineața, kilometraj 245067→245070 pe ~3 km. Kilometrajul și temperatura
  nu sunt încă comparate de utilizator cu bordul.
- Confirmat din jurnal (01.10, 1.1.24): primul drum înregistrat, salutul
  rostit cu `com.google.android.tts` în română, GPS oprit cu motorul oprit. Actualizarea din
  aplicație merge (1.1.24 → 1.1.27, 01.10 seara). Voci române: `ro-ro-x-vfv-local` și
  `ro-ro-x-vfv-network`, ambele q400; cea online întârzie ~3 s, deci de la 1.1.29
  avertizările folosesc vocea locală. Ambele voci sună robotic (utilizatorul, 01.10), deci de la 1.1.30
  salutul (neurgent, cu internet) vine ca MP3 de la `POST /api/car/tts` (Piper
  `ro_RO-mihai-medium` în `/opt/faikkitcar/data/piper`, nu în git),
  redat ca ghidare de navigație; fără internet sau la eroare rămâne TextToSpeech. Confirmat
  în mașină pe 01.10 (1.1.30): „se aude destul de bine”. Netestat încă: avertizarea de ușă.
- Consum (`web/server/fuel.ts`): consumul real = nivelul de la început −
  cel de la sfârșit + salturile ≥ 3 L (alimentări), pe ultimele 90 de zile; de la 8 L
  consumați calibrează estimarea pe drum (altfel plinurile, altfel factor 1).
- (Istoric) În lucru: nivelul combustibilului. „Car Info” afișa litrii, dar din 01.10 nu mai arată
  nimic. Sonda extinsă (CANBUS 0–1999, modulele 1–17) nu a rulat încă. Plan: „Doar
  rezervorul” înainte și după o alimentare, apoi diferența capturilor `CAN SNAP` pe server.
  Apoi: consum/cost pe călătorie (alimentări detectate automat sau jurnal manual + estimare
  calibrată din turație × timp).
- Idei neîncepute: alertă pe telefon la pornirea mașinii (prin FaikkitCar Panel), ore de
  liniște, sunet după ora zilei, codurile CAN ambigue (frână de mână, marșarier, centură),
  adaptor OBD2 pentru consum instantaneu.
