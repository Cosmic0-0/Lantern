# Starting Phase 2: the MVP

Written 2026-11-09, when Phase 1 finished. Read this after "Where things stand" in [PLAN.md](../PLAN.md). The MVP scope and its "done means" checklist are in [PLAN.md](../PLAN.md#mvp-scope).

## What Phase 1 proved

| Part | Result | Details |
|---|---|---|
| Voice | Phonon 31M runs inside an app at RTF 0.33 on 2 threads, steady over a whole chapter. The model loads in 0.2 to 0.4 s and peak RAM is about 550 MB. | [Voice tests](research/voice-tests.md#spike-1-phonon-inside-an-app) |
| Playback | One Media3 item per sentence, with a data source that waits for unready sentences, ran about 45 minutes with the screen off. It resumed within one sentence after a kill, a force stop and a reboot. | [Spike 2](spikes/spike-2-playback.md) |
| Generation | Must run inside the foreground playback service. A background app gets almost no CPU, and inside the service generation keeps full speed with the screen off or other apps open. | [Spike 2](spikes/spike-2-playback.md) |
| Sources | LNReader plugins run in QuickJS through quickjs-kt. AllNovelFull, ReadNovelFull and NovelBuddy work fully. | [Spike 3](spikes/spike-3-sources.md) |

## Code worth carrying over

The spikes are throwaway, but these parts worked on the phone and are the best starting points:

- **Phonon:** `spikes/engine/src/main/kotlin/.../PhononTTS.kt`, the xn-ptts wrapper through JNA. Build `libptts_ffi.so` from `.bench/xn-ptts`, as described in [Voice tests](research/voice-tests.md#spike-1-phonon-inside-an-app). Set `RAYON_NUM_THREADS` before the first model load.
- **Playback:** in `spikes/playback/`, `SentenceDataSource.kt` (wait for the file, rethrow an interrupt as `InterruptedIOException`), `Generator.kt` (making sentences in order and keeping one chapter ahead) and `PlaybackService.kt` (the session, resumption and when to save).
- **Sources:** `spikes/sources/js/` (the esbuild bundle of `cheerio/slim`, `htmlparser2`, `dayjs`, core-js's `URL` and `atob`, plus `host.js` with LNReader's loader contract), and `Http.kt`, which holds the network rules: a plugin reaches only its own site, there's 1 s between requests to a site, and a security-check page stops the request.
- **Drivers:** each spike's `run.sh` shows the `adb` patterns that work from Git Bash.

## Decisions the MVP plan has to make

These are open. Each needs a recommendation and your answer before code is written:

1. **Module structure.** [App type](decisions/app-type.md) wants a `core` module with no Android imports (chapter model, sentence splitter, position logic), so it can be tested on the JVM and reused later.
2. **Storage.** The plan says Room and SQLite. Spike 2 used `SharedPreferences.commit()` for the position, which was enough for the tests.
3. **Sentence splitting.** The plan says `BreakIterator` with fixes for novel punctuation. The spikes used a regex plus an abbreviation list, which worked on real chapters.
4. **Junk lines.** Spike 3 found an ad line, and a watermark disguised in styled maths letters that a voice reads as gibberish. Skip rules are planned for Phase 3, but a minimal filter (Unicode NFKC normalization, then dropping lines that are only a domain or a "read more" ad) may belong in the MVP.
5. **Kotlin version.** quickjs-kt 1.0.15 needs Kotlin 2.4, and AGP 9.5.1's built-in Kotlin is 2.2.10. Spike 3 raised the Kotlin Gradle plugin to 2.4.10. Pick versions once for the whole app.
6. **Getting the model onto the phone.** The spikes pushed it with `adb`. The plan says to download it with a checksum check ([Voice tests](research/voice-tests.md#kokoro-test-done-2026-11-07)), or it could ship inside the APK, which adds 65 MB.
7. **Lock screen.** A `ForwardingSimpleBasePlayer` to show the chapter as one item, which the spikes left out.
8. **Bursts of generation.** Spike 2 made the rest of the chapter, and the whole next one, within minutes of each chapter starting. That's within the one-chapter-ahead limit, but it may need pacing if heat becomes a problem.
9. **Choosing a site when adding a book.** The plan says to pick the site listing the most chapters. Spike 3 showed the same book on AllNovelFull and NovelBuddy with identical text.

Small things still open from Phase 1, neither of which changes the design:

- **The phone call test.** Do it whenever a call comes in during playback.
- **Wuxiaworld.** Its plugin needs `fetchProto`, so protobufjs would have to be added to the bundle.

## Environment problems to know

- **Gradle downloads.** Downloads from `services.gradle.org` timed out. Gradle 9.6.0 is cached in `~/.gradle/wrapper/dists`, and a new project should use that version, or the download will fail again.
- **Paths for adb.** In Git Bash, set `MSYS_NO_PATHCONV=1` for phone paths and convert laptop paths with `cygpath -w` before giving them to `adb.exe`.
- **`local.properties`** needs forward slashes in `sdk.dir`.
- **Line endings.** Git's `core.autocrlf` is on. Each project needs a `.gitattributes` keeping `gradlew` and `*.sh` as LF.
- **WAVs copied with `adb shell cp`** stay hidden from music apps until you send `MEDIA_SCANNER_SCAN_FILE`.
- **Timing runs.** Keep the app on screen, or inside a foreground service, during any timing run.
- **The phone.** It has the three spike apps installed (`app.lantern.spike.engine`, `.playback`, `.sources`). They can be uninstalled once the MVP replaces them.

## Skills to use

Skills available in Claude Code:

| When | Skill |
|---|---|
| Stress-testing the MVP plan before it's approved | `grilling` |
| Naming the domain (book, chapter, sentence, position, source) and writing `CONTEXT.md` and decision records | `domain-modeling` |
| Building `core` and other logic test-first, with integration tests | `tdd` |
| Keeping each piece as small as it can be | `ponytail`, and `simplify` after a piece is written |
| Reviewing each finished piece against the plan | `code-review` |
| Reviewing the plugin host and network rules | `security-review` |
| Before calling anything done: run it on the phone | `principle-prove-it-works` |
| Editing the README | `update-readme` |
| Any prose: docs, commit messages | `unslop` |

These are also in `~/.claude/skills` and can be started by name (`/name`):

- `architect`: sketch modules and signatures before code.
- `grill-with-docs`: a stress-test interview that writes decision records as it goes.
- `implement`: build from a spec.
- `technical-writing`: the documentation standard that `update-readme` refers to.

`impeccable`, `mobile-native` and the other design skills target web interfaces, so they help only with general UI judgment for Compose screens.

## Suggested way to start the new chat

1. Read [PLAN.md](../PLAN.md) "Where things stand", then this file.
2. Use `domain-modeling` and `architect` to propose the module structure and the domain names.
3. Use `grilling` to settle the open decisions above, recording each one in `docs/decisions/`.
4. Write the Phase 2 plan, with build order, tests and phone checks for each piece, and get approval before any app code.
