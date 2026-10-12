package io.github.dorumrr.de1984.domain.model

data class PackageId(
    val packageName: String,
    val userId: Int = 0
)

data class Package(
    val packageName: String,
    val userId: Int = 0,
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
    val isWorkProfile: Boolean = false,
    val isCloneProfile: Boolean = false,
    val isFullUser: Boolean = false
) {
    val id: PackageId get() = PackageId(packageName, userId)
}

enum class PackageType {
    SYSTEM,
    USER
}

enum class PackageCriticality {
    ESSENTIAL,
    IMPORTANT,
    OPTIONAL,
    BLOATWARE,
    UNKNOWN
}
