package io.github.dorumrr.de1984.domain.firewall

import android.content.Context
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.UidRuleAggregate
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

    /** Whether this backend can act on an app in [userId] while De1984 runs in [ownUserId]. A backend that names packages decides only De1984's own profile. */
    fun reachesUser(userId: Int, ownUserId: Int): Boolean = when (this) {
        IPTABLES, NETWORK_POLICY_MANAGER -> true
        CONNECTIVITY_MANAGER, VPN -> userId == ownUserId
    }

    /** Whether the verdict this backend gives an app in De1984's own profile also reaches that app's copy in every other profile. */
    fun ownProfileVerdictReachesEveryProfile(): Boolean = when (this) {
        CONNECTIVITY_MANAGER -> true
        IPTABLES, NETWORK_POLICY_MANAGER, VPN -> false
    }
}

/**
 * The block every backend applies to a uid it has not exempted: its enabled rules if it has any, else
 * the Block All default, which a protected uid never gets. Android enforces even a per-app command per uid.
 */
fun uidBlockedNow(
    rulesForUid: List<FirewallRule>?,
    blockAllDefault: Boolean,
    protectedUid: Boolean,
    perNetwork: Boolean,
    networkType: NetworkType,
    screenOn: Boolean,
): Boolean {
    val enabled = rulesForUid.orEmpty().filter { it.enabled }
    if (enabled.isEmpty()) return blockAllDefault && !protectedUid
    return enabled.any { rule ->
        (!screenOn && rule.blockWhenBackground) ||
            if (perNetwork) rule.isBlockedOn(networkType) else rule.isBlockedOnAnyNetwork()
    }
}

/**
 * Enabled rules grouped by the uid their app has now, per [installedUids] (user -> package -> uid, empty when
 * unread). A rule counts nowhere only once every profile was read and its app is in none; else it keeps a uid.
 */
fun rulesByCurrentUid(
    rules: List<FirewallRule>,
    installedUids: Map<Int, Map<String, Int>>,
): Map<Int, List<FirewallRule>> {
    val everyProfileRead = installedUids.isNotEmpty() && installedUids.values.none { it.isEmpty() }
    return rules
        .filter { it.enabled }
        .mapNotNull { rule ->
            val listed = installedUids[rule.userId]
            val uid = when {
                listed.isNullOrEmpty() -> rule.uid
                rule.packageName in listed -> listed.getValue(rule.packageName)
                else -> installedUids.values.firstNotNullOfOrNull { it[rule.packageName] }
                    ?.let { Constants.Firewall.uidForUser(rule.userId, it) }
                    ?: if (everyProfileRead) return@mapNotNull null else rule.uid
            }
            uid to rule
        }
        .groupBy({ it.first }, { it.second })
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

    /** Every backend skips the whole uid because a package in it is protected: Android blocks per uid. */
    SHARED_WITH_PROTECTED_PACKAGE(fixableHere = false),

    /**
     * The row belongs to another profile and the running backend names packages rather than uids,
     * so it decides only apps in the profile De1984 runs in, never by this row's own rule.
     *
     * FirewallVpnService and ConnectivityManagerFirewallBackend both build their app list from
     * De1984's own profile only. Under ConnectivityManager this is raised only for an app absent there.
     */
    OTHER_PROFILE_UNREACHABLE(fixableHere = false),

    /**
     * As [OTHER_PROFILE_UNREACHABLE], but the same app is in De1984's profile and the backend's
     * command for it reaches this copy too, so [asEnforcedBy] paints that copy's verdict here.
     */
    OTHER_PROFILE_FOLLOWS_OWN_PROFILE(fixableHere = false),

    /**
     * "Allow Firewall Critical Packages" is ON, so the uid is no longer exempt - but under the
     * Block All policy every backend still ALLOWS a package in such a uid that has no rule of its
     * own, for system stability. The screen shows Block All as "Blocked", so without this the row
     * claims a block that is not applied.
     */
    NO_RULE_IN_PROTECTED_UID(fixableHere = true),

    /**
     * A neighbour in the same uid has a rule, and every backend stops applying the Block All default
     * to the WHOLE uid the moment any rule exists - see [uidBlockedNow].
     *
     * So this rule-less row is really governed by the UNION of its neighbours' rules - blocked on
     * the networks they name, open on the rest - while its painting shows the full Block All
     * default. The one row wearing this reason is not zeroed like the others: [asEnforcedBy]
     * substitutes [FirewallBackendType.enforcedVectorFor], so a partial neighbour rule shows as a
     * partial block. A rule of its own can only add to the union: it fixes a default painting, not
     * a neighbour's block, and the switches stay [fixableHere] because they set this app's own rule.
     * Raised only when the union differs from the painting.
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
     * A set of uids cannot answer the screen's question. Every backend branches on rule PRESENCE
     * (any rule at all withdraws the Block All default from the whole uid) and then enforces the
     * rules' UNION - iptables and VPN per current network, iptables plus a LAN pass,
     * NetworkPolicyManager and ConnectivityManager all-or-nothing. So a rule-less package in a ruled
     * uid is not simply "allowed": it is blocked on exactly the networks its neighbours' rules name.
     * Carrying the union lets the screen paint that truth instead of guessing a yes/no.
     */
    val uidRules: Map<Int, Map<String, UidRuleAggregate>> = emptyMap(),
    /** Settings > "Allow Firewall Critical Packages". */
    val allowCritical: Boolean = false,
    /** The default policy is Block All. */
    val blockAllDefault: Boolean = false,
    /** The user De1984 itself runs in. A package-naming backend decides only this one. */
    val ownUserId: Int = 0,
    /** The unmasked rows of [ownUserId], by package name: the rows whose verdict another profile's copy may follow. */
    val ownProfilePackages: Map<String, NetworkPackage> = emptyMap(),
)

