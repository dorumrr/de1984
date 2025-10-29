package io.github.dorumrr.de1984.data.firewall

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * iptables-based firewall backend.
 *
 * Uses iptables owner module to block apps by UID.
 * Requires root or Shizuku access.
 * Frees up VPN slot for real VPN services.
 *
 * Features:
 * - Per-app blocking using UID matching
 * - Network type-specific rules (WiFi/Mobile/Roaming)
 * - Screen state-specific rules
 * - IPv4 and IPv6 support
 * - Custom chain for rule isolation
 */
class IptablesFirewallBackend(
    private val context: Context,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {
    
    companion object {
        private const val TAG = "IptablesFirewall"

        // Custom chain name to avoid conflicts with Android netd
        // Note: We only use OUTPUT chain because the owner module only works for OUTPUT
        // (locally generated packets). INPUT chain cannot match by UID.
        private const val CHAIN_OUTPUT = "de1984_output"

        // Commands
        private const val IPTABLES = "iptables"
        private const val IP6TABLES = "ip6tables"
    }
    
    private val mutex = Mutex()
    private var isActiveState = false
    
    // Track currently blocked UIDs to avoid redundant operations
    private val blockedUids = mutableSetOf<Int>()
    
    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            // Check availability first
            checkAvailability().getOrElse { error ->
                return Result.failure(error)
            }

            // Create custom chains
            createCustomChains().getOrElse { error ->
                return Result.failure(error)
            }

            isActiveState = true
            Log.d(TAG, "iptables firewall started")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start iptables firewall", e)
            val error = errorHandler.handleError(e, "start iptables firewall")
            Result.failure(error)
        }
    }
    
    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "Stopping iptables firewall backend")
            
            // Remove all rules
            clearAllRules().getOrElse { error ->
                Log.w(TAG, "Failed to clear rules during stop: ${error.message}")
            }
            
            // Delete custom chains
            deleteCustomChains().getOrElse { error ->
                Log.w(TAG, "Failed to delete chains during stop: ${error.message}")
            }
            
            blockedUids.clear()
            isActiveState = false
            Log.d(TAG, "iptables firewall backend stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop iptables firewall", e)
            val error = errorHandler.handleError(e, "stop iptables firewall")
            Result.failure(error)
        }
    }
    
    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "Applying rules: ${rules.size} total, network=$networkType, screenOn=$screenOn")

            if (!isActiveState) {
                Log.w(TAG, "Cannot apply rules: backend not active")
                return Result.failure(IllegalStateException("Backend not active"))
            }

            // Get default policy
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            // Determine which apps should be blocked based on current state
            val uidsToBlock = mutableSetOf<Int>()

            // Group rules by UID to handle shared UIDs correctly
            // Multiple apps can share the same UID (sharedUserId in manifest)
            // For security, we use the most restrictive rule (block if ANY app with that UID should be blocked)
            val rulesByUid = rules.filter { it.enabled }.groupBy { it.uid }

            // If default policy is "Block All", we need to get all installed packages
            // and block those without explicit "allow" rules
            if (isBlockAllDefault) {
                val packageManager = context.packageManager
                val allPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { appInfo ->
                        try {
                            val packageInfo = packageManager.getPackageInfo(
                                appInfo.packageName,
                                PackageManager.GET_PERMISSIONS
                            )
                            packageInfo.requestedPermissions?.any { permission ->
                                Constants.Firewall.NETWORK_PERMISSIONS.contains(permission)
                            } ?: false
                        } catch (e: Exception) {
                            false
                        }
                    }

                for (appInfo in allPackages) {
                    val uid = appInfo.uid
                    val packageName = appInfo.packageName

                    // Never block our own app
                    if (Constants.App.isOwnApp(packageName)) {
                        continue
                    }

                    val rulesForUid = rulesByUid[uid]

                    val shouldBlock = if (rulesForUid != null && rulesForUid.isNotEmpty()) {
                        // Has explicit rules - use as-is (absolute blocking state)
                        // Check if ANY rule says to block (most restrictive)
                        rulesForUid.any { rule ->
                            when {
                                !screenOn && rule.blockWhenScreenOff -> true
                                rule.isBlockedOn(networkType) -> true
                                else -> false
                            }
                        }
                    } else {
                        // No rule - apply default policy (block all)
                        true
                    }

                    if (shouldBlock) {
                        uidsToBlock.add(uid)
                    }
                }
            } else {
                // Default policy is "Allow All" - only block apps with explicit block rules
                // For shared UIDs, block if ANY app with that UID should be blocked
                for ((uid, rulesForUid) in rulesByUid) {
                    val shouldBlock = rulesForUid.any { rule ->
                        // Has explicit rule - use as-is (absolute blocking state)
                        when {
                            // Screen off blocking takes precedence
                            !screenOn && rule.blockWhenScreenOff -> true
                            // Network-specific blocking
                            rule.isBlockedOn(networkType) -> true
                            else -> false
                        }
                    }

                    if (shouldBlock) {
                        uidsToBlock.add(uid)
                    }
                }
            }

            // Calculate diff: what to add, what to remove
            val uidsToAdd = uidsToBlock - blockedUids
            val uidsToRemove = blockedUids - uidsToBlock

            Log.d(TAG, "Rule diff: add=${uidsToAdd.size}, remove=${uidsToRemove.size}, keep=${blockedUids.intersect(uidsToBlock).size}")

            // Remove rules that are no longer needed
            for (uid in uidsToRemove) {
                unblockApp(uid).getOrElse { error ->
                    Log.w(TAG, "Failed to unblock UID $uid: ${error.message}")
                }
            }

            // Add new rules
            for (uid in uidsToAdd) {
                blockApp(uid).getOrElse { error ->
                    Log.w(TAG, "Failed to block UID $uid: ${error.message}")
                }
            }

            Log.d(TAG, "Rules applied: ${blockedUids.size} apps blocked")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply rules", e)
            val error = errorHandler.handleError(e, "apply iptables rules")
            Result.failure(error)
        }
    }
    
    override fun isActive(): Boolean = isActiveState
    
    override fun getType(): FirewallBackendType = FirewallBackendType.IPTABLES
    
    override suspend fun checkAvailability(): Result<Unit> {
        return try {
            // Check if we have root or Shizuku access
            val hasRoot = rootManager.hasRootPermission
            val hasShizuku = shizukuManager.hasShizukuPermission
            val hasAccess = hasRoot || hasShizuku

            if (!hasAccess) {
                val error = errorHandler.createRootRequiredError("iptables firewall")
                return Result.failure(error)
            }

            // If using Shizuku, check if it's running in root mode
            if (hasShizuku && !hasRoot) {
                val shizukuUid = shizukuManager.getShizukuUid()
                val isRootMode = shizukuManager.isShizukuRootMode()
                Log.d(TAG, "Shizuku UID: $shizukuUid, isRootMode: $isRootMode")

                if (!isRootMode) {
                    Log.e(TAG, "❌ Shizuku is running in ADB mode (UID $shizukuUid), not root mode!")
                    Log.e(TAG, "❌ iptables requires Shizuku to be started with ROOT, not ADB")
                    val error = errorHandler.createUnsupportedDeviceError(
                        operation = "iptables firewall",
                        reason = "Shizuku must be started with ROOT privileges (not ADB) to use iptables firewall. Please restart Shizuku with root mode."
                    )
                    return Result.failure(error)
                }
                Log.d(TAG, "✅ Shizuku is running in ROOT mode - can use iptables")
            }

            // Check if iptables is available
            val (exitCode, _) = executeCommand("$IPTABLES --version")

            if (exitCode != 0) {
                val error = errorHandler.createUnsupportedDeviceError(
                    operation = "iptables firewall",
                    reason = "iptables not available on this device"
                )
                return Result.failure(error)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check iptables availability", e)
            val error = errorHandler.handleError(e, "check iptables availability")
            Result.failure(error)
        }
    }
    
    /**
     * Create custom chains for rule isolation.
     * Only creates OUTPUT chain since owner module only works for OUTPUT.
     */
    private suspend fun createCustomChains(): Result<Unit> {
        return try {
            // IPv4 chain
            executeCommand("$IPTABLES -N $CHAIN_OUTPUT 2>/dev/null || true")

            // Link chain to OUTPUT
            executeCommand("$IPTABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IPTABLES -I OUTPUT -j $CHAIN_OUTPUT")

            // IPv6 chain
            executeCommand("$IP6TABLES -N $CHAIN_OUTPUT 2>/dev/null || true")

            // Link chain to OUTPUT
            executeCommand("$IP6TABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IP6TABLES -I OUTPUT -j $CHAIN_OUTPUT")

            Log.d(TAG, "Custom chains created successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create custom chains", e)
            val error = errorHandler.handleError(e, "create iptables chains")
            Result.failure(error)
        }
    }
    
    /**
     * Delete custom chains.
     */
    private suspend fun deleteCustomChains(): Result<Unit> {
        return try {
            // IPv4: Unlink and delete chain
            executeCommand("$IPTABLES -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IPTABLES -X $CHAIN_OUTPUT 2>/dev/null || true")

            // IPv6: Unlink and delete chain
            executeCommand("$IP6TABLES -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -X $CHAIN_OUTPUT 2>/dev/null || true")

            Log.d(TAG, "Custom chains deleted successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete custom chains", e)
            val error = errorHandler.handleError(e, "delete iptables chains")
            Result.failure(error)
        }
    }
    
    /**
     * Block an app by UID.
     * Only blocks OUTPUT since owner module only works for locally generated packets.
     */
    private suspend fun blockApp(uid: Int): Result<Unit> {
        return try {
            Log.d(TAG, "=== Blocking UID $uid ===")

            // IPv4: Block OUTPUT for this UID
            val ipv4Command = "$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP"
            Log.d(TAG, "Executing IPv4 command: $ipv4Command")
            val (ipv4ExitCode, ipv4Output) = executeCommand(ipv4Command)
            Log.d(TAG, "IPv4 result: exitCode=$ipv4ExitCode, output='$ipv4Output'")

            // IPv6: Block OUTPUT for this UID
            val ipv6Command = "$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP"
            Log.d(TAG, "Executing IPv6 command: $ipv6Command")
            val (ipv6ExitCode, ipv6Output) = executeCommand(ipv6Command)
            Log.d(TAG, "IPv6 result: exitCode=$ipv6ExitCode, output='$ipv6Output'")

            if (ipv4ExitCode == 0 && ipv6ExitCode == 0) {
                blockedUids.add(uid)
                Log.d(TAG, "✅ Successfully blocked UID $uid (IPv4 and IPv6)")
            } else {
                Log.e(TAG, "❌ Failed to block UID $uid - IPv4 exitCode=$ipv4ExitCode, IPv6 exitCode=$ipv6ExitCode")
                return Result.failure(Exception("Failed to block UID $uid"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to block UID $uid", e)
            val error = errorHandler.handleError(e, "block app UID $uid")
            Result.failure(error)
        }
    }
    
    /**
     * Unblock an app by UID.
     */
    private suspend fun unblockApp(uid: Int): Result<Unit> {
        return try {
            // IPv4: Remove DROP rule for this UID
            executeCommand("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")

            // IPv6: Remove DROP rule for this UID
            executeCommand("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")

            blockedUids.remove(uid)
            Log.d(TAG, "Unblocked UID $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unblock UID $uid", e)
            val error = errorHandler.handleError(e, "unblock app UID $uid")
            Result.failure(error)
        }
    }
    
    /**
     * Clear all firewall rules.
     */
    private suspend fun clearAllRules(): Result<Unit> {
        return try {
            // Flush custom chains (removes all rules)
            executeCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -F $CHAIN_OUTPUT 2>/dev/null || true")

            blockedUids.clear()
            Log.d(TAG, "All rules cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear rules", e)
            val error = errorHandler.handleError(e, "clear iptables rules")
            Result.failure(error)
        }
    }
    
    /**
     * Execute command using root or Shizuku.
     */
    private suspend fun executeCommand(command: String): Pair<Int, String> {
        return if (rootManager.hasRootPermission) {
            rootManager.executeRootCommand(command)
        } else if (shizukuManager.hasShizukuPermission) {
            shizukuManager.executeShellCommand(command)
        } else {
            Pair(-1, "No root or Shizuku access")
        }
    }

    override fun supportsGranularControl(): Boolean = true  // Supports WiFi/Mobile/Roaming separately
}

