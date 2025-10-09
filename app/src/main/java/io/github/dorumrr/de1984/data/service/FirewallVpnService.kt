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
import io.github.dorumrr.de1984.ui.MainActivityViews
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
                } else {
                    Log.w(TAG, "   ⚠️ Service not active, ignoring broadcast")
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
        registerReceiver(rulesChangedReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wasExplicitlyStopped && intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                wasExplicitlyStopped = false
                startVpn()
                return START_STICKY
            }
            ACTION_STOP -> {
                wasExplicitlyStopped = true
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
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
            Log.w(TAG, "⚠️ Failed to unregister broadcast receiver", e)
        }

        super.onDestroy()
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
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
        vpnSetupJob?.cancel()

        isServiceActive = true

        vpnSetupJob = serviceScope.launch {
            try {
                startForeground(NOTIFICATION_ID, createNotification())
                checkBatteryOptimization()

                vpnInterface = buildVpnInterface()

                if (!isServiceActive) {
                    vpnInterface?.close()
                    vpnInterface = null
                    return@launch
                }

                if (vpnInterface == null) {
                    lastAppliedBlockedApps = emptySet()
                } else {
                    startPacketDropping()

                    lastAppliedBlockedApps = getBlockedAppsForCurrentState()
                }

                lastAppliedNetworkType = currentNetworkType
                lastAppliedScreenState = isScreenOn
            } catch (e: Exception) {
                if (isServiceActive) {
                    stopSelf()
                }
            }
        }
    }

    private fun restartVpn() {
        restartDebounceJob?.cancel()

        restartDebounceJob = serviceScope.launch debounce@{
            delay(300)

            if (!shouldRestartVpn()) {
                return@debounce
            }

            vpnSetupJob?.cancel()

            packetForwardingJob?.cancel()

            vpnSetupJob = serviceScope.launch setup@{
                try {
                    vpnInterface?.close()
                    if (!isServiceActive) {
                        return@setup
                    }

                    vpnInterface = buildVpnInterface()

                    if (!isServiceActive) {
                        vpnInterface?.close()
                        vpnInterface = null
                        return@setup
                    }

                    if (vpnInterface == null) {
                        lastAppliedBlockedApps = emptySet()
                    } else {
                        startPacketDropping()

                        lastAppliedBlockedApps = getBlockedAppsForCurrentState()
                    }

                    lastAppliedNetworkType = currentNetworkType
                    lastAppliedScreenState = isScreenOn
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception in restartVpn()", e)
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

        val sharedPreferences = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val defaultPolicy = sharedPreferences.getString(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
            io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_FIREWALL_POLICY
        ) ?: io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_FIREWALL_POLICY
        val isBlockAllDefault = defaultPolicy == io.github.dorumrr.de1984.utils.Constants.Settings.POLICY_BLOCK_ALL

        val allRules = firewallRepository.getAllRules().first()
        val rulesMap = allRules.associateBy { it.packageName }

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
            val rule = rulesMap[packageName]

            val shouldBlock = if (rule != null && rule.enabled) {
                when {
                    !isScreenOn && rule.blockWhenScreenOff -> true
                    rule.isBlockedOn(currentNetworkType) -> true
                    else -> false
                }
            } else {
                isBlockAllDefault
            }

            if (shouldBlock) {
                blockedApps.add(packageName)
            }
        }

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

            if (blockedCount == 0) {
                return null
            }

            val prepareIntent = VpnService.prepare(this@FirewallVpnService)
            if (prepareIntent != null) {
                return@buildVpnInterface null
            }

            val vpn = builder.establish()
            vpn
        } catch (e: Exception) {
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
            val rulesList = firewallRepository.getAllRules().first()
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
            var allowedCount = 0
            var blockedCount = 0
            var defaultPolicyCount = 0

            val rulesMap = rulesList.associateBy { it.packageName }

            allPackages.forEach { appInfo ->
                val packageName = appInfo.packageName
                val rule = rulesMap[packageName]

                val shouldBlock = if (rule != null && rule.enabled) {
                    when {
                        !isScreenOn && rule.blockWhenScreenOff -> {
                            true
                        }

                        rule.isBlockedOn(currentNetworkType) -> {
                            true
                        }

                        else -> {
                            false
                        }
                    }
                } else {
                    defaultPolicyCount++
                    isBlockAllDefault
                }

                if (shouldBlock) {
                    try {
                        builder.addAllowedApplication(packageName)
                        blockedCount++
                    } catch (e: PackageManager.NameNotFoundException) {
                    }
                } else {
                    allowedCount++

                }
            }

            return blockedCount
        } catch (e: Exception) {
            e.printStackTrace()
            return 0
        }
    }
    
    private fun stopVpn() {
        isServiceActive = false
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
            Log.e(TAG, "❌ Exception in stopVpn()", e)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivityViews::class.java)
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

    }

}

