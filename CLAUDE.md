# VW Welcome – context pentru Claude

Utilizatorul scrie în română; răspunde în română.

## Ce e

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
- `Player`: `MediaPlayer` ținut static, `AUDIOFOCUS_GAIN_TRANSIENT`, pauză configurabilă
  (0–15 s, implicit 2,5 s), volum opțional în % din `STREAM_MUSIC` (0 = volumul sistemului),
  refăcut după redare; protecție anti-dublare de 20 s (butonul Test o ocolește).
  Schimbările de focus audio din timpul redării se notează în jurnal (doar notare).
- `Prefs`: sunete multiple în `filesDir/sounds` (`<millis>_<nume>`), la rând sau aleatoriu;
  istoric treziri (20); jurnal de diagnostic (100 de evenimente). Setările stau în
  preferințele `cfg` (rescrise la ~15 s de `lastAlive`), jurnalul și istoricul în
  `journal` (fără migrare: pachetul `com.mapgoo.diruite` a pornit curat). `deviceInfo()` dă antetul
  jurnalului copiat (versiuni, model, optimizare baterie, notificări, setări).
- `Uploader`: trimite jurnalul la `https://status.faicu.ro/api/vw-log` (FaikkitBox din
  `/opt/faikkitbox`, pagina admin `/vw`, tabela `vw_log`; citibil direct din
  `/opt/faikkitbox/data/faikkitbox.db`). Coadă `outbox` în `journal` (max 2000), loturi de
  100, `Authorization: Bearer` cu `BuildConfig.VW_LOG_TOKEN` (secret GitHub
  `VW_LOG_TOKEN` = `VW_LOG_TOKEN` din `/opt/faikkitbox/.env`). Declanșat la fiecare
  `Prefs.log`, la `onAvailable` al rețelei și la ~30 s din tick. În `Uploader` nu se
  folosește `Prefs.log` (ar reintra în coadă). Serviciul scrie la pornire o linie `Info:`.
- `MainActivity` + `Ui`: UI construit din cod (fără XML), temă întunecată, antet cu stare
  și file Acasă / Sunete / Setări / Jurnal; pornește serviciul în `onCreate`. Iconița e
  imaginea desenată de utilizator („FAIKKITVW”, săgeată cu sigla VW), ca strat față adaptiv
  (`drawable-nodpi/ic_launcher_fg.png`, 432 px, imaginea pe ~80 dp) pe fundal #204671.
- `CanProbe` („Sonda CAN” pe Acasă, 5 min, `can_probe_until`): se leagă la
  `com.syu.ms/app.ToolkitService` (acțiunea `com.syu.ms.toolkit`, AIDL `com.syu.ipc`:
  getRemoteModule=1, register=3 cu (callback, cod, 1), callback update=1) și ascultă
  modulul 0 (coduri 0–199) și 7 CANBUS (0–399, 1000–1299). Trimite doar schimbările, max
  1/s per cod, linii `CAN m<modul> c<cod> i=[…] f=[…] s=[…]`. Pornită/oprită din tick.
  Referințe: AxesOfEvil/FYTCanbusMonitor, chrisuthe/7870-Projects. Mașina: Golf 6 (1K),
  1.2 TSI 77 kW (CBZB), benzină, 2012.
  Adăugarea sunetelor: `ACTION_OPEN_DOCUMENT` → `ACTION_GET_CONTENT` → listă proprie din
  `MediaStore.Audio` (cere `READ_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO`). Pe Teyes-ul
  utilizatorului selectorul standard lipsea și aplicația cădea (1.1.10).
