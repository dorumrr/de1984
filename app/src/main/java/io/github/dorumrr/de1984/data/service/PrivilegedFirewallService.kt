package io.github.dorumrr.de1984.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.firewall.ConnectivityManagerFirewallBackend
import io.github.dorumrr.de1984.data.firewall.IptablesFirewallBackend
import io.github.dorumrr.de1984.data.firewall.NetworkPolicyManagerFirewallBackend
import io.github.dorumrr.de1984.data.monitor.NetworkStateMonitor
import io.github.dorumrr.de1984.data.monitor.ScreenStateMonitor
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service for privileged firewall backends (iptables, ConnectivityManager, NetworkPolicyManager).
 * 
 * This service keeps the app process alive to maintain:
 * - Backend health monitoring
 * - Network/screen state monitoring
 * - Rule application on state changes
 * - State persistence across process death
 * 
 * Follows the same pattern as FirewallVpnService but for privileged backends.
 */
class PrivilegedFirewallService : Service() {

    private lateinit var firewallRepository: FirewallRepository
    private lateinit var networkStateMonitor: NetworkStateMonitor
    private lateinit var screenStateMonitor: ScreenStateMonitor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var healthMonitoringJob: Job? = null
    private var ruleApplicationJob: Job? = null

    private var currentBackend: FirewallBackend? = null
    private var currentBackendType: FirewallBackendType? = null
    private var isServiceActive = false
    private var wasExplicitlyStopped = false

    private var currentNetworkType: NetworkType = NetworkType.NONE
    private var isScreenOn: Boolean = true

