#!/usr/bin/env bash
# Android TV smoke test: drives VantaView with remote keys (and taps only to type on set-up screens),
# saving a screenshot after each step, plus any crash in the log.
set -u
OUT=tv-out; mkdir -p $OUT
n=0
shot() { n=$((n+1)); f=$(printf "%s/%02d-%s.png" $OUT $n "$1"); adb exec-out screencap -p > "$f"; echo "shot $f"; }
key() { for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1; }
# Tap the first element whose text contains $1.
tap_text() {
  dump
  xy=$(python3 - "$1" <<'PY'
import re,sys
t=sys.argv[1].lower(); x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0); txt=(re.search(r' text="([^"]*)"',n) or re.search(r'content-desc="([^"]*)"',n))
    if txt and t in txt.group(1).lower():
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
        if b: a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped '$1' at $xy"; else echo "NOT FOUND: '$1'"; fi
  sleep 1.5
}
crashes() { adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: |ANR in com.vantaview" > $OUT/crashes.txt || true; }

adb install -r VantaView-test.apk || exit 1
adb logcat -c
adb shell am start -n com.vantaview.app/.MainActivity
sleep 3; shot splash
sleep 10; shot terms
key DPAD_CENTER; sleep 3; shot profile-setup
tap_text "Profile name"; adb shell input text Tester; key BACK; sleep 1; shot profile-typed
tap_text "Continue"; sleep 3; shot sources
tap_text "Add free channels"; sleep 25; shot countries
tap_text "Save"; sleep 8; shot after-countries
# Sidebar with the remote: Left opens it; go to each section.
key BACK; sleep 2; shot back-from-sources
for section in "Home" "Live TV" "TV Guide" "Search" "Library" "Settings"; do
  key DPAD_LEFT DPAD_LEFT; sleep 1
  tap_text "$section"; sleep 5; shot "section-$section"
done
# Settings with the remote: move down the list (right pane follows), then into a pane.
key DPAD_LEFT DPAD_LEFT; tap_text "Settings"; sleep 3
key DPAD_RIGHT DPAD_DOWN DPAD_DOWN; sleep 1; shot settings-remote-1
key DPAD_DOWN DPAD_DOWN DPAD_DOWN; sleep 1; shot settings-remote-2
key DPAD_RIGHT DPAD_DOWN; sleep 1; shot settings-pane-focus
# Live TV: play a channel with the remote, controls, then Right to the Audio/Subtitles/Size buttons.
key DPAD_LEFT DPAD_LEFT; tap_text "Live TV"; sleep 5
key DPAD_RIGHT DPAD_DOWN DPAD_DOWN DPAD_DOWN; sleep 1; shot live-focus
key DPAD_CENTER; sleep 10; shot player
key DPAD_CENTER; sleep 1; shot player-controls
key DPAD_RIGHT; sleep 1; shot player-buttons
key DPAD_CENTER; sleep 2; shot player-audio-picker
key BACK; sleep 1; key DPAD_UP; sleep 6; shot player-next-channel
key BACK BACK; sleep 2; shot after-player
# TV Guide with the remote.
key DPAD_LEFT DPAD_LEFT; tap_text "TV Guide"; sleep 6
key DPAD_RIGHT DPAD_DOWN DPAD_RIGHT DPAD_RIGHT; sleep 1; shot guide-remote
# Home: Back twice should leave the app.
key DPAD_LEFT DPAD_LEFT; tap_text "Home"; sleep 4; shot home
key BACK; sleep 1; shot back-once
crashes
adb logcat -d -t 2000 > $OUT/logcat.txt
echo "done"
