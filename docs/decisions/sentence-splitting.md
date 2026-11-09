# Sentence splitting

[Position tracking](position-tracking.md) planned `java.text.BreakIterator` with fixes. The MVP keeps the spikes' splitter instead: a regex that ends a sentence after `. ! ? …` plus any closing quotes, and an abbreviation list (Mr., Dr., A.I. and so on). It worked on real chapters in Spikes 1 and 2. `BreakIterator` behaves differently across Android versions and the JVM, which would make `core` tests unreliable.

Very long sentences are not split at clause punctuation in the MVP. That would only shorten the wait after a jump into a very long sentence, which at RTF 0.3 is rare and short.

## Your choice

**Decided 2026-11-09: the spikes' regex splitter, moved to `core` and tested against saved real chapters. No long-sentence splitting.**
