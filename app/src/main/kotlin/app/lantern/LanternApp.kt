package app.lantern

import android.app.Application
import android.content.Context
import app.lantern.data.LanternDatabase
import app.lantern.library.Library
import app.lantern.library.SourcesChapterSource
import app.lantern.sources.Sources
import app.lantern.prepare.PreparedAudio
import app.lantern.speech.Heat
import app.lantern.speech.Phonon
import app.lantern.speech.SentenceAudio
import app.lantern.ui.Covers
import app.lantern.ui.theme.READER_SIZE_DEFAULT
import app.lantern.ui.theme.READER_SIZE_MAX
import app.lantern.ui.theme.READER_SIZE_MIN
import app.lantern.ui.theme.ReaderFont
import app.lantern.ui.theme.ReaderTheme
import java.io.File

/** The one copy of the things screens and the playback service share. No DI framework: these few are enough. */
class LanternApp : Application() {
    val sources by lazy { Sources(log = { EventLog.log("HTTP", it) }) }
    val library by lazy { Library(LanternDatabase.get(this), SourcesChapterSource(sources)) }
    val settings by lazy { Settings(this) }
    val audio by lazy { SentenceAudio(File(cacheDir, "audio")) }
    val prepared by lazy { PreparedAudio(library, audio) }
    val heat by lazy { Heat(this) }
    val covers by lazy { Covers(File(cacheDir, "covers")) }

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}

val Context.lantern: LanternApp get() = applicationContext as LanternApp

/** Voice and speed, one setting each for all books (docs/decisions/mvp-assumptions.md), and how the library is shown. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var voice: String
        get() = prefs.getString("voice", null)?.takeIf { it in Phonon.VOICES } ?: Phonon.VOICES.first()
        set(value) {
            require(value in Phonon.VOICES)
            prefs.edit().putString("voice", value).apply()
        }

    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(value) {
            prefs.edit().putFloat("speed", value).apply()
        }

    /** The library's order: one of [LIBRARY_SORTS]. */
    var librarySort: String
        get() = prefs.getString("library_sort", null)?.takeIf { it in LIBRARY_SORTS } ?: LIBRARY_SORTS.first()
        set(value) {
            require(value in LIBRARY_SORTS)
            prefs.edit().putString("library_sort", value).apply()
        }

    /** Covers in a grid, or a list. */
    var libraryGrid: Boolean
        get() = prefs.getBoolean("library_grid", true)
        set(value) {
            prefs.edit().putBoolean("library_grid", value).apply()
        }

    /** The reader's text size in sp, its font and its theme (app.lantern.ui.theme.ReaderLook). */
    var readerTextSize: Float
        get() = prefs.getFloat("reader_text_size", READER_SIZE_DEFAULT)
        set(value) {
            prefs.edit().putFloat("reader_text_size", value.coerceIn(READER_SIZE_MIN, READER_SIZE_MAX)).apply()
        }

    var readerFont: ReaderFont
        get() = ReaderFont.of(prefs.getString("reader_font", "") ?: "")
        set(value) {
            prefs.edit().putString("reader_font", value.id).apply()
        }

    var readerTheme: ReaderTheme
        get() = ReaderTheme.of(prefs.getString("reader_theme", "") ?: "")
        set(value) {
            prefs.edit().putString("reader_theme", value.id).apply()
        }

    companion object {
        val LIBRARY_SORTS = listOf("recent", "title", "added")
    }
}
