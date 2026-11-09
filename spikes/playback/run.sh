#!/usr/bin/env bash
# Driver for Spike 2 (playback). Runs on the laptop in Git Bash; the phone must be connected over adb.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
HERE=$PWD

ADB=${ADB:-/c/Users/Caellum/Documents/Projects/Lantern/.bench/pt/platform-tools/adb.exe}
PKG=app.lantern.spike.playback
DEV=/sdcard/Android/data/$PKG/files
MODEL_SRC=${MODEL_SRC:-$(echo "$HOME"/.cache/huggingface/hub/models--gradium--phonon-31m-en/snapshots/*/)}
VOICES_SRC=${VOICES_SRC:-$(echo "$HOME"/.cache/huggingface/hub/models--gradium--phonon-voices/snapshots/*/voices)}
export MSYS_NO_PATHCONV=1 # Git Bash would otherwise rewrite /sdcard/... paths
# With path conversion off, laptop paths given to adb.exe must be Windows paths.
win() { cygpath -w "$1"; }

usage() {
  cat <<USAGE
Usage: ./run.sh <command>

  build         assemble the debug APK
  install       install the APK, launch it once and create the folders on the phone
  push-model    push the Phonon model and the everly and trevor voices
  log           print the phone's events.log
  pull          copy the phone's events.log to results/events-<date>.log
  clear-log     delete the phone's events.log
  reset         force-stop the app and delete its chapter texts and events.log (the model stays)
  kill          kill -9 the app's process through run-as (the way to test a kill; needs a debuggable build)
  force-stop    am force-stop, which is what Settings > Force stop does

Env: ADB, JAVA_HOME, MODEL_SRC, VOICES_SRC
USAGE
}

cmd_build() {
  export JAVA_HOME=${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}
  ./gradlew assembleDebug
}

cmd_install() {
  local apk=build/outputs/apk/debug/lantern-spike-playback-debug.apk
  [[ -f $apk ]] || apk=$(ls build/outputs/apk/debug/*.apk | head -n 1)
  "$ADB" install -r "$(win "$apk")"
  # Launch once so Android creates the external files dir.
  "$ADB" shell am start -W -n "$PKG/.MainActivity"
  "$ADB" shell mkdir -p "$DEV/model/voices"
}

cmd_push_model() {
  local f
  for f in config.json tokenizer.json model.q8.gguf default-voice.safetensors; do
    "$ADB" push "$(win "$MODEL_SRC/$f")" "$DEV/model/$f"
  done
  "$ADB" push "$(win "$VOICES_SRC/vjFyp2BcCyTlUmYb.safetensors")" "$DEV/model/voices/everly.safetensors"
  "$ADB" push "$(win "$VOICES_SRC/0zLApsZfVLLNaEGl.safetensors")" "$DEV/model/voices/trevor.safetensors"
}

cmd_log() {
  "$ADB" shell cat "$DEV/events.log"
}

cmd_pull() {
  mkdir -p "$HERE/results"
  local out="$HERE/results/events-$(date +%Y%m%d-%H%M%S).log"
  "$ADB" pull "$DEV/events.log" "$(win "$out")"
  echo "saved $out"
}

cmd_clear_log() {
  "$ADB" shell rm -f "$DEV/events.log"
}

cmd_reset() {
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell rm -rf "$DEV/chapters" "$DEV/events.log"
  echo "Removed chapters and events.log. The saved position and the audio cache stay; the cache for a chapter is dropped when its text is fetched again."
}

cmd_kill() {
  local pid
  pid=$("$ADB" shell pidof "$PKG" | tr -d '\r') || true
  if [[ -z $pid ]]; then
    echo "$PKG is not running"
    return 0
  fi
  echo "pid $pid"
  # am kill only touches cached background processes, and the shell user may not signal an app process,
  # but run-as works on a debuggable build because it becomes the app's own user.
  if "$ADB" shell run-as "$PKG" kill -9 "$pid"; then
    sleep 1
    if "$ADB" shell pidof "$PKG" | tr -d '\r' | grep -qx "$pid"; then
      echo "pid $pid is still alive after kill -9"
    else
      echo "killed pid $pid"
    fi
  else
    echo "run-as kill failed, the process was not killed" >&2
    return 1
  fi
}

cmd_force_stop() {
  "$ADB" shell am force-stop "$PKG"
  echo "force-stopped $PKG"
}

case ${1:-} in
  build) cmd_build ;;
  install) cmd_install ;;
  push-model) cmd_push_model ;;
  log) cmd_log ;;
  pull) cmd_pull ;;
  clear-log) cmd_clear_log ;;
  reset) cmd_reset ;;
  kill) cmd_kill ;;
  force-stop) cmd_force_stop ;;
  *) usage; [[ -z ${1:-} ]] || exit 2 ;;
esac
