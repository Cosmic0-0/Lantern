package app.lantern.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    private fun at(sentence: Int) = Position(bookId = 1, chapterId = 3, sentence = sentence)

    @Test
    fun `first jump from an empty history`() {
        assertEquals(listOf(at(5)), History.afterJump(emptyList(), at(5)))
    }

    @Test
    fun `newest first`() {
        assertEquals(listOf(at(9), at(5)), History.afterJump(listOf(at(5)), at(9)))
    }

    @Test
    fun `keeps at most 20 and drops the oldest`() {
        val full = (1..History.MAX).map { at(it) }.reversed() // newest is 20, oldest is 1
        val after = History.afterJump(full, at(99))
        assertEquals(History.MAX, after.size)
        assertEquals(at(99), after.first())
        assertEquals(at(2), after.last())
    }

    @Test
    fun `a repeat of the newest entry is not added`() {
        val history = listOf(at(9), at(5))
        assertEquals(history, History.afterJump(history, at(9)))
    }

    @Test
    fun `a repeat of an older entry is added and the old one is kept`() {
        val history = listOf(at(9), at(5))
        assertEquals(listOf(at(5), at(9), at(5)), History.afterJump(history, at(5)))
    }

    @Test
    fun `does not change the list it was given`() {
        val history = listOf(at(9))
        History.afterJump(history, at(1))
        assertEquals(listOf(at(9)), history)
    }
}
