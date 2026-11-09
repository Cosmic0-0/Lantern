# Lantern plan

Status: approved 2026-11-09. Phonon is the voice, and the laptop app and phone precompute are dropped. Phases 1 and 2 are done: the MVP is built, on your A26 and accepted. Phase 3 is next.

A link means I read that source. [unverified] marks something I could not confirm. RTF means seconds of computing per second of speech, so below 1 is faster than real time.

## Where things stand

Start here when picking this up in a new chat. Last updated 2026-11-09.

The plan, reference review, site tests and phone voice tests are done. You approved the plan on 2026-11-09, and all three Phase 1 spikes passed the same day. The MVP was built and accepted the same day. The app is in `app/`, `core/` and `sources/`. `spikes/` holds only throwaway spike code.

**Next steps, in order:**

1. ~~Choose a voice setup.~~ Done 2026-11-08: Phonon 31M with voices Everly and Trevor, which you preferred to Supertonic and which ran faster on the phone. See [TTS engine](docs/decisions/voice-engine.md#your-choice).
2. ~~Decide on the laptop precompute idea.~~ Dropped 2026-11-08, because no better model than Phonon fits the laptop's 4 GB GPU. It may return if better models come out. See [Laptop precompute](docs/decisions/laptop-precompute.md).
3. ~~Check the phone-made Phonon samples.~~ Done 2026-11-08: samples 12 and 13, made on the phone, sound the same to you as the laptop-made ones. Phone precompute was reviewed and [dropped](docs/decisions/laptop-precompute.md#phone-precompute-dropped) on 2026-11-08.
4. ~~Answer the rest of the [open questions](#open-questions).~~ Done 2026-11-09: the default site list stands, English only, and a fresh build rather than an LNReader fork.
5. The baseline test in Phase 0 of the [roadmap](#roadmap) wasn't done before approval. You can still try it.
6. ~~Approve the plan.~~ Approved 2026-11-09.
7. ~~Install Android Studio.~~ Done 2026-11-09.
8. ~~Spike 1, engine.~~ Done 2026-11-09: RTF 0.33 at 2 threads over a whole chapter, and both chapters sounded fine to you. See [Voice tests](docs/research/voice-tests.md#spike-1-phonon-inside-an-app).
9. ~~Spike 2, playback.~~ Done 2026-11-09: playback ran for about 45 minutes, mostly with the screen off, and resumed within one sentence after a kill, a force stop and a reboot. The phone call is still untested. See [Spike 2](docs/spikes/spike-2-playback.md).
10. ~~Spike 3, sources.~~ Done 2026-11-09: plugins run in QuickJS on the phone. AllNovelFull, ReadNovelFull and NovelBuddy work fully, NovelCool is dropped and Novel Bin is broken. See [Spike 3](docs/spikes/spike-3-sources.md).
11. ~~Plan the Phase 2 MVP build.~~ Written 2026-11-09: [Phase 2 plan](docs/phase-2-plan.md), with the [architecture sketch](docs/phase-2-architecture.md), the glossary in [CONTEXT.md](CONTEXT.md) and 13 decisions in `docs/decisions/`, all as I recommended. [Starting Phase 2](docs/phase-2-start.md) has the background. Phase 1 leaves two small things open: the phone call test and Wuxiaworld.
12. ~~Approve the Phase 2 plan.~~ Approved 2026-11-09.
13. ~~Build the MVP, steps 1 to 8.~~ Done 2026-11-09: the app is in `app/`, `core/` and `sources/`, and installed on your A26. Each step's tests and phone results are in the [Phase 2 plan](docs/phase-2-plan.md).
14. ~~Step 9, acceptance on your A26.~~ Accepted 2026-11-09: you said your checks were enough and the app is good. Which checks you ran isn't recorded, so treat the phone call as still untested until one comes in.
15. **Next:** update the README, uninstall the spike apps, and plan Phase 3.

**Current setup:**

- Phase 2 was merged into `master` on 2026-11-09 and isn't pushed yet. There is no `main` branch. Ask before every push.
- Build and phone driver: `tools/lantern.sh`. A fresh checkout needs `npm ci && npm run build` in `sources/js`, and `local.properties` with `sdk.dir`, `lantern.benchDir`, `lantern.modelDir` and `lantern.voicesDir` (see `app/build.gradle.kts`).
- Debug builds include two screens, for checks from `adb`: `DebugActivity` (generate a chapter and time it) and `DebugPlayerActivity` (send commands to the player).
- Android Studio is installed, with its bundled JDK and the SDK in `AppData\Local\Android\Sdk`. `adb`, Rust, the Android NDK, the Phonon source and the built `libptts_ffi.so` are in the gitignored `.bench/` folder.
- Spike code is in `spikes/`. The three spike apps (`app.lantern.spike.engine`, `.playback` and `.sources`) are installed on the phone, the first two with the Phonon model in their files folders.
- Gradle 9.6.0 is cached in `~/.gradle`, because downloads from `services.gradle.org` time out.
- The phone has USB debugging on and the Kokoro demo app installed. The voice samples are in `Music/LanternVoiceTest`.
- Test scripts and raw results are in `tools/`.

## Problem

You read light novels in a browser and often want to listen instead. Site TTS sounds bad and loses your place when you pause, switch apps or come back later. You also don't want to hunt across sites for a title or its chapters.

Lantern is a personal Android app. You search for any novel by title, add it to your library, browse all its chapters, then read or listen. It runs on a Galaxy A26 (SM-A266B/DS), at no cost.

Your answers set these constraints:

- Budget is zero.
- You use whatever site has the novel for free, so Lantern needs its own list of sites.
- Only one chapter past the current one gets loaded ahead, no more.
- Lantern is the phone app alone. A laptop app is dropped for now and may return if better voice models come out.
- Battery and heat aren't tuned or measured in version 1, as long as Lantern never runs the phone flat out. If it overheats, optimizing comes then. Decided 2026-11-08.
- English only, for both the sites and the voice. Decided 2026-11-09.
- A wait under 2 minutes before audio starts is acceptable. Phonon starts in under half a second, so this no longer shapes the design.

"Never loses my place" needs a test, so this is the definition I propose: after a pause, app switch, phone call, Bluetooth drop, force stop or reboot, playback resumes within one sentence of where the audio stopped.

## Decisions

The recommended path needs no account, login or backend. The only option that would need accounts is cloud TTS, which I rejected.

- Decision 1, [app type](docs/decisions/app-type.md): a fresh native app in Kotlin, Jetpack Compose and Media3, not an LNReader fork.
- Decision 2, [TTS engine](docs/decisions/voice-engine.md): on-device only, with Phonon's own runtime on the phone and sherpa-onnx as the fallback.
- Decision 3, [finding and loading chapters](docs/decisions/chapter-sources.md): LNReader plugins, run inside Lantern.
- Decision 4, [position tracking](docs/decisions/position-tracking.md): the text is the source of truth and audio is a disposable cache.
- Decision 5, [laptop precompute](docs/decisions/laptop-precompute.md): dropped for now. A future laptop app could generate chapters with a bigger model and load them onto the phone over USB.
- Phase 2 decisions, 2026-11-09: listed at the top of the [Phase 2 plan](docs/phase-2-plan.md). Some refine the ones above: sentence splitting, the lock screen, position scope and choosing a source.

## Research

- [Reference review](docs/research/reference-review.md): lumina-reader and the better references I read
- [Voice tests](docs/research/voice-tests.md): timings from your phone and laptop for every voice tried, and how to repeat the tests
- [Site tests](docs/research/site-tests.md): the sites I tested for search and chapter text

## MVP scope

The smallest version I think you would use daily: search a title, add it, press Continue, and it always lands on the right sentence.

**In:**

1. In-app title search across the on-by-default sites.
2. "Add to library", a library screen and a full chapter list for each book.
3. A reader view with sentence highlight, follow mode, tap to jump and "Back to voice".
4. On-device speech with Phonon 31M q8 at 2 threads ([decided](docs/decisions/generation-pacing.md)), with Everly (female) and Trevor (male) in settings. The model is 64.5 MB and each voice is 256 KB.
5. Screen-off playback with lock-screen and notification controls.
6. Position saving, resume and the undo list, shared by reading and listening.
7. Auto-advance, with only the next chapter loaded ahead.
8. Playback speed control.
9. Paste text, as a cheap way to read anything not covered by a site.

**Out, for now:** site browsing by genre or popularity, switching a book to another site, share-from-browser, skip rules, EPUB, the visible browser for blocked sites, laptop, sync, word highlighting, cloud voices, sleep timer, pronunciation fixes, bookmarks inside chapters, update checks, stats and themes beyond system light and dark.

**Done means all of these pass on your A26:**

- Searching "Ode of the Brave" finds it, and you can add it and open chapter 1.
- Three chapters in a row play with the screen off.
- Resume lands within one sentence after each of these: pause, app switch, phone call, Bluetooth drop, `adb` kill, force stop and reboot.
- Generation never runs the phone flat out: at most 4 threads, and at most one chapter ahead.
- A real chapter from each enabled site keeps all its short dialogue lines.

**Data, stored in SQLite through Room:** `source`, `book`, `chapter` (text fetched on demand), `sentence`, `position` and `position_history`. Sentence audio is files only, with no table, keyed by the chapter text's hash and the voice ([Storage](docs/decisions/storage.md)), which keeps the [laptop idea](docs/decisions/laptop-precompute.md#what-version-1-does-to-stay-ready) possible later.

## Roadmap

**Phase 0: phone tests with no code.** No PC needed.

*Kokoro test, done 2026-11-07.* RTF about 1.23, with the results in [Voice tests](docs/research/voice-tests.md#measured-on-your-phone). Lantern must verify a checksum after downloading any voice model.

*Baseline test.* Install [LNReader](https://www.lnreader.app/releases), [add a plugin repository](https://www.lnreader.app/plugins), and try Kokoro as the system voice through the [sherpa-onnx engine APK](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html). That combination is [unverified]. It shows how close a free app already gets, and if it's enough, you can skip this build.

*Voice samples, saved to `Music/LanternVoiceTest` on your phone on 2026-11-07 and 2026-11-08.* Done: you chose Phonon's Everly and Trevor over Supertonic and the rest. How to make more is in [Voice tests](docs/research/voice-tests.md#fast-voice-samples).

**Phase 1: three spikes, throwaway code.** You install Android Studio first. USB debugging is already on, and the Rust and NDK tools for building Phonon are in `.bench/`.

- **Spike 1, engine.** Build Phonon's `libptts_ffi.so` into a test app and generate a full chapter with Everly at 2 to 4 threads. Record RTF and RAM, listen for dropped or garbled words on real chapters, and check how numbers and dates are read. If Phonon can't work inside an app, fall back to Supertonic through sherpa-onnx and count its skipped words. Timed 2026-11-09: it works, at RTF 0.33. A background app gets almost no CPU, so generation must run in the foreground service.
- **Spike 2, playback.** Run per-sentence Media3 playback with waiting on unready sentences for an hour with the screen off, including fetching the next chapter with the screen off. Check that generation keeps its Spike 1 speed inside the service while other apps are open. Then run every interruption from the MVP checklist. If this design fails, I revise [Decision 4](docs/decisions/position-tracking.md) before the MVP.
- **Spike 3, sources.** Run the AllNovelFull plugin in QuickJS on the phone: search, chapter list and chapter text. Then repeat the [site tests](docs/research/site-tests.md) through plugins. If it fails, I fall back to hand-written adapters, or to forking LNReader, and tell you which and why.

**Phase 2: MVP,** as scoped above.

**Phase 3: comfort.** Switching a book to another site when one breaks, a sleep timer, pronunciation fixes for character names and skip rules.

**Phase 4: more sources.** The visible in-app browser for blocked sites like Scribble Hub, share-from-browser with Mozilla's [Readability](https://github.com/mozilla/readability) for anything else, updating plugins from inside the app after review, and EPUB import for DRM-free files.

**Maybe later:** different voices for dialogue and narration, word-level highlighting, and a laptop app. The laptop app could generate chapters with a better model than the phone can run, if one comes out that fits the laptop, or offer reading on the laptop with your place synced from the phone. See [Laptop precompute](docs/decisions/laptop-precompute.md).

## Open questions

Decisions for you:

1. ~~Is the [default site list](docs/research/site-tests.md) OK?~~ Answered 2026-11-09: yes, then trimmed after Spike 3 to AllNovelFull, ReadNovelFull and NovelBuddy, with Wuxiaworld still to test. Sites that object stay off by default, and you can turn one on per site, accepting the risk under [Decision 3](docs/decisions/chapter-sources.md).
2. ~~Fresh build or LNReader fork?~~ Answered 2026-11-09: fresh build. See [App type](docs/decisions/app-type.md#your-choice).
3. ~~English only?~~ Answered 2026-11-09: yes, English only.
4. ~~Which voice setup?~~ Answered 2026-11-08: Phonon 31M, voices Everly and Trevor.
5. ~~Laptop precompute?~~ Answered 2026-11-08: dropped for now, kept as a future possibility. See [Laptop precompute](docs/decisions/laptop-precompute.md).

Only your phone can answer these:

- ~~Whether plugins run correctly in QuickJS on the phone~~ They do, 2026-11-09
- ~~Whether the on-by-default sites answer the phone the way they answered this PC~~ Three do, NovelCool returned manga, and Novel Bin's plugin is broken on the PC too, 2026-11-09
- ~~Whether sentence-by-sentence WAV playback has audible gaps~~ No gaps you could hear, 2026-11-09
- ~~Whether Samsung leaves the playback service alone~~ It did, for about 45 minutes unplugged, 2026-11-09
- ~~Whether next-chapter fetching works with the screen off~~ It does, 2026-11-09
- Whether Chrome speech survives screen-off, which only matters if you want to rule it out completely
