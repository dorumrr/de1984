package io.github.dorumrr.de1984.data.worker

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.service.BackendMonitoringService
import io.github.dorumrr.de1984.data.service.PackageMonitoringService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.delay

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

            val prefs = applicationContext.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, Constants.Settings.DEFAULT_FIREWALL_ENABLED)

            AppLogger.d(TAG, "Firewall was enabled before boot: $wasEnabled")

            val app = applicationContext as? De1984Application
            if (app == null) {
                AppLogger.e(TAG, "❌ FAILED TO GET APPLICATION INSTANCE | Cannot restore firewall - application context not available")
                return Result.failure()
            }

            // The boot block is lifted once the outcome is known, never before, or the restore window
            // goes unprotected. The app == null exit above cannot lift it; the script's timer does.
            if (!wasEnabled) {
                AppLogger.d(TAG, "ℹ️  FIREWALL WAS NOT ENABLED | Nothing will take over - lifting any boot block")
                app.dependencies.bootProtectionManager.clearBootBlockIfInstalled()
                return Result.success()
            }

            AppLogger.d(TAG, "✅ Firewall was enabled - proceeding with restoration")

            val firewallManager = app.dependencies.firewallManager
            val shizukuManager = app.dependencies.shizukuManager
            val rootManager = app.dependencies.rootManager

            // Request root permission FIRST to wake up Magisk
            // Magisk doesn't grant root permission until the app requests it after boot
            AppLogger.d(TAG, "Requesting root permission to wake up Magisk...")
            rootManager.forceRecheckRootStatus()

            delay(500)

            // Wait for Shizuku to be initialized before starting firewall
            // This is important after boot where Shizuku may not be fully initialized yet
            AppLogger.d(TAG, "Checking Shizuku status before starting firewall...")
            shizukuManager.checkShizukuStatus()

            delay(500)

            AppLogger.d(TAG, "🚀 Starting firewall after boot...")
            val result = firewallManager.startFirewall()

            result.onSuccess { backendType ->
                AppLogger.d(TAG, "✅ FIREWALL RESTORED SUCCESSFULLY | Trigger: BOOT_COMPLETED (WorkManager) | Backend: $backendType")

                // Protection has been handed over - lift the block.
                //
                // Keyed on the script being on disk, not on KEY_BOOT_PROTECTION. That preference is
                // reset by clearing app data while the script stays installed, so gating on it was a
                // second and less reliable source of truth for the same question.
                AppLogger.d(TAG, "Firewall is up - lifting any boot protection block")
                try {
                    val resetResult = app.dependencies.bootProtectionManager.clearBootBlockIfInstalled()
                    if (resetResult.isSuccess) {
                        AppLogger.d(TAG, "✅ Boot protection block lifted (or none present)")
                    } else {
                        AppLogger.e(TAG, "❌ Failed to lift boot protection block: ${resetResult.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "❌ Exception while lifting boot protection block", e)
                }

                // Start the cross-profile watcher too. It was only ever started
                // from MainActivity, so after a reboot nothing looked at other user
                // profiles until the user happened to open the app - and a
                // work-profile app installed in the meantime got no rule at all.
                //
                // Only on the success path, and deliberately so: this is a plain
                // background service, and starting one is legal here ONLY because
                // the firewall just brought PrivilegedFirewallService up in the
                // foreground. With the firewall off there is no foreground service,
                // the start would throw, and the process would be killed shortly
                // after anyway - so there would be nothing to keep running.
                try {
                    PackageMonitoringService.startMonitoring(applicationContext)
                    AppLogger.d(TAG, "Cross-profile package watcher started")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not start the package watcher: ${e.message}")
                }

                if (backendType == FirewallBackendType.VPN) {
                    val currentMode = firewallManager.getCurrentMode()
                    val shizukuStatus = shizukuManager.shizukuStatus.value

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

                // A failed start must not leave the block standing. Protection is gone either way at
                // this point; keeping it only takes the device offline until the script's own timer
                // fires, with no in-app way out. Same rule as BootReceiver's failure path.
                try {
                    app.dependencies.bootProtectionManager.clearBootBlockIfInstalled()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to lift boot protection block after a failed start", e)
                }

                return Result.failure()
            }

            return Result.success()

        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ ERROR IN BOOT WORKER | Error: ${e.message}")
            AppLogger.e(TAG, "Stack trace:", e)

            // A throw anywhere above would otherwise leave the device blocked until the script's own
            // timer fires.
            try {
                (applicationContext as? De1984Application)
                    ?.dependencies?.bootProtectionManager?.clearBootBlockIfInstalled()
            } catch (inner: Exception) {
                AppLogger.e(TAG, "Failed to lift boot protection block after a worker error", inner)
            }

            return Result.failure()
        }
    }
}

