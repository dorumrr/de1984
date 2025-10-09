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

        // Only skip check if we have definitive permission
        if (hasCheckedOnce && currentStatus == RootStatus.ROOTED_WITH_PERMISSION) {
            Log.d(TAG, "Skipping root check - already have permission")
            return
        }

        if (!hasCheckedOnce) {
            _rootStatus.value = RootStatus.CHECKING
        }

        val newStatus = checkRootStatusInternal()
        Log.d(TAG, "Root status check result: $newStatus")
        _rootStatus.value = newStatus
        hasCheckedOnce = true
    }

    private suspend fun checkRootStatusInternal(): RootStatus = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting root status check...")

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
                Log.d(TAG, "No 'su' binary found - device is not rooted")
                return@withContext RootStatus.NOT_ROOTED
            }

            Log.d(TAG, "'su' binary found - device is rooted")

            // Now try to execute 'su' to check if we have permission
            // This will trigger permission dialog if not granted yet
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            Log.d(TAG, "Executed 'su -c id' command")

            val completed = kotlinx.coroutines.withTimeoutOrNull(3000) {
                process.waitFor()
            }

            if (completed == null) {
                Log.d(TAG, "Root check timed out - likely waiting for user permission or permission denied")
                process.destroy()
                return@withContext RootStatus.ROOTED_NO_PERMISSION
            }

            Log.d(TAG, "Process completed with exit code: $completed")

            if (completed == 0) {
                val output = process.inputStream.bufferedReader().readText().trim()
                Log.d(TAG, "Root check output: $output")
                return@withContext if (output.contains("uid=0")) {
                    Log.d(TAG, "Root access granted - uid=0 found")
                    RootStatus.ROOTED_WITH_PERMISSION
                } else {
                    Log.d(TAG, "Root check succeeded but uid=0 not found")
                    RootStatus.ROOTED_NO_PERMISSION
                }
            } else {
                Log.d(TAG, "Root check failed with exit code: $completed")
                val errorOutput = process.errorStream.bufferedReader().readText().trim()
                Log.d(TAG, "Error output: $errorOutput")
                return@withContext RootStatus.ROOTED_NO_PERMISSION
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root check exception: ${e.message}", e)
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
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText().trim()
            Pair(exitCode, output)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }
}

