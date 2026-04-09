package com.backrecorderapp.data.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WaveformRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: WaveformRepository

    @Before
    fun setup() {
        repository = WaveformRepository()
    }

    @Test
    fun `save and load amplitudes roundtrip`() {
        val file = File(tempFolder.root, "test_waveform.bin")
        val original = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

        repository.saveAmplitudes(file, original)
        val loaded = repository.loadAmplitudes(file)

        assertEquals(original.size, loaded.size)
        for (i in original.indices) {
            assertEquals(original[i], loaded[i], 0.001f)
        }
    }

    @Test
    fun `load from nonexistent file returns empty array`() {
        val file = File(tempFolder.root, "nonexistent.bin")
        val result = repository.loadAmplitudes(file)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `load from empty file returns empty array`() {
        val file = tempFolder.newFile("empty.bin")
        val result = repository.loadAmplitudes(file)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `downsample reduces array size`() {
        val input = FloatArray(1000) { it / 1000f }
        val result = repository.downsample(input, 100)
        assertEquals(100, result.size)
    }

    @Test
    fun `downsample preserves peak values in each bucket`() {
        // 10 values, downsample to 2 buckets of 5 each
        val input = floatArrayOf(0.1f, 0.2f, 0.5f, 0.3f, 0.1f, 0.2f, 0.3f, 0.4f, 0.9f, 0.1f)
        val result = repository.downsample(input, 2)

        assertEquals(2, result.size)
        assertEquals(0.5f, result[0], 0.001f) // max of first 5
        assertEquals(0.9f, result[1], 0.001f) // max of last 5
    }

    @Test
    fun `downsample with target larger than input returns original`() {
        val input = floatArrayOf(0.1f, 0.5f, 0.3f)
        val result = repository.downsample(input, 10)
        assertEquals(input.size, result.size)
    }

    @Test
    fun `downsample empty array returns empty`() {
        val result = repository.downsample(floatArrayOf(), 100)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `downsample with zero target returns empty`() {
        val result = repository.downsample(floatArrayOf(0.5f), 0)
        assertTrue(result.isEmpty())
    }
}
