package app.lantern.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// The schema from docs/decisions/storage.md. A book with a null sourceId is pasted text. SQLite treats NULLs
// as distinct in a unique index, so any number of pasted books can share the (null, null) pair.

@Entity(tableName = "book", indices = [Index(value = ["sourceId", "path"], unique = true)])
internal data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String?,
    val path: String?,
    val title: String,
    val coverUrl: String?,
    val addedAt: Long,
    val lastPlayedAt: Long? = null,
)

/** [number] is the 0-based list order. [path] is null for pasted text, [textHash] until the text is fetched. */
@Entity(
    tableName = "chapter",
    foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "number"], unique = true)],
)
internal data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val number: Int,
    val path: String?,
    val title: String,
    val textHash: String?,
)

@Entity(
    tableName = "sentence",
    primaryKeys = ["chapterId", "idx"],
    foreignKeys = [ForeignKey(ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)],
)
internal data class SentenceEntity(
    val chapterId: Long,
    val idx: Int,
    val text: String,
    /** 0-based within the chapter (docs/decisions/paragraphs.md). Chapters stored before schema 2 have paragraph = idx. */
    @ColumnInfo(defaultValue = "0") val paragraph: Int,
)

/** The one saved position of a book. [chapterId] has no foreign key: the position outlives nothing but its book. */
@Entity(
    tableName = "position",
    foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
internal data class PositionEntity(
    @PrimaryKey val bookId: Long,
    val chapterId: Long,
    val sentence: Int,
    val savedAt: Long,
)

/** One position jumped away from. [rank] 0 is the newest. */
@Entity(
    tableName = "position_history",
    foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
internal data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val sentence: Int,
    val rank: Int,
)

/**
 * A skip rule or pronunciation fix (docs/decisions/speech-rules.md). [bookId] null means all books. [kind] is
 * [KIND_SKIP] or [KIND_FIX]. [replacement] is how a fix's [term] should sound, and null for a skip rule.
 * The column is `term`, not `match`, because MATCH is an SQLite keyword.
 */
@Entity(
    tableName = "rule",
    foreignKeys = [ForeignKey(BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
internal data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long?,
    val kind: String,
    val term: String,
    val replacement: String?,
    val createdAt: Long,
) {
    companion object {
        const val KIND_SKIP = "skip"
        const val KIND_FIX = "fix"
    }
}
