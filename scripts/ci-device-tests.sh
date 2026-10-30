#!/usr/bin/env bash
# Record numeric resource metrics only; do not upload logcat or process arguments.
set -uo pipefail

mkdir -p build/device-diagnostics
(
  while true; do
    date -u
    free -m
    sleep 5
  done
) > build/device-diagnostics/host-memory.txt 2>&1 &
monitor_pid=$!

collect_diagnostics() {
  status=$?
  trap - EXIT
  if sudo dmesg | grep -Eq 'Out of memory: Killed process.*(qemu|emulator)'; then
    echo 'emulator_oom_killed=true'
  else
    echo 'emulator_oom_killed=false'
  fi > build/device-diagnostics/oom-status.txt
  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true
  exit "$status"
}
trap collect_diagnostics EXIT

./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace --max-workers=2
