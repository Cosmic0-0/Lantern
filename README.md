# Lantern

Lantern is a planned personal Android app for listening to light novels like a podcast. The plan is to search for a novel by title, add it to a library, then read or listen to any chapter. Voices run on the phone at no cost, and playback should resume within one sentence of where it stopped.

The first target is a Samsung Galaxy A26, with a Windows 11 laptop later. The plan needs no account, login, or backend.

## Status

Lantern is in planning. The repo has no app code yet, so there is nothing to build or install.

The next steps are choosing a voice setup from samples on the phone, then running the three spikes in the [roadmap](PLAN.md#roadmap).

## Planned stack

These choices are recommendations until the spikes confirm them:

- **App:** native Kotlin, Jetpack Compose, and Media3. See [app type](docs/decisions/app-type.md).
- **Voices:** on-device speech through sherpa-onnx. The voice itself is still open. See [voice engine](docs/decisions/voice-engine.md).
- **Chapters:** LNReader's site plugins, run inside the app. See [chapter sources](docs/decisions/chapter-sources.md), which also covers site terms and legal risks.
- **Reading position:** saved per sentence against the text. See [position tracking](docs/decisions/position-tracking.md).

## Documents

- [PLAN.md](PLAN.md): the problem, decisions, MVP scope, roadmap, and open questions. Start here.
- [Reference review](docs/research/reference-review.md): lumina-reader and the other reader apps reviewed.
- [Voice tests](docs/research/voice-tests.md): speed measurements from the phone, and how to rerun them.
- [Site tests](docs/research/site-tests.md): the sites tested, and which ones start enabled.
- `tools/`: the scripts and raw results behind the voice and site tests.
