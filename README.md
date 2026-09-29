# VW Welcome

Aplicație Android (Java, fără AndroidX) pentru navigația Teyes CC3 2K care redă un
MP3 ales de utilizator la fiecare trezire din hibernare (ACC ON).

## Cum detectează trezirea

- `WelcomeService` (foreground service) rulează un `Handler` la 5 s și compară
  `SystemClock.elapsedRealtime()` între tick-uri. Un gap mai mare decât pragul
  (implicit 60 s, minim 30 s) înseamnă că unitatea a fost în hibernare.
- La pornirea serviciului: dacă s-a schimbat contorul de boot-uri Android
  (`Settings.Global.BOOT_COUNT`), e un boot nou, deci trezire, independent de ceas.
  Altfel, dacă ultima oră activă salvată în `SharedPreferences` e mai veche decât
  pragul, se consideră trezire (proces repornit).
- La aprinderea ecranului (`SCREEN_ON`) sau la deblocare (`USER_PRESENT`) verificarea
  se face imediat, nu la următorul tick.
- Serviciul pornește și la deschiderea aplicației, inclusiv din autostart-ul Teyes prin
  activitatea invizibilă `StartActivity` („VW Welcome Start”).
- `BootReceiver` pornește serviciul la `BOOT_COMPLETED`, `QUICKBOOT_POWERON` și
  `MY_PACKAGE_REPLACED`.
- Redarea: `MediaPlayer`, audio focus `AUDIOFOCUS_GAIN_TRANSIENT`, după o pauză
  configurabilă (implicit 2,5 s). Se pot alege mai multe sunete, redate la rând sau
  aleatoriu, și un volum propriu (0 = volumul curent al sistemului), refăcut după redare.
- Ultimele 20 de treziri (motiv și sunet redat) apar în istoricul din aplicație.
- Jurnalul de diagnostic (ultimele 100 de evenimente: pornirea serviciului,
  BootReceiver, pauze între tick-uri peste 15 s, ecran stins/aprins, redare, schimbări
  de focus audio) se poate copia în clipboard din aplicație, cu un antet cu versiunile
  aplicației și ale Android-ului și starea permisiunilor.

## Configurare pe unitate

1. Adaugă unul sau mai multe sunete (se copiază în memoria internă a aplicației)
   și alege ordinea: la rând sau aleatorie.
2. Setează pragul și pauza înainte de redare, apoi salvează; alege volumul.
3. Pornește serviciul.
4. Dezactivează optimizarea bateriei (și, dacă există, adaugă aplicația în lista
   de aplicații permise la pornire / „whitelist” din setările Teyes).
5. Adaugă „VW Welcome Start” în lista de autostart Teyes. E o intrare invizibilă care
   doar pornește serviciul: CC3 închide aplicațiile la intrarea în somn, iar după
   trezire nu vine `BOOT_COMPLETED`.
6. Test sunet.

## Build

GitHub Actions (`.github/workflows/build.yml`) construiește APK-ul de debug cu
Gradle 8.7 / JDK 17 și îl publică drept artifact `vw-welcome-apk` și într-un
GitHub Release (`build-N`, marcat „latest”).

Descărcare directă a ultimului APK:
https://github.com/Faicu/welcometovw/releases/latest/download/VWWelcome.apk

### Semnătura

Ca un APK nou să se instaleze peste cel vechi, fiecare build trebuie semnat cu
aceeași cheie. Workflow-ul o citește din două secrete ale repo-ului
(Settings → Secrets and variables → Actions):

- `KEYSTORE_BASE64` – fișierul keystore (PKCS12, alias `vwwelcome`), codat base64;
- `KEYSTORE_PASSWORD` – parola lui (aceeași pentru keystore și cheie).

Fără ele, build-ul merge, dar e semnat cu o cheie de debug temporară. Păstrează
o copie a keystore-ului: dacă îl pierzi, următoarea actualizare cere dezinstalare.

Local: `gradle assembleDebug` (necesită Android SDK cu platforma 34).
