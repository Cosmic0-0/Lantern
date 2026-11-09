# Lantern

Lantern is a planned personal Android app for listening to light novels like a podcast. The plan is to search for a novel by title, add it to a library, then read or listen to any chapter. Voices run on the phone at no cost, and playback should resume within one sentence of where it stopped.

It targets a Samsung Galaxy A26. A laptop app that generates chapters with a bigger voice model is dropped for now, and may return if better models come out. The plan needs no account, login, or backend.

## Status

The plan was approved on 2026-11-09, and the three Phase 1 spikes of the [roadmap](PLAN.md#roadmap) passed on the phone the same day. Phase 2, the MVP build, is next. The repo has only throwaway spike code in `spikes/`, so there is nothing to install yet.

## Planned stack

The Phase 1 spikes confirmed these choices on the phone:

- **App:** native Kotlin, Jetpack Compose, and Media3. See [app type](docs/decisions/app-type.md).
- **Voices:** on-device speech with Phonon 31M on the phone, through its own runtime. See [voice engine](docs/decisions/voice-engine.md).
- **Chapters:** LNReader's site plugins, run inside the app. See [chapter sources](docs/decisions/chapter-sources.md), which also covers site terms and legal risks.
- **Reading position:** saved per sentence against the text. See [position tracking](docs/decisions/position-tracking.md).
- **Laptop app:** dropped for now. See [laptop precompute](docs/decisions/laptop-precompute.md).

## Documents

- [PLAN.md](PLAN.md): the problem, decisions, MVP scope, roadmap, and open questions. Start here.
- [Reference review](docs/research/reference-review.md): lumina-reader and the other reader apps reviewed.
- [Voice tests](docs/research/voice-tests.md): speed measurements from the phone and laptop, and how to rerun them.
- [Site tests](docs/research/site-tests.md): the sites first tested from the PC.
- [Spike results](docs/spikes/): the playback and sources spikes on the phone. The engine spike is in Voice tests.
- [Starting Phase 2](docs/phase-2-start.md): what Phase 1 proved and what the MVP plan must decide.
- `tools/` and `spikes/`: the scripts, test apps and raw results behind the tests.
