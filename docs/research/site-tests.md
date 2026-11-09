# Site tests

A link means I read that source. [unverified] marks something I could not confirm.

**Starting site list.** I tested 22 sites on 2026-11-07 from this PC, with a plainly named client, one test title each and at most five requests per site. Your phone or a different day may differ, so [Spike 3](../../PLAN.md#roadmap) repeats this. "Chapter text" means the text was in the page itself. The scripts and raw results are in `tools/site-check/`.

*On by default when tested.* Plain requests work, and neither robots.txt nor the terms I found object. After [Spike 3](../spikes/spike-3-sources.md) on 2026-11-09, only AllNovelFull, ReadNovelFull and NovelBuddy stay on, with Wuxiaworld still to test. NovelCool is dropped and Novel Bin is broken, as explained in [Finding and loading chapters](../decisions/chapter-sources.md).

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

*Blocked for a plain client.* These answered with a challenge or 403, so they would need the visible in-app browser in [Phase 4](../../PLAN.md#roadmap). A browser may still get through.

| Site | Note |
|---|---|
| Scribble Hub | Original web fiction |
| Royal Road | Original web fiction. [Terms](https://tostracker.app/document/royalroadcom-tos) ban bots, read through a tracker because the site returned 403 |
| Free Web Novel, NovelFull, BoxNovel | Aggregators |
| Webnovel, Novel Hall | robots.txt also blocks search |
| Ranobes | DDoS-Guard challenge, and robots.txt also blocks search |

*Skip.* MTL Novels' robots.txt blocks everything. WTR-LAB's robots.txt blocks search, and its plugin decrypts content.

*Inconclusive.* LightNovelWorld, LightNovelCave, NovelHi and FanMTL answered, but didn't show my test title. My search URLs may be wrong, so [Spike 3](../../PLAN.md#roadmap) retries through their plugins.
