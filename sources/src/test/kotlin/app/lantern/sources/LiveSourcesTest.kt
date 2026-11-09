package app.lantern.sources

import app.lantern.core.Listing
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Checks against the real sites. They run only with `./gradlew :sources:test -Plive`, and the requests are few
 * and spaced by [Http]. Everything below shares one [Sources] and one search, so a run costs about ten requests.
 */
class LiveSourcesTest {
    @Before
    fun onlyWhenAsked() {
        assumeTrue("live tests run only with -Plive", System.getProperty("lantern.live") != null)
    }

    @Test
    fun searchFindsOdeOfTheBraveOnAnfAndNovelBuddy() {
        for (id in listOf("anf.net", "novelbuddy")) {
            val listings = search.getValue(id).getOrThrow()
            assertTrue(listings.any { it.title.contains(TITLE, ignoreCase = true) }, "$id listed ${listings.map { it.title }}")
        }
    }

    @Test
    fun anfListsAtLeast104ChaptersOfOdeOfTheBrave() {
        assertTrue(anfChapters.size >= 104, "only ${anfChapters.size} chapters")
    }

    @Test
    fun chapterOneIsLongAndKeepsItsShortDialogueLines() {
        val text = runBlocking { sources.chapterText("anf.net", anfChapters.first().path) }
        val shortLines = text.lines().count { it.length < SHORT_LINE_CHARS }

        assertTrue(text.length > 10_000, "chapter 1 has only ${text.length} characters")
        assertTrue(shortLines >= 5, "only $shortLines short lines")
    }

    @Test
    fun readNovelFullFindsShadowSlave() {
        val listings = runBlocking {
            Sources(listOf("readnovelfull")).use { it.search("Shadow Slave").toList().single().second.getOrThrow() }
        }

        assertTrue(listings.any { it.title.contains("Shadow Slave", ignoreCase = true) }, "listed ${listings.map { it.title }}")
    }

    private companion object {
        const val TITLE = "Ode of the Brave"
        const val SHORT_LINE_CHARS = 40

        val sources by lazy { Sources(listOf("anf.net", "novelbuddy")) }

        val search: Map<String, Result<List<Listing>>> by lazy {
            runBlocking { sources.search(TITLE).toList().toMap() }
        }

        val anfChapters by lazy {
            val listing = search.getValue("anf.net").getOrThrow().first { it.title.contains(TITLE, ignoreCase = true) }
            runBlocking { sources.chapters(listing) }
        }

        @JvmStatic
        @AfterClass
        fun closeSources() {
            sources.close()
        }
    }
}
