#!/usr/bin/env bash
# Driver for Spike 3 (sources). Runs on the laptop in Git Bash; the phone must be connected over adb.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
HERE=$PWD

ADB=${ADB:-/c/Users/Caellum/Documents/Projects/Lantern/.bench/pt/platform-tools/adb.exe}
PKG=app.lantern.spike.sources
DEV=/sdcard/Android/data/$PKG/files
TIMEOUT_S=${TIMEOUT_S:-900} # 15 minutes
export MSYS_NO_PATHCONV=1   # Git Bash would otherwise rewrite /sdcard/... paths
# With path conversion off, laptop paths given to adb.exe must be Windows paths.
win() { cygpath -w "$1"; }
# Single-quotes a value for the phone's shell: it becomes one word, and any quote inside it is escaped.
shq() { printf "'%s'" "${1//"'"/"'\''"}"; }

usage() {
  cat <<USAGE
Usage: ./run.sh <command>

  js                           npm ci (if node_modules is missing) and npm run build in js/
  build                        js, then assemble the debug APK
  install                      install the APK
  run [plugins] [query]        one run; plugins is comma separated (default: all five), query defaults to
                               "Ode of the Brave". Prints logcat lines until DONE or the timeout.
  pull                         pull the phone's out/ folder into results/

Example: ./run.sh run anf.net,novelbin "Shadow Slave"
Env: ADB, JAVA_HOME, TIMEOUT_S (default 900)
USAGE
}

cmd_js() {
  (
    cd js
    [[ -d node_modules ]] || npm ci
    npm run build
  )
}

cmd_build() {
  cmd_js
  export JAVA_HOME=${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}
  ./gradlew assembleDebug
}

cmd_install() {
  local apk=build/outputs/apk/debug/lantern-spike-sources-debug.apk
  [[ -f $apk ]] || apk=$(ls build/outputs/apk/debug/*.apk | head -n 1)
  "$ADB" install -r "$(win "$apk")"
}

cmd_run() {
  local plugins=${1:-} query=${2:-}
  # adb joins its arguments into one string for the phone's shell, so a value with spaces needs its own quotes.
  local args=()
  [[ -z $plugins ]] || args+=(--es plugins "$(shq "$plugins")")
  [[ -z $query ]] || args+=(--es query "$(shq "$query")")

  "$ADB" logcat -c
  "$ADB" shell am start -S -W -n "$PKG/.MainActivity" ${args[@]+"${args[@]}"}

  local fifo logcat_pid status=timeout line
  fifo=$(mktemp -u)
  mkfifo "$fifo"
  "$ADB" logcat -v brief -s 'LanternSources:*' >"$fifo" &
  logcat_pid=$!
  exec 3<"$fifo"
  rm -f "$fifo"

  local deadline=$((SECONDS + TIMEOUT_S))
  while ((SECONDS < deadline)); do
    # Wake up every few seconds so the timeout is honoured even when the app is silent.
    if IFS= read -r -t 5 -u 3 line; then
      line=${line%$'\r'}
      echo "$line"
      case $line in
        *' DONE'*) status=done; break ;;
      esac
    elif ! kill -0 "$logcat_pid" 2>/dev/null; then
      echo "logcat ended before DONE" >&2
      status=logcat
      break
    fi
  done
  exec 3<&-
  kill "$logcat_pid" 2>/dev/null || true

  case $status in
    done) echo "Run finished. Fetch the results with: ./run.sh pull" ;;
    timeout)
      echo "Timed out after ${TIMEOUT_S}s waiting for DONE." >&2
      exit 1
      ;;
    *) exit 1 ;;
  esac
}

cmd_pull() {
  mkdir -p "$HERE/results"
  "$ADB" pull "$DEV/out/." "$(win "$HERE/results")"
}

case ${1:-} in
  js) cmd_js ;;
  build) cmd_build ;;
  install) cmd_install ;;
  run) shift; cmd_run "$@" ;;
  pull) cmd_pull ;;
  *) usage; [[ -z ${1:-} ]] || exit 2 ;;
esac
