package app.lantern.spike.sources

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spike 3: run LNReader's compiled site plugins in QuickJS on this phone and record what they return.
 * One run per process (the driver force-stops between runs). Extras: plugins (comma separated ids),
 * query, fallback. Results go to files/out/results.jsonl and files/out/<id>-<n>.txt.
 */
class MainActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(16, 16, 16, 16)
        }
        scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(view)
        }
        setContentView(scroll)
        current = this

        val plugins = (intent.getStringExtra("plugins") ?: DEFAULT_PLUGINS).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val query = intent.getStringExtra("query") ?: "Ode of the Brave"
        val fallback = intent.getStringExtra("fallback") ?: "Shadow Slave"

        if (!started.compareAndSet(false, true)) {
            show("A run already started in this process, so this launch does nothing. See logcat (tag $TAG).")
        } else {
            val app = applicationContext
            val assets = app.assets
            val out = File(app.getExternalFilesDir(null) ?: filesDir, "out").also { it.mkdirs() }
            Thread({ runAll(plugins, query, fallback, out) { assets.open(it).bufferedReader().use { r -> r.readText() } } }, "spike-run").start()
        }
        refresh()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun refresh() {
        view.text = synchronized(screen) { screen.toString() }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        const val TAG = "LanternSources"
        const val DEFAULT_PLUGINS = "anf.net,readnovelfull,novelcool,novelbuddy,novelbin"
        const val LOG_CHUNK = 3500 // logcat cuts a line near 4000 bytes
        const val SHORT_PARAGRAPH_CHARS = 40
        const val MAX_JUNK_LINES = 10
        val JUNK = Regex(
            "(novel(fire|full|bin|cool|hall|buddy)|\\.com|\\.net|\\.org|read (more|at|on|the latest)|latest chapter|" +
                "updated (by|from)|discord|patreon|translat(or|ion|ed) by|stolen|report chapter|support (us|the)|lightnovel)",
            RegexOption.IGNORE_CASE,
        )

        val started = AtomicBoolean(false)
        val screen = StringBuilder()

        // The worker thread outlives the activity (rotation, Back), so what it shows is kept here.
        @Volatile
        var current: MainActivity? = null

        fun show(msg: String) {
            synchronized(screen) { screen.append(msg).append('\n') }
            current?.let { a -> a.runOnUiThread { a.refresh() } }
        }

        fun say(msg: String) {
            var rest = msg
            var first = true
            while (rest.isNotEmpty()) {
                val chunk = rest.take(LOG_CHUNK)
                rest = rest.drop(LOG_CHUNK)
                Log.i(TAG, if (first) chunk else "(cont) $chunk")
                first = false
            }
            if (msg.isEmpty()) Log.i(TAG, "")
            show(if (msg.length > 300) msg.take(300) + "..." else msg)
        }

        fun ms(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

        fun runAll(plugins: List<String>, query: String, fallback: String, outDir: File, readAsset: (String) -> String) {
            say("START plugins=${plugins.joinToString()} query='$query' fallback='$fallback'")
            try {
                runBlocking {
                    for (id in plugins) {
                        try {
                            runPlugin(id, query, fallback, outDir, readAsset)
                        } catch (e: Throwable) {
                            say("FAIL $id ${e.javaClass.simpleName}: ${e.message}")
                            Log.e(TAG, "plugin $id failed", e)
                        }
                    }
                }
            } catch (e: Throwable) {
                say("FAIL ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "run failed", e)
            } finally {
                say("DONE")
            }
        }

        /** The first five results as {name, path}, for the results file. */
        fun brief(arr: JSONArray, limit: Int) = JSONArray().also { out ->
            for (i in 0 until minOf(limit, arr.length())) {
                val o = arr.getJSONObject(i)
                out.put(JSONObject().put("name", o.opt("name")).put("path", o.opt("path")))
            }
        }

        fun briefChapters(arr: JSONArray, from: Int, to: Int) = JSONArray().also { out ->
            for (i in from until to) {
                val o = arr.getJSONObject(i)
                out.put(JSONObject().put("name", o.opt("name")).put("path", o.opt("path")))
            }
        }

        suspend fun runPlugin(id: String, query: String, fallback: String, outDir: File, readAsset: (String) -> String) {
            val errors = JSONArray()
            val result = JSONObject().put("plugin", id).put("run_at", OffsetDateTime.now().toString())
            val total = System.nanoTime()
            val host = PluginHost(id, readAsset) { Log.i(TAG, it); show(it) }
            fun fail(step: String, e: Throwable) {
                val msg = "$step: ${e.javaClass.simpleName}: ${e.message}"
                errors.put(msg)
                say("ERROR $id $msg")
            }
            try {
                // 1. load
                var t = System.nanoTime()
                try {
                    val info = host.load()
                    result.put("info", info).put("load_ms", ms(t))
                    say("LOAD $id ms=${ms(t)} site=${info.optString("site")} version=${info.optString("version")}")
                } catch (e: Throwable) {
                    result.put("load_ms", ms(t))
                    say("LOAD $id ms=${ms(t)} FAILED")
                    fail("load", e)
                    return
                } finally {
                    result.put("errors", errors)
                }

                // 2. search, then the fallback title if the query finds nothing that contains it
                var termUsed = query
                var results = JSONArray()
                t = System.nanoTime()
                try {
                    results = JSONArray(host.search(query, 1))
                    if (!anyContains(results, query)) {
                        say("SEARCH $id '$query' -> ${results.length()} results, none contain it; trying '$fallback'")
                        termUsed = fallback
                        results = JSONArray(host.search(fallback, 1))
                    }
                } catch (e: Throwable) {
                    fail("search '$termUsed'", e)
                }
                result.put("query_used", termUsed)
                    .put("search_ms", ms(t))
                    .put("search_count", results.length())
                    .put("search_first5", brief(results, 5))
                say("SEARCH $id '$termUsed' -> ${results.length()} results ms=${ms(t)}")
                if (results.length() == 0) return
                val pick = (0 until results.length()).map { results.getJSONObject(it) }
                    .firstOrNull { it.optString("name").contains(termUsed, ignoreCase = true) } ?: results.getJSONObject(0)
                result.put("picked", JSONObject().put("name", pick.opt("name")).put("path", pick.opt("path")))

                // 3. novel
                t = System.nanoTime()
                val chapters: JSONArray
                try {
                    val novel = JSONObject(host.novel(pick.getString("path")))
                    chapters = novel.optJSONArray("chapters") ?: JSONArray()
                    val n = chapters.length()
                    result.put(
                        "novel",
                        JSONObject()
                            .put("name", novel.opt("name"))
                            .put("path", novel.opt("path"))
                            .put("chapter_count", n)
                            .put("first3", briefChapters(chapters, 0, minOf(3, n)))
                            .put("last3", briefChapters(chapters, maxOf(0, n - 3), n))
                            .put("ms", ms(t)),
                    )
                    say("NOVEL $id '${novel.optString("name")}' chapters=$n ms=${ms(t)}")
                } catch (e: Throwable) {
                    result.put("novel", JSONObject().put("ms", ms(t)))
                    fail("novel", e)
                    return
                }

                // 4. chapters 1 and 50
                val chapterResults = JSONArray()
                result.put("chapters", chapterResults)
                for (index in listOf(0, 49)) {
                    if (index >= chapters.length()) continue
                    val ch = chapters.getJSONObject(index)
                    val number = index + 1
                    val entry = JSONObject().put("index", index).put("name", ch.opt("name")).put("path", ch.opt("path"))
                    chapterResults.put(entry)
                    t = System.nanoTime()
                    try {
                        val html = PluginHost.decodeString(host.chapter(ch.getString("path")))
                        entry.put("ms", ms(t)).put("html_len", html.length)
                        val text = host.htmlToText(html)
                        val lines = if (text.isEmpty()) emptyList() else text.split('\n')
                        val junk = lines.filter { JUNK.containsMatchIn(it) }
                        File(outDir, "$id-$number.txt").writeText(text, Charsets.UTF_8)
                        entry.put("text_chars", text.length)
                            .put("paragraphs", lines.size)
                            .put("short_paragraphs", lines.count { it.length < SHORT_PARAGRAPH_CHARS })
                            .put("junk_count", junk.size)
                            .put("junk_lines", JSONArray(junk.take(MAX_JUNK_LINES).map { it.take(160) }))
                        say("CHAPTER $id #$number html=${html.length} text=${text.length} paragraphs=${lines.size} junk=${junk.size} ms=${ms(t)}")
                    } catch (e: Throwable) {
                        entry.put("ms", ms(t))
                        fail("chapter #$number", e)
                    }
                }
            } finally {
                result.put("http_requests", host.http.requests.get())
                    .put("http_blocked", host.http.blocked.get())
                    .put("http_challenges", host.http.challenges.get())
                    .put("js_heap_bytes", host.heapBytes)
                    .put("total_ms", ms(total))
                    .put("errors", errors)
                try {
                    host.close()
                } catch (e: Throwable) {
                    Log.w(TAG, "close failed for $id", e)
                }
                File(outDir, "results.jsonl").appendText(result.toString() + "\n", Charsets.UTF_8)
                say("RESULT $result")
            }
        }

        fun anyContains(results: JSONArray, term: String): Boolean =
            (0 until results.length()).any { results.getJSONObject(it).optString("name").contains(term, ignoreCase = true) }
    }
}