/**
 * What THIS backend enforces on a ruled uid, given the union of its rules.
 *
 * iptables and VPN honour the rules per network ([uidBlockedNow] with perNetwork), and only iptables
 * blocks LAN for the uid in a separate pass when any rule names it. NetworkPolicyManager and
 * ConnectivityManager are all-or-nothing: one blocked network takes every network, and neither has a
 * LAN block of its own.
 */
private fun FirewallBackendType.enforcedVector(agg: UidRuleAggregate): UidRuleAggregate = when (this) {
    FirewallBackendType.IPTABLES -> agg
    FirewallBackendType.VPN -> agg.copy(lanBlocked = false)
    FirewallBackendType.NETWORK_POLICY_MANAGER, FirewallBackendType.CONNECTIVITY_MANAGER -> UidRuleAggregate(
        wifiBlocked = agg.blocksInternet,
        mobileBlocked = agg.blocksInternet,
        roamingBlocked = agg.blocksInternet,
        lanBlocked = false,
        backgroundBlocked = agg.backgroundBlocked,
    )
}

/**
 * What the running backend really enforces on THIS row, or null when only its own rule decides.
 *
 * Null means one of: no rule exists anywhere in the uid, no OTHER package holds one, or this row
 * has a rule and the others change nothing this backend enforces. The last two are deliberate - no
 * neighbour then causes what the row gets wrong, and an all-or-nothing backend flattening the row's
 * own rule is a different defect that needs different words.
 *
 * Every rule in the uid goes into the union, INCLUDING a neighbour's rule that blocks nothing.
 * iptables applies the Block All default only while `rulesForUid` is EMPTY, so an allow-everything
 * neighbour still withdraws that default from the whole uid: skipping it left a rule-less row
 * painted Blocked, and sitting under the Blocked chip, while its traffic flowed.
 *
 * This row's own contribution comes from the ROW, not from the map. Right after a tap
 * [BlockingContext.uidRules] is one repository emission behind, and a union can only add - so a map
 * that still held this row's own pre-tap block could never represent the user REMOVING it, and an
 * unblock snapped straight back to Blocked.
 */
fun FirewallBackendType.enforcedVectorFor(
    pkg: NetworkPackage,
    context: BlockingContext,
): UidRuleAggregate? {
    val byPackage = context.uidRules[pkg.uid] ?: return null
    val own = if (pkg.hasExplicitRule) pkg.ownVector else UidRuleAggregate()
    var union = own
    var hasSibling = false
    for ((owner, vector) in byPackage) {
        if (owner == pkg.packageName) continue
        hasSibling = true
        union = union union vector
    }
    if (!hasSibling) return null
    val enforced = enforcedVector(union)
    return if (pkg.hasExplicitRule && enforced.effective() == enforcedVector(own).effective()) null else enforced
}

/** A screen-off block adds nothing to a uid already blocked on every network. */
private fun UidRuleAggregate.effective(): UidRuleAggregate =
    if (wifiBlocked && mobileBlocked && roamingBlocked) copy(backgroundBlocked = false) else this

/**
 * Does this row already show [vector], so no correction is owed?
 *
 * Compares against the row's OWN values, never against a painting guessed from the default policy.
 * The data source paints a critical or VPN package all-allowed whatever the policy says, so a
 * guess disagreed with the screen in both directions - a false warning on a quiet row, and silence
 * on one the backend was really dropping.
 *
 * LAN counts only on iptables, the one backend that can block it. The screen-off value counts only
 * while its switch is on screen, which FirewallFragmentViews.bindScreenOffToggle decides.
 */
