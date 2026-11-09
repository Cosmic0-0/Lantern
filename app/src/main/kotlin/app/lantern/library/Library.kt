package app.lantern.library

import androidx.room.withTransaction
import app.lantern.core.ChapterPlan
import app.lantern.core.History
import app.lantern.core.Listing
import app.lantern.core.Position
import app.lantern.core.TextPrep
import app.lantern.data.BookEntity
import app.lantern.data.ChapterEntity
import app.lantern.data.HistoryEntity
import app.lantern.data.LanternDatabase
import app.lantern.data.PositionEntity
import app.lantern.data.SentenceEntity
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [chapterCount] and the other fields are exactly the columns `LibraryDao.books` selects. */
data class Book(
    val id: Long,
    val title: String,
    val sourceId: String?,
    val coverUrl: String?,
    val chapterCount: Int,
    val lastPlayedAt: Long?,
)

/** [textHash] is null until the chapter's text has been fetched. */
data class Chapter(val id: Long, val bookId: Long, val number: Int, val title: String, val textHash: String?)

/**
 * The books you added: their chapters, sentences, positions and history. Hides Room, the sources module and
 * [TextPrep]. A chapter's text is fetched once, split once and stored as sentences, so sentence numbers never
 * shift afterwards, even if the splitter changes in a later version.
 */
