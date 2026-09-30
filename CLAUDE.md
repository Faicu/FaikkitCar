# VW Welcome – context pentru Claude

Utilizatorul scrie în română; răspunde în română.

## Ce e

Aplicație Android nativă în Java (fără AndroidX, minSdk 26, targetSdk 33, compileSdk 34,
AGP 8.5.2, pachet `ro.faicu.vwwelcome`) pentru navigația Teyes CC3 2K. Redă un MP3 ales de
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
  `journal`, mutate automat din `cfg` la prima rulare. `deviceInfo()` dă antetul
  jurnalului copiat (versiuni, model, optimizare baterie, notificări, setări).
- `Uploader`: trimite jurnalul la `https://status.faicu.ro/api/vw-log` (FaikkitBox din
  `/opt/faikkitbox`, pagina admin `/vw`, tabela `vw_log`; citibil direct din
  `/opt/faikkitbox/data/faikkitbox.db`). Coadă `outbox` în `journal` (max 500), loturi de
  100, `Authorization: Bearer` cu `BuildConfig.VW_LOG_TOKEN` (secret GitHub
  `VW_LOG_TOKEN` = `VW_LOG_TOKEN` din `/opt/faikkitbox/.env`). Declanșat la fiecare
  `Prefs.log`, la `onAvailable` al rețelei și la ~30 s din tick. În `Uploader` nu se
  folosește `Prefs.log` (ar reintra în coadă). Serviciul scrie la pornire o linie `Info:`.
- `MainActivity`: UI construit din cod (fără XML); pornește serviciul în `onCreate`.
  Adăugarea sunetelor: `ACTION_OPEN_DOCUMENT` → `ACTION_GET_CONTENT` → listă proprie din
  `MediaStore.Audio` (cere `READ_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO`). Pe Teyes-ul
  utilizatorului selectorul standard lipsea și aplicația cădea (1.1.10).
- `CrashLog`: handler global care scrie „CADERE: …” (excepție + 6 cadre) în jurnal cu
  `commit`, trimis la server la pornirea următoare.
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

- Instalat pe navigație: 1.1.12 (`build-12`). Unitate: Android 10 (API 29), `sprd
  ums512_1h10_Natv`, fără selector de fișiere (nici OPEN_DOCUMENT, nici GET_CONTENT).
- Testat pe hardware: ACC OFF ~20–30 s → procesorul NU hibernează (somn 0 s, fără
  SCREEN_OFF, serviciul rămâne viu); autostart-ul Teyes lansează „VW Welcome Start” la
  ACC ON → sunetul se redă. Cu pauza 2,5 s se tăiau primele ~2 s; utilizatorul a pus 5 s
  și se aude complet (implicitul din cod e încă 2,5 s).
- Rămâne de testat: ACC OFF 2–5 min și peste noapte (hibernare / boot complet), plus cât
  de des ratează autostart-ul.
- Plan convenit: utilizatorul instalează, configurează, face cicluri ACC OFF/ON (~30 s,
  2–5 min, peste noapte); jurnalul se citește din `vw_log` (FaikkitBox). În funcție de jurnal
  decidem dacă e nevoie de un watchdog (repornire cu `AlarmManager`).
  Atenție: dacă Teyes face force-stop la ACC OFF, alarmele se anulează și ele; atunci ar
  trebui căutat un broadcast ACC specific Teyes (logcat). Tot după test: opțiune de tip
  de sunet „navigație” (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, peste radio) vs. „media”.
- Idei amânate: ore de liniște, sunet în funcție de ora zilei, fade-in, salut TTS, prag
  diferit zi/noapte, iconiță proprie.
