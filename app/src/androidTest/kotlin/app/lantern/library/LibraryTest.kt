package app.lantern.library

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.lantern.core.ChapterRef
import app.lantern.core.Listing
import app.lantern.core.Position
import app.lantern.core.Sentence
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

    /** The stored hash is the SHA-256 of the sentence texts joined with newlines, paragraphs not included. */
    private fun hashOf(sentences: List<Sentence>): String =
        MessageDigest.getInstance("SHA-256").digest(sentences.joinToString("\n") { it.text }.toByteArray()).joinToString("") { "%02x".format(it) }

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

        assertEquals(expected.map { it.text }, library.sentences(chapter.id))
        assertEquals(expected.map { it.text }, library.sentences(chapter.id))

        assertEquals(listOf("/c/1"), source.textCalls)
        assertEquals(hashOf(expected), library.chapter(chapter.id)?.textHash)
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
        assertEquals(TextPrep.sentences(source.texts.getValue("/c/1")).map { it.text }, library.sentences(chapter.id))
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
        assertEquals(expected.map { it.text }, library.sentences(chapter.id))
        assertEquals(expected.size, library.plan(chapter.id)?.sentenceCount)
        assertTrue(source.textCalls.isEmpty())
    }

    @Test
    fun pasteKeepsTheParagraphsAndTheTextHashIgnoresThem() = runBlocking {
        val id = library.paste("It rained. Then it stopped.\nThe sun came out.")

        val chapter = chaptersOf(id).single()
        val text = library.chapterText(chapter.id)
        assertEquals(
            listOf(Sentence("It rained.", 0), Sentence("Then it stopped.", 0), Sentence("The sun came out.", 1)),
            text.sentences,
        )
        assertEquals(emptySet<Int>(), text.skipped)
        assertEquals(hashOf(text.sentences), chapter.textHash)
        assertEquals(listOf("It rained.", "Then it stopped.", "The sun came out."), library.sentences(chapter.id))
    }

    @Test
    fun aFetchedChapterStoresItsParagraphsAndIsFetchedOnce() = runBlocking {
        val chapter = chaptersOf(library.add(listing)).first()
        val expected = listOf(
            Sentence("It rained all day.", 0),
            Sentence("The street was empty.", 0),
            Sentence("She walked home.", 1),
            Sentence("Nobody noticed her.", 1),
        )

        assertEquals(ChapterText(expected, emptySet()), library.chapterText(chapter.id))
        assertEquals(ChapterText(expected, emptySet()), library.chapterText(chapter.id))

        assertEquals(listOf("/c/1"), source.textCalls)
        assertEquals(1, count("SELECT paragraph FROM sentence WHERE chapterId = ? AND idx = 2", chapter.id))
        assertEquals(hashOf(expected), library.chapter(chapter.id)?.textHash)
        assertEquals(expected.map { it.text }, library.sentences(chapter.id))
    }

    @Test
    fun chapterTextFailsLikeSentencesAndStoresNothing() = runBlocking {
        val chapter = chaptersOf(library.add(listing)).first()

        source.failText = true
        try {
            library.chapterText(chapter.id)
            fail("expected an IOException")
        } catch (_: IOException) {
        }
        assertEquals(0, count("SELECT COUNT(*) FROM sentence"))
        assertNull(library.chapter(chapter.id)?.textHash)

        try {
            library.chapterText(-1)
            fail("expected a NoSuchElementException")
        } catch (_: NoSuchElementException) {
        }

        source.failText = false
        assertEquals(4, library.chapterText(chapter.id).sentences.size)
        assertEquals(listOf("/c/1", "/c/1"), source.textCalls)
    }

    @Test
    fun aChapterStoredBeforeParagraphsReadsBackUnchanged() = runBlocking {
        val chapter = chaptersOf(library.add(listing)).first()
        // Schema 1 chapters came out of the migration with paragraph = idx and a text hash already set.
        val sql = db.openHelper.writableDatabase
        listOf("Old one.", "Old two.", "Old three.").forEachIndexed { i, text ->
            sql.execSQL("INSERT INTO sentence (chapterId, idx, text, paragraph) VALUES (?, ?, ?, ?)", arrayOf<Any>(chapter.id, i, text, i))
        }
        sql.execSQL("UPDATE chapter SET textHash = 'old-hash' WHERE id = ?", arrayOf<Any>(chapter.id))

        val expected = listOf(Sentence("Old one.", 0), Sentence("Old two.", 1), Sentence("Old three.", 2))
        assertEquals(ChapterText(expected, emptySet()), library.chapterText(chapter.id))
        assertEquals(listOf("Old one.", "Old two.", "Old three."), library.sentences(chapter.id))
        assertTrue(source.textCalls.isEmpty())
        assertEquals("old-hash", library.chapter(chapter.id)?.textHash)
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
    fun popHistoryReturnsAndRemovesTheNewestEntryOfThatBookOnly() = runBlocking {
        val a = library.add(listing)
        val b = library.add(Listing("src", "/novel/b", "Book B", null))
        for (n in 1..3) library.recordJump(Position(a, 1, n))
        library.recordJump(Position(b, 9, 9))

        assertEquals(Position(a, 1, 3), library.popHistory(a))
        assertEquals(listOf(2, 1), library.history(a).map { it.sentence })
        assertEquals(Position(a, 1, 2), library.popHistory(a))
        assertEquals(Position(a, 1, 1), library.popHistory(a))
        assertNull(library.popHistory(a))
        assertEquals(emptyList<Position>(), library.history(a))
        assertEquals(listOf(Position(b, 9, 9)), library.history(b))

        // After a pop the ranks are still 0, 1, ...: a new jump goes in front and the cap still holds.
        for (n in 1..25) library.recordJump(Position(a, 1, n))
        library.popHistory(a)
        library.recordJump(Position(a, 1, 99))
        assertEquals(99, library.history(a).first().sentence)
        assertEquals(20, library.history(a).size)
        assertEquals(20, count("SELECT COUNT(*) FROM position_history WHERE bookId = ?", a))
        assertEquals(0, count("SELECT MIN(rank) FROM position_history WHERE bookId = ?", a))
        assertEquals(19, count("SELECT MAX(rank) FROM position_history WHERE bookId = ?", a))
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

    // ---- Switching source (docs/decisions/switching-source.md) ----

    private val other = Listing("other", "/novel/b", "Book A (Other)", "http://img/b.jpg")

    /** A book on "src" with numbered chapters [titles] at /a/<n>, each with two sentences of text. */
    private suspend fun numberedBook(vararg titles: String): Long {
        source.refs = titles.mapIndexed { i, t -> ChapterRef("/a/$i", t) }
        titles.indices.forEach { source.texts["/a/$it"] = "Old text $it. Second sentence $it." }
        return library.add(listing)
    }

    /** Points the fake source at the other listing's chapters, at /b/<n>. */
    private fun otherSourceHas(vararg titles: String) {
        source.refs = titles.mapIndexed { i, t -> ChapterRef("/b/$i", t) }
    }

    @Test
    fun aSwitchKeepsThePositionAndFetchedText() = runBlocking {
        val id = numberedBook("Chapter 1", "Chapter 2", "Chapter 3")
        val two = chaptersOf(id)[1]
        val before = library.sentences(two.id)
        library.savePosition(Position(id, two.id, 1))
        otherSourceHas("Chapter 1 - Rain", "Chapter 2 - Home", "Chapter 3 - End", "Chapter 4 - More")
        val calls = source.textCalls.size

        val preview = library.previewSwitch(id, other)
        assertEquals(3, preview.matched)
        assertEquals(1, preview.added)
        assertEquals(0, preview.removed)
        assertEquals("Chapter 2", preview.positionFrom)
        assertEquals("Chapter 2 - Home", preview.positionTo)
        library.switchSource(preview)

        assertEquals(Position(id, two.id, 1), library.position(id).first())
        assertEquals(before, library.sentences(two.id))
        assertEquals(calls, source.textCalls.size)
        val after = chaptersOf(id)
        assertEquals(listOf("Chapter 1 - Rain", "Chapter 2", "Chapter 3 - End", "Chapter 4 - More"), after.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), after.map { it.number })
        assertEquals(two.id, after[1].id)
        val book = library.book(id)!!
        assertEquals("other", book.sourceId)
        assertEquals("http://img/b.jpg", book.coverUrl)
        assertEquals(listOf("/b/0", "/b/1", "/b/2", "/b/3"), (0..3).map { n ->
            db.openHelper.readableDatabase.query("SELECT path FROM chapter WHERE bookId = ? AND number = ?", arrayOf<Any>(id, n)).use { it.moveToFirst(); it.getString(0) }
        })
    }

    @Test
    fun anUnfetchedOldOnlyChapterGoesAndThePositionMovesOn() = runBlocking {
        val id = numberedBook("Chapter 1", "Interlude", "Chapter 2", "Chapter 3", "Chapter 4")
        val old = chaptersOf(id)
        library.sentences(old[1].id) // the interlude is fetched, so it stays
        library.savePosition(Position(id, old[3].id, 5)) // chapter 3, not fetched
        otherSourceHas("Chapter 1", "Chapter 2", "Chapter 4")

        val preview = library.previewSwitch(id, other)
        assertEquals(3, preview.matched)
        assertEquals(1, preview.keptOld)
        assertEquals(1, preview.removed)
        assertEquals("start of Chapter 4", preview.positionTo)
        library.switchSource(preview)

        val after = chaptersOf(id)
        assertEquals(listOf("Chapter 1", "Interlude", "Chapter 2", "Chapter 4"), after.map { it.title })
        assertEquals(listOf(old[0].id, old[1].id, old[2].id, old[4].id), after.map { it.id })
        assertEquals(Position(id, old[4].id, 0), library.position(id).first())
        assertEquals(0, count("SELECT COUNT(*) FROM chapter WHERE id = ?", old[3].id))
    }

    @Test
    fun historyOnRemovedChaptersIsDropped() = runBlocking {
        val id = numberedBook("Chapter 1", "Chapter 2", "Chapter 3")
        val old = chaptersOf(id)
        library.recordJump(Position(id, old[2].id, 0))
        library.recordJump(Position(id, old[0].id, 2))
        otherSourceHas("Chapter 1", "Chapter 2")

        library.switchSource(library.previewSwitch(id, other))

        assertEquals(listOf(Position(id, old[0].id, 2)), library.history(id))
    }

    @Test
    fun aRefreshAfterASwitchAddsOnlyNewChapters() = runBlocking {
        val id = numberedBook("Chapter 1", "Chapter 2")
        library.sentences(chaptersOf(id)[0].id)
        otherSourceHas("Chapter 1", "Chapter 2")
        library.switchSource(library.previewSwitch(id, other))

        otherSourceHas("Chapter 1", "Chapter 2", "Chapter 3")
        assertEquals(1, library.refresh(id))
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), chaptersOf(id).map { it.title })
    }

    @Test
    fun switchingToAListingAlreadyInTheLibraryIsRefused() = runBlocking {
        val id = numberedBook("Chapter 1")
        library.add(other)
        try {
            library.previewSwitch(id, other)
            fail("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("src", library.book(id)!!.sourceId)
    }

    @Test
    fun pastedTextCannotSwitch() = runBlocking {
        val id = library.paste("Just some text.")
        try {
            library.previewSwitch(id, other)
            fail("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aBookThatChangedSinceThePreviewIsNotSwitched() = runBlocking {
        val id = numberedBook("Chapter 1", "Chapter 2")
        otherSourceHas("Chapter 1", "Chapter 2")
        val preview = library.previewSwitch(id, other)
        source.refs = listOf(ChapterRef("/a/0", "Chapter 1"), ChapterRef("/a/1", "Chapter 2"), ChapterRef("/a/2", "Chapter 3"))
        assertEquals(1, library.refresh(id))

        try {
            library.switchSource(preview)
            fail("expected an IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertEquals("src", library.book(id)!!.sourceId)
        assertEquals(3, chaptersOf(id).size)
    }
}