- `Diagnostics` (butonul „Diagnostic Teyes”): trimite doar la server (`Prefs.remoteOnly`,
  linii `DIAG …`) pachetele non-standard + componentele cu nume de somn/kill/autostart,
  rândurile potrivite din `content://settings/{system,global,secure}` și textele potrivite
  din `resources.arsc`/`classes*.dex` ale APK-urilor Teyes/Setări (plus contor pentru
  cuvinte chinezești: 白名单, 保活, 休眠…). Cere `QUERY_ALL_PACKAGES`. Citește și fișierele
  FYT (`/oem/app/skipkillapp.prop`, `protected_app.txt`, `pwctl_config.xml`…),
  `getprop` filtrat și fișierele de configurare din assets ale `com.syu.ms` & co.
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
  c103 frâna (de mână sau pedala, ambiguu); c107 probabil marșarier (dar s-a schimbat și la
  alți pași); c101 poate centura. Lumini, semnalizare, avarii: decodorul NU le trimite.
  m0 c77 = atingerile pe ecran [apăsat, x, y] (de ignorat). Cadrul brut 0x41 sub 2 conține
  turație, viteză, tensiune, temp. ext. și kilometraj (3 octeți); sub 1 = o valoare care
  crește lent (132→136), posibil temp. lichid de răcire. c21 (0–3), c27/c28 (5/7/9) încă
  nemapate; c1033 neidentificat (33–301); c1049 [?, ~viteză];
  c1019 = cadre brute Raise (0x2E, cmd, len, date, checksum): 0x14, 0x21 clima, 0x22/0x23
  radar, 0x24 uși, 0x26 unghi volan, 0x41 date bord, 0x7D frecvent. Combustibil/consum: încă negăsite.
- Platforma e FYT/SYU: `com.syu.ms` (MainServer) gestionează ACC (`U_ACC_ON`) și, conform
  XDA, la somnul adânc închide tot ce nu e în `skipkillapp.prop` (valori negative = nu se
  închide) / `unkillapp.txt` (în APK-ul com.syu.ms) / `protected_app.txt`.
- Călătorii: `CanLink` (legătură permanentă la modulul CANBUS: c110/c1031/c109/c105/c106/c139),
  `TripRecorder` (GPS `LocationManager` + CanLink; punct la 5 s în mers, 30 s cu motorul pe loc,
  nimic cu motorul oprit, plus unul la oprire), `PointQueue` (fișier JSONL în filesDir, max
  40.000), trimise de `Uploader` la `/api/vw-trip` în loturi de 300. Serverul (FaikkitBox,
  tabela `vw_trip_point`, pagina `/calatorii`) împarte în călătorii la pauze > 5 min.
  Serviciul are `foregroundServiceType="location"`; permisiunea se cere din aplicație.
- Calibrare CAN (Acasă, `STEPS` în MainActivity): doar necunoscutele — pedala de frână vs.
  frâna de mână, marșarierul, centura (cu motorul oprit și o treaptă băgată), apoi pornirea
  motorului, AC, ventilator, temperatură, ștergătoare și litrii din rezervor (introduși de
  utilizator; `CanProbe.findValue` caută codurile cu acea valoare, ×1/×10/×100). Sonda 10 min;
  la fiecare pas UI-ul arată live schimbările (`CanProbe.changesSince`, fără codurile din
  `KNOWN`), iar marcajul „CAN MARK n GATA: … | schimbări” le trimite la server. Cadrele brute
  Raise au cheie proprie pe comandă („m7 raw 0x21”, „m7 raw 0x41/2”).
- `VwStatus`: `/api/vw-status` (kilometraj, mentenanță, ultimul APK), reîmprospătat de
  `Uploader` după trimitere dacă e mai vechi de 30 min și din aplicație; salvat în Prefs.
- `Updater`: dacă `apk.versionCode` de pe server > `BuildConfig.VERSION_CODE`, butonul
  „Actualizează acum” descarcă `/api/vw-apk/download` și instalează prin `PackageInstaller`
  (confirmare Android prin `Updater$Result`; prima dată cere „surse necunoscute”). CI
  publică APK-ul la `POST /api/vw-apk` după fiecare build pe main.
- `Speaker` (TextToSpeech, ro-RO, ca ghidare de navigație; bip dacă nu există voce) +
  `Greeting`: salut vorbit după 3 min de mers efectiv (ora zilei, temperatura CAN,
  mentenanța scadentă), avertizare „ușă deschisă” la ≥ 5 km/h (uși c1–c5 din `CanLink`).
  Logica e în `TripRecorder.monitor` (deci merge doar cu călătoriile pornite); tot acolo
  GPS-ul se oprește după 1 min cu motorul oprit (rpm 0) și repornește la turație/mers.
