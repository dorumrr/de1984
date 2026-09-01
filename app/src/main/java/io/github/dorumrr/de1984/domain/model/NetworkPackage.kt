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
    val isWorkProfile: Boolean = false,
    val isCloneProfile: Boolean = false
) {
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
