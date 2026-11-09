package app.lantern.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeTest {
    private val saved = Position(bookId = 1, chapterId = 7, sentence = 12)
    private val minute = 60_000L

    @Test
    fun `resumes at the same sentence after 4 minutes`() {
        assertEquals(saved, Resume.startAt(saved, 4 * minute))
    }

    @Test
    fun `exactly 5 minutes is not more than 5 minutes`() {
        assertEquals(saved, Resume.startAt(saved, 5 * minute))
    }

    @Test
    fun `backs up one sentence after 6 minutes`() {
        assertEquals(saved.copy(sentence = 11), Resume.startAt(saved, 6 * minute))
    }

    @Test
    fun `backs up one sentence just past 5 minutes`() {
        assertEquals(saved.copy(sentence = 11), Resume.startAt(saved, 5 * minute + 1))
    }

    @Test
    fun `sentence 0 stays 0 and never crosses into the previous chapter`() {
        val first = saved.copy(sentence = 0)
        assertEquals(first, Resume.startAt(first, 60 * minute))
    }

    @Test
    fun `negative time away after a clock change counts as not away`() {
        assertEquals(saved, Resume.startAt(saved, -10 * minute))
    }
}
