package com.backrecorderapp.domain

import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubscriptionGateTest {

    private lateinit var billingRepository: BillingRepository
    private lateinit var subscriptionGate: SubscriptionGate

    @Before
    fun setup() {
        billingRepository = mockk()
        subscriptionGate = SubscriptionGate(billingRepository)
    }

    @Test
    fun `free user can save within 15 minute window`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        val startTime = now - (10 * 60 * 1000) // 10 minutes ago
        val endTime = now - (5 * 60 * 1000) // 5 minutes ago

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Allowed)
    }

    @Test
    fun `free user blocked from saving older than 15 minutes`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        val startTime = now - (20 * 60 * 1000) // 20 minutes ago
        val endTime = now - (16 * 60 * 1000) // 16 minutes ago

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Blocked)
        assertEquals(
            BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT,
            (result as GateResult.Blocked).reason
        )
    }

    @Test
    fun `premium user can save any time range`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE

        val now = System.currentTimeMillis()
        val startTime = now - (23 * 60 * 60 * 1000) // 23 hours ago
        val endTime = now - (22 * 60 * 60 * 1000) // 22 hours ago

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Allowed)
    }

    @Test
    fun `trial user can save any time range`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.TRIAL

        val now = System.currentTimeMillis()
        val startTime = now - (20 * 60 * 60 * 1000) // 20 hours ago
        val endTime = now - (19 * 60 * 60 * 1000) // 19 hours ago

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Allowed)
    }

    @Test
    fun `free user blocked from cloud access`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val result = subscriptionGate.checkCloudAccess()
        assertTrue(result is GateResult.Blocked)
        assertEquals(
            BlockReason.CLOUD_STORAGE_PREMIUM_ONLY,
            (result as GateResult.Blocked).reason
        )
    }

    @Test
    fun `premium user allowed cloud access`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.ACTIVE

        val result = subscriptionGate.checkCloudAccess()
        assertTrue(result is GateResult.Allowed)
    }

    @Test
    fun `expired user blocked from cloud access`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.EXPIRED

        val result = subscriptionGate.checkCloudAccess()
        assertTrue(result is GateResult.Blocked)
    }

    @Test
    fun `free user at exactly 15 minute boundary is allowed`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        val startTime = now - (15 * 60 * 1000) // exactly 15 minutes ago
        val endTime = now

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Allowed)
    }
}
