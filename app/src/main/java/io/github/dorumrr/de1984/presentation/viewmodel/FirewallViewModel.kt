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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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

    init {
        loadNetworkPackages()
        loadDefaultPolicy()
        loadFirewallState()
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
            networkState = null
        )
        // Store filter in pending state - DO NOT update StateFlow yet
        pendingFilterState = newFilterState

        // Load packages will emit the state with correct data
        loadNetworkPackages()
    }

    fun setNetworkStateFilter(networkState: String?) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(networkState = networkState)
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
                    pkg.copy(mobileBlocked = blocked, roamingBlocked = true)
                }

                // Persist both changes atomically - both must succeed or both fail
                val mobileResult = manageNetworkAccessUseCase.setMobileBlocking(packageName, blocked)
                val roamingResult = manageNetworkAccessUseCase.setRoamingBlocking(packageName, true)

                // Check if both operations succeeded
                if (mobileResult.isSuccess && roamingResult.isSuccess) {
                    // Both succeeded - optimistic update already applied, no need to reload
                } else {
                    // At least one failed - revert by reloading
                    loadNetworkPackages()
                    val error = mobileResult.exceptionOrNull() ?: roamingResult.exceptionOrNull()
                    if (error != null && superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to block mobile data: ${error?.message ?: "Unknown error"}"
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
                    pkg.copy(roamingBlocked = blocked, mobileBlocked = false)
                }

                // Persist both changes atomically - both must succeed or both fail
                val roamingResult = manageNetworkAccessUseCase.setRoamingBlocking(packageName, blocked)
                val mobileResult = manageNetworkAccessUseCase.setMobileBlocking(packageName, false)

                // Check if both operations succeeded
                if (roamingResult.isSuccess && mobileResult.isSuccess) {
                    // Both succeeded - optimistic update already applied, no need to reload
                } else {
                    // At least one failed - revert by reloading
                    loadNetworkPackages()
                    val error = roamingResult.exceptionOrNull() ?: mobileResult.exceptionOrNull()
                    if (error != null && superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to unblock roaming: ${error?.message ?: "Unknown error"}"
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
        val rootStatus = rootManager.rootStatus.value
        val shizukuStatus = shizukuManager.shizukuStatus.value

        Log.d(TAG, "=== startFirewall() called ===")
        Log.d(TAG, "Firewall mode: $mode")
        Log.d(TAG, "Root status: $rootStatus, hasRootPermission: $hasRoot")
        Log.d(TAG, "Shizuku status: $shizukuStatus, hasShizukuPermission: $hasShizuku")

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
                val willUseVpn = !canUseIptables && !canUseConnectivityManager
                Log.d(TAG, "AUTO mode: canUseIptables=$canUseIptables, canUseConnectivityManager=$canUseConnectivityManager, willUseVpn=$willUseVpn")
                willUseVpn
            }
            else -> {
                // IPTABLES, CONNECTIVITY_MANAGER, or other explicit modes don't need VPN
                false
            }
        }

        Log.d(TAG, "needsVpnPermission: $needsVpnPermission")

        if (needsVpnPermission) {
            // VPN mode - check permission first
            val prepareIntent = VpnService.prepare(getApplication())
            Log.d(TAG, "VPN prepareIntent: ${if (prepareIntent != null) "NEEDS PERMISSION" else "ALREADY GRANTED"}")
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

    private val rootManager = (getApplication<Application>() as io.github.dorumrr.de1984.De1984Application).dependencies.rootManager
    private val shizukuManager = (getApplication<Application>() as io.github.dorumrr.de1984.De1984Application).dependencies.shizukuManager

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
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
