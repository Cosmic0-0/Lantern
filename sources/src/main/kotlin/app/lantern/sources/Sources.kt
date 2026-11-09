package app.lantern.sources

import app.lantern.core.ChapterRef
import app.lantern.core.Listing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The novel sources: LNReader site plugins running in QuickJS, behind plain Kotlin types.
 *
 * Each plugin gets its own QuickJS instance, created on first use and kept until [close]. The network rules
 * (own site only, one request at a time per site, a pause between requests, no solving of security checks) are
 * enforced in Kotlin and are described on [Http]. Every failure comes out as an [IOException] with a message
 * that says what happened, or as a failed [Result] from [search].
 */
class Sources internal constructor(
    private val sourceIds: List<String>,
    private val log: (String) -> Unit,
    private val readAsset: (String) -> String,
) : AutoCloseable {
    constructor(
        sourceIds: List<String> = DEFAULT_SOURCES,
        log: (String) -> Unit = {},
    ) : this(sourceIds, log, ::readResource)

    private val loadLock = Mutex()
    private val hosts = ConcurrentHashMap<String, PluginHost>()

    @Volatile
    private var closed = false

    /** Searches every source; each emits once, as it finishes, with its listings or its failure. */
    fun search(term: String): Flow<Pair<String, Result<List<Listing>>>> = channelFlow {
        val query = term.trim()
        for (id in sourceIds) {
            launch {
                // A blank search would only cost every site a pointless request.
                val result = if (query.isEmpty()) Result.success(emptyList()) else attempt { searchOne(id, query) }
                send(id to result)
            }
        }
    }

    /** The chapter list of a listing, in chapter order (including parsePage pages when the plugin has them). */
    suspend fun chapters(listing: Listing): List<ChapterRef> {
        val novel = Json.parseToJsonElement(host(listing.sourceId).novel(listing.path))
        if (novel !is JsonObject) throw IOException("${listing.sourceId} returned no novel for ${listing.path}")
        val chapters = novel["chapters"] as? JsonArray ?: return emptyList()
        return chapters.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val path = o.string("path") ?: return@mapNotNull null
            ChapterRef(path, o.string("name") ?: path)
        }
    }

    /** The chapter as plain text, one paragraph per line (converted from HTML by cheerio inside QuickJS, as the spike did). */
    suspend fun chapterText(sourceId: String, path: String): String {
        val host = host(sourceId)
        val html = PluginHost.decodeString(host.chapter(path))
        val text = host.htmlToText(html)
        if (text.isBlank()) throw IOException("$sourceId returned no text for $path")
        return text
    }

    override fun close() {
        closed = true
        hosts.values.forEach { it.close() }
        hosts.clear()
    }

    private suspend fun searchOne(sourceId: String, term: String): List<Listing> {
        val found = Json.parseToJsonElement(host(sourceId).search(term, 1))
        if (found is JsonNull) return emptyList()
        if (found !is JsonArray) throw IOException("$sourceId returned an unexpected search result")
        return found.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val path = o.string("path") ?: return@mapNotNull null
            val title = o.string("name") ?: return@mapNotNull null
            Listing(sourceId, path, title, o.string("cover")?.takeIf { it.isNotBlank() })
        }
    }

    /** The plugin host for [id], loading it on first use. */
    private suspend fun host(id: String): PluginHost {
        if (closed) throw IllegalStateException("Sources is closed")
        require(id in sourceIds) { "unknown source $id" }
        hosts[id]?.let { return it }
        return loadLock.withLock {
            hosts[id] ?: PluginHost(id, readAsset, log).also { host ->
                try {
                    host.load()
                } catch (e: CancellationException) {
                    host.close()
                    throw e
                } catch (e: Exception) {
                    host.close()
                    throw if (e is IOException) e else IOException("could not load source $id: ${e.message}", e)
                }
                hosts[id] = host
            }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        val DEFAULT_SOURCES = listOf("anf.net", "readnovelfull", "novelbuddy")
        const val USER_AGENT = "Lantern/0.1 (personal project)"
    }
}

/** Reads a Java resource of this module (the JS bundle and the plugins) as text. */
internal fun readResource(name: String): String {
    val stream = Sources::class.java.classLoader?.getResourceAsStream(name)
        ?: throw IllegalStateException("resource $name is missing from the sources module")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
