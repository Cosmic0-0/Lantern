package app.lantern.data

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Schema 1 to 2 (docs/phase-3-plan.md, step 1). Old databases are built from the committed schemas/1.json, then
 * opened through Room with the app's migrations. Room runs MIGRATION_1_2 and, while opening, checks every table
 * against schema 2 and fails if they differ: the same check room-testing's MigrationTestHelper makes.
 *
 * [realDatabaseMigrates] runs only when a copy of the phone's own lantern.db has been placed in
 * files/migration-check/ (see step 1 in the plan). Otherwise it is skipped.
 */
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After
    fun cleanUp() {
        listOf(TEST_DB, REAL_COPY).forEach { context.deleteDatabase(it) }
    }

    @Test
    fun everyRowSurvivesAndOldSentencesKeepOneParagraphEach() {
        createVersion1(TEST_DB) { db ->
            db.execSQL("INSERT INTO book (id, sourceId, path, title, coverUrl, addedAt, lastPlayedAt) VALUES (1, 'anf.net', '/ode', 'Ode', NULL, 100, 200)")
            db.execSQL("INSERT INTO book (id, sourceId, path, title, coverUrl, addedAt, lastPlayedAt) VALUES (2, NULL, NULL, 'Pasted', NULL, 300, NULL)")
            db.execSQL("INSERT INTO chapter (id, bookId, number, path, title, textHash) VALUES (10, 1, 0, '/ode/1', 'Chapter 1', 'h1')")
            db.execSQL("INSERT INTO chapter (id, bookId, number, path, title, textHash) VALUES (11, 1, 1, '/ode/2', 'Chapter 2', NULL)")
            db.execSQL("INSERT INTO chapter (id, bookId, number, path, title, textHash) VALUES (20, 2, 0, NULL, 'Pasted', 'h2')")
            listOf("One.", "\"Two!\"", "Three?").forEachIndexed { i, s ->
                db.execSQL("INSERT INTO sentence (chapterId, idx, text) VALUES (10, ?, ?)", arrayOf<Any>(i, s))
            }
            db.execSQL("INSERT INTO sentence (chapterId, idx, text) VALUES (20, 0, 'Only.')")
            db.execSQL("INSERT INTO position (bookId, chapterId, sentence, savedAt) VALUES (1, 10, 2, 500)")
            db.execSQL("INSERT INTO position_history (id, bookId, chapterId, sentence, rank) VALUES (1, 1, 10, 0, 0)")
            db.execSQL("INSERT INTO position_history (id, bookId, chapterId, sentence, rank) VALUES (2, 1, 10, 1, 1)")
        }

        openMigrated(TEST_DB) { db ->
            assertEquals(
                listOf("1|anf.net|/ode|Ode|100|200", "2|null|null|Pasted|300|null"),
                db.rows("SELECT id, sourceId, path, title, addedAt, lastPlayedAt FROM book ORDER BY id"),
            )
            assertEquals(
                listOf("10|1|0|/ode/1|Chapter 1|h1", "11|1|1|/ode/2|Chapter 2|null", "20|2|0|null|Pasted|h2"),
                db.rows("SELECT id, bookId, number, path, title, textHash FROM chapter ORDER BY id"),
            )
            assertEquals(
                listOf("10|0|One.|0", "10|1|\"Two!\"|1", "10|2|Three?|2", "20|0|Only.|0"),
                db.rows("SELECT chapterId, idx, text, paragraph FROM sentence ORDER BY chapterId, idx"),
            )
            assertEquals(listOf("1|10|2|500"), db.rows("SELECT bookId, chapterId, sentence, savedAt FROM position"))
            assertEquals(listOf("1|10|0|0", "1|10|1|1"), db.rows("SELECT bookId, chapterId, sentence, rank FROM position_history ORDER BY rank"))
            assertEquals(listOf("0"), db.rows("SELECT COUNT(*) FROM rule"))
            assertEquals(listOf("2"), db.rows("PRAGMA user_version"))
        }
    }

    @Test
    fun rulesForOneBookGoWithItAndRulesForAllBooksStay() {
        createVersion1(TEST_DB) { db ->
            db.execSQL("INSERT INTO book (id, sourceId, path, title, coverUrl, addedAt) VALUES (1, 'anf.net', '/ode', 'Ode', NULL, 100)")
        }
        openMigrated(TEST_DB) { db ->
            db.execSQL("INSERT INTO rule (bookId, kind, term, replacement, createdAt) VALUES (1, 'fix', 'Xiao Yan', 'Shao Yen', 1)")
            db.execSQL("INSERT INTO rule (bookId, kind, term, replacement, createdAt) VALUES (NULL, 'skip', 'Translator:', NULL, 2)")
            assertEquals(listOf("2"), db.rows("SELECT COUNT(*) FROM rule"))

            db.execSQL("DELETE FROM book WHERE id = 1")

            assertEquals(listOf("null|skip|Translator:|null"), db.rows("SELECT bookId, kind, term, replacement FROM rule"))
        }
    }

    @Test
    fun realDatabaseMigrates() {
        val source = File(context.filesDir, "migration-check")
        assumeTrue("no copy of the real database in $source", File(source, "lantern.db").isFile)
        val target = context.getDatabasePath(REAL_COPY)
        target.parentFile!!.mkdirs()
        for (suffix in listOf("", "-wal", "-shm")) {
            File(source, "lantern.db$suffix").takeIf { it.isFile }?.copyTo(File(target.path + suffix), overwrite = true)
        }
        val before = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(1, db.version)
            TABLES.associateWith { t -> db.rawQuery("SELECT COUNT(*) FROM $t", null).use { it.moveToFirst(); it.getLong(0) } }
        }

        openMigrated(REAL_COPY) { db ->
            val after = TABLES.associateWith { t -> db.rows("SELECT COUNT(*) FROM $t").single().toLong() }
            Log.i(TAG, "real database before=$before after=$after")
            assertEquals(before, after)
            assertEquals(listOf("0"), db.rows("SELECT COUNT(*) FROM sentence WHERE paragraph != idx"))
            assertEquals(listOf("ok"), db.rows("PRAGMA integrity_check"))
            assertEquals(listOf<String>(), db.rows("PRAGMA foreign_key_check"))
        }
    }

    /** A version 1 database made from schemas/1.json, as the app made it, with [fill] run on it. */
    private fun createVersion1(name: String, fill: (SQLiteDatabase) -> Unit) {
        val schema = instrumentation.context.assets.open("app.lantern.data.LanternDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("database")
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = 1
            fill(db)
        }
    }

    /** Opens [name] through Room with the app's migrations, which migrates and validates it, then runs [block]. */
    private fun openMigrated(name: String, block: (SupportSQLiteDatabase) -> Unit) {
        val room = Room.databaseBuilder(context, LanternDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            block(room.openHelper.writableDatabase)
        } finally {
            room.close()
        }
    }

    private fun SupportSQLiteDatabase.rows(sql: String): List<String> = query(sql).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "null" else c.getString(it) })
        }
    }

    private companion object {
        const val TAG = "LanternMigration"
        const val TEST_DB = "migration-test.db"
        const val REAL_COPY = "migration-real-copy.db"
        val TABLES = listOf("book", "chapter", "sentence", "position", "position_history")
    }
}