class Library(
    private val db: LanternDatabase,
    private val source: ChapterSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.dao()
    private val fetchLock = Mutex()

    /** Adds the listing with its chapter list and returns the book id. A listing already in the library returns its id and changes nothing. */
    suspend fun add(listing: Listing): Long {
        dao.bookId(listing.sourceId, listing.path)?.let { return it }
        val refs = source.chapters(listing)
        return db.withTransaction {
            // Added by someone else while the chapter list was coming in: keep that one.
            dao.bookId(listing.sourceId, listing.path) ?: run {
                val bookId = dao.insertBook(
                    BookEntity(sourceId = listing.sourceId, path = listing.path, title = listing.title, coverUrl = listing.coverUrl, addedAt = clock()),
                )
                dao.insertChapters(refs.mapIndexed { i, ref -> ChapterEntity(bookId = bookId, number = i, path = ref.path, title = ref.title, textHash = null) })
                bookId
            }
        }
    }

    /**
     * Pasted text as a book with no source and one chapter, titled with its first non-blank line (cut to 80
     * characters). Its sentences are stored at once. Throws [IllegalArgumentException] if no sentence is left.
     */
    suspend fun paste(text: String): Long {
        val sentences = withContext(Dispatchers.Default) { TextPrep.sentences(text) }
        require(sentences.isNotEmpty()) { "The pasted text has nothing to read" }
        val title = text.lineSequence().map { it.trim() }.first { it.isNotEmpty() }.take(TITLE_MAX).dropLastWhile { it.isHighSurrogate() }
        return db.withTransaction {
            val bookId = dao.insertBook(BookEntity(sourceId = null, path = null, title = title, coverUrl = null, addedAt = clock()))
            val chapterId = dao.insertChapter(ChapterEntity(bookId = bookId, number = 0, path = null, title = title, textHash = textHash(sentences)))
            dao.insertSentences(sentences.mapIndexed { i, s -> SentenceEntity(chapterId, i, s) })
            bookId
        }
    }

    /** Last played first, then the most recently added. */
    fun books(): Flow<List<Book>> = dao.books()

    /** In chapter order. */
    fun chapters(bookId: Long): Flow<List<Chapter>> = dao.chapters(bookId)

    suspend fun book(bookId: Long): Book? = dao.bookSummary(bookId)

    suspend fun chapter(chapterId: Long): Chapter? = dao.chapter(chapterId)

    /** The book's first chapter, where a book with no saved position starts. */
    suspend fun firstChapter(bookId: Long): Chapter? = dao.firstChapter(bookId)

    /** The chapter after this one in its book, or null at the last chapter. */
    suspend fun nextChapter(chapterId: Long): Chapter? = dao.nextChapter(chapterId)

    /**
     * The chapter's sentences. The first call fetches the text through [ChapterSource], splits it and stores the
     * sentences and the text hash in one transaction; later calls read the database only. Throws [IOException]
     * if fetching fails or leaves nothing to read, and stores nothing then. Throws [NoSuchElementException] for
     * a chapter that doesn't exist (or was deleted meanwhile).
     */
    suspend fun sentences(chapterId: Long): List<String> {
        dao.chapterEntity(chapterId)?.takeIf { it.textHash != null }?.let { return dao.sentences(chapterId) }
        // The reader and read-ahead often ask for the same chapter at once. One fetch at a time means the second
        // caller finds the text stored instead of fetching it again; requests to a site are one at a time anyway.
        return fetchLock.withLock { fetchSentences(chapterId) }
    }

    private suspend fun fetchSentences(chapterId: Long): List<String> {
        val chapter = dao.chapterEntity(chapterId) ?: throw NoSuchElementException("No chapter $chapterId")
        if (chapter.textHash != null) return dao.sentences(chapterId)
        val sourceId = dao.book(chapter.bookId)?.sourceId
        val path = chapter.path
        check(sourceId != null && path != null) { "Chapter $chapterId has no text and no source to fetch it from" }

        val text = source.chapterText(sourceId, path)
        val sentences = withContext(Dispatchers.Default) { TextPrep.sentences(text) }
        if (sentences.isEmpty()) throw IOException("$sourceId has nothing to read in $path")
        return db.withTransaction {
            val now = dao.chapterEntity(chapterId) ?: throw NoSuchElementException("No chapter $chapterId")
            if (now.textHash != null) {
                // Fetched by someone else meanwhile: keep what is stored so sentence numbers stay put.
                dao.sentences(chapterId)
            } else {
                dao.insertSentences(sentences.mapIndexed { i, s -> SentenceEntity(chapterId, i, s) })
                dao.setTextHash(chapterId, textHash(sentences))
                sentences
            }
        }
    }

    /** What read-ahead needs to know about the chapter, or null if its text isn't fetched yet. Never fetches. */
    suspend fun plan(chapterId: Long): ChapterPlan? =
        if (dao.chapter(chapterId)?.textHash == null) null else ChapterPlan(chapterId, dao.sentenceCount(chapterId))

    fun position(bookId: Long): Flow<Position?> = dao.position(bookId)

    /** Saves the position and sets the book's lastPlayedAt, in one transaction. A book deleted meanwhile is ignored. */
    suspend fun savePosition(position: Position) {
        val now = clock()
        db.withTransaction {
            if (dao.setPlayed(position.bookId, now) == 1) {
                dao.upsertPosition(PositionEntity(position.bookId, position.chapterId, position.sentence, now))
            }
        }
    }

    /** [savePosition], blocking until written. For service destroy and task removal. */
    fun savePositionNow(position: Position) = runBlocking(Dispatchers.IO) { savePosition(position) }

    /** The position of the most recently played book, or null. */
    suspend fun lastPlayed(): Position? = dao.lastPlayed()

    /** When the book's position was last saved, for the resume rule, or null if it has none. */
    suspend fun savedAt(bookId: Long): Long? = dao.savedAt(bookId)

    /** Records a jump away from [from] in its book's history: newest first, the last [History.MAX]. */
    suspend fun recordJump(from: Position) {
        db.withTransaction {
            if (dao.hasBook(from.bookId)) {
                val old = dao.history(from.bookId)
                val new = History.afterJump(old, from)
                if (new != old) {
                    dao.deleteHistory(from.bookId)
                    dao.insertHistory(new.mapIndexed { rank, p -> HistoryEntity(bookId = p.bookId, chapterId = p.chapterId, sentence = p.sentence, rank = rank) })
                }
            }
        }
    }

    /** The positions jumped away from, newest first. */
    suspend fun history(bookId: Long): List<Position> = dao.history(bookId)

    /** Removes the newest history entry and returns it, or null when the book has none. For undoing a jump. */
    suspend fun popHistory(bookId: Long): Position? = db.withTransaction {
        val old = dao.history(bookId)
        val newest = old.firstOrNull() ?: return@withTransaction null
        dao.deleteHistory(bookId)
        dao.insertHistory(old.drop(1).mapIndexed { rank, p -> HistoryEntity(bookId = p.bookId, chapterId = p.chapterId, sentence = p.sentence, rank = rank) })
        newest
    }

    /**
     * Fetches the chapter list again and appends the chapters whose path isn't listed yet, numbered after the
     * last. Existing chapters, text and sentences are untouched. Returns how many were added; 0 for pasted text.
     */
    suspend fun refresh(bookId: Long): Int {
        val book = dao.book(bookId) ?: return 0
        val sourceId = book.sourceId ?: return 0
        val path = book.path ?: return 0
        val refs = source.chapters(Listing(sourceId, path, book.title, book.coverUrl))
        return db.withTransaction {
            if (!dao.hasBook(bookId)) return@withTransaction 0
            val known = dao.chapterPaths(bookId).toSet()
            val first = (dao.lastNumber(bookId) ?: -1) + 1
            val fresh = refs.distinctBy { it.path }.filter { it.path !in known }
            dao.insertChapters(fresh.mapIndexed { i, ref -> ChapterEntity(bookId = bookId, number = first + i, path = ref.path, title = ref.title, textHash = null) })
            fresh.size
        }
    }

    /** Deletes the book and everything that belongs to it. */
    suspend fun delete(bookId: Long) = dao.deleteBook(bookId)

    private companion object {
        const val TITLE_MAX = 80

        /** Lowercase hex SHA-256 of the sentences joined with newlines. */
        fun textHash(sentences: List<String>): String =
            MessageDigest.getInstance("SHA-256").digest(sentences.joinToString("\n").toByteArray()).toHexString()
    }
}
