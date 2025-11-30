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
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.monitor.NetworkStateMonitor
import io.github.dorumrr.de1984.data.monitor.ScreenStateMonitor
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.ui.MainActivity
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

/**
 * Manages firewall backend selection and lifecycle.
 *
 * Responsibilities:
 * - Select appropriate backend based on mode and availability
 * - Start/stop firewall backends
 * - Monitor network and screen state changes
 * - Apply rules reactively when state changes
 * - Provide current backend information to UI
 */
class FirewallManager(
    private val context: Context,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val errorHandler: ErrorHandler,
    private val firewallRepository: FirewallRepository,
    private val networkStateMonitor: NetworkStateMonitor,
    private val screenStateMonitor: ScreenStateMonitor
) {
    companion object {
        private const val TAG = "FirewallManager"
        private const val RULE_APPLICATION_DEBOUNCE_MS = 300L
        private const val VPN_CONFLICT_NOTIFICATION_DEBOUNCE_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val startStopMutex = Mutex()  // Synchronize start/stop operations
    private var monitoringJob: Job? = null
    private var ruleChangeMonitoringJob: Job? = null
    private var ruleApplicationJob: Job? = null
    private var healthMonitoringJob: Job? = null
    private var privilegeMonitoringJob: Job? = null
    private var vpnPermissionMonitoringJob: Job? = null

    // Adaptive health check tracking
    private var consecutiveSuccessfulHealthChecks = 0
    private var currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

    private var currentBackend: FirewallBackend? = null
    private var lastVpnConflictNotificationTime = 0L

    /**
     * Canonical firewall state exposed to the rest of the app.
     *
     * Phase 2 (minimal): this is emitted best‑effort from existing lifecycle
     * points (initializeBackendState/start/stop). It intentionally mirrors
     * activeBackendType and prefs, without yet enforcing all protection
     * invariants from FIREWALL_BACKEND_RELIABILITY_PLAN.md §2/§4.1.
     */
    sealed class FirewallState {
        /** No backend is currently running. */
        object Stopped : FirewallState()

        /** A backend transition/start has been requested but not yet confirmed. */
        data class Starting(val backend: FirewallBackendType?) : FirewallState()

        /** A backend is running and has reported active. */
        data class Running(val backend: FirewallBackendType) : FirewallState()

        /**
         * An error occurred while starting/switching/stopping the firewall.
         *
         * Phase 2 keeps this simple; richer typed reasons are planned for
         * later phases.
         */
        data class Error(val message: String, val lastBackend: FirewallBackendType?) : FirewallState()
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val _activeBackendType = MutableStateFlow<FirewallBackendType?>(null)
    val activeBackendType: StateFlow<FirewallBackendType?> = _activeBackendType.asStateFlow()

    private val _backendHealthWarning = MutableStateFlow<String?>(null)
    val backendHealthWarning: StateFlow<String?> = _backendHealthWarning.asStateFlow()

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

    private var currentNetworkType: NetworkType = NetworkType.NONE
    private var isScreenOn: Boolean = true

    // Track last processed privilege status to prevent duplicate restarts
    private var lastProcessedRootStatus: RootStatus? = null
    private var lastProcessedShizukuStatus: ShizukuStatus? = null

    init {
        // Initialize backend state on startup
        initializeBackendState()

        // Start monitoring privilege changes for automatic backend switching
        startPrivilegeMonitoring()
    }

    /**
     * Initialize backend state by detecting if any backend is currently running.
     * This is needed when the app starts and a backend (e.g., VPN service) is already running.
     */
    private fun initializeBackendState() {
        scope.launch {
            try {
                // Add initial delay to let services update SharedPreferences
                // This prevents race condition where we check before service has started
                delay(200)

                // Try multiple times with exponential backoff to detect running backend
                // This handles cases where service is starting but not yet fully active
                var attempts = 0
                val maxAttempts = 5

                while (attempts < maxAttempts) {
                    Log.d(TAG, "initializeBackendState: Attempt ${attempts + 1}/$maxAttempts")

                    // Check if VPN service is running
                    val vpnBackend = VpnFirewallBackend(context)
                    if (vpnBackend.isActive()) {
                        Log.d(TAG, "Detected VPN backend running on startup (attempt ${attempts + 1})")
                        currentBackend = vpnBackend
                        _activeBackendType.value = FirewallBackendType.VPN
                        _firewallState.value = FirewallState.Running(FirewallBackendType.VPN)
                        // VPN monitors internally, no need to start monitoring
                        startBackendHealthMonitoring()
                        return@launch
                    }

                    // Check if iptables backend is running
                    val iptablesBackend = IptablesFirewallBackend(context, rootManager, shizukuManager, errorHandler)
                    if (iptablesBackend.isActive()) {
                        Log.d(TAG, "Detected iptables backend running on startup (attempt ${attempts + 1})")
                        currentBackend = iptablesBackend
                        _activeBackendType.value = FirewallBackendType.IPTABLES
                        _firewallState.value = FirewallState.Running(FirewallBackendType.IPTABLES)
                        startMonitoring()
                        startBackendHealthMonitoring()
                        return@launch
                    }

                    // Check if ConnectivityManager backend is running
                    val cmBackend = ConnectivityManagerFirewallBackend(context, shizukuManager, errorHandler)
                    if (cmBackend.isActive()) {
                        Log.d(TAG, "Detected ConnectivityManager backend running on startup (attempt ${attempts + 1})")
                        currentBackend = cmBackend
                        _activeBackendType.value = FirewallBackendType.CONNECTIVITY_MANAGER
                        _firewallState.value = FirewallState.Running(FirewallBackendType.CONNECTIVITY_MANAGER)
                        startMonitoring()
                        startBackendHealthMonitoring()
                        return@launch
                    }

                    // Check if NetworkPolicyManager backend is running
                    val npmBackend = NetworkPolicyManagerFirewallBackend(context, shizukuManager, errorHandler)
                    if (npmBackend.isActive()) {
                        Log.d(TAG, "Detected NetworkPolicyManager backend running on startup (attempt ${attempts + 1})")
                        currentBackend = npmBackend
                        _activeBackendType.value = FirewallBackendType.NETWORK_POLICY_MANAGER
                        _firewallState.value = FirewallState.Running(FirewallBackendType.NETWORK_POLICY_MANAGER)
                        startMonitoring()
                        startBackendHealthMonitoring()
                        return@launch
                    }

                    attempts++
                    if (attempts < maxAttempts) {
                        // Exponential backoff: 100ms, 200ms, 300ms, 400ms
                        val delayMs = 100L * attempts
                        Log.d(TAG, "No backend detected, retrying in ${delayMs}ms...")
                        delay(delayMs)
                    }
                }

                // After all attempts, check if firewall should be running
                Log.d(TAG, "No backend detected running on startup after $maxAttempts attempts")
                val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                val shouldBeRunning = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

                if (shouldBeRunning) {
                    Log.w(TAG, "Firewall should be running but no backend detected - attempting restart")
                    // Attempt to restart firewall
                    val mode = getCurrentMode()
                    startFirewall(mode).onFailure { error ->
                        Log.e(TAG, "Failed to restart firewall on initialization: ${error.message}")
                        _firewallState.value = FirewallState.Error(
                            "Firewall should be running but failed to restart: ${error.message}",
                            null
                        )
                    }
                } else {
                    _firewallState.value = FirewallState.Stopped
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing backend state", e)
                _firewallState.value = FirewallState.Error("Error initializing backend state: ${e.message}", _activeBackendType.value)
            }
        }
    }

    /**
     * Get the current firewall mode from settings.
     */
    fun getCurrentMode(): FirewallMode {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val modeString = prefs.getString(
            Constants.Settings.KEY_FIREWALL_MODE,
            Constants.Settings.DEFAULT_FIREWALL_MODE
        )
        return FirewallMode.fromString(modeString) ?: FirewallMode.AUTO
    }

    /**
     * Set the firewall mode in settings.
     */
    fun setMode(mode: FirewallMode) {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(
            Constants.Settings.KEY_FIREWALL_MODE,
            FirewallMode.Companion.run { mode.toStorageString() }
        ).apply()
        Log.d(TAG, "Firewall mode set to: $mode")
    }
    /**
     * Internal plan used to decide which backend will be used and what permissions
     * are required. This is a minimal version for Phase 0/1 focused on VPN
     * permission and backend type only. It mirrors the existing selectBackend
     * behavior as closely as possible.
     */
    data class FirewallStartPlan(
        val mode: FirewallMode,
        val selectedBackendType: FirewallBackendType,
        val requiresVpnPermission: Boolean
    )

    /**
     * Compute a minimal start plan for the current (or provided) firewall mode.
     *
     * This currently focuses on:
     * - Which backend type would be selected, based on existing selectBackend logic.
     * - Whether VPN permission will be required (when backend is VPN).
     *
     * It does **not** change behavior of startFirewall; it only centralizes
     * the decision making so callers (e.g., FirewallViewModel) no longer
     * duplicate the logic.
     */
    suspend fun computeStartPlan(mode: FirewallMode = getCurrentMode()): Result<FirewallStartPlan> {
        Log.d(TAG, "computeStartPlan: Computing start plan for mode: $mode")

        // Reuse existing backend selection to avoid behavior drift.
        val backendResult = selectBackend(mode)

        if (backendResult.isFailure) {
            val error = backendResult.exceptionOrNull()
            Log.e(TAG, "computeStartPlan: Failed to select backend", error)
            return Result.failure(error ?: Exception("Failed to select backend for mode=$mode"))
        }

        val backend = backendResult.getOrThrow()
        val backendType = backend.getType()
        val requiresVpnPermission = backendType == FirewallBackendType.VPN

        Log.d(
            TAG,
            "computeStartPlan: mode=$mode, backendType=$backendType, requiresVpnPermission=$requiresVpnPermission"
        )

        return Result.success(
            FirewallStartPlan(
                mode = mode,
                selectedBackendType = backendType,
                requiresVpnPermission = requiresVpnPermission
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
    suspend fun startFirewall(mode: FirewallMode = getCurrentMode()): Result<FirewallBackendType> = startStopMutex.withLock {
        return try {
            Log.d(TAG, "Starting firewall with mode: $mode")

            // CRITICAL: Check if another VPN is active AND we don't have privileged access
            // This prevents killing user's third-party VPN (like Proton VPN) during:
            // 1. App updates (ACTION_MY_PACKAGE_REPLACED)
            // 2. Device boot (ACTION_BOOT_COMPLETED)
            // 3. Any other scenario where startFirewall() is called before root status is checked
            //
            // If another VPN is active but we have root/Shizuku, we can still use iptables/CM backend.
            // Only fail if another VPN is active AND we don't have privileged access (would need VPN backend).
            if (isAnotherVpnActive()) {
                // Check if we have privileged access (root or Shizuku)
                val hasRoot = rootManager.hasRootPermission
                val hasShizuku = shizukuManager.hasShizukuPermission
                val hasPrivilegedAccess = hasRoot || hasShizuku

                if (!hasPrivilegedAccess) {
                    Log.w(TAG, "startFirewall: Another VPN is active and no privileged access - cannot start firewall")
                    Log.w(TAG, "startFirewall: User needs to disconnect their VPN or grant root/Shizuku access")

                    // Don't start the firewall - we would need VPN backend but another VPN is active
                    val error = Exception("Another VPN is active and no privileged access")
                    _firewallState.value = FirewallState.Error(
                        message = "Another VPN is active",
                        lastBackend = activeBackendType.value
                    )
                    return Result.failure(error)
                } else {
                    Log.d(TAG, "startFirewall: Another VPN is active but we have privileged access - will use iptables/CM backend")
                }
            }

            // Compute start plan first so planner is single source of truth
            val planResult = computeStartPlan(mode)
            if (planResult.isFailure) {
                val error = planResult.exceptionOrNull()
                Log.e(TAG, "startFirewall: Failed to compute start plan", error)
                _firewallState.value = FirewallState.Error(
                    message = "Failed to compute start plan: ${error?.message}",
                    lastBackend = activeBackendType.value
                )
                return Result.failure(error ?: Exception("Failed to compute start plan"))
            }

            val plan = planResult.getOrThrow()
            Log.d(
                TAG,
                "startFirewall: Using plan → mode=${plan.mode}, backend=${plan.selectedBackendType}, requiresVpn=${plan.requiresVpnPermission}"
            )

            // Store old backend info BEFORE any changes
            val oldBackend = currentBackend
            val wasGranular = oldBackend?.supportsGranularControl() ?: false
            val oldBackendType = oldBackend?.getType()

            // We are about to attempt a backend start/switch
            _firewallState.value = FirewallState.Starting(oldBackendType)

            // Instantiate new backend based on planner decision
            val newBackend = selectBackend(plan.mode).getOrElse { error ->
                // This should normally succeed because computeStartPlan already called selectBackend,
                // but we keep this defensive to avoid crashes if something changes.
                Log.e(TAG, "Failed to select backend during start: ${error.message}")
                _firewallState.value = FirewallState.Error(
                    message = "Failed to select backend: ${error.message}",
                    lastBackend = oldBackendType
                )
                return Result.failure(error)
            }

            val newBackendType = newBackend.getType()

            if (oldBackendType == newBackendType) {
                if (!oldBackend.isActive()) {
                    oldBackend.start().getOrElse { error ->
                        Log.e(TAG, "Failed to restart backend: ${error.message}")
                        _firewallState.value = FirewallState.Error(
                            message = "Failed to restart backend: ${error.message}",
                            lastBackend = oldBackendType
                        )
                        return Result.failure(error)
                    }
                }

                // Same backend, successfully (re)started
                _firewallState.value = FirewallState.Running(newBackendType)
                return Result.success(oldBackendType)
            }

            // Different backend - perform atomic switch
            Log.d(TAG, "Backend switch: $oldBackendType → $newBackendType")

            // Check if we're switching from granular to non-granular
            val isGranular = newBackend.supportsGranularControl()
            val needsMigration = wasGranular && !isGranular

            if (needsMigration) {
                Log.d(TAG, "Backend transition: granular ($oldBackendType) → simple ($newBackendType), migrating rules...")
                migrateRulesToSimple()
            } else if (oldBackend != null) {
                Log.d(
                    TAG,
                    "Backend transition: $oldBackendType → $newBackendType, no migration needed (both granular or both simple)"
                )
            }

            // ATOMIC SWITCH: Start new backend FIRST, then stop old backend
            // This prevents security gap where apps are unblocked during transition
            Log.d(TAG, "Starting new backend ($newBackendType) BEFORE stopping old backend...")
            newBackend.start().getOrElse { error ->
                Log.e(TAG, "Failed to start new backend ($newBackendType): ${error.message}")
                // Keep old backend running if new one fails
                if (oldBackend != null && oldBackend.isActive()) {
                    Log.w(TAG, "Keeping old backend ($oldBackendType) running since new backend failed to start")
                    _firewallState.value = FirewallState.Running(oldBackend.getType())
                } else {
                    _firewallState.value = FirewallState.Error(
                        message = "Failed to start new backend: ${error.message}",
                        lastBackend = oldBackendType
                    )
                }
                return Result.failure(error)
            }

            // Apply rules to new backend to ensure it's fully active
            Log.d(TAG, "Applying rules to new backend ($newBackendType)...")
            applyRulesToBackend(newBackend).getOrElse { error ->
                Log.e(TAG, "Failed to apply rules to new backend: ${error.message}")
                // Try to stop the new backend since it's not working properly
                newBackend.stop()
                // Keep old backend running
                if (oldBackend != null && oldBackend.isActive()) {
                    Log.w(TAG, "Keeping old backend ($oldBackendType) running since new backend failed to apply rules")
                    _firewallState.value = FirewallState.Running(oldBackend.getType())
                } else {
                    _firewallState.value = FirewallState.Error(
                        message = "Failed to apply rules to new backend: ${error.message}",
                        lastBackend = oldBackendType
                    )
                }
                return Result.failure(error)
            }

            // Wait a moment to ensure new backend is fully established
            kotlinx.coroutines.delay(500)

            // Verify new backend is active
            if (!newBackend.isActive()) {
                Log.e(TAG, "New backend ($newBackendType) started but is not active!")
                newBackend.stop()
                if (oldBackend != null && oldBackend.isActive()) {
                    Log.w(TAG, "Keeping old backend ($oldBackendType) running since new backend is not active")
                    _firewallState.value = FirewallState.Running(oldBackend.getType())
                } else {
                    _firewallState.value = FirewallState.Error(
                        message = "New backend failed to become active",
                        lastBackend = oldBackendType
                    )
                }
                return Result.failure(Exception("New backend failed to become active"))
            }

            Log.d(TAG, "New backend ($newBackendType) is active, now stopping old backend ($oldBackendType)...")

            // Now it's safe to stop the old backend
            if (oldBackend != null) {
                stopMonitoring() // Stop monitoring for old backend
                oldBackend.stop().getOrElse { error ->
                    Log.w(TAG, "Failed to stop old backend ($oldBackendType): ${error.message}")
                    // Continue anyway - new backend is already running
                }
            }

            // Update current backend reference
            currentBackend = newBackend
            _activeBackendType.value = newBackendType
            _firewallState.value = FirewallState.Running(newBackendType)

            // Clear firewall down flag - firewall is now running successfully
            _isFirewallDown.value = false
            dismissBackendFailedNotification()

            // Start monitoring for iptables, ConnectivityManager, and NetworkPolicyManager backends
            // (VPN backend monitors internally)
            if (newBackendType == FirewallBackendType.IPTABLES ||
                newBackendType == FirewallBackendType.CONNECTIVITY_MANAGER ||
                newBackendType == FirewallBackendType.NETWORK_POLICY_MANAGER) {
                startMonitoring()
            }

            // Start continuous backend health monitoring for privileged backends
            // Per FIREWALL.md lines 92-96: continuously monitor backend availability
            startBackendHealthMonitoring()

            Log.d(TAG, "Firewall started successfully with backend: $newBackendType (atomic switch complete)")
            Result.success(newBackendType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start firewall", e)
            val error = errorHandler.handleError(e, "start firewall")
            _firewallState.value = FirewallState.Error(
                message = "Failed to start firewall: ${error.message}",
                lastBackend = _activeBackendType.value
            )
            Result.failure(error)
        }
    }

    /**
     * Stop the firewall.
     */
    suspend fun stopFirewall(): Result<Unit> = startStopMutex.withLock {
        return stopFirewallInternal()
    }

    /**
     * Internal stop method without mutex (for use within startFirewall which already holds the lock).
     */
    private suspend fun stopFirewallInternal(): Result<Unit> {
        return try {
            Log.d(TAG, "Stopping firewall")

            // Stop monitoring
            stopMonitoring()

            // Stop current backend
            currentBackend?.stop()?.getOrElse { error ->
                Log.w(TAG, "Failed to stop current backend: ${error.message}")
            }

            // Clean up ALL backend types to prevent orphaned rules
            // This ensures that if user switched backends, old rules are cleaned up
            // Per user request: when firewall is OFF, there should be NO rules from ANY backend
            cleanupAllBackends()

            currentBackend = null
            _activeBackendType.value = null
            _firewallState.value = FirewallState.Stopped

            // Clear firewall down flag - firewall is intentionally stopped by user
            _isFirewallDown.value = false
            dismissBackendFailedNotification()

            Log.d(TAG, "Firewall stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop firewall", e)
            val error = errorHandler.handleError(e, "stop firewall")
            _firewallState.value = FirewallState.Error("Failed to stop firewall: ${error.message}", _activeBackendType.value)
            Result.failure(error)
        }
    }

    /**
     * Clean up all backend types to prevent orphaned rules.
     * This is called when stopping the firewall to ensure no rules remain from any backend.
     *
     * Background: If user switches between backends (e.g., iptables → VPN), the old backend's
     * rules may remain active. When firewall is disabled, we want a truly clean state with
     * no rules from any backend.
     */
    private suspend fun cleanupAllBackends() {
        Log.d(TAG, "Cleaning up all backend types to ensure no orphaned rules...")

        // Clean up iptables rules (if any exist)
        // This is the most important cleanup because iptables rules persist in the kernel
        // even after the app is closed or crashes
        try {
            val iptablesBackend = IptablesFirewallBackend(
                context,
                rootManager,
                shizukuManager,
                errorHandler
            )
            iptablesBackend.stop()
            Log.d(TAG, "Iptables cleanup completed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up iptables: ${e.message}")
            // Ignore errors - best effort cleanup
            // User may not have root/Shizuku, which is fine
        }

        // VPN and ConnectivityManager backends don't leave orphaned state:
        // - VPN: Service stops cleanly, Android removes VPN interface automatically
        // - ConnectivityManager: Chain is disabled via shell command, no persistent state
        // So we only need to clean up iptables rules
    }

    /**
     * Check if firewall is currently active.
     */
    fun isActive(): Boolean {
        return currentBackend?.isActive() ?: false
    }

    /**
     * Get the currently active backend type.
     */
    fun getActiveBackendType(): FirewallBackendType? {
        return currentBackend?.getType()
    }

    /**
     * Check if the currently active backend supports granular control.
     * Returns true for iptables (can block WiFi/Mobile/Roaming separately).
     * Returns false for ConnectivityManager/VPN (all-or-nothing blocking).
     * Returns true if no backend is active (default to showing granular UI).
     */
    fun supportsGranularControl(): Boolean {
        return currentBackend?.supportsGranularControl() ?: true
    }

    /**
     * Check if iptables backend is available.
     */
    suspend fun isIptablesAvailable(): Boolean {
        val backend = IptablesFirewallBackend(context, rootManager, shizukuManager, errorHandler)
        return backend.checkAvailability().isSuccess
    }

    /**
     * Select the appropriate backend based on mode and availability.
     */
    private suspend fun selectBackend(mode: FirewallMode): Result<FirewallBackend> {
        return try {
            Log.d(TAG, "Selecting backend for mode: $mode")

            val backend = when (mode) {
                FirewallMode.AUTO -> {
                    Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
                    Log.d(TAG, "║  🎯 AUTO MODE: SELECTING BEST BACKEND                       ║")
                    Log.d(TAG, "║  Priority: iptables > ConnectivityManager > VPN              ║")
                    Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")

                    // Priority: iptables > ConnectivityManager > VPN
                    val iptablesBackend = IptablesFirewallBackend(
                        context, rootManager, shizukuManager, errorHandler
                    )
                    Log.d(TAG, "Checking iptables availability...")
                    val iptablesAvailable = iptablesBackend.checkAvailability()

                    if (iptablesAvailable.isSuccess) {
                        Log.d(TAG, "✅ iptables is AVAILABLE - selecting iptables backend")
                        iptablesBackend
                    } else {
                        Log.d(TAG, "❌ iptables NOT available: ${iptablesAvailable.exceptionOrNull()?.message}")
                        Log.d(TAG, "Checking ConnectivityManager availability...")
                        val cmBackend = ConnectivityManagerFirewallBackend(
                            context, shizukuManager, errorHandler
                        )
                        val cmAvailable = cmBackend.checkAvailability()

                        if (cmAvailable.isSuccess) {
                            Log.d(TAG, "✅ ConnectivityManager is AVAILABLE - selecting ConnectivityManager backend")
                            cmBackend
                        } else {
                            Log.d(TAG, "❌ ConnectivityManager NOT available: ${cmAvailable.exceptionOrNull()?.message}")
                            Log.d(TAG, "✅ Falling back to VPN backend (always available)")
                            VpnFirewallBackend(context)
                        }
                    }
                }

                FirewallMode.VPN -> {
                    VpnFirewallBackend(context)
                }

                FirewallMode.IPTABLES -> {
                    val iptablesBackend = IptablesFirewallBackend(
                        context, rootManager, shizukuManager, errorHandler
                    )
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

            Log.d(TAG, "Backend selected: ${backend.getType()}")
            Result.success(backend)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select backend", e)
            val error = errorHandler.handleError(e, "select firewall backend")
            Result.failure(error)
        }
    }

    /**
     * Migrate partial rules to simple all-or-nothing rules.
     * Per FIREWALL.md lines 214-218: If ANY network is blocked, block all (conservative approach).
     * If NO networks are blocked, allow all.
     *
     * This is called when switching from a granular backend (VPN/iptables) to a
     * simple backend (ConnectivityManager) that only supports all-or-nothing blocking.
     */
    private suspend fun migrateRulesToSimple() {
        try {
            Log.d(TAG, "=== Starting rule migration: granular → simple ===")
            val rules = firewallRepository.getAllRules().first()
            var migratedCount = 0
            var skippedCount = 0

            rules.forEach { rule ->
                // Check if rule has partial blocking
                val blocks = listOf(
                    rule.wifiBlocked,
                    rule.mobileBlocked,
                    rule.blockWhenRoaming
                )

                val hasPartialBlock = blocks.any { it } && blocks.any { !it }

                if (hasPartialBlock) {
                    // Conservative approach: if ANY network is blocked, block all
                    // Per FIREWALL.md line 215: "Partially blocked (1-2 networks blocked): Treat as fully blocked"
                    val hasAnyBlock = blocks.any { it }
                    val blockAll = hasAnyBlock

                    Log.d(TAG, "Migrating ${rule.packageName}: wifi=${rule.wifiBlocked}, mobile=${rule.mobileBlocked}, roaming=${rule.blockWhenRoaming} → blockAll=$blockAll (conservative: any block → block all)")

                    // Update rule to block/allow all networks
                    firewallRepository.updateRule(
                        rule.copy(
                            wifiBlocked = blockAll,
                            mobileBlocked = blockAll,
                            blockWhenRoaming = blockAll,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    migratedCount++
                } else {
                    // Rule is already uniform (all blocked or all allowed)
                    skippedCount++
                }
            }

            Log.d(TAG, "✅ Rule migration complete: $migratedCount rules migrated, $skippedCount rules already uniform")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to migrate rules", e)
            // Don't throw - allow firewall to start even if migration fails
        }
    }

    /**
     * Start monitoring network and screen state changes.
     * Used for iptables and NetworkPolicyManager backends (VPN backend monitors internally).
     */
    private fun startMonitoring() {
        Log.d(TAG, "Starting state monitoring for ${currentBackend?.getType()} backend")

        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            combine(
                networkStateMonitor.observeNetworkType(),
                screenStateMonitor.observeScreenState()
            ) { networkType, screenOn ->
                Pair(networkType, screenOn)
            }.collect { (networkType, screenOn) ->
                currentNetworkType = networkType
                isScreenOn = screenOn

                Log.d(TAG, "State changed: network=$networkType, screenOn=$screenOn")

                // Reapply rules with debouncing
                scheduleRuleApplication()
            }
        }

        // Also listen to rule changes from repository
        ruleChangeMonitoringJob?.cancel()
        ruleChangeMonitoringJob = scope.launch {
            firewallRepository.getAllRules().collect { _ ->
                Log.d(TAG, "Rules changed in repository")
                scheduleRuleApplication()
            }
        }
    }

    /**
     * Stop monitoring.
     */
    private fun stopMonitoring() {
        Log.d(TAG, "Stopping state monitoring")
        monitoringJob?.cancel()
        monitoringJob = null
        ruleChangeMonitoringJob?.cancel()
        ruleChangeMonitoringJob = null
        ruleApplicationJob?.cancel()
        ruleApplicationJob = null
        healthMonitoringJob?.cancel()
        healthMonitoringJob = null
        vpnPermissionMonitoringJob?.cancel()
        vpnPermissionMonitoringJob = null

        // Reset adaptive health check tracking
        consecutiveSuccessfulHealthChecks = 0
        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS
    }

    /**
     * Start continuous backend health monitoring with adaptive interval.
     * Starts with fast checks (30s) for first 5 minutes, then increases to 5 minutes for battery savings.
     * For privileged backends (iptables, ConnectivityManager): checks if backend still has permissions (privilege loss detection).
     * For VPN backend: checks if better backends become available (privilege gain detection).
     * Per FIREWALL.md lines 92-96.
     */
    private fun startBackendHealthMonitoring() {
        val backend = currentBackend ?: return
        val backendType = backend.getType()

        // Reset adaptive tracking when starting new monitoring
        consecutiveSuccessfulHealthChecks = 0
        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

        val monitoringType = if (backendType == FirewallBackendType.VPN) {
            "PRIVILEGE GAIN (checking if better backends available)"
        } else {
            "PRIVILEGE LOSS (checking if backend still has permissions)"
        }

        Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║  🔍 STARTING ADAPTIVE HEALTH MONITORING                      ║")
        Log.d(TAG, "║  Backend: $backendType")
        Log.d(TAG, "║  Type: $monitoringType")
        Log.d(TAG, "║  Initial interval: ${currentHealthCheckInterval}ms (30 seconds)")
        Log.d(TAG, "║  Stable interval: ${Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_STABLE_MS}ms (5 minutes)")
        Log.d(TAG, "║  Threshold: ${Constants.HealthCheck.BACKEND_HEALTH_CHECK_STABLE_THRESHOLD} successful checks")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")

        healthMonitoringJob?.cancel()
        healthMonitoringJob = scope.launch {
            while (true) {
                delay(currentHealthCheckInterval)

                try {
                    // VPN backend: Check if better backends become available (privilege gain detection)
                    if (backendType == FirewallBackendType.VPN) {
                        Log.d(TAG, "Health check: Checking if better backends available (VPN privilege gain detection)... (interval: ${currentHealthCheckInterval}ms, consecutive successes: $consecutiveSuccessfulHealthChecks)")

                        // Force re-check root and Shizuku status to detect privilege gain
                        rootManager.forceRecheckRootStatus()
                        shizukuManager.checkShizukuStatus()

                        // Compute what backend we SHOULD be using now
                        val planResult = computeStartPlan(FirewallMode.AUTO)

                        if (planResult.isSuccess) {
                            val plan = planResult.getOrThrow()

                            // If planner suggests a better backend than VPN, switch to it
                            if (plan.selectedBackendType != FirewallBackendType.VPN) {
                                Log.d(TAG, "")
                                Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
                                Log.d(TAG, "║  ⚡ PRIVILEGE GAIN DETECTED - BETTER BACKEND AVAILABLE      ║")
                                Log.d(TAG, "║  Current: VPN")
                                Log.d(TAG, "║  Better: ${plan.selectedBackendType}")
                                Log.d(TAG, "║  Action: Switching to better backend automatically          ║")
                                Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")
                                Log.d(TAG, "")

                                // Stop current backend and switch to better one
                                stopMonitoring()
                                currentBackend?.stop()
                                currentBackend = null

                                // Start with the better backend
                                startFirewall(FirewallMode.AUTO)
                                break // Stop monitoring - new backend will start its own monitoring
                            }
                        }

                        // No better backend available - VPN is still the best option
                        consecutiveSuccessfulHealthChecks++
                        Log.d(TAG, "✅ Health check passed: VPN is still the best available backend (consecutive successes: $consecutiveSuccessfulHealthChecks)")
                        _backendHealthWarning.value = null

                    } else {
                        // Privileged backend: Check if backend still has permissions (privilege loss detection)
                        Log.d(TAG, "Health check: Testing $backendType backend availability... (interval: ${currentHealthCheckInterval}ms, consecutive successes: $consecutiveSuccessfulHealthChecks)")

                        // Check if backend is still available
                        val availabilityResult = backend.checkAvailability()

                        if (availabilityResult.isFailure) {
                            Log.e(TAG, "❌ Health check FAILED: $backendType backend is no longer available!")
                            Log.e(TAG, "Error: ${availabilityResult.exceptionOrNull()?.message}")
                            Log.e(TAG, "Resetting health check interval to initial value (${Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS}ms)")

                            // Reset adaptive tracking on failure
                            consecutiveSuccessfulHealthChecks = 0
                            currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

                            // Backend failed - trigger automatic fallback to VPN
                            handleBackendFailure(backendType)
                            break // Stop monitoring - new backend will start its own monitoring
                        }

                        // Check if backend is still active
                        if (!backend.isActive()) {
                            Log.e(TAG, "❌ Health check FAILED: $backendType backend is not active!")
                            Log.e(TAG, "Resetting health check interval to initial value (${Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS}ms)")

                            // Reset adaptive tracking on failure
                            consecutiveSuccessfulHealthChecks = 0
                            currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS

                            // Backend became inactive - trigger automatic fallback
                            handleBackendFailure(backendType)
                            break
                        }

                        // Health check passed - increment success counter
                        consecutiveSuccessfulHealthChecks++
                        Log.d(TAG, "✅ Health check passed: $backendType backend is healthy (consecutive successes: $consecutiveSuccessfulHealthChecks)")
                        _backendHealthWarning.value = null // Clear any previous warnings
                    }

                    // Check if we should increase interval (backend is stable)
                    if (consecutiveSuccessfulHealthChecks >= Constants.HealthCheck.BACKEND_HEALTH_CHECK_STABLE_THRESHOLD &&
                        currentHealthCheckInterval == Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS) {
                        currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_STABLE_MS
                        Log.d(TAG, "")
                        Log.d(TAG, "╔════════════════════════════════════════════════════════════════╗")
                        Log.d(TAG, "║  ⚡ BACKEND STABLE - INCREASING HEALTH CHECK INTERVAL       ║")
                        Log.d(TAG, "║  Backend: $backendType")
                        Log.d(TAG, "║  New interval: ${currentHealthCheckInterval}ms (5 minutes)")
                        Log.d(TAG, "║  Battery savings: ~90% reduction in wake-ups                 ║")
                        Log.d(TAG, "╚════════════════════════════════════════════════════════════════╝")
                        Log.d(TAG, "")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Health check exception for $backendType", e)
                    // Don't trigger fallback on exceptions - might be temporary
                    // Don't reset counter either - exception doesn't mean backend is unstable
                }
            }
        }
    }

    /**
     * Handle backend failure notification from PrivilegedFirewallService.
     * This is called when the service detects a failure and stops itself.
     * We handle it immediately instead of waiting for the health check to detect it.
     */
    suspend fun handleBackendFailureFromService(failedBackendType: FirewallBackendType) {
        Log.e(TAG, "Received backend failure notification from service: $failedBackendType")
        handleBackendFailure(failedBackendType)
    }

    /**
     * Handle backend failure using the planner.
     *
     * Rules (per FIREWALL_BACKEND_RELIABILITY_PLAN):
     * - If manually selected backend fails, surface error (no automatic fallback).
     * - Use [computeStartPlan] to determine the best available backend.
     * - If planner selects VPN, honor VPN permission state and show notification when needed.
     */
    private suspend fun handleBackendFailure(failedBackendType: FirewallBackendType) = startStopMutex.withLock {
        Log.e(TAG, "=== BACKEND FAILURE DETECTED: $failedBackendType ===")

        // Check if user had manually selected this backend
        val currentMode = getCurrentMode()
        val wasManualSelection = currentMode != FirewallMode.AUTO

        if (wasManualSelection) {
            Log.e(TAG, "Manually selected backend ($currentMode) failed. Waiting for user action or privilege recovery")

            currentBackend = null
            _activeBackendType.value = null
            _backendHealthWarning.value = "FIREWALL DOWN: $failedBackendType backend failed. Restore privileges or choose another backend."
            _firewallState.value = FirewallState.Error(
                message = "$failedBackendType backend not available",
                lastBackend = failedBackendType
            )

            // Preserve user intent for recovery attempts
            _isFirewallDown.value = true

            // Surface notification to guide the user
            showBackendFailedNotification(failedBackendType)
            dismissVpnFallbackNotification()
            return@withLock
        }

        val effectiveMode = currentMode

        // Ask planner what we should do next
        val planResult = computeStartPlan(effectiveMode)
        if (planResult.isFailure) {
            val error = planResult.exceptionOrNull()
            Log.e(TAG, "handleBackendFailure: Failed to compute start plan after backend failure", error)

            currentBackend = null
            _activeBackendType.value = null
            _backendHealthWarning.value = "FIREWALL DOWN: Failed to compute fallback plan. Your apps are UNBLOCKED!"
            _firewallState.value = FirewallState.Error(
                message = "Failed to compute fallback plan: ${error?.message}",
                lastBackend = failedBackendType
            )

            // Mark firewall as down (preserve user intent for recovery)
            _isFirewallDown.value = true
            return@withLock
        }

        val plan = planResult.getOrThrow()
        Log.d(TAG, "handleBackendFailure: planner selected backend ${plan.selectedBackendType} (requiresVpn=${plan.requiresVpnPermission})")

        if (!plan.requiresVpnPermission || plan.selectedBackendType != FirewallBackendType.VPN) {
            // Planner chose a non-VPN backend or VPN that doesn't require permission (shouldn't happen),
            // just delegate to normal startFirewall flow.
            val result = startFirewall(plan.mode)
            result.onSuccess { backendType ->
                Log.d(TAG, "✅ Backend failure handled via planner: switched to $backendType")
            }.onFailure { error ->
                Log.e(TAG, "❌ Failed to start fallback backend via planner: ${error.message}")
                currentBackend = null
                _activeBackendType.value = null
                _backendHealthWarning.value = "FIREWALL DOWN: Fallback failed. Your apps are UNBLOCKED!"
                _firewallState.value = FirewallState.Error(
                    message = "Fallback start failed: ${error.message}",
                    lastBackend = failedBackendType
                )

                // Mark firewall as down (preserve user intent for recovery)
                _isFirewallDown.value = true
            }
            return@withLock
        }

        // Planner chose VPN and it requires permission – preserve existing UX around permission checks.
        Log.e(TAG, "Planner selected VPN fallback, checking VPN permission...")

        // Check if another VPN is active before calling VpnService.prepare()
        // This prevents killing user's third-party VPN (like Proton VPN)
        val isAnotherVpnActive = isAnotherVpnActive()

        if (isAnotherVpnActive) {
            // Another VPN is active - don't call VpnService.prepare() yet
            Log.e(TAG, "Another VPN is active - showing VPN conflict notification")
            showVpnConflictNotification()

            // Update state to reflect firewall is down
            currentBackend = null
            _activeBackendType.value = null
            _backendHealthWarning.value = "FIREWALL DOWN: Another VPN active. Tap notification to replace VPN and enable firewall."
            _firewallState.value = FirewallState.Error(
                message = "VPN conflict - another VPN is active",
                lastBackend = failedBackendType
            )

            // Mark firewall as down (preserve user intent for recovery)
            _isFirewallDown.value = true

            // Start monitoring for VPN permission grant
            // This will automatically start VPN fallback when user grants permission
            startVpnPermissionMonitoring()
            return@withLock
        }

        // No other VPN active - safe to check VPN permission
        val prepareIntent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check VPN permission", e)
            null
        }

        if (prepareIntent == null) {
            // VPN permission granted - automatic fallback
            Log.d(TAG, "VPN permission granted - attempting automatic VPN fallback via startFirewall(plan.mode)...")

            val result = startFirewall(plan.mode)
            result.onSuccess { backendType ->
                Log.d(TAG, "✅ VPN fallback successful via planner: backend=$backendType")
            }.onFailure { error ->
                Log.e(TAG, "❌ VPN fallback FAILED via planner: ${error.message}")
                currentBackend = null
                _activeBackendType.value = null
                _backendHealthWarning.value = "FIREWALL DOWN: VPN fallback failed. Your apps are UNBLOCKED!"
                _firewallState.value = FirewallState.Error(
                    message = "VPN fallback failed: ${error.message}",
                    lastBackend = failedBackendType
                )

                // Mark firewall as down (preserve user intent for recovery)
                _isFirewallDown.value = true
            }
        } else {
            // VPN permission not granted - show notification
            Log.e(TAG, "VPN permission not granted - showing fallback notification...")
            showVpnFallbackNotification()

            // Update state to reflect firewall is down
            currentBackend = null
            _activeBackendType.value = null
            _backendHealthWarning.value = "FIREWALL DOWN: VPN permission required. Tap notification to enable fallback."
            _firewallState.value = FirewallState.Error(
                message = "VPN permission required for fallback",
                lastBackend = failedBackendType
            )

            // Mark firewall as down (preserve user intent for recovery)
            // When user grants VPN permission or when handlePrivilegeChange() runs,
            // it will check isFirewallDown and attempt recovery.
            _isFirewallDown.value = true

            // Start monitoring for VPN permission grant
            // This will automatically start VPN fallback when user grants permission
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
    private suspend fun startVpnFallback(wasManualSelection: Boolean, failedBackendType: FirewallBackendType) {
        try {
            // Stop monitoring to prevent interference
            stopMonitoring()

            // Try to fallback to VPN
            val vpnBackend = VpnFirewallBackend(context)

            Log.d(TAG, "Starting VPN backend as fallback (legacy path)...")
            vpnBackend.start().getOrElse { error ->
                Log.e(TAG, "❌ CRITICAL: VPN fallback FAILED: ${error.message}")
                _backendHealthWarning.value = "FIREWALL DOWN: VPN fallback failed. Your apps are UNBLOCKED!"

                // Update state to reflect firewall is down
                currentBackend = null
                _activeBackendType.value = null
                _firewallState.value = FirewallState.Error(
                    message = "VPN fallback failed",
                    lastBackend = failedBackendType
                )

                // Mark firewall as down (preserve user intent for recovery)
                _isFirewallDown.value = true

                return
            }

            // Wait for VPN to establish
            delay(1000)

            if (!vpnBackend.isActive()) {
                Log.e(TAG, "❌ CRITICAL: VPN fallback started but not active!")
                _backendHealthWarning.value = "FIREWALL DOWN: VPN fallback failed. Your apps are UNBLOCKED!"

                currentBackend = null
                _activeBackendType.value = null
                _firewallState.value = FirewallState.Error(
                    message = "VPN fallback VPN not active",
                    lastBackend = failedBackendType
                )

                // Mark firewall as down (preserve user intent for recovery)
                _isFirewallDown.value = true

                return
            }

            Log.d(TAG, "✅ VPN fallback successful (legacy path)!")

            // Update current backend
            currentBackend = vpnBackend
            _activeBackendType.value = FirewallBackendType.VPN
            _firewallState.value = FirewallState.Running(FirewallBackendType.VPN)

            // Update SharedPreferences to reflect firewall is running
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, true).apply()

            // Clear firewall down flag - firewall is now running
            _isFirewallDown.value = false

            // Dismiss notification if it was shown
            dismissVpnFallbackNotification()

            // Apply firewall rules to the VPN backend
            applyRules()

            // Show warning to user
            val warningMessage = if (wasManualSelection) {
                "$failedBackendType backend failed. Switched to AUTO mode and using VPN."
            } else {
                "$failedBackendType backend failed. Automatically switched to VPN."
            }
            _backendHealthWarning.value = warningMessage

            // VPN monitors internally, no need to start monitoring

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Exception during VPN fallback", e)
            _backendHealthWarning.value = "FIREWALL DOWN: Fallback failed. Your apps are UNBLOCKED!"

            currentBackend = null
            _activeBackendType.value = null
            _firewallState.value = FirewallState.Error(
                message = "Exception during VPN fallback: ${e.message}",
                lastBackend = failedBackendType
            )

            // Mark firewall as down (preserve user intent for recovery)
            _isFirewallDown.value = true
        }
    }

    /**
     * Show notification to request VPN permission for fallback.
     */
    private fun showVpnFallbackNotification() {
        Log.d(TAG, "Showing VPN fallback notification")

        // Create notification channel (Android O+)
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

        // Create intent to open MainActivity and request VPN permission
        // Must explicitly set component (MainActivity) for PendingIntent to work
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

        // Build notification
        val notificationTitle = context.getString(R.string.vpn_fallback_notification_title)
        val notificationText = context.getString(R.string.vpn_fallback_notification_text)
        val notificationAction = context.getString(R.string.vpn_fallback_notification_action_text)

        val notification = NotificationCompat.Builder(context, Constants.VpnFallback.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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

    /**
     * Dismiss VPN fallback notification.
     */
    private fun dismissVpnFallbackNotification() {
        Log.d(TAG, "Dismissing VPN fallback notification")
        notificationManager.cancel(Constants.VpnFallback.NOTIFICATION_ID)
    }

    /**
     * Show notification when another VPN is active and blocking De1984 from using VPN fallback.
     *
     * This notification informs the user that:
     * 1. Another VPN (like Proton VPN) is currently active
     * 2. De1984 needs VPN permission to restore firewall protection
     * 3. Granting permission will replace their current VPN connection
     *
     * Uses the same notification ID as VPN fallback notification since they're mutually exclusive.
     */
    private fun showVpnConflictNotification() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastVpnConflictNotificationTime

        if (elapsed in 1 until VPN_CONFLICT_NOTIFICATION_DEBOUNCE_MS) {
            Log.d(TAG, "Skipping VPN conflict notification - last shown ${elapsed}ms ago")
            return
        }

        lastVpnConflictNotificationTime = now
        Log.d(TAG, "Showing VPN conflict notification")

        // Create notification channel (Android O+)
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

        // Create intent to open MainActivity and request VPN permission
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

        // Build notification with VPN conflict messaging
        val notificationTitle = context.getString(R.string.vpn_conflict_notification_title)
        val notificationText = context.getString(R.string.vpn_conflict_notification_text)
        val notificationAction = context.getString(R.string.vpn_conflict_notification_action_text)

        val notification = NotificationCompat.Builder(context, Constants.VpnFallback.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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

    /**
     * Dismiss VPN conflict notification.
     *
     * This is an alias for dismissVpnFallbackNotification() since both notifications
     * use the same notification ID (they're mutually exclusive scenarios).
     */
    private fun dismissVpnConflictNotification() {
        dismissVpnFallbackNotification()
    }

    /**
     * Show notification when a manually selected backend fails and no automatic fallback is attempted.
     */
    private fun showBackendFailedNotification(failedBackendType: FirewallBackendType) {
        Log.d(TAG, "Showing backend failed notification for $failedBackendType")

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

        val backendName = when (failedBackendType) {
            FirewallBackendType.CONNECTIVITY_MANAGER -> "Connectivity Manager"
            FirewallBackendType.IPTABLES -> "iptables"
            FirewallBackendType.NETWORK_POLICY_MANAGER -> "Network Policy Manager"
            FirewallBackendType.VPN -> "VPN"
        }

        val body = "$backendName backend is not available. Restore required privileges or choose another backend in settings."

        val notification = NotificationCompat.Builder(context, Constants.BackendFailure.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.privileged_firewall_failure_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(Constants.BackendFailure.NOTIFICATION_ID, notification)
    }

    /**
     * Clear backend failure notification when firewall recovers or is intentionally stopped.
     */
    private fun dismissBackendFailedNotification() {
        Log.d(TAG, "Dismissing backend failed notification")
        notificationManager.cancel(Constants.BackendFailure.NOTIFICATION_ID)
    }

    /**
     * Start VPN fallback manually after user grants permission via notification.
     * This is called from MainActivity when user taps the notification and grants permission.
     */
    suspend fun startVpnFallbackManually() = startStopMutex.withLock {
        Log.d(TAG, "Starting VPN fallback manually after permission grant")

        // Check VPN permission again to be safe
        val prepareIntent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check VPN permission", e)
            return@withLock
        }

        if (prepareIntent != null) {
            Log.e(TAG, "VPN permission still not granted - cannot start fallback")
            return@withLock
        }

        // Start VPN fallback (wasManualSelection = false since this is a fallback scenario)
        startVpnFallback(wasManualSelection = false, failedBackendType = FirewallBackendType.VPN)
    }

    /**
     * Start monitoring for VPN permission grant.
     *
     * This function continuously checks if VPN permission has been granted and automatically
     * starts VPN fallback when permission becomes available.
     *
     * Includes safeguards:
     * - Max 30 retry attempts (1 minute total)
     * - Exponential backoff (2s → 16s)
     * - Cancels existing monitoring to prevent duplicates
     * - Stops when firewall is no longer down
     * - Exception handling for VpnService.prepare()
     */
    private fun startVpnPermissionMonitoring() {
        // Cancel existing monitoring to prevent duplicates
        vpnPermissionMonitoringJob?.cancel()

        Log.d(TAG, "Starting VPN permission monitoring")

        vpnPermissionMonitoringJob = scope.launch {
            var retryCount = 0
            val maxRetries = 30  // 30 attempts = ~1 minute total with exponential backoff
            var delayMs = 2000L  // Start with 2 seconds

            while (_isFirewallDown.value && retryCount < maxRetries) {
                delay(delayMs)
                retryCount++

                Log.d(TAG, "VPN permission monitoring: attempt $retryCount/$maxRetries")

                // Check if another VPN is active before calling VpnService.prepare()
                // This prevents killing user's third-party VPN (like Proton VPN) repeatedly
                val isAnotherVpnActive = isAnotherVpnActive()

                if (isAnotherVpnActive) {
                    // Another VPN is still active - don't check permission yet
                    Log.d(TAG, "Another VPN still active - skipping permission check")
                    showVpnConflictNotification()
                    
                    // On first retry, check if we have VPN permission to determine if situation is hopeless:
                    // - If we have permission: keep trying (user may disconnect their VPN)
                    // - If no permission AND VPN conflict: exit monitoring (can't proceed without both)
                    if (retryCount == 1) {
                        val prepareIntent = try {
                            VpnService.prepare(context)
                        } catch (e: Exception) {
                            Log.w(TAG, "VPN permission check failed: ${e.message}")
                            null
                        }

                        if (prepareIntent != null) {
                            Log.w(TAG, "No VPN permission and another VPN active - stopping monitoring (situation is hopeless)")
                            break
                        }
                    }
                    continue
                }

                // No other VPN active - safe to check VPN permission
                val prepareIntent = try {
                    VpnService.prepare(context)
                } catch (e: Exception) {
                    Log.w(TAG, "VPN permission check failed: ${e.message}")
                    continue
                }

                if (prepareIntent == null) {
                    // Permission granted! Attempt automatic recovery
                    Log.d(TAG, "✅ VPN permission granted - attempting automatic recovery")

                    // Dismiss the notification since permission is now granted
                    dismissVpnFallbackNotification()

                    // Attempt to start firewall (will use VPN backend)
                    val mode = getCurrentMode()
                    val result = startFirewall(mode)

                    if (result.isSuccess) {
                        Log.d(TAG, "✅ Automatic VPN fallback successful")
                        break
                    } else {
                        Log.w(TAG, "⚠️ VPN fallback start failed: ${result.exceptionOrNull()?.message}")
                        // Increase delay and retry
                        delayMs = (delayMs * 1.5).toLong().coerceAtMost(16000L)
                    }
                } else {
                    // Permission not yet granted, continue monitoring
                    Log.d(TAG, "VPN permission not yet granted, will retry in ${delayMs}ms")
                }
            }

            if (retryCount >= maxRetries) {
                Log.w(TAG, "VPN permission monitoring stopped after $maxRetries attempts")
            } else if (!_isFirewallDown.value) {
                Log.d(TAG, "VPN permission monitoring stopped - firewall is now running")
            }
        }
    }

    /**
     * Schedule rule application with debouncing.
     */
    private fun scheduleRuleApplication() {
        ruleApplicationJob?.cancel()
        ruleApplicationJob = scope.launch {
            delay(RULE_APPLICATION_DEBOUNCE_MS)
            applyRules()
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
        Log.d(TAG, "Triggering rule re-application (policy change)")

        // Clear backend caches to force re-evaluation of all packages
        // This is critical to prevent memory leaks from redundant operations
        val backend = currentBackend
        if (backend is ConnectivityManagerFirewallBackend) {
            backend.clearAppliedPoliciesCache()
            Log.d(TAG, "Cleared ConnectivityManager applied policies cache")
        } else if (backend is NetworkPolicyManagerFirewallBackend) {
            backend.clearAppliedPoliciesCache()
            Log.d(TAG, "Cleared NetworkPolicyManager applied policies cache")
        }

        scheduleRuleApplication()
    }

    /**
     * Apply rules to the current backend.
     */
    private suspend fun applyRules() {
        val backend = currentBackend ?: return
        applyRulesToBackend(backend).getOrElse { error ->
            Log.e(TAG, "Failed to apply rules: ${error.message}")
        }
    }

    /**
     * Apply rules to a specific backend.
     * Used during atomic backend switching to ensure new backend is fully active.
     */
    private suspend fun applyRulesToBackend(backend: FirewallBackend): Result<Unit> {
        return try {
            if (backend.getType() == FirewallBackendType.VPN) {
                // VPN backend handles rules internally
                return Result.success(Unit)
            }

            // Get all rules from repository
            val rules = firewallRepository.getAllRules().first()

            // Apply rules
            backend.applyRules(rules, currentNetworkType, isScreenOn).getOrElse { error ->
                Log.e(TAG, "Failed to apply rules to ${backend.getType()}: ${error.message}")
                return Result.failure(error)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying rules to ${backend.getType()}", e)
            Result.failure(e)
        }
    }

    /**
     * Check if a VPN is currently active on the device.
     *
     * This is useful when using iptables mode with "Block All" default policy.
     * If a VPN is active and gets blocked by the firewall, all apps routing through
     * it will lose connectivity.
     *
     * @return true if a VPN is active, false otherwise
     */
    fun isVpnActive(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Log.w(TAG, "ConnectivityManager not available")
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
                // For older Android versions, check all networks
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
            Log.e(TAG, "Failed to check VPN status", e)
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
        // First check if ANY VPN is active
        val isAnyVpnActive = isVpnActive()

        if (!isAnyVpnActive) {
            // No VPN active at all
            return false
        }

        // A VPN is active - check if it's De1984's own VPN
        val currentBackendType = getActiveBackendType()
        if (currentBackendType == FirewallBackendType.VPN) {
            // De1984's VPN is active - not "another" VPN
            Log.d(TAG, "isAnotherVpnActive: De1984's VPN is active, not another VPN")
            return false
        }

        // A VPN is active and it's NOT De1984's - must be another app's VPN
        Log.d(TAG, "isAnotherVpnActive: Another VPN app is active (currentBackend=$currentBackendType)")
        return true
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
        Log.d(TAG, "Starting privilege monitoring for automatic backend switching")

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
     * Handle privilege changes (root/Shizuku status changes).
     * Automatically restarts firewall with new backend when privileges change.
     *
     * Rules (per FIREWALL_BACKEND_RELIABILITY_PLAN Phase 4):
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
        // Skip if we've already processed this exact status combination (unless forced)
        if (!forceCheck &&
            rootStatus == lastProcessedRootStatus &&
            shizukuStatus == lastProcessedShizukuStatus) {
            Log.d(TAG, "handlePrivilegeChange: Skipping - already processed this status combination")
            return
        }

        lastProcessedRootStatus = rootStatus
        lastProcessedShizukuStatus = shizukuStatus

        Log.d(TAG, "Privilege change detected: root=$rootStatus, shizuku=$shizukuStatus")

        // Check if user wants firewall running (user intent) OR if firewall is down.
        // This is critical for two scenarios:
        // 1. User enabled firewall and it's running normally
        // 2. Firewall is down (isFirewallDown=true) and we need to attempt recovery
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val firewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)
        val firewallDown = _isFirewallDown.value

        if (!firewallEnabled && !firewallDown) {
            Log.d(TAG, "Firewall not enabled by user and not in 'down' state, skipping privilege change handling")
            return
        }

        Log.d(TAG, "Firewall enabled=$firewallEnabled, firewall down=$firewallDown - proceeding with privilege change handling")

        // Also check current state - if backend is still active, we're in the normal
        // privilege-change-while-running case. If not active, we're in the
        // service-stopped-itself-due-to-permission-loss case.
        val currentlyActive = isActive()
        Log.d(TAG, "Firewall enabled=$firewallEnabled, currently active=$currentlyActive")

        val currentMode = getCurrentMode()
        val currentBackendType = activeBackendType.value

        // Determine if this is privilege gain or loss (for logging only)
        val hasPrivileges =
            rootStatus == RootStatus.ROOTED_WITH_PERMISSION ||
                shizukuStatus == ShizukuStatus.RUNNING_WITH_PERMISSION

        if (currentMode == FirewallMode.AUTO) {
            // In AUTO mode we let the planner decide whether backend type should change
            Log.d(TAG, "AUTO mode: Computing plan to check if backend should switch...")
            Log.d(TAG, "Current backend: $currentBackendType, Root: $rootStatus, Shizuku: $shizukuStatus")

            val planResult = computeStartPlan(FirewallMode.AUTO)
            if (planResult.isFailure) {
                Log.e(TAG, "handlePrivilegeChange: Failed to compute plan in AUTO mode", planResult.exceptionOrNull())
                return
            }

            val plan = planResult.getOrThrow()
            val plannedBackendType = plan.selectedBackendType

            Log.d(TAG, "Planner result: current=$currentBackendType, planned=$plannedBackendType")

            if (plannedBackendType == currentBackendType) {
                Log.d(TAG, "Privilege change does not require backend switch in AUTO mode (current=$currentBackendType, planned=$plannedBackendType)")
                return
            }

            if (hasPrivileges) {
                Log.d(TAG, "Privilege gain: planner suggests backend change $currentBackendType → $plannedBackendType, restarting firewall...")
            } else {
                Log.d(TAG, "Privilege loss: planner suggests backend change $currentBackendType → $plannedBackendType, restarting firewall with fallback backend...")
            }

            // CRITICAL: Stop current backend FIRST to avoid mutex deadlock
            // If we don't stop the old backend first, startFirewall() will hang waiting for the mutex
            // because the old backend's service might be holding it (health check, rule application, etc.)
            if (currentBackend != null) {
                Log.d(TAG, "Stopping current backend ($currentBackendType) before switching to $plannedBackendType...")
                stopMonitoring() // Stop health monitoring
                currentBackend?.stop()?.onFailure { error ->
                    Log.w(TAG, "Failed to stop old backend ($currentBackendType): ${error.message}")
                    // Continue anyway - we need to switch backends
                }
                currentBackend = null
                _activeBackendType.value = null
            }

            val result = startFirewall(FirewallMode.AUTO)
            result.onSuccess { newBackend ->
                Log.d(TAG, "✅ Firewall automatically switched to $newBackend backend (AUTO mode)")
            }.onFailure { error ->
                Log.e(TAG, "❌ Failed to automatically switch backend in AUTO mode: ${error.message}")
            }
            return
        }

        // Manual mode: respect user choice as long as backend remains viable.
        // If manual backend becomes invalid under new privileges, keep firewall down and notify user.
        if (currentBackendType == null) {
            if (_isFirewallDown.value) {
                Log.d(TAG, "Manual mode $currentMode with firewall down - attempting automatic recovery")
                val restartResult = startFirewall(currentMode)
                restartResult.onSuccess { backendType ->
                    Log.d(TAG, "✅ Manual backend $backendType restarted after privilege recovery")
                    dismissBackendFailedNotification()
                }.onFailure { error ->
                    Log.e(TAG, "❌ Failed to restart manual backend $currentMode: ${error.message}")
                }
            } else {
                Log.d(TAG, "Manual mode $currentMode but no active backend and firewall disabled by user - nothing to do")
            }
            return
        }

        val availabilityResult = try {
            // Reuse backend factory to check if current manual backend is still available
            val backend = selectBackend(currentMode).getOrNull()
            backend?.checkAvailability()
        } catch (e: Exception) {
            Log.e(TAG, "handlePrivilegeChange: exception while checking availability for manual mode $currentMode", e)
            null
        }

        val stillViable = availabilityResult?.isSuccess == true

        if (stillViable) {
            Log.d(TAG, "Manual mode $currentMode with backend $currentBackendType still viable after privilege change; keeping manual selection")
            return
        }

        Log.e(TAG, "Manual backend $currentMode/$currentBackendType no longer viable after privilege change; keeping manual mode and notifying user")
        handleBackendFailure(currentBackendType)
    }

    // NOTE: wouldBackendChange is now obsolete; planner-based flows in handlePrivilegeChange
    // and handleBackendFailure use computeStartPlan instead. It is retained only for potential
    // legacy callers and should be removed once all call sites are migrated.
    private suspend fun wouldBackendChange(currentBackend: FirewallBackendType?): Boolean {
        if (currentBackend == null) return false

        val currentMode = getCurrentMode()
        if (currentMode != FirewallMode.AUTO) return false

        val newBackend = selectBackend(currentMode).getOrNull()?.getType()
        return newBackend != null && newBackend != currentBackend
    }

    /**
     * Check if backend should switch based on current privileges.
     * This is called from MainActivity.onResume() to force a backend switch check
     * even if StateFlow doesn't emit (e.g., root status was already ROOTED_WITH_PERMISSION).
     *
     * This solves the issue where user re-enables root in Magisk, opens the app,
     * but the app stays on VPN because StateFlow deduplicates and doesn't emit.
     */
    suspend fun checkBackendShouldSwitch() {
        Log.d(TAG, "checkBackendShouldSwitch: Explicitly checking if backend should switch")

        // Reset health check interval to fast mode (15s) to quickly detect any issues
        // This is important when user opens the app after changing privileges
        if (currentHealthCheckInterval != Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS) {
            Log.d(TAG, "Resetting health check interval to fast mode (15s) for quick privilege change detection")
            consecutiveSuccessfulHealthChecks = 0
            currentHealthCheckInterval = Constants.HealthCheck.BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS
        }

        // Get current privilege status
        val rootStatus = rootManager.rootStatus.value
        val shizukuStatus = shizukuManager.shizukuStatus.value

        // Call handlePrivilegeChange with forceCheck=true to bypass duplicate check
        // This ensures we always check if backend should switch when user opens the app
        handlePrivilegeChange(rootStatus, shizukuStatus, forceCheck = true)
    }
}

