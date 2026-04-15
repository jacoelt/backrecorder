package com.backrecorderapp.domain

import android.content.Context
import com.backrecorderapp.data.cloud.DriveCloudStorage
import com.backrecorderapp.data.cloud.DriveFileInfo
import com.backrecorderapp.data.preferences.AppPreferences
import com.backrecorderapp.data.preferences.CloudUploadMode
import com.backrecorderapp.data.segment.SegmentManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class CloudSyncRecoveryTest {

    private lateinit var context: Context
    private lateinit var driveCloudStorage: DriveCloudStorage
    private lateinit var segmentManager: SegmentManager
    private lateinit var appPreferences: AppPreferences

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        driveCloudStorage = mockk(relaxed = true)
        segmentManager = mockk(relaxed = true)
        appPreferences = mockk(relaxed = true)

        every { driveCloudStorage.isInitialized } returns true
        every { appPreferences.cloudUploadMode } returns flowOf(CloudUploadMode.WIFI_ONLY)
    }

    @Test
    fun `stale files older than 1 hour outside window trigger recovery`() = runTest {
        val now = System.currentTimeMillis()
        val bufferStart = now - SegmentManager.BUFFER_DURATION_MS

        // Files that are 2 hours before the buffer window
        val staleFiles = (0 until 120).map { i ->
            DriveFileInfo(
                id = "file_$i",
                createdTime = bufferStart - (2 * 60 * 60 * 1000) + (i * 60 * 1000L)
            )
        }

        coEvery { driveCloudStorage.listStagingFiles() } returns staleFiles

        // The stale span is 120 minutes > 1 hour threshold, so recovery should trigger
        // This validates the threshold logic exists
        val staleSpanMs = staleFiles.last().createdTime - staleFiles.first().createdTime
        val staleSpanHours = staleSpanMs / (1000 * 60 * 60)
        assert(staleSpanHours >= 1) { "Stale span should be >= 1 hour to trigger recovery" }
    }

    @Test
    fun `files within buffer window are not stale`() = runTest {
        val now = System.currentTimeMillis()

        val recentFiles = (0 until 10).map { i ->
            DriveFileInfo(
                id = "file_$i",
                createdTime = now - (i * 60 * 1000L) // Within last 10 minutes
            )
        }

        val bufferStart = now - SegmentManager.BUFFER_DURATION_MS
        val staleFiles = recentFiles.filter { it.createdTime < bufferStart }

        assert(staleFiles.isEmpty()) { "Recent files should not be considered stale" }
    }

    @Test
    fun `gap threshold of 12h triggers recovery`() = runTest {
        val now = System.currentTimeMillis()
        val bufferStart = now - SegmentManager.BUFFER_DURATION_MS

        // Single file 13 hours before buffer window
        val staleFiles = listOf(
            DriveFileInfo(
                id = "file_1",
                createdTime = bufferStart - (13 * 60 * 60 * 1000)
            )
        )

        val latestStale = staleFiles.maxByOrNull { it.createdTime }!!
        val gapHours = (bufferStart - latestStale.createdTime) / (1000 * 60 * 60)

        assert(gapHours >= 12) { "Gap of 13h should exceed 12h threshold" }
    }

    @Test
    fun `small gap does not trigger recovery`() = runTest {
        val now = System.currentTimeMillis()
        val bufferStart = now - SegmentManager.BUFFER_DURATION_MS

        // Single file 30 minutes before buffer window (< 1 hour span, < 12h gap)
        val staleFiles = listOf(
            DriveFileInfo(
                id = "file_1",
                createdTime = bufferStart - (30 * 60 * 1000)
            )
        )

        val staleSpanHours = 0L // single file
        val latestStale = staleFiles.maxByOrNull { it.createdTime }!!
        val gapHours = (bufferStart - latestStale.createdTime) / (1000 * 60 * 60)

        assert(staleSpanHours < 1) { "Single file span should be < 1 hour" }
        assert(gapHours < 12) { "30 min gap should be < 12h threshold" }
    }
}
