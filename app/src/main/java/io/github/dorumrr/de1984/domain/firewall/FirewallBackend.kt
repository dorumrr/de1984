package io.github.dorumrr.de1984.domain.firewall

import android.content.Context
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.Constants

interface FirewallBackend {

    suspend fun start(): Result<Unit>

    suspend fun stop(): Result<Unit>

    suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit>

    fun isActive(): Boolean

    fun getType(): FirewallBackendType

    suspend fun checkAvailability(): Result<Unit>

    fun supportsGranularControl(): Boolean
}

enum class FirewallBackendType {
    VPN,

    IPTABLES,

    CONNECTIVITY_MANAGER,

    NETWORK_POLICY_MANAGER;

    fun displayName(context: Context): String = context.getString(
        when (this) {
            VPN -> R.string.backend_vpn_name
            IPTABLES -> R.string.backend_iptables_name
            CONNECTIVITY_MANAGER -> R.string.backend_connectivity_manager_name
            NETWORK_POLICY_MANAGER -> R.string.backend_network_policy_manager_name
        }
    )

    /**
     * Whether this backend can act on a uid outside the app range - "Android System" at 1000,
     * the phone/radio uid, and the rest of the platform.
     *
     * Neither Shizuku backend can. `cmd connectivity set-package-networking-enabled` answers
     * "Can't set package firewall rule for system app <pkg> with appId <n>" and
     * NetworkPolicyManager throws "cannot apply policy to UID <uid>". Each refusal costs a Shizuku
     * process and enough of them kill the daemon, which is why
     * [Constants.Firewall.isFirewallableAppUid] stops those commands from ever being sent - see
     * issue #93. That guard is correct, but it is silent: the rule is still written and the switch
     * still reads "Blocked" while the app keeps its network.
     *
     * iptables matches on the raw uid and never asks the platform's permission. The VPN backend
     * captures every packet the tunnel carries, including the platform's, and names apps by
     * package rather than by uid.
     */
    fun canBlockSystemUids(): Boolean = when (this) {
        IPTABLES, VPN -> true
        CONNECTIVITY_MANAGER, NETWORK_POLICY_MANAGER -> false
    }

    /**
     * Whether this backend's verdict lands on a UID rather than on a package.
     *
     * It decides who is affected by the shared-uid exemption. While Settings > "Allow Firewall
     * Critical Packages" is OFF - the default - a UID-based backend skips the WHOLE uid as soon as
     * any package in it is whitelisted or declares a VpnService, so an ordinary app that happens to
     * share that uid cannot be blocked either: IptablesFirewallBackend.isUidExempted and
     * NetworkPolicyManagerFirewallBackend.isUidExempted.
     *
     * ConnectivityManager and VPN name a package, so the exemption there removes only the protected
     * package itself and its neighbours are still commanded. (They have the opposite problem - the
     * platform applies a per-package command to the whole uid - which is a separate defect and not
     * what this answers.)
     */
    fun blocksByUid(): Boolean = when (this) {
        IPTABLES, NETWORK_POLICY_MANAGER -> true
        CONNECTIVITY_MANAGER, VPN -> false
    }
}

/**
 * Why the block a row displays is not the block in force. Each one needs different words on screen,
 * and [fixableHere] decides whether the switches stay usable.
 */
