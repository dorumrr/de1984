package io.github.dorumrr.de1984.domain.model

/**
 * What one firewall rule - or the union of several - blocks.
 *
 * Lives in `domain.model` rather than next to the backends because [NetworkPackage] carries one:
 * `domain.firewall` already depends on `domain.model`, and the dependency has to keep running that
 * one way.
 */
data class UidRuleAggregate(
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,
    val roamingBlocked: Boolean = false,
    val lanBlocked: Boolean = false,
    val backgroundBlocked: Boolean = false,
) {
    /** NetworkPolicyManager tests exactly this - one blocked network takes every network. */
    val blocksInternet: Boolean get() = wifiBlocked || mobileBlocked || roamingBlocked

    infix fun union(other: UidRuleAggregate): UidRuleAggregate = UidRuleAggregate(
        wifiBlocked = wifiBlocked || other.wifiBlocked,
        mobileBlocked = mobileBlocked || other.mobileBlocked,
        roamingBlocked = roamingBlocked || other.roamingBlocked,
        lanBlocked = lanBlocked || other.lanBlocked,
        backgroundBlocked = backgroundBlocked || other.backgroundBlocked,
    )
}
