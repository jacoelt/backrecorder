package com.backrecorderapp.domain

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.db.SegmentEntity
import com.backrecorderapp.data.segment.SegmentManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SaveRecordingUseCaseTest {

    private lateinit var context: Context
    private lateinit var segmentManager: SegmentManager
    private lateinit var trimUseCase: TrimSegmentUseCase
    private lateinit var billingRepository: BillingRepository
    private lateinit var subscriptionGate: SubscriptionGate
    private lateinit var saveRecordingUseCase: SaveRecordingUseCase

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        segmentManager = mockk(relaxed = true)
        trimUseCase = mockk(relaxed = true)
        billingRepository = mockk()
        subscriptionGate = SubscriptionGate(billingRepository)

        every { context.contentResolver } returns mockk<ContentResolver>(relaxed = true)

        saveRecordingUseCase = SaveRecordingUseCase(context, segmentManager, trimUseCase, subscriptionGate)
    }

    @Test
    fun `returns upgrade required when free user tries to save old recording`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        val result = saveRecordingUseCase.execute(
            startTime = now - (30 * 60 * 1000), // 30 min ago
            endTime = now - (20 * 60 * 1000),   // 20 min ago
            outputUri = mockk()
        )

        assertTrue(result is SaveResult.UpgradeRequired)
        assertTrue((result as SaveResult.UpgradeRequired).reason == BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT)
    }

    @Test
    fun `returns error when no segments found`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE
        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns emptyList()

        val now = System.currentTimeMillis()
        val result = saveRecordingUseCase.execute(
            startTime = now - (5 * 60 * 1000),
            endTime = now,
            outputUri = mockk()
        )

        assertTrue(result is SaveResult.Error)
    }

    @Test
    fun `premium user can save old recordings`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE

        val now = System.currentTimeMillis()
        val segment = SegmentEntity(
            id = "1",
            filePath = "/nonexistent/file.m4a", // Will cause error in actual trim
            waveformPath = "/test/waveform.bin",
            startTime = now - (25 * 60 * 1000),
            endTime = now - (24 * 60 * 1000),
            durationMs = 60000,
            isComplete = true
        )

        coEvery { segmentManager.getSegmentsInRange(any(), any()) } returns listOf(segment)

        val outputUri = mockk<Uri>()
        // The actual file operations will fail since paths don't exist,
        // but subscription gate should not block
        val result = saveRecordingUseCase.execute(
            startTime = now - (25 * 60 * 1000),
            endTime = now - (24 * 60 * 1000),
            outputUri = outputUri
        )

        // Result will be Error because file doesn't exist, but NOT UpgradeRequired
        assertTrue(result !is SaveResult.UpgradeRequired)
    }
}
