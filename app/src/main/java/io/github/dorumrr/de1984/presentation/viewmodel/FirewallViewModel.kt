package io.github.dorumrr.de1984.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.firewall.FirewallManager
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.FirewallFilterState
import io.github.dorumrr.de1984.domain.usecase.GetNetworkPackagesUseCase
import io.github.dorumrr.de1984.domain.usecase.ManageNetworkAccessUseCase
import io.github.dorumrr.de1984.ui.common.SuperuserBannerState
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class FirewallViewModel(
    application: Application,
    private val getNetworkPackagesUseCase: GetNetworkPackagesUseCase,
    private val manageNetworkAccessUseCase: ManageNetworkAccessUseCase,
    private val superuserBannerState: SuperuserBannerState,
    private val permissionManager: io.github.dorumrr.de1984.data.common.PermissionManager,
    private val firewallManager: FirewallManager
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FirewallViewModel"
    }

    private val _uiState = MutableStateFlow(FirewallUiState())
    val uiState: StateFlow<FirewallUiState> = _uiState.asStateFlow()

    // Store pending filter state separately to avoid triggering UI updates
    private var pendingFilterState: FirewallFilterState? = null

    // Job to track the current data loading operation
    private var loadJob: Job? = null

    val showRootBanner: StateFlow<Boolean>
        get() = superuserBannerState.showBanner

    fun dismissRootBanner() {
        superuserBannerState.hideBanner()
    }

    // Access managers from application dependencies
    private val rootManager = (getApplication<Application>() as io.github.dorumrr.de1984.De1984Application).dependencies.rootManager
    private val shizukuManager = (getApplication<Application>() as io.github.dorumrr.de1984.De1984Application).dependencies.shizukuManager

    // Track last processed status to prevent duplicate restarts
    private var lastProcessedRootStatus: RootStatus? = null
    private var lastProcessedShizukuStatus: ShizukuStatus? = null

    init {
        loadNetworkPackages()
        loadDefaultPolicy()
        loadFirewallState()
        observePrivilegeChanges()
    }

    private fun loadDefaultPolicy() {
        val prefs = getApplication<Application>().getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val policy = prefs.getString(
            Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
            Constants.Settings.DEFAULT_FIREWALL_POLICY
        ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY

        _uiState.value = _uiState.value.copy(defaultFirewallPolicy = policy)
    }

    private fun loadFirewallState() {
        val prefs = getApplication<Application>().getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, Constants.Settings.DEFAULT_FIREWALL_ENABLED)

        _uiState.value = _uiState.value.copy(isFirewallEnabled = isEnabled)
    }

    private fun saveFirewallState(enabled: Boolean) {
        val prefs = getApplication<Application>().getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, enabled).apply()

    }

    fun refreshDefaultPolicy() {
        loadDefaultPolicy()
        loadNetworkPackages()
    }
    
    fun loadNetworkPackages() {
        // Cancel any previous loading operation
        loadJob?.cancel()

        // Use pending filter if available, otherwise use current filter
        val filterState = pendingFilterState ?: _uiState.value.filterState

        // Clear pending filter immediately after using it
        pendingFilterState = null

        // Clear packages AND update filter state immediately to prevent showing stale data
        _uiState.value = _uiState.value.copy(
            isLoadingData = true,
            isRenderingUI = false,
            packages = emptyList(),
            filterState = filterState  // Update filter immediately!
        )

        loadJob = getNetworkPackagesUseCase.getFilteredByState(filterState)
            .catch { error ->
                _uiState.value = _uiState.value.copy(
                    isLoadingData = false,
                    isRenderingUI = false,
                    error = error.message
                )
            }
            .onEach { packages ->
                // Filter state was already updated when we started loading
                _uiState.value = _uiState.value.copy(
                    packages = packages,
                    isLoadingData = false,
                    isRenderingUI = true,
                    error = null
                )
            }
            .launchIn(viewModelScope)
    }
    
    fun setPackageTypeFilter(packageType: String) {
        val newFilterState = FirewallFilterState(
            packageType = packageType,
            networkState = null,
            internetOnly = false  // Reset Internet Only when switching User/System
        )
        // Store filter in pending state - DO NOT update StateFlow yet
        pendingFilterState = newFilterState

        // Load packages will emit the state with correct data
        loadNetworkPackages()
    }

    fun setNetworkStateFilter(networkState: String?) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            networkState = networkState
            // internetOnly is preserved when switching Allowed/Blocked
        )
        // Store filter in pending state - DO NOT update StateFlow yet
        pendingFilterState = newFilterState

        // Load packages will emit the state with correct data
        loadNetworkPackages()
    }

    fun setInternetOnlyFilter(internetOnly: Boolean) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            internetOnly = internetOnly
            // networkState and packageType are preserved
        )
        // Store filter in pending state - DO NOT update StateFlow yet
        pendingFilterState = newFilterState

        // Load packages will emit the state with correct data
        loadNetworkPackages()
    }
    
    fun setWifiBlocking(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            // Optimistically update UI first
            updatePackageInList(packageName) { pkg ->
                pkg.copy(wifiBlocked = blocked)
            }

            // Then persist to database
            manageNetworkAccessUseCase.setWifiBlocking(packageName, blocked)
                .onSuccess {
                    // Success - optimistic update already applied, no need to reload
                }
                .onFailure { error ->
                    // Revert on failure by reloading
                    loadNetworkPackages()
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    fun setMobileBlocking(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            // If mobile is being blocked, also block roaming (roaming requires mobile)
            if (blocked) {
                // Optimistically update both mobile and roaming
                updatePackageInList(packageName) { pkg ->
                    pkg.copy(mobileBlocked = true, roamingBlocked = true)
                }

                // Persist with atomic batch update - only one database transaction, only one notification
                manageNetworkAccessUseCase.setMobileAndRoaming(packageName, mobileBlocked = true, roamingBlocked = true)
                    .onSuccess {
                        // Success - optimistic update already applied, no need to reload
                    }
                    .onFailure { error ->
                        // Revert on failure by reloading
                        loadNetworkPackages()
                        if (superuserBannerState.shouldShowBannerForError(error)) {
                            superuserBannerState.showSuperuserRequiredBanner()
                        }
                        _uiState.value = _uiState.value.copy(
                            error = "Failed to block mobile data: ${error.message ?: "Unknown error"}"
                        )
                    }
            } else {
                // Mobile is being enabled - only update mobile, leave roaming as is
                updatePackageInList(packageName) { pkg ->
                    pkg.copy(mobileBlocked = blocked)
                }

                // Then persist to database
                manageNetworkAccessUseCase.setMobileBlocking(packageName, blocked)
                    .onSuccess {
                        // Success - optimistic update already applied, no need to reload
                    }
                    .onFailure { error ->
                        // Revert on failure by reloading
                        loadNetworkPackages()
                        if (superuserBannerState.shouldShowBannerForError(error)) {
                            superuserBannerState.showSuperuserRequiredBanner()
                        }
                        _uiState.value = _uiState.value.copy(error = error.message)
                    }
            }
        }
    }

    fun setRoamingBlocking(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            // If roaming is being enabled (unblocked), also enable mobile (roaming requires mobile)
            if (!blocked) {
                // Optimistically update both roaming and mobile
                updatePackageInList(packageName) { pkg ->
                    pkg.copy(roamingBlocked = false, mobileBlocked = false)
                }

                // Persist with atomic batch update - only one database transaction, only one notification
                manageNetworkAccessUseCase.setMobileAndRoaming(packageName, mobileBlocked = false, roamingBlocked = false)
                    .onSuccess {
                        // Success - optimistic update already applied, no need to reload
                    }
                    .onFailure { error ->
                        // Revert on failure by reloading
                        loadNetworkPackages()
                        if (superuserBannerState.shouldShowBannerForError(error)) {
                            superuserBannerState.showSuperuserRequiredBanner()
                        }
                        _uiState.value = _uiState.value.copy(
                            error = "Failed to unblock roaming: ${error.message ?: "Unknown error"}"
                        )
                    }
            } else {
                // Roaming is being disabled - only update roaming, leave mobile as is
                updatePackageInList(packageName) { pkg ->
                    pkg.copy(roamingBlocked = blocked)
                }

                // Then persist to database
                manageNetworkAccessUseCase.setRoamingBlocking(packageName, blocked)
                    .onSuccess {
                        // Success - optimistic update already applied, no need to reload
                    }
                    .onFailure { error ->
                        // Revert on failure by reloading
                        loadNetworkPackages()
                        if (superuserBannerState.shouldShowBannerForError(error)) {
                            superuserBannerState.showSuperuserRequiredBanner()
                        }
                        _uiState.value = _uiState.value.copy(error = error.message)
                    }
            }
        }
    }

    fun setAllNetworkBlocking(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            // Optimistically update all network types at once
            updatePackageInList(packageName) { pkg ->
                pkg.copy(
                    wifiBlocked = blocked,
                    mobileBlocked = blocked,
                    roamingBlocked = blocked
                )
            }

            // Persist with atomic batch update - only one database transaction, only one notification
            manageNetworkAccessUseCase.setAllNetworkBlocking(packageName, blocked)
                .onSuccess {
                    // Success - optimistic update already applied, no need to reload
                }
                .onFailure { error ->
                    // Revert on failure by reloading
                    loadNetworkPackages()
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    private fun updatePackageInList(packageName: String, transform: (NetworkPackage) -> NetworkPackage) {
        val currentPackages = _uiState.value.packages
        val updatedPackages = currentPackages.map { pkg ->
            if (pkg.packageName == packageName) {
                transform(pkg)
            } else {
                pkg
            }
        }
        _uiState.value = _uiState.value.copy(packages = updatedPackages)
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun setUIReady() {
        _uiState.value = _uiState.value.copy(isRenderingUI = false)
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(
            isLoadingData = true,
            isRenderingUI = false
        )
        loadNetworkPackages()
    }

    fun startFirewall(): Intent? {
        // Check if VPN permission is needed BEFORE starting firewall
        val mode = firewallManager.getCurrentMode()
        val hasRoot = rootManager.hasRootPermission
        val hasShizuku = shizukuManager.hasShizukuPermission

        // Determine if VPN backend will be used
        val needsVpnPermission = when (mode) {
            io.github.dorumrr.de1984.domain.firewall.FirewallMode.VPN -> {
                // Explicit VPN mode
                true
            }
            io.github.dorumrr.de1984.domain.firewall.FirewallMode.AUTO -> {
                // AUTO mode: check if iptables or ConnectivityManager will be available
                val canUseIptables = hasRoot || (hasShizuku && shizukuManager.isShizukuRootMode())
                val canUseConnectivityManager = hasShizuku && android.os.Build.VERSION.SDK_INT >= 33
                !canUseIptables && !canUseConnectivityManager
            }
            else -> {
                // IPTABLES, CONNECTIVITY_MANAGER, or other explicit modes don't need VPN
                false
            }
        }

        if (needsVpnPermission) {
            // VPN mode - check permission first
            val prepareIntent = VpnService.prepare(getApplication())
            if (prepareIntent != null) {
                // Permission not granted yet - return intent to request it
                return prepareIntent
            }
            // Permission already granted - continue to start firewall
        }

        // Start firewall (either iptables mode or VPN with permission already granted)
        viewModelScope.launch {
            val result = firewallManager.startFirewall()

            result.onSuccess { _ ->
                _uiState.value = _uiState.value.copy(isFirewallEnabled = true)
                saveFirewallState(true)

                // Request battery optimization exemption after firewall starts successfully
                // This is important for both VPN and iptables modes to prevent service from being killed
                requestBatteryOptimizationIfNeeded()
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isFirewallEnabled = false,
                    error = error.message
                )
                saveFirewallState(false)
            }
        }

        return null
    }

    private fun requestBatteryOptimizationIfNeeded() {
        // Check if battery optimization is already disabled
        if (permissionManager.isBatteryOptimizationDisabled()) {
            return
        }

        // Trigger battery optimization request via MainActivity
        // This is done by setting a flag that MainActivity will observe
        _uiState.value = _uiState.value.copy(
            shouldRequestBatteryOptimization = true
        )
    }

    fun clearBatteryOptimizationRequest() {
        _uiState.value = _uiState.value.copy(
            shouldRequestBatteryOptimization = false
        )
    }

    fun stopFirewall() {
        viewModelScope.launch {
            firewallManager.stopFirewall()
            _uiState.value = _uiState.value.copy(isFirewallEnabled = false)
            saveFirewallState(false)
        }
    }

    fun onVpnPermissionGranted() {
        // Start firewall after VPN permission is granted
        // Battery optimization will be requested automatically by startFirewall()
        startFirewall()
    }

    fun onVpnPermissionDenied() {
        _uiState.value = _uiState.value.copy(isFirewallEnabled = false)
        saveFirewallState(false)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    private fun observePrivilegeChanges() {
        viewModelScope.launch {
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

    private suspend fun handlePrivilegeChange(
        rootStatus: RootStatus,
        shizukuStatus: ShizukuStatus
    ) {
        // Skip if we've already processed this exact status combination
        if (rootStatus == lastProcessedRootStatus &&
            shizukuStatus == lastProcessedShizukuStatus) {
            return
        }

        lastProcessedRootStatus = rootStatus
        lastProcessedShizukuStatus = shizukuStatus

        // Only act if firewall is running
        if (!firewallManager.isActive()) {
            Log.d(TAG, "Firewall not active, skipping privilege change handling")
            return
        }

        // Check if backend would change (handles both privilege gain AND loss)
        val currentBackend = firewallManager.activeBackendType.value
        val wouldChange = wouldBackendChange(currentBackend)

        if (wouldChange) {
            // Determine if this is privilege gain or loss
            val hasPrivileges =
                rootStatus == RootStatus.ROOTED_WITH_PERMISSION ||
                shizukuStatus == ShizukuStatus.RUNNING_WITH_PERMISSION

            if (hasPrivileges) {
                Log.d(TAG, "Backend would change due to NEW privileges, restarting firewall...")
            } else {
                Log.d(TAG, "Backend would change due to LOST privileges (Shizuku/root unavailable), restarting with fallback backend...")
            }
            restartFirewallWithNewBackend()
        } else {
            Log.d(TAG, "Backend would not change, no restart needed")
        }
    }

    private fun wouldBackendChange(currentBackend: FirewallBackendType?): Boolean {
        val mode = firewallManager.getCurrentMode()

        // Only care about AUTO mode (manual modes are user's explicit choice)
        if (mode != io.github.dorumrr.de1984.domain.firewall.FirewallMode.AUTO) {
            Log.d(TAG, "Firewall mode is $mode (not AUTO), skipping backend change check")
            return false
        }

        val hasRoot = rootManager.hasRootPermission
        val hasShizuku = shizukuManager.hasShizukuPermission
        val isAndroid13Plus = android.os.Build.VERSION.SDK_INT >= 33

        // Determine what backend would be selected now (matches FirewallManager.selectBackend logic)
        val newBackend = when {
            hasRoot || (hasShizuku && shizukuManager.isShizukuRootMode()) ->
                FirewallBackendType.IPTABLES
            hasShizuku && isAndroid13Plus ->
                FirewallBackendType.CONNECTIVITY_MANAGER
            else ->
                FirewallBackendType.VPN
        }

        val wouldChange = currentBackend != newBackend
        Log.d(TAG, "Backend check: current=$currentBackend, new=$newBackend, wouldChange=$wouldChange")
        return wouldChange
    }

    private suspend fun restartFirewallWithNewBackend() {
        try {
            Log.d(TAG, "=== Restarting firewall due to privilege change ===")

            // Stop current firewall
            firewallManager.stopFirewall()

            // Small delay to ensure clean shutdown
            delay(500)

            // Start with new backend (will auto-select based on new privileges)
            val result = firewallManager.startFirewall()

            result.onSuccess { backendType ->
                Log.d(TAG, "✅ Firewall restarted successfully with backend: $backendType")
                _uiState.value = _uiState.value.copy(isFirewallEnabled = true)
                saveFirewallState(true)

                // Reload packages to refresh UI with correct controls
                loadNetworkPackages()
            }.onFailure { error ->
                Log.e(TAG, "❌ Failed to restart firewall: ${error.message}")
                _uiState.value = _uiState.value.copy(
                    isFirewallEnabled = false,
                    error = "Failed to restart firewall with new backend: ${error.message}"
                )
                saveFirewallState(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during firewall restart", e)
            _uiState.value = _uiState.value.copy(
                isFirewallEnabled = false,
                error = "Failed to restart firewall: ${e.message}"
            )
            saveFirewallState(false)
        }
    }

    class Factory(
        private val application: Application,
        private val getNetworkPackagesUseCase: GetNetworkPackagesUseCase,
        private val manageNetworkAccessUseCase: ManageNetworkAccessUseCase,
        private val superuserBannerState: SuperuserBannerState,
        private val permissionManager: io.github.dorumrr.de1984.data.common.PermissionManager,
        private val firewallManager: FirewallManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FirewallViewModel::class.java)) {
                return FirewallViewModel(
                    application,
                    getNetworkPackagesUseCase,
                    manageNetworkAccessUseCase,
                    superuserBannerState,
                    permissionManager,
                    firewallManager
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class FirewallUiState(
    val packages: List<NetworkPackage> = emptyList(),
    val filterState: FirewallFilterState = FirewallFilterState(),
    val searchQuery: String = "",
    val isLoadingData: Boolean = true,
    val isRenderingUI: Boolean = false,
    val error: String? = null,
    val isFirewallEnabled: Boolean = false,
    val defaultFirewallPolicy: String = Constants.Settings.DEFAULT_FIREWALL_POLICY,
    val shouldRequestBatteryOptimization: Boolean = false
) {
    val isLoading: Boolean get() = isLoadingData || isRenderingUI
}
