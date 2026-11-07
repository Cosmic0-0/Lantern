# Runs on the phone. Each voice generates the same passage; timings come from the tool itself.
cd /data/local/tmp/lantern || exit 1
export LD_LIBRARY_PATH=.
mkdir -p out
T="$(cat passage.txt)"
KOKORO="--kokoro-model=kokoro/model.onnx --kokoro-voices=kokoro/voices.bin --kokoro-tokens=kokoro/tokens.txt --kokoro-lexicon=kokoro/lexicon-us-en.txt --kokoro-lang=en"
KINT8="--kokoro-model=kokoro-int8/model.int8.onnx --kokoro-voices=kokoro-int8/voices.bin --kokoro-tokens=kokoro-int8/tokens.txt --kokoro-lexicon=kokoro-int8/lexicon-us-en.txt --kokoro-lang=en"
KITTEN="--kitten-model=kitten/model.fp32.onnx --kitten-voices=kitten/voices.bin --kitten-tokens=kitten/tokens.txt --kitten-lexicon=lexicon-en-us.txt"
S=supertonic
SUPER="--supertonic-duration-predictor=$S/duration_predictor.int8.onnx --supertonic-text-encoder=$S/text_encoder.int8.onnx --supertonic-vector-estimator=$S/vector_estimator.int8.onnx --supertonic-vocoder=$S/vocoder.int8.onnx --supertonic-tts-json=$S/tts.json --supertonic-unicode-indexer=$S/unicode_indexer.bin --supertonic-voice-style=$S/voice.bin --lang=en"
P=pocket
POCKET="--pocket-lm-flow=$P/lm_flow.int8.onnx --pocket-lm-main=$P/lm_main.int8.onnx --pocket-encoder=$P/encoder.onnx --pocket-decoder=$P/decoder.int8.onnx --pocket-text-conditioner=$P/text_conditioner.onnx --pocket-vocab-json=$P/vocab.json --pocket-token-scores-json=$P/token_scores.json"
PIPER="--vits-model=piper/en_US-lessac-medium.onnx --vits-tokens=piper/tokens.txt --vits-lexicon=lexicon-en-us.txt"

# run <label> <cpu mask or "all"> <threads> <text> <args...>
run() {
  label=$1; mask=$2; threads=$3; text=$4; shift 4
  before=$(dumpsys battery | grep -m1 temperature | tr -dc 0-9)
  start=$(date +%s)
  if [ "$mask" = all ]; then
    out=$(./tts --num-threads="$threads" "$@" --output-filename="out/$label.wav" "$text" 2>&1)
  else
    out=$(taskset "$mask" ./tts --num-threads="$threads" "$@" --output-filename="out/$label.wav" "$text" 2>&1)
  fi
  end=$(date +%s)
  after=$(dumpsys battery | grep -m1 temperature | tr -dc 0-9)
  echo "== $label mask=$mask threads=$threads wall=$((end-start))s temp=$before->$after"
  echo "$out" | grep -i -E 'elapsed|audio duration|RTF|real.?time|error|failed|cannot|unknown' | cut -c1-170 | tail -4
}
