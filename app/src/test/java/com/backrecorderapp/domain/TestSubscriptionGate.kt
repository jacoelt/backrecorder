package com.backrecorderapp.domain

import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.data.preferences.ComplimentaryAccessStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * A gate for tests whose subject is something else, with the complimentary term held at
 * [complimentary] - so a test about saving or uploading does not have to restate how entitlement
 * is assembled every time that assembly changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun testSubscriptionGate(
    billingRepository: BillingRepository,
    complimentary: Boolean = false
): SubscriptionGate {
    val store = mockk<ComplimentaryAccessStore>()
    every { store.granted } returns MutableStateFlow(complimentary)
    coEvery { store.isGranted() } returns complimentary
    return SubscriptionGate(
        billingRepository,
        store,
        CoroutineScope(UnconfinedTestDispatcher())
    )
}
