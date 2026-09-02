package io.github.dorumrr.de1984.domain.model

data class NetworkPackage(
    val packageName: String,
    val userId: Int = 0,
    val uid: Int = 0,
    val name: String,
    val icon: String,
    val isEnabled: Boolean,
    val type: PackageType,
    val isNetworkBlocked: Boolean = false,
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,
    val roamingBlocked: Boolean = false,
    /**
     * Roaming BEFORE GetNetworkPackagesUseCase derives it - the rule's own column when the package
     * has a rule, the default painting when it does not.
     *
     * Deliberately not called "stored": for a rule-less row there is nothing stored, and the mapper
     * copies what the scan painted.
     *
     * `roamingBlocked` above is forced true whenever mobile is blocked, so once derived there is no
     * way back to what the user chose. Three defects came from that: a batch guard reading the
     * derived value and skipping the write, so the choice vanished the moment Mobile was unblocked;
     * a batch unblock inserting allow-all rules; and an optimistic row that showed Roaming blocked
     * after a Mobile unblock and then flipped when the real value arrived.
     *
     * Masked by asEnforcedBy in ONE branch only, and the asymmetry is deliberate. The ZEROING
     * branch clears it, so a row displaying nothing blocked cannot report "roaming blocked" through
     * a side channel - a guard that saw it wrote an allow-all rule over the Block All default. The
     * SIBLING branch leaves it alone: every other flag there is recoverable through `savedRule` and
     * this one is not, so substituting a neighbour's value destroyed the row's own column and made
     * "Block Roaming" skip the write entirely. See asEnforcedBy for the same note at the code.
     */
    val roamingBlockedUnderived: Boolean = false,
    val backgroundBlocked: Boolean = false,
    val lanBlocked: Boolean = false,
    val networkPermissions: List<String> = emptyList(),
    val versionName: String? = null,
    val versionCode: Long? = null,
    val installTime: Long? = null,
    val updateTime: Long? = null,
    val isSystemCritical: Boolean = false,
    val isVpnApp: Boolean = false,
    /** See PackageEntity.hasExplicitRule. Distinguishes a real rule from the default policy. */
    val hasExplicitRule: Boolean = false,
    /**
     * The values this row carried BEFORE its blocking flags were replaced by what the running
     * backend actually enforces - see `asEnforcedBy`. Null on an untouched row.
     *
     * The flags above answer "what is happening to this app's traffic", which is what icons, the
     * Blocked/Allowed filter and the row summary must show. This answers "what did the user ask
     * for", which is what every CONTROL and every WRITE must use: a switch has to move the rule it
     * owns, and a batch operation must not skip a row because a neighbour already blocks it.
     *
     * Keeping both also makes the masking idempotent. Re-running the check on an already-masked row
     * used to compare the substituted flags against themselves, decide they matched, and drop the
     * explanation the row was masked to give.
     */
    val savedRule: UidRuleAggregate? = null,
    /** See PackageEntity.paintedAllowCritical - the settings this row was PAINTED with. */
    val paintedAllowCritical: Boolean =
        io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL,
    val paintedBlockAllDefault: Boolean =
        io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_FIREWALL_POLICY ==
            io.github.dorumrr.de1984.utils.Constants.Settings.POLICY_BLOCK_ALL,
    val isWorkProfile: Boolean = false,
    val isCloneProfile: Boolean = false
) {
    /**
     * This row's OWN saved rule, whether or not the displayed flags have been replaced.
     *
     * Read the row itself when nothing was replaced: right after a tap that is the only fresh copy
     * there is, because the repository flow is an emission behind.
     */
    val ownVector: UidRuleAggregate
        get() = savedRule ?: UidRuleAggregate(
            wifiBlocked = wifiBlocked,
            mobileBlocked = mobileBlocked,
            roamingBlocked = roamingBlocked,
            lanBlocked = lanBlocked,
            backgroundBlocked = backgroundBlocked,
        )

    /**
     * This row with the user's own values back in the blocking flags.
     *
     * For CONTROLS whose mask ADDS blocks - a neighbour in the same uid blocking more than this
     * app's own rule does. A switch has to show the rule it moves: showing the neighbour's value
     * made every tap write a value that was already stored, so the switch sprang back and looked
     * dead. The banner beside it is what explains the neighbour.
     *
     * Not for the masks that REMOVE blocks. There the zeroed display is what the switch must show,
     * because the message next to it asks the user to turn that switch on.
     */
    fun asSaved(): NetworkPackage {
        val saved = savedRule ?: return this
        return copy(
            savedRule = null,
            isNetworkBlocked = saved.wifiBlocked || saved.mobileBlocked,
            wifiBlocked = saved.wifiBlocked,
            mobileBlocked = saved.mobileBlocked,
            roamingBlocked = saved.roamingBlocked,
            lanBlocked = saved.lanBlocked,
            backgroundBlocked = saved.backgroundBlocked,
        )
    }

    val isNetworkAllowed: Boolean
        get() = !isNetworkBlocked

    val isFullyBlocked: Boolean
        get() = wifiBlocked && mobileBlocked

    val isFullyAllowed: Boolean
        get() = !wifiBlocked && !mobileBlocked && !roamingBlocked && !lanBlocked

    val isPartiallyBlocked: Boolean
        get() = !isFullyBlocked && !isFullyAllowed

    val networkState: String
        get() = when {
            isFullyBlocked -> "Blocked"
            isFullyAllowed -> "Allowed"
            wifiBlocked && !mobileBlocked && !roamingBlocked -> "WiFi Blocked"
            !wifiBlocked && mobileBlocked && !roamingBlocked -> "Mobile Blocked"
            !wifiBlocked && !mobileBlocked && roamingBlocked -> "Roaming Blocked"
            else -> "Partial"
        }

    val hasInternetPermission: Boolean
        get() = networkPermissions.contains("android.permission.INTERNET")

    val hasNetworkPermissions: Boolean
        get() = networkPermissions.any { permission ->
            io.github.dorumrr.de1984.utils.Constants.Firewall.NETWORK_PERMISSIONS.contains(permission)
        }

    val id: PackageId get() = PackageId(packageName, userId)
}

enum class NetworkAccessState {
    ALLOWED,
    BLOCKED,
    PARTIAL
}

data class FirewallFilterState(
    val packageType: String = "All",
    val networkState: String? = null,
    val internetOnly: Boolean = true,
    val profileFilter: String = "All"
)
