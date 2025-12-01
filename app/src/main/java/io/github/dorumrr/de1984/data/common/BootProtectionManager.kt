package io.github.dorumrr.de1984.data.common

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages boot protection by creating/deleting Magisk boot scripts.
 * Boot protection blocks all network traffic during device startup until De1984 firewall activates.
 */
class BootProtectionManager(
    private val context: Context,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager
) {
    companion object {
        private const val TAG = "BootProtectionManager"
    }

    /**
     * Check if boot script support is available by verifying the post-fs-data.d directory exists.
     * This directory is supported by Magisk, KernelSU, and APatch.
     */
    suspend fun isBootScriptSupportAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "Checking if boot script support is available...")

            val command = "test -d ${Constants.BootProtection.MAGISK_POST_FS_DIR} && echo 'exists' || echo 'not_found'"
            val result = executeCommand(command)

            val available = result.first == 0 && result.second.trim() == "exists"
            AppLogger.d(TAG, "Boot script support available: $available (exitCode=${result.first}, output='${result.second.trim()}')")

            available
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check boot script support availability", e)
            false
        }
    }

    /**
     * Check if boot protection is currently enabled by verifying the script file exists.
     */
    suspend fun isBootProtectionEnabled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val command = "test -f ${Constants.BootProtection.BOOT_SCRIPT_PATH} && echo 'exists' || echo 'not_found'"
            val result = executeCommand(command)
            
            val enabled = result.first == 0 && result.second.trim() == "exists"
            AppLogger.d(TAG, "Boot protection enabled: $enabled")
            
            enabled
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check boot protection status", e)
            false
        }
    }

    /**
     * Enable or disable boot protection.
     * 
     * @param enabled true to enable boot protection, false to disable
     * @return Result with Unit on success, or error message on failure
     */
    suspend fun setBootProtection(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "${if (enabled) "ENABLING" else "DISABLING"} BOOT PROTECTION")

            if (enabled) {
                createBootScript()
            } else {
                deleteBootScript()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to ${if (enabled) "enable" else "disable"} boot protection", e)
            Result.failure(e)
        }
    }

    /**
     * Create the boot protection script in Magisk's post-fs-data.d directory.
     */
    private suspend fun createBootScript(): Result<Unit> {
        AppLogger.d(TAG, "Creating boot protection script...")

        // Script content that blocks all network traffic during boot
        // We use a custom chain to isolate boot protection rules from system rules
        // This allows clean removal when De1984 starts
        val scriptContent = """#!/system/bin/sh
# De1984 Boot Protection
# Blocks all network traffic until De1984 starts
# Author: Doru Moraru

# Create custom chain for boot protection
iptables -N de1984_boot 2>/dev/null || iptables -F de1984_boot
ip6tables -N de1984_boot 2>/dev/null || ip6tables -F de1984_boot

# Allow loopback traffic (required for system services)
iptables -A de1984_boot -o lo -j ACCEPT
ip6tables -A de1984_boot -o lo -j ACCEPT

# Allow critical system UIDs needed for network connectivity
# UID 0 (root) - netd and other critical network daemons
iptables -A de1984_boot -m owner --uid-owner 0 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 0 -j ACCEPT

# UID 1000 (system) - system_server and Android framework
iptables -A de1984_boot -m owner --uid-owner 1000 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 1000 -j ACCEPT

# UID 1010 (wifi) - WiFi services (wpa_supplicant, wificond)
iptables -A de1984_boot -m owner --uid-owner 1010 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 1010 -j ACCEPT

# UID 1016 (media) - May be needed for captive portal detection
iptables -A de1984_boot -m owner --uid-owner 1016 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 1016 -j ACCEPT

# UID 1051 (gps) - GPS/location services
iptables -A de1984_boot -m owner --uid-owner 1051 -j ACCEPT
ip6tables -A de1984_boot -m owner --uid-owner 1051 -j ACCEPT

# Block everything else (user apps)
iptables -A de1984_boot -j DROP
ip6tables -A de1984_boot -j DROP

# Insert boot protection chain at the beginning of OUTPUT
iptables -I OUTPUT -j de1984_boot
ip6tables -I OUTPUT -j de1984_boot
"""

        // Create the script file
        val createCommand = "echo '${scriptContent.replace("'", "'\\''")}' > ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val createResult = executeCommand(createCommand)
        
        if (createResult.first != 0) {
            val error = "Failed to create boot script (exit code: ${createResult.first})"
            AppLogger.e(TAG, error)
            return Result.failure(Exception(error))
        }
        
        AppLogger.d(TAG, "✅ Boot script created successfully")

        // Set executable permissions (755)
        val chmodCommand = "chmod ${Constants.BootProtection.BOOT_SCRIPT_PERMISSIONS} ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val chmodResult = executeCommand(chmodCommand)
        
        if (chmodResult.first != 0) {
            val error = "Failed to set script permissions (exit code: ${chmodResult.first})"
            AppLogger.e(TAG, error)
            return Result.failure(Exception(error))
        }
        
        AppLogger.d(TAG, "✅ Script permissions set to ${Constants.BootProtection.BOOT_SCRIPT_PERMISSIONS}")
        AppLogger.d(TAG, "✅ Boot protection enabled successfully")
        
        return Result.success(Unit)
    }

    /**
     * Delete the boot protection script.
     */
    private suspend fun deleteBootScript(): Result<Unit> {
        AppLogger.d(TAG, "Deleting boot protection script...")

        val deleteCommand = "rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val deleteResult = executeCommand(deleteCommand)
        
        if (deleteResult.first != 0) {
            val error = "Failed to delete boot script (exit code: ${deleteResult.first})"
            AppLogger.e(TAG, error)
            return Result.failure(Exception(error))
        }
        
        AppLogger.d(TAG, "✅ Boot script deleted successfully")
        AppLogger.d(TAG, "✅ Boot protection disabled successfully")
        
        return Result.success(Unit)
    }

    /**
     * Remove boot protection iptables rules after firewall starts.
     * This is called after boot when boot protection was enabled.
     *
     * We remove the custom boot protection chain that was created by the boot script.
     * This allows De1984's firewall to take over network control cleanly.
     */
    suspend fun resetIptablesPolicies(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                AppLogger.d(TAG, "Removing boot protection iptables rules...")

                // IPv4: Remove boot protection chain
                // 1. Unlink the chain from OUTPUT
                var result = executeCommand("iptables -D OUTPUT -j de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv4: Unlinked de1984_boot chain (exit code: ${result.first})")

                // 2. Flush the chain
                result = executeCommand("iptables -F de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv4: Flushed de1984_boot chain (exit code: ${result.first})")

                // 3. Delete the chain
                result = executeCommand("iptables -X de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv4: Deleted de1984_boot chain (exit code: ${result.first})")

                // IPv6: Remove boot protection chain
                // 1. Unlink the chain from OUTPUT
                result = executeCommand("ip6tables -D OUTPUT -j de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv6: Unlinked de1984_boot chain (exit code: ${result.first})")

                // 2. Flush the chain
                result = executeCommand("ip6tables -F de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv6: Flushed de1984_boot chain (exit code: ${result.first})")

                // 3. Delete the chain
                result = executeCommand("ip6tables -X de1984_boot 2>/dev/null || true")
                AppLogger.d(TAG, "IPv6: Deleted de1984_boot chain (exit code: ${result.first})")

                AppLogger.d(TAG, "✅ Boot protection iptables rules removed successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to remove boot protection iptables rules", e)
                Result.failure(e)
            }
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
}

