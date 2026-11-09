package app.lantern.sources

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A throwaway site on 127.0.0.1 for the network-rule tests. It serves requests concurrently, so a test can
 * tell whether [Http] really holds requests back, and it records what it was asked.
 */
internal class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    private val hitCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val inFlight = AtomicInteger()

    val port: Int get() = server.address.port

    /** The site's own address, as a plugin's `site` would say it. */
    val site: String get() = "http://127.0.0.1:$port/"

    /** The same server under another host name, which [Http] must treat as another domain. */
    val otherDomain: String get() = "http://localhost:$port"

    /** System.nanoTime() of each request's arrival, in order. */
    val arrivals = CopyOnWriteArrayList<Long>()

    /** The most requests the server ever had open at the same time. */
    @Volatile
    var maxInFlight = 0
        private set

    /** Last User-Agent header the server received. */
    @Volatile
    var lastUserAgent: String? = null
        private set

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    }

    fun hits(path: String): Int = hitCounts[path]?.get() ?: 0

    fun route(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { ex ->
            arrivals.add(System.nanoTime())
            hitCounts.getOrPut(ex.requestURI.path) { AtomicInteger() }.incrementAndGet()
            lastUserAgent = ex.requestHeaders.getFirst("User-Agent")
            val now = inFlight.incrementAndGet()
            if (now > maxInFlight) maxInFlight = now
            try {
                handler(ex)
            } catch (e: IOException) {
                // The client hung up (it refuses an oversized body), which is not a server failure.
            } finally {
                inFlight.decrementAndGet()
                ex.close()
            }
        }
    }

    fun start(): TestServer {
        server.start()
        return this
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        fun reply(ex: HttpExchange, status: Int, body: String, vararg headers: Pair<String, String>) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }
}
