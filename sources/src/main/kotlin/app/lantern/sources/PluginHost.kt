package app.lantern.sources

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.concurrent.Executors

/** What a plugin says about itself once it has loaded. */
internal class PluginInfo(val id: String?, val name: String?, val site: String?, val version: String?)

/**
 * One plugin in its own QuickJS instance, so plugins cannot see each other's globals.
 *
 * Kotlin talks to JS in two ways. Kotlin to JS: a call is a small script evaluated with [QuickJs.evaluate];
 * its arguments travel through the sync binding `__host.args()` rather than being pasted into the script,
 * which avoids quoting a 100 KB chapter. JS to Kotlin: `__host.http`, `__host.sleep`, `__host.log`.
 * `evaluate` awaits the promise of an `await` expression, so the script `await __lantern.run(...)` returns
 * the plugin's JSON once the plugin's own network calls and timers have finished.
 *
 * Failures are [IOException]s whose message says what happened: a blocked host, a security-check page, an HTTP
 * status, a timeout, or the plugin's own error.
 *
 * No Android dependency: assets arrive through [readAsset], so the laptop tests compile this same file.
 */
internal class PluginHost(
    val pluginId: String,
    private val readAsset: (String) -> String,
    private val log: (String) -> Unit,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
    private val loadTimeoutMs: Long = LOAD_TIMEOUT_MS,
) {
    val http = Http(pluginId, log)
    private val qjs: QuickJs = QuickJs.create(DISPATCHER)
    private val callLock = Mutex()

    // Set before each evaluate and read back by __host.op() / __host.args().
    @Volatile
    private var pendingOp = ""

    @Volatile
    private var pendingArgs = ""

    suspend fun load(): PluginInfo = withContext(DISPATCHER) {
        qjs.memoryLimit = MEMORY_LIMIT_BYTES
        qjs.maxStackSize = STACK_BYTES
        qjs.define("__host") {
            asyncFunction("http") { args -> http.handle(args[0] as String) }
            asyncFunction("sleep") { args ->
                delay((args[0] as Number).toLong())
                null
            }
            function("log") { args ->
                log("JS $pluginId ${args[0]}: ${args[1]}")
                null
            }
            function("op") { pendingOp }
            function("args") { pendingArgs }
        }
        qjs.evaluate<Any?>(readAsset("js/runtime.js"), "runtime.js")
        qjs.evaluate<Any?>(readAsset("js/host.js"), "host.js")
        val code = readAsset("plugins/$pluginId.js")
        val json = evalLocked("loadPlugin", code, "await __lantern.loadPlugin(__host.args())", loadTimeoutMs)
        val parsed = Json.parseToJsonElement(json).jsonObject
        http.site = parsed.string("site")?.ifEmpty { null }
        PluginInfo(parsed.string("id"), parsed.string("name"), parsed.string("site"), parsed.string("version"))
    }

    /** The plugin's `searchNovels(term, page)` result as a JSON string. */
    suspend fun search(term: String, page: Int): String = call("search", buildJsonArray { add(term); add(page) }.toString())

    /** The plugin's `parseNovel(path)` result as a JSON string, with parsePage chapters added when there are any. */
    suspend fun novel(path: String): String = call("novel", buildJsonArray { add(path) }.toString())

    /** The plugin's `parseChapter(path)` result as a JSON string, so the HTML is a quoted JSON string. */
    suspend fun chapter(path: String): String = call("chapter", buildJsonArray { add(path) }.toString())

    /** The chapter HTML as plain text, one paragraph per line, made by cheerio inside this instance. */
    suspend fun htmlToText(html: String): String = decodeString(call("text", buildJsonArray { add(html) }.toString()))

    fun close() {
        qjs.close()
    }

    private suspend fun call(op: String, argsJson: String): String =
        evalLocked(op, argsJson, "await __lantern.run(__host.op(), __host.args())", callTimeoutMs)

    private suspend fun evalLocked(op: String, args: String, script: String, timeoutMs: Long): String =
        callLock.withLock {
            pendingOp = op
            pendingArgs = args
            http.startCall()
            val out: String? = try {
                // withTimeoutOrNull cancels the evaluation, which interrupts it even if the plugin is stuck in a JS loop.
                // The timeout also covers time spent in Kotlin bindings (network waits and the politeness gaps).
                withTimeoutOrNull(timeoutMs) {
                    withContext(DISPATCHER) { qjs.evaluate<String>(script, "$op.js") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("JS $pluginId $op failed: ${e.message}")
                // The latest HTTP problem is the better explanation, since the plugin's error is usually a follow-on.
                throw IOException(http.lastProblem ?: e.message ?: e.javaClass.simpleName, e)
            }
            if (out == null) {
                log("JS $pluginId $op timed out after $timeoutMs ms")
                throw IOException("$pluginId did not finish within $timeoutMs ms")
            }
            // A plugin may swallow the error from a security-check page and return nothing. That still fails the call.
            http.lastChallenge?.let { throw IOException(it) }
            out
        }

    companion object {
        const val CALL_TIMEOUT_MS = 120_000L
        const val LOAD_TIMEOUT_MS = 30_000L
        private const val MEMORY_LIMIT_BYTES = 256L * 1024 * 1024
        private const val STACK_BYTES = 2L * 1024 * 1024

        // QuickJS recurses natively, and a deep DOM needs more than the 256 KB default stack. The thread that
        // runs it gets 16 MB so a 2 MB QuickJS limit is safe. Plugins run one after another, so one thread is enough.
        private val DISPATCHER: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(null, r, "quickjs", 16L * 1024 * 1024).apply { isDaemon = true }
        }.asCoroutineDispatcher()

        /** Turns a JSON string literal such as `"abc\n"` back into its text. */
        fun decodeString(jsonLiteral: String): String =
            (Json.parseToJsonElement(jsonLiteral) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    }
}
