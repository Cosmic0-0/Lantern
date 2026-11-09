package app.lantern.spike.playback

import android.content.Context

/** Where the listener is: a sentence of a chapter, and when that was saved. */
data class Pos(val chapter: Int, val sentence: Int, val ms: Long, val savedAt: Long /* System.currentTimeMillis */)

/** The saved position, written with commit() so it is on disk when the call returns. */
object Position {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("position", Context.MODE_PRIVATE)

    fun save(ctx: Context, pos: Pos, reason: String) {
        val ok = prefs(ctx).edit()
            .putInt("chapter", pos.chapter)
            .putInt("sentence", pos.sentence)
            .putLong("ms", pos.ms)
            .putLong("savedAt", pos.savedAt)
            .commit()
        EventLog.log("SAVE", "chapter=${pos.chapter} sentence=${pos.sentence} ms=${pos.ms} reason=$reason${if (ok) "" else " COMMIT_FAILED"}")
    }

    fun load(ctx: Context): Pos? {
        val p = prefs(ctx)
        if (!p.contains("chapter")) return null
        return Pos(p.getInt("chapter", 1), p.getInt("sentence", 0), p.getLong("ms", 0), p.getLong("savedAt", 0))
    }
}
