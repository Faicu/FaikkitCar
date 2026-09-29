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
  `SCREEN_ON` face verificarea imediat; `SCREEN_OFF` doar se notează în jurnal.
- `BootReceiver`: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` pornesc serviciul.
- `Player`: `MediaPlayer` ținut static, `AUDIOFOCUS_GAIN_TRANSIENT`, pauză configurabilă
  (0–15 s, implicit 2,5 s), volum opțional în % din `STREAM_MUSIC` (0 = volumul sistemului),
  refăcut după redare; protecție anti-dublare de 20 s (butonul Test o ocolește).
- `Prefs`: sunete multiple în `filesDir/sounds` (`<millis>_<nume>`), la rând sau aleatoriu;
  istoric treziri (20); jurnal de diagnostic (100 de evenimente).
- `MainActivity`: UI construit din cod (fără XML).

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
- Plan convenit: utilizatorul instalează, configurează, face cicluri ACC OFF/ON (~30 s,
  2–5 min, peste noapte) și trimite jurnalul copiat din aplicație. În funcție de jurnal
  decidem dacă e nevoie de un watchdog (repornire cu `AlarmManager`).
- Idei amânate: ore de liniște, sunet în funcție de ora zilei, fade-in, salut TTS, prag
  diferit zi/noapte, iconiță proprie.
