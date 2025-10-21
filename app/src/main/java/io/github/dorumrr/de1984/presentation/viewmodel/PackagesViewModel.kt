package io.github.dorumrr.de1984.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.usecase.GetPackagesUseCase
import io.github.dorumrr.de1984.domain.usecase.ManagePackageUseCase
import io.github.dorumrr.de1984.ui.common.SuperuserBannerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class PackagesViewModel(
    private val getPackagesUseCase: GetPackagesUseCase,
    private val managePackageUseCase: ManagePackageUseCase,
    private val superuserBannerState: SuperuserBannerState,
    private val rootManager: RootManager
) : ViewModel() {

    private val TAG = "PackagesViewModel"

    private val _uiState = MutableStateFlow(PackagesUiState())
    val uiState: StateFlow<PackagesUiState> = _uiState.asStateFlow()

    // Store pending filter state separately to avoid triggering UI updates
    private var pendingFilterState: PackageFilterState? = null

    // Job to track the current data loading operation
    private var loadJob: Job? = null

    val showRootBanner: StateFlow<Boolean>
        get() = superuserBannerState.showBanner

    fun dismissRootBanner() {
        superuserBannerState.hideBanner()
    }

    fun checkRootAccess() {
        viewModelScope.launch {
            rootManager.checkRootStatus()
        }
    }

    init {
        loadPackages()
    }

    fun loadPackages() {
        // Cancel any previous loading operation
        loadJob?.cancel()

        // Use pending filter if available, otherwise use current filter
        val filterState = pendingFilterState ?: _uiState.value.filterState
        pendingFilterState = null

        // Clear packages and update filter state immediately
        _uiState.value = _uiState.value.copy(
            isLoadingData = true,
            isRenderingUI = false,
            packages = emptyList(),
            filterState = filterState
        )

        loadJob = getPackagesUseCase.getFilteredByState(filterState)
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
        val newFilterState = PackageFilterState(
            packageType = packageType,
            packageState = null
        )
        pendingFilterState = newFilterState
        loadPackages()
    }

    fun setPackageStateFilter(packageState: String?) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(packageState = packageState)
        pendingFilterState = newFilterState
        loadPackages()
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            // Optimistically update UI first
            updatePackageInList(packageName) { pkg ->
                pkg.copy(isEnabled = enabled)
            }

            // Then persist to system
            managePackageUseCase.setPackageEnabled(packageName, enabled)
                .onSuccess {
                    // Success - optimistic update already applied, no need to reload
                }
                .onFailure { error ->
                    // Revert on failure by reloading
                    loadPackages()
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    fun uninstallPackage(packageName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingData = true,
                isRenderingUI = false
            )

            managePackageUseCase.uninstallPackage(packageName)
                .onSuccess {
                    loadPackages()
                }
                .onFailure { error ->
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoadingData = false,
                        isRenderingUI = false,
                        error = error.message
                    )
                }
        }
    }

    fun forceStopPackage(packageName: String) {
        viewModelScope.launch {
            // Force stop doesn't change package state, so no optimistic update needed
            // Just execute the action
            managePackageUseCase.forceStopPackage(packageName)
                .onSuccess {
                    // Success - no UI update needed (package state unchanged)
                }
                .onFailure { error ->
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }
    
    fun setUIReady() {
        _uiState.value = _uiState.value.copy(isRenderingUI = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    private fun updatePackageInList(packageName: String, transform: (Package) -> Package) {
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

    class Factory(
        private val getPackagesUseCase: GetPackagesUseCase,
        private val managePackageUseCase: ManagePackageUseCase,
        private val superuserBannerState: SuperuserBannerState,
        private val rootManager: RootManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PackagesViewModel::class.java)) {
                return PackagesViewModel(
                    getPackagesUseCase,
                    managePackageUseCase,
                    superuserBannerState,
                    rootManager
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class PackageFilterState(
    val packageType: String = "User",
    val packageState: String? = null
)

data class PackagesUiState(
    val packages: List<Package> = emptyList(),
    val filterState: PackageFilterState = PackageFilterState(),
    val searchQuery: String = "",
    val isLoadingData: Boolean = true,
    val isRenderingUI: Boolean = false,
    val error: String? = null
) {
    val isLoading: Boolean get() = isLoadingData || isRenderingUI
}