enum class UnblockableReason(
    /**
     * Whether the user can put this right from this screen. Only the Block All case can be: setting
     * an explicit rule makes the backend honour it. The other three are refusals no switch reaches,
     * so their controls are dead.
     */
    val fixableHere: Boolean,
) {
    /**
     * HiddenApiHelper.UID_UNKNOWN. De1984 could not work out who this package is, and no backend
     * can act on it: iptables refuses "--uid-owner -1" (IptablesFirewallBackend.isUidExempted),
     * both Shizuku backends fail isFirewallableAppUid, and the VPN backend - which names apps by
     * package rather than by uid - resolves that name in ITS OWN user, so a package that exists
     * only in another profile raises NameNotFoundException at addAllowedApplication.
     */
    UNKNOWN_UID(fixableHere = false),

    /**
     * A uid outside the app range. The platform refuses it to the Shizuku backends - see
     * [FirewallBackendType.canBlockSystemUids].
     */
    PLATFORM_REFUSES_SYSTEM_UID(fixableHere = false),

    /**
     * A UID-based backend skips the whole uid because a package in it is protected - see
     * [FirewallBackendType.blocksByUid].
     */
    SHARED_WITH_PROTECTED_PACKAGE(fixableHere = false),

    /**
     * The row belongs to another profile and the running backend names packages rather than uids,
     * so it resolves that name in the user IT runs in and never finds this one.
     *
     * FirewallVpnService counts the NameNotFoundException from addAllowedApplication as a failure
     * and leaves the app outside the tunnel; ConnectivityManagerFirewallBackend says the same in
     * its own words - "cmd connectivity set-package-networking-enabled operates on package names in
     * the current user context. Work profile apps may not be blocked correctly."
     */
    OTHER_PROFILE_UNREACHABLE(fixableHere = false),

    /**
     * "Allow Firewall Critical Packages" is ON, so the uid is no longer exempt - but under the
     * Block All policy every backend still ALLOWS a package in such a uid that has no rule of its
     * own, for system stability. The screen shows Block All as "Blocked", so without this the row
     * claims a block that is not applied.
     */
    NO_RULE_IN_PROTECTED_UID(fixableHere = true),

    /**
     * A neighbour in the same uid has a rule, and the uid backends stop applying the Block All
     * default to the WHOLE uid the moment any rule exists - `rulesByUid =
     * rules.filter { it.enabled }.groupBy { it.uid }` then `rulesForUid.any { }` in
     * IptablesFirewallBackend.applyRules and its NetworkPolicyManager sibling.
     *
     * So this rule-less row is really governed by the UNION of its neighbours' rules - blocked on
     * the networks they name, open on the rest - while its painting shows the full Block All
     * default. The one row wearing this reason is not zeroed like the others: [asEnforcedBy]
     * substitutes [FirewallBackendType.enforcedVector], so a partial neighbour rule shows as a
     * partial block. Giving this package a rule of its own fixes it, which is why this is
     * [fixableHere]. Raised only when the union falls short of the painting; a uid whose rules
     * block everything anyway needs no correction.
     */
    SIBLING_RULE_OVERRIDES_DEFAULT(fixableHere = true),
}

/**
 * Everything a backend consults before deciding whether a rule is worth sending, gathered once per
 * pass so the screen can reach the same verdict without re-scanning the package list per row.
 *
 * [criticalOrVpnUids] is used two opposite ways, exactly as IptablesFirewallBackend.applyRules
 * describes: with "allow critical" OFF those uids are exempt from being written at all, with it ON
 * they get no rule of their own but are allowed by default. Same membership, opposite use - which
 * is why this carries the membership and the flag rather than a pre-baked set.
 */
data class BlockingContext(
    /** Uids holding a whitelisted package or one that declares a VpnService. */
    val criticalOrVpnUids: Set<Int> = emptySet(),
    /**
     * Per uid, the union of what its ENABLED rules block - keyed presence doubles as "this uid has
     * a rule at all".
     *
     * A set of uids cannot answer the screen's question. The uid backends branch on rule PRESENCE
     * (any rule at all withdraws the Block All default from the whole uid) and then enforce the
     * rules' UNION - iptables per current network plus a LAN pass, NetworkPolicyManager
     * all-or-nothing. So a rule-less package in a ruled uid is not simply "allowed": it is blocked
     * on exactly the networks its neighbours' rules name. Carrying the union lets the screen paint
     * that truth instead of guessing a yes/no.
     */
    val uidRules: Map<Int, UidRuleAggregate> = emptyMap(),
    /** Settings > "Allow Firewall Critical Packages". */
    val allowCritical: Boolean = false,
    /** The default policy is Block All. */
    val blockAllDefault: Boolean = false,
    /** The user De1984 itself runs in. A package-naming backend can reach only this one. */
    val ownUserId: Int = 0,
)

/** The union of every ENABLED rule in one uid - what a uid-deciding backend enforces on it. */
data class UidRuleAggregate(
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,
    val roamingBlocked: Boolean = false,
    val lanBlocked: Boolean = false,
    val backgroundBlocked: Boolean = false,
) {
    val blocksInternet: Boolean get() = wifiBlocked || mobileBlocked || roamingBlocked
}

/**
 * What THIS backend enforces on a ruled uid, given the union of its rules.
 *
 * iptables honours the rules per network (IptablesFirewallBackend.applyRules tests
 * `isBlockedOn(networkType)`, and a separate pass blocks LAN for the uid when any rule names it).
 * NetworkPolicyManager is all-or-nothing: it tests `isBlockedOnAnyNetwork()`, so one blocked
 * network takes every network, and it cannot touch LAN at all. Only meaningful for backends whose
 * verdict lands on a uid; the package-naming two never consult it.
 */
