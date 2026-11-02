#!/usr/bin/env bash
# Resilient adb helpers — the software-emulated guest drops the adb link
# for a few seconds at a time, so every call retries until it works.
#   source .cache/m1/rob.sh
#   rsh <cmd...>      -> adb shell with retries
#   rsnap <label>     -> UI dump + screenshot, retries until non-empty
#   rtap <label> <re> -> tap centre of first node matching regex (retry)
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
mkdir -p .cache/m1 screenshots/m1

# Wait until adb reports a usable device (max ~60s).
adb_wait() {
  local i s c
  for i in $(seq 1 30); do
    s=$("$ADB" get-state 2>/dev/null)
    if [ "$s" = "device" ]; then
      c=$("$ADB" shell echo ok 2>/dev/null | tr -d '\r')
      [ "$c" = "ok" ] && return 0
    fi
    sleep 2
  done
  return 1
}

# Run an adb subcommand, retrying while the link flaps.
adb_retry() {
  local i out
  for i in $(seq 1 8); do
    adb_wait || { sleep 2; continue; }
    out=$("$ADB" "$@" 2>&1)
    case "$out" in
      *"device offline"*|*"no devices/emulators found"*|*"error: closed"*) sleep 3; continue ;;
    esac
    printf '%s\n' "$out"
    return 0
  done
  return 1
}

rsh() { adb_retry shell "$@"; }

# Dump the current screen. Retries until the node list is non-empty.
rsnap() {
  local label="$1" f=".cache/m1/$1.ui.xml" n=0
  while [ "$n" -lt 8 ]; do
    adb_wait || { sleep 3; n=$((n+1)); continue; }
    timeout 40 "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    timeout 30 "$ADB" exec-out cat /sdcard/ui.xml > "$f" 2>/dev/null
    [ -s "$f" ] && break
    n=$((n+1)); sleep 3
  done
  tr '<' '\n' < "$f" \
    | grep -oE '(text|content-desc|resource-id)="[^"]*"' | grep -v '=""' \
    > ".cache/m1/$label.txt" 2>/dev/null
  adb_retry shell screencap -p "/sdcard/$label.png" >/dev/null 2>&1
  adb_retry pull "/sdcard/$label.png" "screenshots/m1/$label.png" >/dev/null 2>&1
  echo "--- [$label] ---"
  head -50 ".cache/m1/$label.txt" 2>/dev/null
}

_bounds_of() {
  local file="$1" needle="$2"
  tr '<' '\n' < "$file" \
    | grep -F "text=\"$needle\"" \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | head -1 | grep -oE '[0-9]+'
}

# Tap the node whose text equals the needle exactly (re-dumps on failure).
rtap() {
  local label="$1" needle="$2" f=".cache/m1/$1.ui.xml" coords a b c d cx cy i
  for i in 1 2 3; do
    [ -s "$f" ] || rsnap "$label" >/dev/null
    coords=$(_bounds_of "$f" "$needle" | tr '\n' ' ')
    [ -n "${coords// /}" ] && break
    sleep 2; rsnap "$label" >/dev/null
  done
  if [ -z "${coords// /}" ]; then echo "!! no text=$needle in $label"; return 1; fi
  read -r a b c d <<< "$coords"
  cx=$(( (a + c) / 2 )); cy=$(( (b + d) / 2 ))
  echo "tap [$needle] -> ($cx,$cy)"
  rsh input tap "$cx" "$cy" >/dev/null
}

# Tap the first node whose whole attribute line matches a regex.
rtapsel() {
  local label="$1" re="$2" f=".cache/m1/$1.ui.xml" line cx cy i
  [ -s "$f" ] || rsnap "$label" >/dev/null
  line=$(tr '<' '\n' < "$f" | grep -E "$re" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  if [ -z "$line" ]; then echo "!! no node /$re/ in $label"; return 1; fi
  read -r a b c d <<< "$(echo $line)"
  cx=$(( (a + c) / 2 )); cy=$(( (b + d) / 2 ))
  echo "tap /$re/ -> ($cx,$cy)"
  rsh input tap "$cx" "$cy" >/dev/null
}

# Type text into the focused field (chokes on commas/quotes/parens, so
# callers pass a trimmed chunk and press Backspace when needed).
rtext() { rsh input text "$1" >/dev/null; }

# Type a string into the currently focused field, clearing it first and
# retrying — the emulated guest drops characters when it is CPU-starved.
#   rtype <expected> <label> [fieldRe]
rtype() {
  local expected="$1" label="$2" fieldre="${3:-}" i n chunk got
  rsh input keyevent KEYCODE_MOVE_END >/dev/null
  for i in $(seq 1 40); do rsh input keyevent KEYCODE_DEL >/dev/null; done
  sleep 1
  for n in $(seq 1 ${#expected}); do
    chunk=${expected:$((n-1)):1}
    rsh input text "$chunk" >/dev/null
    sleep 0.25
  done
  sleep 2
  rsnap "$label" >/dev/null
  if grep -q "text=\"$expected\"" ".cache/m1/$label.ui.xml" 2>/dev/null; then
    echo "typed ok: $expected"; return 0
  fi
  echo "!! typed mismatch for [$expected], got:"
  grep -oE 'text="[^"]*"' ".cache/m1/$label.ui.xml" | head -12
  return 1
}
