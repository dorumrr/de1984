package io.github.dorumrr.de1984.domain.firewall

import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType

/**
 * Abstraction for firewall backend implementations.
 * 
 * Implementations:
 * - VpnFirewallBackend: Uses Android VpnService (no root required, occupies VPN slot)
 * - IptablesFirewallBackend: Uses iptables (requires root/Shizuku, frees VPN slot)
 */
interface FirewallBackend {
    
    /**
     * Start the firewall backend.
     * 
     * @return Result.success if started successfully, Result.failure with error otherwise
     */
    suspend fun start(): Result<Unit>
    
    /**
     * Stop the firewall backend.
     * 
     * @return Result.success if stopped successfully, Result.failure with error otherwise
     */
    suspend fun stop(): Result<Unit>
    
    /**
     * Apply firewall rules based on current state.
     * 
     * @param rules List of firewall rules to apply
     * @param networkType Current network type (WiFi/Mobile/Roaming/None)
     * @param screenOn Whether screen is currently on
     * @return Result.success if rules applied successfully, Result.failure with error otherwise
     */
    suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit>
    
    /**
     * Check if the firewall backend is currently active.
     * 
     * @return true if active, false otherwise
     */
    fun isActive(): Boolean
    
    /**
     * Get the type of this firewall backend.
     * 
     * @return FirewallBackendType
     */
    fun getType(): FirewallBackendType
    
    /**
     * Check if this backend is available on the current device.
     * 
     * @return Result.success if available, Result.failure with reason otherwise
     */
    suspend fun checkAvailability(): Result<Unit>
}

/**
 * Types of firewall backends.
 */
enum class FirewallBackendType {
    /**
     * VPN-based firewall (no root required, occupies VPN slot)
     */
    VPN,
    
    /**
     * iptables-based firewall (requires root/Shizuku, frees VPN slot)
     */
    IPTABLES
}

/**
 * Firewall mode selection.
 */
enum class FirewallMode {
    /**
     * Automatically choose the best available backend.
     * Priority: iptables (if root/Shizuku available) > VPN
     */
    AUTO,
    
    /**
     * Force VPN-based firewall (always available)
     */
    VPN,
    
    /**
     * Force iptables-based firewall (requires root/Shizuku)
     */
    IPTABLES;
    
    companion object {
        /**
         * Parse firewall mode from string.
         * 
         * @param value String value ("auto", "vpn", "iptables")
         * @return FirewallMode or null if invalid
         */
        fun fromString(value: String?): FirewallMode? {
            return when (value?.lowercase()) {
                "auto" -> AUTO
                "vpn" -> VPN
                "iptables" -> IPTABLES
                else -> null
            }
        }
        
        /**
         * Convert firewall mode to string for storage.
         * 
         * @return String value ("auto", "vpn", "iptables")
         */
        fun FirewallMode.toStorageString(): String {
            return when (this) {
                AUTO -> "auto"
                VPN -> "vpn"
                IPTABLES -> "iptables"
            }
        }
    }
}

