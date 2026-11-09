# Storage

Room over SQLite holds sources, books, chapters, chapter text, sentences, positions and position history. The plan's `audio_cache` table is dropped.

- **Sentences are stored**, not split again on each open, so a sentence number never shifts after a chapter is fetched, even if the splitter changes in a later version.
- **Position saves** go through Room on one background thread, as a single small transaction. On task removal and service destroy the caller waits for the write to finish. SQLite's journal makes the write atomic, so a kill mid-write leaves the previous position. Spike 2 used `SharedPreferences.commit()`, which worked, but would split the position from its history.
- **Sentence audio is files only**, at `cache/audio/<voice>/<chapter text hash>/<n>.wav`. A finished `.wav` is its own record, as in Spike 2, and the generator writes `.wav.tmp` first, then renames. Keying by text hash and voice keeps the [laptop idea](laptop-precompute.md#what-version-1-does-to-stay-ready) possible without a table that can disagree with the disk.

## Your choice

**Decided 2026-11-09: Room for everything except sentence audio, which stays files only.**
