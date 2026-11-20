package app.lantern.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HeatGuardTest {
    @Test
    fun `pauses at 40 and resumes only at 37 or below`() {
        val guard = HeatGuard()
        val temps = listOf(30f, 39.9f, 40f, 39f, 37.1f, 37f, 38f, 39.9f, 41f)
        val paused = listOf(false, false, true, true, true, false, false, false, true)
        assertEquals(paused, temps.map(guard::update))
    }

    @Test
    fun `an unknown temperature changes nothing`() {
        val guard = HeatGuard()
        assertEquals(false, guard.update(null))
        guard.update(45f)
        assertEquals(true, guard.update(null))
    }
}
