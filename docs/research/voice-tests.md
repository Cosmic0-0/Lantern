# Voice tests

A link means I read that source. RTF means seconds of computing per second of speech, so below 1 is faster than real time.

## Measured on your phone

Measured on your phone on 2026-11-07, with the sherpa-onnx demo at its default 4 threads and speaker 3:

| Measure | Result |
|---|---|
| Phone | SM-A266B, Exynos 1380, 6 GB RAM, Android 16, One UI 8.5 |
| Generating and playing a 4-minute passage | A few seconds under 9 minutes |
| Speed | RTF about 1.23 |
| Model load | 6.3 s |
| RAM once loaded | About 874 MB |

My earlier estimate of 0.6 to 0.9 was too optimistic. This is one short run, and heat and battery use over a whole chapter are still unmeasured.

## Kokoro test, done 2026-11-07

RTF about 1.23, with the results under [Measured on your phone](#measured-on-your-phone). The [demo app](https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-new/1.12.21/sherpa-onnx-1.12.21-arm64-v8a-zh_en-tts-kokoro-multi-lang-v1_0.apk) had to go on over USB. The copy downloaded on the phone had the right size but the wrong contents, and the phone's log showed failure `-103`, "SHA-256 digest of contents did not verify". Lantern must verify a checksum after downloading any voice model.

To repeat the test, time Generate until it becomes tappable again (T), then time Play (L). RTF = T / L - 1. Gaps between sentences are normal in this demo, because its code [plays each sentence before generating the next](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxTts/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt). That is why the formula subtracts 1, and Lantern won't work that way. Speaker IDs 2, 3, 16 and 21 are `af_bella`, `af_heart`, `am_michael` and `bf_emma`, if you want to compare voices.

## Kokoro can't be sped up with settings

On 2026-11-07 I timed the same passage on your phone with sherpa-onnx 2.0.1's command-line tool. The screen state wasn't controlled and each result is one run:

| Voice and setting | RTF on your A26 | Download | Sound, from community reports until you listen |
|---|---|---|---|
| Kokoro fp32, 4 threads, any cores | 1.34 to 1.37 | 349 MB | The most natural |
| Kokoro fp32, 4 threads on fast cores | 1.47 | | |
| Kokoro fp32, 3 threads on fast cores | 1.82 | | |
| Kokoro fp32, 8 threads | 1.76 | | |
| Kokoro int8, 4 threads on fast cores | 2.62 | 132 MB | |
| Pocket TTS int8, 4 threads | 1.12 to 1.26 | 98 MB | Not rated. No real speed gain, and non-commercial license |
| Supertonic 3 int8, 8 steps | 0.93 | 128 MB | Reported less natural than Kokoro |
| Supertonic 3 int8, 5 steps | 0.59 to 0.61 | | Same model at its default setting |
| Supertonic 3 int8, 3 steps | 0.38 | | Lower quality setting |
| Kitten nano v0.8 fp32, 4 threads | 0.30 to 0.64 per sentence | 63 MB | Few independent reports |
| Piper lessac medium, 4 threads | 0.13 to 0.16 | 67 MB | "Somewhat mechanical" |

Compare generation time as well as RTF, because voices read at different paces. For the same text, Kokoro produced about 44 s of audio and Supertonic about 70 s. Sound notes come from [a CPU benchmark](https://bittide.aicompass.dev/article/213f0a6c-be96-402b-8672-c2d9d3cb6029) and [a Piper comparison](https://www.codesota.com/text-to-speech/piper-vs-kokoro), neither of them a blind test. Your samples are in the phone's `Music/LanternVoiceTest` folder.

## Fast voice samples

These files are in `Music/LanternVoiceTest` on your phone. The texts are in `tools/voice-test/`.

| File | Voice and setting | Text |
|---|---|---|
| `1-Supertonic-voice-A.wav` | Supertonic 3 int8, `--sid=5`, 5 steps | `passage.txt` |
| `2-Supertonic-voice-B.wav` | Supertonic 3 int8, `--sid=0`, 5 steps | `passage.txt` |
| `3-Supertonic-voice-A-faster-setting.wav` | Supertonic 3 int8, `--sid=5`, 3 steps | `passage.txt` |
| `4-Kitten.wav` | Kitten nano v0.8 fp32, `--sid=0`, one sentence at a time, joined with `join.mjs` | `sentences.txt` without quotation marks |
| `5-Piper.wav` | Piper lessac medium | `passage2.txt` without quotation marks |
| `6-Kokoro-digits-test.wav` | Kokoro fp32, `--sid=3`, English lexicon only, no Chinese rule files | `passage.txt` |

`passage.txt` writes numbers as digits, and the other two texts spell them out. Sample 6 tests whether the Chinese-sounding numbers are gone, and nobody has checked it by ear yet. Piper and Kitten skip the word "her" in their samples, and sample 6 may skip words too, as described in [TTS engine](../decisions/voice-engine.md).

To make more samples:

1. Download `sherpa-onnx-v2.0.1-android-aarch64-termux-static.tar.bz2` from the [v2.0.1 release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1), and the voices and `lexicon-en-us.txt` from the [`tts-models` release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models).
2. Get `libc++_shared.so` from the `libc++` package at [packages.termux.dev](https://packages.termux.dev/apt/termux-main/pool/main/libc/libc++/).
3. Push `sherpa-onnx-offline-tts` to `/data/local/tmp/lantern/tts`, with `libc++_shared.so`, `lexicon-en-us.txt`, the texts and `lib.sh` beside it.
4. Push each voice to the folder that `lib.sh` expects: `kokoro`, `kokoro-int8`, `kitten`, `supertonic`, `pocket` or `piper`.
5. Run a voice with `adb shell ". /data/local/tmp/lantern/lib.sh; run my-label all 4 \"\$T\" \$KOKORO --sid=3"`. Audio lands in `out/`, and `oov.sh` lists skipped words.

You can also try Kitten and Piper through the [project's demo APKs](https://k2-fsa.github.io/sherpa/onnx/tts/apk.html), though the newest Kitten demo I found is the older v0.2, not the v0.8 tested here. Supertonic is also available through the [Supertonic TTS app](https://apt.izzysoft.de/fdroid/index/apk/com.brahmadeo.supertonic.tts?repo=main).

## Other notes from these tests

- These timings ran through `adb shell`, not inside an app, so an app's speed may differ.
- Kokoro read the 941-character `passage.txt` in about 44 s, or about 1,300 characters a minute.
- [VoxSherpa's README](https://github.com/CodeBySonu95/VoxSherpa-TTS) claims 60 to 90 s per minute of audio for Kokoro on mid-range phones, which is RTF 1.0 to 1.5 and fits these results.
- The phone has 57 GB free and uses 4 KB memory pages. Its chip reports as `s5e8835`.
- The undamaged demo APK is in the laptop's `Downloads` folder, with SHA-256 `a6654f8b259a713b353f510562b3a84db3744c40593d4cf217de632f99f030e7`. The damaged copy may still be in the phone's `Download` folder, and is safe to delete.
