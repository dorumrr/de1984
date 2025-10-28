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
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val startStopMutex = Mutex()  // Synchronize start/stop operations
    private var monitoringJob: Job? = null
    private var ruleChangeMonitoringJob: Job? = null
    private var ruleApplicationJob: Job? = null

    private var currentBackend: FirewallBackend? = null
    
    private val _activeBackendType = MutableStateFlow<FirewallBackendType?>(null)
    val activeBackendType: StateFlow<FirewallBackendType?> = _activeBackendType.asStateFlow()
    
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

            // Stop any currently running backend first to avoid conflicts
            if (currentBackend != null) {
                Log.d(TAG, "Stopping existing backend before starting new one")
                stopFirewallInternal().getOrElse { error ->
                    Log.w(TAG, "Failed to stop existing backend: ${error.message}")
                    // Continue anyway - try to start new backend
                }
            }

            // Select backend
            val backend = selectBackend(mode).getOrElse { error ->
                Log.e(TAG, "Failed to select backend: ${error.message}")
                return Result.failure(error)
            }

            // Start backend
            backend.start().getOrElse { error ->
                Log.e(TAG, "Failed to start backend: ${error.message}")
                return Result.failure(error)
            }

            currentBackend = backend
            _activeBackendType.value = backend.getType()

            // Start monitoring for iptables backend only
            // (VPN backend monitors internally)
            if (backend.getType() == FirewallBackendType.IPTABLES) {
                startMonitoring()
            }

            Log.d(TAG, "Firewall started successfully with backend: ${backend.getType()}")
            Result.success(backend.getType())
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
            val backend = when (mode) {
                FirewallMode.AUTO -> {
                    // Try iptables first if available, fallback to VPN
                    val iptablesBackend = IptablesFirewallBackend(
                        context, rootManager, shizukuManager, errorHandler
                    )
                    
                    if (iptablesBackend.checkAvailability().isSuccess) {
                        Log.d(TAG, "AUTO mode: Selected iptables backend")
                        iptablesBackend
                    } else {
                        Log.d(TAG, "AUTO mode: iptables not available, using VPN backend")
                        VpnFirewallBackend(context)
                    }
                }
                
                FirewallMode.VPN -> {
                    Log.d(TAG, "VPN mode: Selected VPN backend")
                    VpnFirewallBackend(context)
                }
                
                FirewallMode.IPTABLES -> {
                    val iptablesBackend = IptablesFirewallBackend(
                        context, rootManager, shizukuManager, errorHandler
                    )
                    
                    // Check availability
                    iptablesBackend.checkAvailability().getOrElse { error ->
                        Log.e(TAG, "IPTABLES mode: Backend not available")
                        return Result.failure(error)
                    }
                    
                    Log.d(TAG, "IPTABLES mode: Selected iptables backend")
                    iptablesBackend
                }
            }
            
            Result.success(backend)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select backend", e)
            val error = errorHandler.handleError(e, "select firewall backend")
            Result.failure(error)
        }
    }
    
    /**
     * Start monitoring network and screen state changes.
     * Only used for iptables backend (VPN backend monitors internally).
     */
    private fun startMonitoring() {
        Log.d(TAG, "Starting state monitoring for iptables backend")
        
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
     * Apply rules to the current backend.
     */
    private suspend fun applyRules() {
        val backend = currentBackend ?: return

        if (backend.getType() != FirewallBackendType.IPTABLES) {
            // VPN backend handles rules internally
            return
        }

        try {
            // Get all rules from repository
            val rules = firewallRepository.getAllRules().first()

            // Apply rules
            backend.applyRules(rules, currentNetworkType, isScreenOn).getOrElse { error ->
                Log.e(TAG, "Failed to apply rules: ${error.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying rules", e)
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

