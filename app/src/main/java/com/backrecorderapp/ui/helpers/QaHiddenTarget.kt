package com.backrecorderapp.ui.helpers

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun QaHiddenTapTarget(
    onTriggered: () -> Unit,
    content: @Composable () -> Unit
) {
    var tapCount by remember { mutableIntStateOf(0) }
    var firstTapTime by remember { mutableLongStateOf(0L) }

    Box(
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    val now = System.currentTimeMillis()

                    if (now - firstTapTime > 1000) {
                        tapCount = 0
                    }

                    if (tapCount == 0) {
                        firstTapTime = now
                    }
                    tapCount++

                    if (tapCount >= 5) {
                        tapCount = 0
                        onTriggered()
                    }
                }
            )
        }
    ) {
        content()
    }
}
