# Prepared audio

Decided with you in chat on 2026-11-20. It reverses [phone precompute: dropped](laptop-precompute.md#phone-precompute-dropped) from 2026-11-08 and the one-chapter-ahead limit.

**Why:** on 2026-11-20 you listened for several hours on a bus. It worked, but the battery drain would be too much for a full day out. Playback made each sentence's audio just before it was needed, so Phonon ran about a third of the time you listened (RTF 0.33). Making audio ahead, ideally on the charger, takes that work off the battery. The 2026-11-08 decision assumed battery wasn't a concern, and the bus trip showed it is.

## What was decided

- **Prepare audio, per book.** On the book screen you choose the next 5, 10 or 25 chapters, or the rest of the book, counted from your position. The job fetches every chapter's text first, then makes all the audio in order.
- **Battery is your call.** If the phone isn't charging, a warning says how long the processor will be busy and asks before starting.
- **Read-ahead while listening is 5 chapters,** up from 1. Playback fetches text and makes audio up to 5 chapters past the playing one, then waits. Finishing a chapter opens room for one more.
- **One kind of making at a time.** Preparing waits while you listen, since listening makes its own audio, and carries on when you stop. Every sentence is made under one shared lock, so the two never run the engine at once.
- **Audio is kept until you pass it.** Audio is kept for every chapter from each book's position onward, in the current voice. Chapters behind your position, other voices and unused phrases are deleted. Before, only the current and next chapter were kept.
- **Heat guard.** Changed on 2026-11-21: it reads Android's thermal status instead of the battery temperature. Making holds back from "moderate" until the status is back to "light" or below. While it holds back, preparing pauses, and playback's read-ahead makes nothing while playback is paused and only the playing chapter and the next one while you listen, so playback never stalls. The first version paused at 40 °C battery and carried on at 37 °C; see below for why it was replaced.
- **A foreground job with a notification** that shows progress and has a Cancel button, run by WorkManager. A background app gets almost no CPU on the A26 (Spike 1). It uses the `specialUse` service type, because `dataSync` and `mediaProcessing` stop after 6 hours a day on Android 15 and later, less than one night.
- **Changing voice asks first.** It deletes prepared audio, so a pop-up says how many chapters and how much space would be lost, and needs a ticked "I understand" before it changes.
- **WAV stays,** about 47 MB per 16-minute chapter. The rest of Ode of the Brave is about 4.2 GB, with 47 GB free on 2026-11-20. Compressed audio would be about 10 times smaller, but is left for later.
- **The laptop stopgap** is `tools/prepare-laptop.py`. It makes audio with the Windows build of the same Phonon version (v0.6.0, q8, same voice file) and pushes it into the phone's cache under the same names. It goes from the last chapter backwards, so it meets the phone's own prepare job in the middle, and it refuses books with rules. Since 2026-11-21 it reads everything it needs from the phone once at the start, so the phone can be unplugged while it makes audio; it copies each chapter over whenever the phone is plugged in, and at the end waits for the phone to copy the rest. It runs 2 sentences at once by default, since 4 ran the laptop out of memory.

## Measured on 2026-11-20

- The laptop made one 5.6 s sentence at RTF 0.21 with 4 threads and 0.17 with 8. Each run also loads the model, which takes about 0.4 s. Its WAV is 16-bit 24 kHz mono, the same as the phone's.
- Ode of the Brave: chapters average about 280 sentences, 16 minutes of audio and 47 MB.

## Overnight run, 2026-11-20 to 21

- The phone prepared chapters 15 to 19, then the heat guard paused at 00:25 (40 °C) and never resumed. On the charger the battery stayed at 40 to 41 °C until full at 03:48, then about 39 °C with nothing running, so 37 °C was never reached. Android's thermal status was "light" at that point. That is why the guard now uses the thermal status.
- Playback's read-ahead ignored the guard and made chapter 20 after the pause, which is why read-ahead now obeys it too.
- The laptop made chapters 46 to 50 and 84 to 112 (about 100 s per chapter) before Claude Code stopped it for low memory at 01:20. No sentence failed on either side.

## Finishing the book, 2026-11-21

- By 11:38 all 98 chapters from your position (15 to 112) had audio on the phone, 5.0 GB in all. The phone's prepare job made chapters 21 to 23 and finished with "98 chapters ready"; the laptop made the rest. No sentence failed.
- **The new heat guard works on the charger.** Thermal status went from "light" to "moderate" after about 1 to 8 minutes of making, at 38 to 39 °C battery. Preparing then paused for 2 to 7 minutes (once 28) and carried on at "light". It made audio at RTF 0.29 while running. Playback's hot limit wasn't triggered, since nobody was listening.
- **On battery the phone made audio about 10 times slower.** From 09:04 to 11:24 the phone was unplugged, and the prepare job ran at RTF 2.9 to 6 with thermal status mostly "none", with the screen on or off. On the charger it was 0.29. Playback on the bus on 2026-11-20 ran at about 0.33 on battery, so this looks specific to the prepare job off the charger, maybe a CPU limit Samsung puts on a background app's foreground service. Not yet explained: worth a test before relying on preparing away from the charger.
