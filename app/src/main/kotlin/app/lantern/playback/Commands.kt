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

    internal const val ARG_BOOK = "book"
    internal const val ARG_CHAPTER = "chapter"
    internal const val ARG_SENTENCE = "sentence"
    internal const val ARG_JUMP = "jump"
    internal const val ARG_VOICE = "voice"

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
