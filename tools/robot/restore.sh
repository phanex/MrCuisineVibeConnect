#!/system/bin/sh
# Gives control back to the stock Monsieur Cuisine app (run as root on the robot).
# Connect: de.silpion.mc2 (HardwareMachineService); Smart: com.tecpal.device.mc30 (SerialPortMC30Service).
BB=""
for b in /data/adb/magisk/busybox /sbin/.magisk/busybox/busybox /system/xbin/busybox /system/bin/busybox; do
  if [ -x "$b" ]; then BB="$b"; break; fi
done
am force-stop app.toque
for P in de.silpion.mc2 com.tecpal.device.mc30; do
  pm path "$P" >/dev/null 2>&1 || continue
  pm enable "$P" >/dev/null 2>&1
  for c in $(dumpsys package "$P" | $BB grep -oE '[A-Za-z0-9_./$]*(HardwareMachineService|SerialPortMC30Service)' | $BB sort -u); do
    case "$c" in
      */*) pm enable "$c" ;;
      *) pm enable "$P/$c" ;;
    esac
  done
done
pm disable app.toque >/dev/null 2>&1
echo "Stock app restored. Restart the robot."
