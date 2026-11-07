# TTS engine

A link means I read that source. [estimate] marks my own calculation. [unverified] marks something I could not confirm. RTF means seconds of computing per second of speech, so below 1 is faster than real time.

Costs assume a chapter of 20,000 characters, about 20 minutes of audio [estimate]. That is a cautious figure, since the chapters I sampled had 10,000 to 13,000 characters. The fast-voice speeds below come from [a benchmark](https://github.com/muxable/rtchat/issues/1500) on an x86 emulator, so compare them with each other and don't read them as A26 speeds. All on-device options are free, work offline and send no text anywhere.

| Engine | Voice quality | Cost per chapter | Offline | Latency | On the A26 |
|---|---|---|---|---|---|
| Android system TTS | Probably the voices you already dislike [unverified] | $0 | Depends on voice | Instant | Fine. [Highlight timing](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener) only comes if the engine supplies it |
| Kokoro v1.0 fp32 | Best free candidate. [Apache-2.0](https://huggingface.co/hexgrad/Kokoro-82M), [28 English voices](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md), `af_heart` graded A by its author | $0 | Yes | 6.3 s to load, then the wait below | Measured RTF about 1.23 and 874 MB of RAM once loaded. [About 350 MB](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html) of files |
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

**Recommendation: on-device only, through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).** It is Apache-2.0, ships Android and Windows builds, and runs all of these models through one API. Use Kokoro v1.0 as the great voice and pick the fast voice by ear if you need one.

Four details matter:

- Avoid Kokoro v1.1 for English. It [drops most English voices](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh).
- Default to fp32. On a Pixel 10 Pro [it was faster than int8](https://github.com/k2-fsa/sherpa-onnx/issues/3754), 0.48 to 0.51 against 0.62, and int8 sometimes produced garbage audio. A [fix merged on 2026-10-26](https://github.com/k2-fsa/sherpa-onnx/pull/4087), so int8 is worth retesting.
- The prebuilt library [links GPL-3.0 espeak-ng](https://github.com/muxable/rtchat/issues/1500). That doesn't matter for a personal app, but it would if you ever published Lantern.
- Leave out the Chinese rule files. The demo app is built with `number-zh.fst`, `date-zh.fst` and `phone-zh.fst`, and the engine [runs them on the text](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-tts-kokoro-impl.h) before speaking, which fits the Chinese-sounding numbers you heard. Lantern would load English only and turn numbers into English words itself in `core`, so "Chapter 42" becomes "Chapter forty-two". [Spike 1](../../PLAN.md#roadmap) tests this.

## Kokoro's wait against your 2-minute limit

The measurements from your phone are in [Voice tests](../research/voice-tests.md#measured-on-your-phone).

At the measured RTF of about 1.23, Kokoro falls behind by about 11 seconds for every minute you listen:

| Chapter length | Wait with Kokoro alone | Or: fast voice for this long, then Kokoro | Time to prepare it ahead |
|---|---|---|---|
| 9 min | About 2 min | About 1.7 min | About 11 min |
| 12 min | About 2.8 min | About 2.2 min | About 15 min |
| 20 min | About 4.6 min | About 3.7 min | About 25 min |

The wait is chapter length x (RTF - 1). The handover point is chapter length x (1 - 1/RTF). The chapters I sampled would run about 10 to 13 minutes [estimate], so the usual wait is just over your 2-minute limit.

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

**Unknown words are a new risk.** sherpa-onnx 2.0 removed espeak-ng and looks words up in a lexicon. Unknown words are skipped silently. On my passage the tool skipped any word touching a quotation mark, plus "Kael", "Sealed", "wanted", "opens" and "minutes" with Kokoro, and "her" with Piper and Kitten. Light novels are full of invented names, so this could drop words without warning. Supertonic uses no lexicon, so it shouldn't have this problem, but I didn't check its logs for skipped words. The demo app you tried is the older 1.12.21 with espeak-ng data. [Spike 1](../../PLAN.md#roadmap) must count skipped words on real chapters, strip quotation marks first, and compare 2.0 with the older version. Kitten also crashed on the whole passage, so it needs one sentence at a time.

**How the handover works.** Each sentence plays the best audio available for it. Kokoro starts generating at the calculated point ahead of you, and when playback reaches it, the voice changes at that sentence boundary. Position is stored against the text, so the swap can't lose your place. Running both engines slows Kokoro and adds to its 874 MB of RAM, and Android 17 adds [RAM-based app memory limits](https://developer.android.com/about/versions/17/behavior-changes-all), so [Spike 1](../../PLAN.md#roadmap) tests this too.

**Loading one chapter ahead.** When a chapter starts, Lantern fetches the next chapter's text. Once the current chapter's audio is finished, it generates the next chapter's audio. Nothing beyond that is fetched or generated. Audio is 2.88 MB a minute at 24 kHz, about 58 MB per 20-minute chapter, and only the current and next chapters stay on disk.
