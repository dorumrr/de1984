package io.github.dorumrr.de1984.domain.model

/**
 * Unique identifier for a package across user profiles.
 * Used for selection tracking in multi-user environments.
 */
data class PackageId(
    val packageName: String,
    val userId: Int = 0
)

/**
 * Domain model for package management display.
 *
 * @property packageName The package name of the app
 * @property userId Android user profile ID (0 = personal, 10+ = work/clone profiles)
 * @property uid Absolute UID: userId * 100000 + appId
 */
data class Package(
    val packageName: String,
    /** Android user profile ID (0 = personal, 10+ = work/clone profiles) */
    val userId: Int = 0,
    /** Absolute UID: userId * 100000 + appId */
    val uid: Int = 0,
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
    val affects: List<String> = emptyList(),
    /** True if this package belongs to a work profile (managed profile) */
    val isWorkProfile: Boolean = false,
    /** True if this package belongs to a clone profile */
    val isCloneProfile: Boolean = false
) {
    /** Get the unique identifier for this package */
    val id: PackageId get() = PackageId(packageName, userId)
}

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
