# Lantern

Lantern is a personal Android app for listening to light novels like a podcast. You search for a novel by title, add it to your library, then read or listen to any chapter. The voices run on the phone at no cost, and playback resumes on the sentence where it stopped.

It is built for one phone, a Samsung Galaxy A26. There is no account, login, or backend.

## Status

The MVP is built and installed on the A26. You accepted it on 2026-11-09. Phase 3 of the [roadmap](PLAN.md#roadmap) is next: switching a book to another site when one breaks, a sleep timer, and pronunciation fixes. What is still open is listed in [Where things stand](PLAN.md#where-things-stand).

The MVP does these things:

- Search AllNovelFull, ReadNovelFull and NovelBuddy by title, and add a book with its full chapter list.
- Read a chapter while it plays, with the spoken sentence highlighted. Tap a sentence to play from it, and undo a jump.
- Keep playing with the screen off, into the next chapter, with lock-screen and notification controls.
- Choose the voice, Everly or Trevor, and the speed.
- Paste any text and listen to it as a book.

The reader shows one sentence per row, because paragraph breaks aren't stored yet.

## Stack

- **App.** Native Kotlin, Jetpack Compose, Room and Media3, in three modules: `app`, `core` and `sources`. See [app type](docs/decisions/app-type.md) and [module structure](docs/decisions/module-structure.md).
- **Voice.** Phonon 31M runs on the phone at about three times real time on 2 threads. The model ships inside the APK. See [voice engine](docs/decisions/voice-engine.md) and [model delivery](docs/decisions/model-delivery.md).
- **Chapters.** LNReader's site plugins run inside the app in QuickJS. See [chapter sources](docs/decisions/chapter-sources.md), which also covers site terms and legal risks.
- **Reading position.** Saved per sentence against the text. See [position tracking](docs/decisions/position-tracking.md).

Pinned versions are in `gradle/libs.versions.toml`, and the reasons for them are in [toolchain](docs/decisions/toolchain.md).

## Build and install

The build runs on Windows in Git Bash, with Android Studio's JDK. The Phonon library and model are not in the repo. They live in the gitignored `.bench/` folder.

1. Build `libptts_ffi.so` with `android/build.sh` in `.bench/xn-ptts`.
2. Create `local.properties` in the repo root with `sdk.dir`, `lantern.benchDir`, `lantern.modelDir` and `lantern.voicesDir`. The comment at the top of `app/build.gradle.kts` says what each one points to.
3. Build the plugin host's JavaScript bundle:

   ```sh
   cd sources/js && npm ci && npm run build
   ```

4. Run the laptop tests, build the APK and install it on the phone over USB:

   ```sh
   tools/lantern.sh test
   tools/lantern.sh build
   tools/lantern.sh install
   ```

`tools/lantern.sh` with no command lists the rest, such as `launch` and `log`. It looks for `adb` in `.bench/`, and the `ADB` variable overrides that path.

Some `core` tests read real chapters from `core/src/test/resources/chapters/`. That folder is gitignored, because the chapters are copyrighted.

## Documents

- [PLAN.md](PLAN.md): the problem, decisions, MVP scope, roadmap and open questions. Start at "Where things stand".
- [CONTEXT.md](CONTEXT.md): the words the code and docs use.
- [Phase 2 plan](docs/phase-2-plan.md) and [architecture](docs/phase-2-architecture.md): how the MVP was built, step by step, with the phone results.
- [Decisions](docs/decisions/): one file per decision.
- [Research](docs/research/): the reference review, voice tests and site tests.
- [Spike results](docs/spikes/): the Phase 1 playback and sources spikes. The engine spike is in [voice tests](docs/research/voice-tests.md#spike-1-phonon-inside-an-app).
- `spikes/` and `tools/`: the throwaway Phase 1 test apps, scripts and raw results.
