package io.github.dorumrr.de1984.domain.model

data class Package(
    val packageName: String,
    val name: String,
    val icon: String,
    val isEnabled: Boolean,
    val type: PackageType,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val installTime: Long? = null,
    val updateTime: Long? = null,
    val permissions: List<String> = emptyList(),
    val hasNetworkAccess: Boolean = false
)

enum class PackageType {
    SYSTEM,
    USER
}
