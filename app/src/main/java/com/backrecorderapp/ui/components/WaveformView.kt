package com.backrecorderapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.backrecorderapp.util.TimeUtils
import kotlin.math.abs
import kotlin.math.hypot

private val HANDLE_ZONE_HEIGHT = 32.dp
private val LABEL_ROW_HEIGHT = 20.dp

@Composable
fun WaveformView(
    waveformData: FloatArray,
    bufferStartTime: Long,
    bufferEndTime: Long,
    selectionStartTime: Long,
    selectionEndTime: Long,
    onSelectionStartChanged: (Float) -> Unit,
    onSelectionEndChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val waveformColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val handleColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val timeRange = bufferEndTime - bufferStartTime

    val selectionStartFraction = if (timeRange > 0) {
        ((selectionStartTime - bufferStartTime).toFloat() / timeRange).coerceIn(0f, 1f)
    } else 0f

    val selectionEndFraction = if (timeRange > 0) {
        ((selectionEndTime - bufferStartTime).toFloat() / timeRange).coerceIn(0f, 1f)
    } else 1f

    val downsampled = remember(waveformData) {
        downsampleAmplitudes(waveformData, 800)
    }

    // Captured-by-reference so the gesture loop reads the latest values
    // without restarting the pointerInput coroutine.
    val currentStartFraction by rememberUpdatedState(selectionStartFraction)
    val currentEndFraction by rememberUpdatedState(selectionEndFraction)
    val onStartChanged by rememberUpdatedState(onSelectionStartChanged)
    val onEndChanged by rememberUpdatedState(onSelectionEndChanged)

    Box(modifier = modifier) {
        // Labels are declared first so they sit BEHIND the Canvas in Z-order.
        // The Canvas (declared last) draws the handles on top and owns the pointer
        // input for the full area, so label composables can't intercept edge touches.

        // Top labels overlay — positioned at the top of the canvas.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(LABEL_ROW_HEIGHT)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = TimeUtils.formatTime(selectionStartTime),
                style = MaterialTheme.typography.labelSmall,
                color = handleColor
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = TimeUtils.formatTime(selectionEndTime),
                style = MaterialTheme.typography.labelSmall,
                color = handleColor
            )
        }

        // Bottom labels overlay
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(LABEL_ROW_HEIGHT)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = TimeUtils.formatTime(bufferStartTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val duration = selectionEndTime - selectionStartTime
                Text(
                    text = "Selected: ${TimeUtils.durationString(duration)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = handleColor
                )
            }
            Text(
                text = TimeUtils.formatTime(bufferEndTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Canvas (top layer) — draws the waveform, selection, and handles, and
        // captures all touches across the full area. Handles are drawn only in
        // the handle zones, so labels remain visible through the transparent
        // label regions of the canvas.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(LABEL_ROW_HEIGHT + HANDLE_ZONE_HEIGHT + 150.dp + HANDLE_ZONE_HEIGHT + LABEL_ROW_HEIGHT)
                .pointerInput(Unit) {
                    val hitWidthPx = 32.dp.toPx()
                    val handleZonePx = HANDLE_ZONE_HEIGHT.toPx()
                    val labelPx = LABEL_ROW_HEIGHT.toPx()
                    // Y coordinates of the visible handle tab centers.
                    val startTabCenterY = labelPx + handleZonePx / 2f
                    val endTabCenterY = size.height - labelPx - handleZonePx / 2f

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val selStartX = currentStartFraction * size.width
                        val selEndX = currentEndFraction * size.width
                        val distToStart = abs(down.position.x - selStartX)
                        val distToEnd = abs(down.position.x - selEndX)

                        // In-range test uses only x distance (no y restriction) so
                        // taps anywhere along the vertical cursor line register,
                        // including at extremes where the tab is clipped off-screen.
                        val startInRange = distToStart <= hitWidthPx
                        val endInRange = distToEnd <= hitWidthPx

                        val draggingStart: Boolean? = when {
                            startInRange && !endInRange -> true
                            endInRange && !startInRange -> false
                            startInRange && endInRange -> {
                                // Both in range: pick the handle whose visible tab is
                                // closer to the tap in 2D. This lets the vertical axis
                                // disambiguate when the two handles are horizontally
                                // close, while x still dominates when they're far apart.
                                val d2Start = hypot(distToStart, abs(down.position.y - startTabCenterY))
                                val d2End = hypot(distToEnd, abs(down.position.y - endTabCenterY))
                                d2Start <= d2End
                            }
                            else -> null
                        }

                        if (draggingStart == null) return@awaitEachGesture
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            if (draggingStart) {
                                onStartChanged(fraction)
                            } else {
                                onEndChanged(fraction)
                            }
                            change.consume()
                        }
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val handleZonePx = HANDLE_ZONE_HEIGHT.toPx()
            val labelPx = LABEL_ROW_HEIGHT.toPx()
            val waveformTop = labelPx + handleZonePx
            val waveformBottom = canvasHeight - labelPx - handleZonePx
            val waveformHeight = waveformBottom - waveformTop
            val centerY = waveformTop + waveformHeight / 2

            // Selection region
            val selStartX = selectionStartFraction * canvasWidth
            val selEndX = selectionEndFraction * canvasWidth
            drawRect(
                color = selectionColor,
                topLeft = Offset(selStartX, waveformTop),
                size = Size(selEndX - selStartX, waveformHeight)
            )

            // Waveform bars
            if (downsampled.isNotEmpty()) {
                val barWidth = canvasWidth / downsampled.size
                for (i in downsampled.indices) {
                    val amplitude = downsampled[i]
                    val barHeight = amplitude * waveformHeight * 0.8f
                    val x = i * barWidth

                    drawLine(
                        color = waveformColor,
                        start = Offset(x + barWidth / 2, centerY - barHeight / 2),
                        end = Offset(x + barWidth / 2, centerY + barHeight / 2),
                        strokeWidth = (barWidth * 0.6f).coerceAtLeast(1f)
                    )
                }
            }

            // Hourly tick marks
            if (timeRange > 0) {
                drawHourlyTicks(bufferStartTime, bufferEndTime, canvasWidth, waveformBottom, tickColor)
            }

            // Cursor lines through waveform zone
            drawLine(
                color = handleColor,
                start = Offset(selStartX, waveformTop),
                end = Offset(selStartX, waveformBottom),
                strokeWidth = 3f
            )
            drawLine(
                color = handleColor,
                start = Offset(selEndX, waveformTop),
                end = Offset(selEndX, waveformBottom),
                strokeWidth = 3f
            )

            // Handle tabs in the handle zones (between label area and waveform)
            drawStartHandle(selStartX, labelPx, waveformTop, handleColor)
            drawEndHandle(selEndX, waveformBottom, canvasHeight - labelPx, handleColor)
        }
    }
}

