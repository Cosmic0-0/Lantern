# Lantern

A personal Android app for reading and listening to web novels, where your place is kept to the sentence.

## Language

### Finding books

**Source**:
A site Lantern can read novels from, together with the plugin that knows how to read it.
_Avoid_: provider, site (in code), plugin (the plugin is only the source's code)

**Listing**:
A novel as one source shows it: its title, cover and address on that source, before or after it is added.
_Avoid_: search result, novel item

**Library**:
The books you have added.

**Book**:
A novel in your library, read from exactly one source.
_Avoid_: novel, title, series

**Chapter**:
One part of a book, in the order its source lists them.

**Chapter text**:
The story text of a chapter after junk lines are removed, fetched when first needed and then kept.
_Avoid_: content, body, HTML

**Junk line**:
A line in a chapter page that isn't part of the story, such as an ad or a site watermark.
_Avoid_: noise, spam

### Reading and listening

**Sentence**:
The smallest piece of chapter text Lantern reads, highlights and speaks. A chapter's text is an ordered list of sentences.
_Avoid_: line, segment, utterance

**Position**:
Where you are in a book: a chapter and a sentence in it. Reading and listening share one position per book.
_Avoid_: progress, place, bookmark

**Position history**:
The recent positions in a book you jumped away from, kept so a jump can be undone.
_Avoid_: undo stack

**Voice**:
One of the speakers the speech engine can use, such as Everly or Trevor.

**Sentence audio**:
The spoken audio of one sentence in one voice. It can always be made again, so it is never the record of anything.
_Avoid_: clip, track, audio file

**Read-ahead**:
Fetching chapter text and making sentence audio ahead of the position, never past the end of the next chapter.
_Avoid_: prefetch, preload, buffer

**Following**:
The reader scrolling itself to keep the spoken sentence in view. Scrolling by hand stops it until you press "Back to voice".
_Avoid_: auto-scroll, sync
