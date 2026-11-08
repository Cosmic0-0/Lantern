# Laptop precompute

A link means I read that source. [unverified] marks something I could not confirm. RTF means seconds of computing per second of speech, so below 1 is faster than real time.

## Status: dropped for now, 2026-11-08

You dropped the laptop app on 2026-11-08. Lantern is the phone app alone. The idea stays open for the future, if a model comes out that sounds clearly better than Phonon and fits this laptop.

**Why:** the only model tested that ranks clearly above Phonon and might fit was Ming-3.0-tts 0.5B, and it doesn't fit the RTX 3050's 4 GB. On 2026-11-08 it filled 3.9 GB of VRAM, ran the GPU at 100% while drawing only 26 W, and took about 2 seconds per decoding step. The first short chunk of the test passage took about 100 s, which points to RTF well above 5. The run was stopped before finishing, so there's no exact figure and no sample. Everything else that fits ranks below Phonon, so the laptop had no better voice to offer. Untried ways to fit Ming are loading it in bf16 and its community GGUF build, listed under [Bigger model candidates](#bigger-model-candidates).

**What was decided while it was planned, kept in case it returns:**

- The laptop would use a bigger, better-sounding model than the phone's.
- Batches you start on the laptop would be exempt from the one-chapter-ahead limit, with throttled fetches, because you start each one yourself.
- Transfer would be over USB through `adb`.

## Why it waited for version 1

The phone's voice keeps up on its own. The laptop would only add sound quality, so it never blocked daily use.

## How it fits the existing design

[Position tracking](position-tracking.md) already treats the text as the source of truth and audio as a disposable cache, so precomputed chapters are cache entries in another voice. Your place is stored against sentences, so switching between laptop audio and the phone's voice can't lose it.

The design sketch, if the idea returns:

- **A chapter package** holds the chapter text, its sentence list, the audio and a map of where each sentence starts in the audio. The phone uses the package's text for that chapter, so the sentence numbers can't drift if the site edits the chapter later.
- **Shared `core`.** Sentence splitting and number reading must give identical results on both devices, so the laptop reuses the Kotlin `core` module and the site plugins.
- **The laptop app stays small.** It reads your library from the phone, lets you pick a chapter range, generates the audio and pushes it back. It has no reader.
- **USB through `adb`.** USB debugging is already on. Windows gives apps no simple way to copy files to a phone, and Explorer copies are slow with many files. The folder Lantern reads from must be one `adb` can write to, which is [unverified] on Android 16.
- **Storage.** WAV at 24 kHz is about 2.9 MB a minute, and some models output 44.1 kHz, about 5.3 MB a minute. A 30-chapter batch would be about 1.3 to 2.4 GB [estimate], against 57 GB free. Compressed audio would be about 10 times smaller.

## What version 1 does to stay ready

These cost almost nothing in the MVP and keep the door open:

- Each chapter keeps its own sentence list, which the `sentence` table already does.
- Cached audio is keyed by the chapter text's hash and the voice that made it.
- `core` stays plain Kotlin with no Android imports, as [app type](app-type.md) already plans.

## Laptop speed, measured 2026-11-08

The laptop has a Ryzen 7 6800H with 8 cores and 16 threads, a Radeon 680M, an RTX 3050 Laptop GPU and 15 GB of RAM. These ran on the CPU with sherpa-onnx 2.0.1's command-line tool, on `tools/voice-test/passage.txt`, one run each, with the power state not recorded:

| Voice and setting | Laptop RTF | Phone RTF |
|---|---|---|
| Supertonic 3 int8, 5 steps, 4 threads | 0.22 | 0.59 to 0.61 |
| Supertonic 3 int8, 5 steps, 8 threads | 0.20 | |
| Kokoro v1.0 fp32, 4 threads | 0.64 | 1.34 to 1.37 |
| Kokoro v1.0 fp32, 8 threads | 0.48 | |

The laptop CPU is about 3 times faster than the phone. The RTX 3050 couldn't be tested: from Claude Code's sandboxed shell, Windows reports its status as "Unknown" and `nvidia-smi` is refused. That was still true after a restart on 2026-11-08, with the GPU showing in Task Manager. It has 4 GB of VRAM, per you.

## Bigger model candidates

