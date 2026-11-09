# Phase 3 architecture sketch

Status: proposed 2026-11-09, waiting for your approval. Terms follow [CONTEXT.md](../CONTEXT.md). The module shape from [Phase 2](phase-2-architecture.md) stays: `core` (pure rules), `sources` (plugins), `app` (Android).

## Problem

Phase 3 adds four things to a working app that already holds your library:

- paragraph breaks, which need a schema change;
- skip rules and pronunciation fixes;
- a sleep timer;
- switching a book to another source.

Three constraints shape them:

- **Sentence numbers never change** ([Position tracking](decisions/position-tracking.md)). Rules and switches have to work around stored sentences, never rewrite them.
- **The database now holds data you care about.** The first schema change needs a real migration, tested against a copy of your phone's database.
- **The service stays the only thing driving playback.** Rules and the timer reach the player through new session commands, the way voice changes already do.

## Usage (caller's view)

```kotlin
// Reader: sentences come with their paragraph; skipped ones are dimmed.
val text: ChapterText = library.chapterText(chapterId)       // sentences + which are skipped
text.sentences.groupBy { it.paragraph }.forEach { (_, s) -> showParagraph(s, dimmed = text.skipped) }

// Rules: add one, then tell the service so it rebuilds from the sentence playing.
library.addRule(Rule.Fix(bookId, from = "Xiao Yan", to = "Shao Yen"))
Commands.rulesChanged(controller, bookId)
Commands.preview(controller, "Shao Yen")                       // says it once; narration stays paused

// Sleep timer.
Commands.sleep(controller, Sleep.Minutes(30))                  // or Sleep.EndOfChapter, or null to cancel
controller.sessionExtras.getLong(Commands.EXTRA_SLEEP_AT)      // when it stops, for the mini player

// Switching source: preview, then apply.
val preview: SwitchPreview = library.previewSwitch(bookId, listing)   // fetches the new chapter list
library.switchSource(bookId, preview)                                 // one transaction
```

## Shape

### `core`, all pure and tested on the laptop

```kotlin
data class Sentence(val text: String, val paragraph: Int)
object TextPrep { fun sentences(chapterText: String): List<Sentence> }     // was List<String>

data class SkipRule(val text: String)                                       // case-insensitive substring
data class Fix(val from: String, val to: String)                            // whole words, longest first
class SpeechRules(skips: List<SkipRule>, fixes: List<Fix>) {
    /** Indexes of every sentence in a paragraph where any sentence matches a skip rule. */
    fun skipped(sentences: List<Sentence>): Set<Int>
    /** The text Phonon gets. */
    fun spoken(text: String): String
    /** [textHash] when no fix occurs in the chapter, otherwise a hash of it and the fixes that do. */
    fun audioKey(textHash: String, sentences: List<Sentence>): String
}

object ChapterMatch {
    fun number(title: String): Int?                                          // "Chapter 57", "Ch. 57", "57 - ..."
    /** The chapter list after a switch, in the new source's order. */
    fun plan(old: List<OldChapter>, new: List<ChapterRef>): SwitchPlan
}
data class OldChapter(val title: String, val fetched: Boolean)
data class SwitchPlan(val rows: List<Row>, val removed: List<Int>) {         // removed: old indexes
    sealed interface Row {
        data class Matched(val old: Int, val new: Int) : Row
        data class Added(val new: Int) : Row
        data class KeptOld(val old: Int) : Row
    }
}
```

`ReadAhead` doesn't change: the generator passes `isReady || skipped` as its `isReady`.

### `sources`

Unchanged for the first eight steps. For Wuxiaworld (step 9): `fetchProto` in `host.js` using protobufjs, gRPC-web framing, and a base64 body mode in `Http` for binary requests and replies. Chapters the plugin marks as locked (a trailing 🔒) are left out of `chapters()`.

### `app`

**Data, version 2**, with `Migration(1, 2)`:

```sql
ALTER TABLE sentence ADD COLUMN paragraph INTEGER NOT NULL DEFAULT 0;
UPDATE sentence SET paragraph = idx;          -- old chapters keep one sentence per row
CREATE TABLE rule (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  bookId INTEGER REFERENCES book(id) ON DELETE CASCADE,   -- null: all books
  kind TEXT NOT NULL,                                     -- 'skip' or 'fix'
  match TEXT NOT NULL,
  replacement TEXT,                                       -- fixes only
  createdAt INTEGER NOT NULL
);
CREATE INDEX index_rule_bookId ON rule(bookId);
```

The exact SQL comes from Room's exported schema 2, so the migrated and fresh databases are identical.

**`Library`** gains `chapterText(chapterId): ChapterText` (sentences with paragraphs plus the skipped set), `rules(bookId)` and add, edit and delete, `previewSwitch` and `switchSource`. `sentences(chapterId)` stays for callers that need only the text.

**`Generator`** reads the book's `SpeechRules` when the chapter changes or it's told rules changed. It speaks `rules.spoken(text)` and writes under `rules.audioKey(...)`. The cache cleanup keeps those keys.

**`PlaybackService`**:

- builds items only for unskipped sentences (media ids keep the real index);
- moves a start position forward past skipped sentences;
- handles new commands: `RULES_CHANGED` (rebuild from the sentence playing, as `SET_VOICE` does), `PREVIEW` (say a phrase, then stay paused where you were), `SLEEP`;
- publishes the sleep deadline in the session extras.

When the timer runs out, it sets `pauseAtEndOfMediaItems` so the sentence finishes, then clears it on the pause. "End of chapter" pauses on the transition into the next chapter's first item.

**UI:**

- The reader renders one row per paragraph, highlighting and tapping sentences inside it.
- A long-press sheet in the reader offers "Skip lines like this" and "Fix how a word sounds".
- A rules screen for each book, with the all-books rules below.
- The mini player has a sleep button and shows the time left.
- The book screen has a "Switch source" flow.

What the interfaces hide:

- `SpeechRules` hides matching. The service and generator only ask what's skipped, what to say and where the audio lives.
- `Library.switchSource` hides renumbering, row reuse and history clean-up behind one call that either fully happens or doesn't.
- Screens never touch the generator. They send commands, as before.

Deliberately not done:

- No rule language: substring and whole word only, no regular expressions.
- No per-source rules.
- No automatic source switching.
- No lock-screen sleep button.
- No re-fetching old chapters to recover paragraphs.

## Tradeoffs accepted

- We accept applying rules on every playlist build and every sentence made, instead of once at fetch time, in exchange for rules that work on chapters already fetched, can be undone, and never move a position.
- We accept old chapters showing one sentence per row in exchange for a migration that needs no network and can't break a position.
- We accept a fix remaking the current chapter's audio from the playing sentence on, a short wait like a voice change, instead of tracking audio per sentence.
- We accept that the sleep timer is tested only on the phone, as the rest of the service is, since its rule is a single deadline check.

## Alternatives considered

- **Skip rules at fetch time, in `TextPrep`.** Simplest, and the reader would never see junk. Lost because it can't reach stored chapters and a bad rule loses text for good.
- **One audio key per sentence**, so a fix remakes only the sentences containing the word. Saves a little generation. Lost: it adds a key per item and a cleanup that has to list sentences, for a wait that's usually a couple of seconds.
- **A chapter-level `sourceId`**, so old chapters could still be fetched from the old source after a switch. Lost: you switch because the old source broke, so there is nothing to fetch from.

## Open questions and risks

Settled by your approval of the [Phase 3 plan](phase-3-plan.md). Risks are listed there.

## Next implementation step

Pull a copy of `lantern.db` from the A26, then write schema 2 and its migration, with a migration test run against that copy.
