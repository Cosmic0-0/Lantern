# Reference review

I reviewed [lumina-reader](https://github.com/joshwmy/lumina-reader) at commit `e04fd33`, by reading the code. I did not run it.

**What it does.** It is a web novel reader for iPhone Safari, used over home Wi-Fi or Tailscale. It has FreeWebNovel search, chapter URLs, a library, resume, themes and font size.

**Architecture.** A Node 20 and Express 5 server parses pages with Cheerio and serves a vanilla JS frontend. State lives in one JSON file. Tests use `node:test` with saved HTML fixtures. It has no manifest or service worker, so it is not a PWA, and the server must be running.

**Chapter text.** `src/fetcher.js` fetches pages on the server and `src/adapters/` turns them into plain-text paragraphs. FreeWebNovel goes through Puppeteer with a stealth plugin. NovelBin goes through `puppeteer-real-browser` with `turnstile: true`, which solves Cloudflare challenges automatically.

**TTS.** There is none. A search of the source for `speechSynthesis`, `mediaSession`, `<audio` and `TTS` finds nothing. Lumina solves reading, not listening.

**Reading position.** It saves the chapter URL, scroll pixels and a whole-number scroll percentage, then restores by percentage of page height. That position shifts with font size and doesn't identify a sentence.

**Worth learning from:**

- One adapter per site, with honest capability flags
- Parser tests against saved HTML, with no live network calls
- The heading parser's cases, such as `Prologue`, `Chapter 12-1` and `Volume 2 Chapter 14`
- The canonical novel identity pipeline
- Rendering chapters as plain text, never `innerHTML`

**Do not copy:**

- `src/sanitize.js:36`, `:44`, `:51` and `:54` drop any paragraph of 10 characters or fewer. Lines like "No." and "What?!" vanish. On the chapter pages I sampled from five sites, 2 to 9 paragraph elements per page were that short, though some may be page furniture rather than dialogue.
- `public/js/reader.js:68` sends its last-chance save with `sendBeacon`, which always POSTs. `src/routes/library.js` only defines GET and PUT for progress, so that save can't land. Your friend may want to know.
- The Cloudflare automation, for the legal reasons in [Finding and loading chapters](../decisions/chapter-sources.md).

**Gaps against your goals:**

- No audio of any kind, so no screen-off playback or lock-screen controls
- Search on one site only
- Needs an always-on server, and Puppeteer can't run on a phone
- Scroll-based position
- No saved chapter text, so nothing works offline

**License.** The repo has no LICENSE file and GitHub's API reports no license. `package.json` says `"license": "ISC"`, which is npm's default value, and the README says nothing. I would not treat that as a clear grant. This plan reuses ideas and copies no code. If you ever want its code, ask your friend to add a LICENSE.

**Better references, now that search is in scope.** All were read, none were run.

- [LNReader](https://github.com/LNReader/lnreader) is an MIT-licensed React Native reader, with v2.1.4 released 2026-09-26. Its TTS is 970 lines of Kotlin around Android's `TextToSpeech`, working paragraph by paragraph on the older `MediaSessionCompat`.
- [lnreader-plugins](https://github.com/LNReader/lnreader-plugins) is MIT-licensed and was updated today. Its index lists 187 English sources. Each plugin implements `searchNovels`, `parseNovel` and `parseChapter`, which is exactly the flow you described.
- [IReader](https://github.com/IReaderorg/IReader) is Apache-2.0 Kotlin for Android and Windows, runs LNReader plugins and has a sentence splitter and audio cache. It is also 3,272 Kotlin files with gamification, community features and Supabase settings, and one developer wrote 1,633 of its commits.
- [NovelDokusha](https://github.com/nanihadesuka/NovelDokusha) is GPL-3.0 Kotlin with global search and background TTS, but its README says it is no longer maintained.
