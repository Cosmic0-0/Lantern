# Junk lines

Spike 3 found two kinds in 7 chapters:

- A line of its own: `READ MORE ChapterS HERE : https://beastnovels.com/` (AllNovelFull and NovelBuddy, chapter 50).
- A watermark on the same line as dialogue, in styled maths letters: `"Where's Thomas?" 𝙛𝓻𝒆𝒆𝒘𝙚𝓫𝙣𝙤𝒗𝙚𝓵.𝙘𝙤𝙢` (NovelBuddy, chapter 1). A voice reads it as gibberish.

The MVP has a minimal fixed filter in `core`, applied before splitting:

1. Unicode NFKC normalization, which turns the styled letters into plain ones.
2. Remove a bare domain token (`word.tld`, no spaces) at the end of a line.
3. Drop lines left with only a domain or URL, or a "read more … chapters" ad with a link.

Tests use the two real cases, plus guards that dialogue mentioning "Mr. Smith" or a website inside a sentence is kept. Rules you can edit stay in Phase 3.

## Your choice

**Decided 2026-11-09: the minimal filter in the MVP.**
