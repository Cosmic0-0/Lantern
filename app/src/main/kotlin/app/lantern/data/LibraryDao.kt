package app.lantern.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import app.lantern.core.Position
import app.lantern.core.Sentence
import app.lantern.library.Book
import app.lantern.library.Chapter
import kotlinx.coroutines.flow.Flow

/**
 * Plain queries only; every rule lives in `Library`. Queries that return `Book`, `Chapter`, `Position` or
 * `Sentence` select exactly their columns, so Room fills them directly.
 */
@Dao
internal interface LibraryDao {
    @Query("SELECT COUNT(*) FROM book")
    suspend fun bookCount(): Int

    @Query(
        """SELECT id, title, sourceId, coverUrl, lastPlayedAt,
                  (SELECT COUNT(*) FROM chapter WHERE chapter.bookId = book.id) AS chapterCount
           FROM book ORDER BY lastPlayedAt DESC, addedAt DESC, id DESC""",
    )
    fun books(): Flow<List<Book>>

    @Query(
        """SELECT id, title, sourceId, coverUrl, lastPlayedAt,
                  (SELECT COUNT(*) FROM chapter WHERE chapter.bookId = book.id) AS chapterCount
           FROM book WHERE id = :id""",
    )
    suspend fun bookSummary(id: Long): Book?

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun book(id: Long): BookEntity?

    @Query("SELECT id FROM book WHERE sourceId = :sourceId AND path = :path")
    suspend fun bookId(sourceId: String, path: String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM book WHERE id = :id)")
    suspend fun hasBook(id: Long): Boolean

    @Insert
    suspend fun insertBook(book: BookEntity): Long

    @Query("DELETE FROM book WHERE id = :id")
    suspend fun deleteBook(id: Long)

    /** Sets lastPlayedAt and returns how many books matched (0 when the book is gone). */
    @Query("UPDATE book SET lastPlayedAt = :now WHERE id = :bookId")
    suspend fun setPlayed(bookId: Long, now: Long): Int

    @Query("SELECT id, bookId, number, title, textHash FROM chapter WHERE bookId = :bookId ORDER BY number")
    fun chapters(bookId: Long): Flow<List<Chapter>>

    @Query("SELECT id, bookId, number, title, textHash FROM chapter WHERE id = :id")
    suspend fun chapter(id: Long): Chapter?

    @Query("SELECT * FROM chapter WHERE id = :id")
    suspend fun chapterEntity(id: Long): ChapterEntity?

    @Query(
        """SELECT next.id, next.bookId, next.number, next.title, next.textHash
           FROM chapter AS this JOIN chapter AS next ON next.bookId = this.bookId AND next.number > this.number
           WHERE this.id = :id ORDER BY next.number LIMIT 1""",
    )
    suspend fun nextChapter(id: Long): Chapter?

    @Query("SELECT path FROM chapter WHERE bookId = :bookId AND path IS NOT NULL")
    suspend fun chapterPaths(bookId: Long): List<String>

    @Query("SELECT MAX(number) FROM chapter WHERE bookId = :bookId")
    suspend fun lastNumber(bookId: Long): Int?

    @Insert
    suspend fun insertChapter(chapter: ChapterEntity): Long

    @Insert
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("UPDATE chapter SET textHash = :hash WHERE id = :id")
    suspend fun setTextHash(id: Long, hash: String)

    @Query("SELECT text, paragraph FROM sentence WHERE chapterId = :chapterId ORDER BY idx")
    suspend fun sentences(chapterId: Long): List<Sentence>

    @Query("SELECT COUNT(*) FROM sentence WHERE chapterId = :chapterId")
    suspend fun sentenceCount(chapterId: Long): Int

    @Insert
    suspend fun insertSentences(sentences: List<SentenceEntity>)

    @Query("SELECT bookId, chapterId, sentence FROM position WHERE bookId = :bookId")
    fun position(bookId: Long): Flow<Position?>

    @Query("SELECT bookId, chapterId, sentence FROM position ORDER BY savedAt DESC LIMIT 1")
    suspend fun lastPlayed(): Position?

    @Query("SELECT savedAt FROM position WHERE bookId = :bookId")
    suspend fun savedAt(bookId: Long): Long?

    @Query("SELECT id, bookId, number, title, textHash FROM chapter WHERE bookId = :bookId ORDER BY number LIMIT 1")
    suspend fun firstChapter(bookId: Long): Chapter?

    @Upsert
    suspend fun upsertPosition(position: PositionEntity)

    @Query("SELECT bookId, chapterId, sentence FROM position_history WHERE bookId = :bookId ORDER BY rank")
    suspend fun history(bookId: Long): List<Position>

    @Query("DELETE FROM position_history WHERE bookId = :bookId")
    suspend fun deleteHistory(bookId: Long)

    @Insert
    suspend fun insertHistory(rows: List<HistoryEntity>)
}
