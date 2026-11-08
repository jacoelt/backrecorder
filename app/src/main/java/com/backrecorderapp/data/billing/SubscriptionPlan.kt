package com.backrecorderapp.data.billing

import com.android.billingclient.api.ProductDetails
import java.time.Period
import java.time.format.DateTimeParseException

/**
 * A base plan of [BillingRepository.PREMIUM_SUBSCRIPTION_ID], and the id the Play Console gives it.
 *
 * The ids are a Play Console fact, not a naming choice: an offer is matched to a plan by
 * `basePlanId`, so a typo here is a plan the app can never buy. They are deliberately the only
 * place those strings appear.
 */
enum class BillingPeriod(val basePlanId: String) {
    MONTHLY("monthly"),
    YEARLY("yearly");

    companion object {
        fun forBasePlanId(basePlanId: String?): BillingPeriod? =
            entries.firstOrNull { it.basePlanId == basePlanId }
    }
}

/**
 * One plan as the chooser shows it, with every figure taken from Play.
 *
 * [formattedPrice] is Play's own localised, currency-formatted string and is the only thing that
 * may be shown to the user - building a price out of [priceAmountMicros] and
 * [priceCurrencyCode] by hand gets the separators, the symbol or its position wrong in some
 * locale. Those two are here for arithmetic only (see [SubscriptionPlans.yearlySavingsPercent]).
 */
data class SubscriptionPlan(
    val period: BillingPeriod,
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,
    /** Days of free trial on the offer this plan resolved to, or 0 when it has none. */
    val freeTrialDays: Int
)

/**
 * The purchasable plans, in [BillingPeriod] order. Never empty: a product with no purchasable
 * plan at all is [PurchaseFailure.PRODUCT_UNAVAILABLE], not an empty chooser.
 */
data class SubscriptionPlans(val plans: List<SubscriptionPlan>) {

    init {
        require(plans.isNotEmpty()) { "a plan set with nothing to buy is PRODUCT_UNAVAILABLE" }
    }

    val monthly: SubscriptionPlan? get() = plans.firstOrNull { it.period == BillingPeriod.MONTHLY }

    val yearly: SubscriptionPlan? get() = plans.firstOrNull { it.period == BillingPeriod.YEARLY }

    /**
     * How much less a year costs than twelve months bought one at a time, as a whole percent, or
     * null when there is nothing honest to show.
     *
     * Computed from Play's own prices rather than hard-coded, because the ratio is a Play Console
     * pricing decision that can be changed without an app release - a baked-in "17%" would become
     * a lie in the UI the day someone edits it there. Null, and so no badge, whenever the figure
     * would be meaningless or misleading: only one plan, two different currencies (the prices are
     * not comparable), or a yearly plan that is not actually cheaper.
     */
    val yearlySavingsPercent: Int?
        get() {
            val perMonth = monthly ?: return null
            val perYear = yearly ?: return null
            if (perMonth.priceCurrencyCode != perYear.priceCurrencyCode) return null
            val twelveMonths = perMonth.priceAmountMicros * 12
            if (twelveMonths <= 0L) return null
            val saved = twelveMonths - perYear.priceAmountMicros
            if (saved <= 0L) return null
            return ((saved * 100) / twelveMonths).toInt()
        }
}

/** What asking Play for the plans produced. */
sealed interface PlanQuery {
    data class Available(val plans: SubscriptionPlans) : PlanQuery
    data class Unavailable(val failure: PurchaseFailure) : PlanQuery
}

/** The phases of an offer, cheapest-first as Play orders them. */
private fun ProductDetails.SubscriptionOfferDetails.phases():
    List<ProductDetails.PricingPhase> = pricingPhases?.pricingPhaseList.orEmpty()

/**
 * The phase the user actually keeps paying, which is the last charged one - an offer with a trial
 * puts a free phase in front of it.
 */
internal fun ProductDetails.SubscriptionOfferDetails.recurringPhase(): ProductDetails.PricingPhase? =
    phases().lastOrNull { it.priceAmountMicros > 0L }

/** The free phase of an offer, if it has one. */
internal fun ProductDetails.SubscriptionOfferDetails.freeTrialPhase(): ProductDetails.PricingPhase? =
    phases().firstOrNull { it.priceAmountMicros == 0L }

/**
 * The offer to buy [period] on, or null when this account has none.
 *
 * Offers are matched by `basePlanId` and never by position: Play does not contract the order of
 * `subscriptionOfferDetails`, so indexing into it - which is what the hand-off used to do - picks
 * an arbitrary plan, and could commit someone to a year they never chose.
 *
 * `queryProductDetailsAsync` returns only the offers this account is eligible for, so no further
 * eligibility check is needed; within the chosen plan the trial offer is preferred when there is
 * one. An offer with no charged phase is skipped - there would be no price to show.
 */
internal fun ProductDetails.purchasableOfferFor(
    period: BillingPeriod
): ProductDetails.SubscriptionOfferDetails? {
    val forPlan = subscriptionOfferDetails.orEmpty().filter { offer ->
        offer.basePlanId == period.basePlanId &&
            !offer.offerToken.isNullOrEmpty() &&
            offer.recurringPhase() != null
    }
    return forPlan.firstOrNull { it.freeTrialPhase() != null } ?: forPlan.firstOrNull()
}

/** The plans this account can buy, or null when there is nothing purchasable at all. */
internal fun ProductDetails.toSubscriptionPlans(): SubscriptionPlans? {
    val plans = BillingPeriod.entries.mapNotNull { period ->
        val offer = purchasableOfferFor(period) ?: return@mapNotNull null
        val recurring = offer.recurringPhase() ?: return@mapNotNull null
        SubscriptionPlan(
            period = period,
            formattedPrice = recurring.formattedPrice,
            priceAmountMicros = recurring.priceAmountMicros,
            priceCurrencyCode = recurring.priceCurrencyCode,
            freeTrialDays = freeTrialDays(offer.freeTrialPhase()?.billingPeriod)
        )
    }
    return if (plans.isEmpty()) null else SubscriptionPlans(plans)
}

/**
 * An ISO-8601 billing period ("P1W", "P3D", "P1M") as whole days, for display only.
 *
 * Months and years are taken as 30 and 365 days. Trials are written in days or weeks in practice,
 * and the figure is never used for anything but a sentence telling the user roughly how long they
 * get - Play, not this, decides when the trial ends. An unparseable period is 0, which shows no
 * trial line rather than a wrong one.
 */
internal fun freeTrialDays(billingPeriod: String?): Int {
    if (billingPeriod.isNullOrBlank()) return 0
    return try {
        val period = Period.parse(billingPeriod)
        period.years * 365 + period.months * 30 + period.days
    } catch (_: DateTimeParseException) {
        0
    }
}
