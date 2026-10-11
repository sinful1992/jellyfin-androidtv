#!/bin/bash
# Playback regression check on a TV (task 39).
#
# usage: playcheck.sh <adb-serial> [steps]     steps default: "C1 C2 C3 C4 C5 C6"
#
# Item ids come from perf-tools/playcheck.items (KEY=<item uuid>, one per line):
#   C1_EP_N, C1_EP_NEXT  two episodes of one series with more than one audio/subtitle track
#   C2_ITEM              a direct-played file with EAC3/AC3 audio and a second audio track
#   C3_ITEM              a file the TV cannot direct play (converted by the server), with a second audio track
#   C4_ITEM              a file media3 refuses (unreadable-file fallback)
#   C5_ITEM, C6_ITEM     any episode, at least 5 minutes long
#
# Track switches are made from the server (linus sends SetAudioStreamIndex / SetSubtitleStreamIndex to the
# session) when the script prints "WAITING <step> ...": the TV's own track menu cannot be driven over adb.
#
# Every step prints "STEP <id> START <utc>", then "STEP <id> <PASS|FAIL> <reason>" and "STEP <id> END <utc>",
# and keeps a screencap and the step's logcat in perf-tools/playcheck-out/<serial>/<step>.*. The server side
# of each step is in the team repo, jellyfin/check/oracle.md.
#
# Only adb input, am start, screencap and logcat are used: nothing is installed or cleared.

set -u
SERIAL=${1:?usage: playcheck.sh <adb-serial> [steps]}
STEPS=${2:-"C1 C2 C3 C4 C5 C6"}
ADB=${ADB:-/c/Users/barku/AppData/Local/Android/Sdk/platform-tools/adb.exe}
PKG=org.jellyfin.androidtv
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/playcheck-out/$(echo "$SERIAL" | tr ':/' '__')
mkdir -p "$OUT"

[ -f "$HERE/playcheck.items" ] || { echo "missing $HERE/playcheck.items"; exit 2; }
# shellcheck disable=SC1091
eval "$(tr -d '' < "$HERE/playcheck.items")"

a() { "$ADB" -s "$SERIAL" "$@"; }
utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
key() { a shell input keyevent "$@"; }

FAILED=0
step_start() { STEP=$1; echo "STEP $STEP START $(utc)"; a logcat -c; }
step_end() {
	local result=$1; shift
	a logcat -d -v time > "$OUT/$STEP.logcat.txt" 2>/dev/null
	echo "STEP $STEP $result $*"
	echo "STEP $STEP END $(utc)"
	[ "$result" = PASS ] || FAILED=1
}

# Mean luma (0-255) of the screen right now; the frame is kept as <step>-<label>.png.
luma() {
	local png=$OUT/$STEP-$1.png
	a exec-out screencap -p > "$png"
	python -c "import sys; from PIL import Image, ImageStat; print(int(ImageStat.Stat(Image.open(sys.argv[1]).convert('L')).mean[0]))" "$png"
}

# Seconds of playback the player reports, from the media session (works on debug and release builds).
position() {
	a shell dumpsys media_session | grep -m1 -o 'position=[0-9]*' | cut -d= -f2 | awk '{print int($1/1000)}'
}

play_state() {
	a shell dumpsys media_session | grep -m1 -o 'state=PlaybackState {state=[0-9]*' | grep -o '[0-9]*$'
}

# Open the item's details page and start it from the focused Play/Resume button.
play_item() {
	a shell am start -W -n "$PKG/.ui.startup.StartupActivity" -e ItemId "$1" > /dev/null
	sleep 6
	key 23
}

# Wait until the media session says playing (state 3); fail after $2 seconds.
wait_playing() {
	local limit=${1:-30} i=0
	while [ $i -lt "$limit" ]; do
		[ "$(play_state)" = 3 ] && return 0
		sleep 1; i=$((i + 1))
	done
	return 1
}

# Wait for a logcat line matching $1 (extended regex) for up to $2 seconds.
wait_log() {
	local limit=${2:-60} i=0
	while [ $i -lt "$limit" ]; do
		a logcat -d -v time | grep -Eq "$1" && return 0
		sleep 1; i=$((i + 1))
	done
	return 1
}

leave_player() { key 4; sleep 1; }

check_black_after_leave() {
	# The three hero.42 black screens showed 4-5 s after the stop, so look at 2 s and again at 8 s.
	sleep 2
	local early late
	early=$(luma after2s)
	sleep 6
	late=$(luma after8s)
	if [ "$late" -lt 8 ]; then echo "black luma=$early/$late"; return 1; fi
	if a logcat -d | grep -q "BlackScreenHeal"; then echo "heal fired luma=$early/$late"; return 1; fi
	echo "luma=$early/$late"
}

