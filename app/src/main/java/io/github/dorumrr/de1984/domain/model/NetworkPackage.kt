package io.github.dorumrr.de1984.domain.model

data class NetworkPackage(
    val packageName: String,
    val name: String,
    val icon: String,
    val type: PackageType,
    val isNetworkBlocked: Boolean = false,
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,
    val roamingBlocked: Boolean = false,
    val networkPermissions: List<String> = emptyList(),
    val versionName: String? = null,
    val versionCode: Long? = null,
    val installTime: Long? = null,
    val updateTime: Long? = null,
    val isSystemCritical: Boolean = false,
    val isVpnApp: Boolean = false
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
}

enum class NetworkAccessState {
    ALLOWED,
    BLOCKED,
    PARTIAL
}

data class FirewallFilterState(
    val packageType: String = "User",
    val networkState: String? = null,  // "Allowed" or "Blocked" only
    val internetOnly: Boolean = false   // Independent permission filter
)
