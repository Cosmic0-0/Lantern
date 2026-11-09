#!/usr/bin/env bash
# Driver for the Lantern app. Runs on the laptop in Git Bash; the phone must be connected over adb.
# Grows as the Phase 2 steps need it (docs/phase-2-plan.md).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# adb lives in the main checkout's gitignored .bench folder, which worktrees don't have.
ADB=${ADB:-/c/Users/Caellum/Documents/Projects/Lantern/.bench/pt/platform-tools/adb.exe}
PKG=app.lantern
APK=app/build/outputs/apk/debug/app-debug.apk
export JAVA_HOME=${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}
export MSYS_NO_PATHCONV=1 # Git Bash would otherwise rewrite phone paths
win() { cygpath -w "$1"; } # laptop paths given to adb.exe must be Windows paths

usage() {
  cat <<USAGE
Usage: tools/lantern.sh <command>

  test          run the laptop tests (core and sources)
  build         assemble the debug APK
  install       install the debug APK
  launch        start the app and print Lantern's log lines from this launch
  log           print Lantern's log lines and any crash
  kill          kill -9 the app's process (needs a debuggable build)
  force-stop    am force-stop, what Settings > Force stop does

Env: ADB, JAVA_HOME
USAGE
}

cmd_test() { ./gradlew :core:test :sources:test; }
cmd_build() { ./gradlew :app:assembleDebug; }
cmd_install() { "$ADB" install -r "$(win "$APK")"; }
cmd_log() { "$ADB" logcat -d -s Lantern:V AndroidRuntime:E; }
cmd_launch() {
  "$ADB" logcat -c
  "$ADB" shell am start -W -n "$PKG/.MainActivity"
  sleep 3
  cmd_log
}
cmd_kill() { "$ADB" shell run-as "$PKG" kill -9 "$("$ADB" shell pidof "$PKG" | tr -d '\r')"; }
cmd_force_stop() { "$ADB" shell am force-stop "$PKG"; }

case "${1:-}" in
  test) cmd_test ;;
  build) cmd_build ;;
  install) cmd_install ;;
  launch) cmd_launch ;;
  log) cmd_log ;;
  kill) cmd_kill ;;
  force-stop) cmd_force_stop ;;
  *) usage; exit 1 ;;
esac
