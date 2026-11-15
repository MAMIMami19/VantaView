#!/usr/bin/env bash
# Phone smoke test (portrait, then on its side): taps through every page and Settings section,
# saving a screenshot and the on-screen text after each step, plus any crash in the log.
set -u
OUT=tv-out; mkdir -p $OUT
n=0
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1; }
shot() {
  n=$((n+1)); f=$(printf "%s/%02d-%s.png" $OUT $n "$1"); adb exec-out screencap -p > "$f"; echo "shot $f"
  dump; python3 - <<'PY'
import re
x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
t=[m.group(1) for m in re.finditer(r' text="([^"]+)"',x)]
print("   screen:", " | ".join(t[:45])[:1000])
PY
}
key() { for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; }
tap_text() {
  dump
  xy=$(python3 - "$1" <<'PY'
import re,sys
t=sys.argv[1].lower(); x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
# An exact match first, so "Agree" never taps "Disagree"; then text that starts with it; then anywhere.
nodes=[]
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0); txt=(re.search(r' text="([^"]+)"',n) or re.search(r'content-desc="([^"]+)"',n))
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
    if txt and b: nodes.append((txt.group(1).lower().strip(), list(map(int,b.groups()))))
for test in (lambda s: s==t, lambda s: s.startswith(t), lambda s: t in s):
    hit=next((a for s,a in nodes if test(s)), None)
    if hit: print((hit[0]+hit[2])//2,(hit[1]+hit[3])//2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped '$1' at $xy"; else echo "NOT FOUND: '$1'"; fi
  sleep 1.5
}
size() { adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*'; }
scroll() { s=$(size); w=${s%x*}; h=${s#*x}; adb shell input swipe $((w/2)) $((h*3/4)) $((w/2)) $((h/4)) 400; sleep 1; }
menu() { tap_text "Menu"; sleep 1; tap_text "$1"; sleep 4; }
crashes() { adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: |ANR in com.vantaview" > $OUT/crashes.txt || true; }

adb install -r VantaView-test.apk || exit 1
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb logcat -c
adb shell am start -W -n com.vantaview.app/.MainActivity
sleep 12; shot terms
tap_text "Agree"; sleep 3; shot profile-setup
tap_text "Profile name"; adb shell input text Tester; key BACK; sleep 1
tap_text "Continue"; sleep 4; shot after-profile
# A location prompt may show on first start.
tap_text "Only this time"; tap_text "While using"; tap_text "Don't allow"; sleep 2
tap_text "Add free channels"; sleep 25; shot countries
tap_text "United Kingdom"; sleep 1; tap_text "Save"; sleep 10; shot after-countries
key BACK; key BACK; sleep 2; shot home
scroll; shot home-scrolled
# Add Cinemeta so Movies and TV Series have rows.
menu "Sources"; shot sources; tap_text "Add-ons"; sleep 2; shot sources-addons; tap_text "Add Cinemeta"; sleep 8; shot sources-cinemeta
for page in "Search" "Live TV" "TV Guide" "Movies" "TV Series" "Library" "Sources"; do
  menu "$page"; shot "page-$page"; scroll; shot "page-$page-scrolled"
done
# Live TV: Radio tab, order, a channel playing in portrait (video above, list below), then controls.
menu "Live TV"; tap_text "Order"; sleep 1; shot live-order; key BACK
tap_text "Radio"; sleep 2; shot live-radio
tap_text "All"; sleep 2
dump; xy=$(python3 - <<'PY'
import re
x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node [^>]*text="Now: [^"]*"[^>]*>', x):
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',m.group(0)); a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
[ -n "$xy" ] && adb shell input tap $xy || tap_text "BBC"
sleep 12; shot player-portrait
s=$(size); w=${s%x*}; h=${s#*x}; adb shell input tap $((w/2)) $((h/6)); sleep 1; shot player-portrait-controls
adb shell settings put system user_rotation 1; sleep 4; shot player-landscape
adb shell input tap 400 300; sleep 1; shot player-landscape-controls
adb shell settings put system user_rotation 0; sleep 3; key BACK; key BACK; sleep 2
# Movies: tiles, a tile's page, a film's details.
menu "Movies"; shot movies; tap_text "Trending"; sleep 8; shot movies-trending
adb shell input tap 150 700; sleep 8; shot details; scroll; shot details-scrolled; key BACK; key BACK
# Settings: the list, then every section.
menu "Settings"; shot settings
for sec in "Playback" "Appearance" "Subtitles and audio" "Sources and storage" "Weather" "Backup and restore" "Parental control" "Updates" "Help and about"; do
  menu "Settings"; tap_text "$sec"; sleep 2; shot "settings-$sec"; scroll; shot "settings-$sec-2"; scroll; shot "settings-$sec-3"
done
# Appearance looks: Tiles (light) and Spotlight, then back to VantaView.
menu "Settings"; tap_text "Appearance"; sleep 1; tap_text "Tiles"; sleep 2; menu "Home"; shot home-tiles; menu "Live TV"; shot live-tiles
menu "Settings"; tap_text "Appearance"; sleep 1; tap_text "Spotlight"; sleep 2; menu "Movies"; shot movies-spotlight
menu "Settings"; tap_text "Appearance"; sleep 1; tap_text "VantaView"; sleep 2
# On its side: Home, Live TV, Movies, Settings.
adb shell settings put system user_rotation 1; sleep 4
for page in "Home" "Live TV" "TV Guide" "Movies" "Settings"; do menu "$page"; shot "landscape-$page"; done
adb shell settings put system user_rotation 0
crashes; echo "---- crashes ----"; cat $OUT/crashes.txt
