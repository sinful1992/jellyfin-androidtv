#!/usr/bin/env bash
# Task 176: Play from Home's hero, wait, Back, then sample what the Shield shows for 12 s.
# Usage: perf-tools/task176-player-back.sh <out dir> [play seconds=25]
set -u
ADB="${ADB:-/c/Users/barku/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
DEV="${DEV:-192.168.1.12:5555}"
OUT="${1:?out dir}"; PLAY="${2:-25}"
PKG=org.jellyfin.androidtv
mkdir -p "$OUT"
a() { MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell "$@"; }

a logcat -c
a input keyevent KEYCODE_WAKEUP
a "sleep 2"
# On Home the hero's Play holds the focus at start (hero.64+).
a input keyevent KEYCODE_DPAD_CENTER
echo "$(date +%T.%3N) play pressed" | tee "$OUT/timeline.txt"
a "sleep $PLAY"
echo "$(date +%T.%3N) back" | tee -a "$OUT/timeline.txt"
# One device-side loop so the samples are not spaced by adb start-up: mode id, surfaces, fragment views.
a "input keyevent KEYCODE_BACK; for i in \$(seq 1 40); do t=\$(cat /proc/uptime); m=\$(dumpsys display | grep -m1 -o 'mActiveModeId=[0-9]*'); s=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -c SurfaceView); h=\$(dumpsys activity top | grep -c 'app:id/fdButtonRow\|app:id/home'); echo \"\$t \$m sv=\$s views=\$h\"; sleep 0.3; done" > "$OUT/samples.txt"
a logcat -d -v time > "$OUT/logcat.txt"
echo "$(date +%T.%3N) done" | tee -a "$OUT/timeline.txt"
