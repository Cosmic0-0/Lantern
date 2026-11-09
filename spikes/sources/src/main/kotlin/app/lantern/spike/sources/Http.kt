package app.lantern.spike.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `http` binding for one plugin. This is the point of the spike, so the rules live here:
 *  1. a plugin may only reach its own site (see [hostAllowed]),
 *  2. one request at a time per host, at least [MIN_GAP_MS] between the end of one and the start of the next,
 *  3. a challenge page stops the request, and nothing retries or solves it,
 *  4. redirects are followed by hand, inside the allowlist, at most [MAX_REDIRECTS],
 *  5. 20 s connect and 30 s read timeouts.
 * It has no Android dependency, so the laptop test compiles this same file.
 */
class Http(private val pluginId: String, private val log: (String) -> Unit) {
    /** The plugin's `site` URL, set once the plugin has loaded. Until then every request is blocked. */
    @Volatile
    var site: String? = null

    val requests = AtomicInteger() // requests actually sent, redirect hops included
    val blocked = AtomicInteger()
    val challenges = AtomicInteger()

    /** [requestJson] is `{url, method, headers, body, encoding?}`. The reply is a JSON string, with `error` set on failure. */
    suspend fun handle(requestJson: String): String {
        val req = JSONObject(requestJson)
        val method = req.optString("method", "GET").uppercase()
        val headers = LinkedHashMap<String, String>()
        req.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        val body: String? = if (req.isNull("body")) null else req.getString("body")
        val encoding: String? = if (req.isNull("encoding")) null else req.optString("encoding").ifEmpty { null }
        return try {
            execute(req.getString("url"), method, headers, body, encoding)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun execute(
        startUrl: String,
        startMethod: String,
        headers: Map<String, String>,
        startBody: String?,
        encoding: String?,
    ): String {
        var url = startUrl
        var method = startMethod
        var body = startBody
        for (hop in 0..MAX_REDIRECTS) {
            val host = hostOf(url) ?: return failure(method, url, "bad url $url")
            if (!hostAllowed(host, site)) {
                blocked.incrementAndGet()
                return failure(method, url, "blocked host $host")
            }
            var started = System.nanoTime()
            val res = try {
                // The clock starts once this request has the host to itself, so the logged time leaves out the 1 s gap.
                politely(host) {
                    started = System.nanoTime()
                    requestOnce(url, method, headers, body, encoding)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                log("HTTP $pluginId $method $url -> ERROR $msg ${msSince(started)}ms")
                return error(msg)
            }
            log("HTTP $pluginId $method $url -> ${res.status} ${res.bytes}B ${msSince(started)}ms")

            if (res.status in setOf(403, 429, 503) && isChallenge(res)) {
                challenges.incrementAndGet()
                val msg = "challenge page from $host (status ${res.status})"
                log("HTTP $pluginId $method $url -> $msg")
                return error(msg)
            }
            if (res.status in REDIRECT_STATUSES) {
                val location = res.headers["location"] ?: return reply(res)
                if (hop == MAX_REDIRECTS) return error("too many redirects from $startUrl")
                url = URL(URL(url), location).toString()
                if (res.status != 307 && res.status != 308) {
                    // Browsers turn a redirected POST into a GET and drop the body; 307 and 308 keep both.
                    method = if (method == "HEAD") "HEAD" else "GET"
                    body = null
                }
                continue
            }
            return reply(res)
        }
        return error("too many redirects from $startUrl")
    }

    private class Res(
        val status: Int,
        val statusText: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
        val bytes: Int,
    )

    private suspend fun requestOnce(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        encoding: String?,
    ): Res = withContext(Dispatchers.IO) {
        requests.incrementAndGet()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = method
            // No Accept-Encoding and no Connection here: HttpURLConnection sets gzip and keep-alive itself.
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { readCapped(it) } ?: ByteArray(0)
            val hdrs = LinkedHashMap<String, String>()
            conn.headerFields.forEach { (k, v) -> if (k != null) hdrs[k.lowercase()] = v.joinToString(", ") }
            val charset = charsetFor(encoding, hdrs["content-type"])
            Res(status, conn.responseMessage ?: "", conn.url.toString(), hdrs, String(bytes, charset), bytes.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY_BYTES) throw java.io.IOException("response larger than $MAX_BODY_BYTES bytes")
        }
        return out.toByteArray()
    }

    private fun isChallenge(res: Res): Boolean {
        if (res.headers["cf-mitigated"]?.contains("challenge", ignoreCase = true) == true) return true
        return CHALLENGE_MARKERS.any { res.body.contains(it, ignoreCase = true) }
    }

    private fun reply(res: Res): String = JSONObject().apply {
        put("status", res.status)
        put("statusText", res.statusText)
        put("url", res.url)
        put("headers", JSONObject(res.headers))
        put("body", res.body)
    }.toString()

    private fun error(message: String): String = JSONObject().put("error", message).toString()

    private fun failure(method: String, url: String, message: String): String {
        log("HTTP $pluginId $method $url -> $message")
        return error(message)
    }

    companion object {
        const val MIN_GAP_MS = 1000L
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_BODY_BYTES = 20 * 1024 * 1024
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val CHALLENGE_MARKERS = listOf("Just a moment...", "challenge-platform", "cf-chl", "ddos-guard")

        private class Gate {
            val mutex = Mutex()
            var lastEndNanos: Long? = null
        }

        // Shared by every plugin, so two plugins on one host would also queue behind each other.
        private val gates = ConcurrentHashMap<String, Gate>()

        private fun msSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

        /** One request at a time per host, and [MIN_GAP_MS] between the end of one and the start of the next. */
        private suspend fun <T> politely(host: String, block: suspend () -> T): T {
            val gate = gates.getOrPut(host) { Gate() }
            return gate.mutex.withLock {
                gate.lastEndNanos?.let { end ->
                    val wait = MIN_GAP_MS - (System.nanoTime() - end) / 1_000_000
                    if (wait > 0) delay(wait)
                }
                try {
                    block()
                } finally {
                    gate.lastEndNanos = System.nanoTime()
                }
            }
        }

        fun hostOf(url: String): String? = try {
            val u = URL(url)
            if (u.protocol != "http" && u.protocol != "https") null else u.host.lowercase().ifEmpty { null }
        } catch (e: Exception) {
            null
        }

        /** The last two labels of [host], such as `novgo.net` for `www.novgo.net`. */
        fun registrableDomain(host: String): String = host.split('.').takeLast(2).joinToString(".")

        /** Allowed: the plugin's own site host, or a subdomain of (or the same as) the site's registrable domain. */
        fun hostAllowed(host: String, site: String?): Boolean {
            val siteHost = site?.let { hostOf(it) } ?: return false
            val h = host.lowercase()
            val base = registrableDomain(siteHost)
            return h == siteHost || h == base || h.endsWith(".$base")
        }

        private fun charsetFor(override: String?, contentType: String?): Charset {
            val name = override
                ?: contentType?.let { Regex("charset=\"?([^;\"\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?: return Charsets.UTF_8
            return try {
                Charset.forName(name)
            } catch (e: Exception) {
                Charsets.UTF_8
            }
        }
    }
}