fun FirewallBackendType.enforcedVector(agg: UidRuleAggregate): UidRuleAggregate = when (this) {
    FirewallBackendType.IPTABLES -> agg
    FirewallBackendType.NETWORK_POLICY_MANAGER -> UidRuleAggregate(
        wifiBlocked = agg.blocksInternet,
        mobileBlocked = agg.blocksInternet,
        roamingBlocked = agg.blocksInternet,
        lanBlocked = false,
        backgroundBlocked = agg.backgroundBlocked,
    )
    FirewallBackendType.CONNECTIVITY_MANAGER, FirewallBackendType.VPN -> agg
}

/**
 * Does [vector] block everything the Block All default paints as blocked on this backend?
 *
 * The rule-less row under Block All paints WiFi, Mobile, Roaming and LAN as blocked. When the
 * uid's rules enforce all of that anyway, the painting is already the truth and no reason is
 * owed. LAN counts only on iptables - it is the one backend that can block LAN, and the LAN
 * switch is only shown there.
 */
private fun FirewallBackendType.coversBlockAllPaint(vector: UidRuleAggregate): Boolean =
    vector.wifiBlocked && vector.mobileBlocked && vector.roamingBlocked &&
        (vector.lanBlocked || this != FirewallBackendType.IPTABLES)

/**
 * Why the RUNNING backend will not act on [uid], or null when it will.
 *
 * Every reason ends the same way: a rule is written, no command is sent, and the app keeps its
 * network. The screen takes both its greying and its wording from this one answer, so a row can
 * never be dimmed for one reason and explained with another.
 *
 * A null backend - the firewall is stopped - deliberately answers null. We do not yet know which
 * backend will be chosen, and a root user arranging rules before starting the firewall must not be
 * told that a switch which will work does nothing.
 *
 * [context] carries the two facts the backends compute per pass and the two settings that decide
 * how they are used; the package supplies its own rule state, which the per-package backends need.
 */
fun FirewallBackendType?.unblockableReason(
    pkg: NetworkPackage,
    context: BlockingContext,
): UnblockableReason? {
    val backend = this ?: return null
    val uid = pkg.uid

    if (uid < 0) return UnblockableReason.UNKNOWN_UID

    // A package-naming backend can only reach the user it runs in. Checked before the uid-range
    // test, because a work-profile row fails this whatever its appId is.
    if (!backend.blocksByUid() && pkg.userId != context.ownUserId) {
        return UnblockableReason.OTHER_PROFILE_UNREACHABLE
    }

    if (!backend.canBlockSystemUids() && !Constants.Firewall.isFirewallableAppUid(uid)) {
        return UnblockableReason.PLATFORM_REFUSES_SYSTEM_UID
    }

    val inProtectedUid = uid in context.criticalOrVpnUids

    if (inProtectedUid && !context.allowCritical) {
        // The whole uid is skipped, but only by a backend whose verdict lands on a uid.
        // ConnectivityManager and VPN name the package, so a neighbour is still commanded.
        return if (backend.blocksByUid()) UnblockableReason.SHARED_WITH_PROTECTED_PACKAGE else null
    }

    // Past here the only thing that can go unenforced is the Block All DEFAULT. An explicit rule is
    // honoured everywhere - on a protected uid with "allow critical" ON as much as on an ordinary
    // one - so a row displaying its own rule needs no correction and gets none.
    if (!context.blockAllDefault) return null

    // A package that HAS a rule is painted from that rule, never from the default, so there is
    // nothing left to correct even when the rule allows everything. Without this the sheet told a
    // user who had just allowed an app again to "turn on the switch for this app".
    //
    // This is also what closes the optimistic gap: right after a tap, updatePackageInList marks the
    // row hasExplicitRule with flags mirroring the insert, while [BlockingContext.uidRules] is one
    // repository emission behind. The row answers for itself and does not wait for the map.
    if (pkg.hasExplicitRule) return null

    // No rule of its own. On a uid-deciding backend, one neighbour's rule withdraws the Block All
    // default from the WHOLE uid and the rules' union takes over - so this row is really blocked on
    // exactly the networks [enforcedVector] says, not on the four its default painting shows. Only
    // when the union covers the full painting is the display already the truth.
    if (backend.blocksByUid()) {
        val aggregate = context.uidRules[uid]
        if (aggregate != null) {
            return if (backend.coversBlockAllPaint(backend.enforcedVector(aggregate))) null
            else UnblockableReason.SIBLING_RULE_OVERRIDES_DEFAULT
        }
    }

    // No rule anywhere in reach: the Block All default is enforced as painted, except on a
    // protected uid, where every backend withholds it for system stability.
    return if (inProtectedUid) UnblockableReason.NO_RULE_IN_PROTECTED_UID else null
}

