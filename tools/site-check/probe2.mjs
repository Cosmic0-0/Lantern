// Follow-up check: is the example title really listed, what does chapter text look like,
// and do the sites publish terms that mention automated access. Same limits as probe.mjs.
import { readFileSync } from 'node:fs';

const UA = 'LanternPlanCheck/0.1 (personal project reachability check)';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const q = encodeURIComponent;

async function get(url) {
  await sleep(1500);
  try {
    const res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'text/html,application/json,*/*' }, signal: AbortSignal.timeout(20000) });
    return { status: res.status, url: res.url, body: (await res.text()).slice(0, 3_000_000) };
  } catch (e) {
    return { status: 0, body: '', error: String(e.cause?.code || e.name) };
  }
}
const strip = h => h.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();

const results = JSON.parse(readFileSync(process.argv[2], 'utf8'));

console.log('== example title: real result links only');
const exampleSearch = {
  'Novel Fire': `https://novelfire.net/search?keyword=${q('Ode of the Brave')}`,
  'ReadNovelFull': `https://readnovelfull.com/novel-list/search?keyword=${q('Ode of the Brave')}`,
  'AllNovelFull': `https://novgo.net/search?keyword=${q('Ode of the Brave')}`,
  'NovelBuddy': `https://api.novelbuddy.me/titles/search?q=${q('Ode of the Brave')}`,
  'WuxiaWorld.Site': `https://wuxiaworld.site/page/1/?s=${q('Ode of the Brave')}&post_type=wp-manga`,
};
for (const [name, url] of Object.entries(exampleSearch)) {
  const r = await get(url);
  const links = [...r.body.matchAll(/href\s*=\s*["']([^"']*ode[-_]of[-_]the[-_]brave[^"']*)["']/gi)].map(m => m[1]).filter(l => !/search/i.test(l));
  const jsonTitle = /"(title|name)"\s*:\s*"[^"]*ode of the brave[^"]*"/i.test(r.body);
  console.log(name, r.status, 'links:', links.slice(0, 2), 'jsonTitle:', jsonTitle);
}

console.log('\n== chapter text check');
const junk = /(novel(fire|full|bin|cool|hall|buddy)|\.com|\.net|\.org|read (more|at|on|the latest)|latest chapter|updated (by|from)|discord|patreon|translat(or|ion|ed) by|stolen|report chapter|support (us|the)|lightnovel)/i;
for (const r of results) {
  if (!r.chapter || r.chapter.status !== 200) continue;
  const c = await get(r.chapter.url);
  const paras = [...c.body.matchAll(/<p[^>]*>([\s\S]*?)<\/p>/gi)].map(m => strip(m[1])).filter(Boolean);
  const short = paras.filter(p => p.length <= 10).length;
  const flagged = paras.filter(p => junk.test(p) && p.length < 160).slice(0, 4);
  console.log(JSON.stringify({ site: r.name, url: r.chapter.url, paragraphs: paras.length, paragraphChars: paras.join(' ').length, shortParas: short, flagged }));
}

console.log('\n== published terms');
const termsSites = ['https://readnovelfull.com', 'https://novgo.net', 'https://www.novelcool.com', 'https://wuxiaworld.site', 'https://novelbin.xyz', 'https://novelfire.net', 'https://www.wuxiaworld.com'];
for (const base of termsSites) {
  const home = await get(base + '/');
  const link = [...home.body.matchAll(/href\s*=\s*["']([^"']+)["'][^>]*>([^<]{0,40})/gi)]
    .map(m => { try { return [new URL(m[1], base).toString(), m[2]]; } catch { return null; } })
    .filter(Boolean)
    .find(([u, t]) => /terms|tos\b|term-of|terms-of/i.test(u + ' ' + t));
  if (!link) { console.log(base, 'home', home.status, 'no terms link on the home page'); continue; }
  const t = await get(link[0]);
  const text = strip(t.body);
  const hits = text.split(/(?<=[.;])\s+/).filter(s => /scrap|crawl|\bbots?\b|automated|spider|robot|harvest/i.test(s)).slice(0, 3).map(s => s.slice(0, 220));
  console.log(base, 'terms', link[0], t.status, 'chars', text.length, JSON.stringify(hits));
}
