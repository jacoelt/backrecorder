package com.backrecorderapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codes are never written down here: the shipping digests stand for real codes handed to real
 * people, and a plaintext one in this repository is a code anyone can redeem. Every case below
 * builds its own code and derives the digest the same way the build does.
 */
class ComplimentaryCodeValidatorTest {

    private val accepted = "TEST-AAAA-BBBB"
    private val retired = "TEST-CCCC-DDDD"

    private val validator = ComplimentaryCodeValidator(
        accepted = setOf(
            ComplimentaryCodeValidator.digestOf(accepted),
            ComplimentaryCodeValidator.digestOf(retired)
        ),
        blocked = setOf(ComplimentaryCodeValidator.digestOf(retired))
    )

    @Test
    fun `accepts a code whose digest is on the list`() {
        assertTrue(validator.accepts(accepted))
    }

    @Test
    fun `rejects a code that is not on the list`() {
        assertFalse(validator.accepts("TEST-EEEE-FFFF"))
    }

    @Test
    fun `rejects a block-listed code even though it is still accepted`() {
        assertFalse(validator.accepts(retired))
    }

    @Test
    fun `rejects an empty code`() {
        assertFalse(validator.accepts(""))
    }

    @Test
    fun `accepts the same code typed without separators, in lower case, with padding`() {
        assertTrue(validator.accepts("  testaaaabbbb "))
        assertTrue(validator.accepts("test aaaa bbbb"))
        assertTrue(validator.accepts("TeSt-AaAa-BbBb"))
    }

    @Test
    fun `a one character difference changes the digest`() {
        assertEquals(
            ComplimentaryCodeValidator.digestOf(accepted),
            ComplimentaryCodeValidator.digestOf(accepted)
        )
        assertFalse(
            ComplimentaryCodeValidator.digestOf(accepted) ==
                ComplimentaryCodeValidator.digestOf("TEST-AAAA-BBBC")
        )
    }

    @Test
    fun `normalize keeps only code characters`() {
        assertEquals("TESTAAAABBBB", ComplimentaryCodeValidator.normalize(" test-aaaa-bbbb\n"))
    }

    /**
     * Guards the one failure no behavioural test here can see: a digest pasted into the shipping
     * set truncated or upper-cased matches nothing, and the only symptom is a real person being
     * refused a code that was issued to them.
     */
    @Test
    fun `shipping digests are well formed sha-256 hex`() {
        val shipping = ComplimentaryCodeValidator.ACCEPTED_CODE_DIGESTS
        assertTrue(shipping.isNotEmpty())
        shipping.forEach { digest ->
            assertEquals(digest, 64, digest.length)
            assertTrue(digest, digest.all { it.isDigit() || it in 'a'..'f' })
        }
    }

    @Test
    fun `every retired digest is one that was issued`() {
        assertTrue(
            ComplimentaryCodeValidator.BLOCKED_CODE_DIGESTS
                .all { it in ComplimentaryCodeValidator.ACCEPTED_CODE_DIGESTS }
        )
    }
}
