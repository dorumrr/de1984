package io.github.dorumrr.de1984.data.common

import android.content.Context
import android.util.Log
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
            Log.d(TAG, "Checking if boot script support is available...")

            val command = "test -d ${Constants.BootProtection.MAGISK_POST_FS_DIR} && echo 'exists' || echo 'not_found'"
            val result = executeCommand(command)

            val available = result.first == 0 && result.second.trim() == "exists"
            Log.d(TAG, "Boot script support available: $available (exitCode=${result.first}, output='${result.second.trim()}')")

            available
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check boot script support availability", e)
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
            Log.d(TAG, "Boot protection enabled: $enabled")
            
            enabled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check boot protection status", e)
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
            Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
            Log.d(TAG, "║  ${if (enabled) "ENABLING" else "DISABLING"} BOOT PROTECTION                              ║")
            Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")

            if (enabled) {
                createBootScript()
            } else {
                deleteBootScript()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ${if (enabled) "enable" else "disable"} boot protection", e)
            Result.failure(e)
        }
    }

    /**
     * Create the boot protection script in Magisk's post-fs-data.d directory.
     */
    private suspend fun createBootScript(): Result<Unit> {
        Log.d(TAG, "Creating boot protection script...")

        // Script content that blocks all network traffic during boot
        val scriptContent = """#!/system/bin/sh
# De1984 Boot Protection
# Blocks all network traffic until De1984 starts

# Block all OUTPUT traffic (apps trying to send data)
iptables -P OUTPUT DROP
iptables -A OUTPUT -o lo -j ACCEPT

# Block IPv6 as well
ip6tables -P OUTPUT DROP
ip6tables -A OUTPUT -o lo -j ACCEPT
"""

        // Create the script file
        val createCommand = "echo '${scriptContent.replace("'", "'\\''")}' > ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val createResult = executeCommand(createCommand)
        
        if (createResult.first != 0) {
            val error = "Failed to create boot script (exit code: ${createResult.first})"
            Log.e(TAG, error)
            return Result.failure(Exception(error))
        }
        
        Log.d(TAG, "✅ Boot script created successfully")

        // Set executable permissions (755)
        val chmodCommand = "chmod ${Constants.BootProtection.BOOT_SCRIPT_PERMISSIONS} ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val chmodResult = executeCommand(chmodCommand)
        
        if (chmodResult.first != 0) {
            val error = "Failed to set script permissions (exit code: ${chmodResult.first})"
            Log.e(TAG, error)
            return Result.failure(Exception(error))
        }
        
        Log.d(TAG, "✅ Script permissions set to ${Constants.BootProtection.BOOT_SCRIPT_PERMISSIONS}")
        Log.d(TAG, "✅ Boot protection enabled successfully")
        
        return Result.success(Unit)
    }

    /**
     * Delete the boot protection script.
     */
    private suspend fun deleteBootScript(): Result<Unit> {
        Log.d(TAG, "Deleting boot protection script...")

        val deleteCommand = "rm -f ${Constants.BootProtection.BOOT_SCRIPT_PATH}"
        val deleteResult = executeCommand(deleteCommand)
        
        if (deleteResult.first != 0) {
            val error = "Failed to delete boot script (exit code: ${deleteResult.first})"
            Log.e(TAG, error)
            return Result.failure(Exception(error))
        }
        
        Log.d(TAG, "✅ Boot script deleted successfully")
        Log.d(TAG, "✅ Boot protection disabled successfully")
        
        return Result.success(Unit)
    }

    /**
     * Reset iptables policies to ACCEPT after firewall starts.
     * This is called after boot when boot protection was enabled.
     */
    suspend fun resetIptablesPolicies(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Resetting iptables policies to ACCEPT...")

                // Reset IPv4 OUTPUT policy
                val ipv4Result = executeCommand("iptables -P OUTPUT ACCEPT")
                if (ipv4Result.first != 0) {
                    val error = "Failed to reset IPv4 OUTPUT policy: ${ipv4Result.second}"
                    Log.e(TAG, error)
                    return@withContext Result.failure(Exception(error))
                }

                // Reset IPv6 OUTPUT policy
                val ipv6Result = executeCommand("ip6tables -P OUTPUT ACCEPT")
                if (ipv6Result.first != 0) {
                    val error = "Failed to reset IPv6 OUTPUT policy: ${ipv6Result.second}"
                    Log.e(TAG, error)
                    return@withContext Result.failure(Exception(error))
                }

                Log.d(TAG, "✅ iptables policies reset to ACCEPT successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset iptables policies", e)
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

