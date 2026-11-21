package app.lantern.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PrepareTextTest {
    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    @Test
    fun `a size under 1 GB is whole megabytes, rounded down`() {
        assertEquals("0 MB", formatSize(0))
        assertEquals("0 MB", formatSize(mb - 1))
        assertEquals("1 MB", formatSize(mb))
        assertEquals("450 MB", formatSize(450 * mb))
        assertEquals("1023 MB", formatSize(gb - 1))
    }

    @Test
    fun `a size from 1 GB has one decimal`() {
        assertEquals("1.0 GB", formatSize(gb))
        assertEquals("1.1 GB", formatSize((1.1 * gb).toLong()))
        assertEquals("12.5 GB", formatSize(12 * gb + gb / 2))
    }

    @Test
    fun `a duration under an hour is minutes`() {
        assertEquals("0 minutes", formatDuration(0))
        assertEquals("1 minute", formatDuration(1))
        assertEquals("45 minutes", formatDuration(45))
    }

    @Test
    fun `whole hours leave the minutes off`() {
        assertEquals("1 hour", formatDuration(60))
        assertEquals("2 hours", formatDuration(120))
    }

    @Test
    fun `hours and minutes are both said`() {
        assertEquals("1 hour 1 minute", formatDuration(61))
        assertEquals("1 hour 30 minutes", formatDuration(90))
        assertEquals("8 hours 54 minutes", formatDuration(534))
    }
}
