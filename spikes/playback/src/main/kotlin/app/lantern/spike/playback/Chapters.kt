package app.lantern.spike.playback

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chapter text for the one fixed book. Throwaway: a later spike replaces it with site plugins.
 * Text is cached as getExternalFilesDir/chapters/<n>.txt, one paragraph per line.
 */
object Chapters {
    private const val URL_FORMAT = "https://novgo.net/ode-of-the-brave-novel/chapter-%d.html"
    private const val USER_AGENT = "LanternSpike/0.1 (personal project)"

    fun textFile(ctx: Context, n: Int) = File(ctx.applicationContext.getExternalFilesDir(null), "chapters/$n.txt")

    private val fetchLock = Any()
    private val sentenceCache = HashMap<Int, List<String>>()

    /** Blocking; call off the main thread. True when the chapter's text is on disk afterwards. No retry here. */
    fun fetch(ctx: Context, n: Int): Boolean = synchronized(fetchLock) { fetchLocked(ctx, n) }

    private fun fetchLocked(ctx: Context, n: Int): Boolean {
        val file = textFile(ctx, n)
        if (file.isFile) return true
        val start = SystemClock.elapsedRealtime()
        return try {
            val html = download(String.format(URL_FORMAT, n))
            val lines = extract(html)
            if (lines.isEmpty()) throw IOException("no chapter text found in ${html.length} chars of HTML")
            val text = lines.joinToString("\n")
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$n.txt.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            // New text may differ from what an earlier run turned into audio, so drop that audio first.
            // The generator only starts on a chapter once its text exists, so nothing is writing there yet.
            AudioCache.dir(ctx, n).deleteRecursively()
            if (!tmp.renameTo(file)) throw IOException("cannot rename ${tmp.name}")
            EventLog.log("FETCH", "ok chapter=$n chars=${text.length} ms=${SystemClock.elapsedRealtime() - start} ${EventLog.state(ctx)}")
            true
        } catch (e: Exception) {
            EventLog.log("FETCH", "fail chapter=$n $e ${EventLog.state(ctx)}")
            false
        }
    }

    /** The chapter split into sentences, or null while its text is not on disk. */
    fun sentences(ctx: Context, n: Int): List<String>? = synchronized(sentenceCache) {
        val cached = sentenceCache[n]
        if (cached != null) {
            cached
        } else {
            val file = textFile(ctx, n)
            if (!file.isFile) {
                null
            } else {
                SentenceSplitter.split(file.readText(Charsets.UTF_8)).also { sentenceCache[n] = it }
            }
        }
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code for $url")
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    // ---- HTML to lines. Pure, so it can be checked without a phone. ----

    private val navStart = Regex("""<div[^>]*(chapter-nav|chr-nav|ads)|id="chapter-nav""", RegexOption.IGNORE_CASE)
    private val paragraph = Regex("""<p\b[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
    private val tag = Regex("""<[^>]*>""")
    private val entity = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")

    // Java's \s is ASCII only; JavaScript's also covers these, which is what the text was cleaned with before.
    private val spaces = Regex("""[\s   -     　﻿]+""")

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "hellip" to "…", "mdash" to "—", "ndash" to "–",
    )

    fun extract(html: String): List<String> {
        val at = html.indexOf("id=\"chapter-content\"")
        if (at < 0) return emptyList()
        var body = html.substring(at)
        navStart.find(body)?.let { body = body.substring(0, it.range.first) }

        val lines = paragraph.findAll(body)
            .map { collapse(decode(tag.replace(it.groupValues[1], ""))) }
            .filter { it.isNotEmpty() }
            .toList()

        val errors = lines.indexOfFirst { it.startsWith("If you find any errors") }
        return (if (errors >= 0) lines.subList(0, errors) else lines)
            .filter { !it.contains("READ MORE") && !it.contains("http") }
    }

    private fun collapse(s: String) = spaces.replace(s, " ").trim()

    private fun decode(s: String) = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> codePoint(e.substring(2), 16) ?: m.value
            e.startsWith("#") -> codePoint(e.substring(1), 10) ?: m.value
            else -> named[e] ?: m.value
        }
    }

    private fun codePoint(digits: String, radix: Int): String? {
        val cp = digits.toIntOrNull(radix) ?: return null
        return if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null
    }
}