/**
 * The block this row DISPLAYS is not the block in force, whatever the reason.
 *
 * Use for anything that reports state - icons, switch position, the Blocked/Allowed filter. Never
 * for deciding whether a control is usable: [blockingRefused] answers that.
 */
fun FirewallBackendType?.blockNotEnforced(
    pkg: NetworkPackage,
    context: BlockingContext,
): Boolean = unblockableReason(pkg, context) != null

/**
 * This package with its blocking flags as ENFORCED rather than as saved.
 *
 * The database keeps what the user asked for. When the running backend will not act on the uid,
 * that is not what the network is doing, and EVERYTHING downstream has to see the enforced version:
 * not only the icons and switches, but anything that derives an action from them. A quick toggle
 * computing `willBlock = !isCurrentlyBlocked` from the raw flags did the exact opposite of what its
 * own row was showing.
 *
 * Applied once where the screen's package list is built, so no consumer has to remember to mask.
 * In the zeroing branch backgroundBlocked is deliberately untouched: it only ever means anything
 * inside a rule, and a package in that state has none that is applied. The sibling branch DOES set
 * it - there a neighbour's rule is applied to this uid, background included.
 */
fun NetworkPackage.asEnforcedBy(
    backend: FirewallBackendType?,
    context: BlockingContext,
): NetworkPackage {
    val reason = backend.unblockableReason(this, context) ?: return this

    // A sibling-ruled row is not "no block in force" but "a DIFFERENT block in force": the union
    // of its neighbours' rules, shaped by what this backend can enforce. Zeroing it painted a row
    // as fully Allowed while iptables was still dropping its LAN - or its mobile - traffic.
    if (reason == UnblockableReason.SIBLING_RULE_OVERRIDES_DEFAULT && backend != null) {
        val aggregate = context.uidRules[uid]
        if (aggregate != null) {
            val vector = backend.enforcedVector(aggregate)
            return copy(
                isNetworkBlocked = vector.wifiBlocked || vector.mobileBlocked,
                wifiBlocked = vector.wifiBlocked,
                mobileBlocked = vector.mobileBlocked,
                roamingBlocked = vector.roamingBlocked,
                lanBlocked = vector.lanBlocked,
                backgroundBlocked = vector.backgroundBlocked,
            )
        }
    }

    return copy(
        isNetworkBlocked = false,
        wifiBlocked = false,
        mobileBlocked = false,
        roamingBlocked = false,
        lanBlocked = false,
    )
}

/**
 * Nothing on this screen can make the block happen, so the controls must be dead.
 *
 * Narrower than [blockNotEnforced] on purpose. Under Block All in a protected uid the block is not
 * in force either, but a switch fixes it - and that is exactly what the message tells the user to
 * do, so the switch has to work.
 */
fun FirewallBackendType?.blockingRefused(
    pkg: NetworkPackage,
    context: BlockingContext,
): Boolean = unblockableReason(pkg, context)?.fixableHere == false

enum class FirewallMode {
    AUTO,

    VPN,

    IPTABLES,

    CONNECTIVITY_MANAGER,

    NETWORK_POLICY_MANAGER;

    companion object {
        fun fromString(value: String?): FirewallMode? {
            return when (value?.lowercase()) {
                "auto" -> AUTO
                "vpn" -> VPN
                "iptables" -> IPTABLES
                "connectivity_manager" -> CONNECTIVITY_MANAGER
                "network_policy_manager" -> NETWORK_POLICY_MANAGER
                else -> null
            }
        }

        fun FirewallMode.toStorageString(): String {
            return when (this) {
                AUTO -> "auto"
                VPN -> "vpn"
                IPTABLES -> "iptables"
                CONNECTIVITY_MANAGER -> "connectivity_manager"
                NETWORK_POLICY_MANAGER -> "network_policy_manager"
            }
        }
    }
}

