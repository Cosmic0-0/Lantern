# Phase 2 plan: the MVP

Status: approved 2026-11-09. Steps 1 to 8 done 2026-11-09, apart from the checks only you can do (listed under steps 7 and 8). Step 9, acceptance on your A26, is next.

Scope and the "done means" checklist are in [PLAN.md](../PLAN.md#mvp-scope). The module shape is in [Phase 2 architecture](phase-2-architecture.md), terms in [CONTEXT.md](../CONTEXT.md), and the decisions made for this phase are:

[Module structure](decisions/module-structure.md) · [Storage](decisions/storage.md) · [Sentence splitting](decisions/sentence-splitting.md) · [Junk lines](decisions/junk-lines.md) · [Toolchain](decisions/toolchain.md) · [Model delivery](decisions/model-delivery.md) · [Lock screen](decisions/lock-screen.md) · [Generation pacing](decisions/generation-pacing.md) · [Choosing a source](decisions/choosing-a-source.md) · [Pasted text](decisions/pasted-text.md) · [Position scope](decisions/position-scope.md) · [New chapters](decisions/new-chapters.md) · [Smaller choices](decisions/mvp-assumptions.md)

## How each step is done

- The app lives in `app/`, `core/` and `sources/` at the repo root, package `app.lantern`, with one Gradle build. `spikes/` stays as reference until the MVP passes.
- `core` and other logic are built test-first (`tdd`). After each step: `simplify`, then `code-review` against this plan.
- I write step 1 and step 7 myself, since both still involve judgment (toolchain risk, service behaviour). The other steps have their shape fixed here and go to a Sonnet subagent with exact files, signatures and what's out of scope. I read every diff before calling a step done.
- Each step ends with its phone check on the A26, measured with `adb` and the app's event log rather than estimated. A step isn't done until that passes. Anything I can't run, I say so.
- One commit per finished step. I ask before every push.
- A `tools/lantern.sh` driver (install, launch, log, pull, kill, force-stop), built from the spikes' `run.sh` patterns, grows as steps need it.

## Build order

### 1. Skeleton and toolchain

Three modules building with the versions in [Toolchain](decisions/toolchain.md): `gradle/libs.versions.toml`, Gradle 9.6.0 from the cache, `.gitattributes` keeping `gradlew` and `*.sh` as LF, `local.properties` with forward slashes. `app` has one Room entity and an empty Compose screen. `sources` has one test that evaluates `1 + 1` in quickjs-kt on the JVM.

- **Tests:** `gradlew :core:test :sources:test :app:assembleDebug` passes on the laptop. The quickjs test proves `sources` can be tested on Windows.
- **Phone check:** the app installs and opens, and the log shows the Room database was created.
- **If it fails:** Room 3.0, then KSP 2.3.10, then I ask you. If quickjs-kt won't run on the Windows JVM, `sources` becomes an Android library tested on the phone, and I tell you.
- **Result, 2026-11-09:** passed. Laptop tests and the APK build passed with no fallback needed, the QuickJS test ran on Windows, and on the A26 the app cold-started in 489 ms and logged `database ready books=0`.

### 2. Text preparation (`core`)

`TextPrep.sentences(chapterText)`: NFKC, the [junk line](decisions/junk-lines.md) filter, then the spikes' [splitter](decisions/sentence-splitting.md).

- **Tests:** the real chapters from Spike 3 are copied into `core/src/test/resources/chapters/`, which is gitignored because the texts are copyrighted and the repo has a GitHub remote. The test skips them when the folder is absent. Committed tests use one-line excerpts of each case. Every short dialogue line in them survives. The ad line and the maths-letter watermark are removed. Abbreviations (Mr., Dr., A.I., e.g.), ellipses, closing quotes, a website named inside dialogue, empty and whitespace-only input.
- **Phone check:** none, since this is pure logic. Step 6 checks it on real fetched text.
- **Result, 2026-11-09:** 20 tests pass. On the 6 real chapters the filter dropped the two "READ MORE" ad lines and stripped the maths-letter watermark, and changed nothing else. Known quirks from the spike's splitter, kept as they are:
  - A dialogue tag after a closing quote becomes its own sentence (`"Wait..." | he said.`).
  - NFKC turns `…` into `...`.
  - A sentence ending in a single capital letter (`plan B.`) is read as an initial and merged with the next sentence.

### 3. Position and read-ahead rules (`core`)

`Resume.startAt`, `History.afterJump` (last 20, no duplicates in a row), `ReadAhead.next` (first unready sentence from the playing one, then the next chapter, never further), and grouping of listings by normalized title for the search screen.

- **Result, 2026-11-09:** 36 tests pass. When grouping titles, apostrophes are dropped ("Don't" matches "Dont") and other punctuation becomes a space ("Re-Zero" matches "Re Zero").
- **Tests:** resume after 4 and 6 minutes away, resume at sentence 0, history overflow, read-ahead with gaps, with the next chapter missing, at the last chapter, and never past the next chapter. Title grouping with case, punctuation and "(Web Novel)" style suffixes.
- **Phone check:** none, since this is pure logic.

### 4. Sources (`sources`)

Port `PluginHost`, `Http` and the JS bundle from `spikes/sources`, behind the `Sources` class in the sketch. Plugins: AllNovelFull, ReadNovelFull and NovelBuddy. The User-Agent becomes `Lantern/0.1 (personal project)`.

- **Tests on the laptop:** a local HTTP server checks the network rules: another domain refused, a redirect to another domain refused, 1 s between requests to one site, a Cloudflare-style page stopping the request, a size cap, a plugin that loops forever timing out. Live tests run only with `-Plive`: "Ode of the Brave" found on AllNovelFull and NovelBuddy, 104 chapters, chapter 1 text matching the saved copy.
- **Review:** `security-review` on the plugin host and network rules.
- **Phone check:** none on its own. Step 6 runs it on the phone.
- **Result, 2026-11-09:** 32 laptop tests pass, and the 4 live tests passed in one run with `-Plive`: Ode of the Brave found on AllNovelFull and NovelBuddy, 104 or more chapters, chapter 1 over 10,000 characters, and Shadow Slave found on ReadNovelFull. Breaking each network rule on purpose made its test fail. Changes from the spike:
  - kotlinx-serialization-json replaces `org.json`.
  - The User-Agent is forced in Kotlin.
  - The JS bundle is built with `npm ci && npm run build` in `sources/js` and gitignored.
  - The plugins' MIT notice ships beside them.

  The security review found nothing blocking. Two notes for later:
  - The "own site" rule takes a domain's last two labels, which would be too loose for a site on a `.co.uk`-style suffix. None of the three sources uses one.
  - A site's own DNS could point at the phone's home network. Accepted for a personal app with reviewed plugins.

### 5. Storage and library (`app`)

The Room schema from [Storage](decisions/storage.md) and `Library`: add a listing with its chapter list, books, chapters, sentences (fetch, prepare and store once), position (saved on one background thread, with a blocking save for destroy), history, refresh (adds new chapters only), paste (a book with no source), delete.

- **Tests:** instrumented tests on the phone with an in-memory database and a fake `Sources`: sentences stored once and stable after a refresh, refresh keeps existing chapters, position and history round-trip, pasted text becomes one chapter, delete removes everything.
- **Phone check:** `connectedDebugAndroidTest` passes on the A26.
- **Result, 2026-11-09:** 18 instrumented tests pass on the A26. Two first requests for the same chapter share one fetch.

### 6. Speech and model install (`app`)

The model and both voices as uncompressed assets, copied on first launch with the size and SHA-256 check from [Model delivery](decisions/model-delivery.md). Port `PhononTTS` and `Generator`. The generator asks `ReadAhead.next` what to make and writes `cache/audio/<voice>/<text hash>/<n>.wav` through `.wav.tmp`. A sentence that fails becomes 300 ms of silence, as in Spike 2.

- **Tests:** a JVM test for the WAV writer's header. Instrumented: a corrupted copy is replaced on next launch.
- **Phone check:** a fresh install copies the model, logged with its time. A debug button fetches chapter 1 of Ode of the Brave through `Sources` and `Library` and generates it. The log shows RTF near Spike 1's 0.33 on 2 threads, and you listen to a few sentences in Everly and Trevor.
- **Result, 2026-11-09:** passed on the A26, from `DebugActivity` (debug builds only).
  - Model copy on a fresh install: 375 ms. Model load: 396 ms.
  - Chapter 1: fetched in 1.8 s as 317 sentences.
  - Generation: 20.5 minutes of audio in 7.1 minutes, RTF 0.345 overall at 2 threads, with no failed sentences.
  - 21 JVM tests pass. An interrupted sentence leaves no file, so a stop can't cache silence in place of a real sentence.
  - The listening part moves to step 7, where you hear both voices through the player.

### 7. Playback service (`app`)

`PlaybackService` and `SentenceDataSource` from Spike 2, driven by `Library` instead of the throwaway chapter code: one item per sentence with book and chapter metadata, the custom command `playFrom(position)`, saving on every sentence change, pause, focus loss, task removal and destroy, resume with `Resume.startAt`, playback resumption for the last-played book, auto-advance with a spoken chapter title, "No more chapters yet" at the end, a failed fetch said aloud, live speed, and changing voice.

- **Tests:** the rules it uses are already tested in `core`. The service itself is tested on the phone.
- **Phone check, from a debug screen, before any real UI:**
  - three chapters in a row with the screen off;
  - resume within one sentence after pause, app switch, Bluetooth headphones off, `adb` kill, force stop and reboot, each checked against the event log;
  - speed change heard live;
  - a voice change mid-chapter;
  - no ENDED state in the log.
- **Result, 2026-11-09:** passed from `adb`, driven through a MediaController by the debug-only `DebugPlayerActivity`:

  | Check | Result |
  |---|---|
  | Pause | Saved the sentence playing |
  | `adb` kill | During sentence 6, a media play key resumed at 6 without opening the app |
  | Force stop | During sentence 8, play resumed at 8 |
  | Tap to jump | The sentence jumped from went into the history |
  | Voice change mid-chapter | The first Trevor sentence took 2.1 s to make, then playback carried on |
  | Speed | Applied live and kept |
  | Auto-advance with the screen off | Chapter 1 ran into chapter 2, and chapter 3 was fetched with the screen off in 1.5 s, all as a foreground service |
  | End of the book | "No more chapters yet.", then stopped paused on the last sentence with it saved |
  | No unexpected end | The only ENDED came after that notice, as intended |

  The model check on a later start (hashing the installed 65 MB) took 201 to 450 ms.

  Two problems found and fixed:
  - Site texts open with their own heading, so the spoken chapter title was said twice. Now it's spoken only when the text doesn't start with it.
  - A command that needs a fetch was lost if the asking screen let go of the service before playback began. Now "Loading chapter." is spoken first, which keeps the service in the foreground.

  **Still needs you:** three chapters in a row with the screen off while you listen, Bluetooth headphones off, a reboot, a phone call, and hearing both voices and the speed change yourself. These move to step 9.

### 8. Screens (`app`)

Compose, system light and dark only:

- **Library:** books, Continue on the last-played book.
- **Search:** results arrive per source and are grouped by title, with the source named on each row and failures shown per source.
- **Book:** chapter list, pull-to-refresh, Continue.
- **Reader:**
  - sentence highlight and following;
  - tap to jump;
  - "Back to voice" after a manual scroll;
  - undo from the position history;
  - scrolling while paused saves the first fully visible sentence.
- **Settings:** voice and speed.
- **Paste:** pasted text becomes a book.
- Mini player controls on every screen through a `MediaController`.

- **Tests:** Compose UI tests for the reader only: tapping a sentence sends `playFrom` with the right index, and a manual scroll shows "Back to voice". The other screens are checked by use.
- **Phone check:** you search, add, read and listen for a day of normal use.
- **Result, 2026-11-09:** 26 instrumented tests pass on the A26: 19 library, 5 reader and 2 model install. A walk-through on the phone worked:
  - search grouped Ode of the Brave from AllNovelFull and NovelBuddy into one block;
  - adding gave 104 chapters;
  - tap to play, following, "Back to voice" and undo across chapters all worked;
  - scrolling while paused saved the first visible sentence;
  - Continue, pull-to-refresh, paste, delete and settings worked.

  Changes from the plan:
  - The reader shows one sentence per row, since paragraph breaks aren't stored.
  - Controls are text buttons, because the small icon set has no pause or skip icons.
  - Play after you scroll away while paused starts from where you're reading, recorded as a jump so Undo returns to the voice.

  **Still needs you:** a day of normal use.

### 9. MVP acceptance

The "done means" list from [PLAN.md](../PLAN.md#mvp-scope), run on the A26 and recorded in `docs/phase-2-results.md`:

- Searching "Ode of the Brave" finds it, and it can be added and opened at chapter 1.
- Three chapters play in a row with the screen off.
- Resume lands within one sentence after pause, app switch, phone call, Bluetooth drop, `adb` kill, force stop and reboot. The phone call still needs someone to call you.
- At most 2 threads and at most one chapter ahead, checked from the log.
- A real chapter from each enabled source keeps all its short dialogue lines.

Then the spike apps are uninstalled, the README is updated (`update-readme`) and [PLAN.md](../PLAN.md) gets "Where things stand" for Phase 3.

## Risks

| Risk | Where it shows | Fallback |
|---|---|---|
| Room's KSP on this toolchain | Step 1 | Room 3.0, then KSP 2.3.10, then ask |
| quickjs-kt on the Windows JVM | Step 1 | `sources` tested on the phone |
| A site changes and a plugin breaks | Step 4 live tests, daily use | Two other sources; site switching is Phase 3 |
| Bigger APK makes installs slow over USB | Step 6 | Push the model only on first install in debug builds |
| Phone call untested since Spike 2 | Step 9 | Media3 handles audio focus; tested when a call comes in |
