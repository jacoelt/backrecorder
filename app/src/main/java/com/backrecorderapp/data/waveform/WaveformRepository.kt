package com.backrecorderapp.data.waveform

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WaveformRepository @Inject constructor() {

    companion object {
        const val SAMPLE_INTERVAL_MS = 200L // 5 Hz sampling
    }

    fun saveAmplitudes(file: File, amplitudes: List<Float>) {
        val buffer = ByteBuffer.allocate(amplitudes.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (amplitude in amplitudes) {
            buffer.putFloat(amplitude)
        }
        file.writeBytes(buffer.array())
    }

    fun loadAmplitudes(file: File): FloatArray {
        if (!file.exists() || file.length() == 0L) return floatArrayOf()
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = bytes.size / 4
        return FloatArray(count) { buffer.getFloat() }
    }
}
