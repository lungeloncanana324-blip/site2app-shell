#!/usr/bin/env python3
"""
Installs a built shell APK on the running emulator and walks a scripted tour,
photographing as it goes. The tour is a JSON list of steps:

  {"launch": true}                       start the app without waiting, so the launch screen can be photographed
  {"stop": true}                         force-stop the app
  {"wait": 5}                            pause, in seconds
  {"shot": "home"}                       screenshot, saved as <out>/<nn>-home.png
  {"burst": "launch", "count": 8}        several screenshots in a row
  {"tab": "Study"}                       tap the tab bar item with this label (native views only)
  {"tap": "YouVersion", "scrolls": 8}    tap the first view whose text contains this, scrolling down to find it
  {"back": true}                         press back
  {"offline": true} / {"online": true}   airplane mode on or off
  {"pull": true}                         pull down from the top of the page, to refresh it
  {"foreground": "after-link"}           note which app is in front, in tour.log

Usage: scripts/tour.py <apk> '<tour json>' <out dir>
Exits non-zero if a step cannot be done or the app crashes.
"""

import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

apk, tour, out = sys.argv[1], json.loads(sys.argv[2]), Path(sys.argv[3])
out.mkdir(parents=True, exist_ok=True)
config = json.loads(zipfile.ZipFile(apk).read("assets/app-config.json"))
package = config["app"]["androidPackage"]
activity = f"{package}/app.site2app.shell.MainActivity"
lines: list[str] = []
counter = 0


def log(message: str) -> None:
    print(message, flush=True)
    lines.append(message)


def adb(*args: str, binary: bool = False, check: bool = True):
    result = subprocess.run(["adb", *args], capture_output=True)
    if check and result.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace')}")
    return result.stdout if binary else result.stdout.decode(errors="replace")


def screen() -> tuple[int, int]:
    width, height = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size")).groups()
    return int(width), int(height)


WIDTH, HEIGHT = screen()


def shot(name: str) -> None:
    global counter
    counter += 1
    (out / f"{counter:02d}-{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    log(f"photographed {counter:02d}-{name}")


def dump() -> ET.Element:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    return ET.fromstring(xml[xml.index("<"):])


def nodes(node: ET.Element, inside_web: bool = False):
    inside_web = inside_web or node.get("class") == "android.webkit.WebView"
    yield node, inside_web
    for child in node:
        yield from nodes(child, inside_web)


def centre(node: ET.Element) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(root: ET.Element, text: str, native_only: bool, exact: bool, top: float = 0.0, bottom: float = 1.0):
    """The centre of the first matching view between top and bottom (fractions of the screen height)."""
    for node, inside_web in nodes(root):
        # The WebView itself is labelled with the page title; only its contents count.
        if (native_only and inside_web) or node.get("class") == "android.webkit.WebView":
            continue
        label = node.get("text") or node.get("content-desc") or ""
        if (label == text) if exact else (text.lower() in label.lower()):
            x, y = centre(node)
            if 0 < x < WIDTH and HEIGHT * top < y < HEIGHT * bottom:
                return x, y
    return None


def tap(point: tuple[int, int]) -> None:
    adb("shell", "input", "tap", str(point[0]), str(point[1]))


def scroll_down() -> None:
    x = str(WIDTH // 2)
    adb("shell", "input", "swipe", x, str(int(HEIGHT * 0.7)), x, str(int(HEIGHT * 0.35)), "400")
    time.sleep(1.2)


for step in tour:
    if "launch" in step:
        adb("shell", "am", "start", "-n", activity)
        log("launched")
    elif "stop" in step:
        adb("shell", "am", "force-stop", package)
        log("stopped")
    elif "wait" in step:
        time.sleep(float(step["wait"]))
    elif "shot" in step:
        shot(step["shot"])
    elif "burst" in step:
        for index in range(int(step.get("count", 6))):
            shot(f"{step['burst']}-{index + 1}")
            time.sleep(float(step.get("every", 0.3)))
    elif "tab" in step:
        point = find(dump(), step["tab"], native_only=True, exact=True)
        if not point:
            raise SystemExit(f"No tab says {step['tab']!r}")
        tap(point)
        log(f"tapped tab {step['tab']}")
    elif "tap" in step:
        for attempt in range(int(step.get("scrolls", 0)) + 1):
            # Page text only where it can be tapped: below the status bar, above the tab bar.
            point = find(dump(), step["tap"], native_only=False, exact=False, top=0.06, bottom=0.86)
            if point:
                tap(point)
                log(f"tapped {step['tap']!r} after {attempt} scrolls")
                break
            scroll_down()
        else:
            raise SystemExit(f"Nothing on screen says {step['tap']!r}")
    elif "back" in step:
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        log("pressed back")
    elif "offline" in step:
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
        log("airplane mode on")
    elif "online" in step:
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        log("airplane mode off")
    elif "pull" in step:
        x = str(WIDTH // 2)
        adb("shell", "input", "swipe", x, str(int(HEIGHT * 0.22)), x, str(int(HEIGHT * 0.62)), "700")
        log("pulled to refresh")
    elif "foreground" in step:
        activities = adb("shell", "dumpsys", "activity", "activities", check=False)
        front = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", activities)
        log(f"in front at {step['foreground']}: {front.group(1) if front else 'unknown'}")
    else:
        raise SystemExit(f"Unknown step {step}")

crashes = adb("logcat", "-d", "-b", "crash", check=False)
(out / "crash.log").write_text(crashes)
(out / "logcat.txt").write_text(adb("logcat", "-d", check=False))
(out / "tour.log").write_text("\n".join(lines) + "\n")
if package in crashes:
    log("The app crashed:")
    print(crashes)
    sys.exit(1)
log("No crash.")
