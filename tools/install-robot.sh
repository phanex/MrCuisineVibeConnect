#!/usr/bin/env bash
# Installs Toque on a rooted Monsieur Cuisine (Connect or Smart, ADB enabled). macOS port of install-robot.ps1.
# Usage: tools/install-robot.sh [--apk path.apk] [--ip 192.168.1.x]
# adb: $ADB if set, else the Android SDK's, else the one on the PATH.
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd -P)
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
IP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK=${2:?--apk needs a file}; shift 2 ;;
    --ip) IP=${2:?--ip needs an address}; shift 2 ;;
    *) echo "Usage: tools/install-robot.sh [--apk path.apk] [--ip 192.168.1.x]"; exit 1 ;;
  esac
done
IP=${IP%:5555}
ADB=${ADB:-}
if [ -z "$ADB" ]; then
  for c in "${ANDROID_HOME:-}/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb"; do
    [ -x "$c" ] && { ADB=$c; break; }
  done
fi
ADB=${ADB:-adb}
adb_() { if [ -n "$IP" ]; then "$ADB" -s "$IP:5555" "$@"; else "$ADB" "$@"; fi; }

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK (./gradlew assembleDebug, or download it from GitHub Releases)"
  exit 1
fi
[ -n "$IP" ] && "$ADB" connect "$IP:5555"
"$ADB" devices

root=$(adb_ shell "su -c 'id -u'" 2> /dev/null | tr -d '\r[:space:]')
if [ "$root" != 0 ]; then echo "No root access: install Magisk first (see docs/INSTALLATION.md)."; exit 1; fi

echo "Installing Toque..."
out=$(adb_ install -r "$APK" 2>&1 | tr -d '\r')
echo "$out"
if ! printf '%s\n' "$out" | grep -qx '[[:space:]]*Success[[:space:]]*'; then
  case "$out" in
    *INSTALL_FAILED_VERSION_DOWNGRADE*) echo "The robot already runs a newer Toque than this file: nothing was changed." ;;
    *) echo "Toque was not installed: nothing else was changed on the robot." ;;
  esac
  exit 1
fi
# The scripts sent to the robot (Unix line ends: the robot's shell cannot run CRLF).
for f in restore.sh setup-root.sh toque-su.sh; do
  [ -f "$REPO/tools/robot/$f" ] || { echo "File missing: tools/robot/$f"; exit 1; }
  if LC_ALL=C grep -q "$(printf '\r')" "$REPO/tools/robot/$f"; then echo "tools/robot/$f has Windows line ends (CRLF): take it from the repository again."; exit 1; fi
done
adb_ push "$REPO/tools/robot/restore.sh" /data/local/tmp/toque-restore.sh > /dev/null
adb_ push "$REPO/tools/robot/setup-root.sh" /data/local/tmp/toque-setup-root.sh > /dev/null
# setup-root.sh installs this boot script (Toque allowed in Magisk at every start, opened first).
adb_ push "$REPO/tools/robot/toque-su.sh" /data/local/tmp/toque-su.sh > /dev/null
out=$(adb_ shell "su -c 'sh /data/local/tmp/toque-setup-root.sh'" 2>&1 | tr -d '\r')
echo "$out"
case "$out" in
  *"Boot script installed"*) ;;
  *) echo "The root setup did not finish (see the lines above): Toque is installed, but run this again before using it."; exit 1 ;;
esac
adb_ shell am start -n app.toque/.ui.MainActivity > /dev/null
echo ""
echo "Toque is running. On the robot:"
echo "  1. Settings > Advanced > Machine > Real machine > Apply (first test: EMPTY bowl)."
echo "  2. Toque now opens by itself when the robot starts (the robot has no Home button)."
echo "  3. Settings > Recipes > Lidl > Sync."
echo "To undo everything: tools/restore-robot.sh"
