package app.lantern.core

object History {
    const val MAX = 20

    /** The position history after jumping away from [from]. Newest first, at most MAX, and no repeat of the newest entry. */
    fun afterJump(history: List<Position>, from: Position): List<Position> =
        if (history.firstOrNull() == from) history.take(MAX) else (listOf(from) + history).take(MAX)
}
