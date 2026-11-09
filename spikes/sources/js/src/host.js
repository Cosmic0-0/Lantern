// Plugin host glue for Spike 3. Evaluated after runtime.js in the same QuickJS context.
//
// Kotlin provides globalThis.__host:
//   http(requestJson)  async, returns a JSON string {status, statusText, url, headers, body, error}
//   sleep(ms)          async
//   log(level, text)   sync, optional
// This file defines globalThis.__lantern = { loadPlugin(code), run(op, argsJson), htmlToText(html) }.
(function () {
  'use strict';

  var g = globalThis;
  var host = g.__host;
  var libs = g.__lanternLibs;
  if (!host) throw new Error('__host is missing');
  if (!libs) throw new Error('runtime.js has not been evaluated');

  var USER_AGENT = 'LanternSpike/0.1 (personal project)';
  var MAX_EXTRA_PAGES = 60;

  // ---- console and timers: QuickJS has neither -------------------------------------------------

  function describe(v) {
    if (v instanceof Error) return v.stack || (v.name + ': ' + v.message);
    if (typeof v === 'string') return v;
    try {
      var s = JSON.stringify(v);
      return s === undefined ? String(v) : s;
    } catch (e) {
      return String(v);
    }
  }

  function logger(level) {
    return function () {
      var text = Array.prototype.map.call(arguments, describe).join(' ');
      if (typeof host.log === 'function') host.log(level, text);
    };
  }

  if (typeof g.console === 'undefined') {
    g.console = {
      log: logger('log'),
      info: logger('info'),
      debug: logger('debug'),
      warn: logger('warn'),
      error: logger('error'),
    };
  }

  var timers = new Set();
  var nextTimerId = 1;
  g.setTimeout = function (fn, ms) {
    var extra = Array.prototype.slice.call(arguments, 2);
    var id = nextTimerId++;
    timers.add(id);
    host.sleep(Math.max(0, Number(ms) || 0)).then(function () {
      if (!timers.delete(id)) return; // cleared meanwhile
      try {
        fn.apply(undefined, extra);
      } catch (e) {
        console.error('timer callback failed', e);
      }
    });
    return id;
  };
  g.clearTimeout = function (id) {
    timers.delete(id);
  };

  // ---- Headers and Response ---------------------------------------------------------------------

  class Headers {
    constructor(init) {
      this._map = new Map(); // lowercased name -> value
      if (init) {
        for (var k of Object.keys(init)) this._map.set(k.toLowerCase(), String(init[k]));
      }
    }
    get(name) {
      var v = this._map.get(String(name).toLowerCase());
      return v === undefined ? null : v;
    }
    has(name) {
      return this._map.has(String(name).toLowerCase());
    }
    forEach(cb, thisArg) {
      this._map.forEach(function (value, key) {
        cb.call(thisArg, value, key);
      });
    }
  }

  class Response {
    constructor(data) {
      this.status = data.status;
      this.statusText = data.statusText || '';
      this.url = data.url;
      this.ok = data.status >= 200 && data.status <= 299;
      this.headers = new Headers(data.headers);
      this._body = data.body == null ? '' : String(data.body);
    }
    text() {
      return Promise.resolve(this._body);
    }
    json() {
      var body = this._body;
      return new Promise(function (resolve) {
        resolve(JSON.parse(body));
      });
    }
    blob() {
      throw new Error('Response.blob is not supported in this spike');
    }
  }

  // ---- fetch helpers the plugins get as '@libs/fetch' --------------------------------------------

  function collectHeaders(into, headers) {
    if (!headers) return;
    if (Array.isArray(headers)) {
      headers.forEach(function (pair) {
        into[String(pair[0]).toLowerCase()] = [String(pair[0]), String(pair[1])];
      });
    } else if (typeof headers.forEach === 'function') {
      headers.forEach(function (value, name) {
        into[String(name).toLowerCase()] = [String(name), String(value)];
      });
    } else {
      Object.keys(headers).forEach(function (name) {
        into[name.toLowerCase()] = [name, String(headers[name])];
      });
    }
  }

  function buildRequest(url, init) {
    init = init || {};
    var merged = {};
    // Same defaults as LNReader's fetchApi, minus Accept-Encoding and Connection: the HTTP client sets those.
    collectHeaders(merged, {
      Accept: '*/*',
      'Accept-Language': '*',
      'Cache-Control': 'max-age=0',
      'User-Agent': USER_AGENT,
    });
    collectHeaders(merged, init.headers);

    var body = init.body;
    if (body === undefined || body === null) {
      body = null;
    } else if (typeof body === 'string') {
      // as is
    } else if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
      body = body.toString();
      if (!merged['content-type']) {
        merged['content-type'] = ['Content-Type', 'application/x-www-form-urlencoded'];
      }
    } else if (
      (typeof FormData !== 'undefined' && body instanceof FormData) ||
      (typeof body === 'object' && typeof body.append === 'function' && typeof body.getAll === 'function')
    ) {
      throw new Error('FormData bodies are not supported in this spike');
    } else {
      throw new Error('Unsupported request body type: ' + Object.prototype.toString.call(body));
    }

    var headers = {};
    Object.keys(merged).forEach(function (k) {
      headers[merged[k][0]] = merged[k][1];
    });
    return {
      url: String(url),
      method: String(init.method || 'GET').toUpperCase(),
      headers: headers,
      body: body,
    };
  }

  async function doFetch(url, init, encoding) {
    var request = buildRequest(url, init);
    if (encoding) request.encoding = String(encoding);
    var reply = JSON.parse(await host.http(JSON.stringify(request)));
    if (reply.error) throw new Error(reply.error);
    return new Response(reply);
  }

  function fetchApi(url, init) {
    return doFetch(url, init);
  }

  async function fetchText(url, init, encoding) {
    try {
      var res = await doFetch(url, init, encoding);
      if (!res.ok) return '';
      return await res.text();
    } catch (e) {
      return '';
    }
  }

  function fetchProto() {
    throw new Error('fetchProto is not supported in this spike');
  }

  // ---- the rest of LNReader's '@libs/*' modules --------------------------------------------------

  var NovelStatus = {
    Unknown: 'Unknown',
    Ongoing: 'Ongoing',
    Completed: 'Completed',
    Licensed: 'Licensed',
    PublishingFinished: 'Publishing Finished',
    Cancelled: 'Cancelled',
    OnHiatus: 'On Hiatus',
    STUB: 'STUB',
    Inactive: 'Inactive',
  };

  var FilterTypes = {
    TextInput: 'Text',
    Picker: 'Picker',
    CheckboxGroup: 'Checkbox',
    Switch: 'Switch',
    ExcludableCheckboxGroup: 'XCheckbox',
  };

  var defaultCover =
    'https://github.com/lnreader/lnreader-plugins/blob/master/public/static/coverNotAvailable.webp?raw=true';

  function isUrlAbsolute(url) {
    if (url) {
      if (url.indexOf('//') === 0) return true; // protocol-relative
      if (url.indexOf('://') === -1) return false; // no protocol
      if (url.indexOf('.') === -1) return false; // no dot, so no TLD
      if (url.indexOf('/') === -1) return false; // no slash at all
      if (url.indexOf(':') > url.indexOf('/')) return false; // first colon after the first slash
      if (url.indexOf('://') < url.indexOf('.')) return true; // protocol before the first dot
    }
    return false;
  }

  function makeStore() {
    var data = new Map();
    return {
      get: function (key) {
        return data.get(key);
      },
      set: function (key, value) {
        data.set(key, value);
      },
      delete: function (key) {
        data.delete(key);
      },
      clearAll: function () {
        data.clear();
      },
      getAllKeys: function () {
        return Array.from(data.keys());
      },
    };
  }

  // ---- loading a plugin ---------------------------------------------------------------------------

  var plugin = null;

  function loadPlugin(code) {
    var modules = {
      cheerio: { load: libs.cheerio.load },
      htmlparser2: { Parser: libs.htmlparser2.Parser },
      dayjs: libs.dayjs,
      '@libs/novelStatus': { NovelStatus: NovelStatus },
      '@libs/filterInputs': { FilterTypes: FilterTypes },
      '@libs/defaultCover': { defaultCover: defaultCover },
      '@libs/isAbsoluteUrl': { isUrlAbsolute: isUrlAbsolute },
      '@libs/fetch': { fetchApi: fetchApi, fetchText: fetchText, fetchProto: fetchProto },
      '@libs/storage': { storage: makeStore(), localStorage: makeStore(), sessionStorage: makeStore() },
    };
    function _require(name) {
      if (Object.prototype.hasOwnProperty.call(modules, name)) return modules[name];
      throw new Error('module not available: ' + name);
    }
    // The same call LNReader makes.
    plugin = Function(
      'require',
      'module',
      'const exports = module.exports = {}; ' + code + '; return exports.default',
    )(_require, {});
    if (!plugin || typeof plugin !== 'object') throw new Error('plugin did not set exports.default');
    return JSON.stringify({ id: plugin.id, name: plugin.name, site: plugin.site, version: plugin.version });
  }

  // ---- running it ---------------------------------------------------------------------------------

  async function parseNovel(path) {
    var novel = await plugin.parseNovel(path);
    var totalPages = Number(novel && novel.totalPages) || 0;
    // The plugins here page inside parseNovel, so this only matters for ones that return page 1 plus totalPages.
    if (novel && totalPages > 1 && typeof plugin.parsePage === 'function') {
      var chapters = (novel.chapters || []).slice();
      var last = Math.min(totalPages, MAX_EXTRA_PAGES);
      for (var p = 2; p <= last; p++) {
        var page = await plugin.parsePage(path, String(p));
        if (page && page.chapters) chapters.push.apply(chapters, page.chapters);
      }
      novel.chapters = chapters;
      novel.pagesFetched = last;
    }
    return novel;
  }

  // Text of the HTML, one line per paragraph, in document order. A block element (p, div, h1-h6, li and
  // the other block tags) ends the current line and so does <br>, so text that sits directly inside a
  // wrapper div beside nested paragraphs is kept as its own line instead of being dropped.
  var BLOCK_TAGS = new Set(
    ('address article aside blockquote details dd div dl dt fieldset figcaption figure footer form ' +
      'h1 h2 h3 h4 h5 h6 header hr li main nav ol p pre section table tbody thead tfoot tr ul').split(' '),
  );

  function htmlToText(html) {
    var $ = libs.cheerio.load(String(html == null ? '' : html));
    var lines = [];
    var buf = '';
    function flush() {
      var t = buf.replace(/\s+/g, ' ').trim();
      buf = '';
      if (t) lines.push(t);
    }
    function walk(node) {
      if (node.type === 'text') {
        buf += node.data;
        return;
      }
      if (node.type !== 'tag') return; // comments, scripts, styles, directives
      if (node.name === 'br') {
        flush();
        return;
      }
      var block = BLOCK_TAGS.has(node.name);
      if (block) flush();
      var kids = node.children || [];
      for (var i = 0; i < kids.length; i++) walk(kids[i]);
      if (block) flush();
    }
    var root = $.root()[0];
    var top = (root && root.children) || [];
    for (var i = 0; i < top.length; i++) walk(top[i]);
    flush();
    if (lines.length === 0) {
      lines = $.root()
        .text()
        .split(/\n+/)
        .map(function (s) {
          return s.replace(/\s+/g, ' ').trim();
        })
        .filter(Boolean);
    }
    return lines.join('\n');
  }

  async function run(op, argsJson) {
    var args = argsJson ? JSON.parse(argsJson) : [];
    var result;
    if (op === 'text') {
      result = htmlToText(args[0]);
    } else {
      if (!plugin) throw new Error('no plugin loaded');
      switch (op) {
        case 'search':
          result = await plugin.searchNovels(String(args[0]), Number(args[1]) || 1);
          break;
        case 'novel':
          result = await parseNovel(String(args[0]));
          break;
        case 'chapter':
          result = await plugin.parseChapter(String(args[0]));
          break;
        default:
          throw new Error('unknown op: ' + op);
      }
    }
    return JSON.stringify(result === undefined ? null : result);
  }

  g.__lantern = { loadPlugin: loadPlugin, run: run, htmlToText: htmlToText };
})();
