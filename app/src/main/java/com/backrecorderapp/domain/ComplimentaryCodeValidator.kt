package com.backrecorderapp.domain

import java.security.MessageDigest
import java.util.Locale

/**
 * Decides offline whether a redeem code grants complimentary access.
 *
 * Only salted digests are compiled in, never the codes themselves: this source is in the
 * repository, and a code published there is a code anyone can redeem. The salt buys nothing
 * against someone holding the APK - the accepted digests sit beside it - but it does stop a
 * generic rainbow table, which is all a 12-character code needs on top of its own ~59 bits.
 *
 * [blocked] is subtracted rather than [accepted] being edited, so a leaked code is retired in a
 * later release by adding one line, and the digest it leaked as stays on record.
 *
 * Takes its two sets as constructor arguments so the block-list path is testable without a
 * retired code having to exist in the shipping build.
 */
class ComplimentaryCodeValidator(
    private val accepted: Set<String> = ACCEPTED_CODE_DIGESTS,
    private val blocked: Set<String> = BLOCKED_CODE_DIGESTS
) {

    fun accepts(code: String): Boolean {
        val digest = digestOf(code)
        return digest in accepted && digest !in blocked
    }

    companion object {
        private const val SALT = "backrecorder.complimentary.v1"

        /**
         * Digests of the codes handed out for #98. Each entry is one person, so a code that
         * leaks can be retired on its own.
         */
        internal val ACCEPTED_CODE_DIGESTS = setOf(
            "00d364236376b535ba084e25fe1832079ac83bf8f59d4f531aa0cc43e5996526",
            "0cf6ac0bfe969e2c77f17bbd352f6775eb9260a2a3af07eb3011dee69fce687d",
            "b41d7a675edc2f986353f7c0cd3d015cdcc0eda71b5f217e1c11044e70cd7502",
            "4bf714746e6a931bd7446393ce9796eb83c0f567d98d848a3d7f0eec293e5c19",
            "d54f7c29db8fe3b2ad10968aad7158f2e511026ac2552c6c5f81c9c05704e82c",
            "64b97becc72c621236fd40f5e1db8684b36248bd2633b7f52144bf781d99f9e0",
            "8d630ff2ca15e2dae506fe8d8e4baced682d2883bac1d4add6c3f00d287fa945",
            "a0f3ef2ee5a25c850f976c051ab012788143683e0244c25366208899b471a35f",
            "e33af4ebbe468139c8d784c07a28e22328eeff68a6d814c6d3261ad1b28248a6",
            "69fdaa340a957767a987cb760650fb6a93312ee6e5e9900b5d278b5788682bb5"
        )

        /** Digests of codes that have been withdrawn. Checked after [ACCEPTED_CODE_DIGESTS]. */
        internal val BLOCKED_CODE_DIGESTS = emptySet<String>()

        /**
         * Codes are read off paper or a message, so the separators and the case a person types
         * are not part of the secret. Everything outside the code alphabet is dropped, which
         * also covers a trailing space pasted in from a chat message.
         */
        fun normalize(code: String): String =
            code.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)

        fun digestOf(code: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest("$SALT:${normalize(code)}".toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
