package app.lantern.core

/** A chapter as read-ahead sees it: its id and how many sentences it has. */
data class ChapterPlan(val chapterId: Long, val sentenceCount: Int)

object ReadAhead {
    /**
     * The next sentence to make audio for, as (chapterId, sentence index), or null when nothing is needed.
     * The first unready sentence at or after the playing one in the current chapter, then the first unready one in
     * the next chapter. Never past the end of the next chapter. [next] is null when its text isn't fetched yet.
     */
    fun next(playing: Position, current: ChapterPlan, next: ChapterPlan?, isReady: (chapterId: Long, sentence: Int) -> Boolean): Pair<Long, Int>? {
        require(playing.chapterId == current.chapterId) { "Playing chapter ${playing.chapterId} is not the current chapter ${current.chapterId}" }
        val inCurrent = (playing.sentence until current.sentenceCount).firstOrNull { !isReady(current.chapterId, it) }
        if (inCurrent != null) return current.chapterId to inCurrent
        if (next == null) return null
        return (0 until next.sentenceCount).firstOrNull { !isReady(next.chapterId, it) }?.let { next.chapterId to it }
    }
}
