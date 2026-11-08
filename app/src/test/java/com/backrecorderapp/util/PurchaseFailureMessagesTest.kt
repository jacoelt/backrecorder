package com.backrecorderapp.util

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
