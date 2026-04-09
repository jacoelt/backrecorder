package com.backrecorderapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `durationString formats seconds only`() {
        assertEquals("0:30", TimeUtils.durationString(30_000))
    }

    @Test
    fun `durationString formats minutes and seconds`() {
        assertEquals("5:00", TimeUtils.durationString(300_000))
        assertEquals("12:34", TimeUtils.durationString(754_000))
    }

    @Test
    fun `durationString formats hours minutes seconds`() {
        assertEquals("1:00:00", TimeUtils.durationString(3_600_000))
        assertEquals("2:30:15", TimeUtils.durationString(9_015_000))
    }

    @Test
    fun `durationString handles zero`() {
        assertEquals("0:00", TimeUtils.durationString(0))
    }

    @Test
    fun `startOfBufferWindow returns 24h ago`() {
        val before = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        val result = TimeUtils.startOfBufferWindow()
        val after = System.currentTimeMillis() - (24 * 60 * 60 * 1000)

        // Result should be between before and after (within a few ms)
        assert(result in (before - 100)..(after + 100))
    }
}
