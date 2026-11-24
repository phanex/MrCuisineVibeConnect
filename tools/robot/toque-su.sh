#!/system/bin/sh
# Written by Toque (tools/robot/setup-root.sh): allows Toque in Magisk at every start, then opens
# Toque as soon as Android's home screen starts, without waiting for the boot-completed broadcast
# (it reaches Toque after about twenty other apps, some seconds after the stock app is on screen).
U=$(stat -c %u /data/data/app.toque 2>/dev/null)
[ -n "$U" ] || exit 0
magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($U,2,0,0,0)" >/dev/null 2>&1
P=/data/data/app.toque/shared_prefs/toque.xml
# Toque's setting "open at start" switched off: leave the stock app alone.
grep -q 'name="bootLaunch" value="false"' $P 2>/dev/null && exit 0
V=$(sed -n 's/.*name="vendorPackage">\([^<]*\)<.*/\1/p' $P 2>/dev/null)
# Without a stock app set by hand: the one of the robot chosen in Toque (Smart or Connect).
R=$(sed -n 's/.*name="robotId">\([^<]*\)<.*/\1/p' $P 2>/dev/null)
[ -n "$V" ] || { [ "$R" = mc-smart ] && V=com.tecpal.device.mc30; }
[ -n "$V" ] || V=de.silpion.mc2
# "[d]e.silpion.mc2": the pattern does not match the grep that carries it.
PAT="[$(echo "$V" | cut -c1)]$(echo "$V" | cut -c2-)"
(
  n=0
  while [ $n -lt 240 ]; do
    # Already booted: Toque's own receiver opens it.
    [ "$(getprop sys.boot_completed)" = 1 ] && exit 0
    ps | grep -q "$PAT" && break
    n=$((n + 1))
    sleep 0.5 2>/dev/null || sleep 1
  done
  [ $n -lt 240 ] && /system/bin/am start -n app.toque/.ui.MainActivity >/dev/null 2>&1
) &
