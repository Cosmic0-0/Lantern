# Laptop precompute, version 2

A link means I read that source. [unverified] marks something I could not confirm. RTF means seconds of computing per second of speech, so below 1 is faster than real time.

## What you decided on 2026-11-08

- **Version 1 is the phone app alone.** Everything in [PLAN.md](../../PLAN.md) up to Phase 4 is version 1.
- **Version 2 adds a laptop app** that generates audio for batches of chapters with a bigger, better-sounding model, then loads it onto the phone over USB. The phone plays that audio when it's there and falls back to Supertonic when it isn't.
- **The laptop uses a different, bigger model,** not Supertonic at more steps.
- **Laptop batches are exempt from the one-chapter-ahead limit.** You start each batch yourself to precompute chapters, so it isn't the background crawling that limit exists to prevent. Batch fetches are throttled. The phone keeps the limit.

## Why it's version 2 and not the MVP

Supertonic keeps up on the phone on its own, at RTF about 0.6. The laptop only adds sound quality, plus some battery saved on the phone. That doesn't block daily use, so it waits until version 1 works.

## How it fits the existing design

[Position tracking](position-tracking.md) already treats the text as the source of truth and audio as a disposable cache, so precomputed chapters are cache entries in another voice. Your place is stored against sentences, so switching between laptop audio and Supertonic can't lose it.

The design sketch, to be settled when version 2 starts:

- **A chapter package** holds the chapter text, its sentence list, the audio and a map of where each sentence starts in the audio. The phone uses the package's text for that chapter, so the sentence numbers can't drift if the site edits the chapter later.
- **Shared `core`.** Sentence splitting and number reading must give identical results on both devices, so the laptop reuses the Kotlin `core` module and the site plugins.
- **The laptop app stays small.** It reads your library from the phone, lets you pick a chapter range, generates the audio and pushes it back. It has no reader.
- **USB through `adb`.** USB debugging is already on. Windows gives apps no simple way to copy files to a phone, and Explorer copies are slow with many files. The folder Lantern reads from must be one `adb` can write to, which is [unverified] on Android 16.
- **Storage.** WAV at 24 kHz is about 2.9 MB a minute, and some models output 44.1 kHz, about 5.3 MB a minute. A 30-chapter batch would be about 1.3 to 2.4 GB [estimate], against 57 GB free. Compressed audio would be about 10 times smaller.

## What version 1 does to stay ready

These cost almost nothing in the MVP and avoid reworking the phone app later:

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

**Ming-3.0-tts 0.5B is the lead candidate** for version 2: the highest-ranked model that plausibly fits a laptop GPU, with a permissive license. Breeze TTS 2 is unlikely, because the 3050 has 4 GB of VRAM and its weights are 7.7 GB.

**Phonon may matter for version 1.** It is tiny, ranks above Kokoro, and ships an [Android guide](https://huggingface.co/gradium/phonon-31m-en). It came out on 2026-10-20, after the phone voice tests. Whether it sounds better than Supertonic needs a phone sample.

## Phone precompute: not now

On 2026-11-08 you asked whether the phone should also precompute batches of chapters. A five-advisor review said no, four to one, and I agree:

- Supertonic at RTF 0.6 already stays ahead of playback. The only gap is the first-audio wait, an estimated 3 to 10 seconds, and generating the next chapter's opening sentences while the current one plays closes it with no job queue.
- The quality case needs a slower phone model that beats Supertonic by ear, and none has yet. Ming can't run on the phone, and Kokoro at RTF 1.3 for hours would heat a charging Exynos 1380 that hasn't been heat-tested.
- Keeping an overnight queue alive on One UI takes a foreground service, battery exemptions, retries and resume. That is the hardest code in the app, for a gain nobody has measured.
- The sentence-keyed audio cache means adding it later needs no redesign, so waiting costs little.

**Revisit if both hold:** you're often away from the laptop for long stretches, and a phone model clearly beats Supertonic in a listening test. If full-chapter heat turns out bad first, the smaller fix is a "prepare this chapter now" button that you start, running in the foreground and writing into the same cache.

The council's first step, and the one measurement that decides whether any of this has a job to do, is playing a full chapter of Supertonic on the A26 while logging battery, temperature and first-audio time over `adb`.

## Phonon, checked 2026-11-08

- **On the phone, it needs a source build.** The [Android guide](https://github.com/gradium-ai/xn-ptts/blob/main/android/README.md) offers no APK and no Gradle package. The library is built with Rust, `cargo-ndk` and the Android NDK, and the system-voice engine app is also built from source. It supports arm64 with dotprod and fp16, which the A26's Exynos 1380 has [unverified].
- **On Windows, a prebuilt CPU binary exists.** [ptts v0.6.0](https://github.com/gradium-ai/xn-ptts/releases/tag/v0.6.0) for Windows x64 verified against its `SHA256SUMS`. CUDA needs a source build. Running it here was blocked by a permission check, so no sample or timing exists yet.
- **It reads numbers itself.** `--lang en` turns numbers, amounts, emails and addresses into words before speaking, which `core` would otherwise have to do for sherpa-onnx.
- **Speed and pace controls:** `--threads`, and `--condition duration_delta=-0.3` to speak faster.

## Still open

- A listening test of Ming and Phonon samples made on the laptop. Installing Ming's Python runtime and running Phonon's binary were both blocked by permission checks on 2026-11-08.
- The RTX 3050's VRAM and speed, which needs the GPU visible to the benchmark.
