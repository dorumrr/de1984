package io.github.dorumrr.de1984.domain.model

data class FirewallRule(
    val packageName: String,
    val uid: Int,
    val appName: String,
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false,
    val blockWhenScreenOff: Boolean = false,
    val blockWhenRoaming: Boolean = false,
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

