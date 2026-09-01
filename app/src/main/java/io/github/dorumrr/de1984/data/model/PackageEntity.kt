package io.github.dorumrr.de1984.data.model

import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageCriticality
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.utils.Constants

data class PackageEntity(
    val packageName: String,
    val userId: Int = 0,
    val uid: Int = 0,
    val name: String,
    val icon: String,
    val isEnabled: Boolean,
    val type: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val installTime: Long? = null,
    val updateTime: Long? = null,
    val permissions: List<String> = emptyList(),
    val hasNetworkAccess: Boolean = false,
    val isNetworkBlocked: Boolean = false,
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,
    val roamingBlocked: Boolean = false,
    val backgroundBlocked: Boolean = false,
    val lanBlocked: Boolean = false,
    val isVpnApp: Boolean = false,
    /**
     * An ENABLED rule row exists for this package, as opposed to the blocking flags above being
     * the default policy showing through. `rule?.enabled == true`, matching what the backends see -
     * both uid backends build `rules.filter { it.enabled }.groupBy { it.uid }` before testing
     * presence, so a disabled rule does not count for them and must not count here. (A disabled
     * rule also paints from the default branch, not from its own flags.)
     */
    val hasExplicitRule: Boolean = false,
    val criticality: PackageCriticality? = null,
    val category: String? = null,
    val affects: List<String> = emptyList(),
    val isWorkProfile: Boolean = false,
    val isCloneProfile: Boolean = false
)

fun PackageEntity.toDomain(): Package {
    return Package(
        packageName = packageName,
        userId = userId,
        uid = uid,
        name = name,
        icon = icon,
        isEnabled = isEnabled,
        type = when (type) {
            Constants.Packages.TYPE_SYSTEM -> PackageType.SYSTEM
            Constants.Packages.TYPE_USER -> PackageType.USER
            else -> PackageType.USER
        },
        versionName = versionName,
        versionCode = versionCode,
        installTime = installTime,
        updateTime = updateTime,
        permissions = permissions,
        hasNetworkAccess = hasNetworkAccess,
        criticality = criticality,
        category = category,
        affects = affects,
        isWorkProfile = isWorkProfile,
        isCloneProfile = isCloneProfile
    )
}

fun PackageEntity.toNetworkDomain(): NetworkPackage {
    return NetworkPackage(
        packageName = packageName,
        userId = userId,
        uid = uid,
        name = name,
        icon = icon,
        isEnabled = isEnabled,
        type = when (type) {
            Constants.Packages.TYPE_SYSTEM -> PackageType.SYSTEM
            Constants.Packages.TYPE_USER -> PackageType.USER
            else -> PackageType.USER
        },
        isNetworkBlocked = isNetworkBlocked,
        wifiBlocked = wifiBlocked,
        mobileBlocked = mobileBlocked,
        roamingBlocked = roamingBlocked,
        backgroundBlocked = backgroundBlocked,
        lanBlocked = lanBlocked,
        networkPermissions = permissions.filter {
            Constants.Firewall.NETWORK_PERMISSIONS.contains(it)
        },
        versionName = versionName,
        versionCode = versionCode,
        installTime = installTime,
        updateTime = updateTime,
        isVpnApp = isVpnApp,
        isWorkProfile = isWorkProfile,
        isCloneProfile = isCloneProfile
    )
}

fun Package.toEntity(): PackageEntity {
    return PackageEntity(
        packageName = packageName,
        userId = userId,
        uid = uid,
        name = name,
        icon = icon,
        isEnabled = isEnabled,
        type = when (type) {
            PackageType.SYSTEM -> Constants.Packages.TYPE_SYSTEM
            PackageType.USER -> Constants.Packages.TYPE_USER
        },
        versionName = versionName,
        versionCode = versionCode,
        installTime = installTime,
        updateTime = updateTime,
        permissions = permissions,
        hasNetworkAccess = hasNetworkAccess,
        criticality = criticality,
        category = category,
        affects = affects,
        isWorkProfile = isWorkProfile,
        isCloneProfile = isCloneProfile
    )
}
