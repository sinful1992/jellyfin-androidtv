#!/usr/bin/env bash
# Task 176 driver: Home -> details (first card of the first row) -> Back, N times, on the Shield.
# Usage: perf-tools/task176-back.sh <hero label> [runs=5] [out=jellyfin/task176/drive-<label>.log]
# Writes one line per Back: <host epoch ms> <device uptime ms> back <n> <top fragment after Back>
set -u
ADB="${ADB:-/c/Users/barku/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
DEV="${DEV:-192.168.1.12:5555}"
LABEL="${1:?hero label}"; RUNS="${2:-5}"; OUT="${3:-drive-$LABEL.log}"
PKG=org.jellyfin.androidtv
a() { MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell "$@"; }
key() { a input keyevent "$@"; }
# Release builds strip fragment names: the details page is the one with fdButtonRow in its views.
top() { if a "dumpsys activity top" | grep -aq "app:id/fdButtonRow"; then echo details; else echo home; fi; }

# Start state: the app on Home with the focus on the fixed card (task 176: "Other Mommy", first card of
# Recently added in Movies), set by hand once. Each run opens it with CENTER and goes Back.
: > "$OUT"
key KEYCODE_WAKEUP
for n in $(seq 1 "$RUNS"); do
  key KEYCODE_DPAD_CENTER
  a "sleep 6"
  t=$(top)
  if [ "$t" != details ]; then echo "run $n: details not on top ($t), skipped" >&2; continue; fi
  host=$(date +%s%3N); up=$(a "cat /proc/uptime" | awk '{printf "%d", $1*1000}')
  key KEYCODE_BACK
  a "sleep 6"
  echo "$host $up back $n $(top)" | tee -a "$OUT"
done
