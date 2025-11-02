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
        // Called when VPN permission is revoked (e.g., user starts another VPN app)
        Log.w(TAG, "VPN permission revoked by system")
        wasExplicitlyStopped = true
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

        // Update SharedPreferences to indicate VPN service is running
        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING,
            true
        ).apply()
        Log.d(TAG, "Updated SharedPreferences: VPN_SERVICE_RUNNING=true")

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
                    Log.w(TAG, "VPN interface is null - no apps to block or permission denied")
                    lastAppliedBlockedApps = emptySet()
                } else {
                    Log.d(TAG, "VPN interface established successfully")
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
                        Log.e(TAG, "restartVpn: buildVpnInterface() returned NULL - VPN is DOWN!")
                        lastAppliedBlockedApps = emptySet()
                    } else {
                        // Close old VPN AFTER new one is established
                        // This prevents the system from thinking the service is stopping
                        oldVpnInterface?.close()
                        vpnInterface = newVpnInterface

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

            val rule = rulesMap[packageName]

            val shouldBlock = if (rule != null && rule.enabled) {
                // Has explicit rule - use it as-is (absolute blocking state)
                when {
                    !isScreenOn && rule.blockWhenScreenOff -> true
                    rule.isBlockedOn(currentNetworkType) -> true
                    else -> false
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

                // Never block our own app
                if (io.github.dorumrr.de1984.utils.Constants.App.isOwnApp(packageName)) {
                    allowedCount++
                    return@forEach
                }

                val rule = rulesMap[packageName]

                val shouldBlock = if (rule != null && rule.enabled) {
                    // Has explicit rule - use it as-is (absolute blocking state)
                    val blocked = when {
                        !isScreenOn && rule.blockWhenScreenOff -> true
                        rule.isBlockedOn(currentNetworkType) -> true
                        else -> false
                    }
                    Log.d(TAG, "  $packageName: explicit rule, shouldBlock=$blocked (wifi=${rule.wifiBlocked}, mobile=${rule.mobileBlocked})")
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
        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING,
            false
        ).apply()

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
            .setContentTitle("De1984 Firewall Active")
            .setContentText("Protecting your privacy")
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

}

