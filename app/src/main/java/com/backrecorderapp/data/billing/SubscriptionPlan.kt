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
    YEARLY("yearly")
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
    /**
     * The offer this plan's figures came from, so the purchase can ask for that same offer again
     * rather than re-running the preference rule over a newer list. Null when the base plan's own
     * offer (which has no id) is the one that was chosen.
     */
    val offerId: String?,
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
            // The percent, not the micros, is what the badge shows: a real but tiny saving
            // truncates to 0 and would render "Save ~0%", which is the meaningless badge this
            // whole property exists to suppress.
            return ((saved * 100) / twelveMonths).toInt().takeIf { it > 0 }
        }
}

sealed interface PlanQuery {
    data class Available(val plans: SubscriptionPlans) : PlanQuery
    data class Unavailable(val failure: PurchaseFailure) : PlanQuery
}

/**
 * The phases of an offer, in the order Play applies them over time.
 *
 * Unlike the *offer* list, this order is meaningful and documented - a trial or intro phase comes
 * before the phase it discounts - which is what makes "the last charged phase" the steady-state
 * price below.
 */
private fun ProductDetails.SubscriptionOfferDetails.phases():
    List<ProductDetails.PricingPhase> = pricingPhases?.pricingPhaseList.orEmpty()

/**
 * The phase the user actually keeps paying: the last charged one, so a trial (free) or an
 * introductory (cheaper) phase in front of it is not mistaken for the real price.
 */
internal fun ProductDetails.SubscriptionOfferDetails.recurringPhase(): ProductDetails.PricingPhase? =
    phases().lastOrNull { it.priceAmountMicros > 0L }

internal fun ProductDetails.SubscriptionOfferDetails.freeTrialPhase(): ProductDetails.PricingPhase? =
    phases().firstOrNull { it.priceAmountMicros == 0L }

/**
 * Every offer this account could buy [period] on.
 *
 * Offers are matched by `basePlanId` and never by position: Play does not contract the order of
 * `subscriptionOfferDetails`, so indexing into it - which is what the hand-off used to do - picks
 * an arbitrary plan, and could commit someone to a year they never chose.
 *
 * `queryProductDetailsAsync` returns only the offers this account is eligible for, so no further
 * eligibility check is needed. An offer with no token cannot be bought, and one with no charged
 * phase has no price to show, so neither is a candidate.
 */
private fun ProductDetails.purchasableOffersFor(
    period: BillingPeriod
): List<ProductDetails.SubscriptionOfferDetails> =
    subscriptionOfferDetails.orEmpty().filter { offer ->
        offer.basePlanId == period.basePlanId &&
            !offer.offerToken.isNullOrEmpty() &&
            offer.recurringPhase() != null
    }

/** The best offer to show for [period]: the one with a trial, where this account has one. */
internal fun ProductDetails.purchasableOfferFor(
    period: BillingPeriod
): ProductDetails.SubscriptionOfferDetails? = purchasableOffersFor(period).bestOffer()

/**
 * The offer to actually buy [plan] on, re-resolved against a newer product query.
 *
 * It asks for the same `offerId` the chooser's figures came from, rather than re-running the
 * preference rule: a base plan can carry more than one eligible offer, and "whichever of them
 * comes first" is the same unguaranteed ordering this file refuses to rely on anywhere else. The
 * preference rule is only the fallback, for when that exact offer is gone by the time the user
 * has chosen - and if the whole base plan has gone, this is null and the purchase fails closed
 * rather than quietly moving the user to the other plan.
 */
internal fun ProductDetails.offerForChosenPlan(
    plan: SubscriptionPlan
): ProductDetails.SubscriptionOfferDetails? {
    val forPlan = purchasableOffersFor(plan.period)
    return forPlan.firstOrNull { it.offerId == plan.offerId } ?: forPlan.bestOffer()
}

private fun List<ProductDetails.SubscriptionOfferDetails>.bestOffer():
    ProductDetails.SubscriptionOfferDetails? =
    firstOrNull { it.freeTrialPhase() != null } ?: firstOrNull()

/** The plans this account can buy, or null when there is nothing purchasable at all. */
internal fun ProductDetails.toSubscriptionPlans(): SubscriptionPlans? {
    val plans = BillingPeriod.entries.mapNotNull { period ->
        val offer = purchasableOfferFor(period) ?: return@mapNotNull null
        val recurring = offer.recurringPhase() ?: return@mapNotNull null
        SubscriptionPlan(
            period = period,
            offerId = offer.offerId,
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
