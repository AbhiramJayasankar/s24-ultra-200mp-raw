#!/usr/bin/env bash
# Runs one probe capture on the phone, prints the probe log and the HAL mode lines,
# then moves the saved files to captures/ (deleting them from the phone).
# Usage: tools/probe.sh <seconds-to-wait> key=value ...   (string extras, e.g. cam=20 streams=...)
#        ints/bools are passed as i:key=value / z:key=true
set -u
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
PKG=com.samsung.android.ruler
WAIT="$1"; shift
args="--es mode capture"
for a in "$@"; do
  case "$a" in
    i:*) kv="${a#i:}"; args="$args --ei ${kv%%=*} ${kv#*=}" ;;
    z:*) kv="${a#z:}"; args="$args --ez ${kv%%=*} ${kv#*=}" ;;
    f:*) kv="${a#f:}"; args="$args --ef ${kv%%=*} ${kv#*=}" ;;
    *)   args="$args --es ${a%%=*} '${a#*=}'" ;;
  esac
done
"$ADB" shell am force-stop $PKG
sleep 1
"$ADB" logcat -c
"$ADB" shell "am start -n $PKG/dev.s24cam.ProbeActivity $args" >/dev/null
sleep "$WAIT"
"$ADB" logcat -d -s S24CAM | grep -v "^\s*at \|preview failed\|beginning of" | cut -c32-260
echo "HAL crashes: $("$ADB" logcat -d | grep -c 'Fatal signal')"
"$ADB" logcat -d | grep -E "CheckEnableCondition|Current shootingMode|FillTuningModeDebug.*Snapshot|Matching Usecase id" \
  | awk '!seen[substr($0,32)]++' | cut -c32-260
bash "$(dirname "$0")/pull_and_clean.sh"
