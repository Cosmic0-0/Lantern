# Paragraphs

The MVP stores a chapter as a list of sentences and drops the line breaks between paragraphs, so the reader shows one sentence per row ([Phase 2 plan, step 8](../phase-2-plan.md#8-screens-app)). Phase 3's skip rules also want paragraphs, because a junk note often runs over two or three sentences.

Proposed:

- **Each stored sentence gets a paragraph number**, counted from 0 within its chapter. `TextPrep` already receives one paragraph per line, so it only has to keep the number it currently throws away.
- **The database goes from version 1 to 2 with a written migration**, the app's first. Your library now has real positions and history, so dropping and recreating the database is not an option.
- **Chapters fetched before the update keep one sentence per row.** The migration sets each old sentence's paragraph number to its own index, which shows them exactly as today. Only chapters fetched after the update get paragraphs. Your current chapter and the next one already fetched stay as they are; everything after them shows paragraphs.
- **The text hash doesn't change.** It is still the hash of the sentences, so sentence audio already made stays valid.

Rejected: re-fetching old chapters to recover their paragraphs. It costs a request per chapter you've already read, and if the site changed the text since, the sentences no longer line up with your saved position. Lantern could re-fetch, split, and keep the paragraphs only when the text hash still matches, but the chapters this would fix are mostly ones you've finished.

## Your choice

**Open.** I recommend the above.
