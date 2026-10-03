#!/bin/sh
# usage: DEV=<serial> install.sh <apk> [package]  - install in place, then AOT-compile.
# A sideload leaves the app at status=verify (JIT only) until the TV's idle dexopt runs;
# measured 2026-10-03 on the Bravia: home row-entry frame 125-164 ms uncompiled vs 54-66 compiled.
A=/c/Users/barku/AppData/Local/Android/Sdk/platform-tools/adb.exe
PKG=${2:-org.jellyfin.androidtv}
$A -s $DEV install -r "$1" || exit 1
$A -s $DEV shell "cmd package compile -m speed -f $PKG && dumpsys package dexopt | grep -A2 '\[$PKG\]'"
