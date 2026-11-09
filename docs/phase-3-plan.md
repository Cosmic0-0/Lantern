# Phase 3 plan: comfort

Status: approved 2026-11-09. Step 1 done 2026-11-09; step 2 is next.

Phase 3 in the [roadmap](../PLAN.md#roadmap) covers switching a book to another source when one breaks, a sleep timer, and pronunciation fixes and skip rules. Three things still open from earlier are folded in:

- **Paragraph breaks** go first (steps 1 to 3). They need the schema change, and skip rules work on paragraphs.
- **The phone call** gets a stand-in check I can run from `adb` in step 7, plus a real call in acceptance.
- **Wuxiaworld** is its own step at the end (step 9), with a go or no-go, because it's independent of the rest.

The module shape is in [Phase 3 architecture](phase-3-architecture.md), and terms are in [CONTEXT.md](../CONTEXT.md). The decisions made for this phase, all as I recommended, are:

[Paragraphs](decisions/paragraphs.md) · [Skip rules and pronunciation fixes](decisions/speech-rules.md) · [Switching source](decisions/switching-source.md) · [Smaller choices](decisions/phase-3-assumptions.md)

## Done means

All of these pass on your A26:

- **Library intact after the update.** The same books, chapters, positions and history as before, and Continue lands where it did.
- **Paragraphs.** A chapter fetched after the update shows paragraphs as the site does. Tap to jump, following, "Back to voice" and saving on scroll still land on the right sentence. Chapters fetched before the update open as they did.
- **Skip rule.** A rule for a real junk line, such as a translator note, stops it being spoken in the current and next chapter without moving your position. It's dimmed in the reader, and deleting the rule brings it back.
- **Pronunciation fix.** A fix for a name is heard on the next sentence containing it, the screen text is unchanged, and a chapter without that name keeps its audio.
- **Sleep timer.** With the screen off, 15 minutes pauses at the end of a sentence, and "end of chapter" pauses before the next chapter. Continue afterwards starts in the right place.
- **Switching source.** Moving Ode of the Brave from AllNovelFull to NovelBuddy keeps your chapter and sentence, and the chapters after it play from NovelBuddy.
- **Phone call.** Resume lands within one sentence after a real call during playback.
- **Wuxiaworld** is either a working source or recorded as off, with the reason.

## How each step is done

As in Phase 2:

- `core` and other logic are built test-first (`tdd`). After each step: `simplify`, then `code-review` against this plan.
- I write steps 1, 3, 5, 8's `Library.switchSource` and 9 myself, because each needs judgment: data safety, text layout, service behaviour, and plugin review. The other steps have their shape fixed here and go to a Sonnet subagent with exact files, signatures and what's out of scope. I read every diff before calling a step done.
- Each step ends with its phone check on the A26, measured with `adb` and the event log rather than estimated. Anything I can't run, I say so.
- One commit per finished step. I ask before every push.
- **Your data:** before the first install of a Phase 3 build, I pull `lantern.db` from the phone with `run-as` into the gitignored `.bench/`, as a backup. It holds chapter text, so it never goes into the repo.

## Build order

### 1. Schema 2 and the migration (`app`)

`SentenceEntity` gets `paragraph`, a new `RuleEntity`, database version 2, and a manual `Migration(1, 2)` as in the [architecture](phase-3-architecture.md#app). `fallbackToDestructiveMigration` is never used. Schema 2's JSON is exported and committed beside schema 1. `TextPrep` and `Library` keep compiling by writing `paragraph = idx` until step 2.

- **Tests:** an instrumented migration test on the phone covering:
  - a version 1 database built from schema 1, with a book, chapters, sentences, a position and history, migrates with every row intact;
  - each old sentence's paragraph equals its index;
  - the `rule` table accepts a rule for one book and one for all books, and deleting the book removes its rules.
- **Phone check:** the same migration run on the copy of your real database pulled from the phone, with book, chapter, sentence, position and history counts compared before and after. Then the update is installed over the old app, and Continue lands where it did.
- **If it fails:** I restore the backup with `run-as` and tell you before trying again.
- **Result, 2026-11-09:** passed, and your phone's database is now on schema 2.
  - **Backups:** two copies of `lantern.db` are in `.bench/`: `db-backup-2026-11-09` from 22:06, and `db-backup-2026-11-09b`, taken just before the migration because you'd played more in between.
  - **`room-testing` couldn't be downloaded:** `dl.google.com` timed out from this laptop, for Gradle and for `curl`. `MigrationTest` instead builds a version 1 database from the committed `schemas/1.json`, which has the same identity hash as your real database, and opens it through Room. Room runs the migration and checks every table against schema 2, the same check `MigrationTestHelper` makes.
  - **Tests:** 3 instrumented tests pass. The third ran the migration on a copy of your real database: 1 book, 104 chapters, 1,212 sentences, 1 position and 5 history entries before and after, integrity and foreign keys ok. That copy was deleted from the phone afterwards. With the rule index left out of the migration on purpose, all 3 failed with "Migration didn't properly handle: rule", so the check catches a mismatch. The 19 library and 5 reader tests still pass.
  - **Your phone:** opening the new build migrated it. The database reports schema 2, with the same counts, and your position is unchanged at chapter 3, sentence 18. Continue started at sentence 17, one back after 13 minutes away, as the resume rule says.
  - **How tests run now:** instrumented tests run through `adb install -r` and `am instrument -e class ...`, not `connectedDebugAndroidTest`, because the Gradle task can uninstall the app afterwards and wipe your library.
  - **Changes from the plan:** the rule column is `term`, since `MATCH` is an SQLite keyword.

### 2. Paragraphs in text preparation and storage (`core`, `app`, Sonnet)

`TextPrep.sentences` returns `List<Sentence>` with the paragraph number of the line each came from. Lines dropped as junk don't use up a number. `Library` stores it on fetch and paste, and `Library.chapterText(chapterId)` returns sentences with paragraphs. The skipped set is empty until step 5.

- **Tests:** the existing 20 `TextPrep` tests, adapted, still pass with the same sentences. New tests cover:
  - paragraph numbers across a dialogue chapter;
  - a junk line between two paragraphs;
  - a paragraph that splits into several sentences.

  The real chapters in `core/src/test/resources/chapters/` keep the same sentences as before. Instrumented: paste keeps paragraphs, and an old chapter (paragraph = index) reads back unchanged.
- **Phone check:** none on its own. Step 3 shows it.

### 3. Reader paragraphs (`app`)

One `LazyColumn` row per paragraph. Each row is one `Text` with an annotated span per sentence, so the highlight and tap work per sentence. Following scrolls to the paragraph, then within it if the sentence is below the fold. Saving on scroll while paused uses the first sentence whose first line is fully on screen, from the text layout.

- **Tests:** the 5 reader Compose tests pass, adapted to paragraphs, plus:
  - tapping the second sentence of a paragraph sends `playFrom` with its index;
  - an old chapter renders one sentence per row.
- **Phone check:**
  - a newly fetched chapter of Ode of the Brave shows its paragraphs;
  - tap to jump on a mid-paragraph sentence plays from it;
  - following keeps a long paragraph's spoken sentence visible;
  - scrolling while paused, then play, starts at the first visible sentence, checked in the event log.

### 4. Rules (`core`, Sonnet)

`SpeechRules` as in the [architecture](phase-3-architecture.md#core-all-pure-and-tested-on-the-laptop): `skipped`, `spoken`, `audioKey`.

- **Tests:**
  - **Skip rules:** a match in one sentence skips its whole paragraph; matching ignores case; with no rules nothing is skipped; old-style chapters (paragraph = index) skip one sentence.
  - **Fixes:** whole words only (`Yan` doesn't touch `Yanking`); ignoring case; the possessive `Xiao Yan's`; longest match first; two fixes in one sentence; a fix whose replacement contains another fix's word is applied once, not again.
  - **Audio key:** the plain text hash when no fix occurs in the chapter; changes when a fix that occurs is added, edited or removed; the same no matter what order the fixes are in.
- **Phone check:** none, since this is pure logic.

### 5. Rules in playback (`app`)

- `Library` gets rules: add, edit, delete, and the rules for a book (its own plus all-books).
- `chapterText` fills the skipped set.
- `Generator` speaks `spoken(text)` under `audioKey`, and treats skipped sentences as ready.
- `PlaybackService` leaves skipped sentences out of the playlist, moves a start position past them, and checks whether a chapter title is already spoken against the first unskipped sentence. It handles `RULES_CHANGED` by rebuilding from the sentence playing, and `PREVIEW` by saying a phrase once and then staying paused where you were.
- `DebugPlayerActivity` gets an `add-rule` command so this can be checked before the screens exist.

- **Tests:** instrumented `Library` tests for rule storage and that rules for all books apply to every book. The service is checked on the phone.
- **Phone check, from `adb`:**
  - a skip rule for a sentence ahead in the playing chapter: the log shows it never plays and the position never lands on it;
  - a fix for a name in the next 20 sentences: the log shows audio remade under the new key from the playing sentence, and a name-free next chapter keeps its old folder;
  - removing both restores the playlist;
  - resume onto a skipped sentence starts at the next one.

### 6. Rule screens (`app`, Sonnet)

- A long-press on a sentence in the reader opens a sheet:
  - "Skip lines like this", with the sentence as editable text, so you cut it down to the part that marks junk;
  - "Fix how a word sounds": tap a word or two in the sentence, type how it should sound, then Preview or Save.
- Each sheet has a "This book" or "All books" switch and shows how many sentences in this chapter the rule matches.
- A rules screen for each book, reached from the book screen, lists that book's rules and then the all-books rules, with edit and delete.

- **Tests:** a Compose test that the skip sheet shows the match count for a fixed chapter. The rest is checked by use.
- **Phone check:** you add a skip rule and a fix from the reader while listening, preview a fix, and delete both from the rules screen.

### 7. Sleep timer and the phone call stand-in (`app`)

- **Timer:** the `SLEEP` command, with the choices from [Smaller choices](decisions/phase-3-assumptions.md). The deadline goes in the session extras. The mini player has a Sleep button showing the minutes left, and tapping it again changes or cancels the timer. `DebugPlayerActivity` gets a 1-minute option.
- **Call stand-in:** `DebugPlayerActivity` gets `take-focus`, which holds transient audio focus for 20 seconds the way the dialer does, then lets it go.

- **Tests:** none on the laptop. The rule is one deadline check, run on the phone.
- **Phone check, from `adb`, screen off:**
  - the 1-minute timer pauses at the end of a sentence, and the saved position is that sentence;
  - "end of chapter" pauses before the next chapter, saved as its first sentence;
  - a timer that runs out while paused does nothing;
  - `take-focus` pauses playback and saves, and playback resumes on its own within one sentence when focus returns.

  A real phone call is still needed, in step 10.

### 8. Switching source (`core` and screen by Sonnet, `Library` by me)

- **`core`:** `ChapterMatch.number` and `ChapterMatch.plan` as in [Switching source](decisions/switching-source.md).
- **`Library`:** `previewSwitch` fetches the new chapter list and returns the plan with its counts and where your position lands. `switchSource` applies it in one transaction: renumbering through a temporary offset so the unique `(bookId, number)` index never clashes, chapter rows reused, the position moved if its chapter goes, history cleaned.
- **Screen:** "Switch source" on the book screen. It searches the other sources for the title, then shows the preview and asks you to confirm.

- **Tests:**
  - **`ChapterMatch`:** numbers in "Chapter 57", "Ch. 57: Title", "57 - Title" and "Episode 57"; a prologue matched by title; a duplicate number matched in order; a source missing chapters; a source with extra side stories; the real chapter lists of Ode of the Brave on AllNovelFull and NovelBuddy, saved as test resources (titles only).
  - **Instrumented `Library`:** a switch keeps the position's chapter row and sentence; fetched text stays; an unfetched old-only chapter goes; a position on it moves to the next chapter; the history is cleaned; refresh after a switch adds only new chapters; switching to a listing that's already a book is refused.
- **Phone check:** Ode of the Brave, read partway on AllNovelFull, switched to NovelBuddy. The preview's counts match the lists, Continue lands on the same sentence, and the next chapter's fetch goes to NovelBuddy in the HTTP log.

### 9. Wuxiaworld (`sources`, go or no-go)

What I know so far, from the cached plugin in `.bench/plugins/wuxiaworld.js`:

- It searches through `www.wuxiaworld.com/api` and fetches novels and chapters by gRPC-web from `api2.wuxiaworld.com`, both inside the own-site rule.
- My first read found no app ID or secret, unlike NovelCool.
- It marks chapters past the novel's free limit with 🔒.

Work:

1. Read the whole plugin, and check Wuxiaworld's terms and `robots.txt`.
2. If those allow it: add protobufjs to the JS bundle, write `fetchProto` (gRPC-web framing) in `host.js`, and add a base64 body mode to `Http` for binary requests and replies.
3. Leave locked chapters out of the chapter list, so a refresh adds them once they're free.

- **Tests:** laptop tests for the base64 mode and gRPC-web framing against the local test server. Live with `-Plive`: a search, a chapter list and one free chapter's text.
- **Review:** `security-review` on the `Http` and `host.js` changes.
- **Phone check:** search, add and listen to one free chapter.
- **Go or no-go:** it goes on by default only if the terms don't object and a novel you'd read has a useful number of free chapters. Otherwise it stays off and the reason goes in [Site tests](research/site-tests.md). How many chapters are free per novel is [unverified].
- **Result, 2026-11-09: no-go, nothing built.**
  - **The terms forbid it.** The [terms of service](https://www.wuxiaworld.com/terms-of-service) ban "any robot, spider, scraper, crawler, or any automated means to access or extract data from Wuxiaworld without our prior written permission". The only exception is search engine indexing. robots.txt only blocks account pages, but the terms limit it to search engines.
  - **The plugin itself was clean.** It has no credentials, app ID or impersonation. It reaches `www.wuxiaworld.com/api` and `api2.wuxiaworld.com` (gRPC-web), and marks chapters past the free limit (50 by default) as locked.
  - **Where it stands now.** Wuxiaworld is listed as off by default in [Site tests](research/site-tests.md). Under [Decision 3](decisions/chapter-sources.md), turning it on would be your choice to accept the risk, and it would need the protobuf work above first.

### 10. Phase 3 acceptance

The "done means" list above, run on the A26 and recorded in `docs/phase-3-results.md`. This time I'll ask which checks you ran so the file says so.

- The phone call needs a real call during playback. That can be someone calling you, or a call from the A26 to your own voicemail, which is usually free, but check your plan.

Then the README (`update-readme`), CONTEXT.md and [PLAN.md](../PLAN.md)'s "Where things stand" for Phase 4.

## Risks

| Risk | Where it shows | Fallback |
|---|---|---|
| The migration loses or changes library data | Step 1 | Backup pulled first, the test run on a copy of the real database, restore with `run-as` |
| Paragraph rows make following or saving on scroll less exact | Step 3 | Fall back to the first sentence of the first fully visible paragraph |
| A skip rule catches story text | Steps 5 and 6, daily use | Skipped text stays visible and dimmed, the match count shows before saving, and deleting the rule undoes it |
| A respelling sounds wrong | Step 6 | Preview before saving |
| Two sites number chapters differently | Step 8 | The preview shows the match before anything changes. Switching back re-matches |
| protobufjs needs something QuickJS lacks | Step 9 | Its reflection-only build, or Wuxiaworld stays off |
| Wuxiaworld's free chapters are too few, or its terms object | Step 9 | It stays off, with the reason recorded |
| Still no real phone call | Step 10 | The focus stand-in from step 7 covers Media3's side; the real call stays listed as open |
