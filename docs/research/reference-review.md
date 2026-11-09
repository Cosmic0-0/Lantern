# Reference review

These are the existing readers and plugin sets I checked before planning Lantern. I read each one and ran none of them.

- [LNReader](https://github.com/LNReader/lnreader) is an MIT-licensed React Native reader, with v2.1.4 released 2026-09-26. Its TTS is 970 lines of Kotlin around Android's `TextToSpeech`, working paragraph by paragraph on the older `MediaSessionCompat`.
- [lnreader-plugins](https://github.com/LNReader/lnreader-plugins) is MIT-licensed. Its index lists 187 English sources. Each plugin implements `searchNovels`, `parseNovel` and `parseChapter`, which is exactly the flow you described.
- [IReader](https://github.com/IReaderorg/IReader) is Apache-2.0 Kotlin for Android and Windows, runs LNReader plugins and has a sentence splitter and audio cache. It is also 3,272 Kotlin files with gamification, community features and Supabase settings, and one developer wrote 1,633 of its commits.
- [NovelDokusha](https://github.com/nanihadesuka/NovelDokusha) is GPL-3.0 Kotlin with global search and background TTS, but its README says it is no longer maintained.

**Lessons kept for Lantern:**

- Never drop a paragraph for being short. Lines like "No." and "What?!" are dialogue. On the chapter pages I sampled from five sites, 2 to 9 paragraph elements per page were 10 characters or fewer. See [text quality](../decisions/chapter-sources.md) and [junk lines](../decisions/junk-lines.md).
- Save the position per sentence, not as a scroll percentage, which shifts with font size and layout. See [position tracking](../decisions/position-tracking.md).
- Test parsers against saved pages, with no live network calls.
- Don't automate Cloudflare challenges, for the legal reasons in [Finding and loading chapters](../decisions/chapter-sources.md).
