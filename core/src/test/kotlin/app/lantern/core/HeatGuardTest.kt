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
    fun `a hot battery pauses at 45 and resumes at 42 or below, whatever the status says`() {
        val guard = HeatGuard()
        val temps = listOf(40f, 44.9f, 45f, 43f, 42.1f, 42f, 44f)
        val hot = listOf(false, false, true, true, true, false, false)
        assertEquals(hot, temps.map { guard.update(LIGHT_STATUS, it) })
    }

    @Test
    fun `either check alone keeps it hot`() {
        val guard = HeatGuard()
        guard.update(2, 46f)
        assertEquals(true, guard.update(0, 44f)) // status cool, battery still above 42
        assertEquals(false, guard.update(0, 41f))
        guard.update(2, 30f)
        assertEquals(true, guard.update(2, 30f)) // battery cool, status still moderate
    }

    @Test
    fun `an unknown status changes nothing`() {
        val guard = HeatGuard()
        assertEquals(false, guard.update(null))
        guard.update(3)
        assertEquals(true, guard.update(null))
    }

    private companion object {
        const val LIGHT_STATUS = 1
    }
}
