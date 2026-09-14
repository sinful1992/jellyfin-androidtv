#!/bin/sh
# usage: measure.sh <label>
# NOTE: the input sequence below is GRID-shaped (6 RIGHT, 3 DOWN, 6 LEFT).
# For home, use vertical nav (5 DOWN, 5 UP) instead - do not compare across shapes.
# Always check the frame count phases.awk prints: <60 frames means the keys missed.
A=/c/Users/barku/AppData/Local/Android/Sdk/platform-tools/adb.exe
CC=adb-11141HFDD1TSC1-NhG0tu._adb-tls-connect._tcp
# override with e.g. PKG=org.jellyfin.androidtv.minified sh measure.sh <label>
PKG=${PKG:-org.jellyfin.androidtv}
S=$(cd "$(dirname "$0")" && pwd)
LABEL=$1
$A -s $CC shell "dumpsys gfxinfo $PKG reset" >/dev/null 2>&1
$A -s $CC shell "for i in 1 2 3 4 5 6; do input keyevent 22; sleep 0.7; done; for i in 1 2 3; do input keyevent 20; sleep 0.7; done; for i in 1 2 3 4 5 6; do input keyevent 21; sleep 0.7; done"
$A -s $CC shell "dumpsys gfxinfo $PKG framestats" 2>/dev/null > $S/fs_$LABEL.txt
echo "--- $LABEL ---"
grep -E "Janky frames|50th percentile|90th percentile|gpu percentile" $S/fs_$LABEL.txt | head -8
awk -f $S/phases.awk $S/fs_$LABEL.txt
