package io.github.dorumrr.de1984.data.service

import io.github.dorumrr.de1984.utils.AppLogger
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.firewall.FirewallManager
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that monitors Shizuku availability and automatically switches
 * firewall backend from VPN to privileged backend (iptables/ConnectivityManager) when
 * Shizuku becomes available after device boot.
 * 
 * This service is started by BootReceiver when:
 * 1. Firewall falls back to VPN at boot (Shizuku not ready)
 * 2. Firewall mode is AUTO (not manually selected VPN)
 * 3. Shizuku is installed but not running or permission not granted
 * 
 * The service:
 * - Shows a dismissible notification explaining the situation
 * - Monitors Shizuku status changes via StateFlow
 * - Automatically attempts backend switch when Shizuku becomes available
 * - Provides manual retry option via notification action button
 * - Shows toast and updates notification on success/failure
 * - Stops itself after successful backend switch or timeout
 */
class BackendMonitoringService : Service() {

    companion object {
        private const val TAG = "BackendMonitoringService"
    }

    private lateinit var firewallManager: FirewallManager
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var notificationManager: NotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitoringJob: Job? = null
    private var backendMonitoringJob: Job? = null
    private var timeoutJob: Job? = null

    private var isAttemptingSwitch = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.d(TAG, "Service created")

        val app = application as De1984Application
        val deps = app.dependencies
        firewallManager = deps.firewallManager
        shizukuManager = deps.shizukuManager
        notificationManager = getSystemService(NotificationManager::class.java)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            Constants.BackendMonitoring.ACTION_START -> {
                val shizukuStatusName = intent.getStringExtra(Constants.BackendMonitoring.EXTRA_SHIZUKU_STATUS)
                AppLogger.d(TAG, "Starting monitoring service. Shizuku status: $shizukuStatusName")
                
                startMonitoring()
            }
            Constants.BackendMonitoring.ACTION_RETRY -> {
                AppLogger.d(TAG, "Manual retry requested")
                serviceScope.launch {
                    attemptBackendSwitch()
                }
            }
            Constants.BackendMonitoring.ACTION_STOP -> {
                AppLogger.d(TAG, "Stop action received")
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLogger.d(TAG, "Service destroyed")
        monitoringJob?.cancel()
        backendMonitoringJob?.cancel()
        timeoutJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.BackendMonitoring.CHANNEL_ID,
            Constants.BackendMonitoring.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors backend availability and switches when Shizuku becomes available"
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun startMonitoring() {
        // Register Shizuku listeners if not already registered
        shizukuManager.registerListeners()

        // Start foreground service with initial notification
        val initialNotification = createWaitingNotification(shizukuManager.shizukuStatus.value)
        startForeground(Constants.BackendMonitoring.NOTIFICATION_ID, initialNotification)

        // Monitor Shizuku status changes
        monitoringJob = serviceScope.launch {
            shizukuManager.shizukuStatus.collect { status ->
                AppLogger.d(TAG, "Shizuku status changed: $status")
                
                when (status) {
                    ShizukuStatus.RUNNING_WITH_PERMISSION -> {
                        // Shizuku is ready! Try to switch backend
                        if (!isAttemptingSwitch) {
                            attemptBackendSwitch()
                        }
                    }
                    ShizukuStatus.INSTALLED_NOT_RUNNING,
                    ShizukuStatus.RUNNING_NO_PERMISSION -> {
                        // Update notification to reflect current status
                        updateWaitingNotification(status)
                    }
                    ShizukuStatus.NOT_INSTALLED -> {
                        // Shizuku not installed - start timeout
                        startTimeoutTimer()
                    }
                    ShizukuStatus.CHECKING -> {
                        // Status being checked, wait
                    }
                }
            }
        }

        // Monitor active backend type changes
        backendMonitoringJob = serviceScope.launch {
            firewallManager.activeBackendType.collect { backendType ->
                AppLogger.d(TAG, "Active backend changed: $backendType")

                // Don't stop during backend switch attempt - let attemptBackendSwitch() handle it
                if (!isAttemptingSwitch) {
                    // Check if we should continue monitoring
                    if (!shouldContinueMonitoring()) {
                        AppLogger.d(TAG, "Monitoring no longer needed. Stopping service.")
                        stopSelf()
                    }
                } else {
                    AppLogger.d(TAG, "Backend switch in progress, not stopping service yet")
                }
            }
        }

        // Start timeout if Shizuku not installed
        if (shizukuManager.shizukuStatus.value == ShizukuStatus.NOT_INSTALLED) {
            startTimeoutTimer()
        }
    }

    private fun shouldContinueMonitoring(): Boolean {
        val currentMode = firewallManager.getCurrentMode()
        val activeBackend = firewallManager.activeBackendType.value

        // Continue monitoring if:
        // 1. Mode is AUTO (not manually selected VPN)
        // 2. Backend is VPN (waiting for better backend) OR null (switching backends)
        // Note: null backend means atomic switch in progress - don't stop during switch!
        val shouldContinue = currentMode == FirewallMode.AUTO &&
            (activeBackend == FirewallBackendType.VPN || activeBackend == null)

        AppLogger.d(TAG, "shouldContinueMonitoring: mode=$currentMode, backend=$activeBackend, result=$shouldContinue")
        return shouldContinue
    }

    private fun startTimeoutTimer() {
        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            AppLogger.d(TAG, "Starting timeout timer: ${Constants.BackendMonitoring.TIMEOUT_NO_SHIZUKU_MS}ms")
            delay(Constants.BackendMonitoring.TIMEOUT_NO_SHIZUKU_MS)
            AppLogger.d(TAG, "Timeout reached. Stopping service.")
            stopSelf()
        }
    }

