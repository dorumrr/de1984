package io.github.dorumrr.de1984.data.worker

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.service.BackendMonitoringService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
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
            AppLogger.d(TAG, "🔄 BOOT WORKER STARTED | WorkManager-based boot restoration (Android 12+ compatible)")

            // Check if firewall was enabled before boot
            val prefs = applicationContext.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, Constants.Settings.DEFAULT_FIREWALL_ENABLED)

            AppLogger.d(TAG, "Firewall was enabled before boot: $wasEnabled")

            if (!wasEnabled) {
                AppLogger.d(TAG, "ℹ️  FIREWALL WAS NOT ENABLED | Skipping firewall restoration after boot")
                return Result.success()
            }

            AppLogger.d(TAG, "✅ Firewall was enabled - proceeding with restoration")

            // Get FirewallManager from application
            val app = applicationContext as? De1984Application
            if (app == null) {
                AppLogger.e(TAG, "❌ FAILED TO GET APPLICATION INSTANCE | Cannot restore firewall - application context not available")
                return Result.failure()
            }

            val firewallManager = app.dependencies.firewallManager
            val shizukuManager = app.dependencies.shizukuManager
            val rootManager = app.dependencies.rootManager

            // Request root permission FIRST to wake up Magisk
            // Magisk doesn't grant root permission until the app requests it after boot
            AppLogger.d(TAG, "Requesting root permission to wake up Magisk...")
            rootManager.forceRecheckRootStatus()

            // Small delay to allow Magisk to process the permission request
            delay(500)

            // Wait for Shizuku to be initialized before starting firewall
            // This is important after boot where Shizuku may not be fully initialized yet
            AppLogger.d(TAG, "Checking Shizuku status before starting firewall...")
            shizukuManager.checkShizukuStatus()

            // Small delay to ensure Shizuku is fully ready
            delay(500)

            AppLogger.d(TAG, "🚀 Starting firewall after boot...")
            val result = firewallManager.startFirewall()

            result.onSuccess { backendType ->
                AppLogger.d(TAG, "✅ FIREWALL RESTORED SUCCESSFULLY | Trigger: BOOT_COMPLETED (WorkManager) | Backend: $backendType")

                // Reset iptables policies if boot protection was enabled
                val bootProtectionEnabled = prefs.getBoolean(
                    Constants.Settings.KEY_BOOT_PROTECTION,
                    Constants.Settings.DEFAULT_BOOT_PROTECTION
                )
                if (bootProtectionEnabled) {
                    AppLogger.d(TAG, "Boot protection was enabled - resetting iptables policies to ACCEPT")
                    try {
                        val bootProtectionManager = app.dependencies.bootProtectionManager
                        val resetResult = bootProtectionManager.resetIptablesPolicies()
                        if (resetResult.isSuccess) {
                            AppLogger.d(TAG, "✅ iptables policies reset successfully")
                        } else {
                            AppLogger.e(TAG, "❌ Failed to reset iptables policies: ${resetResult.exceptionOrNull()?.message}")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "❌ Exception while resetting iptables policies", e)
                    }
                } else {
                    AppLogger.d(TAG, "Boot protection not enabled - skipping iptables policy reset")
                }

                // Check if we fell back to VPN and should start monitoring service
                if (backendType == FirewallBackendType.VPN) {
                    val currentMode = firewallManager.getCurrentMode()
                    val shizukuStatus = shizukuManager.shizukuStatus.value

                    // Only start monitoring if:
                    // 1. Mode is AUTO (not manually selected VPN)
                    // 2. Shizuku is installed but not running or no permission
                    val shouldMonitor = currentMode == FirewallMode.AUTO &&
                        (shizukuStatus == ShizukuStatus.INSTALLED_NOT_RUNNING ||
                         shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION)

                    if (shouldMonitor) {
                        AppLogger.d(TAG, "🔍 STARTING BACKEND MONITORING SERVICE | Reason: Firewall fell back to VPN (Shizuku not ready) | Shizuku status: $shizukuStatus | This service will automatically switch to ConnectivityManager | when Shizuku becomes available")

                        val monitorIntent = Intent(applicationContext, BackendMonitoringService::class.java).apply {
                            action = Constants.BackendMonitoring.ACTION_START
                            putExtra(Constants.BackendMonitoring.EXTRA_SHIZUKU_STATUS, shizukuStatus.name)
                        }

                        try {
                            applicationContext.startForegroundService(monitorIntent)
                            AppLogger.d(TAG, "✅ Backend monitoring service started successfully")
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "❌ Failed to start backend monitoring service: ${e.message}", e)
                        }
                    } else {
                        AppLogger.d(TAG, "Backend monitoring not needed. Mode: $currentMode, Shizuku: $shizukuStatus")
                    }
                }
            }.onFailure { error ->
                AppLogger.e(TAG, "❌ FAILED TO RESTORE FIREWALL | Trigger: BOOT_COMPLETED (WorkManager) | Error: ${error.message}")
                return Result.failure()
            }

            return Result.success()

        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ ERROR IN BOOT WORKER | Error: ${e.message}")
            AppLogger.e(TAG, "Stack trace:", e)
            return Result.failure()
        }
    }
}

