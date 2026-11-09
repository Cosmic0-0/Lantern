package app.lantern.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// The library holds real data: every schema change needs a written migration, tested in MigrationTest, and
// never a destructive fallback. Room turns foreign keys on for declared ForeignKeys, which the cascades in
// Entities.kt depend on.
@Database(
    entities = [
        BookEntity::class, ChapterEntity::class, SentenceEntity::class, PositionEntity::class, HistoryEntity::class,
        RuleEntity::class,
    ],
    version = 2,
)
abstract class LanternDatabase : RoomDatabase() {
    internal abstract fun dao(): LibraryDao

    companion object {
        @Volatile private var instance: LanternDatabase? = null

        fun get(context: Context): LanternDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LanternDatabase::class.java, "lantern.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}

/**
 * Schema 2 (docs/decisions/paragraphs.md, docs/decisions/speech-rules.md): sentences get a paragraph number, and
 * old sentences get paragraph = idx so chapters stored before show one sentence per row as they did. Adds the
 * rule table. The SQL matches schemas/2.json, so a migrated database equals a fresh one.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sentence` ADD COLUMN `paragraph` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `sentence` SET `paragraph` = `idx`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER, " +
                "`kind` TEXT NOT NULL, `term` TEXT NOT NULL, `replacement` TEXT, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_rule_bookId` ON `rule` (`bookId`)")
    }
}
