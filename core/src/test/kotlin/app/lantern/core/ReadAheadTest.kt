package app.lantern.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadAheadTest {
    private val current = ChapterPlan(chapterId = 10, sentenceCount = 5)
    private val following = ChapterPlan(chapterId = 11, sentenceCount = 4)

    private fun playing(sentence: Int, chapterId: Long = 10) = Position(bookId = 1, chapterId = chapterId, sentence = sentence)

    /** A readiness check where only the listed (chapter, sentence) pairs are ready. */
    private fun ready(vararg pairs: Pair<Long, Int>): (Long, Int) -> Boolean {
        val set = pairs.toSet()
        return { chapter, sentence -> (chapter to sentence) in set }
    }

    private fun all(plan: ChapterPlan) = (0 until plan.sentenceCount).map { plan.chapterId to it }.toTypedArray()

    @Test
    fun `nothing needed when both chapters are fully ready`() {
        val isReady = ready(*all(current), *all(following))
        assertNull(ReadAhead.next(playing(0), current, following, isReady))
    }

    @Test
    fun `returns the first gap in the current chapter`() {
        val isReady = ready(10L to 1, 10L to 2, 10L to 4)
        assertEquals(10L to 3, ReadAhead.next(playing(1), current, following, isReady))
    }

    @Test
    fun `the playing sentence itself counts when unready`() {
        assertEquals(10L to 2, ReadAhead.next(playing(2), current, following, ready()))
    }

    @Test
    fun `moves to the next chapter when the current one is done`() {
        val isReady = ready(*all(current), 11L to 0, 11L to 1)
        assertEquals(11L to 2, ReadAhead.next(playing(0), current, following, isReady))
    }

    @Test
    fun `starts at the first sentence of the next chapter when none of it is ready`() {
        assertEquals(11L to 0, ReadAhead.next(playing(3), current, following, ready(10L to 3, 10L to 4)))
    }

    @Test
    fun `never goes past the end of the next chapter`() {
        val isReady = ready(*all(current), *all(following))
        assertNull(ReadAhead.next(playing(4), current, following, isReady))
    }

    @Test
    fun `next chapter text not fetched yet means nothing more to do once the current one is ready`() {
        assertNull(ReadAhead.next(playing(0), current, null, ready(*all(current))))
    }

    @Test
    fun `next chapter text not fetched yet still returns a gap in the current chapter`() {
        assertEquals(10L to 4, ReadAhead.next(playing(0), current, null, ready(*all(current).dropLast(1).toTypedArray())))
    }

    @Test
    fun `playing at the last sentence`() {
        assertEquals(10L to 4, ReadAhead.next(playing(4), current, following, ready()))
        assertEquals(11L to 0, ReadAhead.next(playing(4), current, following, ready(10L to 4)))
    }

    @Test
    fun `an empty current chapter goes straight to the next one`() {
        val empty = ChapterPlan(chapterId = 10, sentenceCount = 0)
        assertEquals(11L to 0, ReadAhead.next(playing(0), empty, following, ready()))
    }

    @Test
    fun `an empty next chapter has nothing to make`() {
        val empty = ChapterPlan(chapterId = 11, sentenceCount = 0)
        assertNull(ReadAhead.next(playing(0), current, empty, ready(*all(current))))
    }

    @Test
    fun `two empty chapters need nothing`() {
        val empty10 = ChapterPlan(chapterId = 10, sentenceCount = 0)
        val empty11 = ChapterPlan(chapterId = 11, sentenceCount = 0)
        assertNull(ReadAhead.next(playing(0), empty10, empty11, ready()))
    }

    @Test
    fun `a chapter that does not match the playing position throws`() {
        assertFailsWith<IllegalArgumentException> {
            ReadAhead.next(playing(0, chapterId = 99), current, following, ready())
        }
    }

    @Test
    fun `never returns an index before the playing one`() {
        // Nothing is ready at all, yet nothing before the playing sentence is asked for.
        for (start in 0 until current.sentenceCount) {
            val (chapterId, sentence) = ReadAhead.next(playing(start), current, following, ready())!!
            assertEquals(current.chapterId, chapterId)
            assertTrue(sentence >= start, "got $sentence for playing $start")
        }
        // Same when the current chapter is ready from the playing sentence on: it moves on, never back.
        assertEquals(11L to 0, ReadAhead.next(playing(3), current, following, ready(10L to 3, 10L to 4)))
    }
}
