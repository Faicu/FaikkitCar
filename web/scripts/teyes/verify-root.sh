# Verificare după actualizare / root, doar citiri (nu schimbă nimic).
# Rulat: cd web && node --env-file=/opt/faikkitcar/.env scripts/car-cmd.ts --timeout 60 "$(cat scripts/teyes/verify-root.sh)"
#   sau: adb shell < verify-root.sh
# Fără root, liniile cu "R:" arată "(fara root)"; cu root le citește prin su.
PKG=com.mapgoo.diruite
R() { if [ "$(id -u)" = 0 ]; then sh -c "$1" 2>&1; elif command -v su >/dev/null 2>&1; then su -c "$1" 2>&1; else echo "(fara root)"; fi; }

echo "== root"
id
command -v su || echo "su: lipsa"
R 'id'
echo "== firmware"
for p in ro.fota.version ro.build.date ro.build.fingerprint ro.boot.verifiedbootstate ro.boot.flash.locked ro.debuggable ro.secure; do echo "$p=$(getprop $p)"; done
echo "== aplicatia"
pm list packages --show-versioncode 2>/dev/null | grep "$PKG" || echo "$PKG: LIPSA"
pm list packages -d 2>/dev/null | sort | tr '\n' ' '; echo
echo "== procesul nostru"
PID=$(pidof "$PKG" 2>/dev/null)
echo "pid=$PID"
[ -n "$PID" ] && echo "oom_score_adj=$(cat /proc/$PID/oom_score_adj 2>/dev/null)"
echo "== listele Teyes (aplicatia trebuie sa fie pe ele)"
R 'grep -n "mapgoo" /oem/app/pwctl_config.xml'
R 'find / -name unkill_app.txt 2>/dev/null | while read f; do echo "$f:"; grep -n mapgoo "$f"; done'
echo "== setari"
R 'settings get global preferred_network_mode; settings get global wifi_on; settings get global adb_enabled'
R 'dumpsys package com.mapgoo.diruite | grep -E "granted=true|installerPackageName" | head -20'
R 'cmd notification allowed_listeners'
echo "== memorie"
grep -E "MemTotal|MemAvailable|SwapTotal|SwapFree" /proc/meminfo
cat /proc/uptime
