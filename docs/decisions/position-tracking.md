# Position tracking

A link means I read that source. [unverified] marks something I could not confirm.

| Approach | Accuracy | Survives a voice or speed change | Highlight and tap to jump | Verdict |
|---|---|---|---|---|
| Scroll percentage, as in [Lumina](../research/reference-review.md) | Low, shifts with layout | Yes, but vaguely | No | Rejected |
| Time in a chapter audio file | Exact for one voice | No | Needs a timing map | Rejected, breaks when you change voice or the audio is regenerated |
| Paragraph, as in LNReader | Can rewind a minute or more | Yes | Coarse | Rejected |
| Sentence, stored against the text | Within one sentence | Yes | Yes | Recommended |
| Word | Exact | Yes | Yes | Later, if ever. Needs word timing that I couldn't confirm Phonon or sherpa-onnx provides |

**Recommendation: the text is the source of truth and audio is a disposable cache.** A position is the chapter ID, sentence index and milliseconds into that sentence. Reading and listening share it.

- **Sync.** Each sentence is one Media3 playlist item. [The docs](https://developer.android.com/media/media3/exoplayer/playlists) say transitions are seamless, items can be added during playback, and `onMediaItemTransition` fires on each change. That callback drives both the highlight and the save. An audible click between WAV items on the A26 is [unverified], so [Spike 2](../../PLAN.md#roadmap) checks it.
- **Lock screen.** A [`ForwardingSimpleBasePlayer`](https://developer.android.com/reference/androidx/media3/common/SimpleBasePlayer) shows the chapter as one item instead of hundreds of sentences. Skip buttons move one sentence.
- **Sentence not ready yet.** The player waits in a buffering state instead of ending, so the service stays alive. Android's docs say to [keep it active through interruptions under 10 minutes](https://developer.android.com/about/versions/17/changes/bg-audio). This design is untested, and [Spike 2](../../PLAN.md#roadmap) proves or kills it.
- **Saving.** A small SQLite write happens on each sentence change, pause, audio focus loss, task removal and service destroy. The most you can lose to a crash is one sentence.
- **Resume.** Playback restarts at the beginning of the saved sentence. After more than 5 minutes away, it backs up one more for context. Media3's [playback resumption](https://developer.android.com/media/media3/session/background-playback#resumption) covers headset buttons after the app was killed or the phone rebooted.
- **Reading without audio.** Scrolling saves the first fully visible sentence, so pressing play starts where you are reading.
- **Tap to jump.** Tapping a sentence plays from there. The last 20 positions are kept, so a pocket tap can be undone.
- **Following.** The text scrolls with the voice. Scrolling by hand detaches it and shows "Back to voice".
- **Auto-advance.** The next chapter's sentences join the same playlist after a spoken chapter title, with no gap that could stop the service. When that chapter starts, Lantern fetches the one after it, which happens with the screen off. [Spike 2](../../PLAN.md#roadmap) tests that. If the fetch fails, Lantern says so aloud at the end of the chapter, saves your place and stops.
- **Speed.** Playback speed is applied live by the player, so changing speed doesn't regenerate audio.
- **Sentence splitting.** `java.text.BreakIterator` in `core`, with fixes and tests for novel punctuation such as ellipses, quotes and titles. Very long sentences split at clause punctuation, which [roughly halved first-audio time](https://github.com/muxable/rtchat/issues/1500) in that benchmark.
