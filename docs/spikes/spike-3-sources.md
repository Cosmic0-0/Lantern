# Spike 3: sources

Status, 2026-11-09: designed, and the test app in `spikes/sources/` is being written. It's unreviewed and not yet run on the phone.

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
