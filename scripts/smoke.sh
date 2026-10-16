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

# Without -W: am start -W waits for the app's first frame, which the launch
# screen holds back, so the picture would always come after it.
launch() { adb shell am start -n "$package/app.site2app.shell.MainActivity" > /dev/null; }

# Taps the native view whose text is exactly $1. Text inside the WebView is
# skipped: the page can have its own "Search", and the tab bar is the target.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml "$out/ui.xml" > /dev/null
  local point
  point=$(python3 - "$1" "$out/ui.xml" <<'EOF'
import re, sys
import xml.etree.ElementTree as ET
label, path = sys.argv[1], sys.argv[2]

def native(node, inside_web=False):
    inside_web = inside_web or node.get("class") == "android.webkit.WebView"
    if not inside_web and node.get("text") == label:
        yield node
    for child in node:
        yield from native(child, inside_web)

found = next(native(ET.parse(path).getroot()), None)
if found is None:
    sys.exit(f"no native view says {label!r}")
x1, y1, x2, y2 = map(int, re.findall(r"\d+", found.get("bounds")))
print((x1 + x2) // 2, (y1 + y2) // 2)
EOF
)
  # shellcheck disable=SC2086
  adb shell input tap $point
}

adb logcat -c
adb install -r "$apk"

launch
sleep 1
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
