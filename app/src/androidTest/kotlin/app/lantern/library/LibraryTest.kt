package app.lantern.library

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.lantern.core.ChapterRef
import app.lantern.core.Listing
import app.lantern.core.Position
import app.lantern.core.TextPrep
import app.lantern.data.LanternDatabase
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The step-5 tests: an in-memory Room database on the phone and a fake [ChapterSource] that counts calls. */
class LibraryTest {
    private class FakeSource : ChapterSource {
        var refs = listOf(ChapterRef("/c/1", "One"), ChapterRef("/c/2", "Two"), ChapterRef("/c/3", "Three"))
        val texts = mutableMapOf(
            "/c/1" to "It rained all day. The street was empty.\nShe walked home. Nobody noticed her.",
            "/c/2" to "Morning came. The rain had stopped.",
            "/c/3" to "The end. Or so it seemed.",
        )
        var failText = false
        var chapterCalls = 0
        val textCalls = mutableListOf<String>()

        override suspend fun chapters(listing: Listing): List<ChapterRef> {
            chapterCalls++
            return refs
        }

        var textDelayMs = 0L

        override suspend fun chapterText(sourceId: String, path: String): String {
            synchronized(textCalls) { textCalls += path }
            delay(textDelayMs)
            if (failText) throw IOException("offline")
            return texts.getValue(path)
        }
    }

