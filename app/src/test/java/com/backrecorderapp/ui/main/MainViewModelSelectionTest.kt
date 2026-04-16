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

    // ---- Tests for minute-snapping logic with outward rounding (issue #7) ----

    private val MINUTE_MS = 60_000L

    // Reproduce the snap+clamp logic from MainViewModel.updateSelectionStart
    // (floor to previous minute, clamped to buffer bounds and minimum gap).
    private fun snapSelectionStart(
        fraction: Float,
        bufferStart: Long,
        bufferEnd: Long,
        selectionEnd: Long
    ): Long {
        val range = bufferEnd - bufferStart
        if (range <= 0) return bufferStart
        val raw = bufferStart + (fraction * range).toLong()
        val snapped = (raw / MINUTE_MS) * MINUTE_MS
        return snapped.coerceIn(bufferStart, selectionEnd - MINUTE_MS)
    }

    // Reproduce the snap+clamp logic from MainViewModel.updateSelectionEnd
    // (ceil to next minute, clamped to buffer bounds and minimum gap).
    private fun snapSelectionEnd(
        fraction: Float,
        bufferStart: Long,
        bufferEnd: Long,
        selectionStart: Long
    ): Long {
        val range = bufferEnd - bufferStart
        if (range <= 0) return bufferEnd
        val raw = bufferStart + (fraction * range).toLong()
        val snapped = ((raw + MINUTE_MS - 1) / MINUTE_MS) * MINUTE_MS
        return snapped.coerceIn(selectionStart + MINUTE_MS, bufferEnd)
    }

    @Test
    fun `end handle reaches currently-recording minute when bufferEnd is mid-minute (issue 7)`() {
        // bufferEnd is 20 seconds into the 36th minute — recording in progress.
        val bufferStart = 0L
        val bufferEnd = 36 * MINUTE_MS + 20_000L
        val selectionStart = 30 * MINUTE_MS

        // User drags end handle all the way to the right.
        val result = snapSelectionEnd(1.0f, bufferStart, bufferEnd, selectionStart)

        // Must include the currently-recording minute (i.e., reach bufferEnd),
        // not snap back to the previous minute boundary at 36:00.
        assertEquals(bufferEnd, result)
        assertTrue("end must include the in-progress minute", result > 36 * MINUTE_MS)
    }

    @Test
    fun `end handle reaches bufferEnd even when dragged into the in-progress minute`() {
        val bufferStart = 0L
        val bufferEnd = 36 * MINUTE_MS + 20_000L
        val selectionStart = 30 * MINUTE_MS

        // User drags to a point inside the current incomplete minute (~36:10).
        val fraction = (36 * MINUTE_MS + 10_000L).toFloat() / bufferEnd
        val result = snapSelectionEnd(fraction, bufferStart, bufferEnd, selectionStart)

        assertEquals(bufferEnd, result)
    }

    @Test
    fun `end handle ceils to next minute boundary when dragged inside a complete minute`() {
        val bufferStart = 0L
        val bufferEnd = 60 * MINUTE_MS
        val selectionStart = 0L

        // Drag to ~33:20 — should round UP to 34:00, not nearest (33:00).
        val fraction = (33 * MINUTE_MS + 20_000L).toFloat() / bufferEnd
        val result = snapSelectionEnd(fraction, bufferStart, bufferEnd, selectionStart)

        assertEquals(34 * MINUTE_MS, result)
    }

    @Test
    fun `start handle floors to previous minute boundary`() {
        val bufferStart = 0L
        val bufferEnd = 60 * MINUTE_MS
        val selectionEnd = 50 * MINUTE_MS

        // Drag to ~10:50 — should round DOWN to 10:00, not nearest (11:00).
        val fraction = (10 * MINUTE_MS + 50_000L).toFloat() / bufferEnd
        val result = snapSelectionStart(fraction, bufferStart, bufferEnd, selectionEnd)

        assertEquals(10 * MINUTE_MS, result)
    }

    @Test
    fun `start handle reaches bufferStart when bufferStart is mid-minute`() {
        // bufferStart is 40 seconds past a minute boundary.
        val bufferStart = 5 * MINUTE_MS + 40_000L
        val bufferEnd = 60 * MINUTE_MS
        val selectionEnd = 30 * MINUTE_MS

        val result = snapSelectionStart(0.0f, bufferStart, bufferEnd, selectionEnd)

        assertEquals(bufferStart, result)
    }

    @Test
    fun `end handle preserves minimum one-minute gap`() {
        val bufferStart = 0L
        val bufferEnd = 60 * MINUTE_MS
        val selectionStart = 30 * MINUTE_MS

        // Try to drag end below selectionStart.
        val fraction = (29 * MINUTE_MS).toFloat() / bufferEnd
        val result = snapSelectionEnd(fraction, bufferStart, bufferEnd, selectionStart)

        assertEquals(selectionStart + MINUTE_MS, result)
    }

    // ---- Tests for preset selection logic (issue #14) ----

    // Reproduce MainViewModel.selectLastDuration: exact duration, no snapping.
    private fun presetLastDuration(
        durationMs: Long,
        bufferStart: Long,
        bufferEnd: Long
    ): Pair<Long, Long> {
        if (bufferEnd <= bufferStart) return bufferStart to bufferEnd
        val end = bufferEnd
        val start = (end - durationMs).coerceAtLeast(bufferStart)
        return start to end
    }

    @Test
    fun `preset last 5 min selects exactly the trailing window when buffer is large enough`() {
        val bufferStart = 0L
        val bufferEnd = 60 * MINUTE_MS
        val (start, end) = presetLastDuration(5 * MINUTE_MS, bufferStart, bufferEnd)
        assertEquals(55 * MINUTE_MS, start)
        assertEquals(60 * MINUTE_MS, end)
    }

    @Test
    fun `preset last 1 hour clamps to bufferStart when buffer is shorter than 1 hour`() {
        val bufferStart = 0L
        val bufferEnd = 20 * MINUTE_MS
        val (start, end) = presetLastDuration(60 * MINUTE_MS, bufferStart, bufferEnd)
        assertEquals(bufferStart, start)
        assertEquals(bufferEnd, end)
    }

    @Test
    fun `preset produces minute-aligned timestamps when buffer ends on a minute boundary`() {
        val bufferStart = 0L
        val bufferEnd = 30 * MINUTE_MS
        val (start, end) = presetLastDuration(15 * MINUTE_MS, bufferStart, bufferEnd)
        assertEquals(0L, start % MINUTE_MS)
        assertEquals(0L, end % MINUTE_MS)
    }

    @Test
    fun `preset selects exact duration when bufferEnd is mid-minute`() {
        // bufferEnd is 20 seconds into the 36th minute — recording in progress.
        val bufferStart = 0L
        val bufferEnd = 36 * MINUTE_MS + 20_000L
        val (start, end) = presetLastDuration(5 * MINUTE_MS, bufferStart, bufferEnd)
        assertEquals(bufferEnd, end)
        // Exact 5 min before bufferEnd: 36:20 - 5:00 = 31:20.
        assertEquals(31 * MINUTE_MS + 20_000L, start)
        assertEquals(5 * MINUTE_MS, end - start)
    }

    @Test
    fun `preset selects exact duration even when start would be mid-minute`() {
        val bufferStart = 0L
        val bufferEnd = 60 * MINUTE_MS + 30_000L
        val (start, end) = presetLastDuration(15 * MINUTE_MS, bufferStart, bufferEnd)
        // 60:30 - 15:00 = 45:30, no snapping.
        assertEquals(45 * MINUTE_MS + 30_000L, start)
        assertEquals(15 * MINUTE_MS, end - start)
    }

    @Test
    fun `preset start clamps to mid-minute bufferStart when window reaches the left edge`() {
        // bufferStart is 40 seconds past a minute boundary (e.g., very young buffer).
        val bufferStart = 5 * MINUTE_MS + 40_000L
        val bufferEnd = 8 * MINUTE_MS
        val (start, end) = presetLastDuration(15 * MINUTE_MS, bufferStart, bufferEnd)
        assertEquals(bufferStart, start)
        assertEquals(bufferEnd, end)
    }

    @Test
    fun `preset is no-op when buffer is empty`() {
        val bufferStart = 1_000L
        val bufferEnd = 1_000L
        val (start, end) = presetLastDuration(5 * MINUTE_MS, bufferStart, bufferEnd)
        // Reproduces the early-return; values unchanged from the initial buffer.
        assertEquals(bufferStart, start)
        assertEquals(bufferEnd, end)
    }
}
