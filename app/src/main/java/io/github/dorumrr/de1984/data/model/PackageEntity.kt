package io.github.dorumrr.de1984.data.model

import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageCriticality
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.utils.Constants

/**
 * Data layer entity for package information.
 *
 * @property packageName The package name of the app
 * @property userId Android user profile ID (0 = personal, 10+ = work/clone profiles)
 * @property uid Absolute UID: userId * 100000 + appId
 */
data class PackageEntity(
    val packageName: String,
    /** Android user profile ID (0 = personal, 10+ = work/clone profiles) */
    val userId: Int = 0,
    /** Absolute UID: userId * 100000 + appId */
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
    val criticality: PackageCriticality? = null,
    val category: String? = null,
    val affects: List<String> = emptyList(),
    /** True if this package belongs to a work profile (managed profile) */
    val isWorkProfile: Boolean = false,
    /** True if this package belongs to a clone profile */
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
