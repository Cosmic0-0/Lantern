// One-off, low-volume reachability check for the plan. Honest generic user agent,
// no cookies, no retries, no challenge solving, same-host links only.
import { writeFileSync } from 'node:fs';

const UA = 'LanternPlanCheck/0.1 (personal project reachability check)';
const DELAY_MS = 1500;
const MAX_BYTES = 3 * 1024 * 1024;
const q = encodeURIComponent;
const SS = 'Shadow Slave';

const sites = [
  // group, name, base, search url builder, term, slug regex for the novel link
  ['original', 'Royal Road', 'https://www.royalroad.com', t => `/fictions/search?title=${q(t)}`, 'Beware of Chicken', /beware-of-chicken/i],
  ['original', 'Scribble Hub', 'https://www.scribblehub.com', t => `/?s=${q(t)}&post_type=fictionposts`, 'Beware of Chicken', /beware/i],
  ['licensed', 'Wuxiaworld', 'https://www.wuxiaworld.com', t => `/api/novels/search?query=${q(t)}`, 'Renegade Immortal', /renegade/i],
  ['licensed', 'Webnovel', 'https://www.webnovel.com', t => `/search?keywords=${q(t)}&pageIndex=1`, SS, /shadow.?slave/i],
  ['aggregator', 'Novel Fire', 'https://novelfire.net', t => `/search?keyword=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'Novel Bin', 'https://novelbin.xyz', t => `/search?keyword=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'Free Web Novel', 'https://freewebnovel.com', t => `/search?keyword=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'NovelFull', 'https://novelfull.com', t => `/search?keyword=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'ReadNovelFull', 'https://readnovelfull.com', t => `/novel-list/search?keyword=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'AllNovelFull', 'https://novgo.net', t => `/search?keyword=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'Novel Hall', 'https://novelhall.com', t => `/index.php?s=so&module=book&keyword=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'LightNovelWorld', 'https://lightnovelworld.org', t => `/api/search/?q=${q(t)}&search_type=title`, SS, /shadow.?slave/i],
  ['aggregator', 'NovelBuddy', 'https://api.novelbuddy.me', t => `/titles/search?q=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'NovelHi', 'https://novelhi.com', t => `/book/searchByPageInShelf?keyword=${q(t)}&curr=1&limit=10`, SS, /shadow.?slave/i],
  ['aggregator', 'WuxiaWorld.Site', 'https://wuxiaworld.site', t => `/page/1/?s=${q(t)}&post_type=wp-manga`, SS, /shadow.?slave/i],
  ['aggregator', 'BoxNovel', 'https://novelnice.com', t => `/page/1/?s=${q(t)}&post_type=wp-manga`, SS, /shadow.?slave/i],
  ['aggregator', 'MTL Novels', 'https://www.mtlnovels.com', t => `/?s=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'LightNovelCave', 'https://www.lightnovelcave.com', t => `/search?keyword=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'NovelCool', 'https://www.novelcool.com', t => `/search/?name=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'Ranobes', 'https://ranobes.net', t => `/search/${q(t)}/`, SS, /shadow.?slave/i],
  ['aggregator', 'FanMTL', 'https://www.fanmtl.com', t => `/search.html?q=${q(t)}`, SS, /shadow.?slave/i],
  ['aggregator', 'WTR-LAB', 'https://wtr-lab.com', t => `/en/novel-finder?text=${q(t)}`, SS, /shadow.?slave/i],
];

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function get(url) {
  await sleep(DELAY_MS);
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': UA, Accept: 'text/html,application/json,text/plain,*/*' },
      redirect: 'follow',
      signal: AbortSignal.timeout(20000),
    });
    const buf = Buffer.from(await res.arrayBuffer());
    const body = buf.subarray(0, MAX_BYTES).toString('utf8');
    return { status: res.status, url: res.url, ct: res.headers.get('content-type') || '', server: res.headers.get('server') || '', cfMitigated: res.headers.get('cf-mitigated') || '', body };
  } catch (e) {
    return { status: 0, error: String(e.cause?.code || e.name || e.message), body: '' };
  }
}

const challenged = r =>
  r.cfMitigated === 'challenge' ||
  /just a moment|cf-chl|challenge-platform\/h\/|attention required|enable javascript and cookies|anubis_challenge|verifying you are human/i.test(r.body.slice(0, 200000));

function robotsBlocks(txt, path) {
  // Only the "User-agent: *" group; simple prefix match, good enough for a flag.
  const lines = txt.split(/\r?\n/).map(l => l.split('#')[0].trim());
  let inStar = false, sawAgent = false;
  const dis = [];
  for (const l of lines) {
    const m = l.match(/^([a-z-]+)\s*:\s*(.*)$/i);
    if (!m) continue;
    const k = m[1].toLowerCase(), v = m[2];
    if (k === 'user-agent') { if (!sawAgent) inStar = false; sawAgent = true; inStar = inStar || v === '*'; }
    else { sawAgent = false; if (inStar && k === 'disallow' && v) dis.push(v); }
  }
  return { count: dis.length, blocksAll: dis.includes('/'), blocksSearch: dis.some(d => path.startsWith(d.replace(/\*.*$/, '')) && d !== '/') };
}

function textLen(html) {
  const s = html.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
  return s.length;
}

function hrefs(html, base) {
  const out = [];
  for (const m of html.matchAll(/href\s*=\s*["']([^"'#]+)["']/gi)) {
    try { const u = new URL(m[1], base); if (u.protocol.startsWith('http') && u.host === new URL(base).host) out.push(u.toString()); } catch {}
  }
  return out;
}

const results = [];
for (const [group, name, base, build, term, slug] of sites) {
  const row = { group, name, base, term };
  const robots = await get(base + '/robots.txt');
  const path = build(term);
  row.robots = robots.status === 200 && !challenged(robots) ? robotsBlocks(robots.body, path) : { status: robots.status };
  const s = await get(base + path);
  row.search = { status: s.status, error: s.error, challenged: challenged(s), server: s.server, ct: s.ct.split(';')[0], finalHost: s.url ? new URL(s.url).host : null };
  row.search.found = s.status === 200 && !row.search.challenged && new RegExp(term.replace(/ /g, '.?'), 'i').test(s.body);
  const ex = await get(base + build('Ode of the Brave'));
  row.exampleFound = ex.status === 200 && !challenged(ex) && /ode.?of.?the.?brave/i.test(ex.body.replace(/value="[^"]*"/g, '').replace(/<title>.*?<\/title>/is, ''));
  if (row.search.found && !/json/.test(row.search.ct)) {
    const link = hrefs(s.body, s.url).find(h => slug.test(h) && !/search/i.test(h));
    if (link) {
      const n = await get(link);
      row.novel = { status: n.status, challenged: challenged(n), url: link };
      if (n.status === 200 && !row.novel.challenged) {
        const ls = hrefs(n.body, n.url);
        const chap = ls.find(h => /(chapter|chap|ch)[-_/]?0*1(?![0-9])/i.test(h)) || ls.find(h => /chapter/i.test(h));
        row.novel.chapterLinks = ls.filter(h => /chapter/i.test(h)).length;
        if (chap) {
          const c = await get(chap);
          row.chapter = { status: c.status, challenged: challenged(c), url: chap, textChars: c.status === 200 ? textLen(c.body) : 0 };
        }
      }
    }
  }
  results.push(row);
  console.log(JSON.stringify({
    name, group,
    robots: row.robots.blocksAll ? 'blocks-all' : row.robots.blocksSearch ? 'blocks-search' : row.robots.count !== undefined ? 'ok' : `http-${row.robots.status}`,
    search: row.search.status || row.search.error,
    challenged: row.search.challenged, found: row.search.found, server: row.search.server, host: row.search.finalHost,
    example: row.exampleFound,
    novel: row.novel?.status, chLinks: row.novel?.chapterLinks,
    chapter: row.chapter?.status, chChallenged: row.chapter?.challenged, chars: row.chapter?.textChars,
  }));
}
writeFileSync(process.argv[2], JSON.stringify(results, null, 2));
