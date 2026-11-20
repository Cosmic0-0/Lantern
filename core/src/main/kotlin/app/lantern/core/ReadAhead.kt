package app.lantern.core

/** A chapter as read-ahead sees it: its id and how many sentences it has. */
data class ChapterPlan(val chapterId: Long, val sentenceCount: Int)

object ReadAhead {
    /** How many chapters past the playing one have their text fetched and their audio made while you listen. */
    const val CHAPTERS_AHEAD = 5

    /**
     * The next sentence to make audio for, as (chapterId, sentence index), or null when nothing is needed.
     * [chapters] is the playing chapter first, then the chapters after it whose text is stored, in order and with no
     * gap. The first unready sentence at or after the playing one, then the first unready one in each following
     * chapter in turn. Never past [CHAPTERS_AHEAD] chapters after the playing one: finishing a chapter opens room for
     * one more.
     */
    fun next(playing: Position, chapters: List<ChapterPlan>, isReady: (chapterId: Long, sentence: Int) -> Boolean): Pair<Long, Int>? {
        val current = chapters.firstOrNull()
        require(current?.chapterId == playing.chapterId) { "Playing chapter ${playing.chapterId} is not the first chapter ${current?.chapterId}" }
        for ((i, chapter) in chapters.take(1 + CHAPTERS_AHEAD).withIndex()) {
            val from = if (i == 0) playing.sentence else 0
            (from until chapter.sentenceCount).firstOrNull { !isReady(chapter.chapterId, it) }?.let { return chapter.chapterId to it }
        }
        return null
    }
}
