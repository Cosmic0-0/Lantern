# Lantern plan

Status: second draft for your approval, updated 2026-11-08 with your voice choice. Lantern itself isn't built yet.

A link means I read that source. [unverified] marks something I could not confirm. RTF means seconds of computing per second of speech, so below 1 is faster than real time.

## Where things stand

Start here when picking this up in a new chat. Last updated 2026-11-08.

The plan, reference review, site tests and phone voice tests are done. No app code exists, and you haven't approved the plan yet, so nothing gets built until you do.

**Next steps, in order:**

1. ~~Choose a voice setup.~~ Done 2026-11-08: Supertonic 3 alone, see [TTS engine](docs/decisions/voice-engine.md#your-choice).
2. Decide on the laptop precompute idea, under [open questions](#open-questions). Its go/no-go is a listening test of laptop-made audio against the phone's, which needs the sherpa-onnx tool run on the laptop. Running the downloaded tool was blocked by a permission check on 2026-11-08 and needs your approval.
3. You answer the rest of the [open questions](#open-questions).
4. You try the baseline test in Phase 0 of the [roadmap](#roadmap), if you want. It hasn't been done.
5. You approve the plan, then Phase 1 starts.

**Current setup:**

- All commits are on `master`. There is no `main` branch. Ask before every push.
- The laptop has no Android Studio, JDK or `adb` installed. For USB work, download [Platform Tools](https://dl.google.com/android/repository/platform-tools-latest-windows.zip) to a temporary folder.
- The phone has USB debugging on and the Kokoro demo app installed. The test tool and voice models I pushed to it have been removed.
- Test scripts and raw results are in `tools/`.

## Problem

You read light novels in a browser and often want to listen instead. Site TTS sounds bad and loses your place when you pause, switch apps or come back later. You also don't want to hunt across sites for a title or its chapters.

Lantern is a personal Android app. You search for any novel by title, add it to your library, browse all its chapters, then read or listen. It runs on a Galaxy A26 (SM-A266B/DS) first and a Windows 11 laptop later, at no cost.

Your answers set these constraints:

- Budget is zero.
- You use whatever site has the novel for free, so Lantern needs its own list of sites.
- Only one chapter past the current one gets loaded ahead, no more.
- The laptop is occasional and can wait.
- A Kokoro wait under 2 minutes is acceptable. Above that, a fast voice plays first and hands over to Kokoro mid-chapter.

"Never loses my place" needs a test, so this is the definition I propose: after a pause, app switch, phone call, Bluetooth drop, force stop or reboot, playback resumes within one sentence of where the audio stopped.

## Decisions

The recommended path needs no account, login or backend. The only option that would need accounts is cloud TTS, which I rejected.

- Decision 1, [app type](docs/decisions/app-type.md): native Kotlin, Jetpack Compose and Media3.
- Decision 2, [TTS engine](docs/decisions/voice-engine.md): on-device only, through sherpa-onnx.
- Decision 3, [finding and loading chapters](docs/decisions/chapter-sources.md): LNReader plugins, run inside Lantern.
- Decision 4, [position tracking](docs/decisions/position-tracking.md): the text is the source of truth and audio is a disposable cache.

## Research

- [Reference review](docs/research/reference-review.md): lumina-reader and the better references I read
- [Voice tests](docs/research/voice-tests.md): Kokoro timings from your phone, other voices, and how to repeat the tests
- [Site tests](docs/research/site-tests.md): the sites I tested for search and chapter text

## MVP scope

The smallest version I think you would use daily: search a title, add it, press Continue, and it always lands on the right sentence.

**In:**

1. In-app title search across the on-by-default sites.
2. "Add to library", a library screen and a full chapter list for each book.
3. A reader view with sentence highlight, follow mode, tap to jump and "Back to voice".
4. On-device speech with Supertonic 3 int8 at 5 steps, with voice B (`--sid=0`, female) and voice A (`--sid=5`, male) in settings. Both come in the same model download.
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
- Battery and heat over those three chapters are measured and acceptable to you.
- A real chapter from each enabled site keeps all its short dialogue lines.

**Data, stored in SQLite through Room:** `source`, `book`, `chapter` (text fetched on demand), `sentence`, `position`, `position_history` and `audio_cache`.

## Roadmap

**Phase 0: phone tests with no code.** No PC needed.

*Kokoro test, done 2026-11-07.* RTF about 1.23, with the results in [Voice tests](docs/research/voice-tests.md#measured-on-your-phone). Lantern must verify a checksum after downloading any voice model.

*Baseline test.* Install [LNReader](https://www.lnreader.app/releases), [add a plugin repository](https://www.lnreader.app/plugins), and try Kokoro as the system voice through the [sherpa-onnx engine APK](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html). That combination is [unverified]. It shows how close a free app already gets, and if it's enough, you can skip this build.

*Fast voice samples, saved to `Music/LanternVoiceTest` on your phone on 2026-11-07.* Done: on 2026-11-08 you chose Supertonic voices B and A and rated the rest lower. How to make more is in [Voice tests](docs/research/voice-tests.md#fast-voice-samples).

**Phase 1: three spikes, throwaway code.** You install Android Studio and turn on USB debugging first.

- **Spike 1, engine.** Generate a full chapter with the voice you chose, at 4 threads on any cores, from inside an app. Record RTF, heat, battery and RAM, and count skipped words on real chapters on sherpa-onnx 2.0 and the older version. Check that numbers and dates are read in English.
- **Spike 2, playback.** Run per-sentence Media3 playback with waiting on unready sentences for an hour with the screen off, including fetching the next chapter with the screen off. Then run every interruption from the MVP checklist. If this design fails, I revise [Decision 4](docs/decisions/position-tracking.md) before the MVP.
- **Spike 3, sources.** Run the AllNovelFull plugin in QuickJS on the phone: search, chapter list and chapter text. Then repeat the [site tests](docs/research/site-tests.md) through plugins. If it fails, I fall back to hand-written adapters, or to forking LNReader, and tell you which and why.

**Phase 2: MVP,** as scoped above.

**Phase 3: comfort.** Switching a book to another site when one breaks, a sleep timer, pronunciation fixes for character names and skip rules.

**Phase 4: more sources.** The visible in-app browser for blocked sites like Scribble Hub, share-from-browser with Mozilla's [Readability](https://github.com/mozilla/readability) for anything else, updating plugins from inside the app after review, and EPUB import for DRM-free files.

**Phase 5: laptop.** Compose Desktop on Windows, reusing `core`, the plugins and the sherpa-onnx Windows build. Share your place through file export first, then direct phone-to-laptop sync on the same Wi-Fi. Neither needs a server.

**Maybe later:** different voices for dialogue and narration, and word-level highlighting.

## Open questions

Decisions for you:

1. **Is the [default site list](docs/research/site-tests.md) OK?** I recommend leaving sites that object off by default. You can turn one on per site, accepting the risk under [Decision 3](docs/decisions/chapter-sources.md).
2. **Fresh build or LNReader fork?** I recommend fresh. Tell me after Phase 0 if LNReader felt close to what you want, because that could change it.
3. **English only?** I assumed yes. Other languages would change the voice comparison.
4. ~~Which voice setup?~~ Answered 2026-11-08: Supertonic 3 alone, voices B and A.
5. **Laptop precompute?** Your idea from 2026-11-08: the laptop generates higher-quality audio for batches of chapters and loads it onto the phone over USB, and the phone plays it when present. Under evaluation. It only pays off if laptop audio sounds clearly better than the phone's, so a listening test decides it before any design work. Batches would also break the one-chapter-ahead fetch limit, so that limit needs your decision.

Only your phone can answer these:

- Kokoro's speed, heat and battery use over a whole chapter
- Which fast voice you like
- Whether plugins run correctly in QuickJS on the phone
- Whether the on-by-default sites answer the phone the way they answered this PC
- Whether sentence-by-sentence WAV playback has audible gaps
- Whether Samsung leaves the playback service alone
- Whether next-chapter fetching works with the screen off
- Whether Chrome speech survives screen-off, which only matters if you want to rule it out completely
