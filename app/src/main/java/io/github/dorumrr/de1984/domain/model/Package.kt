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
    val hasNetworkAccess: Boolean = false,
    val criticality: PackageCriticality? = null,
    val category: String? = null,
    val affects: List<String> = emptyList()
)

enum class PackageType {
    SYSTEM,
    USER
}

enum class PackageCriticality {
    ESSENTIAL,  // Red - will brick device
    IMPORTANT,  // Orange - will lose major features
    OPTIONAL,   // Yellow - may lose some features
    BLOATWARE,  // Green - safe to remove
    UNKNOWN     // Gray - no data available
}
