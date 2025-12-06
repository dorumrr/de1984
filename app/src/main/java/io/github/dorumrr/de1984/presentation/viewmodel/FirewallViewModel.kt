package io.github.dorumrr.de1984.presentation.viewmodel

import io.github.dorumrr.de1984.utils.AppLogger
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.firewall.FirewallManager
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.FirewallFilterState
import io.github.dorumrr.de1984.domain.model.PackageId
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.usecase.GetNetworkPackagesUseCase
import io.github.dorumrr.de1984.domain.usecase.ManageNetworkAccessUseCase
import io.github.dorumrr.de1984.data.firewall.FirewallManager.FirewallState

import io.github.dorumrr.de1984.ui.common.SuperuserBannerState
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class FirewallViewModel(
    application: Application,
    private val getNetworkPackagesUseCase: GetNetworkPackagesUseCase,
    private val manageNetworkAccessUseCase: ManageNetworkAccessUseCase,
    private val superuserBannerState: SuperuserBannerState,
    private val permissionManager: io.github.dorumrr.de1984.data.common.PermissionManager,
    private val firewallManager: FirewallManager,
    private val packageDataChanged: SharedFlow<Unit>
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

    // Performance optimization: cache all packages to avoid re-fetching from system on filter change
    private var cachedPackages: List<NetworkPackage> = emptyList()

    val showRootBanner: StateFlow<Boolean>
        get() = superuserBannerState.showBanner

    fun dismissRootBanner() {
        superuserBannerState.hideBanner()
    }

    // Access managers from application dependencies
    private val rootManager = (getApplication<Application>() as io.github.dorumrr.de1984.De1984Application).dependencies.rootManager
    private val shizukuManager = (getApplication<Application>() as io.github.dorumrr.de1984.De1984Application).dependencies.shizukuManager

    init {
        loadNetworkPackages()
        observeFirewallState()
        observePackageDataChanges()
        loadDefaultPolicy()
        // NOTE: Privilege monitoring for automatic backend switching is now handled
        // at the application level in FirewallManager, not in the ViewModel.
        // This ensures automatic switching works even when the app is not open.
    }

    /**
     * Observe package data changes from other screens (e.g., Package Control).
     * When packages are enabled/disabled or firewall rules change, refresh the list.
     * Debounced to prevent rapid successive refreshes.
     */
    @OptIn(FlowPreview::class)
    private fun observePackageDataChanges() {
        packageDataChanged
            .debounce(300L) // Debounce rapid changes to prevent excessive refreshes
            .onEach {
                AppLogger.d(TAG, "Package data changed, refreshing list")
                loadNetworkPackages(forceRefresh = true)
            }
            .launchIn(viewModelScope)
    }

    private fun loadDefaultPolicy() {
        val prefs = getApplication<Application>().getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val policy = prefs.getString(
            Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
            Constants.Settings.DEFAULT_FIREWALL_POLICY
        ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY

        AppLogger.d(TAG, "loadDefaultPolicy: Loaded policy from SharedPreferences: $policy")
        _uiState.value = _uiState.value.copy(defaultFirewallPolicy = policy)
        AppLogger.d(TAG, "loadDefaultPolicy: Updated uiState.defaultFirewallPolicy to: ${_uiState.value.defaultFirewallPolicy}")
    }


    private fun saveFirewallState(enabled: Boolean) {
        val prefs = getApplication<Application>().getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, enabled).apply()
    }

    private fun observeFirewallState() {
        firewallManager.firewallState
            .onEach { state ->
                // Show toggle as ON when firewall is Running OR Starting
                // This provides immediate visual feedback when user starts the firewall
                val enabled = state is FirewallState.Running || state is FirewallState.Starting
                _uiState.value = _uiState.value.copy(isFirewallEnabled = enabled)
            }
            .launchIn(viewModelScope)
    }

    fun refreshDefaultPolicy() {
        AppLogger.d(TAG, "refreshDefaultPolicy: Called - reloading policy and packages")
        loadDefaultPolicy()
        loadNetworkPackages(forceRefresh = true)
    }

    /**
     * Load network packages. Only fetches from system if cache is empty or forceRefresh is true.
     * @param forceRefresh If true, always fetch from system regardless of cache state
     */
    fun loadNetworkPackages(forceRefresh: Boolean = false) {
        // Cancel any previous loading operation
        loadJob?.cancel()

        // Use pending filter if available, otherwise use current filter
        val filterState = pendingFilterState ?: _uiState.value.filterState

        // Clear pending filter immediately after using it
        pendingFilterState = null

        // If we have cached data and not forcing refresh, just apply filters
        if (cachedPackages.isNotEmpty() && !forceRefresh) {
            applyFilters(filterState)
            return
        }

        // Clear packages AND update filter state immediately to prevent showing stale data
        _uiState.value = _uiState.value.copy(
            isLoadingData = true,
            isRenderingUI = false,
            packages = emptyList(),
            filterState = filterState  // Update filter immediately!
        )

        // Fetch ALL packages (unfiltered) and cache them
        loadJob = getNetworkPackagesUseCase.invoke()
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

                // Derive profile availability from loaded packages (avoids separate query)
                val hasWorkProfile = packages.any { it.isWorkProfile }
                val hasCloneProfile = packages.any { it.isCloneProfile }

                _uiState.value = _uiState.value.copy(
                    packages = filteredPackages,
                    isLoadingData = false,
                    isRenderingUI = true,
                    error = null,
                    hasWorkProfile = hasWorkProfile,
                    hasCloneProfile = hasCloneProfile
                )
            }
            .launchIn(viewModelScope)
    }

    /**
     * Apply filters to cached packages (fast, in-memory operation).
     * Does not fetch from system.
     */
    private fun applyFilters(filterState: FirewallFilterState) {
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
    private fun filterPackages(packages: List<NetworkPackage>, filterState: FirewallFilterState): List<NetworkPackage> {
        var result = packages

        // Apply type filter
        result = when (filterState.packageType.lowercase()) {
            Constants.Packages.TYPE_USER.lowercase() ->
                result.filter { it.type == PackageType.USER }
            Constants.Packages.TYPE_SYSTEM.lowercase() ->
                result.filter { it.type == PackageType.SYSTEM }
            else -> result // "all" or default
        }

        // Apply profile filter
        result = when (filterState.profileFilter.lowercase()) {
            "personal" -> result.filter { !it.isWorkProfile && !it.isCloneProfile }
            "work" -> result.filter { it.isWorkProfile }
            "clone" -> result.filter { it.isCloneProfile }
            else -> result // "all" or default
        }

        // Apply internet-only filter
        if (filterState.internetOnly) {
            result = result.filter { it.hasInternetPermission }
        }

        // Apply network state filter (allowed/blocked)
        if (filterState.networkState != null) {
            result = when (filterState.networkState.lowercase()) {
                "allowed" -> result.filter { !it.wifiBlocked && !it.mobileBlocked }
                "blocked" -> result.filter { it.wifiBlocked || it.mobileBlocked }
                else -> result
            }
        }

        return result
    }

    fun setPackageTypeFilter(packageType: String) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            packageType = packageType
            // Preserve networkState and internetOnly when switching type filters
        )
        // Apply filters in-memory (fast) instead of re-fetching from system
        applyFilters(newFilterState)
    }

    fun setNetworkStateFilter(networkState: String?) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            networkState = networkState
            // internetOnly is preserved when switching Allowed/Blocked
        )
        // Apply filters in-memory (fast) instead of re-fetching from system
        applyFilters(newFilterState)
    }

    fun setInternetOnlyFilter(internetOnly: Boolean) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            internetOnly = internetOnly
            // networkState and packageType are preserved
        )
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

    fun setWifiBlocking(packageName: String, userId: Int = 0, blocked: Boolean) {
        viewModelScope.launch {
            // Optimistically update UI first
            updatePackageInList(packageName, userId) { pkg ->
                AppLogger.d(TAG, "setWifiBlocking: BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                val updated = pkg.copy(wifiBlocked = blocked)
                AppLogger.d(TAG, "setWifiBlocking: AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                updated
            }

            // Then persist to database
            manageNetworkAccessUseCase.setWifiBlocking(packageName, userId, blocked)
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

    fun setMobileBlocking(packageName: String, userId: Int = 0, blocked: Boolean) {
        viewModelScope.launch {
            // If mobile is being blocked, also block roaming (roaming requires mobile)
            if (blocked) {
                // Optimistically update both mobile and roaming
                updatePackageInList(packageName, userId) { pkg ->
                    AppLogger.d(TAG, "setMobileBlocking(blocked=true): BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                    val updated = pkg.copy(mobileBlocked = true, roamingBlocked = true)
                    AppLogger.d(TAG, "setMobileBlocking(blocked=true): AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                    updated
                }

                // Persist with atomic batch update - only one database transaction, only one notification
                manageNetworkAccessUseCase.setMobileAndRoaming(packageName, userId, mobileBlocked = true, roamingBlocked = true)
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
                            error = getApplication<Application>().getString(
                                R.string.error_failed_to_block_mobile,
                                error.message ?: getApplication<Application>().getString(R.string.error_unknown)
                            )
                        )
                    }
            } else {
                // Mobile is being enabled - only update mobile, leave roaming as is
                updatePackageInList(packageName, userId) { pkg ->
                    AppLogger.d(TAG, "setMobileBlocking(blocked=false): BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                    val updated = pkg.copy(mobileBlocked = blocked)
                    AppLogger.d(TAG, "setMobileBlocking(blocked=false): AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                    updated
                }

                // Then persist to database
                manageNetworkAccessUseCase.setMobileBlocking(packageName, userId, blocked)
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

    fun setRoamingBlocking(packageName: String, userId: Int = 0, blocked: Boolean) {
        viewModelScope.launch {
            // Per user preference: "enabling Roaming block should auto-enable Mobile block"
            // and "roaming requires mobile" so unblocking roaming also unblocks mobile
            // Both blocking and unblocking affect mobile due to these dependencies
            updatePackageInList(packageName, userId) { pkg ->
                AppLogger.d(TAG, "setRoamingBlocking(blocked=$blocked): BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                val updated = if (blocked) {
                    // Blocking roaming also blocks mobile (per user preference)
                    pkg.copy(mobileBlocked = true, roamingBlocked = true)
                } else {
                    // Unblocking roaming also unblocks mobile (roaming requires mobile)
                    pkg.copy(mobileBlocked = false, roamingBlocked = false)
                }
                AppLogger.d(TAG, "setRoamingBlocking(blocked=$blocked): AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                updated
            }

            // Persist with atomic batch update - always update both mobile and roaming together
            manageNetworkAccessUseCase.setMobileAndRoaming(packageName, userId, mobileBlocked = blocked, roamingBlocked = blocked)
                .onSuccess {
                    // Success - optimistic update already applied, no need to reload
                }
                .onFailure { error ->
                    // Revert on failure by reloading
                    loadNetworkPackages()
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    val errorMsg = if (blocked) {
                        getApplication<Application>().getString(
                            R.string.error_failed_to_block_roaming,
                            error.message ?: getApplication<Application>().getString(R.string.error_unknown)
                        )
                    } else {
                        getApplication<Application>().getString(
                            R.string.error_failed_to_unblock_roaming,
                            error.message ?: getApplication<Application>().getString(R.string.error_unknown)
                        )
                    }
                    _uiState.value = _uiState.value.copy(error = errorMsg)
                }
        }
    }

    fun setBackgroundBlocking(packageName: String, userId: Int = 0, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "setBackgroundBlocking: packageName=$packageName, userId=$userId, blocked=$blocked")
            // Optimistically update UI first
            updatePackageInList(packageName, userId) { pkg ->
                AppLogger.d(TAG, "setBackgroundBlocking: BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                val updated = pkg.copy(backgroundBlocked = blocked)
                AppLogger.d(TAG, "setBackgroundBlocking: AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                updated
            }

            // Then persist to database
            manageNetworkAccessUseCase.setBackgroundBlocking(packageName, userId, blocked)
                .onSuccess {
                    AppLogger.d(TAG, "setBackgroundBlocking: SUCCESS - persisted to database")
                }
                .onFailure { error ->
                    AppLogger.e(TAG, "setBackgroundBlocking: FAILURE - ${error.message}")
                    // Revert on failure by reloading
                    loadNetworkPackages()
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    fun setLanBlocking(packageName: String, userId: Int = 0, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "setLanBlocking: packageName=$packageName, userId=$userId, blocked=$blocked")
            // Optimistically update UI first
            updatePackageInList(packageName, userId) { pkg ->
                AppLogger.d(TAG, "setLanBlocking: BEFORE copy - pkg.lanBlocked=${pkg.lanBlocked}")
                val updated = pkg.copy(lanBlocked = blocked)
                AppLogger.d(TAG, "setLanBlocking: AFTER copy - updated.lanBlocked=${updated.lanBlocked}")
                updated
            }

            // Then persist to database
            manageNetworkAccessUseCase.setLanBlocking(packageName, userId, blocked)
                .onSuccess {
                    AppLogger.d(TAG, "setLanBlocking: SUCCESS - persisted to database")
                }
                .onFailure { error ->
                    AppLogger.e(TAG, "setLanBlocking: FAILURE - ${error.message}")
                    // Revert on failure by reloading
                    loadNetworkPackages()
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    fun setAllNetworkBlocking(packageName: String, userId: Int = 0, blocked: Boolean) {
        val startTime = System.currentTimeMillis()
        AppLogger.d(TAG, "🔥 [TIMING] setAllNetworkBlocking START: pkg=$packageName, userId=$userId, blocked=$blocked, timestamp=$startTime")

        viewModelScope.launch {
            // Optimistically update all network types at once
            updatePackageInList(packageName, userId) { pkg ->
                pkg.copy(
                    wifiBlocked = blocked,
                    mobileBlocked = blocked,
                    roamingBlocked = blocked
                )
            }
            AppLogger.d(TAG, "🔥 [TIMING] UI optimistic update done: +${System.currentTimeMillis() - startTime}ms")

            // Persist with atomic batch update - only one database transaction, only one notification
            manageNetworkAccessUseCase.setAllNetworkBlocking(packageName, userId, blocked)
                .onSuccess {
                    AppLogger.d(TAG, "🔥 [TIMING] UseCase SUCCESS: +${System.currentTimeMillis() - startTime}ms - DB update complete")
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

    private fun updatePackageInList(packageName: String, userId: Int = 0, transform: (NetworkPackage) -> NetworkPackage) {
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
        // Compute minimal start plan (backend type + VPN permission requirement)
        // instead of duplicating backend selection logic here.
        val mode = firewallManager.getCurrentMode()

        // Synchronously compute the start plan so we can reliably decide
        // whether VPN permission is required before returning.
        val planResult = runCatching {
            // This is a quick, non-blocking call in practice (availability
            // checks are light), and we rely on the underlying dispatcher
            // configuration to keep things responsive.
            kotlinx.coroutines.runBlocking {
                firewallManager.computeStartPlan(mode)
            }
        }.fold(
            onSuccess = { result ->
                result.getOrElse { error ->
                    AppLogger.e(TAG, "startFirewall: Failed to compute start plan", error)
                    null
                }
            },
            onFailure = { throwable ->
                AppLogger.e(TAG, "startFirewall: Failed to compute start plan", throwable)
                null
            }
        )

        val needsVpnPermission = planResult?.let { plan ->
            plan.selectedBackendType == FirewallBackendType.VPN && plan.requiresVpnPermission
        } ?: false

        if (needsVpnPermission) {
            // Check if another VPN is active before calling VpnService.prepare()
            // This prevents killing user's third-party VPN (like Proton VPN) when:
            // 1. App is updated and restarted
            // 2. User manually starts firewall while another VPN is connected
            // 3. Any other scenario where startFirewall() is called with another VPN active
            if (firewallManager.isAnotherVpnActive()) {
                AppLogger.w(TAG, "startFirewall: Another VPN is active - cannot use VPN backend")
                AppLogger.w(TAG, "startFirewall: User needs to disconnect their VPN or De1984 needs privileged access (root/Shizuku)")

                // Don't call VpnService.prepare() - it would kill the other VPN
                // Return null to indicate we can't start (no permission dialog needed)
                // The firewall will remain stopped until:
                // - User disconnects their VPN, OR
                // - User grants root/Shizuku access (then iptables/CM backend can be used)
                return null
            }

            val prepareIntent = VpnService.prepare(getApplication())
            if (prepareIntent != null) {
                // Permission dialog must be shown by the Activity. We do NOT
                // start the firewall here; onVpnPermissionGranted() will be
                // called after the user responds.
                return prepareIntent
            }
        }

        // Either VPN permission is already granted, or a privileged backend
        // will be used. Start the firewall asynchronously.
        viewModelScope.launch {
            val result = firewallManager.startFirewall(mode)

            result.onSuccess { _ ->
                saveFirewallState(true)

                // Request battery optimization exemption after firewall starts successfully
                // This is important for both VPN and privileged backends to prevent services from being killed
                requestBatteryOptimizationIfNeeded()
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
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
            saveFirewallState(false)
        }
    }

    fun onVpnPermissionGranted() {
        // Start firewall after VPN permission is granted
        // Battery optimization will be requested automatically by startFirewall()
        startFirewall()
    }

    fun onVpnPermissionDenied() {
        saveFirewallState(false)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    // ========== BATCH OPERATIONS FOR MULTI-SELECT ==========

    /**
     * Block all network access for multiple packages at once.
     * Reports progress and results via UI state.
     */
    fun batchBlockPackages(packageIds: List<PackageId>) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchBlockPackages: Starting batch block for ${packageIds.size} packages")
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<String>()

            for ((index, packageId) in packageIds.withIndex()) {
                AppLogger.d(TAG, "🔥 batchBlockPackages: Processing ${index + 1}/${packageIds.size}: ${packageId.packageName} (userId=${packageId.userId})")

                // Update progress
                _uiState.value = _uiState.value.copy(
                    batchProgress = BatchProgress(
                        current = index + 1,
                        total = packageIds.size,
                        isBlocking = true
                    )
                )

                // Optimistically update UI
                updatePackageInList(packageId.packageName, packageId.userId) { pkg ->
                    pkg.copy(wifiBlocked = true, mobileBlocked = true, roamingBlocked = true)
                }

                // Persist
                manageNetworkAccessUseCase.setAllNetworkBlocking(packageId.packageName, packageId.userId, blocked = true)
                    .onSuccess {
                        succeeded.add(packageId.packageName)
                    }
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchBlockPackages: Failed to block ${packageId.packageName}: ${error.message}")
                        failed.add(packageId.packageName)
                        // Revert optimistic update
                        loadNetworkPackages()
                    }
            }

            // Clear progress and set result
            _uiState.value = _uiState.value.copy(
                batchProgress = null,
                batchBlockResult = BatchBlockResult(
                    succeeded = succeeded,
                    failed = failed,
                    wasBlocking = true
                )
            )
            AppLogger.d(TAG, "🔥 batchBlockPackages: Complete. Succeeded: ${succeeded.size}, Failed: ${failed.size}")
        }
    }

    /**
     * Allow all network access for multiple packages at once.
     * Reports progress and results via UI state.
     */
    fun batchAllowPackages(packageIds: List<PackageId>) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchAllowPackages: Starting batch allow for ${packageIds.size} packages")
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<String>()

            for ((index, packageId) in packageIds.withIndex()) {
                AppLogger.d(TAG, "🔥 batchAllowPackages: Processing ${index + 1}/${packageIds.size}: ${packageId.packageName} (userId=${packageId.userId})")

                // Update progress
                _uiState.value = _uiState.value.copy(
                    batchProgress = BatchProgress(
                        current = index + 1,
                        total = packageIds.size,
                        isBlocking = false
                    )
                )

                // Optimistically update UI
                updatePackageInList(packageId.packageName, packageId.userId) { pkg ->
                    pkg.copy(wifiBlocked = false, mobileBlocked = false, roamingBlocked = false)
                }

                // Persist
                manageNetworkAccessUseCase.setAllNetworkBlocking(packageId.packageName, packageId.userId, blocked = false)
                    .onSuccess {
                        succeeded.add(packageId.packageName)
                    }
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchAllowPackages: Failed to allow ${packageId.packageName}: ${error.message}")
                        failed.add(packageId.packageName)
                        // Revert optimistic update
                        loadNetworkPackages()
                    }
            }

            // Clear progress and set result
            _uiState.value = _uiState.value.copy(
                batchProgress = null,
                batchBlockResult = BatchBlockResult(
                    succeeded = succeeded,
                    failed = failed,
                    wasBlocking = false
                )
            )
            AppLogger.d(TAG, "🔥 batchAllowPackages: Complete. Succeeded: ${succeeded.size}, Failed: ${failed.size}")
        }
    }

    fun clearBatchBlockResult() {
        _uiState.value = _uiState.value.copy(batchBlockResult = null)
    }

    // ========== GRANULAR BATCH OPERATIONS FOR MULTI-SELECT RULES SHEET ==========

    /**
     * Set WiFi blocking for multiple packages at once.
     * @param packages List of Pair(packageName, userId)
     */
    fun batchSetWifiBlocking(packages: List<Pair<String, Int>>, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchSetWifiBlocking: Setting WiFi blocked=$blocked for ${packages.size} packages")
            for ((packageName, userId) in packages) {
                // Optimistically update UI
                updatePackageInList(packageName, userId) { pkg ->
                    pkg.copy(wifiBlocked = blocked)
                }
                // Persist
                manageNetworkAccessUseCase.setWifiBlocking(packageName, userId, blocked)
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchSetWifiBlocking: Failed for $packageName (user=$userId): ${error.message}")
                    }
            }
            AppLogger.d(TAG, "🔥 batchSetWifiBlocking: Complete")
        }
    }

    /**
     * Set Mobile blocking for multiple packages at once.
     * Note: This also handles roaming dependency (blocking mobile blocks roaming too).
     * @param packages List of Pair(packageName, userId)
     */
    fun batchSetMobileBlocking(packages: List<Pair<String, Int>>, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchSetMobileBlocking: Setting Mobile blocked=$blocked for ${packages.size} packages")
            for ((packageName, userId) in packages) {
                // Optimistically update UI - if blocking mobile, also block roaming
                updatePackageInList(packageName, userId) { pkg ->
                    if (blocked) {
                        pkg.copy(mobileBlocked = true, roamingBlocked = true)
                    } else {
                        pkg.copy(mobileBlocked = false)
                    }
                }
                // Persist - use atomic method for mobile+roaming when blocking
                if (blocked) {
                    manageNetworkAccessUseCase.setMobileAndRoaming(packageName, userId, mobileBlocked = true, roamingBlocked = true)
                        .onFailure { error ->
                            AppLogger.e(TAG, "🔥 batchSetMobileBlocking: Failed for $packageName (user=$userId): ${error.message}")
                        }
                } else {
                    manageNetworkAccessUseCase.setMobileBlocking(packageName, userId, blocked = false)
                        .onFailure { error ->
                            AppLogger.e(TAG, "🔥 batchSetMobileBlocking: Failed for $packageName (user=$userId): ${error.message}")
                        }
                }
            }
            AppLogger.d(TAG, "🔥 batchSetMobileBlocking: Complete")
        }
    }

    /**
     * Set Roaming blocking for multiple packages at once.
     * Note: Blocking roaming also blocks mobile (per user preference).
     * Note: Unblocking roaming also unblocks mobile (roaming requires mobile).
     * @param packages List of Pair(packageName, userId)
     */
    fun batchSetRoamingBlocking(packages: List<Pair<String, Int>>, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchSetRoamingBlocking: Setting Roaming blocked=$blocked for ${packages.size} packages")
            for ((packageName, userId) in packages) {
                // Optimistically update UI
                // Both blocking and unblocking affect mobile due to dependency
                updatePackageInList(packageName, userId) { pkg ->
                    if (blocked) {
                        // Blocking roaming also blocks mobile (per user preference)
                        pkg.copy(mobileBlocked = true, roamingBlocked = true)
                    } else {
                        // Unblocking roaming also unblocks mobile (roaming requires mobile)
                        pkg.copy(mobileBlocked = false, roamingBlocked = false)
                    }
                }
                // Persist - always use atomic method for mobile+roaming
                manageNetworkAccessUseCase.setMobileAndRoaming(packageName, userId, mobileBlocked = blocked, roamingBlocked = blocked)
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchSetRoamingBlocking: Failed for $packageName (user=$userId): ${error.message}")
                    }
            }
            AppLogger.d(TAG, "🔥 batchSetRoamingBlocking: Complete")
        }
    }

    /**
     * Set LAN blocking for multiple packages at once.
     * @param packages List of Pair(packageName, userId)
     */
    fun batchSetLanBlocking(packages: List<Pair<String, Int>>, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchSetLanBlocking: Setting LAN blocked=$blocked for ${packages.size} packages")
            for ((packageName, userId) in packages) {
                // Optimistically update UI
                updatePackageInList(packageName, userId) { pkg ->
                    pkg.copy(lanBlocked = blocked)
                }
                // Persist
                manageNetworkAccessUseCase.setLanBlocking(packageName, userId, blocked)
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchSetLanBlocking: Failed for $packageName (user=$userId): ${error.message}")
                    }
            }
            AppLogger.d(TAG, "🔥 batchSetLanBlocking: Complete")
        }
    }

    class Factory(
        private val application: Application,
        private val getNetworkPackagesUseCase: GetNetworkPackagesUseCase,
        private val manageNetworkAccessUseCase: ManageNetworkAccessUseCase,
        private val superuserBannerState: SuperuserBannerState,
        private val permissionManager: io.github.dorumrr.de1984.data.common.PermissionManager,
        private val firewallManager: FirewallManager,
        private val packageDataChanged: SharedFlow<Unit>
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
                    firewallManager,
                    packageDataChanged
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
    val shouldRequestBatteryOptimization: Boolean = false,
    val batchProgress: BatchProgress? = null,
    val batchBlockResult: BatchBlockResult? = null,
    val hasWorkProfile: Boolean = false,
    val hasCloneProfile: Boolean = false
) {
    val isLoading: Boolean get() = isLoadingData || isRenderingUI
}

/**
 * Progress tracking for batch firewall operations
 */
data class BatchProgress(
    val current: Int,
    val total: Int,
    val isBlocking: Boolean
)

/**
 * Result of a batch block/allow operation
 */
data class BatchBlockResult(
    val succeeded: List<String>,
    val failed: List<String>,
    val wasBlocking: Boolean
)
