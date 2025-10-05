package io.github.dorumrr.de1984.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.FirewallFilterState
import io.github.dorumrr.de1984.domain.usecase.GetNetworkPackagesUseCase
import io.github.dorumrr.de1984.domain.usecase.ManageNetworkAccessUseCase
import io.github.dorumrr.de1984.ui.common.SuperuserBannerState
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FirewallViewModel @Inject constructor(
    application: Application,
    private val getNetworkPackagesUseCase: GetNetworkPackagesUseCase,
    private val manageNetworkAccessUseCase: ManageNetworkAccessUseCase,
    private val superuserBannerState: SuperuserBannerState,
    private val permissionManager: io.github.dorumrr.de1984.data.common.PermissionManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FirewallUiState())
    val uiState: StateFlow<FirewallUiState> = _uiState.asStateFlow()

    val showRootBanner: Boolean
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
        _uiState.value = _uiState.value.copy(
            isLoadingData = true,
            isRenderingUI = false
        )

        val filterState = _uiState.value.filterState

        getNetworkPackagesUseCase.getFilteredByState(filterState)
            .catch { error ->
                _uiState.value = _uiState.value.copy(
                    isLoadingData = false,
                    isRenderingUI = false,
                    error = error.message
                )
            }
            .onEach { packages ->
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
        _uiState.value = _uiState.value.copy(
            filterState = newFilterState,
            isLoadingData = true,
            isRenderingUI = false
        )

        loadNetworkPackages()
    }

    fun setNetworkStateFilter(networkState: String?) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(networkState = networkState)
        _uiState.value = _uiState.value.copy(
            filterState = newFilterState,
            isLoadingData = true,
            isRenderingUI = false
        )
        loadNetworkPackages()
    }
    
    fun setWifiBlocking(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            manageNetworkAccessUseCase.setWifiBlocking(packageName, blocked)
                .onSuccess {
                    loadNetworkPackages()
                }
                .onFailure { error ->
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    fun setMobileBlocking(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            manageNetworkAccessUseCase.setMobileBlocking(packageName, blocked)
                .onSuccess {
                    loadNetworkPackages()
                }
                .onFailure { error ->
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    fun setRoamingBlocking(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            manageNetworkAccessUseCase.setRoamingBlocking(packageName, blocked)
                .onSuccess {
                    loadNetworkPackages()
                }
                .onFailure { error ->
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
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
        val prepareIntent = VpnService.prepare(getApplication())

        if (prepareIntent == null) {
            val intent = Intent(getApplication(), FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_START
            }
            getApplication<Application>().startService(intent)

            _uiState.value = _uiState.value.copy(isFirewallEnabled = true)
            saveFirewallState(true)
            return null
        } else {
            return prepareIntent
        }
    }

    fun stopFirewall() {
        val intent = Intent(getApplication(), FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)

        _uiState.value = _uiState.value.copy(isFirewallEnabled = false)
        saveFirewallState(false)
    }

    fun onVpnPermissionGranted(): Intent? {
        startFirewall()
        return permissionManager.createBatteryOptimizationIntent()
    }

    fun onVpnPermissionDenied() {
        _uiState.value = _uiState.value.copy(isFirewallEnabled = false)
        saveFirewallState(false)
    }
}

data class FirewallUiState(
    val packages: List<NetworkPackage> = emptyList(),
    val filterState: FirewallFilterState = FirewallFilterState(),
    val isLoadingData: Boolean = true,
    val isRenderingUI: Boolean = false,
    val error: String? = null,
    val isFirewallEnabled: Boolean = false,
    val defaultFirewallPolicy: String = Constants.Settings.DEFAULT_FIREWALL_POLICY
) {
    val isLoading: Boolean get() = isLoadingData || isRenderingUI
}
