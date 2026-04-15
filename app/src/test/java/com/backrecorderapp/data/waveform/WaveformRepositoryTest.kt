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
}