private fun FirewallBackendType.displayMatches(
    pkg: NetworkPackage,
    vector: UidRuleAggregate,
): Boolean {
    val shown = pkg.ownVector
    val iptables = this == FirewallBackendType.IPTABLES
    // Must match bindScreenOffToggle's blockedEverywhere for the sheet each backend gets.
    val backgroundVisible = when (this) {
        FirewallBackendType.IPTABLES, FirewallBackendType.VPN -> !(shown.wifiBlocked && shown.mobileBlocked)
        FirewallBackendType.NETWORK_POLICY_MANAGER, FirewallBackendType.CONNECTIVITY_MANAGER ->
            !(shown.wifiBlocked || shown.mobileBlocked || shown.roamingBlocked)
    }
    return shown.wifiBlocked == vector.wifiBlocked &&
        shown.mobileBlocked == vector.mobileBlocked &&
        shown.roamingBlocked == vector.roamingBlocked &&
        (!backgroundVisible || shown.backgroundBlocked == vector.backgroundBlocked) &&
        (!iptables || shown.lanBlocked == vector.lanBlocked)
}

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
 * how they are used; the package supplies its own rule state.
 */
fun FirewallBackendType?.unblockableReason(
    pkg: NetworkPackage,
    context: BlockingContext,
): UnblockableReason? {
    val backend = this ?: return null
    val uid = pkg.uid

    if (uid < 0) return UnblockableReason.UNKNOWN_UID

    // A package-naming backend decides only the user it runs in. Checked before the uid-range
    // test, because a work-profile row fails this whatever its appId is.
    if (!backend.reachesUser(pkg.userId, context.ownUserId)) {
        // Only a copy the backend can act on is followed; a system, unknown or protected twin is blocked in no profile.
        val ownCopy = context.ownProfilePackages[pkg.packageName]
        val followsOwnCopy = ownCopy != null && backend.ownProfileVerdictReachesEveryProfile() &&
            backend.unblockableReason(ownCopy, context).let { it == null || it.fixableHere }
        return if (followsOwnCopy) UnblockableReason.OTHER_PROFILE_FOLLOWS_OWN_PROFILE
        else UnblockableReason.OTHER_PROFILE_UNREACHABLE
    }

    if (!backend.canBlockSystemUids() && !Constants.Firewall.isFirewallableAppUid(uid)) {
        return UnblockableReason.PLATFORM_REFUSES_SYSTEM_UID
    }

    val inProtectedUid = uid in context.criticalOrVpnUids

    if (inProtectedUid && !context.allowCritical) return UnblockableReason.SHARED_WITH_PROTECTED_PACKAGE

    // Before the Block All test, and not skipped for a row with its own rule: a neighbour's rule
    // governs this row either way. Raised only where the union differs from what the row paints.
    val enforced = backend.enforcedVectorFor(pkg, context)
    if (enforced != null) {
        return if (backend.displayMatches(pkg, enforced)) null
        else UnblockableReason.SIBLING_RULE_OVERRIDES_DEFAULT
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
        val vector = backend.enforcedVectorFor(this, context)
        if (vector != null) {
            return copy(
                savedRule = ownVector,
                isNetworkBlocked = vector.wifiBlocked || vector.mobileBlocked,
                wifiBlocked = vector.wifiBlocked,
                mobileBlocked = vector.mobileBlocked,
                roamingBlocked = vector.roamingBlocked,
                // roamingBlockedUnderived is deliberately NOT substituted here. Every other flag in
                // this branch is recoverable through savedRule; this one has no backup, so masking
                // it replaced the row's OWN roaming column with a neighbour's - and the batch guard,
                // the only reader that sees a masked row, then skipped writing the column at all.
                // The zeroing branch below DOES clear it, because there the row displays nothing
                // blocked and a guard must not think otherwise.
                lanBlocked = vector.lanBlocked,
                backgroundBlocked = vector.backgroundBlocked,
            )
        }
    }

    // savedRule stays null: this row's own rule is never enforced, and its dead switches show the copy's verdict.
    if (reason == UnblockableReason.OTHER_PROFILE_FOLLOWS_OWN_PROFILE) {
        val enforced = context.ownProfilePackages[packageName]?.asEnforcedBy(backend, context)
        if (enforced != null) {
            return copy(
                savedRule = null,
                isNetworkBlocked = enforced.wifiBlocked || enforced.mobileBlocked,
                wifiBlocked = enforced.wifiBlocked,
                mobileBlocked = enforced.mobileBlocked,
                roamingBlocked = enforced.roamingBlocked,
                roamingBlockedUnderived = enforced.roamingBlockedUnderived,
                lanBlocked = enforced.lanBlocked,
                backgroundBlocked = enforced.backgroundBlocked,
            )
        }
    }

    // No savedRule here on purpose. This branch REMOVES blocks, and the row's pre-mask painting is
    // not something any control should show - the message beside these switches asks the user to
    // turn one ON. Setting it only in the sibling branch is what lets NetworkPackage.asSaved() mean
    // one unambiguous thing: give me back the values a neighbour's block hid.
    return copy(
        savedRule = null,
        isNetworkBlocked = false,
        wifiBlocked = false,
        mobileBlocked = false,
        roamingBlocked = false,
        roamingBlockedUnderived = false,
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

