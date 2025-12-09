package io.github.dorumrr.de1984.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain model for firewall rules.
 *
 * @property packageName The package name of the app
 * @property userId Android user profile ID (0 = personal, 10+ = work/clone profiles)
 * @property uid Absolute UID: userId * 100000 + appId
 */
@Serializable
data class FirewallRule(
    val packageName: String,
    /** Android user profile ID (0 = personal, 10+ = work/clone profiles) */
    val userId: Int = 0,
    /** Absolute UID: userId * 100000 + appId */
    val uid: Int,
    val appName: String,
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,
    val blockWhenBackground: Boolean = false,
    val blockWhenRoaming: Boolean = false,
    val lanBlocked: Boolean = false,
    val enabled: Boolean = true,
    val isSystemApp: Boolean = false,
    val hasInternetPermission: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isFullyBlocked(): Boolean = wifiBlocked && mobileBlocked
    
    fun isFullyAllowed(): Boolean = !wifiBlocked && !mobileBlocked
    
    fun isPartiallyBlocked(): Boolean = (wifiBlocked || mobileBlocked) && !isFullyBlocked()
    
    fun isBlockedOn(networkType: NetworkType): Boolean {
        if (!enabled) return false
        
        return when (networkType) {
            NetworkType.WIFI -> wifiBlocked
            NetworkType.MOBILE -> mobileBlocked
            NetworkType.ROAMING -> blockWhenRoaming || mobileBlocked
            NetworkType.NONE -> false
        }
    }
    
    fun getBlockingStatus(): String {
        return when {
            !enabled -> "Disabled"
            isFullyBlocked() -> "Fully Blocked"
            isFullyAllowed() -> "Allowed"
            wifiBlocked && !mobileBlocked -> "WiFi Blocked"
            !wifiBlocked && mobileBlocked -> "Mobile Blocked"
            else -> "Partially Blocked"
        }
    }
    
    fun blockAll(): FirewallRule = copy(
        wifiBlocked = true,
        mobileBlocked = true,
        blockWhenRoaming = true,
        updatedAt = System.currentTimeMillis()
    )
    
    fun allowAll(): FirewallRule = copy(
        wifiBlocked = false,
        mobileBlocked = false,
        blockWhenRoaming = false,
        updatedAt = System.currentTimeMillis()
    )
}

