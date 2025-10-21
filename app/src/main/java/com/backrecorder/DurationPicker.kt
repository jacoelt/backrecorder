package com.backrecorder

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.commandiron.wheel_picker_compose.WheelTimePicker
import java.time.LocalTime
import androidx.compose.ui.input.pointer.pointerInput

const val TAG = "DurationPicker"

@Composable
fun DurationPicker(
    enabled: Boolean,
    duration: Int,
    onDurationChange: (Int) -> Unit
) {
    val localTime = LocalTime.of(duration / 60, duration % 60)

    Box(
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f) // make it look disabled
    ) {
        WheelTimePicker(
            startTime = localTime
        ) { snappedTime ->
            if (!enabled) return@WheelTimePicker
            val newDuration = snappedTime.hour * 60 + snappedTime.minute
            if (newDuration != duration) {
                onDurationChange(newDuration)
            }
        }

        // Overlay to block all interactions when disabled
        if (!enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { /* consume all touches */ }
            )
        }
    }
}
