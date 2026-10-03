#!/bin/sh
# usage: DEV=<serial> ct.sh <label>  - relaunch test app (base arm), home down-scroll, pass 0 kept
A=/c/Users/barku/AppData/Local/Android/Sdk/platform-tools/adb.exe
P=org.jellyfin.androidtv.minified
S=$(cd "$(dirname "$0")" && pwd)
$A -s $DEV shell "setprop debug.jf.plain 0; setprop debug.jf.prefetch 0; setprop debug.jf.q85 1; am force-stop $P; monkey -p $P -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; sleep 14"
KEEP0=1 PKG=$P DEV=$DEV sh $S/scroll.sh ct_$1_homeV "19 19 19 19 19 19 19" "20 20 20 20 20" 3
