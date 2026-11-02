#!/usr/bin/env bash
# Helpers for driving the emulator (run from the repo root).
#   source .cache/m1/emu.sh
#   snap <label>          -> dump UI + screenshot into .cache/m1 and screenshots/m1
#   taptext <text>        -> tap the centre of the node whose text matches exactly
#   tapid <resource-id>   -> tap by resource-id
#   tapsel <regex>        -> tap the first node whose full attributes match a regex
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
mkdir -p .cache/m1 screenshots/m1

snap() {
  local label="$1"
  timeout 40 "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  timeout 30 "$ADB" exec-out cat /sdcard/ui.xml > ".cache/m1/$label.ui.xml" 2>/dev/null
  tr '<' '\n' < ".cache/m1/$label.ui.xml" \
    | grep -oE '(text|content-desc|resource-id)="[^"]*"' | grep -v '=""' \
    > ".cache/m1/$label.txt" 2>/dev/null
  timeout 30 "$ADB" shell screencap -p "/sdcard/$label.png" >/dev/null 2>&1
  timeout 30 "$ADB" pull "/sdcard/$label.png" "screenshots/m1/$label.png" >/dev/null 2>&1
  echo "--- [$label] ---"
  cat ".cache/m1/$label.txt" 2>/dev/null | head -45
}

# centre of the first node whose text= equals the given string
_bounds_of() {
  local file="$1" needle="$2"
  tr '<' '\n' < "$file" \
    | grep -F "text=\"$needle\"" \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | head -1 | grep -oE '[0-9]+'
}

taptext() {
  local label="$1"; shift
  local needle="$1"
  local f=".cache/m1/$label.ui.xml"
  [ -s "$f" ] || snap "$label" >/dev/null
  local coords
  coords=$(_bounds_of "$f" "$needle" | tr '\n' ' ')
  if [ -z "${coords// /}" ]; then echo "!! no node with text=$needle in $label"; return 1; fi
  set -- $coords
  local cx=$(( ($1 + $3) / 2 )) cy=$(( ($2 + $4) / 2 ))
  echo "tap [$needle] -> ($cx,$cy)"
  timeout 20 "$ADB" shell input tap "$cx" "$cy"
}

tapsel() { # tapsel <label> <regex over the whole <node ...> line>
  local label="$1" re="$2"
  local f=".cache/m1/$label.ui.xml"
  local cx cy
  read -r cx cy < <(tr '<' '\n' < "$f" | grep -E "$re" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | head -1 | grep -oE '[0-9]+' | awk '{s=NR; x[NR]=$1} END{if (NR==4) printf "%d %d\n", (x[1]+x[3])/2, (x[2]+x[4])/2}')
  if [ -z "${cx:-}" ]; then echo "!! no node matching /$re/ in $label"; return 1; fi
  echo "tap /$re/ -> ($cx,$cy)"
  timeout 20 "$ADB" shell input tap "$cx" "$cy"
}
