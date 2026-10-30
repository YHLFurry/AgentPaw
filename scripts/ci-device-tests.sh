#!/usr/bin/env bash
# Keep diagnostics outside the emulator so they survive a lost device.
set -uo pipefail

mkdir -p build/device-diagnostics
adb logcat -c
adb logcat -b all -v threadtime > build/device-diagnostics/logcat.txt 2>&1 &
logcat_pid=$!
(
  while true; do
    date -u
    free -m
    ps -eo pid,ppid,rss,comm --sort=-rss | head -15
    sleep 5
  done
) > build/device-diagnostics/host-memory.txt 2>&1 &
monitor_pid=$!

collect_diagnostics() {
  status=$?
  trap - EXIT
  adb devices -l > build/device-diagnostics/devices.txt 2>&1 || true
  sudo dmesg -T > build/device-diagnostics/kernel.txt 2>&1 || true
  kill "$logcat_pid" "$monitor_pid" 2>/dev/null || true
  wait "$logcat_pid" "$monitor_pid" 2>/dev/null || true
  exit "$status"
}
trap collect_diagnostics EXIT

./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace --max-workers=2
