# Module structure

Lantern is three Gradle modules. The sketch, with usage and signatures, is in [Phase 2 architecture](../phase-2-architecture.md).

| Module | Kind | Holds |
|---|---|---|
| `core` | Kotlin/JVM | Domain types, text cleanup and sentence splitting, resume, history and read-ahead rules, grouping listings by title |
| `sources` | Kotlin/JVM | QuickJS plugin host, JS bundle, network rules. Returns `core` types, never JSON |
| `app` | Android | Room, the playback service with generator and Phonon, model install, Compose screens |

`core` stays free of Android so it can be tested on the JVM and reused by a laptop app later, as [App type](app-type.md) asks. `sources` is separate so plugins and network rules can be tested on the laptop against the real sites, since site breakage is the most likely failure in daily use.

Rejected: a `Narrator` state machine in `core` with Android adapters, because ExoPlayer already owns playback state and the two would drift. Also rejected: a module per feature, which adds build setup with no payoff for a personal app.

## Your choice

**Decided 2026-11-09: three modules as sketched.**
