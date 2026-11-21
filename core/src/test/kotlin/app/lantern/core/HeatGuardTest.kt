package app.lantern.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HeatGuardTest {
    @Test
    fun `hot from moderate until back to light`() {
        val guard = HeatGuard()
        val statuses = listOf(0, 1, 2, 3, 2, 1, 0, 1, 4)
        val hot = listOf(false, false, true, true, true, false, false, false, true)
        assertEquals(hot, statuses.map(guard::update))
    }

    @Test
    fun `the gap holds until the status falls far enough`() {
        val guard = HeatGuard(pauseAt = 3, resumeAt = 1)
        assertEquals(listOf(false, true, true, false), listOf(2, 3, 2, 1).map(guard::update))
    }

    @Test
    fun `an unknown status changes nothing`() {
        val guard = HeatGuard()
        assertEquals(false, guard.update(null))
        guard.update(3)
        assertEquals(true, guard.update(null))
    }
}
