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

    /**
     * The one assertion that pins the algorithm itself. Everything else here derives its expected
     * value through `digestOf`, so changing the salt or the normalization would keep every other
     * test green while invalidating all ten digests in the shipping build - and the only symptom
     * would be a real person being refused a code that was issued to them.
     */
    @Test
    fun `digestOf is pinned to a known vector`() {
        assertEquals(
            "dd821a31abc16a5cc8d09721e08aa93fb6389ce776118c00fcb6b4e552135091",
            ComplimentaryCodeValidator.digestOf(accepted)
        )
    }

    @Test
    fun `a normalisation-equivalent spelling hashes to the same pinned vector`() {
        assertEquals(
            ComplimentaryCodeValidator.digestOf(accepted),
            ComplimentaryCodeValidator.digestOf("  testaaaabbbb ")
        )
    }

    @Test
    fun `a one character difference changes the digest`() {
        assertFalse(
            ComplimentaryCodeValidator.digestOf(accepted) ==
                ComplimentaryCodeValidator.digestOf("TEST-AAAA-BBBC")
        )
    }

    /**
     * A short code would be crackable offline from the digests that ship in the APK, which is a
     * different threat from the APK-patching one #98 accepts: a cracked code can simply be typed
     * in by anyone it reaches.
     */
    @Test
    fun `rejects a code shorter than the minimum even if its digest is listed`() {
        val short = "ABC"
        val lenient = ComplimentaryCodeValidator(
            accepted = setOf(ComplimentaryCodeValidator.digestOf(short)),
            blocked = emptySet()
        )
        assertFalse(lenient.accepts(short))
    }

    @Test
    fun `acceptsDigest refuses a stored digest once it is retired`() {
        assertTrue(validator.acceptsDigest(ComplimentaryCodeValidator.digestOf(accepted)))
        assertFalse(validator.acceptsDigest(ComplimentaryCodeValidator.digestOf(retired)))
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