- Pe server (FaikkitBox): `/calatorii` are și „Unde e mașina” (ultimul punct GPS) și
  Mentenanța (tabela `vw_reminder`).
- `Syu`: protocolul com.syu.ipc scris cu Parcel, folosit de `CanProbe` și `CanLink`.
- `CrashLog`: handler global care scrie „CADERE: …” (excepție + 6 cadre) în jurnal cu
  `commit`, trimis la server la pornirea următoare.
- Pornirea serviciului din `MainActivity` (`Prefs.markUiStart`, fereastră 5 s) nu mai contează
  ca trezire „pornire proces”. Pauza implicită e 5 s, plus `sleep_extra` (implicit 2 s,
  setabil în UI) când procesorul a dormit ≥60 s de la ultimul tick (`sleptAtTick`): după o
  hibernare de ~1 h se pierdea ~1 s din sunet la 5 s pauză.
- `StartActivity` („VW Welcome Start”, a doua iconiță, translucidă, fără UI): pentru
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
  `vw-welcome-apk` și un GitHub Release `build-<run_number>` marcat latest.
  Descărcare: https://github.com/Faicu/welcometovw/releases/latest/download/VWWelcome.apk
- Secretul `VW_LOG_TOKEN` e transmis la `gradle assembleDebug`; fără el build-ul merge,
  dar trimiterea jurnalului e dezactivată.
- Semnare: secretele repo `KEYSTORE_BASE64` și `KEYSTORE_PASSWORD` (deja adăugate; PKCS12,
  alias `vwwelcome`). `app/build.gradle` citește `SIGNING_KEYSTORE` / `SIGNING_PASSWORD`;
  `versionCode` = `GITHUB_RUN_NUMBER`, `versionName` = `1.1.<N>`. Keystore-ul nu e în repo.

## Stare (30.09.2026)

Rezumatul pentru utilizator (funcții, ✅/🧪, ce urmează) e în `README.md`; ține-l la zi.

- Pe navigație: `com.mapgoo.diruite` 1.1.22 (actualizarea din aplicație încă netestată).
  Unitate: Android 10 (API 29), `sprd ums512_1h10_Natv`, fără selector de fișiere.
- Comportament Teyes confirmat din jurnal: ACC OFF scurt (sub ~10 min) → procesorul NU
  doarme, nu vine SCREEN_OFF, doar autostart-ul prinde ACC ON; ACC OFF lung → somn la ~10
  min după ACC OFF (atunci vine SCREEN_OFF). Cu pachetul vechi, aplicația era oprită forțat
  la somn (fără START_STICKY după trezire); cu `com.mapgoo.diruite` supraviețuiește. Autostart-ul
  ratează uneori (o dată din câteva), iar detecția prin pauză acoperă cazurile cu somn.
- Confirmat de utilizator: sunetul se aude întreg cu 5 s pauză (+2 s după hibernare);
  iconița proprie apare corect.
  Excepție (01.10): după 17,7 h de somn s-a pierdut ~0,5 s din început (redare corectă în
  jurnal, deci ieșirea audio nu era gata); utilizatorul a pus în UI +3 s după hibernare.
  Dacă se mai pierde, varianta în cod: pauza în plus crescută cu durata somnului.
- Netestat încă în uz real: călătoriile în mers, vocea (nu știm dacă există TTS în
  română), avertizarea de ușă, GPS-ul oprit cu motorul oprit, actualizarea din aplicație.
- În lucru: nivelul combustibilului. „Car Info” afișează litrii; 1.1.22 extinde sonda la
  CANBUS 0–1999 și la modulele 1–17. Utilizatorul o pornește cu Car Info deschis și spune
  câți litri arată. Apoi: consum/cost pe călătorie (alimentări detectate automat sau
  jurnal manual + estimare calibrată din turație × timp).
- FaikkitBox: commit-urile VW locale (`187e120`, `5409073`, `fddba0d`, `5dc2304`) se
  împing de utilizator din pagina Tehnic; nu face push acolo.
- Idei neîncepute: alertă pe telefon la pornirea mașinii (web push FaikkitBox), ore de
  liniște, sunet după ora zilei, codurile CAN ambigue (frână de mână, marșarier, centură),
  adaptor OBD2 pentru consum instantaneu.
