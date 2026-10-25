#!/usr/bin/env bash
# Launches the debug APK on the emulator and fails if the app crashes in its first 30 seconds.
# Demo mode is on by default, so this needs no server.
set -u
PKG=com.itmanager.app.debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n "$PKG/com.itmanager.app.MainActivity"
# Long enough for the demo alert banner (20 s) and the live channel to start.
sleep 30
adb logcat -d > logcat.txt
# Other emulator apps (Gmail, GMS) sometimes crash while booting; only our process counts.
if ! adb shell pidof "$PKG" > /dev/null || grep -q "Process: $PKG," logcat.txt; then
  echo "::error::App crashed after launch"
  grep -B 1 -A 60 "Process: $PKG," logcat.txt || tail -n 200 logcat.txt
  exit 1
fi
echo "App is still running after 30 s"
