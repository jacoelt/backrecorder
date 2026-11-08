package com.backrecorderapp.util

import com.backrecorderapp.R
import com.backrecorderapp.data.billing.PurchaseFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The failure-to-message mapping. A copy-paste slip here compiles and ships: the user is told
 * the wrong reason their upgrade did not go through, which is worse than a generic message
 * because it sends them to fix the wrong thing.
 */
class PurchaseFailureMessagesTest {

    /**
     * Each mapping spelled out: a distinctness check alone would pass with two of the strings
     * swapped, which is exactly the slip that tells the user to go and fix the wrong thing.
     */
    @Test
    fun `each failure maps to the message that explains it`() {
        assertEquals(
            R.string.upgrade_error_billing_unavailable,
            PurchaseFailure.BILLING_UNAVAILABLE.messageRes()
        )
        assertEquals(
            R.string.upgrade_error_product_unavailable,
            PurchaseFailure.PRODUCT_UNAVAILABLE.messageRes()
        )
        assertEquals(
            R.string.upgrade_error_launch_failed,
            PurchaseFailure.LAUNCH_FAILED.messageRes()
        )
    }

    @Test
    fun `every failure maps to its own message`() {
        val messages = PurchaseFailure.entries.map { it.messageRes() }

        assertEquals(
            "two failures share a message resource",
            PurchaseFailure.entries.size,
            messages.toSet().size
        )
        messages.forEach { assertNotEquals("unmapped failure", 0, it) }
    }
}
