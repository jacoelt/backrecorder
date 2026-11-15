package com.backrecorderapp.domain

import java.security.MessageDigest
import java.util.Locale

/**
 * Decides offline whether a redeem code grants complimentary access.
 *
 * Only salted digests are compiled in, never the codes themselves: this source is in the
 * repository, and a code published there is a code anyone can redeem. The salt buys nothing
 * against someone holding the APK - the accepted digests sit beside it - but it does stop a
 * generic rainbow table.
 *
 * What it cannot stop is an offline attack on a *weak* code, and that is a different threat from
 * the one #98 accepts: patching the gate needs the APK and a rebuild, while a cracked code can be
 * typed in by anyone it is passed to. So codes must be long and random - see [MIN_CODE_LENGTH] -
 * and [accepts] refuses anything shorter rather than trusting whoever generates the next batch.
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
        // Bounded before hashing: the field this comes from is unbounded, and normalizing and
        // hashing a pasted novel would do it on the main thread.
        if (code.length > MAX_INPUT_LENGTH) return false
        val normalized = normalize(code)
        return normalized.length >= MIN_CODE_LENGTH && acceptsDigest(digestOf(normalized))
    }

    /**
     * Re-checks a grant that was stored earlier, which is what makes [blocked] able to *revoke*
     * rather than only refuse the next redemption. Without this, the one scenario the per-person
     * split exists for - a code gets passed around and several installs redeem it - would leave
     * every one of them premium forever, and the later release retiring the digest would change
     * nothing for them.
     */
    fun acceptsDigest(digest: String): Boolean = digest in accepted && digest !in blocked

    companion object {
        private const val SALT = "backrecorder.complimentary.v1"

        /**
         * Codes are generated as 12 characters from a 31-symbol alphabet (A-Z and 2-9, with the
         * pairs that are misread off paper removed) - about 59 bits, which is what makes an
         * offline search over the digests below pointless. Generate the next batch the same way;
         * a short or guessable code would be crackable from a shipped APK.
         */
        const val MIN_CODE_LENGTH = 12

        /** Generous for any real code, and small enough that a pasted blob is cheap to refuse. */
        const val MAX_INPUT_LENGTH = 256

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

        internal val BLOCKED_CODE_DIGESTS = emptySet<String>()

        /**
         * Codes are read off paper or a message, so the separators and the case a person types
         * are not part of the secret. Everything outside the code alphabet is dropped, which
         * also covers a trailing space pasted in from a chat message.
         *
         * `Locale.ROOT` is load-bearing: under a Turkish default locale `i` would uppercase to
         * `İ`, and the digest with it - refusing a code that person was actually issued.
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
