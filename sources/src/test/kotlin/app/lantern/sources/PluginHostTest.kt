package app.lantern.sources

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [PluginHost] with tiny inline plugins, and with the three bundled ones (loading only, so no network). */
class PluginHostTest {
    private val hosts = mutableListOf<PluginHost>()
    private var server: TestServer? = null

    @After
    fun tearDown() {
        hosts.forEach { it.close() }
        server?.close()
    }

    /** The real JS bundle and an inline plugin called `test`. */
    private fun host(plugin: String, callTimeoutMs: Long = PluginHost.CALL_TIMEOUT_MS, loadTimeoutMs: Long = PluginHost.LOAD_TIMEOUT_MS) =
        PluginHost(
            "test",
            { name -> if (name == "plugins/test.js") plugin else readResource(name) },
            {},
            callTimeoutMs,
            loadTimeoutMs,
        ).also { hosts += it }

    private fun site(configure: (TestServer) -> Unit = {}): TestServer =
        TestServer().also { configure(it); it.start(); server = it }

    /** A plugin whose site is [site] and whose search is [body]. */
    private fun searchPlugin(site: String, body: String) = """
        const { fetchApi, fetchText } = require('@libs/fetch');
        exports.default = {
          id: 'test', name: 'Test', site: '$site', version: '1',
          searchNovels: async function (term, page) { $body },
          parseNovel: async function (path) { return { chapters: [] }; },
        };
    """.trimIndent()

    @Test
    fun theBundledPluginsLoadAndSayWhereTheyLive() = runBlocking<Unit> {
        for (id in Sources.DEFAULT_SOURCES) {
            val host = PluginHost(id, ::readResource, {}).also { hosts += it }
            val info = host.load()
            assertEquals(id, info.id)
            assertNotNull(info.site, "$id has no site")
        }
    }

    @Test(timeout = 60_000)
    fun aPluginStuckInALoopIsCutOffByTheCallTimeout() = runBlocking<Unit> {
        val plugin = """
            exports.default = {
              id: 'test', name: 'Test', site: 'http://127.0.0.1:1/', version: '1',
              searchNovels: async function () { while (true) {} },
              parseNovel: async function () { return { chapters: [{ name: 'after', path: 'a' }] }; },
            };
        """.trimIndent()
        val host = host(plugin, callTimeoutMs = 500)
        host.load()

        val started = System.nanoTime()
        val e = assertFailsWith<IOException> { host.search("anything", 1) }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(e.message!!.contains("did not finish within 500 ms"), e.message)
        assertTrue(tookMs < 10_000, "took $tookMs ms")
        // The instance is still usable afterwards.
        assertTrue(host.novel("x").contains("after"))
    }

    @Test(timeout = 60_000)
    fun aPluginStuckWhileLoadingIsCutOffByTheLoadTimeout() = runBlocking<Unit> {
        val host = host("while (true) {}", loadTimeoutMs = 500)

        val e = assertFailsWith<IOException> { host.load() }

        assertTrue(e.message!!.contains("did not finish within 500 ms"), e.message)
    }

    @Test
    fun aPluginErrorComesOutAsAnIOExceptionWithItsMessage() = runBlocking<Unit> {
        val host = host(searchPlugin("http://127.0.0.1:1/", "throw new Error('the plugin broke');"))
        host.load()

        val e = assertFailsWith<IOException> { host.search("x", 1) }

        assertTrue(e.message!!.contains("the plugin broke"), e.message)
    }

    @Test
    fun fetchProtoIsRefused() = runBlocking<Unit> {
        val host = host(searchPlugin("http://127.0.0.1:1/", "require('@libs/fetch').fetchProto({}); return [];"))
        host.load()

        val e = assertFailsWith<IOException> { host.search("x", 1) }

        assertTrue(e.message!!.contains("fetchProto is not supported"), e.message)
    }

    @Test
    fun aBlockedDomainIsNamedInTheError() = runBlocking<Unit> {
        val s = site { it.route("/x") { ex -> TestServer.reply(ex, 200, "never served") } }
        val host = host(searchPlugin(s.site, "await fetchApi('${s.otherDomain}/x'); return [];"))
        host.load()

        val e = assertFailsWith<IOException> { host.search("x", 1) }

        assertTrue(e.message!!.contains("blocked host localhost"), e.message)
        assertEquals(0, s.hits("/x"))
    }

    @Test
    fun aSecurityCheckPageFailsTheCallEvenWhenThePluginHidesTheError() = runBlocking<Unit> {
        val s = site { it.route("/cf") { ex -> TestServer.reply(ex, 403, "<title>Just a moment...</title>") } }
        // fetchText swallows every error and returns '', then this plugin happily returns an empty list.
        val host = host(searchPlugin(s.site, "await fetchText('${s.site}cf'); return [];"))
        host.load()

        val e = assertFailsWith<IOException> { host.search("x", 1) }

        assertTrue(e.message!!.contains("security check page"), e.message)
        assertEquals(1, s.hits("/cf"))
    }

    @Test
    fun anHttpStatusIsNamedWhenThePluginFailsBecauseOfIt() = runBlocking<Unit> {
        val s = site { it.route("/missing") { ex -> TestServer.reply(ex, 404, "gone") } }
        val host = host(searchPlugin(s.site, "const r = await fetchApi('${s.site}missing'); if (!r.ok) throw new Error('no list'); return [];"))
        host.load()

        val e = assertFailsWith<IOException> { host.search("x", 1) }

        assertTrue(e.message!!.startsWith("HTTP 404"), e.message)
        assertTrue(e.message!!.contains("127.0.0.1"), e.message)
    }

    @Test
    fun anHttpErrorThePluginHandlesItselfIsNotAFailure() = runBlocking<Unit> {
        val s = site { it.route("/missing") { ex -> TestServer.reply(ex, 404, "gone") } }
        val host = host(searchPlugin(s.site, "const r = await fetchApi('${s.site}missing'); return r.ok ? [{}] : [];"))
        host.load()

        assertEquals("[]", host.search("x", 1))
    }
}
