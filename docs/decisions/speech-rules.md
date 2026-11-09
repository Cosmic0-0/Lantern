# Skip rules and pronunciation fixes

Two kinds of rule you write yourself:

- A **skip rule** is a piece of text, such as `Translator:` or `patreon.com`. A paragraph with a sentence containing it isn't spoken. Matching ignores case.
- A **pronunciation fix** is a word or name and how it should sound, such as `Xiao Yan` → `Shao Yen`. It matches whole words, ignoring case, and the longest match wins, so `Xiao Yan` beats a separate fix for `Xiao`.

Each rule is for one book or for all books.

## Applied when speaking, not when fetching

[Junk lines](junk-lines.md) are removed before sentences are stored. Skip rules and fixes work differently: the stored sentences never change, and the rules are applied each time Lantern builds the playlist or makes sentence audio.

- **Your position never moves.** Sentence numbers stay as they were stored, the rule that [Position tracking](position-tracking.md) depends on.
- **Rules work on chapters already fetched**, including the one you're in, and removing a rule undoes it.
- **The reader still shows skipped paragraphs**, dimmed, so you can see what a rule catches. A rule that catches story text shows up at once, instead of the story silently going missing.
- **A fix changes only the speech.** The reader shows the text as the site wrote it.

Rejected: applying skip rules at fetch time, as the junk filter does. It would only reach chapters fetched later, and a rule that catches too much would delete text for good.

## Effect on sentence audio

Phonon can't take phonetic spelling, so a fix is a respelling: the spoken text replaces the word before Phonon sees it.

Audio is stored under the chapter's text hash ([Storage](storage.md)). A chapter that a fix touches is stored under a key made from the text hash plus the fixes that occur in it. Adding a fix remakes the audio of the current and next chapter only if the fixed word appears in them, from the sentence playing on, as a voice change does now. A chapter no fix touches keeps its plain text hash, so the [laptop idea](laptop-precompute.md) still works for it.

Skip rules don't touch audio. A skipped sentence is left out of the playlist and read-ahead treats it as ready.

## Your choice

**Open.** I recommend the above.
