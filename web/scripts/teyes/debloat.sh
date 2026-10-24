# Rulat de pe laptop: adb shell < debloat.sh   (reversibil: pm enable <pachet>)
for p in \
  ru.teyes.update com.abupdate.fota_demo_iot \
  com.lsec.gesture \
  android.rockchip.update.service com.android.launcher3 \
  com.txznet.smartadapter com.yf.teyesspotify \
  com.android.se com.android.smspush com.android.modemnotifier com.spreadtrum.vce \
  com.syu.rightcamera \
  com.google.android.apps.wellbeing com.google.android.syncadapters.calendar \
  ; do echo "$p: $(pm disable-user --user 0 $p 2>&1)"; done
pm uninstall --user 0 com.google.android.projection.gearhead
# butoanele plutitoare: separat, apoi verificat bara de sistem
# pm disable-user --user 0 com.fyt.hidebutton; pm disable-user --user 0 com.fyt.screenbutton
