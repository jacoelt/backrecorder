package com.backrecorderapp.data.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Builds the Play [BillingClient].
 *
 * Its own seam for the same reason as the recorder and Drive ones: `BillingClient.newBuilder`
 * needs the Play framework, so without it [PlayBillingRepository] cannot be constructed on the
 * JVM and none of the hand-off logic - the connection sharing, the timeout, the main-thread
 * launch - can be unit-tested. That was not a theoretical cost: a review of this class found a
 * bug that reported every *successful* purchase as a failure, which a single test would have
 * caught.
 */
interface BillingClientFactory {
    fun create(listener: PurchasesUpdatedListener): BillingClient
}

class PlayBillingClientFactory @Inject constructor(
    @ApplicationContext private val context: Context
) : BillingClientFactory {

    override fun create(listener: PurchasesUpdatedListener): BillingClient =
        BillingClient.newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .build()
}
