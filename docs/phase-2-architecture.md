# Phase 2 architecture sketch

Status: accepted 2026-11-09 ([Module structure](decisions/module-structure.md)). Terms follow [CONTEXT.md](../CONTEXT.md).

## Problem

The MVP joins three things the spikes proved separately: plugins in QuickJS, Phonon in a foreground service, and one Media3 item per sentence. Three constraints shape the modules:

- [App type](decisions/app-type.md) wants text splitting and position rules in a `core` with no Android imports, tested on the JVM and reusable by a laptop app later.
- Generation must run inside the playback service, because a background app gets almost no CPU ([Spike 2](spikes/spike-2-playback.md)).
- The spike's `PluginHost` and `Http` have no Android imports, so plugins and network rules could be tested on the laptop against the real sites. No such test was ever run: the spike ran only on the phone. `quickjs-kt-jvm` 1.0.15 ships a Windows x64 library, so it should work, but that's unverified until the first build step tries it.

## Usage (caller's view)

```kotlin
// Search screen: every enabled source is searched, results arrive per source.
sources.search("Ode of the Brave").collect { (sourceId, result) -> show(sourceId, result) }

// Add: Lantern stores the book and its chapter list.
val bookId = library.add(listing)

// Reader screen: text is fetched, cleaned, split and stored the first time.
val sentences: List<String> = library.sentences(chapterId)

// Play from a sentence (tap to jump, Continue, Back to voice). The service does the rest:
// read-ahead, saving the position, auto-advance.
controller.playFrom(Position(bookId, chapterId, sentence = 41))

// Reader follows the voice by watching the saved position.
library.position(bookId).collect { scrollTo(it) }
```

## Shape

Three Gradle modules:

| Module | Kind | Holds | Depends on |
|---|---|---|---|
| `core` | Kotlin/JVM | Domain types; `TextPrep` (NFKC, junk lines, sentence split); position rules (resume back-up, history); `ReadAhead` (what to make next); grouping listings by title | nothing |
| `sources` | Kotlin/JVM | Plugin host, JS bundle, network rules. Returns `core` types, never JSON | `core`, quickjs-kt |
| `app` | Android | Room, `Library`, the playback service with generator and data source, Phonon, model install, Compose screens | `core`, `sources`, Media3, Room |

`core`, all pure functions over plain data:

```kotlin
data class Listing(val sourceId: String, val path: String, val title: String, val coverUrl: String?)
data class ChapterRef(val path: String, val title: String)            // list order is chapter order
data class Position(val bookId: Long, val chapterId: Long, val sentence: Int)

object TextPrep { fun sentences(chapterText: String): List<String> }  // one paragraph per line in
object Resume { fun startAt(saved: Position, awayMs: Long): Position } // back one sentence after 5 min
object History { fun afterJump(history: List<Position>, from: Position): List<Position> } // last 20
object ReadAhead {
    /** The next sentence to make, or null. Never past the end of the next chapter. */
    fun next(playing: Position, current: ChapterPlan, next: ChapterPlan?, isReady: (Long, Int) -> Boolean): Pair<Long, Int>?
}
object Listings { fun groupByTitle(listings: List<Listing>, sourceOrder: List<String>): List<List<Listing>> }
```

`sources`, one class:

```kotlin
class Sources(plugins: List<PluginCode>, userAgent: String) {
    fun search(term: String): Flow<Pair<String, Result<List<Listing>>>>
    suspend fun chapters(listing: Listing): List<ChapterRef>
    suspend fun chapterText(sourceId: String, path: String): String   // plain text, one paragraph per line
}
```

`app`, by package: `data` (Room entities and DAOs), `library` (`Library`: add, books, chapters, sentences, position, history; hides Room, `Sources` and `TextPrep`), `speech` (`PhononTTS`, `Generator`, sentence audio files), `playback` (`PlaybackService`, `SentenceDataSource`, the one-item lock-screen player), `ui` (search, library, book, reader, settings, paste), `model` (getting the model onto the phone).

What the interfaces hide:

- `Sources` hides QuickJS, the JS bundle, plugin JSON, per-site 1 s gaps, domain checks and security-check pages. A caller sees listings, chapter refs and text, or a failure per source.
- `Library` hides that text is fetched once, cleaned, split and stored as sentences, so sentence numbers never shift after a fetch, even if the splitter changes in a later version.
- The service is the only writer of the position while playing. The reader writes it only when not playing (scrolling). The UI never touches the generator.

Deliberately not done: no shared "narrator" state machine in `core`, no dependency injection framework, no repository interfaces with one implementation, no multi-module feature split.

## Synthesis decision

The `architect` skill's multi-model arena isn't available in this setup, so I designed it twice myself. Base: candidate A (thin pure `core`, Android-heavy `app`). From candidate B I took the separate `sources` module, because plugins and network rules are worth testing on the laptop against real sites. I rejected B's core state machine (below).

## Tradeoffs accepted

- We accept the service holding real logic (playlist building, saving, auto-advance) that is tested on the phone rather than on the JVM, in exchange for not mirroring Media3's player state in a second state machine. The rules that decide anything (resume, read-ahead, history) are in `core` and tested there.
- We accept storing every sentence in SQLite, which is more rows than storing text alone, in exchange for positions that never drift.
- We accept a third Gradle module for `sources` in exchange for fast laptop tests of the riskiest outside dependency.

## Alternatives considered

- **Two modules, `core` and `app`, with sources inside `app`.** Simplest build. Lost because plugin tests would need the phone or Robolectric, and site breakage is the most likely failure in daily use.
- **Ports and adapters: a `Narrator` in `core` owning playback state, with Android adapters for audio, speech and storage.** Most JVM-testable. Lost because ExoPlayer already owns playback state (current item, buffering, focus), so `Narrator` would duplicate it and the two would drift. Shallow adapters, more code.
- **A module per feature (`search`, `library`, `reader`, `playback`).** Lost: for one person's app it adds build setup and cross-module plumbing without a payoff.

## Open questions and risks

All settled on 2026-11-09 and listed at the top of the [Phase 2 plan](phase-2-plan.md). Two risks remain open until step 1 of that plan runs: Room's KSP on this toolchain, and quickjs-kt on the Windows JVM.

## Next implementation step

The Gradle skeleton with all three modules building, then `core.TextPrep` test-first.
