package app.lantern.core

object Resume {
    private const val AWAY_MS = 5 * 60 * 1000L

    /** Where playback starts from a saved position: the same sentence, or one earlier after more than 5 minutes away. Never crosses into the previous chapter. */
    fun startAt(saved: Position, awayMs: Long): Position =
        if (awayMs > AWAY_MS && saved.sentence > 0) saved.copy(sentence = saved.sentence - 1) else saved
}
