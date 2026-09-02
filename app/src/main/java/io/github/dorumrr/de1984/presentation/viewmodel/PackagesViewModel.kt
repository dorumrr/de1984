package io.github.dorumrr.de1984.presentation.viewmodel

import io.github.dorumrr.de1984.utils.AppLogger
import android.net.Uri
import io.github.dorumrr.de1984.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class PackagesViewModel(
    application: Application,
    private val getPackagesUseCase: GetPackagesUseCase,
    private val managePackageUseCase: ManagePackageUseCase,
    private val superuserBannerState: SuperuserBannerState,
    val rootManager: RootManager,
    val shizukuManager: ShizukuManager,
    private val packageDataChanged: SharedFlow<Unit>
) : AndroidViewModel(application) {

    private val TAG = "PackagesViewModel"

    private val _uiState = MutableStateFlow(
        PackagesUiState(
            filterState = io.github.dorumrr.de1984.utils.FilterPrefs.loadPackages(application)
        )
    )
    val uiState: StateFlow<PackagesUiState> = _uiState.asStateFlow()

    private var pendingFilterState: PackageFilterState? = null

    private var loadJob: Job? = null

    private var cachedPackages: List<Package> = emptyList()



    val showRootBanner: StateFlow<Boolean>
        get() = superuserBannerState.showBanner

    fun dismissRootBanner() {
        superuserBannerState.hideBanner()
    }

    fun checkRootAccess() {
        viewModelScope.launch {
            shizukuManager.checkShizukuStatus()

            // If Shizuku is available but permission not granted, request it
            // BUT: Skip if user already denied to prevent prompt spam (Issue #68)
            if (shizukuManager.isShizukuAvailable() && !shizukuManager.hasShizukuPermission) {
                if (!shizukuManager.hasUserDeniedPermission) {
                    shizukuManager.requestShizukuPermission()
                }
            }

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
            .debounce(300L)
            .onEach {
                Log.d(TAG, "Package data changed, refreshing list")
                loadPackages(forceRefresh = true)
            }
            .launchIn(viewModelScope)
    }

    fun loadPackages(forceRefresh: Boolean = false) {
        loadJob?.cancel()

        val filterState = pendingFilterState ?: _uiState.value.filterState
        pendingFilterState = null

        if (cachedPackages.isNotEmpty() && !forceRefresh) {
            applyFilters(filterState)
            return
        }

        // Set loading state but keep existing packages visible to preserve scroll position
        // DiffUtil will handle smooth transition when new data arrives
        _uiState.value = _uiState.value.copy(
            isLoadingData = true,
            isRenderingUI = false,
            filterState = filterState
        )

        // A LOCAL, not a field. loadJob.cancel() unwinds asynchronously, so a shared counter let
        // the outgoing collection spend the incoming one's budget - the new load began with a
        // retry already gone, at exactly the 'user just granted root' moment it exists for.

        var retriesSinceSuccess = 0

        loadJob = getPackagesUseCase.invoke()
            // Reset on every good emission, so the budget below is per INCIDENT.
            //
            // retryWhen's own `attempt` counter is monotonic for the life of the collection, and
            // these collections live as long as the screen. Three unrelated transients an hour
            // apart - each of which recovered on its own - would have exhausted it and killed the
            // screen for good.
            .onEach { retriesSinceSuccess = 0 }
            // A scan can fail transiently on a cold start - root or Shizuku is not granted yet, or
            // the package service is still coming up. One quiet retry turns that into a short wait
            // instead of an empty screen the user has to force-stop out of. Bounded on purpose: a
            // device where the scan really cannot work must reach the message, not spin forever.
            .retryWhen { cause, _ ->
                // ONE retry, not two. Every retry re-enters onStart and costs a full serialized
                // rescan - measured in seconds on a device with hundreds of packages - and both
                // screens retry independently. Two each meant up to six full scans before either
                // said anything, spinner throughout. The second retry buys a second of extra grace
                // for a cold start and changes almost nothing the first did not.
                val retry = retriesSinceSuccess < 1 && cause !is CancellationException
                if (retry) {
                    retriesSinceSuccess++
                    AppLogger.w(TAG, "Package scan failed (attempt $retriesSinceSuccess), retrying: ${cause.message}")
                    delay(1000)
                }
                retry
            }
            .catch { error ->
                // A resource string, not error.message. That message is developer English written
                // for a log, and it was being shown to the user verbatim in every locale.
                AppLogger.e(TAG, "Package scan failed after retries", error)
                _uiState.value = _uiState.value.copy(
                    isLoadingData = false,
                    isRenderingUI = false,
                    scanFailed = cachedPackages.isEmpty(),
                    // Always announced, matching FirewallViewModel. Staying quiet over a full list
                    // was tried and was a false economy: the uninstall, reinstall and batch paths all
                    // force a reload precisely because the list they show is now wrong, and silence
                    // there left apps that had just been removed still sitting in the list.
                    error = getApplication<Application>().getString(R.string.error_package_scan_failed)
                )
            }
            .onEach { packages ->
                cachedPackages = packages
                // Read the filter LIVE, not the `filterState` captured when this collection started.
                // The packages flow is a shared replay flow and emits again long after that - the
                // firewall screen invalidating and rescanning is enough - so the captured value goes
                // stale the moment the user taps a chip, and this list was silently repainted with
                // the PREVIOUS filter while the chips showed the new one. FirewallViewModel already
                // corrects this; the mirror here did not.
                val liveFilter = _uiState.value.filterState
                val filteredPackages = filterPackages(packages, liveFilter)
                _uiState.value = _uiState.value.copy(
                    packages = filteredPackages,
                    isLoadingData = false,
                    isRenderingUI = true,
                    error = null,
                    scanFailed = false
                )
            }
            .launchIn(viewModelScope)
    }

    private fun applyFilters(filterState: PackageFilterState) {
        io.github.dorumrr.de1984.utils.FilterPrefs.savePackages(getApplication(), filterState)

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

    private fun filterPackages(packages: List<Package>, filterState: PackageFilterState): List<Package> {
        var result = packages

        result = when (filterState.packageType.lowercase()) {
            io.github.dorumrr.de1984.utils.Constants.Packages.TYPE_USER.lowercase() ->
                result.filter { it.type == io.github.dorumrr.de1984.domain.model.PackageType.USER }
            io.github.dorumrr.de1984.utils.Constants.Packages.TYPE_SYSTEM.lowercase() ->
                result.filter { it.type == io.github.dorumrr.de1984.domain.model.PackageType.SYSTEM }
            // Issue #96. Criticality, not PackageType - bloatware is a judgement the bundled
            // package_safety_levels.json makes, and it cuts across user and system apps. Anything
            // that file does not list is UNKNOWN and is left out rather than guessed at.
            io.github.dorumrr.de1984.utils.Constants.Packages.TYPE_BLOATWARE.lowercase() ->
                result.filter { it.criticality == io.github.dorumrr.de1984.domain.model.PackageCriticality.BLOATWARE }
            else -> result
        }

        // No widening. A profile filter that matches nothing shows an EMPTY list, which is the
        // truth - "no apps in this profile" - and the chip stays visible and selected so the user
        // can step off it.
        //
        // Widening was tried and was worse: it showed every personal app under a highlighted Work
        // chip, so a self-evidently empty result became a silently wrong one, with nothing on screen
        // saying the filter had been ignored. The two real defects were never the empty list. They
        // were that the saved filter got overwritten, and that the chip could vanish leaving no way
        // out - and both are fixed where they happen.
        result = when (filterState.profileFilter.lowercase()) {
            "personal" -> result.filter { !it.isWorkProfile && !it.isCloneProfile }
            "work" -> result.filter { it.isWorkProfile }
            "clone" -> result.filter { it.isCloneProfile }
            else -> result
        }

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
    
    /**
     * Writes the package names currently ON SCREEN to [uri] (issue #96).
     *
     * The list is passed in rather than read from state on purpose: the search box is applied by
     * the fragment, not by filterPackages, so `uiState.packages` is filter-only and would export
     * more than the user can see. The fragment hands over exactly what it gave the adapter.
     *
     * Same format as SettingsViewModel's uninstalled-apps export - a short comment header and one
     * package name per line - so a file exported here can be fed straight back into that importer.
     */
    fun exportVisiblePackages(uri: Uri, visible: List<Package>) {
        viewModelScope.launch {
            if (visible.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    error = getApplication<Application>().getString(R.string.packages_export_empty)
                )
                return@launch
            }
            // One line per NAME, not per row. The list holds a row per package PER PROFILE, so a
            // phone with a work profile lists com.android.egg twice - and a file of package names
            // that repeats itself tells the reader nothing and makes the importer do the work
            // twice. Order is kept, so the file still reads in the order that was on screen.
            val names = visible.map { it.packageName }.distinct()

            try {
                val content = buildString {
                    appendLine("# De1984 Packages Export")
                    appendLine("# Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("# Count: ${names.size}")
                    appendLine()
                    names.forEach { appendLine(it) }
                }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray())
                    } ?: throw java.io.IOException("Could not open the chosen file for writing")
                }
                AppLogger.d(TAG, "Exported ${names.size} package names from ${visible.size} rows")
                _uiState.value = _uiState.value.copy(
                    exportSuccess = getApplication<Application>()
                        .getString(R.string.packages_export_success, names.size)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Package export failed", e)
                _uiState.value = _uiState.value.copy(
                    error = getApplication<Application>()
                        .getString(R.string.packages_export_failed, e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    fun clearExportSuccess() {
        _uiState.value = _uiState.value.copy(exportSuccess = null)
    }

    fun setPackageTypeFilter(packageType: String) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            packageType = packageType
        )
        applyFilters(newFilterState)
    }

    fun setPackageStateFilter(packageState: String?) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(packageState = packageState)
        applyFilters(newFilterState)
    }

    fun setProfileFilter(profileFilter: String) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            profileFilter = profileFilter
        )
        applyFilters(newFilterState)
    }

    fun setPackageEnabled(packageName: String, userId: Int = 0, enabled: Boolean) {
        viewModelScope.launch {
            val snapshot = updatePackageInList(packageName, userId) { pkg ->
                pkg.copy(isEnabled = enabled)
            }

            managePackageUseCase.setPackageEnabled(packageName, userId, enabled)
                                .onFailure { error ->
                    restoreRow(snapshot)
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

    fun uninstallMultiplePackages(packages: List<Pair<String, Int>>): Job {
        return viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingData = true,
                isRenderingUI = false
            )

            managePackageUseCase.uninstallMultiplePackages(packages)
                .onSuccess { result ->
                    loadPackages(forceRefresh = true)

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
            managePackageUseCase.forceStopPackage(packageName)
                .onSuccess {
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

    /** Returns the row as it was, so the caller can undo exactly this change - see [restoreRow]. */
    private fun updatePackageInList(packageName: String, userId: Int = 0, transform: (Package) -> Package): Package? {
        val currentPackages = _uiState.value.packages
        val updatedPackages = currentPackages.map { pkg ->
            if (pkg.packageName == packageName && pkg.userId == userId) {
                transform(pkg)
            } else {
                pkg
            }
        }
        _uiState.value = _uiState.value.copy(packages = updatedPackages)

        val snapshot = cachedPackages.firstOrNull {
            it.packageName == packageName && it.userId == userId
        }

        cachedPackages = cachedPackages.map { pkg ->
            if (pkg.packageName == packageName && pkg.userId == userId) {
                transform(pkg)
            } else {
                pkg
            }
        }

        return snapshot
    }

    /**
     * Put back the row exactly as the optimistic update returned it, because this write failed.
     *
     * The undo travels WITH the write - see FirewallViewModel.restoreRow for why a shared map was
     * the wrong shape. Unlike the firewall screen, `setPackageEnabled` genuinely does need root or
     * Shizuku, so its rescan really would need the privilege that was just refused. That is exactly
     * why the undo must be local.
     */
    private fun restoreRow(snapshot: Package?) {
        if (snapshot == null) return
        cachedPackages = cachedPackages.map {
            if (it.packageName == snapshot.packageName && it.userId == snapshot.userId) snapshot else it
        }
        _uiState.value = _uiState.value.copy(
            packages = filterPackages(cachedPackages, _uiState.value.filterState)
        )
        // One scan settles any interleaving with another tap on the same row - a whole-row snapshot
        // cannot tell which field this write owned. loadPackages cancels the previous job, so
        // repeated taps collapse into one scan.
        loadPackages(forceRefresh = true)
    }

    class Factory(
        private val application: Application,
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
                    application,
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
    val profileFilter: String = "All"
)

data class PackagesUiState(
    val packages: List<Package> = emptyList(),
    val filterState: PackageFilterState = PackageFilterState(),
    val searchQuery: String = "",
    val isLoadingData: Boolean = true,
    val isRenderingUI: Boolean = false,
    val error: String? = null,
    /** See FirewallUiState.scanFailed - the last scan failed and left us with nothing. */
    val scanFailed: Boolean = false,
    val batchUninstallResult: UninstallBatchResult? = null,
    val batchReinstallResult: ReinstallBatchResult? = null,
    val uninstallSuccess: String? = null,
    val reinstallSuccess: String? = null,
    val exportSuccess: String? = null
) {
    val isLoading: Boolean get() = isLoadingData || isRenderingUI
}
