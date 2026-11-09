package app.lantern.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The network rules, tested on [Http] directly against a site on 127.0.0.1. */
class HttpRulesTest {
    // The rules as decided, written out here so that changing the production constants fails these tests.
    private companion object {
        const val MIN_GAP_MS = 1000L
        const val SIZE_CAP = 20 * 1024 * 1024
    }

    private lateinit var server: TestServer
    private lateinit var http: Http

    @Before
    fun setUp() {
        server = TestServer()
        http = Http("test") {}
        http.site = server.site
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun request(url: String): String = buildJsonObject {
        put("url", url)
        put("method", "GET")
        put("headers", buildJsonObject { put("User-Agent", "SomethingElse/9.9") })
    }.toString()

    private fun fetch(url: String, using: Http = http): JsonObject =
        runBlocking { Json.parseToJsonElement(using.handle(request(url))).jsonObject }

    private fun error(reply: JsonObject): String? = reply["error"]?.jsonPrimitive?.content

    @Test
    fun ownSiteIsFetchedWithOurUserAgent() {
        server.route("/ok") { TestServer.reply(it, 200, "hello") }
        server.start()

        val reply = fetch("${server.site}ok")

        assertNull(error(reply))
        assertEquals(200, reply["status"]!!.jsonPrimitive.int)
        assertEquals("hello", reply["body"]!!.jsonPrimitive.content)
        // The request asked for another User-Agent, and Http replaced it.
        assertEquals(Sources.USER_AGENT, server.lastUserAgent)
    }

    @Test
    fun everythingIsBlockedUntilThePluginHasSaidWhereItLives() {
        server.route("/ok") { TestServer.reply(it, 200, "hello") }
        server.start()
        val unloaded = Http("test") {}

        val reply = fetch("${server.site}ok", unloaded)

        assertTrue(error(reply)!!.contains("blocked host 127.0.0.1"))
        assertEquals(0, server.hits("/ok"))
    }

    @Test
    fun aRequestToAnotherDomainIsRefusedWithoutBeingSent() {
        server.route("/hit") { TestServer.reply(it, 200, "should never be served") }
        server.start()

        val reply = fetch("${server.otherDomain}/hit")

        assertTrue(error(reply)!!.contains("blocked host localhost"), error(reply))
        assertEquals(0, server.hits("/hit"))
        assertEquals(1, http.blocked.get())
        assertEquals(0, http.requests.get())
    }

    @Test
    fun onlyHttpAndHttpsAreAllowed() {
        val reply = fetch("file:///etc/passwd")

        assertTrue(error(reply)!!.contains("bad url"), error(reply))
        assertEquals(0, http.requests.get())
    }

    @Test
    fun aRedirectToAnotherDomainIsRefused() {
        server.route("/redir") { ex ->
            TestServer.reply(ex, 302, "", "Location" to "${server.otherDomain}/secret")
        }
        server.route("/secret") { TestServer.reply(it, 200, "should never be served") }
        server.start()

        val reply = fetch("${server.site}redir")

        assertTrue(error(reply)!!.contains("blocked host localhost"), error(reply))
        assertEquals(1, server.hits("/redir"))
        assertEquals(0, server.hits("/secret"))
        assertEquals(1, http.blocked.get())
    }

    @Test
    fun aRedirectInsideTheSiteIsFollowed() {
        server.route("/a") { ex -> TestServer.reply(ex, 302, "", "Location" to "/b") }
        server.route("/b") { TestServer.reply(it, 200, "arrived") }
        server.start()

        val reply = fetch("${server.site}a")

        assertNull(error(reply))
        assertEquals("arrived", reply["body"]!!.jsonPrimitive.content)
        assertEquals(2, http.requests.get())
    }

    @Test
    fun domainRuleKeepsSubdomainsAndRejectsLookalikes() {
        val site = "https://novgo.net/"
        assertTrue(Http.hostAllowed("novgo.net", site))
        assertTrue(Http.hostAllowed("www.novgo.net", site))
        assertTrue(Http.hostAllowed("WWW.NOVGO.NET", site))
        assertFalse(Http.hostAllowed("evilnovgo.net", site))
        assertFalse(Http.hostAllowed("novgo.net.evil.com", site))
        assertFalse(Http.hostAllowed("novgo.com", site))
        assertFalse(Http.hostAllowed("novgo.net", null))
    }

    @Test
    fun requestsToOneSiteAreOneAtATimeAndAtLeastASecondApart() {
        server.route("/slow") { ex ->
            Thread.sleep(150) // long enough that unqueued requests would overlap
            TestServer.reply(ex, 200, "ok")
        }
        server.start()

        // Three plugins' worth of requests arrive at once, from separate Http instances as well as one.
        val other = Http("other") {}.also { it.site = server.site }
        runBlocking {
            listOf(http, http, other).map { h ->
                async(Dispatchers.Default) { h.handle(request("${server.site}slow")) }
            }.awaitAll()
        }

        assertEquals(3, server.arrivals.size)
        assertEquals(1, server.maxInFlight)
        server.arrivals.sorted().zipWithNext().forEach { (first, second) ->
            val gapMs = (second - first) / 1_000_000
            assertTrue(gapMs >= MIN_GAP_MS, "requests only $gapMs ms apart")
        }
    }

    @Test
    fun aSecurityCheckPageStopsTheRequestAndIsNotRetried() {
        // Status, extra header and body: the markers the Http class looks for.
        val cases = listOf(
            Triple(403, null, "<html><head><title>Just a moment...</title></head></html>"),
            Triple(503, "cf-mitigated" to "challenge", "nothing in the body"),
            Triple(403, null, "<html>DDoS-Guard is checking your browser</html>"),
        )
        cases.forEachIndexed { i, (status, header, body) ->
            server.route("/check$i") { ex ->
                TestServer.reply(ex, status, body, *listOfNotNull(header).toTypedArray())
            }
        }
        server.start()

        cases.forEachIndexed { i, (status, _, _) ->
            val reply = fetch("${server.site}check$i")

            assertTrue(error(reply)!!.contains("security check page"), "case $i: ${error(reply)}")
            assertTrue(error(reply)!!.contains("status $status"), "case $i: ${error(reply)}")
            assertEquals(1, server.hits("/check$i"), "case $i was retried")
            assertNotNull(http.lastChallenge)
        }
        assertEquals(cases.size, http.challenges.get())
    }

    @Test
    fun anOrdinaryForbiddenReplyIsPassedOnNotTreatedAsASecurityCheck() {
        server.route("/forbidden") { TestServer.reply(it, 403, "no entry") }
        server.start()

        val reply = fetch("${server.site}forbidden")

        assertNull(error(reply))
        assertEquals(403, reply["status"]!!.jsonPrimitive.int)
        assertEquals(0, http.challenges.get())
        assertNull(http.lastChallenge)
        assertTrue(http.lastProblem!!.startsWith("HTTP 403"), http.lastProblem)
    }

    @Test
    fun aResponseOverTheSizeCapIsRefused() {
        server.route("/big") { ex ->
            val size = SIZE_CAP + 1L
            ex.sendResponseHeaders(200, size)
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            ex.responseBody.use { out ->
                var sent = 0L
                while (sent < size) {
                    val n = minOf(chunk.size.toLong(), size - sent).toInt()
                    out.write(chunk, 0, n)
                    sent += n
                }
            }
        }
        server.start()

        val reply = fetch("${server.site}big")

        assertTrue(error(reply)!!.contains("larger than ${SIZE_CAP} bytes"), error(reply))
    }

    @Test
    fun aResponseExactlyAtTheSizeCapIsAccepted() {
        server.route("/limit") { ex ->
            val bytes = ByteArray(SIZE_CAP) { 'b'.code.toByte() }
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        val reply = fetch("${server.site}limit")

        assertNull(error(reply))
        assertEquals(SIZE_CAP, reply["body"]!!.jsonPrimitive.content.length)
    }
}
