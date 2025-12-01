package io.github.dorumrr.de1984.data.firewall

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.service.PrivilegedFirewallService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    // Track currently blocked UIDs to avoid redundant operations
    private val blockedUids = mutableSetOf<Int>()

    // Track currently LAN-blocked UIDs to avoid redundant operations
    private val blockedLanUids = mutableSetOf<Int>()

    /**
     * Start the firewall by starting the PrivilegedFirewallService.
     * The service will call startInternal() to actually create iptables chains.
     */
    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "=== IptablesFirewallBackend.start() ===")
            AppLogger.d(TAG, "Starting PrivilegedFirewallService with iptables backend")

            // Start the privileged firewall service
            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_START
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "IPTABLES")
            }
            context.startService(intent)

            AppLogger.d(TAG, "✅ iptables firewall service started")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start iptables firewall", e)
            val error = errorHandler.handleError(e, "start iptables firewall")
            Result.failure(error)
        }
    }

    /**
     * Internal method called by PrivilegedFirewallService to actually create iptables chains.
     */
    suspend fun startInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "startInternal: Creating iptables chains")

            // Check availability first
            checkAvailability().getOrElse { error ->
                return Result.failure(error)
            }

            // Create custom chains
            createCustomChains().getOrElse { error ->
                return Result.failure(error)
            }

            AppLogger.d(TAG, "✅ iptables chains created")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create iptables chains", e)
            val error = errorHandler.handleError(e, "create iptables chains")
            Result.failure(error)
        }
    }

    /**
     * Stop the firewall by stopping the PrivilegedFirewallService.
     * The service will call stopInternal() to actually delete iptables chains.
     */
    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "Stopping iptables firewall backend")
            AppLogger.d(TAG, "Stopping PrivilegedFirewallService")

            // Stop the privileged firewall service
            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_STOP
            }
            context.startService(intent)

            AppLogger.d(TAG, "iptables firewall service stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop iptables firewall", e)
            val error = errorHandler.handleError(e, "stop iptables firewall")
            Result.failure(error)
        }
    }

    /**
     * Internal method called by PrivilegedFirewallService to actually delete iptables chains.
     */
    suspend fun stopInternal(): Result<Unit> = mutex.withLock {
        return try {
            AppLogger.d(TAG, "stopInternal: Deleting iptables chains")

            // Remove all rules
            clearAllRules().getOrElse { error ->
                AppLogger.w(TAG, "Failed to clear rules during stop: ${error.message}")
            }

            // Delete custom chains
            deleteCustomChains().getOrElse { error ->
                AppLogger.w(TAG, "Failed to delete chains during stop: ${error.message}")
            }

            blockedUids.clear()
            blockedLanUids.clear()
            AppLogger.d(TAG, "iptables chains deleted")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete iptables chains", e)
            val error = errorHandler.handleError(e, "delete iptables chains")
            Result.failure(error)
        }
    }
    
    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = mutex.withLock {
        val startTime = System.currentTimeMillis()
        return try {
            AppLogger.d(TAG, "🔥 [TIMING] IptablesBackend.applyRules START: ${rules.size} rules, network=$networkType, screenOn=$screenOn")

            // Note: No need to check isActive() here - service will only call this when active

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

                AppLogger.d(TAG, "Block All mode: found ${allPackages.size} packages with network permissions")

                // Get critical package protection setting once (outside the loop)
                val allowCritical = prefs.getBoolean(
                    Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                    Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
                )

                // Pre-compute UIDs that contain critical packages (for UID-level exemption checks)
                // This is needed because we block by UID, not by package - so if ANY package
                // in a UID is critical with no rule, the entire UID should be allowed
                val uidsWithCritical = if (allowCritical) {
                    allPackages
                        .filter { Constants.Firewall.isSystemCritical(it.packageName) || hasVpnService(it.packageName) }
                        .map { it.uid }
                        .toSet()
                } else {
                    emptySet()
                }

                for (appInfo in allPackages) {
                    val uid = appInfo.uid
                    val packageName = appInfo.packageName

                    // Never block UIDs that contain system-critical packages or VPN apps
                    // This prevents shared UID bypass (e.g., Gboard sharing UID with system package)
                    if (isUidExempted(uid, allPackages)) {
                        continue
                    }

                    val rulesForUid = rulesByUid[uid]

                    val shouldBlock = if (rulesForUid != null && rulesForUid.isNotEmpty()) {
                        // Has explicit rules - use as-is (absolute blocking state)
                        // Check if ANY rule says to block (most restrictive)
                        val blockDecision = rulesForUid.any { rule ->
                            when {
                                !screenOn && rule.blockWhenBackground -> true
                                rule.isBlockedOn(networkType) -> true
                                else -> false
                            }
                        }
                        AppLogger.d(TAG, "  $packageName (UID $uid): has rule, shouldBlock=$blockDecision")
                        blockDecision
                    } else {
                        // No rule - check if this UID contains ANY critical package with allowCritical enabled
                        // When allowCritical is ON and no explicit rule exists, default to ALLOW for system stability
                        // IMPORTANT: Check at UID level because we block by UID, not by package
                        if (allowCritical && uidsWithCritical.contains(uid)) {
                            val isSelfCritical = Constants.Firewall.isSystemCritical(packageName) || hasVpnService(packageName)
                            if (isSelfCritical) {
                                AppLogger.d(TAG, "  $packageName (UID $uid): no rule, critical package → allowing")
                            } else {
                                AppLogger.d(TAG, "  $packageName (UID $uid): no rule, shares UID with critical package → allowing")
                            }
                            false
                        } else {
                            // Normal non-critical package/UID - apply default policy (block all)
                            AppLogger.d(TAG, "  $packageName (UID $uid): no rule, blocking by default")
                            true
                        }
                    }

                    if (shouldBlock) {
                        uidsToBlock.add(uid)
                    }
                }
            } else {
                // Default policy is "Allow All" - only block apps with explicit block rules
                // For shared UIDs, block if ANY app with that UID should be blocked

                // Get all installed packages (needed to check for shared UIDs with system-critical/VPN apps)
                val packageManager = context.packageManager
                val allPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

                for ((uid, rulesForUid) in rulesByUid) {
                    // Never block UIDs that contain system-critical packages or VPN apps
                    // This prevents shared UID bypass (e.g., Gboard sharing UID with system package)
                    if (isUidExempted(uid, allPackages)) {
                        continue
                    }

                    val shouldBlock = rulesForUid.any { rule ->
                        // Has explicit rule - use as-is (absolute blocking state)
                        when {
                            // Screen off blocking takes precedence
                            !screenOn && rule.blockWhenBackground -> true
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

            AppLogger.d(TAG, "🔥 [TIMING] Rule diff calculated: +${System.currentTimeMillis() - startTime}ms")
            AppLogger.d(TAG, "🔥 [TIMING] Rule diff: add=${uidsToAdd.size} UIDs, remove=${uidsToRemove.size} UIDs, keep=${blockedUids.intersect(uidsToBlock).size} UIDs")

            if (uidsToAdd.isNotEmpty()) {
                AppLogger.d(TAG, "🔥 [TIMING] UIDs to ADD (block): $uidsToAdd")
            }
            if (uidsToRemove.isNotEmpty()) {
                AppLogger.d(TAG, "🔥 [TIMING] UIDs to REMOVE (unblock): $uidsToRemove")
            }

            // Remove rules that are no longer needed
            val unblockStartTime = System.currentTimeMillis()
            for (uid in uidsToRemove) {
                unblockApp(uid).getOrElse { error ->
                    AppLogger.w(TAG, "Failed to unblock UID $uid: ${error.message}")
                }
            }
            if (uidsToRemove.isNotEmpty()) {
                AppLogger.d(TAG, "🔥 [TIMING] Unblock ${uidsToRemove.size} UIDs took ${System.currentTimeMillis() - unblockStartTime}ms")
            }

            // Add new rules
            val blockStartTime = System.currentTimeMillis()
            for (uid in uidsToAdd) {
                blockApp(uid).getOrElse { error ->
                    AppLogger.w(TAG, "Failed to block UID $uid: ${error.message}")
                }
            }
            if (uidsToAdd.isNotEmpty()) {
                AppLogger.d(TAG, "🔥 [TIMING] Block ${uidsToAdd.size} UIDs took ${System.currentTimeMillis() - blockStartTime}ms")
            }

            // =============================================================================================
            // LAN Blocking Logic
            // =============================================================================================

            // Get all installed packages (needed to check for shared UIDs with system-critical/VPN apps)
            val packageManager = context.packageManager
            val allPackagesForLan = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            // Calculate LAN blocking diff
            val uidsToBlockLan = mutableSetOf<Int>()

            // Determine which apps should have LAN blocked
            for ((uid, rulesForUid) in rulesByUid) {
                // Never block UIDs that contain system-critical packages or VPN apps
                if (isUidExempted(uid, allPackagesForLan)) {
                    continue
                }

                val shouldBlockLan = rulesForUid.any { rule -> rule.lanBlocked }
                if (shouldBlockLan) {
                    uidsToBlockLan.add(uid)
                }
            }

            val uidsToAddLan = uidsToBlockLan - blockedLanUids
            val uidsToRemoveLan = blockedLanUids - uidsToBlockLan

            AppLogger.d(TAG, "LAN blocking diff: add=${uidsToAddLan.size}, remove=${uidsToRemoveLan.size}, keep=${blockedLanUids.intersect(uidsToBlockLan).size}")

            // Remove LAN rules that are no longer needed
            for (uid in uidsToRemoveLan) {
                unblockAppLan(uid).getOrElse { error ->
                    AppLogger.w(TAG, "Failed to unblock LAN for UID $uid: ${error.message}")
                }
            }

            // Add new LAN rules
            for (uid in uidsToAddLan) {
                blockAppLan(uid).getOrElse { error ->
                    AppLogger.w(TAG, "Failed to block LAN for UID $uid: ${error.message}")
                }
            }

            AppLogger.d(TAG, "🔥 [TIMING] IptablesBackend.applyRules COMPLETE: total=${System.currentTimeMillis() - startTime}ms")
            AppLogger.d(TAG, "🔥 [TIMING] Final state: ${blockedUids.size} apps blocked (Internet), ${blockedLanUids.size} apps blocked (LAN)")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply rules", e)
            val error = errorHandler.handleError(e, "apply iptables rules")
            Result.failure(error)
        }
    }
    
    override fun isActive(): Boolean {
        // Check if PrivilegedFirewallService is running with iptables backend
        return try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
            val backendType = prefs.getString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, null)

            // If SharedPreferences says service is not running, it's definitely not active
            if (!isServiceRunning || backendType != "IPTABLES") {
                return false
            }

            // SharedPreferences says service is running, but verify the service is actually alive
            // This is important after app reinstall (e.g., dev.sh update) where SharedPreferences
            // persist but the service process is killed
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (activityManager != null) {
                @Suppress("DEPRECATION")
                val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
                val serviceClassName = "io.github.dorumrr.de1984.data.service.PrivilegedFirewallService"
                val isServiceActuallyRunning = runningServices.any { service ->
                    service.service.className == serviceClassName
                }

                // If service is not actually running, clear the SharedPreferences flags
                if (!isServiceActuallyRunning) {
                    AppLogger.w(TAG, "SharedPreferences says privileged service is running, but service is not actually running. Clearing flags.")
                    prefs.edit()
                        .putBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
                        .remove(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE)
                        .apply()
                    return false
                }

                return true
            }

            // Fallback: if we can't check running services, trust SharedPreferences
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check if iptables firewall is active", e)
            false
        }
    }
    
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
                val isRootMode = shizukuManager.isShizukuRootMode()

                if (!isRootMode) {
                    val error = errorHandler.createUnsupportedDeviceError(
                        operation = "iptables firewall",
                        reason = "Shizuku must be started with ROOT privileges (not ADB) to use iptables firewall"
                    )
                    return Result.failure(error)
                }
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
        } catch (e: java.util.concurrent.CancellationException) {
            // Re-throw cancellation exceptions to allow coroutine cancellation to propagate
            AppLogger.d(TAG, "checkAvailability cancelled")
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Re-throw cancellation exceptions to allow coroutine cancellation to propagate
            AppLogger.d(TAG, "checkAvailability cancelled")
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check iptables availability", e)
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

            AppLogger.d(TAG, "Custom chains created successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create custom chains", e)
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

            AppLogger.d(TAG, "Custom chains deleted successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete custom chains", e)
            val error = errorHandler.handleError(e, "delete iptables chains")
            Result.failure(error)
        }
    }
    
    /**
     * Block an app by UID.
     * Only blocks OUTPUT since owner module only works for locally generated packets.
     *
     * CRITICAL: This runs in NonCancellable context to prevent iptables commands from being
     * interrupted mid-execution when the parent coroutine is cancelled (e.g., by debouncing).
     * An interrupted iptables command could leave the firewall in an inconsistent state.
     */
    private suspend fun blockApp(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            AppLogger.d(TAG, "=== Blocking UID $uid ===")

            // IPv4: Block OUTPUT for this UID
            val ipv4Command = "$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP"
            AppLogger.d(TAG, "Executing IPv4 command: $ipv4Command")
            val (ipv4ExitCode, ipv4Output) = executeCommand(ipv4Command)
            AppLogger.d(TAG, "IPv4 result: exitCode=$ipv4ExitCode, output='$ipv4Output'")

            // IPv6: Block OUTPUT for this UID
            val ipv6Command = "$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP"
            AppLogger.d(TAG, "Executing IPv6 command: $ipv6Command")
            val (ipv6ExitCode, ipv6Output) = executeCommand(ipv6Command)
            AppLogger.d(TAG, "IPv6 result: exitCode=$ipv6ExitCode, output='$ipv6Output'")

            if (ipv4ExitCode == 0 && ipv6ExitCode == 0) {
                blockedUids.add(uid)
                AppLogger.d(TAG, "✅ Successfully blocked UID $uid (IPv4 and IPv6)")
            } else {
                AppLogger.e(TAG, "❌ Failed to block UID $uid - IPv4 exitCode=$ipv4ExitCode, IPv6 exitCode=$ipv6ExitCode")
                return@withContext Result.failure(Exception("Failed to block UID $uid"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to block UID $uid", e)
            val error = errorHandler.handleError(e, "block app UID $uid")
            Result.failure(error)
        }
    }
    
    /**
     * Block LAN access for an app by UID.
     * Uses destination IP filtering to block private IP ranges.
     *
     * CRITICAL: Runs in NonCancellable context to prevent interruption.
     */
    private suspend fun blockAppLan(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            AppLogger.d(TAG, "=== Blocking LAN for UID $uid ===")

            // IPv4: Block private IP ranges
            val ipv4Ranges = listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
            for (range in ipv4Ranges) {
                val command = "$IPTABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP"
                AppLogger.d(TAG, "Executing IPv4 LAN command: $command")
                val (exitCode, output) = executeCommand(command)
                AppLogger.d(TAG, "IPv4 LAN result: exitCode=$exitCode, output='$output'")
                if (exitCode != 0) {
                    AppLogger.e(TAG, "❌ Failed to block LAN IPv4 range $range for UID $uid")
                    return@withContext Result.failure(Exception("Failed to block LAN IPv4 for UID $uid"))
                }
            }

            // IPv6: Block private IP ranges
            val ipv6Ranges = listOf("fc00::/7", "fe80::/10")
            for (range in ipv6Ranges) {
                val command = "$IP6TABLES -A $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP"
                AppLogger.d(TAG, "Executing IPv6 LAN command: $command")
                val (exitCode, output) = executeCommand(command)
                AppLogger.d(TAG, "IPv6 LAN result: exitCode=$exitCode, output='$output'")
                if (exitCode != 0) {
                    AppLogger.e(TAG, "❌ Failed to block LAN IPv6 range $range for UID $uid")
                    return@withContext Result.failure(Exception("Failed to block LAN IPv6 for UID $uid"))
                }
            }

            blockedLanUids.add(uid)
            AppLogger.d(TAG, "✅ Successfully blocked LAN for UID $uid (IPv4 and IPv6)")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to block LAN for UID $uid", e)
            val error = errorHandler.handleError(e, "block LAN for app UID $uid")
            Result.failure(error)
        }
    }

    /**
     * Unblock an app by UID.
     *
     * CRITICAL: Runs in NonCancellable context to prevent interruption.
     */
    private suspend fun unblockApp(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            // IPv4: Remove DROP rule for this UID
            executeCommand("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")

            // IPv6: Remove DROP rule for this UID
            executeCommand("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -j DROP 2>/dev/null || true")

            blockedUids.remove(uid)
            AppLogger.d(TAG, "Unblocked UID $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to unblock UID $uid", e)
            val error = errorHandler.handleError(e, "unblock app UID $uid")
            Result.failure(error)
        }
    }

    /**
     * Unblock LAN access for an app by UID.
     *
     * CRITICAL: Runs in NonCancellable context to prevent interruption.
     */
    private suspend fun unblockAppLan(uid: Int): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            // IPv4: Remove DROP rules for private IP ranges
            val ipv4Ranges = listOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
            for (range in ipv4Ranges) {
                executeCommand("$IPTABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
            }

            // IPv6: Remove DROP rules for private IP ranges
            val ipv6Ranges = listOf("fc00::/7", "fe80::/10")
            for (range in ipv6Ranges) {
                executeCommand("$IP6TABLES -D $CHAIN_OUTPUT -m owner --uid-owner $uid -d $range -j DROP 2>/dev/null || true")
            }

            blockedLanUids.remove(uid)
            AppLogger.d(TAG, "Unblocked LAN for UID $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to unblock LAN for UID $uid", e)
            val error = errorHandler.handleError(e, "unblock LAN for app UID $uid")
            Result.failure(error)
        }
    }

    /**
     * Clear all firewall rules.
     *
     * CRITICAL: Runs in NonCancellable context to prevent interruption.
     */
    private suspend fun clearAllRules(): Result<Unit> = withContext(NonCancellable) {
        return@withContext try {
            // Flush custom chains (removes all rules)
            executeCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
            executeCommand("$IP6TABLES -F $CHAIN_OUTPUT 2>/dev/null || true")

            blockedUids.clear()
            blockedLanUids.clear()
            AppLogger.d(TAG, "All rules cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear rules", e)
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

    /**
     * Check if an app has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * VPN apps don't REQUEST the BIND_VPN_SERVICE permission - they DECLARE it on their service.
     * This is a service permission that protects the VPN service from being bound by unauthorized apps.
     */
    private fun hasVpnService(packageName: String): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SERVICES
            )

            // Check if any service has BIND_VPN_SERVICE permission
            packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if a UID should be exempted from blocking.
     * A UID is exempted if ANY package with that UID is:
     * - System-critical (in SYSTEM_WHITELIST)
     * - A VPN app (has BIND_VPN_SERVICE permission)
     *
     * This prevents shared UID bypass vulnerability where a non-critical app
     * (e.g., Gboard) shares a UID with a system-critical package.
     *
     * @param uid The UID to check
     * @param allPackages List of all installed applications
     * @return true if the UID should be exempted from blocking
     */
    private fun isUidExempted(uid: Int, allPackages: List<android.content.pm.ApplicationInfo>): Boolean {
        // Check if critical package protection is disabled
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        // Get all packages with this UID
        val packagesWithUid = allPackages.filter { it.uid == uid }

        // Check if ANY package with this UID is system-critical or a VPN app (unless setting is enabled)
        return packagesWithUid.any { appInfo ->
            (!allowCritical && Constants.Firewall.isSystemCritical(appInfo.packageName)) ||
            (!allowCritical && hasVpnService(appInfo.packageName))
        }
    }
}

