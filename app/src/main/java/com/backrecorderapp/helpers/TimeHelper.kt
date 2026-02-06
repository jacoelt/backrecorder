package com.backrecorderapp.helpers

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class TimeHelper {

    companion object {
        fun formatTime(time: LocalTime): String {
            val formatter = DateTimeFormatter
                .ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
            return time.format(formatter)
        }

        fun formatDuration(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return "%dh%02d".format(h, m)
        }
    }
}