    private suspend fun attemptBackendSwitch() {
        if (isAttemptingSwitch) {
            AppLogger.d(TAG, "Backend switch already in progress, skipping")
            return
        }

        isAttemptingSwitch = true
        AppLogger.d(TAG, "Attempting backend switch...")

        // Update notification to show switching state
        updateSwitchingNotification()

        try {
            val result = firewallManager.startFirewall()
            
            result.onSuccess { backendType ->
                AppLogger.d(TAG, "Backend switch result: $backendType")
                
                if (backendType != FirewallBackendType.VPN) {
                    // Success! Switched away from VPN
                    handleSwitchSuccess(backendType)
                } else {
                    // Still VPN - Shizuku available but backend didn't switch
                    // This can happen if Shizuku permission was revoked
                    handleSwitchFailure()
                }
            }.onFailure { error ->
                AppLogger.e(TAG, "Backend switch failed", error)
                handleSwitchFailure()
            }
        } finally {
            isAttemptingSwitch = false
        }
    }

    private suspend fun handleSwitchSuccess(backendType: FirewallBackendType) {
        val toastMessage = when (backendType) {
            FirewallBackendType.CONNECTIVITY_MANAGER ->
                getString(R.string.backend_toast_success_connectivity_manager)
            FirewallBackendType.IPTABLES ->
                getString(R.string.backend_toast_success_iptables)
            else -> "Firewall switched to $backendType"
        }

        val notificationText = when (backendType) {
            FirewallBackendType.CONNECTIVITY_MANAGER ->
                getString(R.string.backend_notification_text_success_connectivity_manager)
            FirewallBackendType.IPTABLES ->
                getString(R.string.backend_notification_text_success_iptables)
            else -> "Now using $backendType backend"
        }

        AppLogger.d(TAG, "Backend switch successful: $backendType")

        // Show toast
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()

        // Update notification
        showSuccessNotification(notificationText)

        // Stop service after delay
        delay(Constants.BackendMonitoring.SUCCESS_NOTIFICATION_DURATION_MS)
        stopSelf()
    }

    private fun handleSwitchFailure() {
        AppLogger.e(TAG, "Backend switch failed")

        // Show toast
        Toast.makeText(this, getString(R.string.backend_toast_failed), Toast.LENGTH_SHORT).show()

        // Update notification
        showFailureNotification()
    }

    private fun createWaitingNotification(shizukuStatus: ShizukuStatus): Notification {
        val text = when (shizukuStatus) {
            ShizukuStatus.INSTALLED_NOT_RUNNING ->
                getString(R.string.backend_notification_text_shizuku_not_running)
            ShizukuStatus.RUNNING_NO_PERMISSION ->
                getString(R.string.backend_notification_text_shizuku_no_permission)
            else ->
                getString(R.string.backend_notification_text_shizuku_not_running)
        }

        return buildNotification(
            title = getString(R.string.backend_notification_title_waiting),
            text = text,
            ongoing = false,
            priority = NotificationCompat.PRIORITY_LOW,
            withRetryAction = true
        )
    }

    private fun updateWaitingNotification(shizukuStatus: ShizukuStatus) {
        val notification = createWaitingNotification(shizukuStatus)
        notificationManager.notify(Constants.BackendMonitoring.NOTIFICATION_ID, notification)
    }

    private fun updateSwitchingNotification() {
        val notification = buildNotification(
            title = getString(R.string.backend_notification_title_switching),
            text = getString(R.string.backend_notification_text_switching),
            ongoing = false,
            priority = NotificationCompat.PRIORITY_LOW,
            withRetryAction = false
        )
        notificationManager.notify(Constants.BackendMonitoring.NOTIFICATION_ID, notification)
    }

    private fun showSuccessNotification(text: String) {
        val notification = buildNotification(
            title = getString(R.string.backend_notification_title_success),
            text = text,
            ongoing = false,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            withRetryAction = false,
            autoCancel = true
        )
        notificationManager.notify(Constants.BackendMonitoring.NOTIFICATION_ID, notification)
    }

    private fun showFailureNotification() {
        val notification = buildNotification(
            title = getString(R.string.backend_notification_title_failed),
            text = getString(R.string.backend_notification_text_failed),
            ongoing = false,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            withRetryAction = true,
            autoCancel = true
        )
        notificationManager.notify(Constants.BackendMonitoring.NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        title: String,
        text: String,
        ongoing: Boolean,
        priority: Int,
        withRetryAction: Boolean,
        autoCancel: Boolean = false
    ): Notification {
        // Tap notification to open app
        val tapIntent = Intent(this, MainActivity::class.java)
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, Constants.BackendMonitoring.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_de1984)
            .setContentIntent(tapPendingIntent)
            .setOngoing(ongoing)
            .setPriority(priority)
            .setAutoCancel(autoCancel)

        // Add retry action button if requested
        if (withRetryAction) {
            val retryIntent = Intent(this, BackendMonitoringService::class.java).apply {
                action = Constants.BackendMonitoring.ACTION_RETRY
            }
            val retryPendingIntent = PendingIntent.getService(
                this,
                0,
                retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                R.drawable.ic_signal_cellular_off,  // Reuse existing icon
                getString(R.string.backend_action_button_retry),
                retryPendingIntent
            )
        }

        return builder.build()
    }
}

