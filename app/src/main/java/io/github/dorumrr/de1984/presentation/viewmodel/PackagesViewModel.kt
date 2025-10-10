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
        Log.d(TAG, ">>> ViewModel INIT - About to call loadPackages()")
        loadPackages()
    }

    fun loadPackages() {
        val timestamp = System.currentTimeMillis()
        Log.d(TAG, "════════════════════════════════════════════════════════════════")
        Log.d(TAG, "[$timestamp] loadPackages() CALLED")
        Log.d(TAG, "[$timestamp] Thread: ${Thread.currentThread().name}")
        Log.d(TAG, "[$timestamp] Current loadJob: $loadJob (isActive=${loadJob?.isActive}, isCancelled=${loadJob?.isCancelled}, isCompleted=${loadJob?.isCompleted})")

        // Cancel any previous loading operation
        if (loadJob != null) {
            Log.d(TAG, "[$timestamp] CANCELLING previous loadJob: $loadJob")
            loadJob?.cancel()
            Log.d(TAG, "[$timestamp] Previous loadJob cancelled. New state: isActive=${loadJob?.isActive}, isCancelled=${loadJob?.isCancelled}")
        } else {
            Log.d(TAG, "[$timestamp] No previous loadJob to cancel")
        }

        // Use pending filter if available, otherwise use current filter
        val filterState = pendingFilterState ?: _uiState.value.filterState
        Log.d(TAG, "[$timestamp] Filter state: type=${filterState.packageType}, state=${filterState.packageState}")
        Log.d(TAG, "[$timestamp] Pending filter: ${pendingFilterState?.let { "type=${it.packageType}, state=${it.packageState}" } ?: "null"}")

        // Clear pending filter immediately after using it
        pendingFilterState = null
        Log.d(TAG, "[$timestamp] Cleared pendingFilterState")

        Log.d(TAG, "[$timestamp] Current UI state BEFORE clear: packages.size=${_uiState.value.packages.size}, isLoadingData=${_uiState.value.isLoadingData}, isRenderingUI=${_uiState.value.isRenderingUI}")

        // Clear packages AND update filter state immediately to prevent showing stale data
        Log.d(TAG, "[$timestamp] CLEARING packages and setting isLoadingData=true")
        Log.d(TAG, "[$timestamp] UPDATING filterState to: type=${filterState.packageType}, state=${filterState.packageState}")
        _uiState.value = _uiState.value.copy(
            isLoadingData = true,
            isRenderingUI = false,
            packages = emptyList(),
            filterState = filterState  // Update filter immediately!
        )
        Log.d(TAG, "[$timestamp] UI state AFTER clear: packages.size=${_uiState.value.packages.size}, isLoadingData=${_uiState.value.isLoadingData}, isRenderingUI=${_uiState.value.isRenderingUI}, filterState: type=${_uiState.value.filterState.packageType}")
        Log.d(TAG, "[$timestamp] STATE EMITTED: empty list, isLoadingData=true, filterState updated")

        Log.d(TAG, "[$timestamp] Starting Flow collection for filter: type=${filterState.packageType}, state=${filterState.packageState}")
        loadJob = getPackagesUseCase.getFilteredByState(filterState)
            .catch { error ->
                val errorTimestamp = System.currentTimeMillis()
                Log.e(TAG, "[$errorTimestamp] ❌ ERROR in Flow: ${error.message}")
                Log.e(TAG, "[$errorTimestamp] Error stacktrace:", error)
                _uiState.value = _uiState.value.copy(
                    isLoadingData = false,
                    isRenderingUI = false,
                    error = error.message
                )
                Log.d(TAG, "[$errorTimestamp] STATE EMITTED: error state, isLoadingData=false")
            }
            .onEach { packages ->
                val onEachTimestamp = System.currentTimeMillis()
                Log.d(TAG, "────────────────────────────────────────────────────────────────")
                Log.d(TAG, "[$onEachTimestamp] ✅ Flow.onEach TRIGGERED")
                Log.d(TAG, "[$onEachTimestamp] Thread: ${Thread.currentThread().name}")
                Log.d(TAG, "[$onEachTimestamp] Received ${packages.size} packages")
                Log.d(TAG, "[$onEachTimestamp] First 5 packages: ${packages.take(5).map { it.packageName }}")
                Log.d(TAG, "[$onEachTimestamp] Current loadJob: $loadJob (isActive=${loadJob?.isActive})")
                Log.d(TAG, "[$onEachTimestamp] Current UI state BEFORE update: packages.size=${_uiState.value.packages.size}, isLoadingData=${_uiState.value.isLoadingData}")

                // Filter state was already updated when we started loading
                val newState = _uiState.value.copy(
                    packages = packages,
                    isLoadingData = false,
                    isRenderingUI = true,
                    error = null
                )
                Log.d(TAG, "[$onEachTimestamp] New state created: packages.size=${newState.packages.size}, isLoadingData=${newState.isLoadingData}, isRenderingUI=${newState.isRenderingUI}, filterState: type=${newState.filterState.packageType}")
                Log.d(TAG, "[$onEachTimestamp] EMITTING STATE with ${packages.size} packages")
                _uiState.value = newState
                Log.d(TAG, "[$onEachTimestamp] STATE EMITTED SUCCESSFULLY")
                Log.d(TAG, "[$onEachTimestamp] Current UI state AFTER update: packages.size=${_uiState.value.packages.size}, isLoadingData=${_uiState.value.isLoadingData}")
                Log.d(TAG, "────────────────────────────────────────────────────────────────")
            }
            .launchIn(viewModelScope)

        Log.d(TAG, "[$timestamp] New loadJob created: $loadJob (isActive=${loadJob?.isActive})")
        Log.d(TAG, "[$timestamp] loadPackages() COMPLETED")
        Log.d(TAG, "════════════════════════════════════════════════════════════════")
    }
    
    fun setPackageTypeFilter(packageType: String) {
        Log.d(TAG, ">>> setPackageTypeFilter called: $packageType")
        val newFilterState = PackageFilterState(
            packageType = packageType,
            packageState = null
        )
        // Store filter in pending state - DO NOT update StateFlow yet
        Log.d(TAG, ">>> Storing filter in pendingFilterState (no StateFlow emission)")
        pendingFilterState = newFilterState

        // Load packages will emit the state with correct data
        Log.d(TAG, ">>> Calling loadPackages()")
        loadPackages()
    }

    fun setPackageStateFilter(packageState: String?) {
        Log.d(TAG, ">>> setPackageStateFilter called: $packageState")
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(packageState = packageState)
        // Store filter in pending state - DO NOT update StateFlow yet
        Log.d(TAG, ">>> Storing filter in pendingFilterState (no StateFlow emission)")
        pendingFilterState = newFilterState

        // Load packages will emit the state with correct data
        Log.d(TAG, ">>> Calling loadPackages()")
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
                    // Reload packages to apply filters correctly
                    loadPackages()
                }
                .onFailure { error ->
                    // Revert on failure
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
    val isLoadingData: Boolean = true,
    val isRenderingUI: Boolean = false,
    val error: String? = null
) {
    val isLoading: Boolean get() = isLoadingData || isRenderingUI
}
