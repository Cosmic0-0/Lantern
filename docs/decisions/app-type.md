# App type

A link means I read that source. [unverified] marks something I could not confirm.

| Option | Screen-off audio | Lock-screen controls | Same code on laptop |
|---|---|---|---|
| PWA | Fragile. One developer [logged](https://github.com/Andrewske/music-minion-cli/pull/56) Chrome stopping its media service in a 1.5 s gap between tracks, then Android muting the tab while `<audio>` still reported playing. `speechSynthesis` keeps speaking with the screen off: [unverified] | [Media Session](https://web.dev/media-session) works with `<audio>`. With `speechSynthesis`: [unverified] | Yes, all of it |
| Native Kotlin + Media3 | [First-party service](https://developer.android.com/media/media3/session/background-playback). Android 17 docs say Media3 apps are ["not likely to be impacted"](https://developer.android.com/about/versions/17/changes/bg-audio) by its new audio rules | Automatic media notification, plus resume after the service dies or the phone reboots | No. Core Kotlin can move later to [Compose Desktop](https://kotlinlang.org/docs/multiplatform/compose-desktop-components.html), and sherpa-onnx ships a Windows JNI build |
| Capacitor | [Plugin docs](https://github.com/jofr/capacitor-media-session) call WebView audio unreliable in the background. [MDN's data](https://github.com/mdn/browser-compat-data) lists Web Speech and Media Session as unsupported in Android WebView, so audio and TTS must be native anyway | Third-party plugin | UI only. Audio and TTS would be written twice |
| React Native | LNReader's [release notes](https://www.lnreader.app/releases) say background TTS works. [Track Player v5+](https://www.rntp.dev/terms) is free for personal use | Through Track Player or a native module | React Native Windows and sherpa-onnx bindings: [unverified] |
| Flutter | [`audio_service`](https://pub.dev/packages/audio_service) uses the older `androidx.media:media:1.7.0`, not a Media3 session. [#996](https://github.com/ryanheise/audio_service/issues/996) (resuming after an interruption) and [#932](https://github.com/ryanheise/audio_service/issues/932) (Samsung pausing when locked) are still open, though a 2024 comment on #932 says Google fixed its cause in Android 13 | Yes on Android | Best here. [`sherpa_onnx`](https://pub.dev/packages/sherpa_onnx) covers Android and Windows, but `audio_service` lists no Windows support and [`just_audio`](https://pub.dev/packages/just_audio) needs an add-on there |

Browser speech has two more confirmed problems. In [MDN's compatibility data](https://github.com/mdn/browser-compat-data), `pause()` on Android acts like `cancel()`, and Chrome's `boundary` event "does not fire as expected", which breaks both pausing and highlighting. A PWA also can't read other sites' pages without a server. In-app search makes that a dealbreaker.

**Recommendation: native Kotlin, Jetpack Compose and Media3.** Your main complaint is losing your place when you pause or switch apps. Android 17 [silences audio with no error](https://developer.android.com/about/versions/17/changes/bg-audio) if an app is in the wrong lifecycle state, and the A26 is [promised six OS upgrades](https://www.gizbot.com/mobile/news/samsung-galaxy-a26-a36-a56-launched-with-6-years-of-os-updates-ip67-rating-check-price-and-specs-110693.html). Media3 is the route Google names for this, and you said the laptop can wait.

The cost is a separate Windows app shell later. To keep that cheap, the chapter model, sentence splitter and position logic go in a `core` module with no Android imports.

**Build fresh or fork?** In-app search adds a library and catalog, which existing apps already have, so I rechecked this.

| Option | Search, library, chapters | Audio and position work left | Verdict |
|---|---|---|---|
| Use LNReader as it is, with Kokoro as the system voice | Done, 187 English sources | None possible. Position stays per paragraph on the older media API | Free to try in [Phase 0](../../PLAN.md#roadmap). If you're happy, stop there |
| Fork LNReader | Done | Replace its TTS module with Media3 and sherpa-onnx, and add sentence highlighting in its WebView reader | Fallback. Brings the whole React Native and Expo toolchain, with no Windows app |
| Fork IReader | Done, plus a Windows app | Replace the older media session, add sherpa-onnx | Too much code to own. Useful for reading its plugin bridge |
| Fork NovelDokusha | Done, but sources are no longer maintained | Add sherpa-onnx and Media3 | Rejected |
| Fresh Kotlin app that runs LNReader plugins | Build ordinary Compose screens, reuse site parsing | All of it, as designed in [TTS engine](voice-engine.md) and [Position tracking](position-tracking.md) | Recommended |
| Fresh Kotlin app with hand-written adapters | Build screens and 3 to 5 parsers | All of it | Fallback if plugins fail in [Spike 3](../../PLAN.md#roadmap) |

Reliable audio and exact position are the reason to build at all, and every fork would need those rewritten. Search screens are routine, and the hard part, site parsing that keeps working, is what the plugins provide. Forking LNReader is the closest alternative. You chose a fresh build, recorded under [Your choice](#your-choice).

**Installing on your phone costs nothing.** [ADB installs are exempt](https://developer.android.com/developer-verification/guides/faq) from Google's developer verification, which goes global in 2027, and a USB install worked on your phone on 2026-11-07. A free limited-distribution account covering 20 devices is the backup. You don't need the Play Store.

**Samsung setup.** Add Lantern to "Apps that won't be put to sleep" and set its battery use to unrestricted. [dontkillmyapp](https://dontkillmyapp.com/samsung) says foreground services should work properly since One UI 6, but marks that claim unverified, so the tests in the [roadmap](../../PLAN.md#roadmap) check it.

## Your choice

**Decided 2026-11-09: a fresh build, not an LNReader fork.** You chose this before trying the Phase 0 baseline. Two results would reopen it: the baseline test in [Phase 0](../../PLAN.md#roadmap) feeling close enough to what you want, or plugins failing in QuickJS in [Spike 3](../../PLAN.md#roadmap), which would take away the free site parsing a fresh build relies on.
