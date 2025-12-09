package io.github.dorumrr.de1984.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.ReinstallBatchResult
import io.github.dorumrr.de1984.domain.model.UninstallBatchResult
import io.github.dorumrr.de1984.domain.usecase.GetPackagesUseCase
import io.github.dorumrr.de1984.domain.usecase.ManagePackageUseCase
import io.github.dorumrr.de1984.ui.common.SuperuserBannerState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class PackagesViewModel(
    private val getPackagesUseCase: GetPackagesUseCase,
    private val managePackageUseCase: ManagePackageUseCase,
    private val superuserBannerState: SuperuserBannerState,
    val rootManager: RootManager,
    val shizukuManager: ShizukuManager,
    private val packageDataChanged: SharedFlow<Unit>
) : ViewModel() {

    private val TAG = "PackagesViewModel"

    private val _uiState = MutableStateFlow(PackagesUiState())
    val uiState: StateFlow<PackagesUiState> = _uiState.asStateFlow()

    // Store pending filter state separately to avoid triggering UI updates
    private var pendingFilterState: PackageFilterState? = null

    // Job to track the current data loading operation
    private var loadJob: Job? = null

    // Performance optimization: cache all packages to avoid re-fetching from system on filter change
    private var cachedPackages: List<Package> = emptyList()

    val showRootBanner: StateFlow<Boolean>
        get() = superuserBannerState.showBanner

    fun dismissRootBanner() {
        superuserBannerState.hideBanner()
    }

    fun checkRootAccess() {
        viewModelScope.launch {
            // Check Shizuku first (preferred method)
            shizukuManager.checkShizukuStatus()

            // If Shizuku is available but permission not granted, request it
            if (shizukuManager.isShizukuAvailable() && !shizukuManager.hasShizukuPermission) {
                shizukuManager.requestShizukuPermission()
            }

            // Also check root as fallback
            rootManager.checkRootStatus()
        }
    }

    init {
        loadPackages()
        observePackageDataChanges()
    }

    /**
     * Observe package data changes from other screens (e.g., Firewall Rules).
     * When firewall rules change, refresh the list to show updated state.
     * Debounced to prevent rapid successive refreshes.
     */
    @OptIn(FlowPreview::class)
    private fun observePackageDataChanges() {
        packageDataChanged
            .debounce(300L) // Debounce rapid changes to prevent excessive refreshes
            .onEach {
                Log.d(TAG, "Package data changed, refreshing list")
                loadPackages(forceRefresh = true)
            }
            .launchIn(viewModelScope)
    }

    /**
     * Load packages from system. Only fetches from system if cache is empty or forceRefresh is true.
     * @param forceRefresh If true, always fetch from system regardless of cache state
     */
    fun loadPackages(forceRefresh: Boolean = false) {
        // Cancel any previous loading operation
        loadJob?.cancel()

        // Use pending filter if available, otherwise use current filter
        val filterState = pendingFilterState ?: _uiState.value.filterState
        pendingFilterState = null

        // If we have cached data and not forcing refresh, just apply filters
        if (cachedPackages.isNotEmpty() && !forceRefresh) {
            applyFilters(filterState)
            return
        }

        // Clear packages and update filter state immediately
        _uiState.value = _uiState.value.copy(
            isLoadingData = true,
            isRenderingUI = false,
            packages = emptyList(),
            filterState = filterState
        )

        // Fetch ALL packages (unfiltered) and cache them
        loadJob = getPackagesUseCase.invoke()
            .catch { error ->
                _uiState.value = _uiState.value.copy(
                    isLoadingData = false,
                    isRenderingUI = false,
                    error = error.message
                )
            }
            .onEach { packages ->
                // Cache the full list
                cachedPackages = packages
                // Apply current filters to the cached list
                val filteredPackages = filterPackages(packages, filterState)
                _uiState.value = _uiState.value.copy(
                    packages = filteredPackages,
                    isLoadingData = false,
                    isRenderingUI = true,
                    error = null
                )
            }
            .launchIn(viewModelScope)
    }

    /**
     * Apply filters to cached packages (fast, in-memory operation).
     * Does not fetch from system.
     */
    private fun applyFilters(filterState: PackageFilterState) {
        _uiState.value = _uiState.value.copy(
            filterState = filterState
        )

        val filteredPackages = filterPackages(cachedPackages, filterState)
        _uiState.value = _uiState.value.copy(
            packages = filteredPackages,
            isLoadingData = false,
            isRenderingUI = true,
            error = null
        )
    }

    /**
     * Filter packages in-memory based on filter state.
     */
    private fun filterPackages(packages: List<Package>, filterState: PackageFilterState): List<Package> {
        var result = packages

        // Apply type filter
        result = when (filterState.packageType.lowercase()) {
            io.github.dorumrr.de1984.utils.Constants.Packages.TYPE_USER.lowercase() ->
                result.filter { it.type == io.github.dorumrr.de1984.domain.model.PackageType.USER }
            io.github.dorumrr.de1984.utils.Constants.Packages.TYPE_SYSTEM.lowercase() ->
                result.filter { it.type == io.github.dorumrr.de1984.domain.model.PackageType.SYSTEM }
            else -> result // "all" or default
        }

        // Apply profile filter
        result = when (filterState.profileFilter.lowercase()) {
            "personal" -> result.filter { !it.isWorkProfile && !it.isCloneProfile }
            "work" -> result.filter { it.isWorkProfile }
            "clone" -> result.filter { it.isCloneProfile }
            else -> result // "all" or default
        }

        // Apply state filter
        if (filterState.packageState != null) {
            result = when (filterState.packageState.lowercase()) {
                io.github.dorumrr.de1984.utils.Constants.Packages.STATE_ENABLED.lowercase() ->
                    result.filter { it.isEnabled }
                io.github.dorumrr.de1984.utils.Constants.Packages.STATE_DISABLED.lowercase() ->
                    result.filter { !it.isEnabled }
                io.github.dorumrr.de1984.utils.Constants.Packages.STATE_UNINSTALLED.lowercase() ->
                    result.filter { it.versionName == null && !it.isEnabled && it.type == io.github.dorumrr.de1984.domain.model.PackageType.SYSTEM }
                else -> result
            }
        }

        return result
    }
    
    fun setPackageTypeFilter(packageType: String) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            packageType = packageType
            // Preserve packageState when switching type filters
        )
        // Apply filters in-memory (fast) instead of re-fetching from system
        applyFilters(newFilterState)
    }

    fun setPackageStateFilter(packageState: String?) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(packageState = packageState)
        // Apply filters in-memory (fast) instead of re-fetching from system
        applyFilters(newFilterState)
    }

    fun setProfileFilter(profileFilter: String) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            profileFilter = profileFilter
            // All other filters are preserved
        )
        // Apply filters in-memory (fast) instead of re-fetching from system
        applyFilters(newFilterState)
    }

    fun setPackageEnabled(packageName: String, userId: Int = 0, enabled: Boolean) {
        viewModelScope.launch {
            // Optimistically update UI first
            updatePackageInList(packageName, userId) { pkg ->
                pkg.copy(isEnabled = enabled)
            }

            // Then persist to system
            managePackageUseCase.setPackageEnabled(packageName, userId, enabled)
                .onSuccess {
                    // Success - optimistic update already applied, no need to reload
                }
                .onFailure { error ->
                    // Revert on failure by reloading from system
                    loadPackages(forceRefresh = true)
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    fun uninstallPackage(packageName: String, userId: Int = 0, appName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingData = true,
                isRenderingUI = false
            )

            managePackageUseCase.uninstallPackage(packageName, userId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        uninstallSuccess = "$appName uninstalled"
                    )
                    loadPackages(forceRefresh = true)
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

    /**
     * Uninstall multiple packages at once.
     * @param packages List of Pair(packageName, userId)
     */
    fun uninstallMultiplePackages(packages: List<Pair<String, Int>>): Job {
        return viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingData = true,
                isRenderingUI = false
            )

            // Call the batch uninstall use case
            managePackageUseCase.uninstallMultiplePackages(packages)
                .onSuccess { result ->
                    // Reload packages to reflect changes
                    loadPackages(forceRefresh = true)

                    // Update UI state with result
                    _uiState.value = _uiState.value.copy(
                        batchUninstallResult = result,
                        isLoadingData = false,
                        isRenderingUI = false
                    )
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

    fun clearBatchUninstallResult() {
        _uiState.value = _uiState.value.copy(batchUninstallResult = null)
    }

    fun reinstallPackage(packageName: String, userId: Int = 0, appName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingData = true,
                isRenderingUI = false
            )

            managePackageUseCase.reinstallPackage(packageName, userId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        reinstallSuccess = "$appName reinstalled"
                    )
                    loadPackages(forceRefresh = true)
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

    /**
     * Reinstall multiple packages at once.
     * @param packages List of Pair(packageName, userId)
     */
    fun reinstallMultiplePackages(packages: List<Pair<String, Int>>): Job {
        return viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingData = true,
                isRenderingUI = false
            )

            managePackageUseCase.reinstallMultiplePackages(packages)
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(
                        batchReinstallResult = result
                    )
                    loadPackages(forceRefresh = true)
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

    fun clearBatchReinstallResult() {
        _uiState.value = _uiState.value.copy(batchReinstallResult = null)
    }

    fun clearUninstallSuccess() {
        _uiState.value = _uiState.value.copy(uninstallSuccess = null)
    }

    fun clearReinstallSuccess() {
        _uiState.value = _uiState.value.copy(reinstallSuccess = null)
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

    private fun updatePackageInList(packageName: String, userId: Int = 0, transform: (Package) -> Package) {
        // Update both the displayed list and the cache
        val currentPackages = _uiState.value.packages
        val updatedPackages = currentPackages.map { pkg ->
            if (pkg.packageName == packageName && pkg.userId == userId) {
                transform(pkg)
            } else {
                pkg
            }
        }
        _uiState.value = _uiState.value.copy(packages = updatedPackages)

        // Also update the cache so filter changes reflect the update
        cachedPackages = cachedPackages.map { pkg ->
            if (pkg.packageName == packageName && pkg.userId == userId) {
                transform(pkg)
            } else {
                pkg
            }
        }
    }

    class Factory(
        private val getPackagesUseCase: GetPackagesUseCase,
        private val managePackageUseCase: ManagePackageUseCase,
        private val superuserBannerState: SuperuserBannerState,
        private val rootManager: RootManager,
        private val shizukuManager: ShizukuManager,
        private val packageDataChanged: SharedFlow<Unit>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PackagesViewModel::class.java)) {
                return PackagesViewModel(
                    getPackagesUseCase,
                    managePackageUseCase,
                    superuserBannerState,
                    rootManager,
                    shizukuManager,
                    packageDataChanged
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class PackageFilterState(
    val packageType: String = "All",
    val packageState: String? = null,
    val profileFilter: String = "All"  // "All", "Personal", "Work", "Clone"
)

data class PackagesUiState(
    val packages: List<Package> = emptyList(),
    val filterState: PackageFilterState = PackageFilterState(),
    val searchQuery: String = "",
    val isLoadingData: Boolean = true,
    val isRenderingUI: Boolean = false,
    val error: String? = null,
    val batchUninstallResult: UninstallBatchResult? = null,
    val batchReinstallResult: ReinstallBatchResult? = null,
    val uninstallSuccess: String? = null,
    val reinstallSuccess: String? = null
) {
    val isLoading: Boolean get() = isLoadingData || isRenderingUI
}
