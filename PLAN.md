# Lantern plan

Status: second draft for your approval, updated 2026-11-07 with measurements from your phone. Lantern itself isn't built yet.

A link means I read that source. [estimate] marks my own calculation. [unverified] marks something I could not confirm. RTF means seconds of computing per second of speech, so below 1 is faster than real time.

## Problem

You read light novels in a browser and often want to listen instead. Site TTS sounds bad and loses your place when you pause, switch apps or come back later. You also don't want to hunt across sites for a title or its chapters.

Lantern is a personal Android app. You search for any novel by title, add it to your library, browse all its chapters, then read or listen. It runs on a Galaxy A26 (SM-A266B/DS) first and a Windows 11 laptop later, at no cost.

Your answers set these constraints:

- Budget is zero.
- You use whatever site has the novel for free, so Lantern needs its own list of sites.
- Only one chapter past the current one gets loaded ahead, no more.
- The laptop is occasional and can wait.
- A Kokoro wait under 2 minutes is acceptable. Above that, a fast voice plays first and hands over to Kokoro mid-chapter.

"Never loses my place" needs a test, so this is the definition I propose: after a pause, app switch, phone call, Bluetooth drop, force stop or reboot, playback resumes within one sentence of where the audio stopped.

## Reference review

I reviewed [lumina-reader](https://github.com/joshwmy/lumina-reader) at commit `e04fd33`, by reading the code. I did not run it.

**What it does.** It is a web novel reader for iPhone Safari, used over home Wi-Fi or Tailscale. It has FreeWebNovel search, chapter URLs, a library, resume, themes and font size.

**Architecture.** A Node 20 and Express 5 server parses pages with Cheerio and serves a vanilla JS frontend. State lives in one JSON file. Tests use `node:test` with saved HTML fixtures. It has no manifest or service worker, so it is not a PWA, and the server must be running.

**Chapter text.** `src/fetcher.js` fetches pages on the server and `src/adapters/` turns them into plain-text paragraphs. FreeWebNovel goes through Puppeteer with a stealth plugin. NovelBin goes through `puppeteer-real-browser` with `turnstile: true`, which solves Cloudflare challenges automatically.

**TTS.** There is none. A search of the source for `speechSynthesis`, `mediaSession`, `<audio` and `TTS` finds nothing. Lumina solves reading, not listening.

**Reading position.** It saves the chapter URL, scroll pixels and a whole-number scroll percentage, then restores by percentage of page height. That position shifts with font size and doesn't identify a sentence.

**Worth learning from:**

- One adapter per site, with honest capability flags
- Parser tests against saved HTML, with no live network calls
- The heading parser's cases, such as `Prologue`, `Chapter 12-1` and `Volume 2 Chapter 14`
- The canonical novel identity pipeline
- Rendering chapters as plain text, never `innerHTML`

**Do not copy:**

- `src/sanitize.js:36`, `:44`, `:51` and `:54` drop any paragraph of 10 characters or fewer. Lines like "No." and "What?!" vanish. On the chapter pages I sampled from five sites, 2 to 9 paragraph elements per page were that short, though some may be page furniture rather than dialogue.
- `public/js/reader.js:68` sends its last-chance save with `sendBeacon`, which always POSTs. `src/routes/library.js` only defines GET and PUT for progress, so that save can't land. Your friend may want to know.
- The Cloudflare automation, for the legal reasons under Decision 3.

**Gaps against your goals:**

- No audio of any kind, so no screen-off playback or lock-screen controls
- Search on one site only
- Needs an always-on server, and Puppeteer can't run on a phone
- Scroll-based position
- No saved chapter text, so nothing works offline

**License.** The repo has no LICENSE file and GitHub's API reports no license. `package.json` says `"license": "ISC"`, which is npm's default value, and the README says nothing. I would not treat that as a clear grant. This plan reuses ideas and copies no code. If you ever want its code, ask your friend to add a LICENSE.

**Better references, now that search is in scope.** All were read, none were run.

- [LNReader](https://github.com/LNReader/lnreader) is an MIT-licensed React Native reader, with v2.1.4 released 2026-09-26. Its TTS is 970 lines of Kotlin around Android's `TextToSpeech`, working paragraph by paragraph on the older `MediaSessionCompat`.
- [lnreader-plugins](https://github.com/LNReader/lnreader-plugins) is MIT-licensed and was updated today. Its index lists 187 English sources. Each plugin implements `searchNovels`, `parseNovel` and `parseChapter`, which is exactly the flow you described.
- [IReader](https://github.com/IReaderorg/IReader) is Apache-2.0 Kotlin for Android and Windows, runs LNReader plugins and has a sentence splitter and audio cache. It is also 3,272 Kotlin files with gamification, community features and Supabase settings, and one developer wrote 1,633 of its commits.
- [NovelDokusha](https://github.com/nanihadesuka/NovelDokusha) is GPL-3.0 Kotlin with global search and background TTS, but its README says it is no longer maintained.

## Available tooling

This comes from what this session lists. I can see plugin skills by their `anthropic-skills:` and `unslop:` prefixes, but no tool lists installed plugins. Skills listed without a description are judged by name only.

| Group | Available | Planning | Building |
|---|---|---|---|
| Core tools | Read, Write, Edit, Glob, Grep, Bash, PowerShell, Skill, ToolSearch, AskUserQuestion, Agent, ListAgents, Artifact, ReportFindings, ScheduleWakeup, SendFeedback | Read, Grep, Bash, AskUserQuestion | Everything for files and commands |
| Loaded on demand | WebSearch, WebFetch | Used for all claims here | Library docs, bug lookups |
| Available, not loaded | Monitor, TaskStop, SendMessage, EnterPlanMode, ExitPlanMode, EnterWorktree, ExitWorktree, NotebookEdit, CronCreate, CronList, CronDelete, RemoteTrigger, PushNotification, DesignSync, ArtifactComments, ArtifactData, ListMcpResourcesTool, ReadMcpResourceTool, ReadMcpResourceDirTool, EndConversation | EnterPlanMode | Monitor for long Gradle builds, worktrees for spikes |
| Subagents | `claude`, `general-purpose`, `Explore`, `Plan`, `claude-code-guide`, `statusline-setup` | `Plan`, `Explore`, not needed so far | `general-purpose` on Sonnet or Haiku for tasks with a decided structure, per your workflow |
| MCP servers | Claude Docs, Figma, IDE (`getDiagnostics`, `executeCode`) | Figma for optional mockups | IDE diagnostics if run inside an IDE. Claude Docs isn't needed |
| Skills for planning | `grilling`, `llm-council`, `domain-modeling`, `anthropic-skills:deep-research`, `unslop` | Stress-test this plan, record decisions | `domain-modeling` for later decision records |
| Skills for building | `tdd`, `principle-prove-it-works`, `code-review`, `security-review`, `simplify`, `ponytail`, `update-readme`, `run` | | Core logic tests, proof before done, reviews |
| Skills for UI | `mobile-native`, `impeccable`, `frontend-design`, the design and animation skills | | Screen polish. Most lean toward web, so `mobile-native` needs reading first |
| Browser skills | `claude-in-chrome`, `anthropic-skills:chrome-browser` | | Saving real site pages as test fixtures through your own Chrome |
| No use here | `write-swift`, `apple-design`, `animate-expo`, the Office and PDF skills, the Figma skills unless you want mockups | | |

**What I can't do from here.** I have no Android device or emulator tool. A check of this PC found `node` and `npm`, but no `adb`, `java`, Kotlin, Gradle, Flutter or Android SDK. I can write Kotlin but can't compile it until you install Android Studio. I can only test on the A26 once it is connected with USB debugging. You will have to do the screen-off listening tests yourself.

## Decisions

The recommended path needs no account, login or backend. The only option that would need accounts is cloud TTS, which I rejected.

### Decision 1: App type

| Option | Screen-off audio | Lock-screen controls | Same code on laptop |
|---|---|---|---|
| PWA | Fragile. One developer [logged](https://github.com/Andrewske/music-minion-cli/pull/56) Chrome stopping its media service in a 1.5 s gap between tracks, then Android muting the tab while `<audio>` still reported playing. `speechSynthesis` keeps speaking with the screen off: [unverified] | [Media Session](https://web.dev/media-session) works with `<audio>`. With `speechSynthesis`: [unverified] | Yes, all of it |
| Native Kotlin + Media3 | [First-party service](https://developer.android.com/media/media3/session/background-playback). Android 17 docs say Media3 apps are ["not likely to be impacted"](https://developer.android.com/about/versions/17/changes/bg-audio) by its new audio rules | Automatic media notification, plus resume after the service dies or the phone reboots | No. Core Kotlin can move later to [Compose Desktop](https://kotlinlang.org/docs/multiplatform/compose-desktop-components.html), and sherpa-onnx ships a Windows JNI build |
| Capacitor | [Plugin docs](https://github.com/jofr/capacitor-media-session) call WebView audio unreliable in the background. [MDN's data](https://github.com/mdn/browser-compat-data) lists Web Speech and Media Session as unsupported in Android WebView, so audio and TTS must be native anyway | Third-party plugin | UI only. Audio and TTS would be written twice |
| React Native | LNReader's [release notes](https://www.lnreader.app/releases) say background TTS works. [Track Player v5+](https://www.rntp.dev/terms) is free for personal use | Through Track Player or a native module | React Native Windows and sherpa-onnx bindings: [unverified] |
| Flutter | [`audio_service`](https://pub.dev/packages/audio_service) uses the older `androidx.media:media:1.7.0`, not a Media3 session. [#996](https://github.com/ryanheise/audio_service/issues/996) (resuming after an interruption) and [#932](https://github.com/ryanheise/audio_service/issues/932) (Samsung pausing when locked) are still open, though a 2024 comment on #932 says Google fixed its cause in Android 13 | Yes on Android | Best here. [`sherpa_onnx`](https://pub.dev/packages/sherpa_onnx) covers Android and Windows, but `audio_service` lists no Windows support and [`just_audio`](https://pub.dev/packages/just_audio) needs an add-on there |

Browser speech has two more confirmed problems. In [MDN's compatibility data](https://github.com/mdn/browser-compat-data), `pause()` on Android acts like `cancel()`, and Chrome's `boundary` event "does not fire as expected", which breaks both pausing and highlighting. A PWA also can't read other sites' pages without a server, which is why Lumina has one. In-app search makes that a dealbreaker.

**Recommendation: native Kotlin, Jetpack Compose and Media3.** Your main complaint is losing your place when you pause or switch apps. Android 17 [silences audio with no error](https://developer.android.com/about/versions/17/changes/bg-audio) if an app is in the wrong lifecycle state, and the A26 is [promised six OS upgrades](https://www.gizbot.com/mobile/news/samsung-galaxy-a26-a36-a56-launched-with-6-years-of-os-updates-ip67-rating-check-price-and-specs-110693.html). Media3 is the route Google names for this, and you said the laptop can wait.

The cost is a separate Windows app shell later. To keep that cheap, the chapter model, sentence splitter and position logic go in a `core` module with no Android imports.

**Build fresh or fork?** In-app search adds a library and catalog, which existing apps already have, so I rechecked this.

| Option | Search, library, chapters | Audio and position work left | Verdict |
|---|---|---|---|
| Use LNReader as it is, with Kokoro as the system voice | Done, 187 English sources | None possible. Position stays per paragraph on the older media API | Free to try in Phase 0. If you're happy, stop there |
| Fork LNReader | Done | Replace its TTS module with Media3 and sherpa-onnx, and add sentence highlighting in its WebView reader | Fallback. Brings the whole React Native and Expo toolchain, with no Windows app |
| Fork IReader | Done, plus a Windows app | Replace the older media session, add sherpa-onnx | Too much code to own. Useful for reading its plugin bridge |
| Fork NovelDokusha | Done, but sources are no longer maintained | Add sherpa-onnx and Media3 | Rejected |
| Fresh Kotlin app that runs LNReader plugins | Build ordinary Compose screens, reuse site parsing | All of it, as designed in Decisions 2 and 4 | Recommended |
| Fresh Kotlin app with hand-written adapters | Build screens and 3 to 5 parsers | All of it | Fallback if plugins fail in Spike 3 |

Reliable audio and exact position are the reason to build at all, and every fork would need those rewritten. Search screens are routine, and the hard part, site parsing that keeps working, is what the plugins provide. Forking LNReader is the closest alternative, so I would revisit this once you've tried it in Phase 0.

**Installing on your phone costs nothing.** [ADB installs are exempt](https://developer.android.com/developer-verification/guides/faq) from Google's developer verification, which goes global in 2027, and a USB install worked on your phone on 2026-11-07. A free limited-distribution account covering 20 devices is the backup. You don't need the Play Store.

**Samsung setup.** Add Lantern to "Apps that won't be put to sleep" and set its battery use to unrestricted. [dontkillmyapp](https://dontkillmyapp.com/samsung) says foreground services should work properly since One UI 6, but marks that claim unverified, so the tests below check it.

### Decision 2: TTS engine

Costs assume a chapter of 20,000 characters, about 20 minutes of audio [estimate]. That is a cautious figure, since the chapters I sampled had 10,000 to 13,000 characters. The fast-voice speeds below come from [a benchmark](https://github.com/muxable/rtchat/issues/1500) on an x86 emulator, so compare them with each other and don't read them as A26 speeds. All on-device options are free, work offline and send no text anywhere.

| Engine | Voice quality | Cost per chapter | Offline | Latency | On the A26 |
|---|---|---|---|---|---|
| Android system TTS | Probably the voices you already dislike [unverified] | $0 | Depends on voice | Instant | Fine. [Highlight timing](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener) only comes if the engine supplies it |
| Kokoro v1.0 fp32 | Best free candidate. [Apache-2.0](https://huggingface.co/hexgrad/Kokoro-82M), [28 English voices](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md), `af_heart` graded A by its author | $0 | Yes | 6.3 s to load, then the wait below | Measured RTF about 1.23 and 874 MB of RAM once loaded. [About 350 MB](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html) of files |
| Supertonic 3 int8 | Vendor claims only, needs your ears. OpenRAIL-M license, fine for personal use | $0 | Yes | Under 1 s | Emulator RTF 0.20, 129 MB |
| Kitten nano v0.8 fp32 | Needs your ears. Fewest word errors of the English models in the benchmark. Apache-2.0 | $0 | Yes | Under 1 s | Emulator RTF 0.09, 64 MB |
| Piper medium | Usually rated less natural [unverified]. More word errors in the tested voice | $0 | Yes | Fastest | Emulator RTF 0.05, 82 MB |
| Kokoro in browser | Same voice | $0 | Yes | Unknown | [About 0.5x to 1x real time on desktop CPUs](https://offlinetts.pages.dev/blog/browser-tts-showdown-kokoro-piper-kitten/). On this phone: [unverified] |
| Azure | High [unverified by ear] | [500,000 free characters a month](https://azure.microsoft.com/en-us/pricing/details/speech/), about 25 chapters. Then about $0.30 [secondary sources] | No | Network | Fine. [Signup needs a card](https://azure.microsoft.com/en-us/pricing/purchase-options/azure-account) |
| Google Cloud | High [unverified by ear] | [Free monthly](https://cloud.google.com/text-to-speech/pricing): Chirp 3 HD 1M, about 50 chapters, then $0.60. WaveNet 4M, about 200 chapters, then $0.08 | No | Network | Fine. [Billing must be enabled](https://docs.cloud.google.com/text-to-speech/docs/before-you-begin), so overage can cost you real money |
| Gemini API | High [unverified by ear] | [Free tier](https://aistudio.google.com/docs/pricing) for Flash TTS, but your text is "used to improve our products". Daily limits: [unverified] | No | Network | Fine |
| ElevenLabs | High [unverified by ear] | [About 10,000 free credits a month](https://costbench.com/software/ai-voice-tools/elevenlabs/free-plan), about half a chapter | No | Network | Fine |
| OpenAI | High [unverified by ear] | [About $0.015 a minute](https://developers.openai.com/api/docs/models/gpt-4o-mini-tts), about $0.30 [secondary sources]. No recurring free tier | No | Network | Fine |
| edge-tts | High | $0, unofficial | No | Network | An add-on author says [Microsoft blocked browser clients in December 2025](https://addons.mozilla.org/firefox/addon/edge-tts/). Too fragile to build on |

**Recommendation: on-device only, through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).** It is Apache-2.0, ships Android and Windows builds, and runs all of these models through one API. Use Kokoro v1.0 as the great voice and pick the fast voice by ear if you need one.

Four details matter:

- Avoid Kokoro v1.1 for English. It [drops most English voices](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh).
- Default to fp32. On a Pixel 10 Pro [it was faster than int8](https://github.com/k2-fsa/sherpa-onnx/issues/3754), 0.48 to 0.51 against 0.62, and int8 sometimes produced garbage audio. A [fix merged on 2026-10-26](https://github.com/k2-fsa/sherpa-onnx/pull/4087), so int8 is worth retesting.
- The prebuilt library [links GPL-3.0 espeak-ng](https://github.com/muxable/rtchat/issues/1500). That doesn't matter for a personal app, but it would if you ever published Lantern.
- Leave out the Chinese rule files. The demo app is built with `number-zh.fst`, `date-zh.fst` and `phone-zh.fst`, and the engine [runs them on the text](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-tts-kokoro-impl.h) before speaking, which fits the Chinese-sounding numbers you heard. Lantern would load English only and turn numbers into English words itself in `core`, so "Chapter 42" becomes "Chapter forty-two". Spike 1 tests this.

#### Kokoro's wait against your 2-minute limit

Measured on your phone on 2026-11-07, with the sherpa-onnx demo at its default 4 threads and speaker 3:

| Measure | Result |
|---|---|
| Phone | SM-A266B, Exynos 1380, 6 GB RAM, Android 16, One UI 8.5 |
| Generating and playing a 4-minute passage | A few seconds under 9 minutes |
| Speed | RTF about 1.23 |
| Model load | 6.3 s |
| RAM once loaded | About 874 MB |

My earlier estimate of 0.6 to 0.9 was too optimistic. This is one short run, and heat and battery use over a whole chapter are still unmeasured.

At this speed Kokoro falls behind by about 11 seconds for every minute you listen:

| Chapter length | Wait with Kokoro alone | Or: fast voice for this long, then Kokoro | Time to prepare it ahead |
|---|---|---|---|
| 9 min | About 2 min | About 1.7 min | About 11 min |
| 12 min | About 2.8 min | About 2.2 min | About 15 min |
| 20 min | About 4.6 min | About 3.7 min | About 25 min |

The wait is chapter length x (RTF - 1). The handover point is chapter length x (1 - 1/RTF). The chapters I sampled would run about 10 to 13 minutes [estimate], so the usual wait is just over your 2-minute limit.

**Prepared audio removes most of the problem.** Audio generated ahead of time builds a lead, and listening uses it up at that 11 seconds a minute. One fully prepared 12-minute chapter should buy about an hour of unbroken Kokoro [estimate] and takes about 15 minutes to generate. So Lantern should prepare the current and next chapter while the phone charges, which stays within your one-chapter-ahead limit. Faster playback uses up the lead about twice as fast at 1.25x.

**Kokoro can't be sped up with settings.** You judged its voice good enough but too slow. On 2026-11-07 I timed the same passage on your phone with sherpa-onnx 2.0.1's command-line tool. The screen state wasn't controlled and each result is one run:

| Voice and setting | RTF on your A26 | Download | Sound, from community reports until you listen |
|---|---|---|---|
| Kokoro fp32, 4 threads, any cores | 1.34 to 1.37 | 349 MB | The most natural |
| Kokoro fp32, 4 threads on fast cores | 1.47 | | |
| Kokoro fp32, 3 threads on fast cores | 1.82 | | |
| Kokoro fp32, 8 threads | 1.76 | | |
| Kokoro int8, 4 threads on fast cores | 2.62 | 132 MB | |
| Pocket TTS int8, 4 threads | 1.12 to 1.26 | 98 MB | Not rated. No real speed gain, and non-commercial license |
| Supertonic 3 int8, 8 steps | 0.93 | 128 MB | Reported less natural than Kokoro |
| Supertonic 3 int8, 5 steps | 0.59 to 0.61 | | Same model at its default setting |
| Supertonic 3 int8, 3 steps | 0.38 | | Lower quality setting |
| Kitten nano v0.8 fp32, 4 threads | 0.30 to 0.64 per sentence | 63 MB | Few independent reports |
| Piper lessac medium, 4 threads | 0.13 to 0.16 | 67 MB | "Somewhat mechanical" |

Compare generation time as well as RTF, because voices read at different paces. For the same text, Kokoro produced about 44 s of audio and Supertonic about 70 s. Sound notes come from [a CPU benchmark](https://bittide.aicompass.dev/article/213f0a6c-be96-402b-8672-c2d9d3cb6029) and [a Piper comparison](https://www.codesota.com/text-to-speech/piper-vs-kokoro), neither of them a blind test. Your samples are in the phone's `Music/LanternVoiceTest` folder.

**Options now that Kokoro can't keep up:**

| Option | Wait | Extra work | Verdict |
|---|---|---|---|
| Supertonic 3 as the only voice | Seconds | Least | Recommended if the samples sound good enough to you |
| Kokoro, with a fast voice first on unprepared chapters and preparation while charging | None for you, but the voice changes mid-chapter | Most | Recommended if only Kokoro will do |
| Kokoro alone, with preparation while charging | About 3 min on an unprepared 9-minute chapter | Medium | Breaks your 2-minute limit |
| Laptop generates Kokoro and sends it to the phone | None if prepared | Medium, and the laptop must be on | Later option |
| Cloud voices | Seconds | Accounts and internet | Rejected, see the table above |

**Unknown words are a new risk.** sherpa-onnx 2.0 removed espeak-ng and looks words up in a lexicon. Unknown words are skipped silently. On my passage the tool skipped any word touching a quotation mark, plus "Kael", "Sealed", "wanted", "opens" and "minutes" with Kokoro, and "her" with Piper and Kitten. Light novels are full of invented names, so this could drop words without warning. Supertonic uses no lexicon, so it shouldn't have this problem, but I didn't check its logs for skipped words. The demo app you tried is the older 1.12.21 with espeak-ng data. Spike 1 must count skipped words on real chapters, strip quotation marks first, and compare 2.0 with the older version. Kitten also crashed on the whole passage, so it needs one sentence at a time.

**How the handover works.** Each sentence plays the best audio available for it. Kokoro starts generating at the calculated point ahead of you, and when playback reaches it, the voice changes at that sentence boundary. Position is stored against the text, so the swap can't lose your place. Running both engines slows Kokoro and adds to its 874 MB of RAM, and Android 17 adds [RAM-based app memory limits](https://developer.android.com/about/versions/17/behavior-changes-all), so Spike 1 tests this too.

**Loading one chapter ahead.** When a chapter starts, Lantern fetches the next chapter's text. Once the current chapter's audio is finished, it generates the next chapter's audio. Nothing beyond that is fetched or generated. Audio is 2.88 MB a minute at 24 kHz, about 58 MB per 20-minute chapter, and only the current and next chapters stay on disk.

### Decision 3: Finding and loading chapters

This decision changed most. Pasting and sharing URLs don't meet "I don't want to run around finding them".

| Option | Effort for you | Reach | Upkeep when sites change | Legal or terms risk |
|---|---|---|---|---|
| Paste text | High | Anything | None | Lowest |
| Share URL into an in-app browser | Medium, you still find each chapter | Most sites | Low | Low |
| Hand-written adapters for 3 to 5 sites | Low | Those sites | You and I fix each break | Automated access |
| LNReader plugins inside the app | Low | 187 English sources available, a reviewed few enabled | Community fixes, then you update | Automated access, and some plugins bypass checks unless excluded |
| Stealth headless browser, as in Lumina | Low | Most sites | High | Highest. Also can't run on a phone |
| EPUB import | One file | Your own files | None | Lowest if DRM-free |

**Recommendation: LNReader plugins, run inside Lantern.** The compiled AllNovelFull plugin is 19 KB and only needs `@libs/fetch`, `@libs/novelStatus`, `cheerio` and `htmlparser2`. [LNReader's plugin loader](https://github.com/LNReader/lnreader/blob/master/src/plugins/pluginManager.ts) shows the whole contract in a short list of modules. Lantern would run plugins in QuickJS through [quickjs-kt](https://github.com/dokar3/quickjs-kt), Apache-2.0 with Android and JVM targets, with a Kotlin bridge for network requests. This is unproven in Kotlin apart from IReader's different engine, so Spike 3 proves it or falls back to hand-written adapters.

**The flow you described:**

1. Type a title and submit. Lantern asks every enabled site once and shows results as they arrive, grouped by title.
2. Tap "Add to library". Lantern picks the site listing the most chapters.
3. Open the book to see all its chapters, then read or listen to any of them.

I checked your example. "Ode of the Brave" is on AllNovelFull, with chapter links up to at least 104, and on NovelBuddy. A general web search didn't find it, so in-app search would have saved you real time here.

**Limits the app enforces:**

- One search request per enabled site, on submit and never per keystroke
- Chapter lists are fetched only when you add or open a book
- Chapter text is fetched for the current and next chapter only, with no background crawling
- Each plugin can only reach its own site, and has no file access
- Plugin code ships inside the app after I read it. Nothing is downloaded and run later
- A challenge page stops the request and tells you. Lantern never solves or evades one

**Starting site list.** I tested 22 sites on 2026-11-07 from this PC, with a plainly named client, one test title each and at most five requests per site. Your phone or a different day may differ, so Spike 3 repeats this. "Chapter text" means the text was in the page itself.

*On by default.* Plain requests work, and neither robots.txt nor the terms I found object.

| Site | Type | Search | Chapter text | Terms |
|---|---|---|---|---|
| AllNovelFull (novgo.net) | Aggregator | Works | 13,264 characters | [Allows automated requests](https://novgo.net/tos) that don't overload the site or evade restrictions |
| ReadNovelFull | Aggregator | Works | 10,473 characters | [Terms page](https://readnovelfull.com/site/tos) doesn't mention automation |
| NovelCool | Aggregator | Works | 10,474 characters, with 2,503 chapter links on one page | [Terms page](https://www.novelcool.com/termsofuse.html) only covers VIP renewal |
| NovelBuddy | Aggregator | Works through its API | Not tested | Not checked |
| Novel Bin (novelbin.xyz) | Aggregator | Works | Not in the page, the plugin reads the site's JSON. Untested | No terms link on the home page |
| Wuxiaworld | Licensed publisher | Works through its API | Not tested. Some chapters may be paid [unverified] | No terms link on the home page |

*Off by default.* These work, but the site objects, so turning one on is your decision.

| Site | Result | Objection |
|---|---|---|
| Novel Fire | Search works, 11,429 characters of chapter text | robots.txt blocks its search path, and its [terms](https://novelfire.net/page/terms-of-service) restrict automated systems |
| WuxiaWorld.Site | Search works, but my test title's result wasn't a real match | Its [terms](https://wuxiaworld.site/term-of-use/) ban scraping without written permission |

*Blocked for a plain client.* These answered with a challenge or 403, so they would need the visible in-app browser in Phase 4. A browser may still get through.

| Site | Note |
|---|---|
| Scribble Hub | Original web fiction |
| Royal Road | Original web fiction. [Terms](https://tostracker.app/document/royalroadcom-tos) ban bots, read through a tracker because the site returned 403 |
| Free Web Novel, NovelFull, BoxNovel | Aggregators |
| Webnovel, Novel Hall | robots.txt also blocks search |
| Ranobes | DDoS-Guard challenge, and robots.txt also blocks search |

*Skip.* MTL Novels' robots.txt blocks everything. WTR-LAB's robots.txt blocks search, and its plugin decrypts content.

*Inconclusive.* LightNovelWorld, LightNovelCave, NovelHi and FanMTL answered, but didn't show my test title. My search URLs may be wrong, so Spike 3 retries through their plugins.

**Plugins I would not enable.** The Royal Road plugin contains code that solves Anubis anti-bot challenges, and StorySeedling's refers to bypassing Turnstile. That conflicts with the rule above, and I read every plugin before it ships.

**Legal and terms-of-service flags.** This is general research, not legal advice, and the law cited is US law.

- **Automated access is now the core of the app.** Every search and chapter fetch is an automated request. That is why the default list favours sites that don't object and why the limits above exist. Using an off-by-default site could breach its terms, and the consequence would most likely be a ban.
- **Rights.** Aggregators may host unlicensed translations, and I can't tell per title. A [third-party review](https://tools.malwaretips.com/url-scan/freewebnovel.com) says authors have reported their work on FreeWebNovel without permission, which is unverified and no court's finding. Keep all text and audio on your own device and never share it.
- **Bypassing challenges.** Platforms now [argue](https://www.jenner.com/en/news-insights/publications/googles-scraping-suit-highlights-section-1201s-expansive-reach) that evading bot checks breaks DMCA section 1201, with statutory damages of $200 to $2,500 per act. The theory is largely untested and the cases are commercial, but I found no personal-use exemption. This is why I rejected Lumina's approach.
- **Plugin license.** The plugins are MIT, so the app keeps their copyright notice.

**Text quality.** A pattern check across five chapter pages flagged one promo line, "Source: Webnovel.com, updated on .co". It's a small sample and the check could miss others, but that is rare enough to move skip rules to Phase 3. Lantern keeps every paragraph, however short, to avoid Lumina's dropped-dialogue bug.

### Decision 4: Position tracking

| Approach | Accuracy | Survives a voice or speed change | Highlight and tap to jump | Verdict |
|---|---|---|---|---|
| Scroll percentage, as in Lumina | Low, shifts with layout | Yes, but vaguely | No | Rejected |
| Time in a chapter audio file | Exact for one voice | No | Needs a timing map | Rejected, breaks the voice handover |
| Paragraph, as in LNReader | Can rewind a minute or more | Yes | Coarse | Rejected |
| Sentence, stored against the text | Within one sentence | Yes | Yes | Recommended |
| Word | Exact | Yes | Yes | Later, if ever. Needs word timing that I couldn't confirm sherpa-onnx provides |

**Recommendation: the text is the source of truth and audio is a disposable cache.** A position is the chapter ID, sentence index and milliseconds into that sentence. Reading and listening share it.

- **Sync.** Each sentence is one Media3 playlist item. [The docs](https://developer.android.com/media/media3/exoplayer/playlists) say transitions are seamless, items can be added during playback, and `onMediaItemTransition` fires on each change. That callback drives both the highlight and the save. An audible click between WAV items on the A26 is [unverified], so Spike 2 checks it.
- **Lock screen.** A [`ForwardingSimpleBasePlayer`](https://developer.android.com/reference/androidx/media3/common/SimpleBasePlayer) shows the chapter as one item instead of hundreds of sentences. Skip buttons move one sentence.
- **Sentence not ready yet.** The player waits in a buffering state instead of ending, so the service stays alive. Android's docs say to [keep it active through interruptions under 10 minutes](https://developer.android.com/about/versions/17/changes/bg-audio). This design is untested, and Spike 2 proves or kills it.
- **Saving.** A small SQLite write happens on each sentence change, pause, audio focus loss, task removal and service destroy. The most you can lose to a crash is one sentence.
- **Resume.** Playback restarts at the beginning of the saved sentence. After more than 5 minutes away, it backs up one more for context. Media3's [playback resumption](https://developer.android.com/media/media3/session/background-playback#resumption) covers headset buttons after the app was killed or the phone rebooted.
- **Reading without audio.** Scrolling saves the first fully visible sentence, so pressing play starts where you are reading.
- **Tap to jump.** Tapping a sentence plays from there. The last 20 positions are kept, so a pocket tap can be undone.
- **Following.** The text scrolls with the voice. Scrolling by hand detaches it and shows "Back to voice".
- **Auto-advance.** The next chapter's sentences join the same playlist after a spoken chapter title, with no gap that could stop the service. When that chapter starts, Lantern fetches the one after it, which happens with the screen off. Spike 2 tests that. If the fetch fails, Lantern says so aloud at the end of the chapter, saves your place and stops.
- **Speed.** Playback speed is applied live by the player, so changing speed doesn't regenerate audio.
- **Sentence splitting.** `java.text.BreakIterator` in `core`, with fixes and tests for novel punctuation such as ellipses, quotes and titles. Very long sentences split at clause punctuation, which [roughly halved first-audio time](https://github.com/muxable/rtchat/issues/1500) in that benchmark.

## MVP scope

The smallest version I think you would use daily: search a title, add it, press Continue, and it always lands on the right sentence.

**In:**

1. In-app title search across the on-by-default sites.
2. "Add to library", a library screen and a full chapter list for each book.
3. A reader view with sentence highlight, follow mode, tap to jump and "Back to voice".
4. On-device speech in the voice setup you choose from the samples, either Supertonic alone or Kokoro with a fast voice and preparation while charging.
5. Screen-off playback with lock-screen and notification controls.
6. Position saving, resume and the undo list, shared by reading and listening.
7. Auto-advance, with only the next chapter loaded ahead.
8. Playback speed control.
9. Paste text, as a cheap way to read anything not covered by a site.

**Out, for now:** site browsing by genre or popularity, switching a book to another site, share-from-browser, skip rules, EPUB, the visible browser for blocked sites, laptop, sync, word highlighting, cloud voices, sleep timer, pronunciation fixes, bookmarks inside chapters, update checks, stats and themes beyond system light and dark.

**Done means all of these pass on your A26:**

- Searching "Ode of the Brave" finds it, and you can add it and open chapter 1.
- Three chapters in a row play with the screen off.
- Resume lands within one sentence after each of these: pause, app switch, phone call, Bluetooth drop, `adb` kill, force stop and reboot.
- Battery and heat over those three chapters are measured and acceptable to you.
- A real chapter from each enabled site keeps all its short dialogue lines.

**Data, stored in SQLite through Room:** `source`, `book`, `chapter` (text fetched on demand), `sentence`, `position`, `position_history` and `audio_cache`.

## Roadmap

**Phase 0: phone tests with no code.** No PC needed.

*Kokoro test, done 2026-11-07.* RTF about 1.23, with the results under Decision 2. The [demo app](https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-new/1.12.21/sherpa-onnx-1.12.21-arm64-v8a-zh_en-tts-kokoro-multi-lang-v1_0.apk) had to go on over USB. The copy downloaded on the phone had the right size but the wrong contents, and the phone's log showed failure `-103`, "SHA-256 digest of contents did not verify". Lantern must verify a checksum after downloading any voice model.

To repeat the test, time Generate until it becomes tappable again (T), then time Play (L). RTF = T / L - 1. Gaps between sentences are normal in this demo, because its code [plays each sentence before generating the next](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxTts/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt). That is why the formula subtracts 1, and Lantern won't work that way. Speaker IDs 2, 3, 16 and 21 are `af_bella`, `af_heart`, `am_michael` and `bf_emma`, if you want to compare voices.

*Baseline test.* Install [LNReader](https://www.lnreader.app/releases), [add a plugin repository](https://www.lnreader.app/plugins), and try Kokoro as the system voice through the [sherpa-onnx engine APK](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html). That combination is [unverified]. It shows how close a free app already gets, and if it's enough, you can skip this build.

*Fast voice samples, saved to `Music/LanternVoiceTest` on your phone on 2026-11-07.* Your choice from these sets the voice setup. To make more, push `sherpa-onnx-v2.0.1-android-aarch64-termux-static` plus Termux's `libc++_shared.so` to `/data/local/tmp`, and run `sherpa-onnx-offline-tts` with `LD_LIBRARY_PATH=.`. Piper and Kitten also need `lexicon-en-us.txt` from the `tts-models` release. Google's Platform Tools aren't installed on the laptop, so download them again. The demo APKs are another way to try Kitten and Piper from the [same project's APKs](https://k2-fsa.github.io/sherpa/onnx/tts/apk.html), plus the [Supertonic TTS app](https://apt.izzysoft.de/fdroid/index/apk/com.brahmadeo.supertonic.tts?repo=main), and pick by ear.

**Phase 1: three spikes, throwaway code.** You install Android Studio and turn on USB debugging first.

- **Spike 1, engine.** Generate a full chapter with the voice you chose, at 4 threads on any cores, from inside an app. Record RTF, heat, battery and RAM, and count skipped words on real chapters on sherpa-onnx 2.0 and the older version. Check that numbers and dates are read in English. Test both voices together only if you choose the handover.
- **Spike 2, playback.** Run per-sentence Media3 playback with waiting on unready sentences for an hour with the screen off, including fetching the next chapter with the screen off. Then run every interruption from the MVP checklist. If this design fails, I revise Decision 4 before the MVP.
- **Spike 3, sources.** Run the AllNovelFull plugin in QuickJS on the phone: search, chapter list and chapter text. Then repeat the site tests through plugins. If it fails, I fall back to hand-written adapters, or to forking LNReader, and tell you which and why.

**Phase 2: MVP,** as scoped above.

**Phase 3: comfort.** Switching a book to another site when one breaks, a sleep timer, pronunciation fixes for character names and skip rules.

**Phase 4: more sources.** The visible in-app browser for blocked sites like Scribble Hub, share-from-browser with Mozilla's [Readability](https://github.com/mozilla/readability) for anything else, updating plugins from inside the app after review, and EPUB import for DRM-free files.

**Phase 5: laptop.** Compose Desktop on Windows, reusing `core`, the plugins and the sherpa-onnx Windows build. Share your place through file export first, then direct phone-to-laptop sync on the same Wi-Fi. Neither needs a server.

**Maybe later:** different voices for dialogue and narration, and word-level highlighting.

## Open questions

Decisions for you:

1. **Is the default site list OK?** I recommend leaving sites that object off by default. You can turn one on per site, accepting the risk under Decision 3.
2. **Fresh build or LNReader fork?** I recommend fresh. Tell me after Phase 0 if LNReader felt close to what you want, because that could change it.
3. **English only?** I assumed yes. Other languages would change the voice comparison.
4. **Which voice setup?** Listen to `Music/LanternVoiceTest` on your phone. If Supertonic sounds good enough, I recommend it alone, which removes the handover and chapter preparation. If only Kokoro will do, Lantern needs both.

Only your phone can answer these:

- Kokoro's speed, heat and battery use over a whole chapter
- Which fast voice you like
- Whether plugins run correctly in QuickJS on the phone
- Whether the on-by-default sites answer the phone the way they answered this PC
- Whether sentence-by-sentence WAV playback has audible gaps
- Whether Samsung leaves the playback service alone
- Whether next-chapter fetching works with the screen off
- Whether Chrome speech survives screen-off, which only matters if you want to rule it out completely
