#!/usr/bin/env bash
# Driver for Spike 1 (engine). Runs on the laptop in Git Bash; the phone must be connected over adb.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
HERE=$PWD

ADB=${ADB:-/c/Users/Caellum/Documents/Projects/Lantern/.bench/pt/platform-tools/adb.exe}
PKG=app.lantern.spike.engine
DEV=/sdcard/Android/data/$PKG/files
MODEL_SRC=${MODEL_SRC:-$(echo "$HOME"/.cache/huggingface/hub/models--gradium--phonon-31m-en/snapshots/*/)}
VOICES_SRC=${VOICES_SRC:-$(echo "$HOME"/.cache/huggingface/hub/models--gradium--phonon-voices/snapshots/*/voices)}
TIMEOUT_S=${TIMEOUT_S:-2700} # 45 minutes
export MSYS_NO_PATHCONV=1    # Git Bash would otherwise rewrite /sdcard/... paths
# With path conversion off, laptop paths given to adb.exe must be Windows paths.
win() { cygpath -w "$1"; }

usage() {
  cat <<USAGE
Usage: ./run.sh <command>

  build                              assemble the debug APK
  install                            install the APK and create the folders on the phone
  push-model                         push the Phonon model and the everly and trevor voices
  push-texts                         push texts/*.txt and texts/real/*.txt
  run <text> <voice> <threads> [mode]   one run; mode is sentences (default) or whole
  pull                               pull the phone's out/ folder into results/

Example: ./run.sh run chapter.txt everly 2 sentences
Env: ADB, JAVA_HOME, MODEL_SRC, VOICES_SRC, TIMEOUT_S (default 2700)
USAGE
}

cmd_build() {
  export JAVA_HOME=${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}
  ./gradlew assembleDebug
}

cmd_install() {
  local apk=build/outputs/apk/debug/lantern-spike-engine-debug.apk
  [[ -f $apk ]] || apk=$(ls build/outputs/apk/debug/*.apk | head -n 1)
  "$ADB" install -r "$(win "$apk")"
  # Launch once so Android creates the external files dir, then stop it.
  "$ADB" shell am start -W -n "$PKG/.MainActivity" --es text none.txt
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell mkdir -p "$DEV/model/voices" "$DEV/input" "$DEV/out"
}

cmd_push_model() {
  local f
  for f in config.json tokenizer.json model.q8.gguf default-voice.safetensors; do
    "$ADB" push "$(win "$MODEL_SRC/$f")" "$DEV/model/$f"
  done
  "$ADB" push "$(win "$VOICES_SRC/vjFyp2BcCyTlUmYb.safetensors")" "$DEV/model/voices/everly.safetensors"
  "$ADB" push "$(win "$VOICES_SRC/0zLApsZfVLLNaEGl.safetensors")" "$DEV/model/voices/trevor.safetensors"
}

cmd_push_texts() {
  local f
  for f in texts/*.txt texts/real/*.txt; do
    [[ -f $f ]] || continue
    "$ADB" push "$(win "$f")" "$DEV/input/$(basename "$f")"
  done
}

cmd_run() {
  [[ $# -ge 3 ]] || { usage; exit 2; }
  local text=$1 voice=$2 threads=$3 mode=${4:-sentences}
  local label="${text%.*}-$voice-${threads}t-$mode" # same formula as the app's default label

  "$ADB" logcat -c
  "$ADB" shell am start -S -W -n "$PKG/.MainActivity" \
    --es text "$text" --es voice "$voice" --ei threads "$threads" --es mode "$mode"

  local fifo logcat_pid final="" status=timeout
  fifo=$(mktemp -u)
  mkfifo "$fifo"
  "$ADB" logcat -v brief -s 'LanternSpike:*' >"$fifo" &
  logcat_pid=$!
  exec 3<"$fifo"
  rm -f "$fifo"

  local deadline=$((SECONDS + TIMEOUT_S)) line
  while ((SECONDS < deadline)); do
    # Wake up every few seconds so the timeout is honoured even when the app is silent.
    if IFS= read -r -t 5 -u 3 line; then
      line=${line%$'\r'}
      case $line in
        *' DONE '*) final=$line; status=done; break ;;
        *' FAIL '*)
          final=$line; status=fail
          echo "$final" >&2
          # The stack trace follows as more logcat lines; show what arrives in the next moments.
          while IFS= read -r -t 2 -u 3 line; do echo "${line%$'\r'}" >&2; done
          break ;;
        *) echo "$line" ;;
      esac
    elif ! kill -0 "$logcat_pid" 2>/dev/null; then
      echo "logcat ended before DONE or FAIL" >&2
      status=logcat
      break
    fi
  done
  exec 3<&-
  kill "$logcat_pid" 2>/dev/null || true

  case $status in
    done)
      echo "$final"
      "$ADB" shell mkdir -p /sdcard/Music/LanternVoiceTest
      "$ADB" shell cp "$DEV/out/$label.wav" "/sdcard/Music/LanternVoiceTest/spike1-$label.wav"
      # A shell cp leaves the media index entry pending, which hides the file from music apps until a scan.
      "$ADB" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d "file:///sdcard/Music/LanternVoiceTest/spike1-$label.wav" >/dev/null
      echo "WAV copied to /sdcard/Music/LanternVoiceTest/spike1-$label.wav"
      ;;
    fail) exit 1 ;;
    timeout)
      echo "Timed out after ${TIMEOUT_S}s waiting for DONE or FAIL." >&2
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
  build) cmd_build ;;
  install) cmd_install ;;
  push-model) cmd_push_model ;;
  push-texts) cmd_push_texts ;;
  run) shift; cmd_run "$@" ;;
  pull) cmd_pull ;;
  *) usage; [[ -z ${1:-} ]] || exit 2 ;;
esac
