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
- `MainActivity`: UI construit din cod (fără XML); pornește serviciul în `onCreate`.
- `StartActivity` („VW Welcome Start”, a doua iconiță, translucidă, fără UI): pentru
  lista de autostart Teyes; notează „Pornit din autostart”, pornește serviciul, `finish()`.

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
- Semnare: secretele repo `KEYSTORE_BASE64` și `KEYSTORE_PASSWORD` (deja adăugate; PKCS12,
  alias `vwwelcome`). `app/build.gradle` citește `SIGNING_KEYSTORE` / `SIGNING_PASSWORD`;
  `versionCode` = `GITHUB_RUN_NUMBER`, `versionName` = `1.1.<N>`. Keystore-ul nu e în repo.

## Stare (29.09.2026)

- Ultimul build verde: `build-6` (1.1.6). Nicio versiune nu a fost încă instalată pe
  navigație; nimic nu e testat pe hardware real.
- După build-6 s-a îmbunătățit diagnosticul (focus audio în jurnal, antet cu versiuni și
  permisiuni, `QUICKBOOT_POWERON`, `USER_PRESENT`, jurnal separat de setări). Testul pe
  navigație trebuie făcut cu un build care conține aceste schimbări, nu cu 1.1.6/1.1.7.
- Build-ul cu `StartActivity` trebuie pus în lista de autostart înainte de test; jurnalul
  arată cât de des vine „Pornit din autostart”.
- Plan convenit: utilizatorul instalează, configurează, face cicluri ACC OFF/ON (~30 s,
  2–5 min, peste noapte) și trimite jurnalul copiat din aplicație. În funcție de jurnal
  decidem dacă e nevoie de un watchdog (repornire cu `AlarmManager`).
  Atenție: dacă Teyes face force-stop la ACC OFF, alarmele se anulează și ele; atunci ar
  trebui căutat un broadcast ACC specific Teyes (logcat). Tot după test: opțiune de tip
  de sunet „navigație” (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, peste radio) vs. „media”.
- Idei amânate: ore de liniște, sunet în funcție de ora zilei, fade-in, salut TTS, prag
  diferit zi/noapte, iconiță proprie.