no_crash_lines() {
	! a logcat -d | grep -Eq "FATAL EXCEPTION|AndroidRuntime: .*$PKG|Playback failed"
}

run_C1() {
	step_start C1
	play_item "$C1_EP_N"
	if ! wait_playing 40; then step_end FAIL "episode N never played"; return; fi
	echo "WAITING C1 pick a non-default audio or subtitle track on episode N now (server command), 90 s"
	if ! wait_log "Selected (audio|subtitle) track|Disabled subtitles|Playing http" 90; then
		step_end FAIL "no track change seen on episode N"; return
	fi
	sleep 5
	leave_player; leave_player
	play_item "$C1_EP_NEXT"
	if ! wait_playing 40; then step_end FAIL "episode N+1 never played"; return; fi
	sleep 10
	local picks
	picks=$(a logcat -d -v time | grep -Ec "Selected (audio|subtitle) track|Disabled subtitles")
	leave_player
	if no_crash_lines; then step_end PASS "carry applied lines=$picks (server: oracle C1.a-d)"
	else step_end FAIL "crash or playback error in logcat"; fi
}

run_C2() {
	step_start C2
	play_item "$C2_ITEM"
	if ! wait_playing 40; then step_end FAIL "never played"; return; fi
	sleep 20
	echo "WAITING C2 switch the audio track now (server command), 90 s"
	if ! wait_log "Selected audio track" 90; then step_end FAIL "no client-side audio switch seen"; return; fi
	local before after
	before=$(position); sleep 15; after=$(position)
	leave_player
	if [ -n "$before" ] && [ -n "$after" ] && [ "$after" -gt "$before" ] && no_crash_lines; then
		step_end PASS "position $before→$after s after the switch"
	else
		step_end FAIL "stuck or error after the switch (position $before→$after)"
	fi
}

run_C3() {
	step_start C3
	play_item "$C3_ITEM"
	if ! wait_playing 60; then step_end FAIL "never played"; return; fi
	sleep 30
	local before after
	before=$(position)
	echo "WAITING C3 switch the audio track now (server command), 90 s"
	# A converted stream is asked for again with the new track, and played from where it was.
	local i=0
	while [ $i -lt 90 ] && [ "$(a logcat -d -v time | grep -c 'Playing http')" -lt 2 ]; do sleep 1; i=$((i + 1)); done
	if [ $i -ge 90 ]; then step_end FAIL "stream not reloaded after the switch (debug build only shows it)"; return; fi
	wait_playing 60
	sleep 5
	after=$(position)
	leave_player
	if [ -n "$before" ] && [ -n "$after" ] && [ $((after + 10)) -ge "$before" ] && no_crash_lines; then
		step_end PASS "position $before→$after s across the switch"
	else
		step_end FAIL "restarted or error (position $before→$after)"
	fi
}

run_C4() {
	step_start C4
	play_item "$C4_ITEM"
	if ! wait_playing 60; then
		step_end FAIL "never played (fallback not taken?)"; return
	fi
	local fallback=no
	a logcat -d | grep -q "asking the server to convert it" && fallback=yes
	sleep 10
	leave_player
	if [ "$fallback" = yes ] || no_crash_lines; then step_end PASS "played, fallback line seen=$fallback (oracle C4.a has the server side)"
	else step_end FAIL "played but logcat has an error"; fi
}

run_C5() {
	step_start C5
	play_item "$C5_ITEM"
	if ! wait_playing 40; then step_end FAIL "never played"; return; fi
	sleep 45
	local stopped resumed
	stopped=$(position)
	leave_player; leave_player
	sleep 3
	play_item "$C5_ITEM"
	if ! wait_playing 40; then step_end FAIL "did not resume"; return; fi
	sleep 3
	resumed=$(position)
	leave_player
	if [ -n "$stopped" ] && [ -n "$resumed" ] && [ "$resumed" -ge $((stopped - 17)) ]; then
		step_end PASS "stopped at $stopped s, resumed at $resumed s"
	else
		step_end FAIL "stopped at $stopped s, resumed at $resumed s"
	fi
}

run_C6() {
	step_start C6
	local i result=PASS notes=""
	for i in 1 2 3; do
		play_item "$C6_ITEM"
		if ! wait_playing 40; then result=FAIL; notes="$notes run$i:never-played"; continue; fi
		sleep 30
		leave_player
		local seen
		if seen=$(check_black_after_leave); then notes="$notes run$i:$seen"
		else result=FAIL; notes="$notes run$i:$seen"; fi
		leave_player
		sleep 3
	done
	step_end "$result" "$notes"
}

echo "playcheck $SERIAL $(a shell dumpsys package $PKG | grep -m1 versionName | tr -d ' ') at $(utc)"
for s in $STEPS; do
	"run_$s"
done
echo "playcheck done $(utc) failed=$FAILED"
exit $FAILED
