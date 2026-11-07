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

To make more, push `sherpa-onnx-v2.0.1-android-aarch64-termux-static` plus Termux's `libc++_shared.so` to `/data/local/tmp`, and run `sherpa-onnx-offline-tts` with `LD_LIBRARY_PATH=.`. Piper and Kitten also need `lexicon-en-us.txt` from the `tts-models` release. Google's Platform Tools aren't installed on the laptop, so download them again. You can also try Kitten and Piper through the [project's demo APKs](https://k2-fsa.github.io/sherpa/onnx/tts/apk.html), and Supertonic through the [Supertonic TTS app](https://apt.izzysoft.de/fdroid/index/apk/com.brahmadeo.supertonic.tts?repo=main).