private fun DrawScope.drawHourlyTicks(
    startTime: Long,
    endTime: Long,
    canvasWidth: Float,
    waveformBottom: Float,
    tickColor: Color
) {
    val timeRange = endTime - startTime
    val firstHour = ((startTime / 3_600_000) + 1) * 3_600_000

    var tickTime = firstHour
    while (tickTime < endTime) {
        val fraction = (tickTime - startTime).toFloat() / timeRange
        val x = fraction * canvasWidth

        drawLine(
            color = tickColor,
            start = Offset(x, waveformBottom - 12f),
            end = Offset(x, waveformBottom),
            strokeWidth = 1f
        )

        tickTime += 3_600_000
    }
}

private fun DrawScope.drawStartHandle(x: Float, topY: Float, bottomY: Float, color: Color) {
    // Tab handle in the start handle zone, extending right from the cursor line.
    val handleWidth = 36f
    val cornerSize = 8f
    val path = Path().apply {
        moveTo(x, bottomY)
        lineTo(x, topY)
        lineTo(x + handleWidth, topY)
        lineTo(x + handleWidth, bottomY - cornerSize)
        lineTo(x + cornerSize, bottomY)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawEndHandle(x: Float, topY: Float, bottomY: Float, color: Color) {
    // Tab handle in the end handle zone, extending left from the cursor line.
    val handleWidth = 36f
    val cornerSize = 8f
    val path = Path().apply {
        moveTo(x, topY)
        lineTo(x, bottomY)
        lineTo(x - handleWidth, bottomY)
        lineTo(x - handleWidth, topY + cornerSize)
        lineTo(x - cornerSize, topY)
        close()
    }
    drawPath(path, color)
}

private fun downsampleAmplitudes(amplitudes: FloatArray, targetSize: Int): FloatArray {
    if (amplitudes.isEmpty() || targetSize <= 0) return floatArrayOf()
    if (amplitudes.size <= targetSize) return amplitudes

    val result = FloatArray(targetSize)
    val bucketSize = amplitudes.size.toFloat() / targetSize
    for (i in 0 until targetSize) {
        val start = (i * bucketSize).toInt()
        val end = ((i + 1) * bucketSize).toInt().coerceAtMost(amplitudes.size)
        var peak = 0f
        for (j in start until end) {
            if (amplitudes[j] > peak) peak = amplitudes[j]
        }
        result[i] = peak
    }
    return result
}
