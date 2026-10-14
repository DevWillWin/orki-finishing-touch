#!/usr/bin/env bash
# Runs the instrumented tests on an already-booted emulator (called from android-emulator-runner) and
# collects everything needed to diagnose a device-side failure: logcat (all buffers), memory dumps,
# gradle output and the JUnit XML. Never fails the step: the report step decides the verdict.
#
# usage: run_device_tests.sh [instrumentation-class-filter]
set -u
OUT="build-reports/device"
mkdir -p "$OUT"
FILTER="${1:-}"
PKG="com.devdgames.orkiai"

adb wait-for-device
{
  echo "release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "pagesize=$(adb shell getconf PAGESIZE | tr -d '\r')"
  adb shell cat /proc/meminfo | head -3 | tr -d '\r'
  echo "webview=$(adb shell dumpsys webviewupdate | grep -i -E 'Current WebView package|packageName' | head -3 | tr -d '\r' | tr '\n' ' ')"
  adb shell df -h /data | tail -1 | tr -d '\r'
} > "$OUT/device.txt" 2>&1

adb logcat -b all -c
adb logcat -b all -v threadtime > "$OUT/logcat_full.txt" &
LOGCAT_PID=$!

ARGS=()
if [ -n "$FILTER" ]; then
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=$FILTER")
fi

gradle connectedDebugAndroidTest --no-configuration-cache --console=plain --stacktrace "${ARGS[@]}" 2>&1 | tee "$OUT/gradle_connected.log"
echo "gradle_exit=${PIPESTATUS[0]}" > "$OUT/gradle_exit.txt"

adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo_after.txt" 2>&1 || true
adb shell dumpsys activity exit-info "$PKG" > "$OUT/exit_info.txt" 2>&1 || true
sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true
exit 0