Rankings come from the [Artificial Analysis speech arena](https://artificialanalysis.ai/text-to-speech/leaderboard?open_weights=true), filtered to open weights and read on 2026-11-08. Supertonic isn't on it. Personal use makes non-commercial licenses acceptable.

| Model | Arena Elo | Size | License | On this laptop |
|---|---|---|---|---|
| [Fish Audio Drama 3](https://www.marktechpost.com/2026/10/14/fish-audio-drama-3-open-weight-tts-model/) | 1275 | 35B mixture of experts, about 40 GB of weights | Fish Audio Community License | No. Reports put it at 32 to 48 GB of VRAM |
| [Breeze TTS 2](https://huggingface.co/BreezeBlue/Breeze-TTS-2) | 1228 | 3B, 7.7 GB of weights | Non-commercial weights | Unlikely on the 3050 without quantizing [unverified] |
| [Ming-3.0-tts 0.5B](https://huggingface.co/inclusionAI/Ming-3.0-tts-0.5B) | 1187 | 0.5B, though the card also says 1B | MIT | Likely, but only tested by its authors on data-centre GPUs [unverified]. 44.1 kHz, built-in voices, PyTorch |
| [Phonon 31M English](https://huggingface.co/gradium/phonon-31m-en) | 1114 | 64.5 MB | Apache-2.0 | Yes, it targets CPUs. 24 kHz, about 100 voices |
| Fish Audio S2 Pro | 1108 | 5B | Research license | No |
| Kokoro v1.0 | 1064 | 82M | Apache-2.0 | Yes, measured above |

**Ming-3.0-tts 0.5B was the lead candidate:** the highest-ranked model that plausibly fit a laptop GPU, with a permissive license. In full precision it didn't fit the 4 GB 3050, as described [above](#status-dropped-for-now-2026-11-08). Breeze TTS 2's weights are 7.7 GB, so it can't fit either.

**Phonon may matter for version 1.** It is tiny, ranks above Kokoro, and ships an [Android guide](https://huggingface.co/gradium/phonon-31m-en). It came out on 2026-10-20, after the phone voice tests. Whether it sounds better than Supertonic needs a phone sample.

## Phone precompute: dropped

On 2026-11-08 you asked whether the phone should also precompute batches of chapters. A five-advisor review said no, four to one, and you dropped the idea the same day. The reasons:

- Supertonic at RTF 0.6 already stays ahead of playback. The only gap is the first-audio wait, an estimated 3 to 10 seconds, and generating the next chapter's opening sentences while the current one plays closes it with no job queue.
- The quality case needs a slower phone model that beats Supertonic by ear, and none has yet. Ming can't run on the phone, and Kokoro at RTF 1.3 for hours would heat a charging Exynos 1380 that hasn't been heat-tested.
- Keeping an overnight queue alive on One UI takes a foreground service, battery exemptions, retries and resume. That is the hardest code in the app, for a gain nobody has measured.
- The sentence-keyed audio cache means adding it later needs no redesign, so waiting costs little.

If heat ever becomes a problem, the smaller fix is a "prepare this chapter now" button that you start, running in the foreground and writing into the same cache.

## Phonon, checked 2026-11-08

**You prefer it to Supertonic.** On 2026-11-08 you listened to samples 7 to 11 in `Music/LanternVoiceTest`, made on the laptop with ptts v0.6.0, `--quant q8`, from `tools/voice-test/passage.txt`. You liked Everly (8) for a female voice and Trevor (11) for a male voice, and found the default voice (7) not bad. It was faster than Supertonic on the phone too, so it became the version 1 voice. Phone timings are in [TTS engine](voice-engine.md#your-choice).

Laptop CPU timings, one run each: RTF 0.19 to 0.22 across five voices, first audio after about 0.2 s, and 63 to 68 s of audio for the passage against Supertonic's 70 s.

- **On the phone, it needs a source build.** The [Android guide](https://github.com/gradium-ai/xn-ptts/blob/main/android/README.md) offers no APK and no Gradle package. The library is built with Rust, `cargo-ndk` and the Android NDK, and the system-voice engine app is also built from source. It supports arm64 with dotprod and fp16, which the A26's Exynos 1380 has [unverified].
- **On Windows, a prebuilt CPU binary exists.** [ptts v0.6.0](https://github.com/gradium-ai/xn-ptts/releases/tag/v0.6.0) for Windows x64 verified against its `SHA256SUMS`. CUDA needs a source build.
- **It reads numbers itself, partly.** `--lang en` rewrites numbers, amounts, emails and addresses before speaking. On the test passage it turned 1024 into "1 thousand 24" and left 317 as digits, so check by ear how numbers come out.
- **The phone build works.** On 2026-11-08 the ptts v0.6.0 command-line tool cross-compiled for the phone in about 5 minutes with Rust stable, `cargo-ndk`, NDK r28c, API level 28 and `+dotprod,+fp16`.
- **Speed and pace controls:** `--threads`, and `--condition duration_delta=-0.3` to speak faster.

## Still open

- Nothing while the idea is dropped. If it returns, try Ming in bf16 or its GGUF build, or whatever newer model beats Phonon, and compare samples by ear first.
