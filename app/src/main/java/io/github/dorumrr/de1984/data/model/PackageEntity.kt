package io.github.dorumrr.de1984.data.model

import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageCriticality
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.utils.Constants

data class PackageEntity(
    val packageName: String,
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
    val isVpnApp: Boolean = false,
    val criticality: PackageCriticality? = null,
    val category: String? = null,
    val affects: List<String> = emptyList()
)

fun PackageEntity.toDomain(): Package {
    return Package(
        packageName = packageName,
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
        affects = affects
    )
}

fun Package.toEntity(): PackageEntity {
    return PackageEntity(
        packageName = packageName,
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
        affects = affects
    )
}
