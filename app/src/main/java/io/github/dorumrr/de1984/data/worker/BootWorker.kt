package io.github.dorumrr.de1984.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.delay

/**
 * WorkManager worker that restores firewall state after device boot.
 * This is the Android 12+ (API 31+) compatible way to handle boot persistence.
 * 
 * WorkManager advantages over BroadcastReceiver:
 * - Works on Android 12+ where foreground service restrictions apply
 * - Not affected by battery optimization
 * - Guaranteed to run even if app is not in foreground
 * - Can properly start foreground services
 * 
 * Per FIREWALL.md: Firewall must survive device restarts.
 */
class BootWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "De1984.BootWorker"
        const val WORK_NAME = "boot_restore_firewall"
    }

    override suspend fun doWork(): Result {
        try {
            Log.d(TAG, "")
            Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
            Log.d(TAG, "║  🔄 BOOT WORKER STARTED                                      ║")
            Log.d(TAG, "║  WorkManager-based boot restoration (Android 12+ compatible) ║")
            Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")
            Log.d(TAG, "")

            // Check if firewall was enabled before boot
            val prefs = applicationContext.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, Constants.Settings.DEFAULT_FIREWALL_ENABLED)

            Log.d(TAG, "Firewall was enabled before boot: $wasEnabled")

            if (!wasEnabled) {
                Log.d(TAG, "")
                Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
                Log.d(TAG, "║  ℹ️  FIREWALL WAS NOT ENABLED                                ║")
                Log.d(TAG, "║  Skipping firewall restoration after boot                   ║")
                Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")
                Log.d(TAG, "")
                return Result.success()
            }

            Log.d(TAG, "✅ Firewall was enabled - proceeding with restoration")

            // Get FirewallManager from application
            val app = applicationContext as? De1984Application
            if (app == null) {
                Log.e(TAG, "")
                Log.e(TAG, "╔════════════════════════════════════════════════════════════════╗")
                Log.e(TAG, "║  ❌ FAILED TO GET APPLICATION INSTANCE                       ║")
                Log.e(TAG, "║  Cannot restore firewall - application context not available ║")
                Log.e(TAG, "╚════════════════════════════════════════════════════════════════╝")
                Log.e(TAG, "")
                return Result.failure()
            }

            val firewallManager = app.dependencies.firewallManager
            val shizukuManager = app.dependencies.shizukuManager
            val rootManager = app.dependencies.rootManager

            // Request root permission FIRST to wake up Magisk
            // Magisk doesn't grant root permission until the app requests it after boot
            Log.d(TAG, "Requesting root permission to wake up Magisk...")
            rootManager.forceRecheckRootStatus()

            // Small delay to allow Magisk to process the permission request
            delay(500)

            // Wait for Shizuku to be initialized before starting firewall
            // This is important after boot where Shizuku may not be fully initialized yet
            Log.d(TAG, "Checking Shizuku status before starting firewall...")
            shizukuManager.checkShizukuStatus()

            // Small delay to ensure Shizuku is fully ready
            delay(500)

            Log.d(TAG, "🚀 Starting firewall after boot...")
            val result = firewallManager.startFirewall()
            
            result.onSuccess { backendType ->
                Log.d(TAG, "")
                Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
                Log.d(TAG, "║  ✅ FIREWALL RESTORED SUCCESSFULLY                           ║")
                Log.d(TAG, "║  Trigger: BOOT_COMPLETED (WorkManager)                       ║")
                Log.d(TAG, "║  Backend: $backendType")
                Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")
                Log.d(TAG, "")
            }.onFailure { error ->
                Log.e(TAG, "")
                Log.e(TAG, "╔════════════════════════════════════════════════════════════════╗")
                Log.e(TAG, "║  ❌ FAILED TO RESTORE FIREWALL                               ║")
                Log.e(TAG, "║  Trigger: BOOT_COMPLETED (WorkManager)                       ║")
                Log.e(TAG, "║  Error: ${error.message}")
                Log.e(TAG, "╚════════════════════════════════════════════════════════════════╝")
                Log.e(TAG, "")
                return Result.failure()
            }

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "")
            Log.e(TAG, "╔════════════════════════════════════════════════════════════════╗")
            Log.e(TAG, "║  ❌ ERROR IN BOOT WORKER                                     ║")
            Log.e(TAG, "║  Error: ${e.message}")
            Log.e(TAG, "╚════════════════════════════════════════════════════════════════╝")
            Log.e(TAG, "")
            Log.e(TAG, "Stack trace:", e)
            return Result.failure()
        }
    }
}

