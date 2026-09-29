# VW Welcome

Aplicație Android (Java, fără AndroidX) pentru navigația Teyes CC3 2K care redă un
MP3 ales de utilizator la fiecare trezire din hibernare (ACC ON).

## Cum detectează trezirea

- `WelcomeService` (foreground service) rulează un `Handler` la 5 s și compară
  `SystemClock.elapsedRealtime()` între tick-uri. Un gap mai mare decât pragul
  (implicit 60 s, minim 30 s) înseamnă că unitatea a fost în hibernare.
- Ultima oră activă e salvată în `SharedPreferences`; la pornirea serviciului, dacă a
  trecut mai mult decât pragul, se consideră trezire (proces repornit sau boot).
- `BootReceiver` pornește serviciul la `BOOT_COMPLETED` și `MY_PACKAGE_REPLACED`.
- Redarea: `MediaPlayer`, audio focus `AUDIOFOCUS_GAIN_TRANSIENT`, după 2,5 s.

## Configurare pe unitate

1. Alege sunetul (se copiază în memoria internă a aplicației).
2. Setează pragul și salvează.
3. Pornește serviciul.
4. Dezactivează optimizarea bateriei (și, dacă există, adaugă aplicația în lista
   de aplicații permise la pornire / „whitelist” din setările Teyes).
5. Test sunet.

## Build

GitHub Actions (`.github/workflows/build.yml`) construiește APK-ul de debug cu
Gradle 8.7 / JDK 17 și îl publică drept artifact `vw-welcome-apk`.

Local: `gradle assembleDebug` (necesită Android SDK cu platforma 34).
