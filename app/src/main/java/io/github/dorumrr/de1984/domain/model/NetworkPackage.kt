package io.github.dorumrr.de1984.domain.model

/**
 * Domain model for network/firewall package display.
 *
 * @property packageName The package name of the app
 * @property userId Android user profile ID (0 = personal, 10+ = work/clone profiles)
 * @property uid Absolute UID: userId * 100000 + appId
 */
data class NetworkPackage(
    val packageName: String,
    /** Android user profile ID (0 = personal, 10+ = work/clone profiles) */
    val userId: Int = 0,
    /** Absolute UID: userId * 100000 + appId */
    val uid: Int = 0,
    val name: String,
    val icon: String,
    val isEnabled: Boolean,
    val type: PackageType,
    val isNetworkBlocked: Boolean = false,
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,
    val roamingBlocked: Boolean = false,
    val backgroundBlocked: Boolean = false,
    val lanBlocked: Boolean = false,
    val networkPermissions: List<String> = emptyList(),
    val versionName: String? = null,
    val versionCode: Long? = null,
    val installTime: Long? = null,
    val updateTime: Long? = null,
    val isSystemCritical: Boolean = false,
    val isVpnApp: Boolean = false,
    /** True if this package belongs to a work profile (managed profile) */
    val isWorkProfile: Boolean = false,
    /** True if this package belongs to a clone profile */
    val isCloneProfile: Boolean = false
) {
    val isNetworkAllowed: Boolean
        get() = !isNetworkBlocked

    val isFullyBlocked: Boolean
        get() = wifiBlocked && mobileBlocked

    val isFullyAllowed: Boolean
        get() = !wifiBlocked && !mobileBlocked && !roamingBlocked

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

    /** Get the unique identifier for this package */
    val id: PackageId get() = PackageId(packageName, userId)
}

enum class NetworkAccessState {
    ALLOWED,
    BLOCKED,
    PARTIAL
}

data class FirewallFilterState(
    val packageType: String = "All",
    val networkState: String? = null,  // "Allowed" or "Blocked" only
    val internetOnly: Boolean = true,  // Independent permission filter
    val profileFilter: String = "All"  // "All", "Personal", "Work", "Clone"
)
