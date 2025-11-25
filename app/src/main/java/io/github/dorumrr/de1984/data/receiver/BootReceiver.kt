package io.github.dorumrr.de1984.data.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.service.BackendMonitoringService
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.data.worker.BootWorker
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Broadcast receiver that restores firewall state after device boot or app update.
 *
 * On Android 12+ (API 31+), this receiver schedules a WorkManager job to handle
 * firewall restoration, as direct foreground service starts from boot receivers
 * are restricted.
 *
 * On Android 11 and below, this receiver directly starts the firewall.
 *
 * Per FIREWALL.md: Firewall must survive device restarts.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "De1984.BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action

        Log.d(TAG, "")
        Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║  🔄 BOOT RECEIVER TRIGGERED                                  ║")
        Log.d(TAG, "║  Action: $action")
        Log.d(TAG, "║  Android Version: ${Build.VERSION.SDK_INT} (API ${Build.VERSION.SDK_INT})")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")
        Log.d(TAG, "")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val bootType = if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                    "LOCKED_BOOT_COMPLETED (before user unlock)"
                } else {
                    "BOOT_COMPLETED (after user unlock)"
                }
                Log.d(TAG, "📱 Device boot completed - $bootType")

                // Android 12+ (API 31+): Use WorkManager to avoid foreground service restrictions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Log.d(TAG, "Android 12+ detected - scheduling WorkManager job for firewall restoration")
                    scheduleBootWorker(context)
                } else {
                    Log.d(TAG, "Android 11 or below - directly restoring firewall state")
                    restoreFirewallState(context, bootType)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "📦 App package replaced - checking if firewall should be restored")

                // For app updates, we can directly restore on all Android versions
                // as the app is already in foreground context
                restoreFirewallState(context, "PACKAGE_REPLACED")
            }
            else -> {
                Log.w(TAG, "⚠️ Unknown action received: $action")
            }
        }

    }

    /**
     * Schedule a WorkManager job to restore firewall state.
     * This is the Android 12+ compatible way to handle boot persistence.
     * Falls back to direct restoration if WorkManager is not initialized.
     */
    private fun scheduleBootWorker(context: Context) {
        try {
            Log.d(TAG, "Scheduling BootWorker...")

            val workRequest = OneTimeWorkRequestBuilder<BootWorker>()
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                BootWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            Log.d(TAG, "✅ BootWorker scheduled successfully")

        } catch (e: IllegalStateException) {
            // WorkManager not initialized yet (can happen at boot time)
            // Fall back to direct restoration
            Log.e(TAG, "❌ Failed to schedule BootWorker: WorkManager not initialized", e)
            Log.d(TAG, "⚠️ Falling back to direct firewall restoration")
            restoreFirewallState(context, "BOOT_COMPLETED (WorkManager fallback)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to schedule BootWorker", e)
        }
    }

    private fun restoreFirewallState(context: Context, trigger: String) {
        try {
            Log.d(TAG, "restoreFirewallState: trigger=$trigger")

            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, Constants.Settings.DEFAULT_FIREWALL_ENABLED)

            Log.d(TAG, "Firewall was enabled before $trigger: $wasEnabled")

            if (wasEnabled) {
                Log.d(TAG, "✅ Firewall was enabled - proceeding with restoration")

                // Get FirewallManager from application
                val app = context.applicationContext as? De1984Application
                if (app != null) {
                    val firewallManager = app.dependencies.firewallManager
                    val shizukuManager = app.dependencies.shizukuManager
                    val rootManager = app.dependencies.rootManager

                    // Use goAsync() to keep receiver alive while coroutine runs
                    val pendingResult = goAsync()

                    // Use coroutine to start firewall asynchronously
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    scope.launch {
                        try {
                            // CRITICAL: Request root permission FIRST to wake up Magisk
                            // Magisk doesn't grant root permission until the app requests it after boot/update
                            // Without this, FirewallManager.selectBackend() will think root is not available
                            // and fall back to VPN backend, which kills user's third-party VPN (like Proton VPN)
                            Log.d(TAG, "Requesting root permission to wake up Magisk...")
                            rootManager.forceRecheckRootStatus()

                            // Small delay to allow Magisk to process the permission request
                            kotlinx.coroutines.delay(500)

                            // Wait for Shizuku to be initialized before starting firewall
                            // This is important for ACTION_MY_PACKAGE_REPLACED (app update)
                            // where Shizuku may not be fully initialized yet
                            Log.d(TAG, "Checking Shizuku status before starting firewall...")
                            shizukuManager.checkShizukuStatus()

                            // Small delay to ensure Shizuku is fully ready
                            kotlinx.coroutines.delay(500)

                            Log.d(TAG, "🚀 Starting firewall after $trigger...")
                            val result = firewallManager.startFirewall()
                            result.onSuccess { backendType ->
                                Log.d(TAG, "")
                                Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
                                Log.d(TAG, "║  ✅ FIREWALL RESTORED SUCCESSFULLY                           ║")
                                Log.d(TAG, "║  Trigger: $trigger")
                                Log.d(TAG, "║  Backend: $backendType")
                                Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")
                                Log.d(TAG, "")

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
                                        Log.d(TAG, "Started with VPN fallback (Shizuku status: $shizukuStatus). Starting backend monitoring service...")
                                        val monitorIntent = Intent(context, BackendMonitoringService::class.java).apply {
                                            action = Constants.BackendMonitoring.ACTION_START
                                            putExtra(Constants.BackendMonitoring.EXTRA_SHIZUKU_STATUS, shizukuStatus.name)
                                        }

                                        try {
                                            context.startForegroundService(monitorIntent)
                                            Log.d(TAG, "Backend monitoring service started successfully")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to start backend monitoring service", e)
                                        }
                                    } else {
                                        Log.d(TAG, "Backend monitoring not needed. Mode: $currentMode, Shizuku: $shizukuStatus")
                                    }
                                }
                            }.onFailure { error ->
                                Log.e(TAG, "")
                                Log.e(TAG, "╔════════════════════════════════════════════════════════════════╗")
                                Log.e(TAG, "║  ❌ FAILED TO RESTORE FIREWALL                               ║")
                                Log.e(TAG, "║  Trigger: $trigger")
                                Log.e(TAG, "║  Error: ${error.message}")
                                Log.e(TAG, "╚════════════════════════════════════════════════════════════════╝")
                                Log.e(TAG, "")

                                // Show notification asking user to open app
                                // (VPN permission likely needs to be re-granted)
                                showBootFailureNotification(context)
                            }
                        } finally {
                            // Signal that async work is complete
                            pendingResult.finish()
                        }
                    }
                } else {
                    Log.e(TAG, "")
                    Log.e(TAG, "╔════════════════════════════════════════════════════════════════╗")
                    Log.e(TAG, "║  ❌ FAILED TO GET APPLICATION INSTANCE                       ║")
                    Log.e(TAG, "║  Cannot restore firewall - application context not available ║")
                    Log.e(TAG, "╚════════════════════════════════════════════════════════════════╝")
                    Log.e(TAG, "")

                    // Fallback to VPN service for backward compatibility
                    Log.d(TAG, "Attempting fallback to VPN service...")
                    val serviceIntent = Intent(context, FirewallVpnService::class.java).apply {
                        action = FirewallVpnService.ACTION_START
                    }

                    try {
                        context.startService(serviceIntent)
                        Log.d(TAG, "✅ VPN service started successfully (fallback)")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to start VPN service (fallback)", e)
                    }
                }
            } else {
                Log.d(TAG, "")
                Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
                Log.d(TAG, "║  ℹ️  FIREWALL WAS NOT ENABLED                                ║")
                Log.d(TAG, "║  Skipping firewall restoration after $trigger")
                Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")
                Log.d(TAG, "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "")
            Log.e(TAG, "╔════════════════════════════════════════════════════════════════╗")
            Log.e(TAG, "║  ❌ ERROR IN BOOT RECEIVER                                   ║")
            Log.e(TAG, "║  Trigger: $trigger")
            Log.e(TAG, "║  Error: ${e.message}")
            Log.e(TAG, "╚════════════════════════════════════════════════════════════════╝")
            Log.e(TAG, "")
            Log.e(TAG, "Stack trace:", e)
        }
    }

    /**
     * Show notification asking user to open the app when firewall fails to start at boot.
     * This typically happens when VPN permission needs to be re-granted.
     */
    private fun showBootFailureNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Create notification channel (required for Android 8.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    Constants.BootFailure.CHANNEL_ID,
                    Constants.BootFailure.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications when firewall fails to start at boot"
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Create intent to open the app and trigger firewall recovery
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                action = Constants.Notifications.ACTION_BOOT_FAILURE_RECOVERY
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Build notification
            val notification = NotificationCompat.Builder(context, Constants.BootFailure.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Firewall failed to start")
                .setContentText("Tap to open De1984 and grant VPN permission")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("The firewall could not start after boot. This usually happens when VPN permission needs to be re-granted. Tap to open De1984 and enable the firewall."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(Constants.BootFailure.NOTIFICATION_ID, notification)
            Log.d(TAG, "Boot failure notification shown")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show boot failure notification", e)
        }
    }
}

