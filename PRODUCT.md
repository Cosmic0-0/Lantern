# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

One person, the owner, on their own Samsung Galaxy A26. They read web novels and light novels, and mostly listen to them like a podcast: on the bus, on a day out, with the screen off, sometimes reading along with the highlighted sentence.

## Product Purpose

Search for a novel by title, add it to a library, then read or listen to any chapter with an on-device voice, at no cost. Success is pressing Continue and landing on the right sentence every time, with audio that keeps going and doesn't drain the battery.

## Positioning

Free, private and offline-first: the voice runs on the phone, audio can be prepared ahead on the charger, and the place is kept per sentence across reading and listening. No account, no backend, no ads.

## Operating Context

- Listening on the move with the screen off, using lock-screen and notification controls; the app is opened to start, switch chapters or read along.
- Preparing audio on the charger overnight, or on the laptop with `tools/prepare-laptop.py`.
- Books come from sites through LNReader plugins (AllNovelFull, ReadNovelFull, NovelBuddy), or from pasted text.

## Capabilities and Constraints

- Native Kotlin, Jetpack Compose, Material 3, Media3, Room. One phone, English only, budget zero.
- Two voices, Everly and Trevor. Prepared audio is kept per voice until its chapter is passed.
- Chapters are stored one sentence per row; paragraph breaks aren't stored yet.
- Each book stores its cover image URL from the source; pasted texts have none.
- Builds run offline (`--offline`), because dl.google.com times out from the laptop.

## Brand Commitments

- Visual reference chosen by the owner on 2026-11-21: the Android app "Light Novel" by Not a simple studio (screenshots in `C:\Users\Caellum\Pictures\TempLanternInspo`). Near-black with a red accent, and a reader with its own switchable themes. Its "Pick a source" flow is explicitly not wanted; Lantern keeps title search.
- Palette chosen by the owner: Rush Red #E52620, Ink Black #20201E, Warm Ivory #F2EBDD.

## Evidence on Hand

- The owner's real library: Ode of the Brave (AllNovelFull, 112 chapters) and MTL-Masked Knight (NovelBuddy, 338 chapters). Use these for screenshots and checks.

## Product Principles

- Listening comes first: play and pause are always one tap away.
- The place is sacred: no design change may move or lose the reading position.
- Calm on battery: no decorative motion or work that costs power while listening.
