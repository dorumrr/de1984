package io.github.dorumrr.de1984.data.firewall

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.service.PrivilegedFirewallService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Firewall backend using Android's ConnectivityManager firewall chain API.
 *
 * This backend uses shell commands to access the ConnectivityManager firewall chain
 * to block network access for specific apps using the FIREWALL_CHAIN_OEM_DENY_3 chain.
 *
 * Requirements:
 * - Android 13+ (API 33+)
 * - Shizuku in ADB mode (UID 2000) or root mode (UID 0)
 *
 * Advantages:
 * - Blocks ALL network types (WiFi, Mobile, Roaming, VPN, etc.)
 * - No VPN icon in status bar
 * - Kernel-level blocking (packets are dropped)
 *
 * Limitations:
 * - All-or-nothing blocking (cannot block only WiFi or only Mobile)
 * - Settings lost on reboot (must restore on boot)
 *
 * Shell commands used:
 * - cmd connectivity set-chain3-enabled true/false
 * - cmd connectivity set-package-networking-enabled true/false <package>
 * - cmd connectivity get-package-networking-enabled <package>
 */
class ConnectivityManagerFirewallBackend(
    private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler
) : FirewallBackend {

    companion object {
        private const val TAG = "ConnectivityManagerFirewall"
        private const val SERVICE_NAME = "connectivity"
        private const val MIN_API_LEVEL = Build.VERSION_CODES.TIRAMISU // Android 13
        private const val FIREWALL_CHAIN_OEM_DENY_3 = 3 // OEM-specific deny chain
    }

    private val mutex = Mutex()

    // Track applied policies to avoid redundant shell commands (memory leak fix)
    // Maps packageName -> isBlocked
    private val appliedPolicies = mutableMapOf<String, Boolean>()

    /**
     * Start the firewall by starting the PrivilegedFirewallService.
     * The service will call startInternal() to actually enable the firewall chain.
     */
    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "=== ConnectivityManagerFirewallBackend.start() ===")
            Log.d(TAG, "Starting PrivilegedFirewallService with ConnectivityManager backend")

            // Start the privileged firewall service
            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_START
                putExtra(PrivilegedFirewallService.EXTRA_BACKEND_TYPE, "CONNECTIVITY_MANAGER")
            }
            context.startService(intent)

            Log.d(TAG, "✅ ConnectivityManager firewall service started")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ConnectivityManager firewall", e)
            Result.failure(errorHandler.handleError(e, "start ConnectivityManager firewall"))
        }
    }

    /**
     * Internal method called by PrivilegedFirewallService to actually enable the firewall chain.
     */
    suspend fun startInternal(): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "startInternal: Enabling firewall chain")

            // Enable the firewall chain using shell command
            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled true")
            if (exitCode != 0) {
                val error = "Failed to enable firewall chain: $output"
                Log.e(TAG, error)
                return Result.failure(Exception(error))
            }

            Log.d(TAG, "✅ Firewall chain enabled (FIREWALL_CHAIN_OEM_DENY_3)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable firewall chain", e)
            Result.failure(errorHandler.handleError(e, "enable ConnectivityManager firewall chain"))
        }
    }

    /**
     * Stop the firewall by stopping the PrivilegedFirewallService.
     * The service will call stopInternal() to actually disable the firewall chain.
     */
    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "Stopping ConnectivityManager firewall backend")
            Log.d(TAG, "Stopping PrivilegedFirewallService")

            // Stop the privileged firewall service
            val intent = Intent(context, PrivilegedFirewallService::class.java).apply {
                action = PrivilegedFirewallService.ACTION_STOP
            }
            context.startService(intent)

            Log.d(TAG, "ConnectivityManager firewall service stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ConnectivityManager firewall", e)
            Result.failure(errorHandler.handleError(e, "stop ConnectivityManager firewall"))
        }
    }

    /**
     * Internal method called by PrivilegedFirewallService to actually disable the firewall chain.
     */
    suspend fun stopInternal(): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "stopInternal: Disabling firewall chain")

            // Disable the firewall chain using shell command
            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled false")
            if (exitCode != 0) {
                Log.w(TAG, "Failed to disable firewall chain: $output")
                // Don't fail on stop - just log the warning
            }

            // Clear applied policies cache when stopping firewall
            appliedPolicies.clear()
            Log.d(TAG, "Cleared applied policies cache")

            Log.d(TAG, "Firewall chain disabled")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable firewall chain", e)
            Result.failure(errorHandler.handleError(e, "disable ConnectivityManager firewall chain"))
        }
    }

    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "=== ConnectivityManagerFirewallBackend.applyRules() ===")
            Log.d(TAG, "Rules count: ${rules.size}, networkType: $networkType, screenOn: $screenOn")

            // Get default policy from SharedPreferences
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            Log.d(TAG, "Default policy: $defaultPolicy (isBlockAllDefault=$isBlockAllDefault)")

            var appliedCount = 0
            var errorCount = 0
            var skippedCount = 0

            // Create a map of rules by package name for quick lookup
            val rulesByPackage = rules.filter { it.enabled }.associateBy { it.packageName }

            // Get all installed packages with network permissions
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

            Log.d(TAG, "Found ${allPackages.size} packages with network permissions")

            // Calculate desired policies for all packages
            val desiredPolicies = mutableMapOf<String, Boolean>()  // packageName -> shouldBlock

            // First pass: Calculate what the policy should be for each package
            allPackages.forEach { appInfo ->
                val packageName = appInfo.packageName

                // Never block system-critical packages - always allow
                if (Constants.Firewall.isSystemCritical(packageName)) {
                    desiredPolicies[packageName] = false  // false = allow
                    return@forEach
                }

                // Never block VPN apps to prevent VPN reconnection issues
                if (hasVpnService(packageName)) {
                    desiredPolicies[packageName] = false  // false = allow
                    return@forEach
                }

                val rule = rulesByPackage[packageName]

                val shouldBlock = if (rule != null) {
                    // Has explicit rule - use it
                    // Per FIREWALL.md lines 220-230: ConnectivityManager is all-or-nothing
                    when {
                        !screenOn && rule.blockWhenScreenOff -> true
                        rule.isBlockedOn(networkType) -> true
                        else -> false
                    }
                } else {
                    // No rule - apply default policy
                    // Per FIREWALL.md lines 220-230:
                    // - Block All mode: Apps without rules are blocked on all networks
                    // - Allow All mode: Apps without rules are allowed on all networks
                    isBlockAllDefault
                }

                desiredPolicies[packageName] = shouldBlock
            }

            // Second pass: Only apply changes for packages whose policy changed
            // This drastically reduces shell command execution (memory leak fix)
            desiredPolicies.forEach { (packageName, shouldBlock) ->
                val currentPolicy = appliedPolicies[packageName]

                // Skip if policy hasn't changed
                if (currentPolicy == shouldBlock) {
                    skippedCount++
                    return@forEach
                }

                try {
                    // Set package networking enabled/disabled using shell command
                    val enabled = !shouldBlock  // true = allow, false = block
                    val (exitCode, output) = shizukuManager.executeShellCommand(
                        "cmd connectivity set-package-networking-enabled $enabled $packageName"
                    )

                    if (exitCode == 0) {
                        appliedCount++
                        appliedPolicies[packageName] = shouldBlock  // Track applied policy
                        val rule = rulesByPackage[packageName]
                        val ruleStatus = if (rule != null) "has rule" else "no rule (default policy)"
                        Log.d(TAG, "Applied policy for $packageName ($ruleStatus): " +
                                "policy=${if (shouldBlock) "BLOCK (all networks)" else "ALLOW"}")
                    } else {
                        errorCount++
                        Log.e(TAG, "Failed to apply policy for $packageName: $output")
                    }
                } catch (e: Exception) {
                    errorCount++
                    Log.e(TAG, "Failed to apply policy for $packageName", e)
                }
            }

            Log.d(TAG, "✅ Applied $appliedCount policies, skipped $skippedCount unchanged, $errorCount errors")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply rules", e)
            Result.failure(errorHandler.handleError(e, "apply connectivity manager rules"))
        }
    }

    override fun isActive(): Boolean {
        // Check if PrivilegedFirewallService is running with ConnectivityManager backend
        return try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
            val backendType = prefs.getString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, null)

            // If SharedPreferences says service is not running, it's definitely not active
            if (!isServiceRunning || backendType != "CONNECTIVITY_MANAGER") {
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
                    Log.w(TAG, "SharedPreferences says privileged service is running, but service is not actually running. Clearing flags.")
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
            Log.e(TAG, "Failed to check if ConnectivityManager firewall is active", e)
            false
        }
    }

    override fun getType(): FirewallBackendType = FirewallBackendType.CONNECTIVITY_MANAGER

    override suspend fun checkAvailability(): Result<Unit> {
        return try {
            // Check Android version
            if (Build.VERSION.SDK_INT < MIN_API_LEVEL) {
                val error = "ConnectivityManager firewall requires Android 13+"
                return Result.failure(Exception(error))
            }

            // Check Shizuku permission
            if (!shizukuManager.hasShizukuPermission) {
                val error = "Shizuku permission required"
                return Result.failure(Exception(error))
            }

            // Test if the connectivity command is available
            val (_, output) = shizukuManager.executeShellCommand("cmd connectivity help")

            // Check if output contains expected help text
            if (!output.contains("set-chain3-enabled")) {
                val error = "ConnectivityManager firewall chain API not available"
                return Result.failure(Exception(error))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "ConnectivityManager firewall not available", e)
            Result.failure(errorHandler.handleError(e, "check ConnectivityManager availability"))
        }
    }

    override fun supportsGranularControl(): Boolean = false  // All-or-nothing blocking

    /**
     * Clear the applied policies cache.
     * This should be called when the default policy changes to force re-evaluation of all packages.
     */
    fun clearAppliedPoliciesCache() {
        appliedPolicies.clear()
        Log.d(TAG, "Cleared applied policies cache (forced)")
    }

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
}

