package com.backrecorderapp.data.billing

import com.android.billingclient.api.ProductDetails
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning what Play returns into the plans the chooser shows (issue #74).
 *
 * This is the half of the fix that no amount of billing-client mocking reaches: picking the right
 * offer out of a list Play does not order, and working out a saving that must never overstate
 * itself.
 */
class SubscriptionPlanTest {

    private fun phase(
        micros: Long,
        formatted: String = "price",
        period: String = "P1M",
        currency: String = "USD"
    ): ProductDetails.PricingPhase = mockk(relaxed = true) {
        every { priceAmountMicros } returns micros
        every { formattedPrice } returns formatted
        every { priceCurrencyCode } returns currency
        every { billingPeriod } returns period
    }

    private fun offer(
        basePlanId: String,
        token: String = "$basePlanId-token",
        offerId: String? = null,
        phases: List<ProductDetails.PricingPhase> = listOf(phase(1_000_000L))
    ): ProductDetails.SubscriptionOfferDetails = mockk(relaxed = true) {
        every { this@mockk.basePlanId } returns basePlanId
        every { this@mockk.offerId } returns offerId
        every { offerToken } returns token
        every { pricingPhases } returns mockk(relaxed = true) {
            every { pricingPhaseList } returns phases
        }
    }

    private fun product(
        vararg offers: ProductDetails.SubscriptionOfferDetails
    ): ProductDetails = mockk(relaxed = true) {
        every { productId } returns BillingRepository.PREMIUM_SUBSCRIPTION_ID
        every { subscriptionOfferDetails } returns offers.toList()
    }

    private fun plan(
        period: BillingPeriod,
        micros: Long,
        currency: String = "USD",
        offerId: String? = null
    ) = SubscriptionPlan(
        period = period,
        offerId = offerId,
        formattedPrice = "formatted",
        priceAmountMicros = micros,
        priceCurrencyCode = currency,
        freeTrialDays = 0
    )

    /**
     * The defect this file exists for. Play does not contract the order of
     * `subscriptionOfferDetails`, so an offer must be found by its base plan id. Yearly is put
     * first here precisely so that taking element zero would get it wrong.
     */
    @Test
    fun `an offer is found by its base plan, not its position`() {
        val details = product(offer("yearly"), offer("monthly"))

        assertEquals(
            "monthly-token",
            details.purchasableOfferFor(BillingPeriod.MONTHLY)?.offerToken
        )
        assertEquals(
            "yearly-token",
            details.purchasableOfferFor(BillingPeriod.YEARLY)?.offerToken
        )
    }

    @Test
    fun `a base plan Play does not offer has no offer to buy`() {
        val details = product(offer("monthly"))

        assertNull(details.purchasableOfferFor(BillingPeriod.YEARLY))
    }

    /**
     * `queryProductDetailsAsync` returns only the offers this account is eligible for, so no
     * eligibility check is needed - but where the account can take a trial, it should get it.
     */
    @Test
    fun `the trial offer wins over a plain one on the same base plan`() {
        val plain = offer("monthly", token = "plain", phases = listOf(phase(2_990_000L)))
        val withTrial = offer(
            "monthly",
            token = "trial",
            phases = listOf(phase(0L, period = "P1W"), phase(2_990_000L))
        )

        // Either order: the trial is preferred, not merely first.
        assertEquals("trial", product(plain, withTrial).purchasableOfferFor(BillingPeriod.MONTHLY)?.offerToken)
        assertEquals("trial", product(withTrial, plain).purchasableOfferFor(BillingPeriod.MONTHLY)?.offerToken)
    }

    @Test
    fun `an offer with nothing to charge is not purchasable`() {
        val details = product(offer("monthly", phases = emptyList()))

        assertNull(details.purchasableOfferFor(BillingPeriod.MONTHLY))
        assertNull(details.toSubscriptionPlans())
    }

    @Test
    fun `an offer with no token is not purchasable`() {
        val details = product(offer("monthly", token = ""))

        assertNull(details.purchasableOfferFor(BillingPeriod.MONTHLY))
    }

    @Test
    fun `a base plan nothing recognises is ignored`() {
        val details = product(offer("quarterly"))

        assertNull(details.toSubscriptionPlans())
    }

    @Test
    fun `plans carry Play's own price string and the trial length`() {
        val details = product(
            offer(
                "monthly",
                phases = listOf(phase(0L, period = "P1W"), phase(2_990_000L, formatted = "US$2.99"))
            )
        )

        val plans = details.toSubscriptionPlans()!!

        assertEquals("US$2.99", plans.monthly?.formattedPrice)
        assertEquals(2_990_000L, plans.monthly?.priceAmountMicros)
        assertEquals(7, plans.monthly?.freeTrialDays)
    }

    /** The price that matters is the one the user keeps paying, not the trial in front of it. */
    @Test
    fun `the recurring price is taken, not the free phase`() {
        val details = product(
            offer("yearly", phases = listOf(phase(0L, period = "P1W"), phase(29_900_000L)))
        )

        assertEquals(29_900_000L, details.toSubscriptionPlans()?.yearly?.priceAmountMicros)
    }

    @Test
    fun `yearly priced at ten months reads as a saving of about a sixth`() {
        val plans = SubscriptionPlans(
            listOf(
                plan(BillingPeriod.MONTHLY, 2_990_000L),
                plan(BillingPeriod.YEARLY, 29_900_000L)
            )
        )

        assertEquals(16, plans.yearlySavingsPercent)
    }

    /**
     * "If the two prices ever stop implying a saving, show no badge rather than a misleading
     * one." A yearly plan priced at or above twelve months is not a saving.
     */
    @Test
    fun `a yearly plan that is not cheaper shows no saving`() {
        val plans = SubscriptionPlans(
            listOf(
                plan(BillingPeriod.MONTHLY, 2_990_000L),
                plan(BillingPeriod.YEARLY, 35_880_000L)
            )
        )

        assertNull(plans.yearlySavingsPercent)
    }

    @Test
    fun `a single plan has nothing to compare against`() {
        assertNull(SubscriptionPlans(listOf(plan(BillingPeriod.YEARLY, 29_900_000L))).yearlySavingsPercent)
        assertNull(SubscriptionPlans(listOf(plan(BillingPeriod.MONTHLY, 2_990_000L))).yearlySavingsPercent)
    }

    /** Prices in two currencies are not comparable, and subtracting them would be nonsense. */
    @Test
    fun `prices in different currencies produce no saving`() {
        val plans = SubscriptionPlans(
            listOf(
                plan(BillingPeriod.MONTHLY, 2_990_000L, currency = "USD"),
                plan(BillingPeriod.YEARLY, 29_900_000L, currency = "EUR")
            )
        )

        assertNull(plans.yearlySavingsPercent)
    }

    @Test
    fun `a free monthly plan cannot imply a saving`() {
        val plans = SubscriptionPlans(
            listOf(
                plan(BillingPeriod.MONTHLY, 0L),
                plan(BillingPeriod.YEARLY, 29_900_000L)
            )
        )

        assertNull(plans.yearlySavingsPercent)
    }

    @Test
    fun `trial lengths are read from the billing period`() {
        assertEquals(3, freeTrialDays("P3D"))
        assertEquals(7, freeTrialDays("P1W"))
        assertEquals(30, freeTrialDays("P1M"))
        assertEquals(365, freeTrialDays("P1Y"))
    }

    /** A period nothing can parse shows no trial line rather than a wrong one. */
    @Test
    fun `an unreadable billing period is no trial at all`() {
        assertEquals(0, freeTrialDays("not-a-period"))
        assertEquals(0, freeTrialDays(""))
        assertEquals(0, freeTrialDays(null))
    }

    /**
     * The badge is a percentage, so a saving too small to round to 1% is as meaningless as no
     * saving at all - and "Save ~0%" is exactly the misleading badge the spec said to suppress.
     */
    @Test
    fun `a saving too small to show a whole percent shows no badge`() {
        val plans = SubscriptionPlans(
            listOf(
                // Twelve months = 13.08; yearly 12.99 saves 0.09, which is 0 percent truncated.
                plan(BillingPeriod.MONTHLY, 1_090_000L),
                plan(BillingPeriod.YEARLY, 12_990_000L)
            )
        )

        assertNull(plans.yearlySavingsPercent)
    }

    @Test
    fun `a yearly plan priced at exactly twelve months shows no saving`() {
        val plans = SubscriptionPlans(
            listOf(
                plan(BillingPeriod.MONTHLY, 2_990_000L),
                plan(BillingPeriod.YEARLY, 2_990_000L * 12)
            )
        )

        assertNull(plans.yearlySavingsPercent)
    }

    // ---- Re-resolving the offer the chooser priced ----

    /**
     * A base plan can carry several eligible offers. The purchase must ask for the one the
     * chooser's figures came from rather than re-running the preference rule over a newer list,
     * which is the same unguaranteed ordering this file refuses to rely on elsewhere.
     */
    @Test
    fun `the chosen plan's own offer wins over the preferred one`() {
        val promo = offer(
            "monthly",
            token = "promo",
            offerId = "monthly-promo",
            phases = listOf(phase(0L, period = "P1M"), phase(2_990_000L))
        )
        val trial = offer(
            "monthly",
            token = "trial",
            offerId = "monthly-free-trial",
            phases = listOf(phase(0L, period = "P1W"), phase(2_990_000L))
        )
        val details = product(promo, trial)

        val chosen = plan(BillingPeriod.MONTHLY, 2_990_000L, offerId = "monthly-free-trial")

        assertEquals("trial", details.offerForChosenPlan(chosen)?.offerToken)
    }

    /** When that exact offer is gone, the preference rule is the fallback - not a refusal. */
    @Test
    fun `an offer that has gone falls back to the best remaining one`() {
        val details = product(
            offer(
                "monthly",
                token = "plain",
                offerId = null,
                phases = listOf(phase(2_990_000L))
            )
        )

        val chosen = plan(BillingPeriod.MONTHLY, 2_990_000L, offerId = "monthly-free-trial")

        assertEquals("plain", details.offerForChosenPlan(chosen)?.offerToken)
    }

    /** A base plan that has gone entirely fails closed rather than moving the user to the other. */
    @Test
    fun `a chosen plan whose base plan has gone resolves to nothing`() {
        val details = product(offer("yearly"))

        val chosen = plan(BillingPeriod.MONTHLY, 2_990_000L, offerId = "monthly-free-trial")

        assertNull(details.offerForChosenPlan(chosen))
    }

    /** A trial offer that cannot be bought must not shadow a plain one that can. */
    @Test
    fun `an unbuyable trial offer does not hide a usable plain offer`() {
        val brokenTrial = offer(
            "monthly",
            token = "",
            offerId = "monthly-free-trial",
            phases = listOf(phase(0L, period = "P1W"), phase(2_990_000L))
        )
        val plain = offer("monthly", token = "plain", phases = listOf(phase(2_990_000L)))

        assertEquals(
            "plain",
            product(brokenTrial, plain).purchasableOfferFor(BillingPeriod.MONTHLY)?.offerToken
        )
    }

    /** An intro price is not the price the user keeps paying. */
    @Test
    fun `an introductory price does not become the plan's price`() {
        val details = product(
            offer(
                "yearly",
                phases = listOf(
                    phase(0L, period = "P1W"),
                    phase(9_900_000L, formatted = "intro"),
                    phase(29_900_000L, formatted = "recurring")
                )
            )
        )

        val yearly = details.toSubscriptionPlans()?.yearly
        assertEquals("recurring", yearly?.formattedPrice)
        assertEquals(29_900_000L, yearly?.priceAmountMicros)
    }

    @Test
    fun `an empty plan set is a contradiction and is refused`() {
        val failed = runCatching { SubscriptionPlans(emptyList()) }
        assertEquals(true, failed.exceptionOrNull() is IllegalArgumentException)
    }

    /**
     * The chooser can price the base plan's own offer, which has no id. Re-resolving must then
     * pick that same id-less offer and *not* the trial sitting beside it: matching on a null
     * `offerId` is exactly the case where a sloppy fallback would silently move the user onto a
     * different offer than the one whose figures they agreed to.
     */
    @Test
    fun `a plan priced from the base plan's own offer does not drift onto the trial`() {
        val plain = offer(
            "monthly",
            token = "plain",
            offerId = null,
            phases = listOf(phase(2_990_000L))
        )
        val trial = offer(
            "monthly",
            token = "trial",
            offerId = "monthly-free-trial",
            phases = listOf(phase(0L, period = "P1W"), phase(2_990_000L))
        )
        // Trial first, so the preference rule would reach for it.
        val details = product(trial, plain)

        val chosen = plan(BillingPeriod.MONTHLY, 2_990_000L, offerId = null)

        assertEquals("plain", details.offerForChosenPlan(chosen)?.offerToken)
    }
}