    private val listing = Listing("src", "/novel/a", "Book A", "http://img/a.jpg")
    private lateinit var db: LanternDatabase
    private lateinit var source: FakeSource
    private lateinit var library: Library
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, LanternDatabase::class.java).build()
        source = FakeSource()
        library = Library(db, source) { ++now }
    }

    @After
    fun tearDown() = db.close()

    private fun count(sql: String, vararg args: Any): Int =
        db.openHelper.readableDatabase.query(sql, args).use { it.moveToFirst(); it.getInt(0) }

    private suspend fun chaptersOf(bookId: Long) = library.chapters(bookId).first()

    @Test
    fun addStoresTheBookAndAllChaptersInOrder() = runBlocking {
        val id = library.add(listing)

        val book = library.books().first().single()
        assertEquals(Book(id, "Book A", "src", "http://img/a.jpg", 3, null), book)
        val chapters = chaptersOf(id)
        assertEquals(listOf("One", "Two", "Three"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.number })
        assertTrue(chapters.all { it.bookId == id && it.textHash == null })
    }

    @Test
    fun twoFirstCallsAtOnceFetchOnce() = runBlocking {
        val chapter = chaptersOf(library.add(listing)).first()
        source.textDelayMs = 300

        val a = async(Dispatchers.Default) { library.sentences(chapter.id) }
        val b = async(Dispatchers.Default) { library.sentences(chapter.id) }

        assertEquals(a.await(), b.await())
        assertEquals(listOf("/c/1"), source.textCalls)
    }

    @Test
    fun addingTheSameListingAgainChangesNothing() = runBlocking {
        val id = library.add(listing)
        val again = library.add(listing)

        assertEquals(id, again)
        assertEquals(1, count("SELECT COUNT(*) FROM book"))
        assertEquals(3, count("SELECT COUNT(*) FROM chapter"))
        assertEquals(1, source.chapterCalls)
    }

    @Test
    fun sentencesFetchOnceAndStoreTheHash() = runBlocking {
        val chapter = chaptersOf(library.add(listing)).first()
        val expected = TextPrep.sentences(source.texts.getValue("/c/1"))
        assertTrue(expected.size > 1)

        assertEquals(expected, library.sentences(chapter.id))
        assertEquals(expected, library.sentences(chapter.id))

        assertEquals(listOf("/c/1"), source.textCalls)
        val sha = MessageDigest.getInstance("SHA-256").digest(expected.joinToString("\n").toByteArray())
        val hex = sha.joinToString("") { "%02x".format(it) }
        assertEquals(hex, library.chapter(chapter.id)?.textHash)
        assertEquals(expected.size, count("SELECT COUNT(*) FROM sentence WHERE chapterId = ?", chapter.id))
    }

    @Test
    fun aFetchFailureStoresNothingAndALaterCallRetries() = runBlocking {
        val chapter = chaptersOf(library.add(listing)).first()

        source.failText = true
        try {
            library.sentences(chapter.id)
            fail("expected an IOException")
        } catch (_: IOException) {
        }
        assertEquals(0, count("SELECT COUNT(*) FROM sentence"))
        assertNull(library.chapter(chapter.id)?.textHash)
        assertNull(library.plan(chapter.id))

        source.failText = false
        assertEquals(TextPrep.sentences(source.texts.getValue("/c/1")), library.sentences(chapter.id))
        assertEquals(listOf("/c/1", "/c/1"), source.textCalls)
        assertNotNull(library.chapter(chapter.id)?.textHash)
    }

    @Test
    fun planIsNullBeforeFetchingAndCountedAfter() = runBlocking {
        val chapter = chaptersOf(library.add(listing)).first()
        assertNull(library.plan(chapter.id))
        assertTrue(source.textCalls.isEmpty())

        val sentences = library.sentences(chapter.id)
        val plan = library.plan(chapter.id)

        assertEquals(chapter.id, plan?.chapterId)
        assertEquals(sentences.size, plan?.sentenceCount)
    }

    @Test
    fun refreshAppendsOnlyNewChaptersAndKeepsTheOldOnes() = runBlocking {
        val id = library.add(listing)
        val before = chaptersOf(id)
        val sentences = library.sentences(before[0].id)
        val hash = library.chapter(before[0].id)?.textHash
        assertEquals(0, library.refresh(id))

        source.refs = source.refs + listOf(ChapterRef("/c/4", "Four"), ChapterRef("/c/5", "Five"))
        assertEquals(2, library.refresh(id))
        assertEquals(0, library.refresh(id))

        val after = chaptersOf(id)
        assertEquals(listOf("One", "Two", "Three", "Four", "Five"), after.map { it.title })
        assertEquals(listOf(0, 1, 2, 3, 4), after.map { it.number })
        assertEquals(before.map { it.id }, after.take(3).map { it.id })
        assertEquals(hash, after[0].textHash)
        assertEquals(sentences, library.sentences(before[0].id))
        assertEquals(listOf("/c/1"), source.textCalls)
        assertEquals(5, library.books().first().single().chapterCount)
    }

    @Test
    fun refreshReturnsZeroForAPastedBook() = runBlocking {
        val id = library.paste("Some pasted story. It has two sentences.")

        assertEquals(0, library.refresh(id))
        assertEquals(0, source.chapterCalls)
    }

    @Test
    fun pasteMakesOneBookWithOneChapterAndItsSentences() = runBlocking {
        val text = "\n  \n  The First Line  \nIt rained. Then it stopped.\nAnd the sun came out."
        val id = library.paste(text)

        val book = library.books().first().single()
        assertEquals(id, book.id)
        assertEquals("The First Line", book.title)
        assertNull(book.sourceId)
        assertEquals(1, book.chapterCount)
        val chapter = chaptersOf(id).single()
        assertNotNull(chapter.textHash)
        val expected = TextPrep.sentences(text)
        assertEquals(expected, library.sentences(chapter.id))
        assertEquals(expected.size, library.plan(chapter.id)?.sentenceCount)
        assertTrue(source.textCalls.isEmpty())
    }

    @Test
    fun pasteCutsALongTitleAndAllowsTheSameTextTwice() = runBlocking {
        val line = "word ".repeat(40).trim() + "."
        val first = library.paste(line)
        val second = library.paste(line)

        assertNotEquals(first, second)
        assertEquals(80, library.books().first().first().title.length)
        assertEquals(2, count("SELECT COUNT(*) FROM book"))
    }

    @Test
    fun pasteOfNothingReadableIsRejected() = runBlocking {
        try {
            library.paste("  \n \n")
            fail("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, count("SELECT COUNT(*) FROM book"))
    }

    @Test
    fun positionFlowAndLastPlayedFollowTheLatestSave() = runBlocking {
        val a = library.add(listing)
        val b = library.add(Listing("src", "/novel/b", "Book B", null))
        assertNull(library.position(a).first())
        assertNull(library.lastPlayed())

        library.savePosition(Position(a, 10, 3))
        library.savePosition(Position(b, 20, 7))
        assertEquals(Position(b, 20, 7), library.lastPlayed())
        assertEquals(Position(a, 10, 3), library.position(a).first())

        library.savePosition(Position(a, 10, 4))
        assertEquals(Position(a, 10, 4), library.lastPlayed())
        assertEquals(Position(a, 10, 4), library.position(a).first())
        assertEquals(Position(b, 20, 7), library.position(b).first())
        assertEquals(2, count("SELECT COUNT(*) FROM position"))
    }

    @Test
    fun booksListLastPlayedFirstThenNewestAdded() = runBlocking {
        val a = library.add(listing)
        val b = library.add(Listing("src", "/novel/b", "Book B", null))
        val c = library.add(Listing("src", "/novel/c", "Book C", null))
        assertEquals(listOf(c, b, a), library.books().first().map { it.id })

        library.savePosition(Position(a, 1, 0))
        assertEquals(listOf(a, c, b), library.books().first().map { it.id })
        assertNotNull(library.books().first().first().lastPlayedAt)
    }

    @Test
    fun savePositionNowRoundTrips() {
        val id = runBlocking { library.add(listing) }

        library.savePositionNow(Position(id, 5, 9))

        assertEquals(Position(id, 5, 9), runBlocking { library.position(id).first() })
        assertEquals(Position(id, 5, 9), runBlocking { library.lastPlayed() })
    }

    @Test
    fun savingForADeletedBookIsIgnored() = runBlocking {
        val id = library.add(listing)
        library.delete(id)

        library.savePosition(Position(id, 1, 1))
        library.recordJump(Position(id, 1, 1))

        assertEquals(0, count("SELECT COUNT(*) FROM position"))
        assertEquals(0, count("SELECT COUNT(*) FROM position_history"))
    }

    @Test
    fun historyIsNewestFirstCappedAtTwentyAndSkipsARepeatOfTheNewest() = runBlocking {
        val id = library.add(listing)
        for (n in 1..25) library.recordJump(Position(id, 1, n))
        assertEquals((25 downTo 6).map { Position(id, 1, it) }, library.history(id))

        library.recordJump(Position(id, 1, 25))
        assertEquals((25 downTo 6).map { Position(id, 1, it) }, library.history(id))

        library.recordJump(Position(id, 1, 7))
        assertEquals(listOf(7, 25, 24), library.history(id).take(3).map { it.sentence })
        assertEquals(20, library.history(id).size)
        assertEquals(20, count("SELECT COUNT(*) FROM position_history WHERE bookId = ?", id))
    }

    @Test
    fun deleteRemovesEverythingOfThatBookAndNothingElse() = runBlocking {
        val a = library.add(listing)
        val b = library.add(Listing("src", "/novel/b", "Book B", null))
        for (id in listOf(a, b)) {
            val chapter = chaptersOf(id).first()
            library.sentences(chapter.id)
            library.savePosition(Position(id, chapter.id, 1))
            library.recordJump(Position(id, chapter.id, 0))
        }
        val keptSentences = count("SELECT COUNT(*) FROM sentence WHERE chapterId IN (SELECT id FROM chapter WHERE bookId = ?)", b)
        assertTrue(keptSentences > 0)

        library.delete(a)

        assertEquals(listOf(b), library.books().first().map { it.id })
        assertEquals(0, count("SELECT COUNT(*) FROM chapter WHERE bookId = ?", a))
        assertEquals(0, count("SELECT COUNT(*) FROM position WHERE bookId = ?", a))
        assertEquals(0, count("SELECT COUNT(*) FROM position_history WHERE bookId = ?", a))
        assertEquals(keptSentences, count("SELECT COUNT(*) FROM sentence"))
        assertEquals(3, count("SELECT COUNT(*) FROM chapter"))
        assertEquals(1, count("SELECT COUNT(*) FROM position"))
        assertEquals(1, count("SELECT COUNT(*) FROM position_history"))
        assertNull(library.position(a).first())
    }

    @Test
    fun nextChapterInTheMiddleAndAtTheEnd() = runBlocking {
        val chapters = chaptersOf(library.add(listing))

        assertEquals(chapters[1], library.nextChapter(chapters[0].id))
        assertEquals(chapters[2], library.nextChapter(chapters[1].id))
        assertNull(library.nextChapter(chapters[2].id))
        assertNull(library.nextChapter(-1))
    }
}
