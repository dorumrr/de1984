package io.github.dorumrr.de1984.data.common

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class RootStatus {
    NOT_ROOTED,

    ROOTED_NO_PERMISSION,

    ROOTED_WITH_PERMISSION,

    CHECKING
}

class RootManager(private val context: Context) {

    companion object {
        private const val TAG = "RootManager"
        private const val PREFS_NAME = "de1984_root"
        private const val KEY_ROOT_PERMISSION_REQUESTED = "root_permission_requested"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _rootStatus = MutableStateFlow<RootStatus>(RootStatus.CHECKING)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    private var hasCheckedOnce = false

    val hasRootPermission: Boolean
        get() = _rootStatus.value == RootStatus.ROOTED_WITH_PERMISSION

    fun hasRequestedRootPermission(): Boolean {
        return prefs.getBoolean(KEY_ROOT_PERMISSION_REQUESTED, false)
    }

    fun markRootPermissionRequested() {
        prefs.edit().putBoolean(KEY_ROOT_PERMISSION_REQUESTED, true).apply()
    }

    suspend fun checkRootStatus() {
        val currentStatus = _rootStatus.value

        Log.d(TAG, "=== checkRootStatus() called ===")
        Log.d(TAG, "Current status: $currentStatus, hasCheckedOnce: $hasCheckedOnce")

        // Only skip check if we have definitive permission
        if (hasCheckedOnce && currentStatus == RootStatus.ROOTED_WITH_PERMISSION) {
            Log.d(TAG, "Skipping check - already have permission")
            return
        }

        if (!hasCheckedOnce) {
            _rootStatus.value = RootStatus.CHECKING
            Log.d(TAG, "First check - setting status to CHECKING")
        }

        val newStatus = checkRootStatusInternal()
        _rootStatus.value = newStatus
        hasCheckedOnce = true
        Log.d(TAG, "Root status check complete: $newStatus")
    }

    private suspend fun checkRootStatusInternal(): RootStatus = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for su binary...")
            // First, check if 'su' binary exists without executing it
            val suPaths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/su/bin/su",
                "/magisk/.core/bin/su"
            )

            val suExists = suPaths.any { path ->
                try {
                    java.io.File(path).exists()
                } catch (e: Exception) {
                    false
                }
            }

            if (!suExists) {
                Log.d(TAG, "No su binary found - device is NOT_ROOTED")
                return@withContext RootStatus.NOT_ROOTED
            }

            Log.d(TAG, "su binary found - testing root access...")
            // Now try to execute 'su' to check if we have permission
            // This will trigger permission dialog if not granted yet
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))

            val completed = kotlinx.coroutines.withTimeoutOrNull(3000) {
                process.waitFor()
            }

            if (completed == null) {
                Log.d(TAG, "Root check timed out - ROOTED_NO_PERMISSION")
                // Drain streams before destroying (best practice)
                try {
                    process.inputStream.bufferedReader().use { it.readText() }
                    process.errorStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    // Ignore stream read errors on timeout
                }
                process.destroy()
                return@withContext RootStatus.ROOTED_NO_PERMISSION
            }

            if (completed == 0) {
                // Read both streams to prevent blocking
                val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                process.errorStream.bufferedReader().use { it.readText() } // Drain error stream
                process.destroy()

                val hasPermission = output.contains("uid=0")
                Log.d(TAG, "Root check exit code 0, output: $output, hasPermission: $hasPermission")
                return@withContext if (hasPermission) {
                    RootStatus.ROOTED_WITH_PERMISSION
                } else {
                    RootStatus.ROOTED_NO_PERMISSION
                }
            } else {
                Log.d(TAG, "Root check failed with exit code: $completed - ROOTED_NO_PERMISSION")
                // Drain streams before destroying
                process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }
                process.destroy()
                return@withContext RootStatus.ROOTED_NO_PERMISSION
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during root check: ${e.message}", e)
            // Exception during check - assume no permission
            return@withContext RootStatus.ROOTED_NO_PERMISSION
        }
    }
    
    suspend fun executeRootCommand(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!hasRootPermission) {
            return@withContext Pair(-1, "No root permission")
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

            // Read both output and error streams to prevent blocking
            // If error stream is not read, the process can block when stderr buffer fills up
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val error = process.errorStream.bufferedReader().use { it.readText().trim() }

            val exitCode = process.waitFor()

            // Explicitly destroy process to ensure cleanup
            process.destroy()

            // Return stdout if available, otherwise stderr
            val result = if (output.isNotEmpty()) output else error
            Pair(exitCode, result)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }
}

