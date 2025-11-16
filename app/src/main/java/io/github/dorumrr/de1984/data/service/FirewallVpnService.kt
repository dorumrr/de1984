package io.github.dorumrr.de1984.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.data.monitor.NetworkStateMonitor
import io.github.dorumrr.de1984.data.monitor.ScreenStateMonitor
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


class FirewallVpnService : VpnService() {

    
    lateinit var firewallRepository: FirewallRepository

    
    lateinit var networkStateMonitor: NetworkStateMonitor

    
    lateinit var screenStateMonitor: ScreenStateMonitor

    
    lateinit var packageDataSource: PackageDataSource

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnSetupJob: Job? = null
    private var packetForwardingJob: Job? = null
    private var restartDebounceJob: Job? = null

    private var currentNetworkType: NetworkType = NetworkType.NONE
    private var isScreenOn: Boolean = true
    private var isServiceActive = false
    private var wasExplicitlyStopped = false

    private var lastAppliedBlockedApps: Set<String> = emptySet()
    private var lastAppliedNetworkType: NetworkType = NetworkType.NONE
    private var lastAppliedScreenState: Boolean = true

    // Track blocked count to distinguish zero-app optimization from failures
    private var lastBlockedCount: Int = 0

    // Track consecutive VPN interface failures for notification debouncing
    private var consecutiveFailures: Int = 0

    // Track retry attempts for exponential backoff
    private var retryAttempt: Int = 0

