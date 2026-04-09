package com.backrecorderapp.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeUtils {

    private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.US)
    private val TIME_FORMAT_SECONDS = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val DATETIME_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun formatTime(epochMs: Long): String = TIME_FORMAT.format(Date(epochMs))

    fun formatTimeWithSeconds(epochMs: Long): String = TIME_FORMAT_SECONDS.format(Date(epochMs))

    fun formatForFilename(epochMs: Long): String = DATETIME_FORMAT.format(Date(epochMs))

    fun startOfBufferWindow(): Long {
        return System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
    }

    fun durationString(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
