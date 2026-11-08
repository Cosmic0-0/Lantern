# TTS engine

A link means I read that source. [estimate] marks my own calculation. [unverified] marks something I could not confirm. RTF means seconds of computing per second of speech, so below 1 is faster than real time.

Costs assume a chapter of 20,000 characters, about 20 minutes of audio [estimate]. That is a cautious figure, since the chapters I sampled had 10,000 to 13,000 characters. The fast-voice speeds below come from [a benchmark](https://github.com/muxable/rtchat/issues/1500) on an x86 emulator, so compare them with each other and don't read them as A26 speeds. All on-device options are free, work offline and send no text anywhere.

| Engine | Voice quality | Cost per chapter | Offline | Latency | On the A26 |
|---|---|---|---|---|---|
| Android system TTS | Probably the voices you already dislike [unverified] | $0 | Depends on voice | Instant | Fine. [Highlight timing](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener) only comes if the engine supplies it |
| Kokoro v1.0 fp32 | Best free candidate. [Apache-2.0](https://huggingface.co/hexgrad/Kokoro-82M), [28 English voices](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md), `af_heart` graded A by its author | $0 | Yes | 6.3 s to load, then the wait below | Measured RTF about 1.23 and 874 MB of RAM once loaded. [About 350 MB](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html) of files |
| Phonon 31M English q8 | Your choice, picked by ear on 2026-11-08. Apache-2.0, about 100 voices. Ranks above Kokoro on the [Artificial Analysis arena](https://artificialanalysis.ai/text-to-speech/leaderboard?open_weights=true) | $0 | Yes | 0.4 s first audio | Measured RTF 0.33 to 0.43. 64.5 MB, plus 256 KB per voice. Not through sherpa-onnx |
| Supertonic 3 int8 | Vendor claims only, needs your ears. OpenRAIL-M license, fine for personal use | $0 | Yes | Under 1 s | Emulator RTF 0.20, 129 MB |
| Kitten nano v0.8 fp32 | Needs your ears. Fewest word errors of the English models in the benchmark. Apache-2.0 | $0 | Yes | Under 1 s | Emulator RTF 0.09, 64 MB |
| Piper medium | Usually rated less natural [unverified]. More word errors in the tested voice | $0 | Yes | Fastest | Emulator RTF 0.05, 82 MB |
| Kokoro in browser | Same voice | $0 | Yes | Unknown | [About 0.5x to 1x real time on desktop CPUs](https://offlinetts.pages.dev/blog/browser-tts-showdown-kokoro-piper-kitten/). On this phone: [unverified] |
| Azure | High [unverified by ear] | [500,000 free characters a month](https://azure.microsoft.com/en-us/pricing/details/speech/), about 25 chapters. Then about $0.30 [secondary sources] | No | Network | Fine. [Signup needs a card](https://azure.microsoft.com/en-us/pricing/purchase-options/azure-account) |
| Google Cloud | High [unverified by ear] | [Free monthly](https://cloud.google.com/text-to-speech/pricing): Chirp 3 HD 1M, about 50 chapters, then $0.60. WaveNet 4M, about 200 chapters, then $0.08 | No | Network | Fine. [Billing must be enabled](https://docs.cloud.google.com/text-to-speech/docs/before-you-begin), so overage can cost you real money |
| Gemini API | High [unverified by ear] | [Free tier](https://aistudio.google.com/docs/pricing) for Flash TTS, but your text is "used to improve our products". Daily limits: [unverified] | No | Network | Fine |
| ElevenLabs | High [unverified by ear] | [About 10,000 free credits a month](https://costbench.com/software/ai-voice-tools/elevenlabs/free-plan), about half a chapter | No | Network | Fine |
| OpenAI | High [unverified by ear] | [About $0.015 a minute](https://developers.openai.com/api/docs/models/gpt-4o-mini-tts), about $0.30 [secondary sources]. No recurring free tier | No | Network | Fine |
| edge-tts | High | $0, unofficial | No | Network | An add-on author says [Microsoft blocked browser clients in December 2025](https://addons.mozilla.org/firefox/addon/edge-tts/). Too fragile to build on |

## Your choice

**Updated 2026-11-08: Phonon 31M English replaces Supertonic.** You preferred its samples to Supertonic's, choosing Everly (female) and Trevor (male), with the default voice as a possible third. On your phone it is faster than Supertonic:

| Setting, on `tools/voice-test/passage.txt` | RTF on your A26 | First audio |
|---|---|---|
| Phonon q8, 4 threads, Everly | 0.33 and 0.40 | 0.38 to 0.46 s |
| Phonon q8, 4 threads, Trevor | 0.40 | 0.45 s |
| Phonon q8, 2 threads, Everly | 0.43 | 0.43 s |
| Supertonic 3 int8, 5 steps, for comparison | 0.59 to 0.61 | not measured |

These ran through `adb shell` with ptts v0.6.0 built from source, the phone plugged in, one or two runs each. The passage made 64 to 69 s of audio, against Supertonic's 70 s. Samples 12 and 13 in `Music/LanternVoiceTest` were made on the phone. Even at 2 threads Phonon makes audio more than twice as fast as it plays, so Lantern can run it gently.

What changes: Phonon doesn't run through sherpa-onnx. Lantern would build Phonon's runtime from source as `libptts_ffi.so`, a C library with a Kotlin wrapper through JNA, described in its [Android guide](https://github.com/gradium-ai/xn-ptts/blob/main/android/README.md). It's Apache-2.0 for the weights and MIT or Apache-2.0 for the runtime. It reads numbers itself, only partly, so `core` still checks them. Supertonic through sherpa-onnx stays the fallback if [Spike 1](../../PLAN.md#roadmap) can't make Phonon work inside an app. More detail is in [Laptop precompute](laptop-precompute.md#phonon-checked-2026-11-08).

**Earlier choice, 2026-11-08.** You listened to the [samples](../research/voice-tests.md#fast-voice-samples) and chose Supertonic 3 int8 at 5 steps, voice B (`--sid=0`, female) or voice A (`--sid=5`, male). You rated Kitten, Piper, Supertonic at 3 steps and the Kokoro digits test lower. Lantern ships Supertonic alone, with both voices in settings, so the Kokoro handover, the fast-voice fallback and preparing chapters while charging are dropped from the MVP. The Kokoro sections below are kept as the record of why.

On your phone it measured RTF 0.59 to 0.61, so it builds a lead while you listen and only falls behind above about 1.6x playback speed. It reads slower than Kokoro, about 800 characters a minute against 1,300, so a 10,000 to 13,000 character chapter runs about 12 to 16 minutes and takes about 7 to 10 minutes to generate in the background [estimate]. Supertonic uses no lexicon, but its logs weren't checked for skipped words, so [Spike 1](../../PLAN.md#roadmap) still counts them.

## Engine

**Recommendation: on-device only.** Phonon runs through its own runtime, as described [above](#your-choice). [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) is the fallback: it is Apache-2.0, ships Android and Windows builds, and runs Kokoro, Supertonic, Kitten and Piper through one API.

Four details matter if Lantern falls back to sherpa-onnx:

- Avoid Kokoro v1.1 for English. It [drops most English voices](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh).
- Default to fp32. On a Pixel 10 Pro [it was faster than int8](https://github.com/k2-fsa/sherpa-onnx/issues/3754), 0.48 to 0.51 against 0.62, and int8 sometimes produced garbage audio. A [fix merged on 2026-10-26](https://github.com/k2-fsa/sherpa-onnx/pull/4087), so int8 is worth retesting.
- The prebuilt library [links GPL-3.0 espeak-ng](https://github.com/muxable/rtchat/issues/1500). That doesn't matter for a personal app, but it would if you ever published Lantern.
- Leave out the Chinese rule files. The demo app is built with `number-zh.fst`, `date-zh.fst` and `phone-zh.fst`, and the engine [runs them on the text](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-tts-kokoro-impl.h) before speaking, which fits the Chinese-sounding numbers you heard. Lantern would load English only and turn numbers into English words itself in `core`, so "Chapter 42" becomes "Chapter forty-two". [Spike 1](../../PLAN.md#roadmap) tests this.

## Kokoro's wait against your 2-minute limit

This section and the next two are the record of why Kokoro was ruled out. Phonon doesn't wait, so none of it applies now.

The measurements from your phone are in [Voice tests](../research/voice-tests.md#measured-on-your-phone).

At the measured RTF of about 1.23, Kokoro falls behind by about 11 seconds for every minute you listen:

| Chapter length | Wait with Kokoro alone | Or: fast voice for this long, then Kokoro | Time to prepare it ahead |
|---|---|---|---|
| 9 min | About 2 min | About 1.7 min | About 11 min |
| 12 min | About 2.8 min | About 2.2 min | About 15 min |
| 20 min | About 4.6 min | About 3.7 min | About 25 min |

The wait is chapter length x (RTF - 1). The handover point is chapter length x (1 - 1/RTF). The chapters I sampled would run about 10 to 13 minutes [estimate], so the usual wait is just over your 2-minute limit.

These figures may be off in both directions. The later command-line runs in [Voice tests](../research/voice-tests.md#kokoro-cant-be-sped-up-with-settings) measured RTF 1.34 to 1.37, which makes the waits about 50% longer and the handover times about 40% longer. Kokoro also read faster than I assumed, so those chapters may run closer to 8 to 10 minutes.

**Prepared audio removes most of the problem.** Audio generated ahead of time builds a lead, and listening uses it up at that 11 seconds a minute. One fully prepared 12-minute chapter should buy about an hour of unbroken Kokoro [estimate] and takes about 15 minutes to generate. So Lantern should prepare the current and next chapter while the phone charges, which stays within your one-chapter-ahead limit. Faster playback uses up the lead about twice as fast at 1.25x.

**Kokoro can't be sped up with settings.** You judged its voice good enough but too slow. The timings for each voice and setting are in [Voice tests](../research/voice-tests.md#kokoro-cant-be-sped-up-with-settings).

**Options now that Kokoro can't keep up:**

| Option | Wait | Extra work | Verdict |
|---|---|---|---|
| Supertonic 3 as the only voice | Seconds | Least | Recommended if the samples sound good enough to you |
| Kokoro, with a fast voice first on unprepared chapters and preparation while charging | None for you, but the voice changes mid-chapter | Most | Recommended if only Kokoro will do |
| Kokoro alone, with preparation while charging | About 3 min on an unprepared 9-minute chapter | Medium | Breaks your 2-minute limit |
| Laptop generates Kokoro and sends it to the phone | None if prepared | Medium, and the laptop must be on | Later option |
| Cloud voices | Seconds | Accounts and internet | Rejected, see [the engine table](#tts-engine) |

**Unknown words are a risk with sherpa-onnx.** Phonon reads raw text through its own tokenizer, so this applies only to the fallback, but [Spike 1](../../PLAN.md#roadmap) still listens for dropped words. sherpa-onnx 2.0 removed espeak-ng and looks words up in a lexicon. Unknown words are skipped silently. On my passage the tool skipped any word touching a quotation mark, plus "Kael", "Sealed", "wanted", "opens" and "minutes" with Kokoro, and "her" with Piper and Kitten. Light novels are full of invented names, so this could drop words without warning. Supertonic uses no lexicon, so it shouldn't have this problem, but I didn't check its logs for skipped words. The demo app you tried is the older 1.12.21 with espeak-ng data. [Spike 1](../../PLAN.md#roadmap) must count skipped words on real chapters, strip quotation marks first, and compare 2.0 with the older version. Kitten also crashed on the whole passage, so it needs one sentence at a time.

**How the handover would have worked.** It's dropped with Kokoro. Each sentence plays the best audio available for it. Kokoro starts generating at the calculated point ahead of you, and when playback reaches it, the voice changes at that sentence boundary. Position is stored against the text, so the swap can't lose your place. Running both engines slows Kokoro and adds to its 874 MB of RAM, and Android 17 adds [RAM-based app memory limits](https://developer.android.com/about/versions/17/behavior-changes-all), so [Spike 1](../../PLAN.md#roadmap) tests this too.

**Loading one chapter ahead.** When a chapter starts, Lantern fetches the next chapter's text. Once the current chapter's audio is finished, it generates the next chapter's audio. Nothing beyond that is fetched or generated. Phonon's audio is 24 kHz, so 2.88 MB a minute as WAV, about 58 MB per 20-minute chapter, and only the current and next chapters stay on disk.