    private val rulesChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED") {
                if (isServiceActive) {
                    scheduleRuleApplication()
                }
            }
        }
    }

    companion object {
        private const val TAG = "PrivilegedFirewallService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "firewall_privileged_channel"
        private const val CHANNEL_NAME = "Firewall Service"
        private const val BACKEND_HEALTH_CHECK_INTERVAL_MS = 30_000L  // 30 seconds

        const val ACTION_START = "io.github.dorumrr.de1984.action.START_PRIVILEGED_FIREWALL"
        const val ACTION_STOP = "io.github.dorumrr.de1984.action.STOP_PRIVILEGED_FIREWALL"
        const val EXTRA_BACKEND_TYPE = "backend_type"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize dependencies manually
        val app = application as De1984Application
        val deps = app.dependencies
        firewallRepository = deps.firewallRepository
        networkStateMonitor = deps.networkStateMonitor
        screenStateMonitor = deps.screenStateMonitor

        createNotificationChannel()

        val filter = android.content.IntentFilter("io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rulesChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(rulesChangedReceiver, filter)
        }

        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, wasExplicitlyStopped=$wasExplicitlyStopped")

        if (wasExplicitlyStopped && intent?.action != ACTION_START) {
            Log.d(TAG, "Service was explicitly stopped and no START action - stopping self")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                val backendTypeStr = intent.getStringExtra(EXTRA_BACKEND_TYPE)
                val backendType = when (backendTypeStr) {
                    "IPTABLES" -> FirewallBackendType.IPTABLES
                    "CONNECTIVITY_MANAGER" -> FirewallBackendType.CONNECTIVITY_MANAGER
                    "NETWORK_POLICY_MANAGER" -> FirewallBackendType.NETWORK_POLICY_MANAGER
                    else -> {
                        Log.e(TAG, "Invalid backend type: $backendTypeStr")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                Log.d(TAG, "ACTION_START received - starting privileged firewall with backend: $backendType")
                wasExplicitlyStopped = false
                startFirewall(backendType)
                return START_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received - stopping privileged firewall")
                wasExplicitlyStopped = true
                stopFirewall()
                return START_NOT_STICKY
            }
            else -> {
                Log.w(TAG, "Unknown action or null intent - stopping self")
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopFirewall()
        serviceScope.cancel()

        try {
            unregisterReceiver(rulesChangedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister broadcast receiver", e)
        }

        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Firewall service notification"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val backendName = when (currentBackendType) {
            FirewallBackendType.IPTABLES -> "iptables"
            FirewallBackendType.CONNECTIVITY_MANAGER -> "ConnectivityManager"
            FirewallBackendType.NETWORK_POLICY_MANAGER -> "NetworkPolicyManager"
            else -> "Unknown"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("De1984 Firewall Active")
            .setContentText("Using $backendName backend")
            .setSmallIcon(R.drawable.ic_notification_de1984)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startFirewall(backendType: FirewallBackendType) {
        Log.d(TAG, "startFirewall() called with backend: $backendType")

        serviceScope.launch {
            try {
                // Create backend instance
                val app = application as De1984Application
                val deps = app.dependencies

                val backend = when (backendType) {
                    FirewallBackendType.IPTABLES -> {
                        val b = IptablesFirewallBackend(
                            context = applicationContext,
                            rootManager = deps.rootManager,
                            shizukuManager = deps.shizukuManager,
                            errorHandler = deps.errorHandler
                        )
                        // Call internal start method
                        b.startInternal().getOrElse { error ->
                            Log.e(TAG, "Failed to start iptables backend: ${error.message}")
                            stopSelf()
                            return@launch
                        }
                        b
                    }
                    FirewallBackendType.CONNECTIVITY_MANAGER -> {
                        val b = ConnectivityManagerFirewallBackend(
                            context = applicationContext,
                            shizukuManager = deps.shizukuManager,
                            errorHandler = deps.errorHandler
                        )
                        // Call internal start method
                        b.startInternal().getOrElse { error ->
                            Log.e(TAG, "Failed to start ConnectivityManager backend: ${error.message}")
                            stopSelf()
                            return@launch
                        }
                        b
                    }
                    FirewallBackendType.NETWORK_POLICY_MANAGER -> {
                        val b = NetworkPolicyManagerFirewallBackend(
                            context = applicationContext,
                            shizukuManager = deps.shizukuManager,
                            errorHandler = deps.errorHandler
                        )
                        // Call internal start method
                        b.startInternal().getOrElse { error ->
                            Log.e(TAG, "Failed to start NetworkPolicyManager backend: ${error.message}")
                            stopSelf()
                            return@launch
                        }
                        b
                    }
                    else -> {
                        Log.e(TAG, "Unsupported backend type: $backendType")
                        stopSelf()
                        return@launch
                    }
                }

                currentBackend = backend
                currentBackendType = backendType
                isServiceActive = true

                // Update SharedPreferences to indicate service is running
                val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, true)
                    .putString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, backendType.name)
                    .apply()
                Log.d(TAG, "Updated SharedPreferences: PRIVILEGED_SERVICE_RUNNING=true, BACKEND_TYPE=$backendType")

                // Start foreground service
                Log.d(TAG, "Starting foreground service with notification")
                startForeground(NOTIFICATION_ID, createNotification())

                // Apply initial rules
                scheduleRuleApplication()

                // Start monitoring
                startMonitoring()
                startBackendHealthMonitoring()

                Log.d(TAG, "Privileged firewall started successfully with backend: $backendType")
            } catch (e: Exception) {
                Log.e(TAG, "Error in startFirewall", e)
                stopSelf()
            }
        }
    }

    private fun stopFirewall() {
        Log.d(TAG, "stopFirewall() called")

        isServiceActive = false

        // Stop monitoring
        monitoringJob?.cancel()
        monitoringJob = null
        healthMonitoringJob?.cancel()
        healthMonitoringJob = null
        ruleApplicationJob?.cancel()
        ruleApplicationJob = null

        // Stop backend
        serviceScope.launch {
            val backend = currentBackend
            val backendType = currentBackendType

            if (backend != null && backendType != null) {
                // Call internal stop method based on backend type
                when (backendType) {
                    FirewallBackendType.IPTABLES -> {
                        (backend as? IptablesFirewallBackend)?.stopInternal()?.getOrElse { error ->
                            Log.w(TAG, "Failed to stop iptables backend: ${error.message}")
                        }
                    }
                    FirewallBackendType.CONNECTIVITY_MANAGER -> {
                        (backend as? ConnectivityManagerFirewallBackend)?.stopInternal()?.getOrElse { error ->
                            Log.w(TAG, "Failed to stop ConnectivityManager backend: ${error.message}")
                        }
                    }
                    FirewallBackendType.NETWORK_POLICY_MANAGER -> {
                        (backend as? NetworkPolicyManagerFirewallBackend)?.stopInternal()?.getOrElse { error ->
                            Log.w(TAG, "Failed to stop NetworkPolicyManager backend: ${error.message}")
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unknown backend type: $backendType")
                    }
                }
            }

            currentBackend = null
            currentBackendType = null

            // Update SharedPreferences
            val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
                .remove(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE)
                .apply()
            Log.d(TAG, "Updated SharedPreferences: PRIVILEGED_SERVICE_RUNNING=false")

            // Stop foreground and remove notification
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startMonitoring() {
        Log.d(TAG, "Starting network/screen state monitoring")

        // Monitor network type and screen state changes
        monitoringJob = serviceScope.launch {
            combine(
                networkStateMonitor.observeNetworkType(),
                screenStateMonitor.observeScreenState()
            ) { networkType, screenOn ->
                Pair(networkType, screenOn)
            }.collect { (networkType, screenOn) ->
                currentNetworkType = networkType
                isScreenOn = screenOn

                if (isServiceActive) {
                    Log.d(TAG, "State changed: network=$networkType, screen=$screenOn - scheduling rule application")
                    scheduleRuleApplication()
                }
            }
        }

        // Monitor rule changes from repository
        serviceScope.launch {
            firewallRepository.getAllRules().collect { _ ->
                if (isServiceActive) {
                    Log.d(TAG, "Rules changed - scheduling rule application")
                    scheduleRuleApplication()
                }
            }
        }
    }

    private fun startBackendHealthMonitoring() {
        Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║  🔍 STARTING SERVICE HEALTH MONITORING                       ║")
        Log.d(TAG, "║  Interval: ${BACKEND_HEALTH_CHECK_INTERVAL_MS}ms (30 seconds)")
        Log.d(TAG, "║  Purpose: Detect permission loss within service              ║")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")

        healthMonitoringJob = serviceScope.launch {
            while (isServiceActive) {
                delay(BACKEND_HEALTH_CHECK_INTERVAL_MS)

                val backend = currentBackend
                val backendType = currentBackendType

                if (backend == null || backendType == null) {
                    Log.w(TAG, "⚠️  SERVICE HEALTH CHECK: backend is null, stopping monitoring")
                    break
                }

                try {
                    Log.d(TAG, "")
                    Log.d(TAG, "=== SERVICE HEALTH CHECK: $backendType ===")
                    Log.d(TAG, "Checking if backend still has required permissions...")

                    // Check if backend is still available (root/Shizuku access, iptables binary, etc.)
                    val availabilityResult = backend.checkAvailability()

                    if (availabilityResult.isFailure) {
                        Log.e(TAG, "")
                        Log.e(TAG, "╔════════════════════════════════════════════════════════════════╗")
                        Log.e(TAG, "║  ❌ SERVICE: BACKEND AVAILABILITY CHECK FAILED               ║")
                        Log.e(TAG, "║  Backend: $backendType")
                        Log.e(TAG, "║  Reason: ${availabilityResult.exceptionOrNull()?.message}")
                        Log.e(TAG, "║  Action: Stopping service to trigger FirewallManager fallback║")
                        Log.e(TAG, "╚════════════════════════════════════════════════════════════════╝")
                        Log.e(TAG, "")
                        handleBackendFailure(backendType)
                        break
                    }

                    // Note: We don't check backend.isActive() here because it checks if THIS service
                    // is running (circular check). The checkAvailability() above is sufficient to
                    // verify the backend can still function (root/Shizuku access, APIs available, etc.)

                    Log.d(TAG, "✅ SERVICE: Health check passed - $backendType is healthy")
                    Log.d(TAG, "")
                } catch (e: Exception) {
                    Log.e(TAG, "")
                    Log.e(TAG, "╔════════════════════════════════════════════════════════════════╗")
                    Log.e(TAG, "║  ❌ SERVICE: HEALTH CHECK EXCEPTION                          ║")
                    Log.e(TAG, "║  Backend: $backendType")
                    Log.e(TAG, "║  Exception: ${e.message}")
                    Log.e(TAG, "║  Action: Stopping service to trigger FirewallManager fallback║")
                    Log.e(TAG, "╚════════════════════════════════════════════════════════════════╝")
                    Log.e(TAG, "", e)
                    handleBackendFailure(backendType)
                    break
                }
            }
        }
    }

    private fun handleBackendFailure(backendType: FirewallBackendType) {
        Log.e(TAG, "╔════════════════════════════════════════════════════════════════╗")
        Log.e(TAG, "║  ⚠️  BACKEND FAILURE DETECTED IN SERVICE                     ║")
        Log.e(TAG, "║  Backend: $backendType")
        Log.e(TAG, "║  Action: Stopping service to trigger FirewallManager fallback ║")
        Log.e(TAG, "╚════════════════════════════════════════════════════════════════╝")

        // Show notification to user
        showFailureNotification(backendType)

        // Stop the service - FirewallManager watchdog will detect this and handle fallback to VPN
        // IMPORTANT: Set wasExplicitlyStopped = true to prevent service from restarting
        // The FirewallManager will start VPN backend instead
        wasExplicitlyStopped = true

        Log.e(TAG, "Stopping service now - FirewallManager should detect within 30 seconds and fallback to VPN")
        stopFirewall()

        Log.e(TAG, "Service stopped. Waiting for FirewallManager watchdog to detect and trigger VPN fallback...")
    }

    private fun showFailureNotification(backendType: FirewallBackendType) {
        val backendName = when (backendType) {
            FirewallBackendType.IPTABLES -> "iptables"
            FirewallBackendType.CONNECTIVITY_MANAGER -> "ConnectivityManager"
            FirewallBackendType.NETWORK_POLICY_MANAGER -> "NetworkPolicyManager"
            else -> "Unknown"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Firewall Backend Failed")
            .setContentText("$backendName backend stopped working. Tap to restart.")
            .setSmallIcon(R.drawable.ic_notification_de1984)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun scheduleRuleApplication() {
        ruleApplicationJob?.cancel()

        ruleApplicationJob = serviceScope.launch {
            delay(300)  // Debounce

            if (!isServiceActive) {
                Log.d(TAG, "Service not active, skipping rule application")
                return@launch
            }

            val backend = currentBackend
            if (backend == null) {
                Log.w(TAG, "Backend is null, cannot apply rules")
                return@launch
            }

            try {
                Log.d(TAG, "Applying rules: network=$currentNetworkType, screen=$isScreenOn")
                val rules = firewallRepository.getAllRules().first()

                backend.applyRules(rules, currentNetworkType, isScreenOn).getOrElse { error ->
                    Log.e(TAG, "Failed to apply rules: ${error.message}")
                    return@launch
                }

                Log.d(TAG, "Rules applied successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Exception while applying rules", e)
            }
        }
    }
}

