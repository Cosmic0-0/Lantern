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

    /** The playing chapter (10) and [ahead] chapters after it (11, 12, ...), each with 3 sentences. */
    private fun book(ahead: Int) = (0..ahead).map { ChapterPlan(chapterId = 10L + it, sentenceCount = 3) }

    @Test
    fun `nothing needed when both chapters are fully ready`() {
        val isReady = ready(*all(current), *all(following))
        assertNull(ReadAhead.next(playing(0), listOf(current, following), isReady))
    }

    @Test
    fun `returns the first gap in the current chapter`() {
        val isReady = ready(10L to 1, 10L to 2, 10L to 4)
        assertEquals(10L to 3, ReadAhead.next(playing(1), listOf(current, following), isReady))
    }

    @Test
    fun `the playing sentence itself counts when unready`() {
        assertEquals(10L to 2, ReadAhead.next(playing(2), listOf(current, following), ready()))
    }

    @Test
    fun `moves to the next chapter when the current one is done`() {
        val isReady = ready(*all(current), 11L to 0, 11L to 1)
        assertEquals(11L to 2, ReadAhead.next(playing(0), listOf(current, following), isReady))
    }

    @Test
    fun `starts at the first sentence of the next chapter when none of it is ready`() {
        assertEquals(11L to 0, ReadAhead.next(playing(3), listOf(current, following), ready(10L to 3, 10L to 4)))
    }

    @Test
    fun `no chapters after the current one means nothing more to do once it is ready`() {
        assertNull(ReadAhead.next(playing(0), listOf(current), ready(*all(current))))
    }

    @Test
    fun `no chapters after the current one still returns a gap in it`() {
        assertEquals(10L to 4, ReadAhead.next(playing(0), listOf(current), ready(*all(current).dropLast(1).toTypedArray())))
    }

    @Test
    fun `playing at the last sentence`() {
        assertEquals(10L to 4, ReadAhead.next(playing(4), listOf(current, following), ready()))
        assertEquals(11L to 0, ReadAhead.next(playing(4), listOf(current, following), ready(10L to 4)))
    }

    @Test
    fun `an empty current chapter goes straight to the next one`() {
        val empty = ChapterPlan(chapterId = 10, sentenceCount = 0)
        assertEquals(11L to 0, ReadAhead.next(playing(0), listOf(empty, following), ready()))
    }

    @Test
    fun `empty chapters are passed over`() {
        val chapters = listOf(current, ChapterPlan(11, 0), ChapterPlan(12, 0), ChapterPlan(13, 2))
        assertEquals(13L to 0, ReadAhead.next(playing(0), chapters, ready(*all(current))))
        assertNull(ReadAhead.next(playing(0), chapters.dropLast(1), ready(*all(current))))
    }

    @Test
    fun `goes on to the fifth chapter ahead`() {
        val chapters = book(ahead = 5)
        val readyUpToFourth = chapters.dropLast(1).flatMap { all(it).toList() }.toTypedArray()
        assertEquals(15L to 0, ReadAhead.next(playing(0), chapters, ready(*readyUpToFourth)))
    }

    @Test
    fun `never goes past the fifth chapter ahead`() {
        val chapters = book(ahead = 7)
        val readyUpToFifth = chapters.take(1 + ReadAhead.CHAPTERS_AHEAD).flatMap { all(it).toList() }.toTypedArray()
        assertNull(ReadAhead.next(playing(0), chapters, ready(*readyUpToFifth)))
    }

    @Test
    fun `finishing a chapter opens room for one more`() {
        // Playing chapter 11 now: 12 to 16 are the five ahead, so 16 is next once 11 to 15 are ready.
        val chapters = book(ahead = 7).drop(1)
        val readyUpToFifteen = chapters.take(5).flatMap { all(it).toList() }.toTypedArray()
        assertEquals(16L to 0, ReadAhead.next(playing(0, chapterId = 11), chapters, ready(*readyUpToFifteen)))
    }

    @Test
    fun `a chapter that does not match the playing position throws`() {
        assertFailsWith<IllegalArgumentException> {
            ReadAhead.next(playing(0, chapterId = 99), listOf(current, following), ready())
        }
        assertFailsWith<IllegalArgumentException> { ReadAhead.next(playing(0), emptyList(), ready()) }
    }

    @Test
    fun `never returns an index before the playing one`() {
        // Nothing is ready at all, yet nothing before the playing sentence is asked for.
        for (start in 0 until current.sentenceCount) {
            val (chapterId, sentence) = ReadAhead.next(playing(start), listOf(current, following), ready())!!
            assertEquals(current.chapterId, chapterId)
            assertTrue(sentence >= start, "got $sentence for playing $start")
        }
        // Same when the current chapter is ready from the playing sentence on: it moves on, never back.
        assertEquals(11L to 0, ReadAhead.next(playing(3), listOf(current, following), ready(10L to 3, 10L to 4)))
    }
}
