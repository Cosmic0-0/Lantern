package app.lantern.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

// Step 1 only proves Room and KSP build on this toolchain. Step 5 replaces this with the real schema
// (docs/decisions/storage.md); nothing is stored yet, so the version stays 1.

@Entity(tableName = "book")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
)

@Dao
interface BookDao {
    @Query("SELECT COUNT(*) FROM book")
    suspend fun count(): Int
}

@Database(entities = [BookEntity::class], version = 1)
abstract class LanternDatabase : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        @Volatile private var instance: LanternDatabase? = null

        fun get(context: Context): LanternDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LanternDatabase::class.java, "lantern.db")
                .build()
                .also { instance = it }
        }
    }
}
