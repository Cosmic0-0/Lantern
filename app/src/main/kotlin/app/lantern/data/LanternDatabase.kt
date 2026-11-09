package app.lantern.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Version stays 1 until the app has data worth migrating. Room turns foreign keys on for declared ForeignKeys,
// which the cascades in Entities.kt depend on.
@Database(
    entities = [BookEntity::class, ChapterEntity::class, SentenceEntity::class, PositionEntity::class, HistoryEntity::class],
    version = 1,
)
abstract class LanternDatabase : RoomDatabase() {
    internal abstract fun dao(): LibraryDao

    companion object {
        @Volatile private var instance: LanternDatabase? = null

        fun get(context: Context): LanternDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LanternDatabase::class.java, "lantern.db")
                .build()
                .also { instance = it }
        }
    }
}
