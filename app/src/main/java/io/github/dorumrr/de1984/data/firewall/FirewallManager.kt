package io.github.dorumrr.de1984.data.firewall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.monitor.NetworkStateMonitor
import io.github.dorumrr.de1984.data.monitor.ScreenStateMonitor
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
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
        private const val BACKEND_HEALTH_CHECK_INTERVAL_MS = 30_000L // 30 seconds
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val startStopMutex = Mutex()  // Synchronize start/stop operations
    private var monitoringJob: Job? = null
    private var ruleChangeMonitoringJob: Job? = null
    private var ruleApplicationJob: Job? = null
    private var healthMonitoringJob: Job? = null

    private var currentBackend: FirewallBackend? = null

    private val _activeBackendType = MutableStateFlow<FirewallBackendType?>(null)
    val activeBackendType: StateFlow<FirewallBackendType?> = _activeBackendType.asStateFlow()

    private val _backendHealthWarning = MutableStateFlow<String?>(null)
    val backendHealthWarning: StateFlow<String?> = _backendHealthWarning.asStateFlow()
    
    private var currentNetworkType: NetworkType = NetworkType.NONE
    private var isScreenOn: Boolean = true
    
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
     * Start the firewall with the appropriate backend.
     *
     * @param mode Firewall mode (AUTO, VPN, IPTABLES)
     * @return Result with backend type if successful, error otherwise
     */
    suspend fun startFirewall(mode: FirewallMode = getCurrentMode()): Result<FirewallBackendType> = startStopMutex.withLock {
        return try {
            Log.d(TAG, "Starting firewall with mode: $mode")

            // Store old backend info BEFORE any changes
            val oldBackend = currentBackend
            val wasGranular = oldBackend?.supportsGranularControl() ?: false
            val oldBackendType = oldBackend?.getType()

            // Select new backend
            val newBackend = selectBackend(mode).getOrElse { error ->
                Log.e(TAG, "Failed to select backend: ${error.message}")
                return Result.failure(error)
            }

            val newBackendType = newBackend.getType()

            // If same backend type, just ensure it's running
            if (oldBackendType == newBackendType && oldBackend != null) {
                Log.d(TAG, "Same backend type ($oldBackendType), ensuring it's active")
                if (!oldBackend.isActive()) {
                    Log.d(TAG, "Backend not active, restarting...")
                    oldBackend.start().getOrElse { error ->
                        Log.e(TAG, "Failed to restart backend: ${error.message}")
                        return Result.failure(error)
                    }
                }
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
                Log.d(TAG, "Backend transition: $oldBackendType → $newBackendType, no migration needed (both granular or both simple)")
            }

            // ATOMIC SWITCH: Start new backend FIRST, then stop old backend
            // This prevents security gap where apps are unblocked during transition
            Log.d(TAG, "Starting new backend ($newBackendType) BEFORE stopping old backend...")
            newBackend.start().getOrElse { error ->
                Log.e(TAG, "Failed to start new backend ($newBackendType): ${error.message}")
                // Keep old backend running if new one fails
                if (oldBackend != null && oldBackend.isActive()) {
                    Log.w(TAG, "Keeping old backend ($oldBackendType) running since new backend failed to start")
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

            // Stop backend
            currentBackend?.stop()?.getOrElse { error ->
                Log.w(TAG, "Failed to stop backend: ${error.message}")
            }

            currentBackend = null
            _activeBackendType.value = null

            Log.d(TAG, "Firewall stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop firewall", e)
            val error = errorHandler.handleError(e, "stop firewall")
            Result.failure(error)
        }
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
                    // Priority: iptables > ConnectivityManager > VPN
                    val iptablesBackend = IptablesFirewallBackend(
                        context, rootManager, shizukuManager, errorHandler
                    )
                    val iptablesAvailable = iptablesBackend.checkAvailability()

                    if (iptablesAvailable.isSuccess) {
                        iptablesBackend
                    } else {
                        val cmBackend = ConnectivityManagerFirewallBackend(
                            context, shizukuManager, errorHandler
                        )
                        val cmAvailable = cmBackend.checkAvailability()

                        if (cmAvailable.isSuccess) {
                            cmBackend
                        } else {
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
    }

    /**
     * Start continuous backend health monitoring.
     * Periodically checks if the current backend is still available and active.
     * If backend fails, automatically fallback to VPN.
     * Per FIREWALL.md lines 92-96.
     */
    private fun startBackendHealthMonitoring() {
        val backend = currentBackend ?: return
        val backendType = backend.getType()

        // Only monitor privileged backends (iptables, ConnectivityManager)
        // VPN backend doesn't need health monitoring (always available)
        if (backendType == FirewallBackendType.VPN) {
            Log.d(TAG, "VPN backend doesn't need health monitoring")
            return
        }

        Log.d(TAG, "Starting backend health monitoring for $backendType (every ${BACKEND_HEALTH_CHECK_INTERVAL_MS}ms)")

        healthMonitoringJob?.cancel()
        healthMonitoringJob = scope.launch {
            while (true) {
                delay(BACKEND_HEALTH_CHECK_INTERVAL_MS)

                try {
                    Log.d(TAG, "Health check: Testing $backendType backend availability...")

                    // Check if backend is still available
                    val availabilityResult = backend.checkAvailability()

                    if (availabilityResult.isFailure) {
                        Log.e(TAG, "❌ Health check FAILED: $backendType backend is no longer available!")
                        Log.e(TAG, "Error: ${availabilityResult.exceptionOrNull()?.message}")

                        // Backend failed - trigger automatic fallback to VPN
                        handleBackendFailure(backendType)
                        break // Stop monitoring - new backend will start its own monitoring
                    }

                    // Check if backend is still active
                    if (!backend.isActive()) {
                        Log.e(TAG, "❌ Health check FAILED: $backendType backend is not active!")

                        // Backend became inactive - trigger automatic fallback
                        handleBackendFailure(backendType)
                        break
                    }

                    Log.d(TAG, "✅ Health check passed: $backendType backend is healthy")
                    _backendHealthWarning.value = null // Clear any previous warnings

                } catch (e: Exception) {
                    Log.e(TAG, "Health check exception for $backendType", e)
                    // Don't trigger fallback on exceptions - might be temporary
                }
            }
        }
    }

    /**
     * Handle backend failure by automatically falling back to VPN.
     * Per FIREWALL.md lines 46, 80-96: If manually selected backend fails, switch to AUTO mode.
     *
     * IMPORTANT: This method acquires startStopMutex to prevent race conditions with manual
     * backend switching operations.
     */
    private suspend fun handleBackendFailure(failedBackendType: FirewallBackendType) = startStopMutex.withLock {
        Log.e(TAG, "=== BACKEND FAILURE DETECTED: $failedBackendType ===")

        // Check if user had manually selected this backend
        val currentMode = getCurrentMode()
        val wasManualSelection = currentMode != FirewallMode.AUTO

        if (wasManualSelection) {
            Log.e(TAG, "Manually selected backend ($currentMode) failed. Switching to AUTO mode per FIREWALL.md line 46")
            // Switch to AUTO mode in settings
            setMode(FirewallMode.AUTO)
        }

        Log.e(TAG, "Attempting automatic fallback to VPN...")

        try {
            // Stop monitoring to prevent interference
            stopMonitoring()

            // Try to fallback to VPN
            val vpnBackend = VpnFirewallBackend(context)

            Log.d(TAG, "Starting VPN backend as fallback...")
            vpnBackend.start().getOrElse { error ->
                Log.e(TAG, "❌ CRITICAL: VPN fallback FAILED: ${error.message}")
                _backendHealthWarning.value = "FIREWALL DOWN: All backends failed. Your apps are UNBLOCKED!"

                // Update state to reflect firewall is down
                currentBackend = null
                _activeBackendType.value = null

                return@withLock
            }

            // Wait for VPN to establish
            delay(1000)

            if (!vpnBackend.isActive()) {
                Log.e(TAG, "❌ CRITICAL: VPN fallback started but not active!")
                _backendHealthWarning.value = "FIREWALL DOWN: VPN fallback failed. Your apps are UNBLOCKED!"

                currentBackend = null
                _activeBackendType.value = null

                return@withLock
            }

            Log.d(TAG, "✅ VPN fallback successful!")

            // Update current backend
            currentBackend = vpnBackend
            _activeBackendType.value = FirewallBackendType.VPN

            // Show warning to user
            val warningMessage = if (wasManualSelection) {
                "$failedBackendType backend failed. Switched to AUTO mode and using VPN."
            } else {
                "$failedBackendType backend failed. Automatically switched to VPN."
            }
            _backendHealthWarning.value = warningMessage

            // VPN monitors internally, no need to start monitoring

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Exception during backend fallback", e)
            _backendHealthWarning.value = "FIREWALL DOWN: Fallback failed. Your apps are UNBLOCKED!"

            currentBackend = null
            _activeBackendType.value = null
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
     */
    fun triggerRuleReapplication() {
        Log.d(TAG, "Triggering rule re-application")
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
}

