package io.github.dorumrr.de1984.data.firewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.awaitPrivilegeProbes
import io.github.dorumrr.de1984.data.common.hasPrivilegedAccess
import io.github.dorumrr.de1984.data.common.privilegeProbesAnswered
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.monitor.NetworkStateMonitor
import io.github.dorumrr.de1984.data.monitor.ScreenStateMonitor
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallHealth
import io.github.dorumrr.de1984.domain.firewall.FirewallHealthPresenter
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.ui.VpnPermissionActivity
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FirewallManager(
    private val context: Context,
    /**
     * The process-wide iptables backend. Injected rather than built here: it carries the only
     * in-memory picture of one kernel chain, so a second instance means two owners that cannot see
     * each other's writes. See De1984Dependencies.iptablesBackend.
     */
    private val iptablesBackend: IptablesFirewallBackend,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler,
    private val firewallRepository: FirewallRepository,
    private val networkStateMonitor: NetworkStateMonitor,
    private val screenStateMonitor: ScreenStateMonitor
) {
    companion object {
        private const val TAG = "FirewallManager"
        private const val VPN_CONFLICT_NOTIFICATION_DEBOUNCE_MS = 30_000L

        /** How often to ask a starting backend whether it is up yet. See awaitBackendActive. */
        private const val BACKEND_ACTIVE_POLL_MS = 200L

        /** How long to keep asking before calling it a failed start. See awaitBackendActive. */
        private const val BACKEND_ACTIVE_TIMEOUT_MS = 8_000L

        /**
         * How long a start will wait for the root and Shizuku probes to stop saying CHECKING.
         *
         * Matched to BACKEND_ACTIVE_TIMEOUT_MS above, and deliberately under the ten seconds a
         * FOREGROUND broadcast is allowed - FirewallToggleReceiver reaches startFirewall through
         * goAsync(), so a longer wait there would be killed by the system rather than by us.
         * The probe measured about five seconds on a cold start, so this leaves margin.
         */
        const val PRIVILEGE_ANSWER_TIMEOUT_MS = 8_000L
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val startStopMutex = Mutex()
    private var healthMonitoringJob: Job? = null

    /**
     * True only while the health job is calling startFirewall on itself, for a privilege-gain switch.
     *
     * That call re-enters startBackendHealthMonitoring, whose first act is
     * `healthMonitoringJob?.cancel()` - cancelling the very job sitting in the call. startFirewall is
     * `withContext(Dispatchers.IO)`, so it rethrows CancellationException on the way out: `result`
     * never binds and the whole success block is skipped. The switch itself completes, but silently -
     * no privilege-gain notification (that is its only call site), no orphaned-tunnel report, and
     * :1381 logs it as a routine "Health check exception".
     *
     * The author already guarded the reentrancy they saw - see the "we're inside the health job"
     * note at the switch - but stopMonitoring was not the only way back in.
     *
     * stopMonitoring() still cancels unconditionally, so a concurrent stopFirewall always wins.
     */
    private var switchingInsideHealthJob = false
    private var privilegeMonitoringJob: Job? = null
    private var vpnPermissionMonitoringJob: Job? = null
    private var vpnStateMonitoringJob: Job? = null

    private var consecutiveSuccessfulHealthChecks = 0
    private var currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

    private var currentBackend: FirewallBackend? = null
    private var lastVpnConflictNotificationTime = 0L

    sealed class FirewallState {
        object Stopped : FirewallState()

        data class Starting(val backend: FirewallBackendType?) : FirewallState()

        data class Running(val backend: FirewallBackendType) : FirewallState()

        data class Error(val message: String, val lastBackend: FirewallBackendType?) : FirewallState()
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val _activeBackendType = MutableStateFlow<FirewallBackendType?>(null)
    val activeBackendType: StateFlow<FirewallBackendType?> = _activeBackendType.asStateFlow()

    /**
     * Whether the firewall is actually enforcing, for the UI to render.
     *
     * Every path that loses protection must publish here through [reportFirewallDown], and every
     * path that regains or intentionally releases it through [reportFirewallHealthy]. Setting the
     * flow by hand re-opens the drift this replaced: nine hand-written failure paths, five of which
     * warned nobody.
     */
    /**
     * Set when any teardown reports a failure, including one the privileged service finds after
     * stopFirewallInternal has already moved on. Volatile because the service writes it from its
     * own scope while the stop path reads it.
     */
    @Volatile
    private var stopTeardownFailed = false

    private val _firewallHealth = MutableStateFlow<FirewallHealth>(FirewallHealth.Healthy)
    val firewallHealth: StateFlow<FirewallHealth> = _firewallHealth.asStateFlow()

    private val _currentMode = MutableStateFlow(FirewallMode.AUTO)
    val currentMode: StateFlow<FirewallMode> = _currentMode.asStateFlow()

    /**
     * Tracks whether the firewall is down but user wants it running.
     * This is separate from KEY_FIREWALL_ENABLED to distinguish:
     * - KEY_FIREWALL_ENABLED = user intent (should firewall be running?)
     * - isFirewallDown = current state (is firewall temporarily down due to error?)
     *
     * When isFirewallDown=true, handlePrivilegeChange() will attempt recovery
     * even if KEY_FIREWALL_ENABLED was cleared by error paths.
     */
    private val _isFirewallDown = MutableStateFlow(false)
    val isFirewallDown: StateFlow<Boolean> = _isFirewallDown.asStateFlow()

    private val _firewallState = MutableStateFlow<FirewallState>(FirewallState.Stopped)
    val firewallState: StateFlow<FirewallState> = _firewallState.asStateFlow()


    private var lastProcessedRootStatus: RootStatus? = null
    private var lastProcessedShizukuStatus: ShizukuStatus? = null

    init {
        _currentMode.value = getCurrentMode()
        
        initializeBackendState()

        startPrivilegeMonitoring()
        
        startVpnStateMonitoring()
    }

    /**
     * Publish a backend that startup detection found already running.
     *
     * Detection itself stays OUTSIDE the lock - it retries five times with backoff, and holding
     * startStopMutex across that would block the toggle for seconds. Only the WRITE is serialised,
     * and it yields to anything that got there first.
     *
     * That check is the point. This runs from init on its own coroutine with no lock, while a
     * widget tap, a tile tap or boot restore can be inside startFirewall at the same moment. The
     * detection result is a snapshot from up to two seconds ago; a start or stop that has already
     * claimed currentBackend has the newer truth, and overwriting it left the manager pointing at a
     * backend that had been replaced - so what the UI showed, and what a later stop tore down, was
     * not what was actually running.
     *
     * @return true if this detection was published, false if something else had already claimed it.
     */
    private suspend fun claimDetectedBackend(
        backend: FirewallBackend,
        type: FirewallBackendType
    ): Boolean = startStopMutex.withLock {
        if (currentBackend != null) {
            AppLogger.d(TAG, "Startup detection found $type, but ${_activeBackendType.value} was claimed first - leaving it alone")
            return@withLock false
        }

        currentBackend = backend
        _activeBackendType.value = type
        _firewallState.value = FirewallState.Running(type)
        emitStateChangeBroadcast(_firewallState.value)
        startBackendHealthMonitoring()
        true
    }

    private fun initializeBackendState() {
        scope.launch {
            val initStartTime = System.currentTimeMillis()
            AppLogger.i(TAG, "⏱️ TIMING: initializeBackendState START at $initStartTime")
            try {
                delay(200)

                var attempts = 0
                val maxAttempts = 5

                while (attempts < maxAttempts) {
                    val attemptStartTime = System.currentTimeMillis()
                    AppLogger.d(TAG, "⏱️ TIMING: initializeBackendState attempt ${attempts + 1}/$maxAttempts at $attemptStartTime (elapsed: ${attemptStartTime - initStartTime}ms)")

                    val vpnBackend = VpnFirewallBackend(context)
                    if (vpnBackend.isActive()) {
                        AppLogger.d(TAG, "Detected VPN backend running on startup (attempt ${attempts + 1})")
                        claimDetectedBackend(vpnBackend, FirewallBackendType.VPN)
                        return@launch
                    }

                    val iptablesCheckStart = System.currentTimeMillis()
                    if (iptablesBackend.isActive()) {
                        val iptablesCheckEnd = System.currentTimeMillis()
                        AppLogger.i(TAG, "⏱️ TIMING: Detected iptables backend running (check took ${iptablesCheckEnd - iptablesCheckStart}ms, total elapsed: ${iptablesCheckEnd - initStartTime}ms)")
                        claimDetectedBackend(iptablesBackend, FirewallBackendType.IPTABLES)
                        return@launch
                    }

                    val cmBackend = ConnectivityManagerFirewallBackend(context, shizukuManager, errorHandler)
                    if (cmBackend.isActive()) {
                        AppLogger.d(TAG, "Detected ConnectivityManager backend running on startup (attempt ${attempts + 1})")
                        claimDetectedBackend(cmBackend, FirewallBackendType.CONNECTIVITY_MANAGER)
                        return@launch
                    }

                    val npmBackend = NetworkPolicyManagerFirewallBackend(context, shizukuManager, errorHandler)
                    if (npmBackend.isActive()) {
                        AppLogger.d(TAG, "Detected NetworkPolicyManager backend running on startup (attempt ${attempts + 1})")
                        claimDetectedBackend(npmBackend, FirewallBackendType.NETWORK_POLICY_MANAGER)
                        return@launch
                    }

                    attempts++
                    if (attempts < maxAttempts) {
                        val delayMs = 100L * attempts
                        AppLogger.d(TAG, "No backend detected, retrying in ${delayMs}ms...")
                        delay(delayMs)
                    }
                }

                AppLogger.d(TAG, "No backend detected running on startup after $maxAttempts attempts")
                val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                val shouldBeRunning = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

                if (shouldBeRunning) {
                    AppLogger.w(TAG, "Firewall should be running but no backend detected - attempting restart")
                    val mode = getCurrentMode()
                    startFirewall(mode).onFailure { error ->
                        AppLogger.e(TAG, "Failed to restart firewall on initialization: ${error.message}")
                        _firewallState.value = FirewallState.Error(
                            "Firewall should be running but failed to restart: ${error.message}",
                            null
                        )
                        emitStateChangeBroadcast(_firewallState.value)
                    }
                } else {
                    _firewallState.value = FirewallState.Stopped
                    emitStateChangeBroadcast(_firewallState.value)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error initializing backend state", e)
                _firewallState.value = FirewallState.Error("Error initializing backend state: ${e.message}", _activeBackendType.value)
                emitStateChangeBroadcast(_firewallState.value)
            }
        }
    }

    fun getCurrentMode(): FirewallMode {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val modeString = prefs.getString(
            Constants.Settings.KEY_FIREWALL_MODE,
            Constants.Settings.DEFAULT_FIREWALL_MODE
        )
        return FirewallMode.fromString(modeString) ?: FirewallMode.AUTO
    }

    fun setMode(mode: FirewallMode) {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(
            Constants.Settings.KEY_FIREWALL_MODE,
            FirewallMode.Companion.run { mode.toStorageString() }
        ).apply()
        _currentMode.value = mode
        AppLogger.d(TAG, "Firewall mode set to: $mode (StateFlow updated)")
    }
    data class FirewallStartPlan(
        val mode: FirewallMode,
        val selectedBackendType: FirewallBackendType
    )

    /** Runs on Dispatchers.IO: [selectBackend] probes root and Shizuku. */
    suspend fun computeStartPlan(mode: FirewallMode = getCurrentMode()): Result<FirewallStartPlan> = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "computeStartPlan: Computing start plan for mode: $mode")

        var effectiveMode = mode
        var backendResult = selectBackend(effectiveMode)

        // A stored manual mode whose backend this device cannot run used to be a hard failure, and
        // every caller answered it differently: the widget fell back to AUTO, Settings fell back to
        // AUTO, and boot restore and the in-app Start button simply gave up. The two that gave up
        // were the ones that mattered - a mode that stops working (root lost, a rule restored from
        // another device) meant a reboot with no firewall at all.
        //
        // The fallback belongs here, the single point every start path funnels through, rather than
        // in a third copy. The plan reports the mode it actually resolved to, so a caller that cares
        // - Settings tells the user - can see the substitution instead of being handed a quiet lie.
        if (backendResult.isFailure && effectiveMode != FirewallMode.AUTO) {
            AppLogger.w(
                TAG,
                "computeStartPlan: $effectiveMode is unavailable (${backendResult.exceptionOrNull()?.message}) - falling back to AUTO"
            )
            effectiveMode = FirewallMode.AUTO
            backendResult = selectBackend(effectiveMode)
        }

        if (backendResult.isFailure) {
            val error = backendResult.exceptionOrNull()
            AppLogger.e(TAG, "computeStartPlan: Failed to select backend", error)
            return@withContext Result.failure(error ?: Exception("Failed to select backend for mode=$mode"))
        }

        val backend = backendResult.getOrThrow()
        val backendType = backend.getType()

        AppLogger.d(
            TAG,
            "computeStartPlan: requested=$mode, resolved=$effectiveMode, backendType=$backendType"
        )

        Result.success(
            FirewallStartPlan(
                mode = effectiveMode,
                selectedBackendType = backendType
            )
        )
    }


    /**
     * Start the firewall with the appropriate backend using the planner.
     *
     * All backend selection must go through [computeStartPlan] so that:
     * - UI and manager never drift on which backend will be used.
     * - Privilege/failure handlers can rely on the same planning logic.
     */
    /**
     * Runs on Dispatchers.IO. Every entry point here does blocking work - `su` probes, Shizuku
     * binder calls, shell commands, Room reads - and callers reach them from lifecycleScope and
     * viewModelScope, which are Main. Measured on hardware: 117 skipped frames at cold start.
     */
    suspend fun startFirewall(mode: FirewallMode = getCurrentMode()): Result<FirewallBackendType> =
        withContext(Dispatchers.IO) {
            startStopMutex.withLock { startFirewallInternal(mode) }
        }

    /** False once the user stopped the firewall or turned it off. */
    fun isMeantToBeOn(): Boolean =
        _firewallState.value !is FirewallState.Stopped &&
            context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

    /** Checks [isMeantToBeOn] under the start/stop lock, so a stop already running wins. Null when it no longer holds. */
    suspend fun startFirewallIfMeantToBeOn(): Result<FirewallBackendType>? =
        withContext(Dispatchers.IO) {
            startStopMutex.withLock { if (isMeantToBeOn()) startFirewallInternal(getCurrentMode()) else null }
        }

    /**
     * Internal start method without mutex (for callers that already hold the lock).
     *
     * [handleBackendFailure] runs inside [startStopMutex] and must reach this, not [startFirewall].
     * kotlinx Mutex is not reentrant, so going through the public method there suspended forever
     * while still holding the lock, hanging every later start, stop and toggle for the rest of the
     * process.
     */
    /**
     * Waits until the root and Shizuku probes have said something other than CHECKING.
     *
     * CHECKING is not an answer. `hasRootPermission` and `hasShizukuPermission` are plain
     * `status == GRANTED` reads, so while a probe is running they return false - which is
     * indistinguishable from "this device has none". [startFirewallInternal] then decided the only
     * usable backend was VPN, refused to start because another VPN was up, and raised FIREWALL DOWN
     * with a notification.
     *
     * Measured on hardware 2026-08-28 against a live ProtonVPN session:
     *
     *   15:56:18  root=CHECKING, shizuku=CHECKING
     *   15:56:20  FIREWALL DOWN - "another VPN is active and no privileged access"
     *   15:56:24  root=ROOTED_WITH_PERMISSION -> started successfully with IPTABLES
     *
     * Four seconds of a red notification on a device that had working root the whole time.
     *
     * This is the same trap handlePrivilegeChange already guards against further down this file.
     * That one can simply return, because a finished probe emits again; this is a one-shot request,
     * so it has to wait instead.
     *
     * Bounded, and false on expiry so the caller falls back to whatever it can see - never worse
     * than before this existed. Only ever reached when another VPN is already up.
     */
    private suspend fun awaitPrivilegeAnswer(): Boolean {
        fun answered() = privilegeProbesAnswered(rootManager.rootStatus.value, shizukuManager.shizukuStatus.value)

        if (answered()) {
            return true
        }

        AppLogger.d(
            TAG,
            "Privilege probe still running (root=${rootManager.rootStatus.value}, " +
                "shizuku=${shizukuManager.shizukuStatus.value}) - waiting for a real answer " +
                "before judging a VPN conflict"
        )

        val inTime = awaitPrivilegeProbes(rootManager.rootStatus, shizukuManager.shizukuStatus, PRIVILEGE_ANSWER_TIMEOUT_MS)

        if (inTime) {
            AppLogger.d(
                TAG,
                "Privilege probe answered: root=${rootManager.rootStatus.value}, " +
                    "shizuku=${shizukuManager.shizukuStatus.value}"
            )
        } else {
            AppLogger.w(
                TAG,
                "Privilege probe gave no answer within ${PRIVILEGE_ANSWER_TIMEOUT_MS}ms - " +
                    "deciding on what is visible"
            )
        }
        return inTime
    }

    private suspend fun startFirewallInternal(mode: FirewallMode): Result<FirewallBackendType> {
        return try {
            AppLogger.d(TAG, "Starting firewall with mode: $mode")

            // With another VPN up only a privileged backend may start, so the plan must see real
            // privileges: CHECKING is waited out first (see awaitPrivilegeAnswer).
            val anotherVpnActive = isAnotherVpnActive()
            if (anotherVpnActive) awaitPrivilegeAnswer()

            val planResult = computeStartPlan(mode)
            if (planResult.isFailure) {
                val error = planResult.exceptionOrNull()
                AppLogger.e(TAG, "startFirewall: Failed to compute start plan", error)
                reportStartFailure(
                    reason = FirewallHealth.Down.Reason.NO_FALLBACK_PLAN,
                    backend = activeBackendType.value,
                    stateMessage = "Failed to compute start plan: ${error?.message}"
                )
                return Result.failure(error ?: Exception("Failed to compute start plan"))
            }

            val plan = planResult.getOrThrow()
            AppLogger.d(
                TAG,
                "startFirewall: Using plan → mode=${plan.mode}, backend=${plan.selectedBackendType}"
            )

            // A consented VPN start takes the other app's tunnel with no tap; only the conflict banner may.
            if (anotherVpnActive && plan.selectedBackendType == FirewallBackendType.VPN) {
                AppLogger.w(TAG, "startFirewall: Another VPN is active and the plan needs the VPN backend - not starting")
                reportStartFailure(
                    reason = FirewallHealth.Down.Reason.VPN_CONFLICT,
                    backend = activeBackendType.value,
                    stateMessage = "Another VPN is active"
                )
                return Result.failure(Exception("Another VPN is active"))
            }

            val oldBackend = currentBackend
            val wasGranular = oldBackend?.supportsGranularControl() ?: false
            val oldBackendType = oldBackend?.getType()

            // EARLY EXIT: If the planned backend is the same as the current backend AND it's already active,
            // skip all work and return success immediately. This prevents redundant broadcasts and widget
            // updates when multiple concurrent paths (initializeBackendState, startPrivilegeMonitoring,
            // checkBackendShouldSwitch) all trigger startFirewall() during startup.
            if (oldBackend != null && oldBackendType == plan.selectedBackendType && oldBackend.isActive()) {
                AppLogger.d(TAG, "startFirewall: Backend $oldBackendType is already running and active - skipping redundant start")
                // Still clear any stale warning. Reaching here means a backend IS enforcing, and
                // without this a banner raised earlier would never be taken down.
                reportFirewallHealthy()
                return Result.success(oldBackendType)
            }

            _firewallState.value = FirewallState.Starting(oldBackendType)
            emitStateChangeBroadcast(_firewallState.value)

            val newBackend = selectBackend(plan.mode).getOrElse { error ->
                AppLogger.e(TAG, "Failed to select backend during start: ${error.message}")
                reportStartFailure(
                    reason = FirewallHealth.Down.Reason.START_FAILED,
                    backend = oldBackendType,
                    stateMessage = "Failed to select backend: ${error.message}"
                )
                return Result.failure(error)
            }

            val newBackendType = newBackend.getType()

            if (oldBackendType == newBackendType) {
                if (!oldBackend.isActive()) {
                    oldBackend.start().getOrElse { error ->
                        AppLogger.e(TAG, "Failed to restart backend: ${error.message}")
                        reportStartFailure(
                            reason = FirewallHealth.Down.Reason.START_FAILED,
                            backend = oldBackendType,
                            stateMessage = "Failed to restart backend: ${error.message}"
                        )
                        return Result.failure(error)
                    }

                    // Wait for it, exactly as the switch path below does. This branch used to call
                    // start() and go straight on to write rules. start() is fire-and-forget for the
                    // privileged backends, so the rules could be written before the chain existed -
                    // "-A de1984_output" against a chain that "-N de1984_output" had not created
                    // yet. That is the ordering race the switch path already guards against; this
                    // path simply never got the guard.
                    if (!awaitBackendActive(oldBackend, oldBackendType)) {
                        AppLogger.e(TAG, "Backend ($oldBackendType) restarted but never became active")
                        reportStartFailure(
                            reason = FirewallHealth.Down.Reason.START_FAILED,
                            backend = oldBackendType,
                            stateMessage = "Backend restarted but failed to become active"
                        )
                        return Result.failure(Exception("Backend restarted but failed to become active"))
                    }
                }

                // Apply here too. Every OTHER start path writes the rules through
                // applyRulesToBackend; this one used to lean on PrivilegedFirewallService applying
                // by itself at startup - which is exactly the duplicate pass being removed. Without
                // this line, restarting onto the same backend would leave the rules unwritten.
                //
                // It also makes the invariant the service now depends on true everywhere:
                // FirewallManager has always applied by the time the service finishes starting.
                applyRulesToBackend(oldBackend).getOrElse { error ->
                    AppLogger.e(TAG, "Failed to apply rules on same-backend restart: ${error.message}")

                    // Stop the backend, the same as the switch path above does when its apply
                    // fails. Leaving it running would be the worst of both worlds now that
                    // PrivilegedFirewallService no longer applies on its own at startup: a live
                    // backend enforcing nothing this session, with the service having already
                    // skipped its first emission, so nothing would write the rules until the next
                    // unrelated change. There is no old backend to fall back to here - old and new
                    // are the same one.
                    oldBackend.stop()

                    reportStartFailure(
                        reason = FirewallHealth.Down.Reason.START_FAILED,
                        backend = oldBackendType,
                        stateMessage = "Failed to apply rules on restart: ${error.message}"
                    )
                    return Result.failure(error)
                }

                _firewallState.value = FirewallState.Running(newBackendType)
                emitStateChangeBroadcast(_firewallState.value)
                _activeBackendType.value = newBackendType
                reportFirewallHealthy()
                return Result.success(oldBackendType)
            }

            AppLogger.d(TAG, "Backend switch: $oldBackendType → $newBackendType")

            val isGranular = newBackend.supportsGranularControl()
            val needsMigration = wasGranular && !isGranular

            // ATOMIC SWITCH: Start new backend FIRST, then stop old backend
            // This prevents security gap where apps are unblocked during transition
            AppLogger.d(TAG, "Starting new backend ($newBackendType) BEFORE stopping old backend...")
            newBackend.start().getOrElse { error ->
                AppLogger.e(TAG, "Failed to start new backend ($newBackendType): ${error.message}")
                if (oldBackend != null && oldBackend.isActive()) {
                    AppLogger.w(TAG, "Keeping old backend ($oldBackendType) running since new backend failed to start")
                    _firewallState.value = FirewallState.Running(oldBackend.getType())
                    emitStateChangeBroadcast(_firewallState.value)
                } else {
                    reportStartFailure(
                        reason = FirewallHealth.Down.Reason.START_FAILED,
                        backend = oldBackendType,
                        stateMessage = "Failed to start new backend: ${error.message}"
                    )
                }
                return Result.failure(error)
            }

            if (!awaitBackendActive(newBackend, newBackendType)) {
                AppLogger.e(TAG, "New backend ($newBackendType) started but is not active!")
                newBackend.stop()
                if (oldBackend != null && oldBackend.isActive()) {
                    AppLogger.w(TAG, "Keeping old backend ($oldBackendType) running since new backend is not active")
                    _firewallState.value = FirewallState.Running(oldBackend.getType())
                    emitStateChangeBroadcast(_firewallState.value)
                } else {
                    reportStartFailure(
                        reason = FirewallHealth.Down.Reason.START_FAILED,
                        backend = oldBackendType,
                        stateMessage = "New backend failed to become active"
                    )
                }
                return Result.failure(Exception("New backend failed to become active"))
            }

            // Rules go on only AFTER the backend has proven it is up.
            //
            // This used to run before the delay and the isActive() check above, and for the
            // privileged backends `start()` is fire-and-forget - it posts an intent to
            // PrivilegedFirewallService and returns. So the rules were written while the service was
            // still starting. Caught on hardware 2026-08-25 with iptables under root:
            //
            //   53.494  iptables -A de1984_output --uid-owner 10272 -j DROP   <- rules
            //   53.770  iptables -N de1984_output                             <- chain created, after
            //
            // The -A commands failed against a chain that did not exist yet, then an EMPTY chain was
            // created and linked into OUTPUT. The firewall reported success while blocking nothing.
            //
            // It went unnoticed because PrivilegedFirewallService used to re-apply on its own first
            // emission, silently repairing the race a moment later. That second pass was removed as
            // a duplicate, which turned a latent ordering bug into a real one.
            AppLogger.d(TAG, "Applying rules to new backend ($newBackendType)...")
            applyRulesToBackend(newBackend).getOrElse { error ->
                AppLogger.e(TAG, "Failed to apply rules to new backend: ${error.message}")
                newBackend.stop()
                if (oldBackend != null && oldBackend.isActive()) {
                    AppLogger.w(TAG, "Keeping old backend ($oldBackendType) running since new backend failed to apply rules")
                    _firewallState.value = FirewallState.Running(oldBackend.getType())
                    emitStateChangeBroadcast(_firewallState.value)
                } else {
                    reportStartFailure(
                        reason = FirewallHealth.Down.Reason.START_FAILED,
                        backend = oldBackendType,
                        stateMessage = "Failed to apply rules to new backend: ${error.message}"
                    )
                }
                return Result.failure(error)
            }

            // Not before the new backend enforces: the rewrite cannot be undone, and every failure above keeps the old one.
            // Not after stopMonitoring(): this can run inside the health job, which that call cancels.
            if (needsMigration) {
                AppLogger.d(TAG, "Backend transition: granular ($oldBackendType) → simple ($newBackendType), migrating rules...")
                migrateRulesToSimple()
            } else if (oldBackend != null) {
                AppLogger.d(
                    TAG,
                    "Backend transition: $oldBackendType → $newBackendType, no migration needed (both granular or both simple)"
                )
            }

            AppLogger.d(TAG, "New backend ($newBackendType) is active, now stopping old backend ($oldBackendType)...")

            // Now it's safe to stop the old backend.
            // The failure is CAPTURED, not swallowed. It used to be logged with "continue anyway",
            // which left the old backend enforcing underneath the new one with nothing on screen.
            var switchOrphan: Throwable? = null
            if (oldBackend != null) {
                stopMonitoring()
                switchOrphan = tearDownSwitchedAwayBackend(oldBackend, oldBackendType, runningBackendType = newBackendType)
            }

            currentBackend = newBackend
            _activeBackendType.value = newBackendType
            _firewallState.value = FirewallState.Running(newBackendType)
            emitStateChangeBroadcast(_firewallState.value)

            reportFirewallHealthy()

            // AFTER reportFirewallHealthy, never before: it clears the health state, so an orphan
            // reported earlier would be wiped by the very switch that created it.
            if (switchOrphan != null) reportOrphanedBackend(oldBackendType, switchOrphan)

            // No monitoring is started here, for any backend:
            // - VPN monitors internally via VpnService
            // - iptables, ConnectivityManager and NetworkPolicyManager all run through
            //   PrivilegedFirewallService, which observes the same network, screen and rule signals
            //   and applies the rules itself.
            //
            // ConnectivityManager and NetworkPolicyManager used to start their own monitoring here
            // while the service was doing the same job, so every rule change ran two full passes over
            // every UID from two separate backend instances - measured at ~16 s each on a real device,
            // and the reason those instances raced over the shared policy record. The exclusion
            // already existed for iptables; it just never covered the other two.

            startBackendHealthMonitoring()

            AppLogger.d(TAG, "Firewall started successfully with backend: $newBackendType (atomic switch complete)")
            Result.success(newBackendType)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start firewall", e)
            val error = errorHandler.handleError(e, "start firewall")
            reportStartFailure(
                reason = FirewallHealth.Down.Reason.START_FAILED,
                backend = _activeBackendType.value,
                stateMessage = "Failed to start firewall: ${error.message}"
            )
            Result.failure(error)
        }
    }

    /** Runs on Dispatchers.IO, for the same reason as [startFirewall]. */
    suspend fun stopFirewall(): Result<Unit> =
        withContext(Dispatchers.IO) {
            startStopMutex.withLock { stopFirewallInternal() }
        }

    private suspend fun stopFirewallInternal(): Result<Unit> {
        return try {
            AppLogger.d(TAG, "Stopping firewall")

            // A fresh attempt gets a fresh verdict. The privileged service reports its teardown
            // asynchronously, so this flag is how a failure it finds mid-sweep stops the success
            // path below from declaring the firewall healthy.
            stopTeardownFailed = false

            stopMonitoring()

            // Stop current backend.
            //
            // The failure is captured, not just logged. This used to fall through to
            // Result.success(Unit), so a backend that refused to tear down left its rules in the
            // kernel while the preference, the widget and the toggle all went to OFF, and the user
            // was told nothing. Apps could be offline with every control saying the firewall was
            // off. Only the running backend counts here - the opportunistic sweep below stays
            // best-effort, because it also runs for backends the user has no privilege for.
            // On a retry there is no active backend to read: the first stop returned before the
            // privileged service reported its failure asynchronously, so it had already cleared the
            // refs. The standing StopFailed warning still names the backend, and that is the one
            // whose sweep failure has to be reported - otherwise "Stop again" sweeps, fails, and
            // still says the firewall stopped.
            val stoppedBackendType = _activeBackendType.value
                ?: (_firewallHealth.value as? FirewallHealth.StopFailed)?.backend
                // Last resort: ask the backend object itself. handleVpnConflictFallbackFailed nulls
                // _activeBackendType on purpose while KEEPING currentBackend, so the health monitor
                // can still drive recovery - and in that state neither of the two sources above can
                // name the backend. The sweep would then report a failure against nobody.
                ?: currentBackend?.getType()
            var stopFailure: Throwable? = null
            currentBackend?.stop()?.onFailure { error ->
                AppLogger.e(TAG, "Failed to stop current backend ($stoppedBackendType): ${error.message}", error)
                stopFailure = error
            }

            val sweepFailure = cleanupAllBackends(reportFailureFor = stoppedBackendType)

            // For VPN only, ASK whether the tunnel is down rather than trusting stop()'s verdict.
            // stop() gives up after a 2s timeout, and a tunnel that simply needed a little longer
            // would otherwise leave a STUCK badge on a firewall that is genuinely off - a false
            // alarm is the app lying to the user just as much as a missed one.
            //
            // This checks isActive() directly instead of reading sweepFailure == null, because that
            // is ambiguous: the sweep returns no VPN failure both when it proved the tunnel down
            // AND when it never looked. Only a direct check is evidence.
            //
            // The privileged backends do NOT get this: they tear down inside PrivilegedFirewallService
            // and report asynchronously via stopTeardownFailed, which cannot be re-proven here.
            if (stoppedBackendType == FirewallBackendType.VPN && stopFailure != null) {
                if (!VpnFirewallBackend(context).isActive()) {
                    AppLogger.d(TAG, "VPN stop() timed out but the tunnel is down now - treating the stop as successful")
                    stopFailure = null
                }
            }

            val teardownError = stopFailure
                ?: sweepFailure?.error
                ?: if (stopTeardownFailed) Exception("Backend teardown reported a failure") else null

            // Name the backend the failure actually belongs to. The sweep can prove an orphan on a
            // backend the user was NOT running - old iptables chains under a VPN session, say - and
            // blaming the running backend for that sends the user to the wrong control.
            val blamedBackend = when {
                stopFailure != null -> stoppedBackendType
                sweepFailure != null -> sweepFailure.backend
                else -> stoppedBackendType
            }

            if (teardownError != null) {
                // currentBackend and _activeBackendType are deliberately KEPT here. The backend may
                // well still be enforcing, and the banner's "Stop again" button calls straight back
                // into this function - with them nulled, `currentBackend?.stop()` short-circuited,
                // no failure was captured, and the retry fell through to success and erased the
                // warning without removing a single rule.
                reportStopFailed(blamedBackend, teardownError)
                return Result.failure(
                    errorHandler.handleError(teardownError, "stop firewall")
                )
            }

            currentBackend = null
            _activeBackendType.value = null
            _firewallState.value = FirewallState.Stopped
            emitStateChangeBroadcast(_firewallState.value)

            // Clear firewall down flag and any stale warning - the user stopped it on purpose,
            // so this is not a failure and must not leave a "your apps are unblocked" banner behind
            reportFirewallHealthy()
            dismissVpnConflictSwitchNotification()

            AppLogger.d(TAG, "Firewall stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop firewall", e)
            val error = errorHandler.handleError(e, "stop firewall")
            _firewallState.value = FirewallState.Error("Failed to stop firewall: ${error.message}", _activeBackendType.value)
            emitStateChangeBroadcast(_firewallState.value)
            Result.failure(error)
        }
    }

    /**
     * Tear down the backend we are switching AWAY from, and prove it actually went.
     *
     * A switch is not a stop: the firewall stays up on the new backend, so a failure here is not
     * "the firewall would not stop" - it is an ORPHAN. The old backend is still enforcing its own
     * rules underneath the new one, invisibly, and the user has no control that touches it.
     *
     * Every switch site used to call backend.stop() and either ignore the Result or log it and
     * "continue anyway". That was survivable while stop() could not report a real failure. Now that
     * it can - VpnFirewallBackend proves a live tunnel - throwing the proof away is the app choosing
     * not to know.
     *
     * So: stop, and if that fails or cannot reach the old backend, run the sweep, which does the real
     * teardown and re-checks. Only a failure that survives BOTH is reported, which keeps a slow VPN
     * tunnel from raising a false alarm on every backend change.
     *
     * @param runningBackendType the backend already enforcing in its place; the sweep must not undo it.
     * @return the surviving failure, or null when the old backend is provably gone.
     */
    private suspend fun tearDownSwitchedAwayBackend(
        oldBackend: FirewallBackend?,
        oldBackendType: FirewallBackendType?,
        runningBackendType: FirewallBackendType? = null
    ): Throwable? {
        if (oldBackend == null) return null

        var failure: Throwable? = null
        oldBackend.stop().onFailure { failure = it }
        val sweptType = oldBackend.getType()
        // PrivilegedFirewallService runs one backend and drops a stop for a type it no longer runs.
        val stopDropped = sweptType != FirewallBackendType.VPN &&
            runningBackendType != null && runningBackendType != FirewallBackendType.VPN
        if (failure == null && !stopDropped) return null

        AppLogger.w(TAG, "Old backend ($oldBackendType) not proven gone - sweeping to confirm: ${failure?.message ?: "the privileged service now runs $runningBackendType"}")
        val sweep = cleanupAllBackends(reportFailureFor = sweptType, spare = runningBackendType)
        val surviving = sweep?.takeIf { it.backend == sweptType }?.error
        if (surviving == null) {
            AppLogger.d(TAG, "Sweep confirmed $oldBackendType is gone - the switch is clean")
            return null
        }

        AppLogger.e(TAG, "ORPHANED BACKEND: $oldBackendType is still enforcing after a switch - ${surviving.message}")
        return surviving
    }

    /**
     * @param reportFailureFor the backend that was actually running, or null.
     * @param spare a backend type to leave alone because it is running, or null to sweep every type.
     *
     * On a retry the privileged backends' `stop()` fires an intent at a service that has already
     * stopped itself, so this sweep is what removes their rules, and its failure must be reported.
     *
     * @return the failure for [reportFailureFor] if it failed, else the first other failure, or null.
     */
    private suspend fun cleanupAllBackends(
        reportFailureFor: FirewallBackendType? = null,
        spare: FirewallBackendType? = null
    ): SweepFailure? {
        AppLogger.d(TAG, "Cleaning up all backend types${spare?.let { " except $it" } ?: ""} to ensure no orphaned rules...")
        fun sweeps(type: FirewallBackendType) = type != spare

        // Every failure is kept, not just the running backend's. Each branch below can only fail
        // when it has EVIDENCE - iptables saw its chain, or created chains it can no longer see;
        // the VPN branch ran only because our tunnel is up; and the two Shizuku backends return
        // early with success when their record is empty. None of them can fail merely because the
        // user has no privilege, which is the only reason the old code silenced them.
        //
        // Silencing them was a real hole: an orphan proven live on a backend that was NOT the
        // running one was dropped on the floor, and stopFirewall then reported success with the OFF
        // badge over rules that were still enforcing.
        val failures = mutableMapOf<FirewallBackendType, Throwable>()

        // Clean up iptables rules (if any exist)
        // This is the most important cleanup because iptables rules persist in the kernel
        // even after the app is closed or crashes
        if (sweeps(FirewallBackendType.IPTABLES)) try {
            // stopInternal(), NOT stop(). stop() only fires ACTION_STOP at PrivilegedFirewallService
            // and returns success immediately - and on a retry that service has already stopped
            // itself, so the intent goes nowhere. The sweep would then "succeed" without touching a
            // single chain, and its success cleared the "firewall did not stop" warning over rules
            // that were still live. stopInternal() is what actually tears the chains down, and it is
            // what De1984Application's cold-start sweep already calls.
            iptablesBackend.stopInternal()
                .onSuccess { AppLogger.d(TAG, "Iptables cleanup completed") }
                .onFailure {
                    AppLogger.w(TAG, "Iptables cleanup incomplete: ${it.message}")
                    failures[FirewallBackendType.IPTABLES] = it
                }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to clean up iptables: ${e.message}")
            failures[FirewallBackendType.IPTABLES] = e
            // Ignore errors - best effort cleanup
            // User may not have root/Shizuku, which is fine
        }

        // Clean up NetworkPolicyManager uid policies (if any exist).
        // Android persists these in /data/system/netpolicy.xml, so they survive the process, a
        // reboot and an uninstall. The backend keeps the uid list in SharedPreferences, so a fresh
        // instance here can still revert what an earlier one blocked.
        if (sweeps(FirewallBackendType.NETWORK_POLICY_MANAGER)) try {
            val npmBackend = NetworkPolicyManagerFirewallBackend(
                context,
                shizukuManager,
                errorHandler
            )
            // Report what actually happened. clearOrphanedPolicies returns a failure rather than
            // throwing when Shizuku is gone, so the try/catch below would not see it and the old
            // unconditional "completed" line claimed success while apps stayed blocked.
            npmBackend.clearOrphanedPolicies()
                .onSuccess { AppLogger.d(TAG, "NetworkPolicyManager cleanup completed") }
                .onFailure {
                    AppLogger.w(TAG, "NetworkPolicyManager cleanup incomplete: ${it.message}")
                    failures[FirewallBackendType.NETWORK_POLICY_MANAGER] = it
                }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to clean up NetworkPolicyManager policies: ${e.message}")
            failures[FirewallBackendType.NETWORK_POLICY_MANAGER] = e
            // Ignore errors - best effort cleanup
            // User may not have Shizuku, which is fine
        }

        // Clean up ConnectivityManager package denials (if any exist).
        //
        // The comment that used to sit here said this backend leaves no persistent state. It does:
        // "cmd connectivity set-package-networking-enabled false <pkg>" and the OEM_DENY_3 chain are
        // system state, and the only record of what we denied lived in an in-memory map. A crash,
        // a force-stop or a backend switch left denied apps with no network and nothing to undo it.
        // The record is now on disk, so this fresh instance can put it back.
        if (sweeps(FirewallBackendType.CONNECTIVITY_MANAGER)) try {
            val cmBackend = ConnectivityManagerFirewallBackend(
                context,
                shizukuManager,
                errorHandler
            )
            cmBackend.clearOrphanedPolicies()
                .onSuccess { AppLogger.d(TAG, "ConnectivityManager cleanup completed") }
                .onFailure {
                    AppLogger.w(TAG, "ConnectivityManager cleanup incomplete: ${it.message}")
                    failures[FirewallBackendType.CONNECTIVITY_MANAGER] = it
                }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to clean up ConnectivityManager policies: ${e.message}")
            failures[FirewallBackendType.CONNECTIVITY_MANAGER] = e
            // Ignore errors - best effort cleanup
            // User may not have Shizuku, which is fine
        }

        // Clean up the VPN tunnel (if it is still up).
        //
        // The comment that used to sit here said VPN leaves no orphaned state because the service
        // stops cleanly and Android removes the interface. That is only true when the service
        // actually stops. VpnFirewallBackend.stop() waits 2 seconds and then gives up, so a wedged
        // service or a ParcelFileDescriptor that will not close leaves the tunnel up - and with no
        // branch here, nothing looked. The user was told the firewall stopped while it was still
        // dropping traffic, which is the same lie the privileged backends used to tell.
        //
        // Guarded by isActive() so the normal case costs one check and starts nothing. isActive()
        // reads our own service flags and looks for our own service class, and the stop intent is
        // explicit to FirewallVpnService, so this can never touch another app's VPN.
        if (sweeps(FirewallBackendType.VPN)) try {
            val vpnBackend = VpnFirewallBackend(context)
            if (vpnBackend.isActive()) {
                AppLogger.w(TAG, "VPN tunnel still up during cleanup - stopping it")
                vpnBackend.stop()
                    .onSuccess { AppLogger.d(TAG, "VPN cleanup completed") }
                    .onFailure {
                        AppLogger.w(TAG, "VPN cleanup incomplete: ${it.message}")
                        failures[FirewallBackendType.VPN] = it
                    }
            } else {
                AppLogger.d(TAG, "VPN cleanup: no tunnel up")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to clean up VPN: ${e.message}")
            failures[FirewallBackendType.VPN] = e
        }

        if (failures.isEmpty()) return null

        // Name the backend the user was actually running when it is one of the failures - that is
        // the one the banner should talk about. Otherwise report the orphan we found.
        val blamed = reportFailureFor?.takeIf { failures.containsKey(it) } ?: failures.keys.first()
        if (failures.size > 1) {
            AppLogger.e(TAG, "Cleanup failed for ${failures.keys.joinToString()} - reporting $blamed")
        }
        return SweepFailure(blamed, failures.getValue(blamed))
    }

    /** A backend the sweep could not clean, and the proof of it. */
    private data class SweepFailure(val backend: FirewallBackendType, val error: Throwable)

    fun isActive(): Boolean {
        return currentBackend?.isActive() ?: false
    }

    /**
     * Emit a broadcast when firewall state changes.
     * This allows widgets, tiles, and other components to receive real-time updates.
     * 
     * Note: On Android 8.0+ (API 26+), implicit broadcasts are restricted for manifest-registered
     * receivers. We must send explicit broadcasts to each component.
     */
    private fun emitStateChangeBroadcast(state: FirewallState) {
        AppLogger.d(TAG, "━━━━━ emitStateChangeBroadcast() ━━━━━")
        AppLogger.d(TAG, "Emitting state change broadcast: state=$state")
        
        val widgetIntent = Intent(Constants.Firewall.ACTION_FIREWALL_STATE_CHANGED).apply {
            setClass(context, io.github.dorumrr.de1984.ui.widget.FirewallWidget::class.java)
            putExtra(Constants.Firewall.EXTRA_FIREWALL_STATE, state.toString())
            when (state) {
                is FirewallState.Running -> {
                    putExtra(Constants.Firewall.EXTRA_BACKEND_TYPE, state.backend.name)
                }
                else -> {}
            }
        }
        context.sendBroadcast(widgetIntent)
        AppLogger.d(TAG, "✅ Explicit broadcast sent to FirewallWidget")
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            io.github.dorumrr.de1984.ui.tile.FirewallTileService.requestTileUpdate(context)
            AppLogger.d(TAG, "✅ Requested Quick Settings Tile update")
        }
        
        val implicitIntent = Intent(Constants.Firewall.ACTION_FIREWALL_STATE_CHANGED).apply {
            putExtra(Constants.Firewall.EXTRA_FIREWALL_STATE, state.toString())
            when (state) {
                is FirewallState.Running -> {
                    putExtra(Constants.Firewall.EXTRA_BACKEND_TYPE, state.backend.name)
                }
                else -> {}
            }
        }
        context.sendBroadcast(implicitIntent)
        AppLogger.d(TAG, "Implicit broadcast also sent for other listeners")
    }

    fun getActiveBackendType(): FirewallBackendType? {
        return currentBackend?.getType()
    }

    fun supportsGranularControl(): Boolean {
        return currentBackend?.supportsGranularControl() ?: true
    }

    /** Runs on Dispatchers.IO: checkAvailability shells out to `iptables --version`. */
    suspend fun isIptablesAvailable(): Boolean = withContext(Dispatchers.IO) {
        iptablesBackend.checkAvailability().isSuccess
    }

    /**
     * The modes this device can actually run, asked of the backends themselves.
     *
     * The Settings picker used to decide this from privileges alone - "Shizuku plus Android 13"
     * for ConnectivityManager, for instance - but a backend can need more than privilege.
     * ConnectivityManager also needs `cmd connectivity` to expose set-chain3-enabled, which plenty
     * of ROMs do not. The picker offered it, selecting it failed, and the firewall went down with
     * no fallback. Asking the backend is the only answer that cannot drift from the truth.
     *
     * AUTO and VPN are always in: AUTO ends at VPN, and VPN needs no privilege at all.
     *
     * Every probe is read-only - a version string, a help listing, a reflection lookup - and each
     * is guarded, so one backend that throws cannot hide the others.
     */
    /**
     * One availability probe, guarded - but NOT with runCatching.
     *
     * runCatching catches Throwable, cancellation included, which would turn "the user left the
     * screen" into "this backend is unavailable" and then carry on running the remaining privileged
     * probes on a dead scope. IptablesFirewallBackend.checkAvailability goes out of its way to
     * rethrow both CancellationException types; swallowing them here would undo that.
     */
    private suspend fun probeBackend(name: String, check: suspend () -> Result<Unit>): Boolean =
        try {
            check().isSuccess
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Availability probe for $name threw: ${e.message}")
            false
        }

    suspend fun getUsableModes(): Set<FirewallMode> = withContext(Dispatchers.IO) {
        val usable = mutableSetOf(FirewallMode.AUTO, FirewallMode.VPN)

        if (probeBackend("iptables") {
                iptablesBackend.checkAvailability()
            }) usable += FirewallMode.IPTABLES

        if (probeBackend("ConnectivityManager") {
                ConnectivityManagerFirewallBackend(context, shizukuManager, errorHandler).checkAvailability()
            }) usable += FirewallMode.CONNECTIVITY_MANAGER

        if (probeBackend("NetworkPolicyManager") {
                NetworkPolicyManagerFirewallBackend(context, shizukuManager, errorHandler).checkAvailability()
            }) usable += FirewallMode.NETWORK_POLICY_MANAGER

        AppLogger.d(TAG, "Usable backends on this device: $usable")
        usable
    }

    private suspend fun selectBackend(mode: FirewallMode): Result<FirewallBackend> {
        return try {
            AppLogger.d(TAG, "Selecting backend for mode: $mode")

            val backend = when (mode) {
                FirewallMode.AUTO -> {
                    AppLogger.d(TAG, "🎯 AUTO MODE: SELECTING BEST BACKEND | Priority: iptables > ConnectivityManager > VPN")

                    AppLogger.d(TAG, "Checking iptables availability...")
                    val iptablesAvailable = iptablesBackend.checkAvailability()

                    if (iptablesAvailable.isSuccess) {
                        AppLogger.d(TAG, "✅ iptables is AVAILABLE - selecting iptables backend")
                        iptablesBackend
                    } else {
                        AppLogger.d(TAG, "❌ iptables NOT available: ${iptablesAvailable.exceptionOrNull()?.message}")
                        AppLogger.d(TAG, "Checking ConnectivityManager availability...")
                        val cmBackend = ConnectivityManagerFirewallBackend(
                            context, shizukuManager, errorHandler
                        )
                        val cmAvailable = cmBackend.checkAvailability()

                        if (cmAvailable.isSuccess) {
                            AppLogger.d(TAG, "✅ ConnectivityManager is AVAILABLE - selecting ConnectivityManager backend")
                            cmBackend
                        } else {
                            AppLogger.d(TAG, "❌ ConnectivityManager NOT available: ${cmAvailable.exceptionOrNull()?.message}")
                            AppLogger.d(TAG, "✅ Falling back to VPN backend (always available)")
                            VpnFirewallBackend(context)
                        }
                    }
                }

                FirewallMode.VPN -> {
                    VpnFirewallBackend(context)
                }

                FirewallMode.IPTABLES -> {
                    iptablesBackend.checkAvailability().getOrElse { error ->
                        return Result.failure(error)
                    }
                    iptablesBackend
                }

                FirewallMode.CONNECTIVITY_MANAGER -> {
                    val cmBackend = ConnectivityManagerFirewallBackend(
                        context, shizukuManager, errorHandler
                    )
                    cmBackend.checkAvailability().getOrElse { error ->
                        return Result.failure(error)
                    }
                    cmBackend
                }

                FirewallMode.NETWORK_POLICY_MANAGER -> {
                    val npmBackend = NetworkPolicyManagerFirewallBackend(
                        context, shizukuManager, errorHandler
                    )
                    npmBackend.checkAvailability().getOrElse { error ->
                        return Result.failure(error)
                    }
                    npmBackend
                }
            }

            AppLogger.d(TAG, "Backend selected: ${backend.getType()}")
            Result.success(backend)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to select backend", e)
            val error = errorHandler.handleError(e, "select firewall backend")
            Result.failure(error)
        }
    }

    private suspend fun migrateRulesToSimple() {
        try {
            AppLogger.d(TAG, "=== Starting rule migration: granular → simple ===")
            val rules = firewallRepository.getAllRules().first()
            var migratedCount = 0
            var skippedCount = 0

            rules.forEach { rule ->
                // Roaming DERIVED, exactly as FirewallRule.isBlockedOn answers it - never the raw
                // column. Blocking Mobile no longer writes blockWhenRoaming, so a fully blocked app
                // is now wifi=1/mobile=1/roaming=0. Read raw, that looks PARTIAL, and this rewrote
                // all three to true - persisting a roaming block the user never set and putting back
                // the very defect that removing the paired write got rid of.
                val blocks = listOf(
                    rule.wifiBlocked,
                    rule.mobileBlocked,
                    rule.blockWhenRoaming || rule.mobileBlocked
                )

                val hasPartialBlock = blocks.any { it } && blocks.any { !it }

                if (hasPartialBlock) {
                    val hasAnyBlock = blocks.any { it }
                    val blockAll = hasAnyBlock

                    AppLogger.d(TAG, "Migrating ${rule.packageName}: wifi=${rule.wifiBlocked}, mobile=${rule.mobileBlocked}, roaming=${rule.blockWhenRoaming} → blockAll=$blockAll (conservative: any block → block all)")

                    firewallRepository.updateRule(
                        rule.copy(
                            wifiBlocked = blockAll,
                            mobileBlocked = blockAll,
                            // CARRIED THROUGH, neither invented nor erased. Writing blockAll here
                            // persisted a roaming block the user never set; writing false erased one
                            // they did - `(0,0,1)` is reachable by blocking Roaming then unblocking
                            // Mobile, and an unattended backend switch would have dropped it.
                            // mobileBlocked already implies roaming everywhere that reads it, so
                            // keeping the column costs nothing and loses nothing.
                            blockWhenRoaming = rule.blockWhenRoaming,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    migratedCount++
                } else {
                    skippedCount++
                }
            }

            AppLogger.d(TAG, "✅ Rule migration complete: $migratedCount rules migrated, $skippedCount rules already uniform")
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to migrate rules", e)
            // Don't throw - allow firewall to start even if migration fails
        }
    }

    /**
     * Cancel this manager's background jobs.
     *
     * There is no matching start: network, screen and rule-change monitoring for every privileged
     * backend belongs to PrivilegedFirewallService, and the VPN backend monitors internally. This
     * class used to duplicate that work on its own backend instance, which ran every rule change
     * twice.
     */
    private fun stopMonitoring() {
        AppLogger.d(TAG, "Stopping state monitoring")
        healthMonitoringJob?.cancel()
        healthMonitoringJob = null
        vpnPermissionMonitoringJob?.cancel()
        vpnPermissionMonitoringJob = null

        consecutiveSuccessfulHealthChecks = 0
        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS
    }

    private fun startBackendHealthMonitoring() {
        val backend = currentBackend ?: return
        val backendType = backend.getType()

        consecutiveSuccessfulHealthChecks = 0
        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

        val monitoringType = if (backendType == FirewallBackendType.VPN) {
            "PRIVILEGE GAIN (checking if better backends available)"
        } else {
            "PRIVILEGE LOSS (checking if backend still has permissions)"
        }

        AppLogger.d(TAG, "🔍 STARTING ADAPTIVE HEALTH MONITORING | Backend: $backendType | Type: $monitoringType | Initial interval: ${currentHealthCheckInterval}ms | Stable interval: ${Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_STABLE_MS}ms | Threshold: ${Constants.HealthCheck.BACKEND_HEALTH_CHECK_STABLE_THRESHOLD} successful checks")

        // Not while the running health job is the one asking. See switchingInsideHealthJob:
        // cancelling here kills the coroutine sitting in startFirewall, so its result never binds and
        // the privilege-gain notification never fires. The old job breaks out of its loop by itself
        // straight after the switch, so nothing is left running.
        if (!switchingInsideHealthJob) {
            healthMonitoringJob?.cancel()
        }
        healthMonitoringJob = scope.launch {
            while (true) {
                delay(currentHealthCheckInterval)

                try {
                    // VPN backend: check whether a better backend has become available.
                    //
                    // The test below is `currentMode == VPN`, not `== AUTO`. Only a MANUAL VPN choice
                    // is respected and left alone; every other stored mode - AUTO, and any privileged
                    // mode that fell back to VPN - enters the switching branch. That is deliberate:
                    // a user whose iptables mode fell back to VPN wants to be moved back when root
                    // returns. This comment used to say "only switch in AUTO mode", which the code
                    // has never done.
                    if (backendType == FirewallBackendType.VPN) {
                        // Ask the backend before claiming it is healthy. Both VPN branches below
                        // incremented the success counter and logged "VPN backend is active" without
                        // ever checking, so a killed VPN service was never detected: the tunnel was
                        // gone, every app was unblocked, and the health check kept reporting success
                        // until the interval stretched to 60s. Same two-step as every other backend.
                        if (!vpnIsHealthy(backend, backendType)) {
                            break
                        }

                        val currentMode = getCurrentMode()
                        
                        if (currentMode == FirewallMode.VPN) {
                            AppLogger.d(TAG, "Health check: User is in manual VPN mode - respecting choice, not checking for privilege gain (interval: ${currentHealthCheckInterval}ms)")
                            consecutiveSuccessfulHealthChecks++
                            AppLogger.d(TAG, "✅ Health check passed: VPN backend is active (manual mode, consecutive successes: $consecutiveSuccessfulHealthChecks)")
                            clearHealthWarningIfEnforcing()
                        } else {
                            AppLogger.d(TAG, "Health check: Checking if better backends available (AUTO mode)... (interval: ${currentHealthCheckInterval}ms, consecutive successes: $consecutiveSuccessfulHealthChecks)")

                            // One try per tick: the next tick is the retry, and each extra try spawns su and sh again.
                            rootManager.forceRecheckRootStatus(retry = false)
                            shizukuManager.checkShizukuStatus()

                            // The stored mode, not a hard-coded AUTO. Reaching here on VPN with a
                            // manual mode set means the user was moved off their choice by a
                            // fallback; planning for AUTO would put them on AUTO's favourite rather
                            // than back on what they picked - an NPM user would silently become an
                            // iptables user for good. computeStartPlan falls back to AUTO by itself
                            // if their mode still is not runnable, so this cannot get stuck.
                            val planResult = computeStartPlan(getCurrentMode())

                            if (planResult.isSuccess) {
                                val plan = planResult.getOrThrow()

                                if (plan.selectedBackendType != FirewallBackendType.VPN) {
                                    AppLogger.d(TAG, "⚡ PRIVILEGE GAIN DETECTED - BETTER BACKEND AVAILABLE | Current: VPN (AUTO mode) | Better: ${plan.selectedBackendType} | Action: Switching to better backend automatically")

                                    // Stop current VPN backend (don't call stopMonitoring() - we're inside the health job!)
                                    // The startFirewall() will start new monitoring for the new backend
                                    val orphan = tearDownSwitchedAwayBackend(currentBackend, FirewallBackendType.VPN)
                                    currentBackend = null
                                    _activeBackendType.value = null

                                    // Guarded: this call re-enters startBackendHealthMonitoring,
                                    // which would otherwise cancel THIS coroutine mid-call and throw
                                    // away everything below. Cleared in finally so a failure cannot
                                    // leave the flag set and make a later stop unable to cancel.
                                    switchingInsideHealthJob = true
                                    val result = try {
                                        startFirewall(FirewallMode.AUTO)
                                    } finally {
                                        switchingInsideHealthJob = false
                                    }
                                    result.onSuccess { newBackend ->
                                        AppLogger.d(TAG, "✅ Successfully switched to $newBackend backend via privilege gain detection")
                                        // The tunnel that would not close is still dropping traffic
                                        // under its old allowlist. Report it once the new backend is
                                        // up, so the start's reportFirewallHealthy cannot erase it.
                                        if (orphan != null) reportOrphanedBackend(FirewallBackendType.VPN, orphan)
                                        showPrivilegeGainSwitchNotification(newBackend)
                                    }.onFailure { error ->
                                        AppLogger.e(TAG, "❌ Failed to switch to better backend: ${error.message}")
                                    }
                                    break
                                }
                            }

                            consecutiveSuccessfulHealthChecks++
                            AppLogger.d(TAG, "✅ Health check passed: VPN is still the best available backend (AUTO mode, consecutive successes: $consecutiveSuccessfulHealthChecks)")
                            clearHealthWarningIfEnforcing()
                        }

                    } else {
                        AppLogger.d(TAG, "Health check: Testing $backendType backend availability... (interval: ${currentHealthCheckInterval}ms, consecutive successes: $consecutiveSuccessfulHealthChecks)")

                        val availabilityResult = backend.checkAvailability()

                        if (availabilityResult.isFailure) {
                            AppLogger.e(TAG, "❌ Health check FAILED: $backendType backend is no longer available!")
                            AppLogger.e(TAG, "Error: ${availabilityResult.exceptionOrNull()?.message}")
                            failHealthCheck(backendType)
                            break
                        }

                        if (!backend.isActive()) {
                            AppLogger.e(TAG, "❌ Health check FAILED: $backendType backend is not active!")
                            failHealthCheck(backendType)
                            break
                        }

                        consecutiveSuccessfulHealthChecks++
                        AppLogger.d(TAG, "✅ Health check passed: $backendType backend is healthy (consecutive successes: $consecutiveSuccessfulHealthChecks)")
                        clearHealthWarningIfEnforcing()
                    }

                    if (consecutiveSuccessfulHealthChecks >= Constants.HealthCheck.BACKEND_HEALTH_CHECK_STABLE_THRESHOLD &&
                        currentHealthCheckInterval == Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS) {
                        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_STABLE_MS
                        AppLogger.d(TAG, "⚡ BACKEND STABLE - INCREASING HEALTH CHECK INTERVAL | Backend: $backendType | New interval: ${currentHealthCheckInterval}ms | 4x fewer ticks (15s to 60s)")
                    }

                } catch (e: Exception) {
                    AppLogger.e(TAG, "Health check exception for $backendType", e)
                    // Don't trigger fallback on exceptions - might be temporary
                    // Don't reset counter either - exception doesn't mean backend is unstable
                }
            }
        }
    }

    /**
     * True when the VPN backend is still up. On false it has already reported the failure and reset
     * the health counters, exactly as the non-VPN path does, and the caller must break the loop.
     */
    private suspend fun vpnIsHealthy(backend: FirewallBackend, backendType: FirewallBackendType): Boolean {
        val availabilityResult = backend.checkAvailability()
        if (availabilityResult.isFailure) {
            AppLogger.e(TAG, "❌ Health check FAILED: VPN backend is no longer available!")
            AppLogger.e(TAG, "Error: ${availabilityResult.exceptionOrNull()?.message}")
            failHealthCheck(backendType)
            return false
        }

        if (!backend.isActive()) {
            AppLogger.e(TAG, "❌ Health check FAILED: VPN backend is not active!")
            failHealthCheck(backendType)
            return false
        }

        return true
    }

    private suspend fun failHealthCheck(backendType: FirewallBackendType) {
        AppLogger.e(TAG, "Resetting health check interval to initial value (${Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS}ms)")
        consecutiveSuccessfulHealthChecks = 0
        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS
        handleBackendFailure(backendType)
    }

    /**
     * A cold-start sweep found a backend still enforcing. Raise the same warning a failed stop does.
     *
     * De1984Application sweeps on every cold start where the firewall is supposed to be OFF, using
     * its own backend instances. It is the only retry that happens after a stop failed and the
     * process then died - and [FirewallHealth] is in-memory, so without this the app came back up
     * reporting Healthy while apps were still blocked, with no badge, no banner and no "Stop again".
     *
     * Public because the Application owns that sweep, not this class.
     */
    suspend fun reportStopFailedFromSweep(backendType: FirewallBackendType, error: Throwable) {
        AppLogger.e(TAG, "Cold-start sweep could not clear $backendType - raising the stuck warning")
        reportStopFailed(backendType, error)
    }

    /**
     * The privileged service could not tear a backend down.
     *
     * Deliberately takes no lock. It is called from the service's own stop handler, which runs while
     * stopFirewallInternal may still be sweeping, and blocking there would serialise the two halves
     * of a stop against each other. It only publishes state.
     */
    suspend fun handleStopFailureFromService(backendType: FirewallBackendType, error: Throwable) {
        AppLogger.e(TAG, "Service reported a failed teardown for $backendType")
        reportStopFailed(backendType, error)
    }

    suspend fun handleBackendFailureFromService(failedBackendType: FirewallBackendType) {
        AppLogger.e(TAG, "Received backend failure notification from service: $failedBackendType")
        handleBackendFailure(failedBackendType)
    }

    /**
     * The widget or the tile asked to start the firewall, and the plan needs VPN permission.
     *
     * Both of those arrive through FirewallToggleReceiver, and a BroadcastReceiver cannot open the
     * permission dialog: with targetSdk 34, Android 14 refuses the launch with BAL_BLOCK and the tap
     * did nothing at all, silently. A notification CAN get there, because tapping one is a user
     * gesture, and [showVpnFallbackNotification] already exists and already opens MainActivity with
     * ACTION_ENABLE_VPN_FALLBACK.
     *
     * Routed through [reportFirewallDown] rather than calling the notification directly, so the
     * badge, the banner and the widget all describe the same situation - the user asked for the
     * firewall and did not get it, which is exactly what Down means.
     *
     * This runs on every Android version, not only 14+. The direct launch still works below 14, but
     * keeping it would be a second way to do one thing, and the notification works everywhere. The
     * cost is one extra tap on older devices.
     *
     * @param resolvedMode the mode the receiver actually planned with, which is NOT always the
     *   stored preference: a manual mode whose backend is gone makes it fall back to AUTO. Carried
     *   all the way to VpnPermissionActivity so that fallback is not recomputed and lost.
     */
    /** A background restore that threw before [startFirewall] could report; silent while a backend enforces. */
    suspend fun reportStartFailedFromBackground(error: Throwable) = withContext(Dispatchers.IO) {
        startStopMutex.withLock {
            if (currentBackend?.isActive() != true) {
                reportStartFailure(
                    reason = FirewallHealth.Down.Reason.START_FAILED,
                    backend = _activeBackendType.value,
                    stateMessage = "Failed to start firewall: ${error.message}"
                )
            }
        }
    }

    fun reportVpnPermissionRequiredFromBackground(resolvedMode: FirewallMode) {
        // Another path may have started a backend since the caller planned; that one is protecting.
        if (isActive()) {
            AppLogger.d(TAG, "VPN permission report skipped - ${activeBackendType.value} is already enforcing")
            return
        }
        AppLogger.w(TAG, "Widget/tile start needs VPN permission (mode=$resolvedMode) - a receiver cannot open the dialog, notifying instead")
        reportFirewallDown(
            reason = FirewallHealth.Down.Reason.VPN_PERMISSION_REQUIRED,
            backend = FirewallBackendType.VPN,
            stateMessage = "VPN permission required",
            vpnPermissionResolvedMode = resolvedMode
        )
    }

    /**
     * Single entry point for "the firewall is no longer enforcing".
     *
     * Publishes the typed health state the UI renders, mirrors it into [_firewallState], tells the
     * widget, preserves user intent so recovery can run, and raises the matching notification.
     *
     * Backend teardown stays at the call sites. Some paths must keep [currentBackend] so the health
     * monitor can still see it and drive recovery, so this helper never touches it.
     */
    private fun reportFirewallDown(
        reason: FirewallHealth.Down.Reason,
        backend: FirewallBackendType?,
        stateMessage: String,
        afterStartAttempt: Boolean = false,
        /**
         * Set only by [reportVpnPermissionRequiredFromBackground]. Its presence is what marks this
         * as "the user asked the widget or tile to start the firewall", as opposed to "a privileged
         * backend died and we are falling back" - two situations that need different words and a
         * different destination. See [showVpnFallbackNotification].
         */
        vpnPermissionResolvedMode: FirewallMode? = null
    ) {
        // This flag means "the user wants the firewall on and it is not". Recovery keys off it, so
        // setting it when the user's own intent flag is false turns a failed toggle into a restart
        // attempt on every resume: FirewallViewModel writes KEY_FIREWALL_ENABLED=false on a failed
        // start, and handlePrivilegeChange's `!enabled && !down` guard then stops short-circuiting.
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val userWantsFirewallOn = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

        // PRECEDENCE: a live StopFailed outranks Down while the user's intent is OFF.
        //
        // Killing Shizuku raises both. They claim opposite things - "your apps are unblocked"
        // versus "some apps may still be blocked" - and whichever landed last used to win, so the
        // same failure showed a different banner from one run to the next.
        //
        // The tiebreak is the user's own intent, because that is what separates these two states in
        // the first place: Down means wanting blocking and getting none, StopFailed means wanting
        // none and maybe getting some. With intent OFF, StopFailed is the truthful one - and on
        // iptables, ConnectivityManager and NetworkPolicyManager it is literally true, because
        // kernel chains, sUidOwnerMap entries and netpolicy.xml all outlive the backend that wrote
        // them, so "apps are unblocked" is a lie there.
        //
        // Intent ON is deliberately NOT suppressed: that is the orphan-after-switch case, where the
        // firewall is meant to be running and losing it IS the news. The orphan keeps its own
        // notification, which is the part that survives process death anyway.
        //
        // Nor is a start attempt suppressed. [reportStartFailure] only reaches here after proving
        // `currentBackend.isActive()` is false and nulling the refs, so the app has already decided
        // nothing is enforcing. Keeping "some apps may still be blocked" over that decision would
        // contradict it: after a failed stop the user can tap ON again, the start can fail with the
        // stuck backend now genuinely gone, and the banner would still claim rules are live.
        if (!afterStartAttempt && _firewallHealth.value is FirewallHealth.StopFailed && !userWantsFirewallOn) {
            AppLogger.w(
                TAG,
                "Firewall down ($reason, backend=$backend) but a stuck backend warning is live and " +
                    "the user asked for the firewall off - keeping StopFailed"
            )
            return
        }

        AppLogger.e(TAG, "🚨 FIREWALL DOWN ($reason, backend=$backend): apps are UNBLOCKED - $stateMessage")

        _firewallHealth.value = FirewallHealth.Down(reason, backend)
        _firewallState.value = FirewallState.Error(message = stateMessage, lastBackend = backend)
        emitStateChangeBroadcast(_firewallState.value)
        dismissVpnConflictSwitchNotification()

        _isFirewallDown.value = userWantsFirewallOn
        if (!userWantsFirewallOn) {
            AppLogger.d(TAG, "User intent is off - reporting the failure but not arming recovery")
        }

        // Android 13+ drops notify() silently when POST_NOTIFICATIONS is denied. Say so, rather than
        // logging a success the user never saw. The in-app banner also reacts to this.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            AppLogger.w(TAG, "Notifications are disabled - this warning only reaches the user if they open the app")
        }

        when (reason) {
            // These two already have their own actionable notifications, with buttons that drive
            // the VPN permission flow. Reusing them keeps one notification per situation.
            FirewallHealth.Down.Reason.VPN_CONFLICT -> showVpnConflictNotification()
            FirewallHealth.Down.Reason.VPN_PERMISSION_REQUIRED ->
                showVpnFallbackNotification(vpnPermissionResolvedMode)
            else -> showFirewallDownNotification(reason, backend)
        }
    }

    /**
     * Clear the "not enforcing" state, because a backend is running again or because the user
     * stopped the firewall on purpose.
     *
     * Both failure notifications are dismissed, not just the backend one - a VPN conflict or a
     * pending VPN permission is equally over once we get here.
     *
     * Only call this when protection genuinely changed hands. A passing health check must use
     * [clearHealthWarningIfEnforcing] instead: clearing [_isFirewallDown] is what re-enables
     * recovery, and a timer tick is not evidence that a lost firewall came back.
     */
    private fun reportFirewallHealthy() {
        stopTeardownFailed = false
        _firewallHealth.value = FirewallHealth.Healthy
        _isFirewallDown.value = false
        dismissBackendFailedNotification()
        dismissVpnFallbackNotification()
        dismissStopFailedNotification()
        // A tile/widget stop posts this after its stop returns, so a stop never cancels its own notice here.
        notificationManager.cancel(Constants.FirewallStopped.NOTIFICATION_ID)
    }

    /**
     * The user asked to stop and the backend would not tear down, so its rules may still be live.
     *
     * [_isFirewallDown] is cleared on purpose: that flag arms automatic recovery, which restarts the
     * firewall. Arming it here would fight the user, who just asked for the opposite. The stale
     * failure notifications go too - whatever was wrong before, "will not stop" is the live problem
     * now, and the banner carries it.
     */
    /**
     * Tell the user a backend we switched away from is still enforcing.
     *
     * Same warning surface as a failed stop - the user's problem is identical: apps are blocked and
     * no control in the app touches the thing blocking them. But [_firewallState] is deliberately
     * left alone, because the firewall IS running, on the new backend. Writing Error here would put
     * the widget and the tile into a failed state for a firewall that is up and working.
     */
    private fun reportOrphanedBackend(backend: FirewallBackendType?, error: Throwable) {
        AppLogger.e(TAG, "⚠️ ORPHANED BACKEND ($backend): its rules may still be enforced", error)

        stopTeardownFailed = true
        _firewallHealth.value = FirewallHealth.StopFailed(backend)
        showStopFailedNotification(backend)
    }

    private fun reportStopFailed(backend: FirewallBackendType?, error: Throwable) {
        AppLogger.e(TAG, "⚠️ FIREWALL WOULD NOT STOP (backend=$backend): rules may still be enforced", error)

        stopTeardownFailed = true

        _firewallHealth.value = FirewallHealth.StopFailed(backend)
        _isFirewallDown.value = false
        dismissBackendFailedNotification()
        dismissVpnFallbackNotification()

        _firewallState.value = FirewallState.Error(
            message = context.getString(R.string.firewall_stop_failed_title),
            lastBackend = backend
        )
        emitStateChangeBroadcast(_firewallState.value)

        // _firewallHealth is in-memory and resets to Healthy on process death, so without this the
        // warning would live only inside an open Activity: swipe the app away, come back, and the
        // UI would say OFF with rules still enforced. The notification is the part that survives.
        showStopFailedNotification(backend)
    }

    /**
     * Tell the user, outside the app, that the firewall would not shut down.
     *
     * Same wording as the banner, through the same presenter, so the two cannot drift apart.
     * Its own notification id: [dismissBackendFailedNotification] must not cancel it, because a
     * later "backend healthy" is not evidence that the stuck rules were removed.
     */
    /**
     * Whether stopping the firewall from the tile or the widget should ask first.
     *
     * ON by default: stopping hands every app on the device network access at once, which is worth
     * one tap of friction. The reporter of issue #91 wanted the opposite, so it is a setting rather
     * than a decision made for everyone.
     *
     * Defined ONCE here because both the tile and the widget's receiver ask the same question, and a
     * rule like this written in two places is how the two drift apart.
     */
    fun shouldConfirmStop(): Boolean =
        context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(
                Constants.Settings.KEY_CONFIRM_FIREWALL_STOP,
                Constants.Settings.DEFAULT_CONFIRM_FIREWALL_STOP
            )

    /**
     * Says the firewall was stopped, for the path where nothing was confirmed on screen.
     *
     * Without this a tile tap silently removes all protection - the one outcome the confirmation
     * existed to prevent. autoCancel, because tapping it opens the app where the real state is.
     */
    fun showFirewallStoppedNotification() {
        // No SDK guard: minSdk is 26, which IS Build.VERSION_CODES.O, so NotificationChannel is
        // always available. The older notifications here still carry that check; lint flags it.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                Constants.FirewallStopped.CHANNEL_ID,
                Constants.FirewallStopped.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = context.getString(R.string.firewall_stopped_notification_text)
        val notification = NotificationCompat.Builder(context, Constants.FirewallStopped.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.firewall_stopped_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(Constants.FirewallStopped.NOTIFICATION_ID, notification)
    }

    private fun showStopFailedNotification(backend: FirewallBackendType?) {
        AppLogger.d(TAG, "Showing stop-failed notification (backend=$backend)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.BackendFailure.CHANNEL_ID,
                Constants.BackendFailure.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when preferred firewall backend fails"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val health = FirewallHealth.StopFailed(backend)
        val title = FirewallHealthPresenter.title(context, health).orEmpty()
        val body = FirewallHealthPresenter.message(context, health).orEmpty()

        val notification = NotificationCompat.Builder(context, Constants.BackendFailure.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            // NOT autoCancel. FirewallHealth.StopFailed lives only in memory and resets to Healthy on
            // process death, so this notification is the only record that survives - which is the
            // whole reason it is posted. With autoCancel, the ordinary "tap to open the app" gesture
            // destroyed it, and if the process had already died the app would open showing Healthy
            // with the rules still enforced. It is dismissed deliberately instead, by
            // dismissStopFailedNotification, once a stop or a start has genuinely succeeded.
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(Constants.StopFailure.NOTIFICATION_ID, notification)
    }

    private fun dismissStopFailedNotification() {
        notificationManager.cancel(Constants.StopFailure.NOTIFICATION_ID)
    }

    /**
     * A start attempt failed. Report it as "firewall down" only if nothing is actually enforcing.
     *
     * Several start paths deliberately keep the previous backend running when the new one fails, and
     * on those the firewall is still protecting the user - saying "your apps are unblocked" there
     * would be a lie, and would set [_isFirewallDown] on a firewall that is up.
     *
     * When nothing is enforcing, this clears the backend refs first so the invariant every other
     * down-path holds - [_activeBackendType] is null whenever health is Down - stays true. Without
     * it, [clearHealthWarningIfEnforcing] would wipe the warning on the next tick.
     */
    private fun reportStartFailure(
        reason: FirewallHealth.Down.Reason,
        backend: FirewallBackendType?,
        stateMessage: String
    ) {
        if (currentBackend?.isActive() == true) {
            AppLogger.w(TAG, "Start failed but ${currentBackend?.getType()} is still enforcing - not reporting down")
            _firewallState.value = FirewallState.Error(message = stateMessage, lastBackend = backend)
            emitStateChangeBroadcast(_firewallState.value)
            return
        }

        currentBackend = null
        _activeBackendType.value = null
        // afterStartAttempt: the check above is evidence that nothing is enforcing, so this Down
        // supersedes any standing StopFailed rather than being suppressed by it.
        reportFirewallDown(reason, backend, stateMessage, afterStartAttempt = true)
    }

    /**
     * A health check passed: drop any stale warning, and claim nothing else.
     *
     * Deliberately narrower than [reportFirewallHealthy]. This runs on a timer for as long as a
     * backend is up, so it must never touch [_isFirewallDown].
     *
     * Guarded on [_activeBackendType], which every "firewall is down" path nulls before reporting.
     * The guard matters: the manual-VPN branch of the health loop reports a pass without checking
     * anything at all, and [handleVpnConflictFallbackFailed] deliberately leaves the health job
     * running. Without it, a VPN-conflict warning was erased one tick after it appeared, and with
     * it went the flag that drives recovery.
     */
    private fun clearHealthWarningIfEnforcing() {
        if (_activeBackendType.value == null) {
            AppLogger.d(TAG, "Health check passed but no active backend - keeping the current warning")
            return
        }
        // A healthy backend is NOT evidence that a different backend's rules are gone. StopFailed
        // means something is still enforcing that no control in the app can touch, and the running
        // backend passing its own health check says nothing about it. Without this guard the next
        // health check, 15 seconds later, silently erased every orphan warning a backend switch
        // raised. Only a proven teardown clears it, in reportFirewallHealthy.
        if (_firewallHealth.value is FirewallHealth.StopFailed) {
            AppLogger.d(TAG, "Health check passed but a backend is still stuck - keeping the warning")
            return
        }

        _firewallHealth.value = FirewallHealth.Healthy
        dismissBackendFailedNotification()
    }

    private suspend fun handleBackendFailure(failedBackendType: FirewallBackendType) = startStopMutex.withLock {
        AppLogger.e(TAG, "=== BACKEND FAILURE DETECTED: $failedBackendType ===")

        val currentMode = getCurrentMode()
        val wasManualSelection = currentMode != FirewallMode.AUTO

        if (wasManualSelection) {
            // A hand-picked backend that stops working used to stop here: report DOWN and wait for
            // the user. For a firewall that means OFF rather than "protected by something else",
            // and the phone may sit in a pocket for hours before anyone reads the notification.
            // A weaker backend beats no backend, so it switches on its own now.
            //
            // Nothing special is needed to do it - the AUTO machinery below is already careful, and
            // computeStartPlan falls back to AUTO by itself when the stored mode's backend is
            // unavailable. So the manual case now gets exactly what AUTO always got: VPN permission
            // checked, a third-party VPN respected, real failures still reported.
            //
            // The stored mode is deliberately NOT rewritten. It is what handlePrivilegeChange uses
            // to put the user back on their real choice the moment its privileges return.
            AppLogger.w(
                TAG,
                "Manual backend $currentMode ($failedBackendType) failed - switching automatically rather than staying down; keeping $currentMode as the stored choice"
            )
        }

        val effectiveMode = currentMode

        val planResult = computeStartPlan(effectiveMode)
        if (planResult.isFailure) {
            val error = planResult.exceptionOrNull()
            AppLogger.e(TAG, "handleBackendFailure: Failed to compute start plan after backend failure", error)

            currentBackend = null
            _activeBackendType.value = null
            reportFirewallDown(
                reason = FirewallHealth.Down.Reason.NO_FALLBACK_PLAN,
                backend = failedBackendType,
                stateMessage = "Failed to compute fallback plan: ${error?.message}"
            )
            return@withLock
        }

        val plan = planResult.getOrThrow()
        AppLogger.d(TAG, "handleBackendFailure: planner selected backend ${plan.selectedBackendType}")

        if (plan.selectedBackendType != FirewallBackendType.VPN) {
            val result = startFirewallInternal(plan.mode)
            result.onSuccess { backendType ->
                AppLogger.d(TAG, "✅ Backend failure handled via planner: switched to $backendType")
            }.onFailure { error ->
                AppLogger.e(TAG, "❌ Failed to start fallback backend via planner: ${error.message}")
                currentBackend = null
                _activeBackendType.value = null
                reportFirewallDown(
                    reason = FirewallHealth.Down.Reason.FALLBACK_FAILED,
                    backend = failedBackendType,
                    stateMessage = "Fallback start failed: ${error.message}"
                )
            }
            return@withLock
        }

        AppLogger.e(TAG, "Planner selected VPN fallback, checking VPN permission...")

        // Check if another VPN is active before calling VpnService.prepare()
        // This prevents killing user's third-party VPN (like Proton VPN)
        val isAnotherVpnActive = isAnotherVpnActive()

        if (isAnotherVpnActive) {
            AppLogger.e(TAG, "Another VPN is active - reporting VPN conflict")

            currentBackend = null
            _activeBackendType.value = null
            reportFirewallDown(
                reason = FirewallHealth.Down.Reason.VPN_CONFLICT,
                backend = failedBackendType,
                stateMessage = "VPN conflict - another VPN is active"
            )

            startVpnPermissionMonitoring()
            return@withLock
        }

        val prepareIntent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check VPN permission", e)
            null
        }

        if (prepareIntent == null) {
            AppLogger.d(TAG, "VPN permission granted - attempting automatic VPN fallback via startFirewall(plan.mode)...")

            val result = startFirewallInternal(plan.mode)
            result.onSuccess { backendType ->
                AppLogger.d(TAG, "✅ VPN fallback successful via planner: backend=$backendType")
                // startFirewallInternal ends on Healthy; say the switch, unless VPN itself failed and was only restarted.
                if (failedBackendType != FirewallBackendType.VPN) {
                    _firewallHealth.value = FirewallHealth.SwitchedToVpn(
                        failedBackend = failedBackendType,
                        fromManualMode = wasManualSelection
                    )
                }
            }.onFailure { error ->
                AppLogger.e(TAG, "❌ VPN fallback FAILED via planner: ${error.message}")
                currentBackend = null
                _activeBackendType.value = null
                reportFirewallDown(
                    reason = FirewallHealth.Down.Reason.FALLBACK_FAILED,
                    backend = failedBackendType,
                    stateMessage = "VPN fallback failed: ${error.message}"
                )
            }
        } else {
            AppLogger.e(TAG, "VPN permission not granted - reporting, which shows the fallback notification...")

            // Update state to reflect firewall is down.
            // reportFirewallDown also preserves user intent: when the user grants VPN permission or
            // handlePrivilegeChange() runs, it checks isFirewallDown and attempts recovery.
            currentBackend = null
            _activeBackendType.value = null
            reportFirewallDown(
                reason = FirewallHealth.Down.Reason.VPN_PERMISSION_REQUIRED,
                backend = failedBackendType,
                stateMessage = "VPN permission required for fallback"
            )

            startVpnPermissionMonitoring()
        }
    }

    /**
     * Legacy VPN fallback helper.
     *
     * Retained only for manual-initiated fallback flows (e.g., MainActivity) that
     * specifically expect a direct VPN start. Internal health/privilege handling
     * should prefer planner-based [startFirewall] and [handleBackendFailure].
     */
    private suspend fun startVpnFallback(wasManualSelection: Boolean, failedBackendType: FirewallBackendType?) {
        try {
            stopMonitoring()

            val vpnBackend = VpnFirewallBackend(context)

            AppLogger.d(TAG, "Starting VPN backend as fallback (legacy path)...")
            vpnBackend.start().getOrElse { error ->
                AppLogger.e(TAG, "❌ CRITICAL: VPN fallback FAILED: ${error.message}")

                currentBackend = null
                _activeBackendType.value = null
                reportFirewallDown(
                    reason = FirewallHealth.Down.Reason.FALLBACK_FAILED,
                    backend = failedBackendType,
                    stateMessage = "VPN fallback failed"
                )

                return
            }

            delay(1000)

            if (!vpnBackend.isActive()) {
                AppLogger.e(TAG, "❌ CRITICAL: VPN fallback started but not active!")

                currentBackend = null
                _activeBackendType.value = null
                reportFirewallDown(
                    reason = FirewallHealth.Down.Reason.FALLBACK_FAILED,
                    backend = failedBackendType,
                    stateMessage = "VPN fallback VPN not active"
                )

                return
            }

            AppLogger.d(TAG, "✅ VPN fallback successful (legacy path)!")

            currentBackend = vpnBackend
            _activeBackendType.value = FirewallBackendType.VPN
            _firewallState.value = FirewallState.Running(FirewallBackendType.VPN)
            emitStateChangeBroadcast(_firewallState.value)

            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, true).apply()

            // Protection is restored - clear the down state and both failure notifications.
            reportFirewallHealthy()
            dismissVpnFallbackNotification()

            applyRules()

            if (failedBackendType != null) {
                _firewallHealth.value = FirewallHealth.SwitchedToVpn(
                    failedBackend = failedBackendType,
                    fromManualMode = wasManualSelection
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ CRITICAL: Exception during VPN fallback", e)

            currentBackend = null
            _activeBackendType.value = null
            reportFirewallDown(
                reason = FirewallHealth.Down.Reason.FALLBACK_FAILED,
                backend = failedBackendType,
                stateMessage = "Exception during VPN fallback: ${e.message}"
            )
        }
    }

    /**
     * [resolvedMode] null: a running backend died, so the tap opens MainActivity's "Enable VPN". Set: a widget/tile
     * start where nothing failed, so the tap goes to VpnPermissionActivity, which starts in the mode the receiver resolved.
     */
    private fun showVpnFallbackNotification(resolvedMode: FirewallMode? = null) {
        val fromBackgroundStart = resolvedMode != null
        AppLogger.d(TAG, "Showing VPN permission notification (backgroundStart=$fromBackgroundStart, mode=$resolvedMode)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.VpnFallback.CHANNEL_ID,
                Constants.VpnFallback.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for VPN fallback when privileged backends fail"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // The component must be explicit for the PendingIntent to resolve. The two branches target
        // different classes, so their PendingIntents never collide on request code 0.
        val intent = if (resolvedMode != null) {
            Intent(context, VpnPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(VpnPermissionActivity.EXTRA_RESOLVED_MODE, resolvedMode.name)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                action = Constants.Notifications.ACTION_ENABLE_VPN_FALLBACK
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationTitle = if (fromBackgroundStart) {
            context.getString(R.string.vpn_permission_notification_title)
        } else {
            context.getString(R.string.vpn_fallback_notification_title)
        }
        val notificationText = if (fromBackgroundStart) {
            context.getString(R.string.vpn_permission_notification_text)
        } else {
            context.getString(R.string.vpn_fallback_notification_text)
        }
        val notificationAction = if (fromBackgroundStart) {
            context.getString(R.string.vpn_permission_notification_action_text)
        } else {
            context.getString(R.string.vpn_fallback_notification_action_text)
        }

        val notification = NotificationCompat.Builder(context, Constants.VpnFallback.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Three detectors find the same failure within seconds - FirewallManager's health check,
            // PrivilegedFirewallService's, then the next pass. Measured on device: 3 heads-up alerts
            // in 17s for one event. Re-posting now updates the notification silently instead.
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_shield,
                notificationAction,
                pendingIntent
            )
            .build()

        notificationManager.notify(Constants.VpnFallback.NOTIFICATION_ID, notification)
    }

    private fun dismissVpnFallbackNotification() {
        AppLogger.d(TAG, "Dismissing VPN fallback notification")
        notificationManager.cancel(Constants.VpnFallback.NOTIFICATION_ID)
    }

    private fun showVpnConflictNotification() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastVpnConflictNotificationTime

        if (elapsed in 1 until VPN_CONFLICT_NOTIFICATION_DEBOUNCE_MS) {
            AppLogger.d(TAG, "Skipping VPN conflict notification - last shown ${elapsed}ms ago")
            return
        }

        lastVpnConflictNotificationTime = now
        AppLogger.d(TAG, "Showing VPN conflict notification")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.VpnFallback.CHANNEL_ID,
                Constants.VpnFallback.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for VPN fallback when privileged backends fail"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Constants.Notifications.ACTION_ENABLE_VPN_FALLBACK
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationTitle = context.getString(R.string.vpn_conflict_notification_title)
        val notificationText = context.getString(R.string.vpn_conflict_notification_text)
        val notificationAction = context.getString(R.string.vpn_conflict_notification_action_text)

        val notification = NotificationCompat.Builder(context, Constants.VpnFallback.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Three detectors find the same failure within seconds - FirewallManager's health check,
            // PrivilegedFirewallService's, then the next pass. Measured on device: 3 heads-up alerts
            // in 17s for one event. Re-posting now updates the notification silently instead.
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_shield,
                notificationAction,
                pendingIntent
            )
            .build()

        notificationManager.notify(Constants.VpnFallback.NOTIFICATION_ID, notification)
    }


    private fun showFirewallDownNotification(
        reason: FirewallHealth.Down.Reason,
        failedBackendType: FirewallBackendType?
    ) {
        AppLogger.d(TAG, "Showing firewall down notification ($reason, backend=$failedBackendType)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.BackendFailure.CHANNEL_ID,
                Constants.BackendFailure.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when preferred firewall backend fails"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Same wording as the in-app banner, so the notification and the banner cannot drift apart.
        // This is also what makes the text translatable - it used to be assembled here in English.
        // Non-null for every Down state; the presenter returns null only for Healthy.
        val health = FirewallHealth.Down(reason, failedBackendType)
        val title = FirewallHealthPresenter.title(context, health).orEmpty()
        val body = FirewallHealthPresenter.message(context, health).orEmpty()

        val notification = NotificationCompat.Builder(context, Constants.BackendFailure.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Three detectors find the same failure within seconds - FirewallManager's health check,
            // PrivilegedFirewallService's, then the next pass. Measured on device: 3 heads-up alerts
            // in 17s for one event. Re-posting now updates the notification silently instead.
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(Constants.BackendFailure.NOTIFICATION_ID, notification)
    }

    private fun dismissBackendFailedNotification() {
        AppLogger.d(TAG, "Dismissing backend failed notification")
        notificationManager.cancel(Constants.BackendFailure.NOTIFICATION_ID)
    }

    /** Runs on Dispatchers.IO, for the same reason as [startFirewall]. */
    suspend fun startVpnFallbackManually() = withContext(Dispatchers.IO) { startStopMutex.withLock {
        AppLogger.d(TAG, "Starting VPN fallback manually after permission grant")

        val prepareIntent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check VPN permission", e)
            return@withLock
        }

        if (prepareIntent != null) {
            AppLogger.e(TAG, "VPN permission still not granted - cannot start fallback")
            return@withLock
        }

        // Only a privileged backend's failure is a switch; a VPN that lacked consent or lost its slot just starts.
        val failedBackend = (_firewallHealth.value as? FirewallHealth.Down)?.backend
            ?.takeIf { it != FirewallBackendType.VPN }
        startVpnFallback(wasManualSelection = false, failedBackendType = failedBackend)
    } }

    private fun startVpnPermissionMonitoring() {
        vpnPermissionMonitoringJob?.cancel()

        AppLogger.d(TAG, "Starting VPN permission monitoring")

        vpnPermissionMonitoringJob = scope.launch {
            var retryCount = 0
            val maxRetries = 30
            var delayMs = 2000L

            while (_isFirewallDown.value && retryCount < maxRetries) {
                delay(delayMs)
                // Another path may have restored protection during the delay; a pass now would re-post a stale conflict.
                if (!_isFirewallDown.value) break
                retryCount++

                AppLogger.d(TAG, "VPN permission monitoring: attempt $retryCount/$maxRetries")

                // Check if another VPN is active before calling VpnService.prepare()
                // This prevents killing user's third-party VPN (like Proton VPN) repeatedly
                val isAnotherVpnActive = isAnotherVpnActive()

                if (isAnotherVpnActive) {
                    AppLogger.d(TAG, "Another VPN still active - skipping permission check")
                    showVpnConflictNotification()
                    continue
                }

                val prepareIntent = try {
                    VpnService.prepare(context)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "VPN permission check failed: ${e.message}")
                    continue
                }

                if (prepareIntent == null) {
                    AppLogger.d(TAG, "✅ VPN permission granted - attempting automatic recovery")

                    dismissVpnFallbackNotification()

                    val mode = getCurrentMode()
                    val result = startFirewall(mode)

                    if (result.isSuccess) {
                        AppLogger.d(TAG, "✅ Automatic VPN fallback successful")
                        break
                    } else {
                        AppLogger.w(TAG, "⚠️ VPN fallback start failed: ${result.exceptionOrNull()?.message}")
                        delayMs = (delayMs * 1.5).toLong().coerceAtMost(16000L)
                    }
                } else {
                    AppLogger.d(TAG, "VPN permission not yet granted, will retry in ${delayMs}ms")
                }
            }

            if (retryCount >= maxRetries) {
                AppLogger.w(TAG, "VPN permission monitoring stopped after $maxRetries attempts")
            } else if (!_isFirewallDown.value) {
                AppLogger.d(TAG, "VPN permission monitoring stopped - firewall is now running")
            }
        }
    }

    /**
     * Trigger rule re-application (e.g., when policy changes).
     * This is a public method that can be called from outside to force rule re-application.
     *
     * When the default policy changes, we need to clear the ConnectivityManager backend's
     * applied policies cache to force re-evaluation of all packages.
     */
    fun triggerRuleReapplication() {
        AppLogger.d(TAG, "Triggering rule re-application (policy change)")

        val backend = currentBackend
        if (backend is ConnectivityManagerFirewallBackend) {
            backend.clearAppliedPoliciesCache()
            AppLogger.d(TAG, "Cleared ConnectivityManager applied policies cache")
        } else if (backend is NetworkPolicyManagerFirewallBackend) {
            backend.clearAppliedPoliciesCache()
            AppLogger.d(TAG, "Cleared NetworkPolicyManager applied policies cache")
        }

        // No pass is scheduled here. The only caller, SettingsViewModel.setDefaultFirewallPolicy,
        // also broadcasts FIREWALL_RULES_CHANGED, which PrivilegedFirewallService and
        // FirewallVpnService already turn into a rule application on the instance that owns
        // enforcement. Scheduling one here as well ran a second full pass over every package from
        // this class's own backend instance - the duplication the monitoring change removed
        // everywhere else. The cache clear above stays: it belongs to this instance and matters for
        // the next backend switch.
    }

    private suspend fun applyRules() {
        val backend = currentBackend ?: return
        applyRulesToBackend(backend).getOrElse { error ->
            AppLogger.e(TAG, "Failed to apply rules: ${error.message}")
        }
    }

    /**
     * Wait for a backend that was just asked to start to actually be up.
     *
     * `start()` on the privileged backends is fire-and-forget: it posts an Intent to
     * PrivilegedFirewallService and returns success immediately. The service then has to be created,
     * possibly with its process, and run `startInternal()` - which shells out twice, for
     * `iptables --version` and to create the chains.
     *
     * This used to be a single `delay(500)` followed by one `isActive()` call. Observed on hardware
     * 2026-08-27: right after an app update the device is busy enough that 500 ms is not enough, and
     * the failure branch calls `stop()` - so a start that was seconds from succeeding was actively
     * torn down, the user was told the firewall was DOWN, and nothing retried. It stayed down until
     * the app was opened by hand. `isActive()` even carries a comment about "after app reinstall".
     *
     * Polling also makes the healthy case FASTER, not slower: it returns on the first check at
     * ~200 ms instead of always sleeping 500 ms.
     */
    private suspend fun awaitBackendActive(
        backend: FirewallBackend,
        backendType: FirewallBackendType
    ): Boolean {
        val startedWaiting = System.currentTimeMillis()

        while (true) {
            kotlinx.coroutines.delay(BACKEND_ACTIVE_POLL_MS)

            if (backend.isActive()) {
                AppLogger.d(TAG, "$backendType became active after ${System.currentTimeMillis() - startedWaiting}ms")
                return true
            }

            if (System.currentTimeMillis() - startedWaiting >= BACKEND_ACTIVE_TIMEOUT_MS) {
                AppLogger.e(TAG, "$backendType did not become active within ${BACKEND_ACTIVE_TIMEOUT_MS}ms")
                return false
            }
        }
    }

    private suspend fun applyRulesToBackend(backend: FirewallBackend): Result<Unit> {
        return try {
            if (backend.getType() == FirewallBackendType.VPN) {
                return Result.success(Unit)
            }

            val rules = firewallRepository.getAllRules().first()

            // Read the live state rather than caching it. These used to be fields fed by this
            // class's own monitoring loop; that loop duplicated PrivilegedFirewallService and was
            // removed, which left the fields frozen at their initial values - and
            // FirewallRule.isBlockedOn(NetworkType.NONE) now blocks, so a frozen NONE would have
            // over-blocked every rule on each backend switch until the service corrected it.
            val networkType = networkStateMonitor.getCurrentNetworkType()
            val screenOn = screenStateMonitor.isScreenOn()

            backend.applyRules(rules, networkType, screenOn).getOrElse { error ->
                AppLogger.e(TAG, "Failed to apply rules to ${backend.getType()}: ${error.message}")
                return Result.failure(error)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error applying rules to ${backend.getType()}", e)
            Result.failure(e)
        }
    }

    fun isVpnActive(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                AppLogger.w(TAG, "ConnectivityManager not available")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                    if (capabilities != null) {
                        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val allNetworks = connectivityManager.allNetworks
                for (network in allNetworks) {
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                        return true
                    }
                }
            }

            false
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check VPN status", e)
            false
        }
    }

    /**
     * Check if ANOTHER VPN (not De1984's) is currently active on the device.
     *
     * This is used to avoid calling VpnService.prepare() when a third-party VPN
     * (like Proton VPN) is active, which would revoke their VPN connection.
     *
     * @return true if another VPN app is active, false if no VPN or only De1984's VPN is active
     */
    fun isAnotherVpnActive(): Boolean {
        val isAnyVpnActive = isVpnActive()

        if (!isAnyVpnActive) {
            return false
        }

        val currentBackendType = getActiveBackendType()
        if (currentBackendType == FirewallBackendType.VPN) {
            AppLogger.d(TAG, "isAnotherVpnActive: De1984's VPN is active, not another VPN")
            return false
        }

        AppLogger.d(TAG, "isAnotherVpnActive: Another VPN app is active (currentBackend=$currentBackendType)")
        return true
    }

    /**
     * The consent dialog the VPN backend still needs, or null when it can start as it is.
     * Never calls prepare() while another VPN is up: for a consented app that disconnects the other VPN.
     */
    fun vpnConsentIntent(): Intent? {
        if (isAnotherVpnActive()) return null
        return try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check VPN permission", e)
            null
        }
    }

    /**
     * Start monitoring privilege changes (root/Shizuku) for automatic backend switching.
     * This runs for the entire application lifetime, independent of UI lifecycle.
     *
     * When privileges become available (e.g., Shizuku starts after boot), the firewall
     * will automatically switch from VPN to a privileged backend (iptables/ConnectivityManager).
     *
     * When privileges are lost (e.g., Shizuku stops), the firewall will automatically
     * fall back to VPN.
     */
    private fun startPrivilegeMonitoring() {
        AppLogger.d(TAG, "Starting privilege monitoring for automatic backend switching")

        privilegeMonitoringJob?.cancel()
        privilegeMonitoringJob = scope.launch {
            combine(
                rootManager.rootStatus,
                shizukuManager.shizukuStatus
            ) { rootStatus, shizukuStatus ->
                Pair(rootStatus, shizukuStatus)
            }.collect { (rootStatus, shizukuStatus) ->
                handlePrivilegeChange(rootStatus, shizukuStatus)
            }
        }
    }

    /**
     * Start monitoring VPN state changes for external VPN conflict detection.
     * This runs for the entire application lifetime, independent of UI lifecycle.
     *
     * When another VPN app connects while DE1984's VPN backend is active:
     * 1. DE1984's VPN gets kicked out by the system (only one VPN can be active)
     * 2. This monitoring detects the external VPN connection
     * 3. If in AUTO mode: automatically switch to iptables/ConnectivityManager
     * 4. If in VPN mode: show notification and update UI to reflect conflict
     */
    private fun startVpnStateMonitoring() {
        AppLogger.i(TAG, "🔐 Starting VPN state monitoring for external VPN conflict detection")

        vpnStateMonitoringJob?.cancel()
        vpnStateMonitoringJob = scope.launch {
            networkStateMonitor.observeVpnState().collect { isAnyVpnActive ->
                handleVpnStateChange(isAnyVpnActive)
            }
        }
    }

    /**
     * Handle VPN state changes detected by NetworkStateMonitor.
     * Detects when another VPN app takes over the VPN slot and reacts accordingly.
     * 
     * IMPORTANT: This is called whenever ANY VPN connects or disconnects.
     * We need to distinguish between De1984's VPN and external VPNs.
     */
    private suspend fun handleVpnStateChange(isAnyVpnActive: Boolean) {
        val currentBackendType = activeBackendType.value
        val currentMode = getCurrentMode()
        
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val firewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)
        
        if (!firewallEnabled) {
            AppLogger.d(TAG, "🔐 VPN state changed but firewall not enabled - ignoring")
            return
        }

        // One detector, on every Android version.
        //
        // This used to ask NetworkStateMonitor to read the VPN's session name. That is dead on
        // arrival: Android redacts sessionId from getTransportInfo() for apps without
        // NETWORK_SETTINGS, so it always came back null and this always concluded "no other VPN" -
        // then logged that the tunnel was ours. Proved on device against a real ProtonVPN session.
        //
        // Android runs ONE VPN at a time, so a VPN being up while our backend is not the VPN one
        // already means somebody else owns it. That is what isAnotherVpnActive() answers, and it
        // needs no hidden field and no API level check.
        val isOtherVpnActive = isAnotherVpnActive()
        
        AppLogger.i(TAG, "🔐 VPN state change: isAnyVpnActive=$isAnyVpnActive, isOtherVpnActive=$isOtherVpnActive, currentBackend=$currentBackendType, mode=$currentMode")

        if (isOtherVpnActive) {
            AppLogger.w(TAG, "🔐 EXTERNAL VPN DETECTED (not De1984's VPN)")
            
            if (currentBackendType == FirewallBackendType.VPN) {
                AppLogger.w(TAG, "🔐 VPN CONFLICT: Another VPN is active while we're using VPN backend!")
                AppLogger.i(TAG, "🔐 Switching to privileged backend to maintain protection...")
                handleVpnConflict(currentMode)
                return
            }
            
            // Case 1b: We're using privileged backend but mode says VPN
            // Issue #68: Do NOT force AUTO mode - respect user's VPN preference
            // This state is unusual but possible during transitions. User chose VPN
            // because they want system app blocking. Leave mode as VPN so when the
            // external VPN disconnects, we can switch back to our VPN backend.
            if (currentMode == FirewallMode.VPN && currentBackendType != null) {
                AppLogger.i(TAG, "🔐 Mode is VPN but we're using $currentBackendType - keeping VPN mode (respecting user preference)")
                return
            }
            
            AppLogger.d(TAG, "🔐 External VPN active but we're protected with $currentBackendType backend")
            return
        }
        
        if (!isAnyVpnActive) {
            AppLogger.d(TAG, "🔐 No VPN active")
            
            if (currentBackendType == FirewallBackendType.VPN) {
                val isOurVpnStillActive = currentBackend?.isActive() == true
                if (!isOurVpnStillActive) {
                    AppLogger.w(TAG, "🔐 Our VPN backend appears to have stopped")
                }
            }
        } else {
            AppLogger.d(TAG, "🔐 De1984's VPN is active - no conflict")
        }
    }

    private suspend fun handleVpnConflict(currentMode: FirewallMode) {
        AppLogger.d(TAG, "🔐 Handling VPN conflict in mode: $currentMode")

        // Issue #68: If user explicitly chose VPN mode, respect their choice
        // Don't auto-switch to a backend that may not meet their needs
        if (currentMode == FirewallMode.VPN) {
            AppLogger.i(TAG, "🔐 User explicitly selected VPN mode - NOT auto-switching to privileged backend")
            AppLogger.i(TAG, "🔐 VPN conflict: Another VPN took over - firewall protection LOST (respecting user choice)")
            handleVpnConflictFallbackFailed()
            return
        }

        AppLogger.i(TAG, "🔐 AUTO mode: Attempting to switch to privileged backend due to VPN conflict...")

        val planResult = computeStartPlan(FirewallMode.AUTO)
        if (planResult.isFailure) {
            AppLogger.e(TAG, "🔐 Failed to compute fallback plan", planResult.exceptionOrNull())
            handleVpnConflictFallbackFailed()
            return
        }

        val plan = planResult.getOrThrow()

        if (plan.selectedBackendType != FirewallBackendType.VPN) {
            AppLogger.i(TAG, "🔐 Switching to ${plan.selectedBackendType} backend due to VPN conflict")

            var vpnConflictOrphan: Throwable? = null
            if (currentBackend != null) {
                AppLogger.d(TAG, "🔐 Stopping current VPN backend before switching...")
                stopMonitoring()
                vpnConflictOrphan = tearDownSwitchedAwayBackend(currentBackend, FirewallBackendType.VPN)
                currentBackend = null
                _activeBackendType.value = null
            }

            val restartResult = startFirewall(FirewallMode.AUTO)

            if (restartResult.isSuccess) {
                val newBackend = restartResult.getOrThrow()
                AppLogger.i(TAG, "🔐 ✅ Successfully switched to $newBackend due to VPN conflict")
                // Reported after the start, whose reportFirewallHealthy would otherwise erase it.
                vpnConflictOrphan?.let { reportOrphanedBackend(FirewallBackendType.VPN, it) }
                showVpnConflictSwitchNotification(newBackend)
            } else {
                AppLogger.e(TAG, "🔐 ❌ Failed to switch backend: ${restartResult.exceptionOrNull()?.message}")
                handleVpnConflictFallbackFailed()
            }
        } else {
            AppLogger.w(TAG, "🔐 No privileged backend available - VPN conflict cannot be resolved")
            handleVpnConflictFallbackFailed()
        }
    }

    private fun handleVpnConflictFallbackFailed() {
        AppLogger.w(TAG, "🔐 VPN conflict: No fallback available - firewall protection LOST")

        _activeBackendType.value = null

        // currentBackend is deliberately left alone here. The dead VPN backend must stay visible to
        // the health monitor, which is what drives recovery from this state.
        reportFirewallDown(
            reason = FirewallHealth.Down.Reason.VPN_CONFLICT,
            backend = FirewallBackendType.VPN,
            stateMessage = "Another VPN is active"
        )
    }

    private fun showVpnConflictSwitchNotification(newBackend: FirewallBackendType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    Constants.VpnConflict.CHANNEL_ID,
                    Constants.VpnConflict.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for firewall status and alerts"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Same name the Settings picker shows. "(root)" / "(Shizuku)" are requirements, not
            // names, and Settings already states those separately.
            val backendName = newBackend.displayName(context)
            
            val notification = NotificationCompat.Builder(context, Constants.VpnConflict.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.backend_switched_notification_title))
                .setContentText(context.getString(R.string.backend_switched_notification_text, backendName))
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.backend_switched_notification_big, backendName)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            
            notificationManager.notify(Constants.VpnConflict.NOTIFICATION_ID, notification)
            AppLogger.d(TAG, "🔐 Showed persistent VPN conflict switch notification")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show VPN conflict switch notification", e)
        }
    }

    fun dismissVpnConflictSwitchNotification() {
        try {
            notificationManager.cancel(Constants.VpnConflict.NOTIFICATION_ID)
            AppLogger.d(TAG, "🔐 Dismissed VPN conflict switch notification")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to dismiss VPN conflict switch notification", e)
        }
    }

    private fun showPrivilegeGainSwitchNotification(newBackend: FirewallBackendType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    Constants.VpnConflict.CHANNEL_ID,
                    Constants.VpnConflict.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for firewall status and alerts"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Same name the Settings picker shows. "(root)" / "(Shizuku)" are requirements, not
            // names, and Settings already states those separately.
            val backendName = newBackend.displayName(context)
            
            val notification = NotificationCompat.Builder(context, Constants.VpnConflict.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.backend_upgraded_notification_title))
                .setContentText(context.getString(R.string.backend_upgraded_notification_text, backendName))
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.backend_upgraded_notification_big, backendName)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(Constants.VpnConflict.NOTIFICATION_ID, notification)
            AppLogger.d(TAG, "🔐 Showed privilege gain switch notification")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show privilege gain switch notification", e)
        }
    }

    /**
     * Handle privilege changes (root/Shizuku status changes).
     * Automatically restarts firewall with new backend when privileges change.
     *
     * Rules:
     * - If mode is AUTO, recompute plan and restart only if backend type would change.
     * - If mode is MANUAL and selected backend is no longer viable, normalize to AUTO,
     *   recompute plan, and restart.
     *
     * @param forceCheck If true, bypass the duplicate check and always process
     */
    private suspend fun handlePrivilegeChange(
        rootStatus: RootStatus,
        shizukuStatus: ShizukuStatus,
        forceCheck: Boolean = false
    ) {
        if (!forceCheck &&
            rootStatus == lastProcessedRootStatus &&
            shizukuStatus == lastProcessedShizukuStatus) {
            AppLogger.d(TAG, "handlePrivilegeChange: Skipping - already processed this status combination")
            return
        }

        lastProcessedRootStatus = rootStatus
        lastProcessedShizukuStatus = shizukuStatus

        AppLogger.d(TAG, "Privilege change detected: root=$rootStatus, shizuku=$shizukuStatus")

        // CHECKING is not an answer. Acting on it treats "we do not know yet" as "there is none":
        // hasRootPermission is still false while the probe runs, so IptablesFirewallBackend fails
        // its availability check, AUTO falls all the way through to VPN, and the start then blocks
        // on VpnFirewallBackend's 10-second activation timeout before anything can correct it.
        //
        // Measured on hardware 2026-08-25, cold start on a rooted device:
        //
        //   03.107  Starting firewall with mode: AUTO      (root=CHECKING, shizuku=CHECKING)
        //   15.087  VPN service failed to become active after 10029ms (timeout)
        //   15.155  🚨 FIREWALL DOWN
        //   17.xxx  ✅ iptables is AVAILABLE - selecting iptables backend   (probe finished)
        //
        // That is issue #91: the quick tile sits on "Starting…" for about ten seconds and the user
        // sees a FIREWALL DOWN flash, for a device that had working root the whole time.
        //
        // A probe that finishes always emits again with a real status, so nothing is lost by
        // waiting - the same reasoning as BootProtectionManager.isBootProtectionInstalled()
        // returning null rather than false when it cannot find out.
        if (rootStatus == RootStatus.CHECKING || shizukuStatus == ShizukuStatus.CHECKING) {
            AppLogger.d(TAG, "Privilege probe still running (root=$rootStatus, shizuku=$shizukuStatus) - waiting for a real answer")
            // Not recorded as processed: the next emission carries the actual status and must not
            // be skipped as a duplicate.
            lastProcessedRootStatus = null
            lastProcessedShizukuStatus = null
            return
        }

        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val firewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)
        val firewallDown = _isFirewallDown.value

        if (!firewallEnabled && !firewallDown) {
            AppLogger.d(TAG, "Firewall not enabled by user and not in 'down' state, skipping privilege change handling")
            return
        }

        AppLogger.d(TAG, "Firewall enabled=$firewallEnabled, firewall down=$firewallDown - proceeding with privilege change handling")

        val currentlyActive = isActive()
        AppLogger.d(TAG, "Firewall enabled=$firewallEnabled, currently active=$currentlyActive")

        val currentMode = getCurrentMode()
        val currentBackendType = activeBackendType.value

        val hasPrivileges = hasPrivilegedAccess(rootStatus, shizukuStatus)

        if (currentMode == FirewallMode.AUTO) {
            AppLogger.d(TAG, "AUTO mode: Computing plan to check if backend should switch...")
            AppLogger.d(TAG, "Current backend: $currentBackendType, Root: $rootStatus, Shizuku: $shizukuStatus")

            val planResult = computeStartPlan(FirewallMode.AUTO)
            if (planResult.isFailure) {
                AppLogger.e(TAG, "handlePrivilegeChange: Failed to compute plan in AUTO mode", planResult.exceptionOrNull())
                return
            }

            val plan = planResult.getOrThrow()
            val plannedBackendType = plan.selectedBackendType

            AppLogger.d(TAG, "Planner result: current=$currentBackendType, planned=$plannedBackendType")

            if (plannedBackendType == currentBackendType) {
                AppLogger.d(TAG, "Privilege change does not require backend switch in AUTO mode (current=$currentBackendType, planned=$plannedBackendType)")
                return
            }

            if (hasPrivileges) {
                AppLogger.d(TAG, "Privilege gain: planner suggests backend change $currentBackendType → $plannedBackendType, restarting firewall...")
            } else {
                AppLogger.d(TAG, "Privilege loss: planner suggests backend change $currentBackendType → $plannedBackendType, restarting firewall with fallback backend...")
            }

            // NOTE: We stop the current backend before calling startFirewall() so that
            // startFirewall() sees no existing backend and performs a fresh start rather than
            // an atomic switch. This creates a brief security gap (~1-2s) where apps are
            // unprotected. A future improvement could refactor this to use atomic switching
            // (start new backend first, then stop old) as documented in FIREWALL.md.
            var privilegeChangeOrphan: Throwable? = null
            if (currentBackend != null) {
                AppLogger.d(TAG, "Stopping current backend ($currentBackendType) before switching to $plannedBackendType...")
                stopMonitoring()
                privilegeChangeOrphan = tearDownSwitchedAwayBackend(currentBackend, currentBackendType)
                currentBackend = null
                _activeBackendType.value = null
            }

            val result = startFirewall(FirewallMode.AUTO)
            result.onSuccess { newBackend ->
                AppLogger.d(TAG, "✅ Firewall automatically switched to $newBackend backend (AUTO mode)")
                // Reported after the start, whose reportFirewallHealthy would otherwise erase it.
                privilegeChangeOrphan?.let { reportOrphanedBackend(currentBackendType, it) }
            }.onFailure { error ->
                AppLogger.e(TAG, "❌ Failed to automatically switch backend in AUTO mode: ${error.message}")
            }
            return
        }

        // Manual mode: respect user choice as long as backend remains viable.
        // If manual backend becomes invalid under new privileges, keep firewall down and notify user.
        if (currentBackendType == null) {
            if (_isFirewallDown.value) {
                AppLogger.d(TAG, "Manual mode $currentMode with firewall down - attempting automatic recovery")
                val restartResult = startFirewall(currentMode)
                restartResult.onSuccess { backendType ->
                    AppLogger.d(TAG, "✅ Manual backend $backendType restarted after privilege recovery")
                    dismissBackendFailedNotification()
                }.onFailure { error ->
                    AppLogger.e(TAG, "❌ Failed to restart manual backend $currentMode: ${error.message}")
                }
            } else {
                AppLogger.d(TAG, "Manual mode $currentMode but no active backend and firewall disabled by user - nothing to do")
            }
            return
        }

        val availabilityResult = try {
            val backend = selectBackend(currentMode).getOrNull()
            backend?.checkAvailability()
        } catch (e: Exception) {
            AppLogger.e(TAG, "handlePrivilegeChange: exception while checking availability for manual mode $currentMode", e)
            null
        }

        val stillViable = availabilityResult?.isSuccess == true

        if (stillViable) {
            AppLogger.d(TAG, "Manual mode $currentMode with backend $currentBackendType still viable after privilege change; keeping manual selection")
            return
        }

        // A privilege change cannot break a running VPN fallback; a dead one still fails over below.
        if (currentBackendType == FirewallBackendType.VPN && isActive()) {
            AppLogger.d(TAG, "Manual backend $currentMode still unavailable after privilege change; staying on the VPN fallback")
            return
        }

        AppLogger.e(TAG, "Manual backend $currentMode/$currentBackendType no longer viable after privilege change; keeping manual mode and notifying user")
        handleBackendFailure(currentBackendType)
    }


    /** Runs on Dispatchers.IO: [handlePrivilegeChange] can start and stop backends. */
    suspend fun checkBackendShouldSwitch() = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "checkBackendShouldSwitch: Explicitly checking if backend should switch")

        if (currentHealthCheckInterval != Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS) {
            AppLogger.d(TAG, "Resetting health check interval to fast mode (15s) for quick privilege change detection")
            consecutiveSuccessfulHealthChecks = 0
            currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS
        }

        val rootStatus = rootManager.rootStatus.value
        val shizukuStatus = shizukuManager.shizukuStatus.value

        handlePrivilegeChange(rootStatus, shizukuStatus, forceCheck = true)
    }
}

