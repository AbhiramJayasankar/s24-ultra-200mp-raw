#!/usr/bin/env bash
# Copies every file the app saved on the phone to captures/ on the PC, then deletes
# each phone copy only after the PC copy is confirmed to have the same size.
# Phone storage is tight, so run this after every test capture.
set -u
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
PKG="${PKG:-com.samsung.android.ruler}"
SRC="/sdcard/Android/data/$PKG/files"
OUT="$(cd "$(dirname "$0")/.." && pwd -W)/captures"
mkdir -p "$OUT"

for f in $("$ADB" shell "ls $SRC" | tr -d '\r'); do
  remote_size=$("$ADB" shell "stat -c %s $SRC/$f" | tr -d '\r')
  "$ADB" pull "$SRC/$f" "$OUT/$f" >/dev/null || { echo "pull failed: $f"; continue; }
  local_size=$(stat -c %s "$OUT/$f" 2>/dev/null || echo -1)
  if [ "$remote_size" = "$local_size" ]; then
    "$ADB" shell "rm $SRC/$f"
    echo "moved $f ($local_size bytes)"
  else
    echo "size mismatch for $f (phone $remote_size, pc $local_size); left on phone"
  fi
done
