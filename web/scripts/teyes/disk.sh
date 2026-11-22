# Spatiul de stocare al navigatiei (ca aplicatia, fara root): ce e plin si ce ocupam noi.
echo "== df"; df -h /data /sdcard /system /cache 2>&1
echo "== FaikkitCar"; du -sh /data/data/com.mapgoo.diruite/* /data/data/com.mapgoo.diruite 2>&1
echo "== /sdcard"; du -sh /sdcard/* 2>&1 | sort -rh | head -25
echo "== /sdcard/Android"; du -sh /sdcard/Android/data/* /sdcard/Android/obb/* 2>&1 | sort -rh | head -15
echo "== Download"; ls -laS /sdcard/Download 2>&1 | head -15
