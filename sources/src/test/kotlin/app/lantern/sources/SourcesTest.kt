package app.lantern.sources

import app.lantern.core.ChapterRef
import app.lantern.core.Listing
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [Sources] end to end with inline plugins that never touch the network. */
class SourcesTest {
    private val loadedAssets = mutableListOf<String>()
    private val opened = mutableListOf<Sources>()

    private val plugins = mapOf(
        "fake" to """
            exports.default = {
              id: 'fake', name: 'Fake', site: 'http://127.0.0.1:1/', version: '1',
              searchNovels: async function (term, page) {
                return [
                  { name: 'Ode ' + term, path: 'ode.html', cover: 'http://127.0.0.1:1/c.jpg' },
                  { name: 'No cover', path: 'nc.html', cover: '' },
                  { name: 'Missing path' },
                ];
              },
              parseNovel: async function (path) {
                return { path: path, name: 'Ode', totalPages: 3,
                         chapters: [{ name: 'Chapter 1', path: 'c/1' }, { name: 'Chapter 2', path: 'c/2' }] };
              },
              parsePage: async function (path, page) {
                return { chapters: [{ name: 'Chapter p' + page, path: 'c/p' + page }] };
              },
              parseChapter: async function (path) {
                return '<div><p>He came.</p><p>"Run!"</p><p>Why?</p>Loose text<br>after the break</div>';
              },
            };
        """.trimIndent(),
        "boom" to """
            exports.default = {
              id: 'boom', name: 'Boom', site: 'http://127.0.0.1:1/', version: '1',
              searchNovels: async function () { throw new Error('boom: the site is down'); },
              parseNovel: async function () { return null; },
              parseChapter: async function () { return ''; },
            };
        """.trimIndent(),
    )

    private fun sources(vararg ids: String) = Sources(
        ids.toList(),
        {},
        { name ->
            loadedAssets += name
            val id = name.removePrefix("plugins/").removeSuffix(".js")
            if (name.startsWith("plugins/") && id in plugins) plugins.getValue(id) else readResource(name)
        },
    ).also { opened += it }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
    }

    @Test
    fun searchEmitsOncePerSourceWithListingsOrTheFailure() = runBlocking<Unit> {
        val results = sources("fake", "boom").search("Brave").toList().toMap()

        assertEquals(setOf("fake", "boom"), results.keys)
        // The entry without a path is dropped, and a blank cover becomes null.
        assertEquals(
            listOf(
                Listing("fake", "ode.html", "Ode Brave", "http://127.0.0.1:1/c.jpg"),
                Listing("fake", "nc.html", "No cover", null),
            ),
            results.getValue("fake").getOrThrow(),
        )
        val failure = results.getValue("boom").exceptionOrNull()
        assertTrue(failure!!.message!!.contains("the site is down"), failure.message)
    }

    @Test
    fun aBlankSearchAsksNoSource() = runBlocking<Unit> {
        val results = sources("fake").search("   ").toList()

        assertEquals(listOf("fake" to emptyList<Listing>()), results.map { it.first to it.second.getOrThrow() })
        assertTrue(loadedAssets.isEmpty(), "loaded $loadedAssets")
    }

    @Test
    fun anUnknownSourceIsAFailureNotACrash() = runBlocking<Unit> {
        val results = sources("fake", "nope").search("x").toList().toMap()

        assertTrue(results.getValue("fake").isSuccess)
        assertTrue(results.getValue("nope").isFailure)
        assertTrue(results.getValue("nope").exceptionOrNull()!!.message!!.contains("nope"))
    }

    @Test
    fun aSourceNotInTheListIsRefused() = runBlocking<Unit> {
        val sources = sources("fake")

        assertFailsWith<IllegalArgumentException> { sources.chapterText("boom", "x") }
    }

    @Test
    fun chaptersComeInOrderIncludingParsePagePages() = runBlocking<Unit> {
        val chapters = sources("fake").chapters(Listing("fake", "ode.html", "Ode", null))

        assertEquals(
            listOf(
                ChapterRef("c/1", "Chapter 1"),
                ChapterRef("c/2", "Chapter 2"),
                ChapterRef("c/p2", "Chapter p2"),
                ChapterRef("c/p3", "Chapter p3"),
            ),
            chapters,
        )
    }

    @Test
    fun chapterTextIsOneParagraphPerLineAndKeepsShortLines() = runBlocking<Unit> {
        val text = sources("fake").chapterText("fake", "c/1")

        assertEquals("He came.\n\"Run!\"\nWhy?\nLoose text\nafter the break", text)
    }

    @Test
    fun anEmptyChapterIsAFailure() = runBlocking<Unit> {
        val e = assertFailsWith<IOException> { sources("boom").chapterText("boom", "c/1") }

        assertTrue(e.message!!.contains("no text"), e.message)
    }

    @Test
    fun aListingTheSourceCannotShowIsAFailure() = runBlocking<Unit> {
        val e = assertFailsWith<IOException> { sources("boom").chapters(Listing("boom", "x", "X", null)) }

        assertTrue(e.message!!.contains("boom"), e.message)
    }

    @Test
    fun eachSourceLoadsOnceAndOnlyWhenUsed() = runBlocking<Unit> {
        val sources = sources("fake", "boom")

        sources.chapterText("fake", "c/1")
        sources.chapterText("fake", "c/2")

        assertEquals(1, loadedAssets.count { it == "plugins/fake.js" })
        assertEquals(0, loadedAssets.count { it == "plugins/boom.js" })
    }

    @Test
    fun aClosedSourcesRefusesFurtherWork() = runBlocking<Unit> {
        val sources = sources("fake")
        sources.chapterText("fake", "c/1")

        sources.close()

        assertFailsWith<IllegalStateException> { sources.chapterText("fake", "c/1") }
    }
}