    private val rulesChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED") {
                if (isServiceActive) {
                    restartVpn()
                }
            }
        }
    }

    companion object {
        private const val TAG = "FirewallVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "firewall_vpn_channel"
        private const val CHANNEL_NAME = "Firewall VPN"

        const val ACTION_START = "io.github.dorumrr.de1984.action.START_VPN"
        const val ACTION_STOP = "io.github.dorumrr.de1984.action.STOP_VPN"
    }
    
    override fun onCreate() {
        super.onCreate()

        // Initialize dependencies manually
        val app = application as De1984Application
        val deps = app.dependencies
        firewallRepository = deps.firewallRepository
        networkStateMonitor = deps.networkStateMonitor
        screenStateMonitor = deps.screenStateMonitor
        packageDataSource = deps.packageDataSource

        createNotificationChannel()
        startMonitoring()

        val filter = android.content.IntentFilter("io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rulesChangedReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(rulesChangedReceiver, filter)
        }
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
                Log.d(TAG, "ACTION_START received - starting VPN")
                wasExplicitlyStopped = false
                startVpn()
                return START_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received - stopping VPN")
                wasExplicitlyStopped = true
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                Log.w(TAG, "Unknown action or null intent - stopping self")
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }
    
    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()

        try {
            unregisterReceiver(rulesChangedReceiver)
        } catch (e: Exception) {
            // Failed to unregister broadcast receiver
        }

        super.onDestroy()
    }

    override fun onRevoke() {
        // Called when VPN permission is revoked
        // This can happen for multiple reasons:
        // 1. User starts another VPN app (should NOT auto-restart)
        // 2. Airplane mode enabled (SHOULD auto-restart when network restored)
        // 3. Network temporarily unavailable (SHOULD auto-restart)

        Log.w(TAG, "VPN permission revoked by system")

        // Check if another VPN is active
        // VpnService.prepare() returns:
        // - null: VPN permission still granted (no other VPN active) → likely airplane mode
        // - Intent: VPN permission NOT granted (another VPN active) → user chose different VPN
        val prepareIntent = VpnService.prepare(this@FirewallVpnService)
        if (prepareIntent != null) {
            // Permission NOT granted - another VPN is active
            Log.w(TAG, "Another VPN app is active - will not auto-restart")
            wasExplicitlyStopped = true
        } else {
            // Permission still granted - likely airplane mode or network issue
            Log.w(TAG, "VPN permission still available - will allow auto-restart when network restored")
            wasExplicitlyStopped = false
        }

        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun checkBatteryOptimization() {
        // Battery optimization is available on all supported API levels (26+)
    }

    private fun startMonitoring() {
        serviceScope.launch {
            combine(
                networkStateMonitor.observeNetworkType(),
                screenStateMonitor.observeScreenState()
            ) { networkType, screenOn ->
                Pair(networkType, screenOn)
            }.collect { (networkType, screenOn) ->
                currentNetworkType = networkType
                isScreenOn = screenOn

                if (isServiceActive) {
                    restartVpn()
                }
            }
        }

        serviceScope.launch {
            firewallRepository.getAllRules().collect { _ ->
                if (isServiceActive) {
                    restartVpn()
                }
            }
        }
    }

    private fun startVpn() {
        Log.d(TAG, "startVpn() called")
        vpnSetupJob?.cancel()

        isServiceActive = true

        // Reset retry counters on fresh start
        // This ensures that if user manually restarts firewall, retry starts from 1s instead of 30s
        consecutiveFailures = 0
        retryAttempt = 0
        Log.d(TAG, "Reset retry counters: consecutiveFailures=0, retryAttempt=0")

        // Update SharedPreferences to indicate VPN service is running
        // IMPORTANT: Use commit() instead of apply() to ensure synchronous write
        // FirewallManager checks isActive() shortly after starting the service
        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING, true)
            .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE, false)  // Will be set to true when interface established
            .commit()
        Log.d(TAG, "Updated SharedPreferences: VPN_SERVICE_RUNNING=true, VPN_INTERFACE_ACTIVE=false (pending)")

        vpnSetupJob = serviceScope.launch {
            try {
                Log.d(TAG, "Starting foreground service with notification")
                startForeground(NOTIFICATION_ID, createNotification())
                checkBatteryOptimization()

                Log.d(TAG, "Building VPN interface")
                vpnInterface = buildVpnInterface()

                if (!isServiceActive) {
                    Log.w(TAG, "Service became inactive during VPN setup")
                    vpnInterface?.close()
                    vpnInterface = null
                    return@launch
                }

                if (vpnInterface == null) {
                    // Distinguish between zero-app optimization and failure
                    if (lastBlockedCount > 0) {
                        // This is a FAILURE - we expected VPN but establish() returned null
                        Log.e(TAG, "startVpn: VPN interface FAILED (blockedCount=$lastBlockedCount)")
                        handleVpnInterfaceFailure()
                    } else {
                        // This is zero-app optimization - expected behavior
                        Log.w(TAG, "VPN interface is null - no apps to block (zero-app optimization)")
                        consecutiveFailures = 0
                        retryAttempt = 0

                        // IMPORTANT: Set KEY_VPN_INTERFACE_ACTIVE = true even for zero-app optimization
                        // This ensures isActive() returns true (firewall IS active, just not blocking anything)
                        prefs.edit().putBoolean(
                            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE,
                            true
                        ).commit()
                        Log.d(TAG, "Zero-app optimization: Set VPN_INTERFACE_ACTIVE=true")
                    }
                    lastAppliedBlockedApps = emptySet()
                } else {
                    Log.d(TAG, "VPN interface established successfully")

                    // Track successful VPN establishment
                    onVpnInterfaceSuccess()

                    startPacketDropping()

                    lastAppliedBlockedApps = getBlockedAppsForCurrentState()
                    Log.d(TAG, "Blocking ${lastAppliedBlockedApps.size} apps")
                }

                lastAppliedNetworkType = currentNetworkType
                lastAppliedScreenState = isScreenOn
            } catch (e: Exception) {
                Log.e(TAG, "Error in startVpn", e)
                if (isServiceActive) {
                    stopSelf()
                }
            }
        }
    }

    private fun restartVpn() {
        Log.d(TAG, "restartVpn: called")
        restartDebounceJob?.cancel()

        restartDebounceJob = serviceScope.launch debounce@{
            delay(300)

            if (!shouldRestartVpn()) {
                Log.d(TAG, "restartVpn: shouldRestartVpn() returned false, skipping restart")
                return@debounce
            }

            Log.d(TAG, "restartVpn: proceeding with VPN restart")
            vpnSetupJob?.cancel()

            packetForwardingJob?.cancel()

            vpnSetupJob = serviceScope.launch setup@{
                try {
                    if (!isServiceActive) {
                        Log.w(TAG, "restartVpn: service not active, aborting")
                        return@setup
                    }

                    Log.d(TAG, "restartVpn: calling buildVpnInterface()...")
                    val oldVpnInterface = vpnInterface
                    val newVpnInterface = buildVpnInterface()

                    if (!isServiceActive) {
                        Log.w(TAG, "restartVpn: service became inactive during build, aborting")
                        newVpnInterface?.close()
                        return@setup
                    }

                    if (newVpnInterface == null) {
                        // Close old interface to prevent resource leak
                        oldVpnInterface?.close()
                        vpnInterface = null

                        // Distinguish between zero-app optimization and failure
                        if (lastBlockedCount > 0) {
                            // This is a FAILURE - we expected VPN but establish() returned null
                            Log.e(TAG, "restartVpn: VPN interface FAILED (blockedCount=$lastBlockedCount)")
                            handleVpnInterfaceFailure()
                        } else {
                            // This is zero-app optimization - expected behavior
                            Log.d(TAG, "restartVpn: No apps to block (zero-app optimization)")
                            consecutiveFailures = 0
                            retryAttempt = 0

                            // IMPORTANT: Set KEY_VPN_INTERFACE_ACTIVE = true even for zero-app optimization
                            val prefs = getSharedPreferences(
                                io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
                                Context.MODE_PRIVATE
                            )
                            prefs.edit().putBoolean(
                                io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE,
                                true
                            ).commit()
                            Log.d(TAG, "Zero-app optimization: Set VPN_INTERFACE_ACTIVE=true")
                        }

                        lastAppliedBlockedApps = emptySet()
                    } else {
                        // Close old VPN AFTER new one is established
                        oldVpnInterface?.close()
                        vpnInterface = newVpnInterface

                        // Track successful VPN establishment
                        onVpnInterfaceSuccess()

                        Log.d(TAG, "restartVpn: VPN interface established, starting packet dropping")
                        startPacketDropping()

                        lastAppliedBlockedApps = getBlockedAppsForCurrentState()
                    }

                    lastAppliedNetworkType = currentNetworkType
                    lastAppliedScreenState = isScreenOn
                } catch (e: Exception) {
                    Log.e(TAG, "restartVpn: Exception during restart", e)
                    if (isServiceActive) {
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun handleVpnInterfaceFailure() {
        consecutiveFailures++

        Log.e(TAG, "handleVpnInterfaceFailure: consecutiveFailures=$consecutiveFailures")

        // Update SharedPreferences to indicate VPN interface is down
        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE,
            false
        ).commit()

        // Show notification after 2 consecutive failures (debouncing)
        if (consecutiveFailures >= 2) {
            showVpnFailureNotification()
        }

        // Schedule retry with exponential backoff
        scheduleVpnRetry()
    }

    private fun scheduleVpnRetry() {
        serviceScope.launch {
            val delay = when (retryAttempt) {
                0 -> 1000L      // 1 second
                1 -> 2000L      // 2 seconds
                2 -> 5000L      // 5 seconds
                else -> 30000L  // 30 seconds (steady state)
            }

            retryAttempt++
            Log.d(TAG, "scheduleVpnRetry: attempt=$retryAttempt, delay=${delay}ms")

            delay(delay)

            if (isServiceActive) {
                Log.d(TAG, "scheduleVpnRetry: Attempting VPN restart...")
                restartVpn()
            }
        }
    }

    private fun onVpnInterfaceSuccess() {
        // Reset failure tracking
        consecutiveFailures = 0
        retryAttempt = 0

        // Update SharedPreferences
        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE,
            true
        ).commit()

        // Dismiss failure notification
        dismissVpnFailureNotification()
    }

    private fun showVpnFailureNotification() {
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_failure_notification_title))
            .setContentText(getString(R.string.vpn_failure_notification_text))
            .setSmallIcon(R.drawable.ic_notification_de1984)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(false)
            .build()

        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun dismissVpnFailureNotification() {
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID + 1)
    }

    private suspend fun shouldRestartVpn(): Boolean {
        if (currentNetworkType != lastAppliedNetworkType || isScreenOn != lastAppliedScreenState) {
            return true
        }

        val currentBlockedApps = getBlockedAppsForCurrentState()
        return currentBlockedApps != lastAppliedBlockedApps
    }

    private suspend fun getBlockedAppsForCurrentState(): Set<String> {
        val blockedApps = mutableSetOf<String>()

        Log.d(TAG, "getBlockedAppsForCurrentState: currentNetworkType=$currentNetworkType, isScreenOn=$isScreenOn")

        val sharedPreferences = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val defaultPolicy = sharedPreferences.getString(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
            io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_FIREWALL_POLICY
        ) ?: io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_FIREWALL_POLICY
        val isBlockAllDefault = defaultPolicy == io.github.dorumrr.de1984.utils.Constants.Settings.POLICY_BLOCK_ALL

        Log.d(TAG, "getBlockedAppsForCurrentState: defaultPolicy=$defaultPolicy, isBlockAllDefault=$isBlockAllDefault")

        val allRules = firewallRepository.getAllRules().first()
        val rulesMap = allRules.associateBy { it.packageName }

        Log.d(TAG, "getBlockedAppsForCurrentState: loaded ${allRules.size} rules from database")

        val allPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                try {
                    val packageInfo = packageManager.getPackageInfo(
                        appInfo.packageName,
                        PackageManager.GET_PERMISSIONS
                    )
                    packageInfo.requestedPermissions?.any { permission ->
                        io.github.dorumrr.de1984.utils.Constants.Firewall.NETWORK_PERMISSIONS.contains(permission)
                    } ?: false
                } catch (e: Exception) {
                    false
                }
            }

        for (appInfo in allPackages) {
            val packageName = appInfo.packageName

            // Never block our own app
            if (io.github.dorumrr.de1984.utils.Constants.App.isOwnApp(packageName)) {
                continue
            }

            // Never block system-critical packages
            if (io.github.dorumrr.de1984.utils.Constants.Firewall.isSystemCritical(packageName)) {
                continue
            }

            // Never block VPN apps to prevent VPN reconnection issues
            if (hasVpnService(packageName)) {
                continue
            }

            val rule = rulesMap[packageName]

            val shouldBlock = if (rule != null && rule.enabled) {
                // Has explicit rule - use same logic as applyFirewallRules() for consistency
                // When network is NONE (e.g., at boot), block if app has ANY blocking rules
                when {
                    !isScreenOn && rule.blockWhenScreenOff -> true
                    currentNetworkType == NetworkType.NONE -> rule.wifiBlocked || rule.mobileBlocked
                    else -> rule.isBlockedOn(currentNetworkType)
                }
            } else {
                // No rule - apply default policy
                isBlockAllDefault
            }

            if (shouldBlock) {
                blockedApps.add(packageName)
            }
        }

        Log.d(TAG, "getBlockedAppsForCurrentState: returning ${blockedApps.size} blocked apps")
        return blockedApps
    }

    private suspend fun buildVpnInterface(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("De1984 Firewall")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .setBlocking(false)

            val blockedCount = applyFirewallRules(builder)
            lastBlockedCount = blockedCount  // Track for failure detection
            Log.d(TAG, "buildVpnInterface: blockedCount=$blockedCount")

            // If blockedCount is -1, it means no apps need to be blocked
            // In this case, don't establish VPN to avoid routing all apps through it
            if (blockedCount < 0) {
                Log.d(TAG, "buildVpnInterface: No apps to block, not establishing VPN")
                return null
            }

            // Check if VPN permission is granted
            val prepareIntent = VpnService.prepare(this@FirewallVpnService)
            if (prepareIntent != null) {
                // VPN permission not granted - stop the service and update firewall state
                Log.e(TAG, "VPN permission not granted - cannot establish VPN interface")

                // Update SharedPreferences to indicate firewall is disabled
                val prefs = getSharedPreferences(
                    io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_FIREWALL_ENABLED, false)
                    .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING, false)
                    .apply()

                // Stop the service
                stopSelf()
                return null
            }

            Log.d(TAG, "buildVpnInterface: calling builder.establish()...")
            val vpn = builder.establish()
            if (vpn == null) {
                Log.e(TAG, "buildVpnInterface: builder.establish() returned NULL! This usually means:")
                Log.e(TAG, "  1. VPN permission was revoked")
                Log.e(TAG, "  2. Another VPN app took over")
                Log.e(TAG, "  3. VPN configuration is invalid")
                Log.e(TAG, "  blockedCount was: $blockedCount")
            } else {
                Log.d(TAG, "buildVpnInterface: VPN established successfully with blockedCount=$blockedCount")
            }
            vpn
        } catch (e: Exception) {
            Log.e(TAG, "buildVpnInterface: Exception caught", e)
            e.printStackTrace()
            null
        }
    }

    private suspend fun applyFirewallRules(builder: Builder): Int {
        try {
            val prefs = getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                io.github.dorumrr.de1984.utils.Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_FIREWALL_POLICY

            val isBlockAllDefault = defaultPolicy == io.github.dorumrr.de1984.utils.Constants.Settings.POLICY_BLOCK_ALL
            Log.d(TAG, "applyFirewallRules: defaultPolicy=$defaultPolicy, isBlockAllDefault=$isBlockAllDefault")

            val rulesList = firewallRepository.getAllRules().first()
            Log.d(TAG, "applyFirewallRules: loaded ${rulesList.size} rules from database")

            val allPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { appInfo ->
                    try {
                        val packageInfo = packageManager.getPackageInfo(
                            appInfo.packageName,
                            PackageManager.GET_PERMISSIONS
                        )
                        packageInfo.requestedPermissions?.any { permission ->
                            io.github.dorumrr.de1984.utils.Constants.Firewall.NETWORK_PERMISSIONS.contains(permission)
                        } ?: false
                    } catch (e: Exception) {
                        false
                    }
                }
            Log.d(TAG, "applyFirewallRules: found ${allPackages.size} packages with network permissions")

            var allowedCount = 0
            var blockedCount = 0
            var defaultPolicyCount = 0
            var failedCount = 0

            val rulesMap = rulesList.associateBy { it.packageName }

            // SIMPLE STRATEGY: Always use addAllowedApplication() for blocked apps
            // This means:
            // - Blocked apps: Added to VPN → traffic goes through VPN → gets dropped
            // - Allowed apps: NOT added → bypass VPN → use normal internet
            // This works for both "Block All" and "Allow All" modes

            Log.d(TAG, "applyFirewallRules: Using simple strategy (addAllowedApplication for blocked apps)")

            allPackages.forEach { appInfo ->
                val packageName = appInfo.packageName

                // Never block system-critical packages
                if (io.github.dorumrr.de1984.utils.Constants.Firewall.isSystemCritical(packageName)) {
                    allowedCount++
                    return@forEach
                }

                // Never block VPN apps to prevent VPN reconnection issues
                if (hasVpnService(packageName)) {
                    allowedCount++
                    return@forEach
                }

                val rule = rulesMap[packageName]

                val shouldBlock = if (rule != null && rule.enabled) {
                    // Has explicit rule - determine blocking based on rule configuration
                    val blocked = when {
                        !isScreenOn && rule.blockWhenScreenOff -> true
                        // For VPN backend: When network is NONE (e.g., at boot), block if app has ANY blocking rules
                        // Otherwise, use network-specific blocking to support WiFi-only or Mobile-only rules
                        currentNetworkType == NetworkType.NONE -> rule.wifiBlocked || rule.mobileBlocked
                        else -> rule.isBlockedOn(currentNetworkType)
                    }
                    Log.d(TAG, "  $packageName: explicit rule, shouldBlock=$blocked (wifi=${rule.wifiBlocked}, mobile=${rule.mobileBlocked}, currentNetwork=$currentNetworkType)")
                    blocked
                } else {
                    // No explicit rule - use default policy
                    defaultPolicyCount++
                    isBlockAllDefault
                }

                if (shouldBlock) {
                    // Add to VPN to block
                    try {
                        builder.addAllowedApplication(packageName)
                        blockedCount++
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "  $packageName: NameNotFoundException when adding to VPN")
                        failedCount++
                    }
                } else {
                    allowedCount++
                    // Don't add to builder - will bypass VPN
                }
            }

            // CRITICAL: When using addAllowedApplication(), if we don't add ANY apps,
            // Android will route ALL apps through the VPN by default!
            // To prevent this, if blockedCount==0, we return -1 to signal "don't establish VPN"
            if (blockedCount == 0) {
                Log.w(TAG, "applyFirewallRules: No apps to block, returning -1 to skip VPN establishment")
                return -1
            }

            Log.d(TAG, "applyFirewallRules: FINAL COUNTS - blocked=$blockedCount, allowed=$allowedCount, defaultPolicy=$defaultPolicyCount, failed=$failedCount")
            return blockedCount
        } catch (e: Exception) {
            Log.e(TAG, "applyFirewallRules: Exception", e)
            e.printStackTrace()
            return 0
        }
    }
    
    private fun stopVpn() {
        isServiceActive = false

        // Update SharedPreferences to indicate VPN service is stopped
        // IMPORTANT: Use commit() instead of apply() to ensure synchronous write
        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING, false)
            .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE, false)
            .commit()
        Log.d(TAG, "Updated SharedPreferences: VPN_SERVICE_RUNNING=false, VPN_INTERFACE_ACTIVE=false")

        // Dismiss failure notification if it's showing
        // This prevents notification from persisting after manual stop
        dismissVpnFailureNotification()
        Log.d(TAG, "Dismissed VPN failure notification (if any)")

        vpnSetupJob?.cancel()
        vpnSetupJob = null
        packetForwardingJob?.cancel()
        packetForwardingJob = null
        try {
            vpnInterface?.close()
            vpnInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            // Exception in stopVpn
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Firewall VPN service notification"
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_firewall_notification_title))
            .setContentText(getString(R.string.vpn_firewall_notification_text))
            .setSmallIcon(R.drawable.ic_notification_de1984)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startPacketDropping() {
        packetForwardingJob?.cancel()

        val vpn = vpnInterface ?: return

        packetForwardingJob = serviceScope.launch {
            try {
                val inputStream = java.io.FileInputStream(vpn.fileDescriptor)
                val buffer = ByteArray(32767) // Max IP packet size

                Log.d(TAG, "startPacketDropping: Started reading packets to drop them")

                while (isServiceActive && vpnInterface != null) {
                    try {
                        val length = inputStream.read(buffer)
                        if (length > 0) {
                            // Packet read successfully - just drop it (don't forward)
                            // This effectively blocks the app's network access
                        } else if (length < 0) {
                            // End of stream - VPN closed
                            Log.d(TAG, "startPacketDropping: End of stream, stopping")
                            break
                        }
                    } catch (e: Exception) {
                        if (isServiceActive) {
                            Log.w(TAG, "startPacketDropping: Error reading packet", e)
                        }
                        break
                    }
                }

                Log.d(TAG, "startPacketDropping: Stopped reading packets")
            } catch (e: Exception) {
                Log.e(TAG, "startPacketDropping: Exception", e)
            }
        }
    }

    /**
     * Check if a package has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * VPN apps don't REQUEST the BIND_VPN_SERVICE permission - they DECLARE it on their service.
     * This is a service permission that protects the VPN service from being bound by unauthorized apps.
     */
    private fun hasVpnService(packageName: String): Boolean {
        return try {
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SERVICES
            )

            // Check if any service has BIND_VPN_SERVICE permission
            packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == io.github.dorumrr.de1984.utils.Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

}

