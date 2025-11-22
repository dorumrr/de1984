package io.github.dorumrr.de1984.data.common

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.topjohnwu.superuser.Shell
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

    /**
     * Public root status check used by UI and privilege banners.
     *
     * Optimized to avoid hammering Magisk/Shizuku once we have a stable
     * ROOTED_WITH_PERMISSION state. Other states are re-checked so the app
     * can recover when a user later grants permission.
     */
    suspend fun checkRootStatus() {
        checkRootStatusInternalWithCaching(forceRecheck = false)
    }

    /**
     * Internal helper that allows callers (like backend health monitoring)
     * to force a re-check even if we previously had ROOTED_WITH_PERMISSION.
     */
    suspend fun forceRecheckRootStatus() {
        checkRootStatusInternalWithCaching(forceRecheck = true)
    }

    private suspend fun checkRootStatusInternalWithCaching(forceRecheck: Boolean) {
        val currentStatus = _rootStatus.value

        Log.d(TAG, "=== checkRootStatusInternalWithCaching() called ===")
        Log.d(TAG, "Current status: $currentStatus, hasCheckedOnce: $hasCheckedOnce, forceRecheck: $forceRecheck")

        // Only skip check if we have definitive permission AND caller did not
        // explicitly request a re-check (e.g., health monitoring after root
        // revocation from Magisk).
        if (!forceRecheck && hasCheckedOnce && currentStatus == RootStatus.ROOTED_WITH_PERMISSION) {
            Log.d(TAG, "Skipping check - already have permission and no forceRecheck")
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
            Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
            Log.d(TAG, "║  🔍 CHECKING ROOT STATUS (using libsu)                       ║")
            Log.d(TAG, "║  Creating NEW root shell to request permission...           ║")
            Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")

            // Use libsu to check root status
            // CRITICAL: We must use Shell.Builder.build() to create a NEW shell instance
            // Shell.getShell() returns a cached shell, which won't detect permission changes
            // This ensures we always get fresh root status from Magisk
            val shell = Shell.Builder.create().build()

            return@withContext if (shell.isRoot) {
                Log.d(TAG, "✅ Root access GRANTED - ROOTED_WITH_PERMISSION")
                RootStatus.ROOTED_WITH_PERMISSION
            } else {
                Log.e(TAG, "❌ Root access DENIED or not available - NOT_ROOTED")
                RootStatus.NOT_ROOTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during root check: ${e.message}", e)
            return@withContext RootStatus.NOT_ROOTED
        }
    }

    suspend fun executeRootCommand(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!hasRootPermission) {
            return@withContext Pair(-1, "No root permission")
        }

        try {
            // Use libsu to execute root commands
            val result = Shell.cmd(command).exec()

            // Get the output (stdout + stderr combined)
            val output = result.out.joinToString("\n")

            // Return exit code and output
            Pair(result.code, output)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }
}

