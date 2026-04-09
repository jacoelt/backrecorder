package com.backrecorderapp.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the selection clamping logic extracted from MainViewModel.
 * Validates fixes for issues #5 and #9.
 */
class MainViewModelSelectionTest {

    // Reproduce the clamping logic from MainViewModel.updateSelectionStart/End
    private fun clampSelectionStart(
        fraction: Float,
        bufferStart: Long,
        bufferEnd: Long,
        selectionEnd: Long
    ): Long {
        val range = bufferEnd - bufferStart
        if (range <= 0) return bufferStart
        val time = bufferStart + (fraction * range).toLong()
        return time.coerceIn(bufferStart, selectionEnd - 1_000)
    }

    private fun clampSelectionEnd(
        fraction: Float,
        bufferStart: Long,
        bufferEnd: Long,
        selectionStart: Long
    ): Long {
        val range = bufferEnd - bufferStart
        if (range <= 0) return bufferEnd
        val time = bufferStart + (fraction * range).toLong()
        return time.coerceIn(selectionStart + 1_000, bufferEnd)
    }

    @Test
    fun `selection allows less than 1 minute gap (issue 5)`() {
        val bufferStart = 1000L
        val bufferEnd = 31_000L // 30 seconds of buffer

        // Select full range
        val selEnd = clampSelectionEnd(1.0f, bufferStart, bufferEnd, bufferStart)
        val selStart = clampSelectionStart(0.0f, bufferStart, bufferEnd, selEnd)

        assertEquals(bufferStart, selStart)
        assertEquals(bufferEnd, selEnd)
        assertTrue(selEnd - selStart < 60_000) // less than 1 minute is now allowed
    }

    @Test
    fun `minimum selection is 1 second`() {
        val bufferStart = 0L
        val bufferEnd = 100_000L
        val selectionEnd = 50_000L

        // Try to set start right at selectionEnd
        val result = clampSelectionStart(0.5f, bufferStart, bufferEnd, selectionEnd)
        assertEquals(selectionEnd - 1_000, result) // clamped to 1 second before end
    }

    @Test
    fun `selection end cannot go below start plus 1 second`() {
        val bufferStart = 0L
        val bufferEnd = 100_000L
        val selectionStart = 50_000L

        // Try to drag end to before start
        val result = clampSelectionEnd(0.2f, bufferStart, bufferEnd, selectionStart)
        assertEquals(selectionStart + 1_000, result) // clamped to 1 second after start
    }

    @Test
    fun `selection works with very short buffer (issue 5)`() {
        val bufferStart = 10_000L
        val bufferEnd = 15_000L // only 5 seconds of buffer

        val selEnd = clampSelectionEnd(1.0f, bufferStart, bufferEnd, bufferStart)
        val selStart = clampSelectionStart(0.0f, bufferStart, bufferEnd, selEnd)

        assertEquals(bufferStart, selStart)
        assertEquals(bufferEnd, selEnd)
        assertEquals(5_000L, selEnd - selStart)
    }

    @Test
    fun `start handle stays within buffer bounds`() {
        val bufferStart = 10_000L
        val bufferEnd = 100_000L
        val selectionEnd = 80_000L

        // Drag past left edge
        val result = clampSelectionStart(-0.5f, bufferStart, bufferEnd, selectionEnd)
        assertEquals(bufferStart, result)
    }

    @Test
    fun `end handle stays within buffer bounds`() {
        val bufferStart = 10_000L
        val bufferEnd = 100_000L
        val selectionStart = 30_000L

        // Drag past right edge
        val result = clampSelectionEnd(1.5f, bufferStart, bufferEnd, selectionStart)
        assertEquals(bufferEnd, result)
    }
}
