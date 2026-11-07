# Finding and loading chapters

This decision changed most. Pasting and sharing URLs don't meet "I don't want to run around finding them".

| Option | Effort for you | Reach | Upkeep when sites change | Legal or terms risk |
|---|---|---|---|---|
| Paste text | High | Anything | None | Lowest |
| Share URL into an in-app browser | Medium, you still find each chapter | Most sites | Low | Low |
| Hand-written adapters for 3 to 5 sites | Low | Those sites | You and I fix each break | Automated access |
| LNReader plugins inside the app | Low | 187 English sources available, a reviewed few enabled | Community fixes, then you update | Automated access, and some plugins bypass checks unless excluded |
| Stealth headless browser, as in [Lumina](../research/reference-review.md) | Low | Most sites | High | Highest. Also can't run on a phone |
| EPUB import | One file | Your own files | None | Lowest if DRM-free |

**Recommendation: LNReader plugins, run inside Lantern.** The compiled AllNovelFull plugin is 19 KB and only needs `@libs/fetch`, `@libs/novelStatus`, `cheerio` and `htmlparser2`. [LNReader's plugin loader](https://github.com/LNReader/lnreader/blob/master/src/plugins/pluginManager.ts) shows the whole contract in a short list of modules. Lantern would run plugins in QuickJS through [quickjs-kt](https://github.com/dokar3/quickjs-kt), Apache-2.0 with Android and JVM targets, with a Kotlin bridge for network requests. This is unproven in Kotlin apart from IReader's different engine, so [Spike 3](../../PLAN.md#roadmap) proves it or falls back to hand-written adapters.

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

**Starting site list.** The six sites on by default are AllNovelFull, ReadNovelFull, NovelCool, NovelBuddy, Novel Bin and Wuxiaworld, and the full list with how the sites were tested is in [Site tests](../research/site-tests.md).

**Plugins I would not enable.** The Royal Road plugin contains code that solves Anubis anti-bot challenges, and StorySeedling's refers to bypassing Turnstile. That conflicts with the rule above, and I read every plugin before it ships.

**Legal and terms-of-service flags.** This is general research, not legal advice, and the law cited is US law.

- **Automated access is now the core of the app.** Every search and chapter fetch is an automated request. That is why the [default list](../research/site-tests.md) favours sites that don't object and why the limits above exist. Using an off-by-default site could breach its terms, and the consequence would most likely be a ban.
- **Rights.** Aggregators may host unlicensed translations, and I can't tell per title. A [third-party review](https://tools.malwaretips.com/url-scan/freewebnovel.com) says authors have reported their work on FreeWebNovel without permission, which is unverified and no court's finding. Keep all text and audio on your own device and never share it.
- **Bypassing challenges.** Platforms now [argue](https://www.jenner.com/en/news-insights/publications/googles-scraping-suit-highlights-section-1201s-expansive-reach) that evading bot checks breaks DMCA section 1201, with statutory damages of $200 to $2,500 per act. The theory is largely untested and the cases are commercial, but I found no personal-use exemption. This is why I rejected Lumina's approach.
- **Plugin license.** The plugins are MIT, so the app keeps their copyright notice.

**Text quality.** A pattern check across five chapter pages flagged one promo line, "Source: Webnovel.com, updated on .co". It's a small sample and the check could miss others, but that is rare enough to move skip rules to [Phase 3](../../PLAN.md#roadmap). Lantern keeps every paragraph, however short, to avoid Lumina's dropped-dialogue bug.
