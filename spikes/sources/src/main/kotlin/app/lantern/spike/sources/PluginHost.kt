package app.lantern.spike.sources

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors

/**
 * One plugin in its own QuickJS instance, so plugins cannot see each other's globals.
 *
 * Kotlin talks to JS in two ways. Kotlin to JS: a call is a small script evaluated with [QuickJs.evaluate];
 * its arguments travel through the sync binding `__host.args()` rather than being pasted into the script,
 * which avoids quoting a 100 KB chapter. JS to Kotlin: `__host.http`, `__host.sleep`, `__host.log`.
 * `evaluate` awaits the promise of an `await` expression, so the script `await __lantern.run(...)` returns
 * the plugin's JSON once the plugin's own network calls and timers have finished.
 *
 * No Android dependency: assets arrive through [readAsset], so the laptop test compiles this same file.
 */
class PluginHost(
    val pluginId: String,
    private val readAsset: (String) -> String,
    private val log: (String) -> Unit,
) {
    val http = Http(pluginId, log)
    private val qjs: QuickJs = QuickJs.create(DISPATCHER)
    private val callLock = Mutex()

    // Set before each evaluate and read back by __host.op() / __host.args().
    @Volatile
    private var pendingOp = ""

    @Volatile
    private var pendingArgs = ""

    /** `{id, name, site, version}` once [load] has finished. */
    var info: JSONObject? = null
        private set

    /** Bytes of JS heap in use, for the results file. */
    val heapBytes: Long get() = if (qjs.isClosed) -1 else qjs.memoryUsage.memoryUsedSize

    suspend fun load(): JSONObject = withContext(DISPATCHER) {
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
        val json = evalLocked("loadPlugin", code, "await __lantern.loadPlugin(__host.args())", LOAD_TIMEOUT_MS)
        val parsed = JSONObject(json)
        http.site = parsed.optString("site").ifEmpty { null }
        info = parsed
        parsed
    }

    /** The plugin's `searchNovels(term, page)` result as a JSON string. */
    suspend fun search(term: String, page: Int): String = call("search", "[${JSONObject.quote(term)},$page]")

    /** The plugin's `parseNovel(path)` result as a JSON string, with parsePage chapters added when there are any. */
    suspend fun novel(path: String): String = call("novel", "[${JSONObject.quote(path)}]")

    /** The plugin's `parseChapter(path)` result as a JSON string, so the HTML is a quoted JSON string. */
    suspend fun chapter(path: String): String = call("chapter", "[${JSONObject.quote(path)}]")

    /** The chapter HTML as plain text, one paragraph per line, made by cheerio inside this instance. */
    suspend fun htmlToText(html: String): String = decodeString(call("text", "[${JSONObject.quote(html)}]"))

    fun close() {
        qjs.close()
    }

    private suspend fun call(op: String, argsJson: String): String =
        evalLocked(op, argsJson, "await __lantern.run(__host.op(), __host.args())", CALL_TIMEOUT_MS)

    private suspend fun evalLocked(op: String, args: String, script: String, timeoutMs: Long): String =
        callLock.withLock {
            pendingOp = op
            pendingArgs = args
            // withTimeout cancels the evaluation, which interrupts it even if the plugin is stuck in a JS loop.
            // The timeout also covers time spent in Kotlin bindings (network waits and the politeness gaps).
            withTimeout(timeoutMs) {
                withContext(DISPATCHER) { qjs.evaluate<String>(script, "$op.js") }
            }
        }

    companion object {
        const val CALL_TIMEOUT_MS = 120_000L
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val MEMORY_LIMIT_BYTES = 256L * 1024 * 1024
        private const val STACK_BYTES = 2L * 1024 * 1024

        // QuickJS recurses natively, and a deep DOM needs more than the 256 KB default stack. The thread that
        // runs it gets 16 MB so a 2 MB QuickJS limit is safe. Plugins run one after another, so one thread is enough.
        private val DISPATCHER: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(null, r, "quickjs", 16L * 1024 * 1024).apply { isDaemon = true }
        }.asCoroutineDispatcher()

        /** Turns a JSON string literal such as `"abc\n"` back into its text. */
        fun decodeString(jsonLiteral: String): String = JSONTokener(jsonLiteral).nextValue() as? String ?: ""
    }
}
