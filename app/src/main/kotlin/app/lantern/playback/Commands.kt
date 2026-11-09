package app.lantern.playback

import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import app.lantern.core.Position

/** The playback service's own commands, and how a screen sends them through its MediaController. */
object Commands {
    const val PLAY_FROM = "lantern.play_from"
    const val CONTINUE = "lantern.continue"
    const val SET_VOICE = "lantern.set_voice"
    const val SLEEP = "lantern.sleep"

    /** Session extra: when the sleep timer stops playback, in epoch milliseconds. Absent when no timed sleep is set. */
    const val EXTRA_SLEEP_AT = "sleep_at"

    /** Session extra: true while the sleep timer is set to stop at the end of the chapter. */
    const val EXTRA_SLEEP_END_OF_CHAPTER = "sleep_end_of_chapter"

    internal const val ARG_BOOK = "book"
    internal const val ARG_CHAPTER = "chapter"
    internal const val ARG_SENTENCE = "sentence"
    internal const val ARG_JUMP = "jump"
    internal const val ARG_VOICE = "voice"
    internal const val ARG_SLEEP_MINUTES = "sleep_minutes" // > 0 minutes, SLEEP_END_OF_CHAPTER, or 0 to cancel
    internal const val SLEEP_END_OF_CHAPTER = -1

    /** A sleep timer setting (docs/decisions/phase-3-assumptions.md). */
    sealed interface Sleep {
        data class Minutes(val minutes: Int) : Sleep {
            init { require(minutes > 0) { "minutes must be positive" } }
        }

        data object EndOfChapter : Sleep
    }

    /** Sets the sleep timer, replacing any set before, or cancels it when [sleep] is null. */
    fun sleep(controller: MediaController, sleep: Sleep?) {
        val minutes = when (sleep) {
            null -> 0
            is Sleep.Minutes -> sleep.minutes
            Sleep.EndOfChapter -> SLEEP_END_OF_CHAPTER
        }
        controller.sendCustomCommand(SessionCommand(SLEEP, Bundle.EMPTY), Bundle().apply { putInt(ARG_SLEEP_MINUTES, minutes) })
    }

    /** Plays from [position]. [jump] records where you were in the position history, so it can be undone. */
    fun playFrom(controller: MediaController, position: Position, jump: Boolean) {
        val args = Bundle().apply {
            putLong(ARG_BOOK, position.bookId)
            putLong(ARG_CHAPTER, position.chapterId)
            putInt(ARG_SENTENCE, position.sentence)
            putBoolean(ARG_JUMP, jump)
        }
        controller.sendCustomCommand(SessionCommand(PLAY_FROM, Bundle.EMPTY), args)
    }

    /** Continues [bookId] from its saved position by the resume rule, or the last-played book when null. */
    fun continueBook(controller: MediaController, bookId: Long?) {
        val args = Bundle().apply { if (bookId != null) putLong(ARG_BOOK, bookId) }
        controller.sendCustomCommand(SessionCommand(CONTINUE, Bundle.EMPTY), args)
    }

    fun setVoice(controller: MediaController, voice: String) {
        controller.sendCustomCommand(SessionCommand(SET_VOICE, Bundle.EMPTY), Bundle().apply { putString(ARG_VOICE, voice) })
    }

    internal fun position(args: Bundle) =
        Position(args.getLong(ARG_BOOK), args.getLong(ARG_CHAPTER), args.getInt(ARG_SENTENCE))
}
