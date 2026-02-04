package com.backrecorderapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import java.util.Calendar

@Composable
fun SavingContent(
    maxMinutes: Int,
    onConfirmSave: (startMinute: Int, endMinute: Int) -> Unit,
    onCancel: () -> Unit,
) {
    var range by remember {
        mutableStateOf(0f..maxMinutes.toFloat())
    }

    fun previousMinutesToCurrentHHMM(minutes: Int): String {
        val currentTime = Calendar.getInstance()
        val totalMinutes = currentTime.get(Calendar.HOUR) * 60 + currentTime.get(Calendar.MINUTE) - maxMinutes + minutes
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return "%02d:%02d".format(h, m)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {

        // ===== Waveform + Range selector =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                FakeWaveformPreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                RangeSlider(
                    value = range,
                    onValueChange = { newRange ->
                        if (maxMinutes < 2) {
                            range = 0f..1f
                            return@RangeSlider
                        }

                        val min = newRange.start
                        val max = newRange.endInclusive

                        val correctedRange =
                            if (max - min < 1) {
                                // Determine which thumb moved
                                if (min != range.start) {
                                    // Left thumb moved → keep it left
                                    (min - 1)..max
                                } else {
                                    // Right thumb moved → keep it right
                                    min..(max + 1)
                                }
                            } else {
                                newRange
                            }

                        range = correctedRange
                    },
                    valueRange = 0f..maxMinutes.toFloat(),
                    steps = maxMinutes - 1,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row (
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(previousMinutesToCurrentHHMM(range.start.toInt()))
                    Text(previousMinutesToCurrentHHMM(range.endInclusive.toInt()))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== Presets =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = stringResource(R.string.quick_presets),
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                PresetButton("Last 5 min") {
                    range = (maxMinutes - 5).coerceAtLeast(0).toFloat()..maxMinutes.toFloat()
                }

                PresetButton("Last 15 min") {
                    range = (maxMinutes - 15).coerceAtLeast(0).toFloat()..maxMinutes.toFloat()
                }

                PresetButton("Last 1 hour") {
                    range = (maxMinutes - 60).coerceAtLeast(0).toFloat()..maxMinutes.toFloat()
                }

                PresetButton("Full recording") {
                    range = 0f..maxMinutes.toFloat()
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

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
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium
    )
}