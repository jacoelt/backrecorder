package com.backrecorderapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.backrecorderapp.R
import com.backrecorderapp.helpers.TimeHelper
import java.time.LocalTime
@Composable
fun SavingContent(
    maxMinutes: Int,
    onConfirmSave: (startMinute: Int, endMinute: Int) -> Unit,
    onCancel: () -> Unit,
) {

    val defaultRange =
        (maxMinutes - 15).coerceAtLeast(0).toFloat()..maxMinutes.toFloat()

    var range by remember { mutableStateOf(defaultRange) }

    fun previousMinutesToCurrentHHMM(minutes: Int): String {
        val actualTime =
            LocalTime.now()
                .minusMinutes(maxMinutes.toLong())
                .plusMinutes(minutes.toLong())
        return TimeHelper.formatTime(actualTime)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {

        // ===== Waveform + Range =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = stringResource(R.string.select_range),
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                FakeWaveformPreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                RangeSlider(
                    value = range,
                    onValueChange = { newRange ->
                        if (maxMinutes < 2) {
                            range = 0f..1f
                            return@RangeSlider
                        }

                        val min = newRange.start
                        val max = newRange.endInclusive

                        range =
                            if (max - min < 1f) {
                                if (min != range.start) {
                                    (min - 1f)..max
                                } else {
                                    min..(max + 1f)
                                }
                            } else {
                                newRange
                            }
                    },
                    valueRange = 0f..maxMinutes.toFloat(),
                    steps = maxMinutes - 1
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        previousMinutesToCurrentHHMM(range.start.toInt()),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        previousMinutesToCurrentHHMM(range.endInclusive.toInt()),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ===== Presets =====
        Text(
            text = stringResource(R.string.quick_presets),
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetButton(
                    label = stringResource(R.string.last_5),
                    modifier = Modifier.weight(1f)
                ) {
                    range = (maxMinutes - 5).coerceAtLeast(0).toFloat()..maxMinutes.toFloat()
                }

                PresetButton(
                    label = stringResource(R.string.last_15),
                    modifier = Modifier.weight(1f)
                ) {
                    range = (maxMinutes - 15).coerceAtLeast(0).toFloat()..maxMinutes.toFloat()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetButton(
                    label = stringResource(R.string.last_1h),
                    modifier = Modifier.weight(1f)
                ) {
                    range = (maxMinutes - 60).coerceAtLeast(0).toFloat()..maxMinutes.toFloat()
                }

                PresetButton(
                    label = stringResource(R.string.full_recording),
                    modifier = Modifier.weight(1f)
                ) {
                    range = 0f..maxMinutes.toFloat()
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ===== Actions =====
        Button(
            onClick = {
                onConfirmSave(
                    range.start.toInt(),
                    range.endInclusive.toInt()
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.save_recording))
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.save_recording_cancel))
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
