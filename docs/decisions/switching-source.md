# Switching a book to another source

A book reads from one source ([Choosing a source](choosing-a-source.md)). When that site breaks or stops updating, you need to move the book to another source without losing your place.

## You choose, Lantern shows the match

From the book screen, "Switch source" searches the other sources for the book's title and shows the listings, grouped as search does. Tapping one fetches its chapter list and shows what the switch would do before anything changes:

- how many of your chapters it matches, such as "98 of 104";
- where your position lands, such as "Chapter 57: The Gate → Chapter 57 - The Gate";
- how many chapters it adds and how many are only on the old source.

You confirm or go back. Lantern doesn't switch on its own, as [Choosing a source](choosing-a-source.md) left open: chapter numbering differs enough between sites that you should see the match first.

## Matching chapters

By the chapter number in the title ("Chapter 57", "Ch. 57", "57 - The Gate"). Titles with no number, such as a prologue or a side story, match on the same normalized title. A number that appears twice on one side ("Chapter 12 part 1", "Chapter 12 part 2") matches in order. Anything else is unmatched.

## What the switch changes

In one transaction:

- The book's source and address become the new listing's.
- **A matched chapter keeps its row**, so your position, history and any text already fetched stay as they are. Only its address changes, to the new source's. Its title changes too unless its text was already fetched.
- Chapters only on the new source are added.
- Chapters only on the old source are kept if their text was fetched, since you can still read them. Ones not fetched are removed, as there is nowhere left to fetch them from.
- Chapter order follows the new source. A kept old chapter goes after the chapter that came before it on the old source.
- A position on a removed chapter moves to the start of the next chapter that remains. History entries on removed chapters are dropped.

Already-fetched text is never fetched again, so a switch never moves your position within a chapter. Switching back later works the same way.

If the new listing is already a separate book in your library, the switch is refused and Lantern says so.

## Your choice

**Decided 2026-11-09: as above, approved with the [Phase 3 plan](../phase-3-plan.md).**
