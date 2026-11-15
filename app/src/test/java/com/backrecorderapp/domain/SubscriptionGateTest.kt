package com.backrecorderapp.domain

import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.billing.SubscriptionStatus
import com.backrecorderapp.data.preferences.ComplimentaryAccessStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionGateTest {

    private lateinit var billingRepository: BillingRepository
    private lateinit var complimentaryAccessStore: ComplimentaryAccessStore
    private lateinit var complimentaryGranted: MutableStateFlow<Boolean>
    private lateinit var billingResolved: MutableStateFlow<Boolean>
    private lateinit var subscriptionGate: SubscriptionGate

    @Before
    fun setup() {
        billingRepository = mockk()
        complimentaryAccessStore = mockk()
        complimentaryGranted = MutableStateFlow(false)
        billingResolved = MutableStateFlow(false)
        every { complimentaryAccessStore.granted } returns complimentaryGranted
        coEvery { complimentaryAccessStore.isGranted() } answers { complimentaryGranted.value }
        every { billingRepository.statusResolved } returns billingResolved
        subscriptionGate = SubscriptionGate(
            billingRepository,
            complimentaryAccessStore,
            CoroutineScope(UnconfinedTestDispatcher())
        )
    }

    @Test
    fun `free user can save within 15 minute window`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        val startTime = now - (10 * 60 * 1000) // 10 minutes ago
        val endTime = now

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Allowed)
    }

    @Test
    fun `free user blocked when start is well beyond free window`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        val startTime = now - (20 * 60 * 1000) // 20 minutes ago
        val endTime = now

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
        val startTime = now - (2 * 60 * 60 * 1000) // 2 hours ago
        val endTime = now

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Allowed)
    }

    @Test
    fun `trial user can save any time range`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.TRIAL

        val now = System.currentTimeMillis()
        val startTime = now - (2 * 60 * 60 * 1000) // 2 hours ago
        val endTime = now

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
    fun `free user at exactly 15 minutes ago is allowed thanks to grace period`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        val startTime = now - (15 * 60 * 1000) // exactly 15 minutes ago
        val endTime = now

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Allowed)
    }

    @Test
    fun `free user with 15 min selection plus in-progress seconds is allowed`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        // Start 15 min ago, end includes 45 extra seconds from in-progress minute
        val startTime = now - (15 * 60 * 1000)
        val endTime = now + 45_000

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Allowed)
    }

    @Test
    fun `complimentary access saves any time range while billing still reads FREE`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE
        complimentaryGranted.value = true

        val now = System.currentTimeMillis()
        val result = subscriptionGate.checkSaveAccess(now - (2 * 60 * 60 * 1000), now)

        assertTrue(result is GateResult.Allowed)
    }

    @Test
    fun `complimentary access allows cloud even when the subscription has expired`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.EXPIRED
        complimentaryGranted.value = true

        assertTrue(subscriptionGate.checkCloudAccess() is GateResult.Allowed)
    }

    @Test
    fun `complimentary access resolves entitlement without waiting for Play`() = runTest {
        complimentaryGranted.value = true

        assertTrue(subscriptionGate.statusResolved.value)
    }

    @Test
    fun `entitlement is unresolved while neither term has answered`() = runTest {
        assertFalse(subscriptionGate.statusResolved.value)
    }

    @Test
    fun `billing resolving alone still resolves entitlement`() = runTest {
        billingResolved.value = true

        assertTrue(subscriptionGate.statusResolved.value)
    }

    @Test
    fun `free user blocked when start is 17 minutes ago`() = runTest {
        coEvery { billingRepository.getSubscriptionStatus() } returns SubscriptionStatus.FREE

        val now = System.currentTimeMillis()
        // 17 minutes ago is safely past the 16-minute grace boundary (15 min limit +
        // 1 min grace), avoiding a same-millisecond race with the gate's own clock read.
        val startTime = now - (17 * 60 * 1000)
        val endTime = now

        val result = subscriptionGate.checkSaveAccess(startTime, endTime)
        assertTrue(result is GateResult.Blocked)
        assertEquals(
            BlockReason.SAVE_RANGE_EXCEEDS_FREE_LIMIT,
            (result as GateResult.Blocked).reason
        )
    }
}
