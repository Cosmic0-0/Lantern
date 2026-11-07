# Runs on the phone. Lists the words each lexicon-based engine reports as unknown or skipped.
. /data/local/tmp/lantern/lib.sh
T2="$(cat passage2.txt)"
echo "-- kokoro, normalized passage"
./tts --num-threads=4 $KOKORO --sid=3 --output-filename=out/kokoro-norm.wav "$T2" > out/kokoro-norm.log 2>&1
grep -E 'Elapsed|Audio duration|RTF' out/kokoro-norm.log | cut -c1-120
echo "unknown-word lines: $(grep -c -i -E 'oov|skipped|not in the lexicon|cannot find' out/kokoro-norm.log)"
grep -i -E 'oov|skipped|not in the lexicon|cannot find' out/kokoro-norm.log | sed 's/.*://' | sort | uniq -c | sort -rn | head -12 | cut -c1-90
echo "-- piper, normalized passage"
./tts --num-threads=4 $PIPER --output-filename=out/piper-norm.wav "$T2" > out/piper-norm.log 2>&1
grep -E 'Elapsed|Audio duration|RTF' out/piper-norm.log | cut -c1-120
echo "unknown-word lines: $(grep -c -i -E 'oov|skipped|not in the lexicon|cannot find' out/piper-norm.log)"
grep -i -E 'oov|skipped|not in the lexicon|cannot find' out/piper-norm.log | sed 's/.*://' | sort | uniq -c | sort -rn | head -12 | cut -c1-90
echo "-- kitten, one sentence at a time"
n=0; crash=0
while IFS= read -r line; do
  [ -z "$line" ] && continue
  n=$((n+1))
  ./tts --num-threads=4 $KITTEN --sid=0 --output-filename="out/kit-$n.wav" "$line" > out/kit.log 2>&1
  rc=$?
  oov=$(grep -i -E 'oov|skipped' out/kit.log | sed 's/.*skipped: //' | tr '\n' ' ')
  rtf=$(grep -E 'RTF' out/kit.log | sed 's/.*= //')
  echo "$n rc=$rc rtf=$rtf chars=${#line} oov=[$oov]"
done < sentences.txt
grep -E '^(her|Her|HER|she|Mira|mira|Kael|kael|KAEL) ' lexicon-en-us.txt | head -6 | cut -c1-60
echo "kokoro lexicon:"; grep -E '^(her|mira|kael|Mira|Kael) ' kokoro/lexicon-us-en.txt | head -5 | cut -c1-60
