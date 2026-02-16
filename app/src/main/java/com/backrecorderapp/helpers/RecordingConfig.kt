package com.backrecorderapp.helpers

object RecordingConfig {

    private const val MAX_MINUTES_TOTAL = 24 * 60  // 24h
    private const val MAX_MINUTES_TOTAL_QA = 17
    var currentMaxDuration = MAX_MINUTES_TOTAL

    fun switchQaDuration() {
        currentMaxDuration = if (currentMaxDuration == MAX_MINUTES_TOTAL) {
            MAX_MINUTES_TOTAL_QA
        } else {
            MAX_MINUTES_TOTAL
        }
    }
}
