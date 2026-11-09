# Spike 3: sources

Status, 2026-11-09: tested on your A26. Plugins run in QuickJS on the phone, and three of the five sites work fully. The raw results are in `spikes/sources/results/results.jsonl`.

## What it proves

That [Decision 3](../decisions/chapter-sources.md) works on your A26: LNReader's compiled site plugins run inside QuickJS in a Kotlin app, and search, chapter lists and chapter text come back complete from the on-by-default sites.

## Design

- **Plugins:** AllNovelFull (`anf.net`), ReadNovelFull, NovelCool, NovelBuddy and Novel Bin, copied into `spikes/sources/plugins/` from `lnreader-plugins` branch `plugins/v3.0.0`. I read each one: they reach only their own site, and Novel Bin stops when a site shows a security check. Wuxiaworld is left out, because its plugin needs protobuf (`fetchProto`).
- **Runtime:** quickjs-kt 1.0.15, with one QuickJS instance per plugin. A JavaScript bundle built with esbuild supplies what the plugins `require`: `cheerio/slim` (no parse5 or Node modules), `htmlparser2`, `dayjs`, and core-js's `URL` and `URLSearchParams`. `host.js` reproduces LNReader's loader contract and `@libs/*` helpers. `fetchProto` is refused.
- **Network rules, enforced in Kotlin, not in the plugin:**
  - Each plugin reaches only its own site's domain, and redirects are checked too.
  - One request at a time per site, with at least 1 s between requests.
  - A security-check page (Cloudflare or DDoS-Guard) stops the request. It is never solved or retried.
  - The User-Agent is `LanternSpike/0.1 (personal project)`.
- **Test run:** for each plugin, search "Ode of the Brave" (falling back to "Shadow Slave"), load the chapter list, and fetch chapters 1 and 50. Each chapter's text is saved, and the short paragraphs and junk lines are counted. The results go to `results.jsonl`, and the text files are kept for reading.

## Test plan

1. Phone plugged in: `./run.sh build`, `install`, `run`, then `pull`.
2. Compare with the [site tests](../research/site-tests.md) from the PC.
3. You read two or three of the fetched chapters for missing text, especially short dialogue lines.

## Results, 2026-11-09

All five plugins loaded in QuickJS on the phone, in 0.1 to 0.25 s each. No request was blocked, and no site showed a security check.

| Site | Search | Chapter list | Chapters 1 and 50 | Verdict |
|---|---|---|---|---|
| AllNovelFull | Found Ode of the Brave in 1.1 s | 104 chapters in 2.9 s | 19,167 and 14,818 characters | Works |
| ReadNovelFull | Doesn't list Ode of the Brave. Found Shadow Slave | 3,241 chapters in 4.0 s | 10,500 and 8,938 characters | Works |
| NovelBuddy | Found Ode of the Brave among 24 results in 0.7 s | 104 chapters in 1.3 s | 19,199 and 14,818 characters | Works |
| NovelCool | Ode of the Brave not listed. Shadow Slave and Lord of the Mysteries matched only manga | 14 chapters | Empty, since manga chapters are images | Dropped |
| Novel Bin | Fails: the plugin's data address now returns the site's home page, on the PC too | | | Broken until the plugin is fixed |

- **Text matches.** AllNovelFull's chapter 1 is identical to the copy you listened to in Spike 1. ReadNovelFull's Shadow Slave chapter 1 matches the Spike 1 copy apart from quote styles, a title line and one trailing emoji. Every short dialogue line is kept.
- **Junk lines.** Two kinds turned up in the 7 chapters. An ad line, "READ MORE ChapterS HERE : https://beastnovels.com/", appeared in chapter 50 on AllNovelFull and NovelBuddy. NovelBuddy also hid a watermark after a line of dialogue, spelled in styled maths letters ("freewebnovel.com"), which the plain junk check missed and a voice would read out as gibberish. Normalizing Unicode (NFKC) before checking would catch it. Skip rules are in Phase 3, but this suggests a minimal filter belongs in the MVP.
- **NovelCool poses as its own phone app.** Its plugin calls `api.novelcool.com` with NovelCool's app ID, secret and app User-Agent. When I first read the plugins I only checked which sites they reach, and missed this. You chose on 2026-11-09 to turn NovelCool off for good.
- **Not tested:** Wuxiaworld, because its plugin needs protobuf, and the off-by-default sites.
- **Build note:** quickjs-kt 1.0.15 needs Kotlin 2.4, newer than AGP 9.5.1's built-in 2.2.10, so the spike raises the Kotlin Gradle plugin to 2.4.10. The MVP has to pick versions with this in mind.
