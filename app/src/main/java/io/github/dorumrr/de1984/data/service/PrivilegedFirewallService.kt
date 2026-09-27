package io.github.dorumrr.de1984.data.service

import io.github.dorumrr.de1984.utils.AppLogger
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * The real teardown, which runs asynchronously in [serviceScope]. [onDestroy] waits on it for a
     * bounded time before cancelling the scope.
     */
    private var teardownJob: Job? = null

    /**
     * How long [onDestroy] will wait for the teardown. Bounded so a wedged backend cannot turn a
     * service destroy into an ANR; [serviceScope] runs on Dispatchers.IO, so the wait cannot
     * deadlock against the main thread it blocks.
     */
    private val teardownGraceMs = 5000L
    private var monitoringJob: Job? = null

    /**
     * The rules-Flow collector.
     *
     * Held in its own field because [stopFirewall] must cancel it. It used to be launched into
     * serviceScope untracked, so a backend switch left the previous session's collector alive and
     * every switch added another. They collapsed onto one debounced apply so nothing visibly broke,
     * but each leaked collector still woke on every rule change for the life of the service.
     */
    private var rulesJob: Job? = null
    private var healthMonitoringJob: Job? = null
    private var ruleApplicationJob: Job? = null

    private var currentBackend: FirewallBackend? = null
    private var currentBackendType: FirewallBackendType? = null
    private var isServiceActive = false
    private var wasExplicitlyStopped = false

    private var consecutiveSuccessfulHealthChecks = 0
    private var currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

    private var currentNetworkType: NetworkType = NetworkType.NONE
    private var isScreenOn: Boolean = true

    private val rulesChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED") {
                AppLogger.d(TAG, "🔥 [TIMING] Broadcast RECEIVED: timestamp=${System.currentTimeMillis()}")
                if (isServiceActive) {
                    val backend = currentBackend
                    if (backend is ConnectivityManagerFirewallBackend) {
                        backend.clearAppliedPoliciesCache()
                        AppLogger.d(TAG, "Cleared ConnectivityManager cache on rule change")
                    } else if (backend is NetworkPolicyManagerFirewallBackend) {
                        backend.clearAppliedPoliciesCache()
                        AppLogger.d(TAG, "Cleared NetworkPolicyManager cache on rule change")
                    }
                    scheduleRuleApplication("broadcast")
                }
            }
        }
    }

    companion object {
        private const val TAG = "PrivilegedFirewallService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "firewall_privileged_channel"
        private const val CHANNEL_NAME = "Firewall Service"

        const val ACTION_START = "io.github.dorumrr.de1984.action.START_PRIVILEGED_FIREWALL"
        const val ACTION_STOP = "io.github.dorumrr.de1984.action.STOP_PRIVILEGED_FIREWALL"
        const val EXTRA_BACKEND_TYPE = "backend_type"
    }

    override fun onCreate() {
        super.onCreate()

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

        AppLogger.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "onStartCommand: action=${intent?.action}, wasExplicitlyStopped=$wasExplicitlyStopped")

        if (wasExplicitlyStopped && intent?.action != ACTION_START) {
            AppLogger.d(TAG, "Service was explicitly stopped and no START action - stopping self")
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
                        AppLogger.e(TAG, "Invalid backend type: $backendTypeStr")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                AppLogger.d(TAG, "ACTION_START received - starting privileged firewall with backend: $backendType")
                wasExplicitlyStopped = false
                startFirewall(backendType)
                return START_STICKY
            }
            ACTION_STOP -> {
                // A stop that names a backend must only stop THAT backend. This service holds one
                // currentBackend, and an atomic switch starts the new backend before stopping the
                // old one - so an unqualified stop arriving second would tear down the backend that
                // was just started, and then report the failure under the new backend's name. The
                // user would be told the wrong thing about a firewall that had just been killed.
                //
                // An intent with no extra is an older caller; honour it as before.
                val requested = intent.getStringExtra(EXTRA_BACKEND_TYPE)
                val running = currentBackendType
                if (requested != null && running != null && requested != running.name) {
                    AppLogger.w(TAG, "Ignoring ACTION_STOP for $requested - this service is running $running")
                    return START_STICKY
                }

                AppLogger.d(TAG, "ACTION_STOP received - stopping privileged firewall (requested=${requested ?: "any"}, running=$running)")
                wasExplicitlyStopped = true
                stopFirewall()
                return START_NOT_STICKY
            }
            else -> {
                AppLogger.w(TAG, "Unknown action or null intent - stopping self")
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopFirewall()

        // Wait for the teardown before killing the scope it runs in. Cancelling on the next line
        // used to abort stopInternal() mid-flight, so a service destroyed without an explicit
        // ACTION_STOP left DROP rules on the device with nothing left running to remove them.
        runBlocking {
            withTimeoutOrNull(teardownGraceMs) { teardownJob?.join() }
        } ?: AppLogger.w(TAG, "Teardown did not finish within ${teardownGraceMs}ms - cancelling anyway")

        serviceScope.cancel()

        try {
            unregisterReceiver(rulesChangedReceiver)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to unregister broadcast receiver", e)
        }

        super.onDestroy()
        AppLogger.d(TAG, "Service destroyed")
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

        // The old hand-written map had no VPN branch, so a VPN backend fell into else and this
        // notification said "Unknown".
        val backendName = currentBackendType?.displayName(this)
            ?: getString(R.string.backend_unknown_name)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.privileged_firewall_notification_title))
            .setContentText(getString(R.string.privileged_firewall_notification_text, backendName))
            .setSmallIcon(R.drawable.ic_notification_de1984)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startFirewall(backendType: FirewallBackendType) {
        AppLogger.d(TAG, "startFirewall() called with backend: $backendType")

        serviceScope.launch {
            try {
                val app = application as De1984Application
                val deps = app.dependencies

                val backend = when (backendType) {
                    FirewallBackendType.IPTABLES -> {
                        // The shared instance, NOT a new one. This object holds the only in-memory
                        // picture of the de1984_output chain - chainNeedsResync, blockedUids,
                        // blockedLanUids - and FirewallManager applies rules through the same
                        // object. Building a second one here meant two owners of one chain, each
                        // arming its own resync flag, so every start rewrote the whole chain twice.
                        val b = deps.iptablesBackend
                        b.startInternal().getOrElse { error ->
                            AppLogger.e(TAG, "Failed to start iptables backend: ${error.message}")
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
                        b.startInternal().getOrElse { error ->
                            AppLogger.e(TAG, "Failed to start ConnectivityManager backend: ${error.message}")
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
                        b.startInternal().getOrElse { error ->
                            AppLogger.e(TAG, "Failed to start NetworkPolicyManager backend: ${error.message}")
                            stopSelf()
                            return@launch
                        }
                        b
                    }
                    else -> {
                        AppLogger.e(TAG, "Unsupported backend type: $backendType")
                        stopSelf()
                        return@launch
                    }
                }

                currentBackend = backend
                currentBackendType = backendType
                isServiceActive = true

                val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, true)
                    .putString(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE, backendType.name)
                    .apply()
                AppLogger.d(TAG, "Updated SharedPreferences: PRIVILEGED_SERVICE_RUNNING=true, BACKEND_TYPE=$backendType")

                AppLogger.d(TAG, "Starting foreground service with notification")
                startForeground(NOTIFICATION_ID, createNotification())

                // No "initial" apply here, on purpose.
                //
                // It never ran anyway: startMonitoring() below collects the Room rules Flow and the
                // network/screen monitors, all of which emit their current value immediately, and
                // each of those calls scheduleRuleApplication - which cancels the pending job. Timed
                // on hardware 2026-08-25: "initial" scheduled at 59.501, cancelled by "flow" at
                // 59.567 and again by "state-change" at 59.607, all inside the 300ms debounce.
                //
                // It was not merely dead. currentNetworkType is still NetworkType.NONE at this point
                // - startMonitoring() is what fills it - and FirewallRule.isBlockedOn(NONE) blocks.
                // So on any device slow enough for the monitors to take more than 300ms to emit,
                // this line applied a full over-block of every rule, then corrected itself moments
                // later. The rules Flow emission is the guaranteed trigger; this was a race against
                // it that could only ever produce a wrong answer.

                startMonitoring()
                startBackendHealthMonitoring()

                AppLogger.d(TAG, "Privileged firewall started successfully with backend: $backendType")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in startFirewall", e)
                stopSelf()
            }
        }
    }

    private fun stopFirewall() {
        AppLogger.d(TAG, "stopFirewall() called")

        isServiceActive = false

        monitoringJob?.cancel()
        monitoringJob = null
        rulesJob?.cancel()
        rulesJob = null
        healthMonitoringJob?.cancel()
        healthMonitoringJob = null
        ruleApplicationJob?.cancel()
        ruleApplicationJob = null

        consecutiveSuccessfulHealthChecks = 0
        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

        teardownJob = serviceScope.launch {
            val backend = currentBackend
            val backendType = currentBackendType

            if (backend != null && backendType != null) {
                when (backendType) {
                    // The teardown failure is reported, not just logged. FirewallManager judges the
                    // stop by backend.stop(), which for all three of these returns success as soon
                    // as startService() returns - the real teardown happens here, asynchronously.
                    // Swallowing it meant the "firewall would not stop" warning could never fire for
                    // any privileged backend, which is three of the four.
                    FirewallBackendType.IPTABLES -> {
                        (backend as? IptablesFirewallBackend)?.stopInternal()?.getOrElse { error ->
                            AppLogger.w(TAG, "Failed to stop iptables backend: ${error.message}")
                            reportTeardownFailure(backendType, error)
                        }
                    }
                    FirewallBackendType.CONNECTIVITY_MANAGER -> {
                        (backend as? ConnectivityManagerFirewallBackend)?.stopInternal()?.getOrElse { error ->
                            AppLogger.w(TAG, "Failed to stop ConnectivityManager backend: ${error.message}")
                            reportTeardownFailure(backendType, error)
                        }
                    }
                    FirewallBackendType.NETWORK_POLICY_MANAGER -> {
                        (backend as? NetworkPolicyManagerFirewallBackend)?.stopInternal()?.getOrElse { error ->
                            AppLogger.w(TAG, "Failed to stop NetworkPolicyManager backend: ${error.message}")
                            reportTeardownFailure(backendType, error)
                        }
                    }
                    else -> {
                        AppLogger.w(TAG, "Unknown backend type: $backendType")
                    }
                }
            }

            currentBackend = null
            currentBackendType = null

            val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(Constants.Settings.KEY_PRIVILEGED_SERVICE_RUNNING, false)
                .remove(Constants.Settings.KEY_PRIVILEGED_BACKEND_TYPE)
                .apply()
            AppLogger.d(TAG, "Updated SharedPreferences: PRIVILEGED_SERVICE_RUNNING=false")

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Watch for changes that need the rules re-applied.
     *
     * The first emission of each flow is the CURRENT value rather than a change, so this DOES look
     * like a duplicate of the pass FirewallManager runs during the start - and it was skipped as one
     * on 2026-08-25. That was wrong, and the reason is worth keeping.
     *
     * FirewallManager cannot tell when this service has finished starting. For the privileged
     * backends `backend.start()` posts an intent and returns, so its own `applyRules` can land before
     * `startInternal()` has created the iptables chains. Caught on hardware: the DROP rules were
     * written 276 ms before `iptables -N de1984_output` ran, so they failed, an EMPTY chain was
     * linked into OUTPUT, and the firewall reported success while blocking nothing. Moving the apply
     * after the liveness check only moved the race - `isActive()` then ran before the chains existed
     * and aborted the start instead.
     *
     * This pass is what repairs both cases: it runs inside the service, after `startInternal()`, so
     * the chains are guaranteed to exist. Until FirewallManager and this service have a real
     * start handshake, the second pass is the price of the rules actually being applied.
     *
     * Cancels its own previous collectors first, so calling this twice cannot leave two sets running.
     */
    private fun startMonitoring() {
        AppLogger.d(TAG, "Starting network/screen state monitoring")

        monitoringJob?.cancel()
        rulesJob?.cancel()

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
                    AppLogger.d(TAG, "State changed: network=$networkType, screen=$screenOn - scheduling rule application")
                    scheduleRuleApplication("state-change")
                }
            }
        }

        rulesJob = serviceScope.launch {
            firewallRepository.getAllRules().collect { _ ->
                if (isServiceActive) {
                    AppLogger.d(TAG, "🔥 [TIMING] Flow EMITTED: timestamp=${System.currentTimeMillis()}")
                    scheduleRuleApplication("flow")
                }
            }
        }
    }

    private fun startBackendHealthMonitoring() {
        consecutiveSuccessfulHealthChecks = 0
        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

        AppLogger.d(TAG, "🔍 STARTING ADAPTIVE SERVICE HEALTH MONITORING | Initial interval: ${currentHealthCheckInterval}ms | Stable interval: ${Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_STABLE_MS}ms | Threshold: ${Constants.HealthCheck.BACKEND_HEALTH_CHECK_STABLE_THRESHOLD} successful checks | Purpose: Detect permission loss within service")

        healthMonitoringJob = serviceScope.launch {
            while (isServiceActive) {
                delay(currentHealthCheckInterval)

                val backend = currentBackend
                val backendType = currentBackendType

                if (backend == null || backendType == null) {
                    AppLogger.w(TAG, "⚠️  SERVICE HEALTH CHECK: backend is null, stopping monitoring")
                    break
                }

                try {
                    AppLogger.d(TAG, "=== SERVICE HEALTH CHECK: $backendType ===")
                    AppLogger.d(TAG, "Checking if backend still has required permissions... (interval: ${currentHealthCheckInterval}ms, consecutive successes: $consecutiveSuccessfulHealthChecks)")

                    // For root-based backends (iptables) explicitly re-check root status so
                    // we can detect Magisk revocation while the app is in background.
                    if (backendType == FirewallBackendType.IPTABLES) {
                        try {
                            val app = application as De1984Application
                            val deps = app.dependencies
                            AppLogger.d(TAG, "Health check: forcing root status re-check for iptables backend")
                            // CRITICAL: Must await the result so the StateFlow is updated before checkAvailability()
                            deps.rootManager.forceRecheckRootStatus()
                            AppLogger.d(TAG, "Health check: root status re-check complete, new status: ${deps.rootManager.rootStatus.value}")
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Health check: failed to force root status re-check: ${e.message}")
                        }
                    }

                    val availabilityResult = backend.checkAvailability()

                    if (availabilityResult.isFailure) {
                        AppLogger.e(TAG, "❌ SERVICE: BACKEND AVAILABILITY CHECK FAILED | Backend: $backendType | Reason: ${availabilityResult.exceptionOrNull()?.message} | Action: Stopping service to trigger FirewallManager fallback")

                        consecutiveSuccessfulHealthChecks = 0
                        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

                        handleBackendFailure(backendType)
                        break
                    }

                    // Note: We don't check backend.isActive() here because it checks if THIS service
                    // is running (circular check). The checkAvailability() above is sufficient to
                    // verify the backend can still function (root/Shizuku access, APIs available, etc.)

                    consecutiveSuccessfulHealthChecks++
                    AppLogger.d(TAG, "✅ SERVICE: Health check passed - $backendType is healthy (consecutive successes: $consecutiveSuccessfulHealthChecks)")

                    if (consecutiveSuccessfulHealthChecks >= Constants.HealthCheck.BACKEND_HEALTH_CHECK_STABLE_THRESHOLD &&
                        currentHealthCheckInterval == Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS) {
                        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_STABLE_MS
                        AppLogger.d(TAG, "⚡ SERVICE: BACKEND STABLE - INCREASING INTERVAL | Backend: $backendType | New interval: ${currentHealthCheckInterval}ms | 4x fewer ticks (15s to 60s)")
                    }

                } catch (e: Exception) {
                    AppLogger.e(TAG, "❌ SERVICE: HEALTH CHECK EXCEPTION | Backend: $backendType | Exception: ${e.message} | Action: Stopping service to trigger FirewallManager fallback")
                    AppLogger.e(TAG, "", e)

                    consecutiveSuccessfulHealthChecks = 0
                    currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

                    handleBackendFailure(backendType)
                    break
                }
            }
        }
    }

    private suspend fun reportTeardownFailure(backendType: FirewallBackendType, error: Throwable) {
        try {
            val app = application as De1984Application
            app.dependencies.firewallManager.handleStopFailureFromService(backendType, error)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to notify FirewallManager of teardown failure: ${e.message}")
        }
    }

    private fun handleBackendFailure(backendType: FirewallBackendType) {
        AppLogger.e(TAG, "⚠️  BACKEND FAILURE DETECTED IN SERVICE | Backend: $backendType | Action: Notifying FirewallManager and stopping service")

        // Deliberately no notification here. FirewallManager.reportFirewallDown() is the single
        // place that tells the user protection was lost, and handleBackendFailureFromService below
        // always reaches it when the firewall stays down.
        //
        // This used to raise its own "Firewall Backend Failed" as well, which was wrong twice over:
        // two notifications with different wording for one event, and a false alarm that stayed on
        // screen even when FirewallManager recovered onto another backend a second later.

        // Notify FirewallManager immediately instead of waiting for health check
        // This makes VPN fallback instant instead of waiting up to 15 seconds
        try {
            val app = application as De1984Application
            val deps = app.dependencies
            AppLogger.e(TAG, "Notifying FirewallManager of backend failure...")
            serviceScope.launch {
                deps.firewallManager.handleBackendFailureFromService(backendType)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to notify FirewallManager of backend failure: ${e.message}")
        }

        // Stop the service
        // IMPORTANT: Set wasExplicitlyStopped = true to prevent service from restarting
        // The FirewallManager will start VPN backend instead
        wasExplicitlyStopped = true

        AppLogger.e(TAG, "Stopping service now...")
        stopFirewall()

        AppLogger.e(TAG, "Service stopped. FirewallManager should handle VPN fallback immediately.")
    }

    private var ruleApplicationStartTime: Long = 0L

    private fun scheduleRuleApplication(source: String) {
        val previousJob = ruleApplicationJob
        ruleApplicationJob?.cancel()
        ruleApplicationStartTime = System.currentTimeMillis()

        AppLogger.d(TAG, "🔥 [TIMING] scheduleRuleApplication($source): previousJobActive=${previousJob?.isActive}, timestamp=$ruleApplicationStartTime")

        ruleApplicationJob = serviceScope.launch {
            AppLogger.d(TAG, "🔥 [TIMING] Debounce START (300ms): source=$source")
            delay(300)
            AppLogger.d(TAG, "🔥 [TIMING] Debounce END: +${System.currentTimeMillis() - ruleApplicationStartTime}ms")

            if (!isServiceActive) {
                AppLogger.d(TAG, "Service not active, skipping rule application")
                return@launch
            }

            val backend = currentBackend
            if (backend == null) {
                AppLogger.w(TAG, "Backend is null, cannot apply rules")
                return@launch
            }

            try {
                AppLogger.d(TAG, "🔥 [TIMING] Fetching rules from DB: +${System.currentTimeMillis() - ruleApplicationStartTime}ms")
                val rules = firewallRepository.getAllRules().first()
                AppLogger.d(TAG, "🔥 [TIMING] Rules fetched (${rules.size} rules): +${System.currentTimeMillis() - ruleApplicationStartTime}ms")

                AppLogger.d(TAG, "🔥 [TIMING] Applying rules to backend: network=$currentNetworkType, screen=$isScreenOn")
                val applyStartTime = System.currentTimeMillis()
                backend.applyRules(rules, currentNetworkType, isScreenOn).getOrElse { error ->
                    AppLogger.e(TAG, "🔥 [TIMING] Backend applyRules FAILED: +${System.currentTimeMillis() - ruleApplicationStartTime}ms, error=${error.message}")
                    reportApplyPass(backend, error)
                    return@launch
                }

                AppLogger.d(TAG, "🔥 [TIMING] Backend applyRules SUCCESS: backend took ${System.currentTimeMillis() - applyStartTime}ms, total +${System.currentTimeMillis() - ruleApplicationStartTime}ms")
                reportApplyPass(backend, null)
            } catch (e: CancellationException) {
                // A newer schedule superseded this pass; that one reports.
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "🔥 [TIMING] Exception while applying rules: +${System.currentTimeMillis() - ruleApplicationStartTime}ms", e)
                reportApplyPass(backend, e)
            }
        }
    }

    private fun reportApplyPass(backend: FirewallBackend, error: Throwable?) {
        val backendType = currentBackendType
        // A pass that outlived its backend says nothing about the one running now.
        if (!isServiceActive || currentBackend !== backend || backendType == null) return
        try {
            val app = application as De1984Application
            app.dependencies.firewallManager.reportApplyPassFromService(backendType, error)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to notify FirewallManager of the apply result: ${e.message}")
        }
    }
}

