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
import androidx.core.app.NotificationCompat
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.data.monitor.NetworkStateMonitor
import io.github.dorumrr.de1984.data.monitor.ScreenStateMonitor
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


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
    @Volatile
    private var isServiceActive = false
    private var wasExplicitlyStopped = false

    private var lastAppliedBlockedApps: Set<String> = emptySet()
    private var lastAppliedNetworkType: NetworkType = NetworkType.NONE
    private var lastAppliedScreenState: Boolean = true

    private var lastBlockedCount: Int = 0

    private var consecutiveFailures: Int = 0

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

        // One setup at a time, across service objects: a destroyed object's build still runs,
        // and its establish() replaces the new object's tunnel. Overlap in one object leaks read loops.
        private val setupMutex = Mutex()
    }
    
    override fun onCreate() {
        super.onCreate()

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
        AppLogger.d(TAG, "onStartCommand: action=${intent?.action}, wasExplicitlyStopped=$wasExplicitlyStopped")

        // Every stop here passes startId: plain stopSelf() also drops a START the system has already accepted.
        if (wasExplicitlyStopped && intent?.action != ACTION_START) {
            AppLogger.d(TAG, "Service was explicitly stopped and no START action - stopping self")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                AppLogger.d(TAG, "ACTION_START received - starting VPN")
                wasExplicitlyStopped = false
                startVpn()
                return START_STICKY
            }
            ACTION_STOP -> {
                AppLogger.d(TAG, "ACTION_STOP received - stopping VPN")
                wasExplicitlyStopped = true
                stopVpn()
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            else -> {
                AppLogger.w(TAG, "Unknown action or null intent - stopping self")
                stopSelfResult(startId)
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
        }

        super.onDestroy()
    }

    override fun onRevoke() {
        // Called when VPN permission is revoked
        // This can happen for multiple reasons:
        // 1. User starts another VPN app (should NOT auto-restart)
        // 2. Airplane mode enabled (SHOULD auto-restart when network restored)
        // 3. Network temporarily unavailable (SHOULD auto-restart)

        AppLogger.w(TAG, "VPN permission revoked by system")

        // Check if another VPN is active
        // VpnService.prepare() returns:
        // - null: VPN permission still granted (no other VPN active) → likely airplane mode
        // - Intent: VPN permission NOT granted (another VPN active) → user chose different VPN
        val prepareIntent = VpnService.prepare(this@FirewallVpnService)
        if (prepareIntent != null) {
            AppLogger.w(TAG, "Another VPN app is active - will not auto-restart")
            wasExplicitlyStopped = true
        } else {
            AppLogger.w(TAG, "VPN permission still available - will allow auto-restart when network restored")
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
        AppLogger.d(TAG, "startVpn() called")
        vpnSetupJob?.cancel()

        isServiceActive = true

        // Reset retry counters on fresh start
        // This ensures that if user manually restarts firewall, retry starts from 1s instead of 30s
        consecutiveFailures = 0
        retryAttempt = 0
        AppLogger.d(TAG, "Reset retry counters: consecutiveFailures=0, retryAttempt=0")

        // Update SharedPreferences to indicate VPN service is running
        // IMPORTANT: Use commit() instead of apply() to ensure synchronous write
        // FirewallManager checks isActive() shortly after starting the service
        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING, true)
            .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE, false)
            .commit()
        AppLogger.d(TAG, "Updated SharedPreferences: VPN_SERVICE_RUNNING=true, VPN_INTERFACE_ACTIVE=false (pending)")

        vpnSetupJob = serviceScope.launch {
            try {
                AppLogger.d(TAG, "Starting foreground service with notification")
                startForeground(NOTIFICATION_ID, createNotification())
                checkBatteryOptimization()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in startVpn", e)
                if (isServiceActive) {
                    stopSelf()
                }
                return@launch
            }

            rebuildInterface(onlyIfChanged = false)
        }
    }

    private fun restartVpn() {
        AppLogger.d(TAG, "restartVpn: called")
        restartDebounceJob?.cancel()

        restartDebounceJob = serviceScope.launch {
            delay(300)
            rebuildInterface(onlyIfChanged = true)
        }
    }

    private suspend fun rebuildInterface(onlyIfChanged: Boolean) {
        setupMutex.withLock {
            // NonCancellable: an interrupted build or swap would leave a tunnel open that nothing owns.
            withContext(NonCancellable) {
                try {
                    if (!isServiceActive) {
                        AppLogger.w(TAG, "rebuildInterface: service not active, skipping")
                        return@withContext
                    }
                    // Inside the lock, so a start that is still building is not mistaken for a change.
                    if (onlyIfChanged && !shouldRestartVpn()) {
                        AppLogger.d(TAG, "restartVpn: shouldRestartVpn() returned false, skipping restart")
                        return@withContext
                    }

                    AppLogger.d(TAG, "rebuildInterface: building VPN interface (onlyIfChanged=$onlyIfChanged)")
                    // Decided once here: the tunnel is built from exactly this set, and this set is the record.
                    val builtNetworkType = currentNetworkType
                    val builtScreenOn = isScreenOn
                    val builtRules = firewallRepository.getAllRules().first()
                    AppLogger.d(TAG, "rebuildInterface: snapshot read ${builtRules.size} rules")
                    val builtBlockedApps = blockedAppsFor(builtRules, builtNetworkType, builtScreenOn)
                    val oldVpnInterface = vpnInterface
                    val newVpnInterface = buildVpnInterface(builtBlockedApps)

                    // Closing the old descriptor is what ends the read loop that owns it.
                    oldVpnInterface?.close()
                    vpnInterface = newVpnInterface

                    // Checked after the swap. A stop can miss the new descriptor, so close the one this build made.
                    if (!isServiceActive) {
                        AppLogger.w(TAG, "rebuildInterface: service became inactive during build, discarding it")
                        newVpnInterface?.close()
                        vpnInterface = null
                        return@withContext
                    }

                    if (newVpnInterface == null) {
                        if (lastBlockedCount >= 0) {
                            AppLogger.e(TAG, "rebuildInterface: VPN interface FAILED (blockedCount=$lastBlockedCount)")
                            handleVpnInterfaceFailure()
                        } else {
                            AppLogger.d(TAG, "rebuildInterface: No apps to block (zero-app optimization)")
                            consecutiveFailures = 0
                            retryAttempt = 0
                            // Still active with nothing to block: VpnFirewallBackend.isActive() reads this flag.
                            getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean(Constants.Settings.KEY_VPN_INTERFACE_ACTIVE, true)
                                .commit()
                            AppLogger.d(TAG, "Zero-app optimization: Set VPN_INTERFACE_ACTIVE=true")
                        }
                        lastAppliedBlockedApps = emptySet()
                    } else {
                        AppLogger.d(TAG, "VPN interface established successfully")
                        onVpnInterfaceSuccess()
                        startPacketDropping()
                        lastAppliedBlockedApps = builtBlockedApps
                        AppLogger.d(TAG, "Blocking ${lastAppliedBlockedApps.size} apps")
                    }

                    lastAppliedNetworkType = builtNetworkType
                    lastAppliedScreenState = builtScreenOn
                } catch (e: Exception) {
                    AppLogger.e(TAG, "rebuildInterface: Exception during setup", e)
                    if (isServiceActive) {
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun handleVpnInterfaceFailure() {
        consecutiveFailures++

        AppLogger.e(TAG, "handleVpnInterfaceFailure: consecutiveFailures=$consecutiveFailures")

        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE,
            false
        ).commit()

        if (consecutiveFailures >= 2) {
            showVpnFailureNotification()
        }

        scheduleVpnRetry()
    }

    private fun scheduleVpnRetry() {
        serviceScope.launch {
            val delay = when (retryAttempt) {
                0 -> 1000L
                1 -> 2000L
                2 -> 5000L
                else -> 30000L
            }

            retryAttempt++
            AppLogger.d(TAG, "scheduleVpnRetry: attempt=$retryAttempt, delay=${delay}ms")

            delay(delay)

            if (isServiceActive) {
                AppLogger.d(TAG, "scheduleVpnRetry: Attempting VPN restart...")
                restartVpn()
            }
        }
    }

    private fun onVpnInterfaceSuccess() {
        consecutiveFailures = 0
        retryAttempt = 0

        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE,
            true
        ).commit()

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
        notificationManager.notify(Constants.VpnFailure.NOTIFICATION_ID, notification)
    }

    private fun dismissVpnFailureNotification() {
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.cancel(Constants.VpnFailure.NOTIFICATION_ID)
    }

    private suspend fun shouldRestartVpn(): Boolean {
        val networkType = currentNetworkType
        val screenOn = isScreenOn
        if (networkType != lastAppliedNetworkType || screenOn != lastAppliedScreenState) {
            return true
        }

        val currentBlockedApps = blockedAppsFor(firewallRepository.getAllRules().first(), networkType, screenOn)
        return currentBlockedApps != lastAppliedBlockedApps
    }

    private fun blockedAppsFor(allRules: List<FirewallRule>, networkType: NetworkType, screenOn: Boolean): Set<String> {
        val blockedApps = mutableSetOf<String>()

        AppLogger.d(TAG, "blockedAppsFor: networkType=$networkType, screenOn=$screenOn")

        val sharedPreferences = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val defaultPolicy = sharedPreferences.getString(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
            io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_FIREWALL_POLICY
        ) ?: io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_FIREWALL_POLICY
        val isBlockAllDefault = defaultPolicy == io.github.dorumrr.de1984.utils.Constants.Settings.POLICY_BLOCK_ALL

        AppLogger.d(TAG, "blockedAppsFor: defaultPolicy=$defaultPolicy, isBlockAllDefault=$isBlockAllDefault")

        val rulesMap = allRules.associateBy { "${it.packageName}:${it.userId}" }

        AppLogger.d(TAG, "blockedAppsFor: loaded ${allRules.size} rules from database")

        val userProfiles = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(this)
        val allPackages = userProfiles.flatMap { profile ->
            io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getInstalledApplicationsAsUser(
                this, PackageManager.GET_META_DATA, profile.userId
            ).map { appInfo -> appInfo to profile.userId }
        }.filter { (appInfo, userId) ->
            try {
                val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                    this,
                    appInfo.packageName,
                    PackageManager.GET_PERMISSIONS,
                    userId
                )
                packageInfo?.requestedPermissions?.any { permission ->
                    io.github.dorumrr.de1984.utils.Constants.Firewall.NETWORK_PERMISSIONS.contains(permission)
                } ?: false
            } catch (e: Exception) {
                false
            }
        }.map { (appInfo, _) -> appInfo }

        AppLogger.d(TAG, "blockedAppsFor: found ${allPackages.size} packages across ${userProfiles.size} profiles")

        val prefs = getSharedPreferences(io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            io.github.dorumrr.de1984.utils.Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            io.github.dorumrr.de1984.utils.Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        // Pre-compute UIDs that contain critical packages (for UID-level exemption checks)
        // Even though VPN backend operates per-package, Android's network permissions are UID-based
        val uidsWithCritical = if (allowCritical) {
            allPackages
                .filter { io.github.dorumrr.de1984.utils.Constants.Firewall.isSystemCritical(it.packageName) || hasVpnService(it.packageName, it.uid / 100000) }
                .map { it.uid }
                .toSet()
        } else {
            emptySet()
        }

        for (appInfo in allPackages) {
            val packageName = appInfo.packageName
            val uid = appInfo.uid
            val userId = uid / 100000

            // Never block our own app
            if (io.github.dorumrr.de1984.utils.Constants.App.isOwnApp(packageName)) {
                continue
            }

            if (io.github.dorumrr.de1984.utils.Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
                continue
            }

            if (hasVpnService(packageName, userId) && !allowCritical) {
                continue
            }

            val rule = rulesMap["$packageName:$userId"]

            val shouldBlock = if (rule != null && rule.enabled) {
                // NetworkType.NONE is decided in FirewallRule.isBlockedOn, which every backend shares.
                when {
                    !screenOn && rule.blockWhenBackground -> true
                    else -> rule.isBlockedOn(networkType)
                }
            } else {
                if (isBlockAllDefault && allowCritical && uidsWithCritical.contains(uid)) {
                    val isSelfCritical = io.github.dorumrr.de1984.utils.Constants.Firewall.isSystemCritical(packageName) || hasVpnService(packageName, userId)
                    if (!isSelfCritical) {
                        AppLogger.d(TAG, "  $packageName (UID $uid): no rule, shares UID with critical package → allowing")
                    }
                    false
                } else {
                    isBlockAllDefault
                }
            }

            if (shouldBlock) {
                blockedApps.add(packageName)
            }
        }

        AppLogger.d(TAG, "blockedAppsFor: returning ${blockedApps.size} blocked apps")
        return blockedApps
    }

    private fun buildVpnInterface(blockedApps: Set<String>): ParcelFileDescriptor? {
        // 0 until counted, so a build that throws is sorted as a failure and never as the last build's "nothing to block".
        lastBlockedCount = 0
        return try {
            val builder = Builder()
                .setSession("De1984 Firewall")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                // Blocking, so an idle tunnel costs no CPU. Closing the descriptor wakes the read.
                .setBlocking(true)

            // IPv6 as well as IPv4, or the block is only half a block.
            //
            // This tunnel blocks by capturing an app's traffic and dropping it. With an IPv4 address
            // and an IPv4 default route only, IPv6 traffic never enters the tunnel at all - it goes
            // straight out over the network. On any dual-stack carrier or IPv6 home network a
            // "blocked" app reached the internet normally while the UI showed it as blocked.
            //
            // fd00:1984::2 is a unique-local address (RFC 4193), the IPv6 equivalent of the private
            // 10.0.0.2 above, so it cannot collide with a real destination. Added inside its own
            // try/catch: a device or ROM with IPv6 disabled can reject either call, and losing IPv6
            // capture is far better than losing the whole tunnel.
            var ipv6Captured = false
            try {
                builder.addAddress("fd00:1984::2", 64)
                builder.addRoute("::", 0)
                ipv6Captured = true
            } catch (e: IllegalArgumentException) {
                AppLogger.w(TAG, "IPv6 not accepted by this device - tunnel will capture IPv4 only: ${e.message}")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not add IPv6 to the tunnel - capturing IPv4 only: ${e.message}")
            }
            AppLogger.d(TAG, "buildVpnInterface: ipv6Captured=$ipv6Captured")

            val blockedCount = applyFirewallRules(builder, blockedApps)
            lastBlockedCount = blockedCount
            AppLogger.d(TAG, "buildVpnInterface: blockedCount=$blockedCount")

            if (blockedCount < 0) {
                AppLogger.d(TAG, "buildVpnInterface: No apps to block, not establishing VPN")
                return null
            }

            val prepareIntent = VpnService.prepare(this@FirewallVpnService)
            if (prepareIntent != null) {
                AppLogger.e(TAG, "VPN permission not granted - cannot establish VPN interface")

                // Update SharedPreferences to indicate VPN service is not running
                // IMPORTANT: Do NOT clear KEY_FIREWALL_ENABLED here!
                // We want to preserve user intent so handlePrivilegeChange() can attempt recovery.
                val prefs = getSharedPreferences(
                    io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING, false)
                    .apply()

                stopSelf()
                return null
            }

            AppLogger.d(TAG, "buildVpnInterface: calling builder.establish()...")
            val vpn = builder.establish()
            if (vpn == null) {
                AppLogger.e(TAG, "buildVpnInterface: builder.establish() returned NULL! This usually means:")
                AppLogger.e(TAG, "  1. VPN permission was revoked")
                AppLogger.e(TAG, "  2. Another VPN app took over")
                AppLogger.e(TAG, "  3. VPN configuration is invalid")
                AppLogger.e(TAG, "  blockedCount was: $blockedCount")
            } else {
                AppLogger.d(TAG, "buildVpnInterface: VPN established successfully with blockedCount=$blockedCount")
            }
            vpn
        } catch (e: Exception) {
            AppLogger.e(TAG, "buildVpnInterface: Exception caught", e)
            e.printStackTrace()
            null
        }
    }

    private fun applyFirewallRules(builder: Builder, blockedApps: Set<String>): Int {
        var blockedCount = 0
        var failedCount = 0
        for (packageName in blockedApps) {
            // Only NameNotFound is caught: establishing a builder after any other error tunnels every app, or the wrong ones.
            try {
                builder.addAllowedApplication(packageName)
                blockedCount++
            } catch (e: PackageManager.NameNotFoundException) {
                AppLogger.w(TAG, "  $packageName: NameNotFoundException when adding to VPN")
                failedCount++
            }
        }

        // With no allowed application added, Android routes every app into the VPN, so never establish it.
        if (blockedCount == 0) {
            AppLogger.w(TAG, "applyFirewallRules: No apps to block, returning -1 to skip VPN establishment")
            return -1
        }

        AppLogger.d(TAG, "applyFirewallRules: FINAL COUNTS - blocked=$blockedCount, failed=$failedCount")
        return blockedCount
    }
    
    private fun stopVpn() {
        isServiceActive = false

        // IMPORTANT: Use commit() instead of apply() to ensure synchronous write
        val prefs = getSharedPreferences(
            io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING, false)
            .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE, false)
            .commit()
        AppLogger.d(TAG, "Updated SharedPreferences: VPN_SERVICE_RUNNING=false, VPN_INTERFACE_ACTIVE=false")

        dismissVpnFailureNotification()
        AppLogger.d(TAG, "Dismissed VPN failure notification (if any)")

        vpnSetupJob?.cancel()
        vpnSetupJob = null
        packetForwardingJob?.cancel()
        packetForwardingJob = null
        try {
            vpnInterface?.close()
            vpnInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
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
                val buffer = ByteArray(32767)

                AppLogger.d(TAG, "startPacketDropping: Started reading packets to drop them")

                while (isServiceActive && vpnInterface != null) {
                    try {
                        val length = inputStream.read(buffer)
                        if (length > 0) {
                        } else if (length < 0) {
                            AppLogger.d(TAG, "startPacketDropping: End of stream, stopping")
                            break
                        }
                    } catch (e: Exception) {
                        // A swapped or stopped tunnel is closed on purpose; only the live one is an error.
                        if (isServiceActive && vpnInterface === vpn) {
                            AppLogger.w(TAG, "startPacketDropping: Error reading packet", e)
                        }
                        break
                    }
                }

                AppLogger.d(TAG, "startPacketDropping: Stopped reading packets")
            } catch (e: Exception) {
                AppLogger.e(TAG, "startPacketDropping: Exception", e)
            }
        }
    }

    /**
     * Check if a package has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * VPN apps don't REQUEST the BIND_VPN_SERVICE permission - they DECLARE it on their service.
     * This is a service permission that protects the VPN service from being bound by unauthorized apps.
     *
     * userId has NO DEFAULT on purpose. It used to default to 0, and every enforcement call site
     * omitted it - so a VPN app installed only in the work profile was looked up in the personal
     * profile, not found, and treated as an ordinary app. Block All then cut the work profile's
     * VPN. Making it required means the compiler catches the next caller that forgets.
     */
    private fun hasVpnService(packageName: String, userId: Int): Boolean {
        return try {
                // GET_PERMISSIONS is requested but never read. It is here so this shares a cache
                // entry with the getPackagesWithNetworkPermissions sweep, which asks for both.
                // getPackageInfoAsUser keys its cache on "userId:flags:packageName", so GET_SERVICES
                // alone (4) is a different key from GET_PERMISSIONS or GET_SERVICES (4100) and every
                // call was a guaranteed miss - one binder round trip per app, inside a filter over
                // every package. Measured on hardware: 53 hit / 587 miss before, and the whole
                // Block All start took 8.35s.
            val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                this,
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
                userId
            ) ?: return false

            packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == io.github.dorumrr.de1984.utils.Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

}

