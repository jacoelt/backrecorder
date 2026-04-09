package com.backrecorderapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.backrecorderapp.data.waveform.WaveformRepository
import com.backrecorderapp.util.TimeUtils
import kotlin.math.abs

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
        val repo = WaveformRepository()
        repo.downsample(waveformData, 800)
    }

    Column(modifier = modifier) {
        // Time labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
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

        // Waveform canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .pointerInput(timeRange) {
                    detectHorizontalDragGestures { change, _ ->
                        val fraction = change.position.x / size.width

                        // Determine which handle is closer
                        val distToStart = abs(fraction - selectionStartFraction)
                        val distToEnd = abs(fraction - selectionEndFraction)

                        if (distToStart < distToEnd) {
                            onSelectionStartChanged(fraction.coerceIn(0f, 1f))
                        } else {
                            onSelectionEndChanged(fraction.coerceIn(0f, 1f))
                        }
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val centerY = canvasHeight / 2

            // Draw selection region
            val selStartX = selectionStartFraction * canvasWidth
            val selEndX = selectionEndFraction * canvasWidth
            drawRect(
                color = selectionColor,
                topLeft = Offset(selStartX, 0f),
                size = Size(selEndX - selStartX, canvasHeight)
            )

            // Draw waveform bars
            if (downsampled.isNotEmpty()) {
                val barWidth = canvasWidth / downsampled.size
                for (i in downsampled.indices) {
                    val amplitude = downsampled[i]
                    val barHeight = amplitude * canvasHeight * 0.8f
                    val x = i * barWidth

                    drawLine(
                        color = waveformColor,
                        start = Offset(x + barWidth / 2, centerY - barHeight / 2),
                        end = Offset(x + barWidth / 2, centerY + barHeight / 2),
                        strokeWidth = (barWidth * 0.6f).coerceAtLeast(1f)
                    )
                }
            }

            // Draw hourly tick marks
            if (timeRange > 0) {
                drawHourlyTicks(bufferStartTime, bufferEndTime, canvasWidth, canvasHeight, tickColor)
            }

            // Draw selection handles
            drawHandle(selStartX, canvasHeight, handleColor)
            drawHandle(selEndX, canvasHeight, handleColor)
        }

        // Buffer time range labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
    }
}

private fun DrawScope.drawHourlyTicks(
    startTime: Long,
    endTime: Long,
    canvasWidth: Float,
    canvasHeight: Float,
    tickColor: Color
) {
    val timeRange = endTime - startTime
    // Round up to next hour
    val firstHour = ((startTime / 3_600_000) + 1) * 3_600_000

    var tickTime = firstHour
    while (tickTime < endTime) {
        val fraction = (tickTime - startTime).toFloat() / timeRange
        val x = fraction * canvasWidth

        drawLine(
            color = tickColor,
            start = Offset(x, canvasHeight - 12f),
            end = Offset(x, canvasHeight),
            strokeWidth = 1f
        )

        tickTime += 3_600_000
    }
}

private fun DrawScope.drawHandle(x: Float, canvasHeight: Float, color: Color) {
    // Vertical line
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, canvasHeight),
        strokeWidth = 3f
    )

    // Top triangle handle
    drawCircle(
        color = color,
        radius = 8f,
        center = Offset(x, 8f)
    )

    // Bottom triangle handle
    drawCircle(
        color = color,
        radius = 8f,
        center = Offset(x, canvasHeight - 8f)
    )
}
