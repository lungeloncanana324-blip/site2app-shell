#!/usr/bin/env bash
# Installs a built shell APK on a running emulator and photographs it: the
# launch screen, the first page, two tabs, back, the offline screen, and the
# recovery when the network returns. Fails if the app crashes.
#
# Usage: scripts/smoke.sh <apk> <package> <out dir>
set -euo pipefail

apk=$1
package=$2
out=$3
mkdir -p "$out"

shot() { adb exec-out screencap -p > "$out/$1.png"; echo "photographed $1"; }

launch() { adb shell am start -W -n "$package/app.site2app.shell.MainActivity" > /dev/null; }

# Taps the on-screen view whose text is exactly $1.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml "$out/ui.xml" > /dev/null
  local point
  point=$(python3 - "$1" "$out/ui.xml" <<'EOF'
import re, sys
import xml.etree.ElementTree as ET
label, path = sys.argv[1], sys.argv[2]
for node in ET.parse(path).iter("node"):
    if node.get("text") == label:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
else:
    sys.exit(f"nothing on screen says {label!r}")
EOF
)
  # shellcheck disable=SC2086
  adb shell input tap $point
}

adb logcat -c
adb install -r "$apk"

launch
shot 01-launch
sleep 15
shot 02-first-page

tap_text "Random"
sleep 10
shot 03-random-tab

tap_text "Search"
sleep 8
shot 04-search-tab

adb shell input keyevent KEYCODE_BACK
sleep 4
shot 05-after-back

# Offline: start the app with no network, then give it back.
adb shell cmd connectivity airplane-mode enable
sleep 5
adb shell am force-stop "$package"
launch
sleep 10
shot 06-offline

adb shell cmd connectivity airplane-mode disable
sleep 20
shot 07-back-online

adb logcat -d -b crash > "$out/crash.log" || true
adb logcat -d > "$out/logcat.txt" || true
if grep -q "$package" "$out/crash.log"; then
  echo "The app crashed:"
  cat "$out/crash.log"
  exit 1
fi
echo "No crash."
