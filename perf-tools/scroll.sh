#!/bin/sh
# usage: scroll.sh <label> "<prep keycodes>" "<measure keycodes>" [passes]
# Generalised pass.sh: per pass, send the prep keys (return to a fixed start), settle 3 s,
# reset gfxinfo, send the measure keys 0.7 s apart, dump framestats to fs_<label>_<n>.txt.
# Pass 0 is a warm-up and is discarded. Keep measure bursts <= 10 keys (120-frame ring).
A=/c/Users/barku/AppData/Local/Android/Sdk/platform-tools/adb.exe
CC=${DEV:-adb-11141HFDD1TSC1-NhG0tu._adb-tls-connect._tcp}
PKG=${PKG:-org.jellyfin.androidtv}
S=$(cd "$(dirname "$0")" && pwd)
LABEL=$1; PREP=$2; MEAS=$3; N=${4:-3}
p=0
while [ $p -le $N ]; do
  $A -s $CC shell "for k in $PREP; do input keyevent \$k; sleep 0.4; done; sleep 3; dumpsys gfxinfo $PKG reset >/dev/null; for k in $MEAS; do input keyevent \$k; sleep 0.7; done; sleep 1.5"
  if [ $p -gt 0 ] || [ -n "$KEEP0" ]; then
    $A -s $CC shell "dumpsys gfxinfo $PKG framestats" 2>/dev/null > $S/fs_${LABEL}_$p.txt
  fi
  p=$((p+1))
done
$A -s $CC shell "dumpsys meminfo $PKG | grep -E 'TOTAL PSS|Graphics:'" | tr -s ' ' | sed "s/^/$LABEL mem:/"
