package io.github.dorumrr.de1984.data.firewall

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.ShizukuManager
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
    private var isRunning = false

    override suspend fun start(): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "=== ConnectivityManagerFirewallBackend.start() ===")

            if (isRunning) {
                Log.d(TAG, "Already running")
                return Result.success(Unit)
            }

            // Enable the firewall chain using shell command
            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled true")
            if (exitCode != 0) {
                val error = "Failed to enable firewall chain: $output"
                Log.e(TAG, error)
                return Result.failure(Exception(error))
            }

            isRunning = true
            Log.d(TAG, "✅ ConnectivityManager firewall started")
            Log.d(TAG, "ℹ️  Firewall chain enabled (FIREWALL_CHAIN_OEM_DENY_3)")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ConnectivityManager firewall", e)
            Result.failure(errorHandler.handleError(e, "start ConnectivityManager firewall"))
        }
    }

    override suspend fun stop(): Result<Unit> = mutex.withLock {
        return try {
            Log.d(TAG, "Stopping ConnectivityManager firewall backend")

            if (!isRunning) {
                Log.d(TAG, "Not running")
                return Result.success(Unit)
            }

            // Disable the firewall chain using shell command
            val (exitCode, output) = shizukuManager.executeShellCommand("cmd connectivity set-chain3-enabled false")
            if (exitCode != 0) {
                Log.w(TAG, "Failed to disable firewall chain: $output")
                // Don't fail on stop - just log the warning
            }

            isRunning = false
            Log.d(TAG, "ConnectivityManager firewall backend stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ConnectivityManager firewall", e)
            Result.failure(errorHandler.handleError(e, "stop ConnectivityManager firewall"))
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

            if (!isRunning) {
                Log.w(TAG, "Firewall not running, skipping rule application")
                return Result.success(Unit)
            }

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

            // Apply rules to all packages
            allPackages.forEach { appInfo ->
                val packageName = appInfo.packageName

                // Never block our own app
                if (Constants.App.isOwnApp(packageName)) {
                    return@forEach
                }

                try {
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

                    // Set package networking enabled/disabled using shell command
                    val enabled = !shouldBlock  // true = allow, false = block
                    val (exitCode, output) = shizukuManager.executeShellCommand(
                        "cmd connectivity set-package-networking-enabled $enabled $packageName"
                    )

                    if (exitCode == 0) {
                        appliedCount++
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

            Log.d(TAG, "✅ Applied $appliedCount policies, $errorCount errors")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply rules", e)
            Result.failure(errorHandler.handleError(e, "apply connectivity manager rules"))
        }
    }

    override fun isActive(): Boolean = isRunning

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
}

