package io.github.dorumrr.de1984.domain.firewall

import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType

/**
 * Abstraction for firewall backend implementations.
 *
 * Implementations:
 * - VpnFirewallBackend: Uses Android VpnService (no root required, occupies VPN slot)
 * - IptablesFirewallBackend: Uses iptables (requires root, frees VPN slot)
 * - ConnectivityManagerFirewallBackend: Uses ConnectivityManager firewall chain (requires Shizuku, Android 13+, no VPN icon)
 * - NetworkPolicyManagerFirewallBackend: Uses NetworkPolicyManager (requires Shizuku, no VPN icon, legacy option for Android 12 and below)
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

    /**
     * Check if this backend supports granular per-network-type blocking.
     *
     * @return true if supports WiFi/Mobile/Roaming separately, false if all-or-nothing
     */
    fun supportsGranularControl(): Boolean
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
     * iptables-based firewall (requires root, frees VPN slot)
     */
    IPTABLES,

    /**
     * ConnectivityManager firewall chain (requires Shizuku, Android 13+, no VPN icon)
     */
    CONNECTIVITY_MANAGER,

    /**
     * NetworkPolicyManager-based firewall (requires Shizuku, no VPN icon, legacy option for Android 12 and below)
     */
    NETWORK_POLICY_MANAGER
}

/**
 * Firewall mode selection.
 */
enum class FirewallMode {
    /**
     * Automatically choose the best available backend.
     * Priority: iptables (if root) > ConnectivityManager (if Shizuku + Android 13+) > VPN
     */
    AUTO,

    /**
     * Force VPN-based firewall (always available)
     */
    VPN,

    /**
     * Force iptables-based firewall (requires root)
     */
    IPTABLES,

    /**
     * Force ConnectivityManager firewall chain (requires Shizuku, Android 13+)
     */
    CONNECTIVITY_MANAGER,

    /**
     * Force NetworkPolicyManager-based firewall (requires Shizuku, legacy option for Android 12 and below)
     */
    NETWORK_POLICY_MANAGER;

    companion object {
        /**
         * Parse firewall mode from string.
         *
         * @param value String value ("auto", "vpn", "iptables", "connectivity_manager", "network_policy_manager")
         * @return FirewallMode or null if invalid
         */
        fun fromString(value: String?): FirewallMode? {
            return when (value?.lowercase()) {
                "auto" -> AUTO
                "vpn" -> VPN
                "iptables" -> IPTABLES
                "connectivity_manager" -> CONNECTIVITY_MANAGER
                "network_policy_manager" -> NETWORK_POLICY_MANAGER
                else -> null
            }
        }

        /**
         * Convert firewall mode to string for storage.
         *
         * @return String value ("auto", "vpn", "iptables", "connectivity_manager", "network_policy_manager")
         */
        fun FirewallMode.toStorageString(): String {
            return when (this) {
                AUTO -> "auto"
                VPN -> "vpn"
                IPTABLES -> "iptables"
                CONNECTIVITY_MANAGER -> "connectivity_manager"
                NETWORK_POLICY_MANAGER -> "network_policy_manager"
            }
        }
    }
}

