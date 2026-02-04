package com.backrecorderapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

@Composable
fun FakeWaveformPreview(
    modifier: Modifier = Modifier
) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)

    Canvas (modifier = modifier) {
        val barWidth = size.width / 40f
        val maxBarHeight = size.height

        repeat(40) { index ->
            val barHeight = maxBarHeight * (0.3f + (index % 5) * 0.12f)
            drawRect(
                color = color,
                topLeft = Offset(
                    x = index * barWidth,
                    y = (maxBarHeight - barHeight) / 2
                ),
                size = Size(
                    width = barWidth * 0.6f,
                    height = barHeight
                )
            )
        }
    }
}
