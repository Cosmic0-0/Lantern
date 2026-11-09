// QuickJS has no URL, URLSearchParams or atob. Two of the plugins use URL and URLSearchParams, and
// htmlparser2's entities module calls atob while it loads. These imports must stay first.
import 'core-js/actual/atob';
import 'core-js/actual/url';
import 'core-js/actual/url-search-params';
// "slim" is htmlparser2 only: no parse5 and no undici, which would drag in Node modules.
import { load } from 'cheerio/slim';
import { Parser } from 'htmlparser2';
import dayjs from 'dayjs';

globalThis.__lanternLibs = { cheerio: { load }, htmlparser2: { Parser }, dayjs };
