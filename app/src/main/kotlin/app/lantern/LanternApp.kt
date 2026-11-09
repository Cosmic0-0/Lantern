package app.lantern

import android.app.Application
import android.content.Context
import app.lantern.data.LanternDatabase
import app.lantern.library.Library
import app.lantern.library.SourcesChapterSource
import app.lantern.sources.Sources
import app.lantern.speech.Phonon

/** The one copy of the things screens and the playback service share. No DI framework: these few are enough. */
class LanternApp : Application() {
    val sources by lazy { Sources(log = { EventLog.log("HTTP", it) }) }
    val library by lazy { Library(LanternDatabase.get(this), SourcesChapterSource(sources)) }
    val settings by lazy { Settings(this) }

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}

val Context.lantern: LanternApp get() = applicationContext as LanternApp

/** Voice and speed, one setting each for all books (docs/decisions/mvp-assumptions.md). */
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
}
