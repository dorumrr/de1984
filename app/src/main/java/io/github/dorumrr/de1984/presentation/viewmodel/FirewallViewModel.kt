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
import io.github.dorumrr.de1984.domain.firewall.BlockingContext
import io.github.dorumrr.de1984.domain.model.UidRuleAggregate
import io.github.dorumrr.de1984.domain.firewall.asEnforcedBy
import io.github.dorumrr.de1984.domain.firewall.blockingRefused
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.FirewallFilterState
import io.github.dorumrr.de1984.domain.model.PackageId
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.domain.usecase.GetNetworkPackagesUseCase
import io.github.dorumrr.de1984.domain.usecase.ManageNetworkAccessUseCase
import io.github.dorumrr.de1984.data.firewall.FirewallManager.FirewallState

import io.github.dorumrr.de1984.domain.firewall.FirewallHealth
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FirewallViewModel(
    application: Application,
    private val getNetworkPackagesUseCase: GetNetworkPackagesUseCase,
    private val manageNetworkAccessUseCase: ManageNetworkAccessUseCase,
    private val superuserBannerState: SuperuserBannerState,
    private val permissionManager: io.github.dorumrr.de1984.data.common.PermissionManager,
    private val firewallManager: FirewallManager,
    private val firewallRepository: FirewallRepository,
    private val packageDataChanged: SharedFlow<Unit>
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FirewallViewModel"
    }

    private val _uiState = MutableStateFlow(
        FirewallUiState(
            filterState = io.github.dorumrr.de1984.utils.FilterPrefs.loadFirewall(application)
        )
    )
    val uiState: StateFlow<FirewallUiState> = _uiState.asStateFlow()

    private var pendingFilterState: FirewallFilterState? = null

    private var loadJob: Job? = null

    private var cachedPackages: List<NetworkPackage> = emptyList()

    /**
     * Per uid, the union of what its ENABLED rules block, read from the SAME query the backends
     * are handed - firewallRepository.getAllRules(), exactly as FirewallManager.applyRules does.
     *
     * Not derived from the package list. A rule outlives the package it was written for: uninstall
     * or disable an app and the row disappears while its rule stays in the table, and the UID
     * backends group by rulesByUid, so they would still see a rule where a package-derived map saw
     * none. That mismatch made a row claim "the Block All default does not reach this app" for a
     * uid the backend was in fact ruling on.
     */
    private var cachedUidRules: Map<Int, Map<String, UidRuleAggregate>> = emptyMap()


    val showRootBanner: StateFlow<Boolean>
        get() = superuserBannerState.showBanner

    val firewallHealth: StateFlow<FirewallHealth>
        get() = firewallManager.firewallHealth

    fun dismissRootBanner() {
        superuserBannerState.hideBanner()
    }

    private val rootManager = (getApplication<Application>() as io.github.dorumrr.de1984.De1984Application).dependencies.rootManager
    private val shizukuManager = (getApplication<Application>() as io.github.dorumrr.de1984.De1984Application).dependencies.shizukuManager

    init {
        loadNetworkPackages()
        observeRuleUids()
        observeFirewallState()
        observeActiveBackendType()
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
            .debounce(300L)
            .onEach {
                AppLogger.d(TAG, "Package data changed, refreshing list")
                // Invalidate, not just forceRefresh. The scan reads the rules and PAINTS every row
                // from them, so a rule change is a repaint. forceRefresh only bypasses this class's
                // cache, and within CACHE_TTL the data source replayed the pre-change rows - which
                // is how a default-policy switch could rewrite every rule and leave the list showing
                // the old painting.
                getNetworkPackagesUseCase.invalidateCache()
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

    /**
     * The Blocked/Allowed chips classify by what the running backend actually enforces, and that
     * changes with no package emission and no chip tap behind it - the firewall starts or stops, or
     * a privilege gain swaps the backend. Nothing else re-runs [filterPackages]: its only other
     * callers are the package flow and [applyFilters], and neither fires on a backend change.
     *
     * Without this, turning the firewall on while the Blocked chip is active leaves every platform
     * uid sitting in a list labelled "Blocked" while its row paints allowed.
     *
     * drop(1) skips the value the StateFlow replays on collect - the list was just built with it.
     * [applyFilters] is deliberately not reused: it re-persists the filter to preferences, and a
     * backend change is not the user choosing a filter.
     */
    /**
     * Keeps [cachedUidRules] current and republishes when it changes, because a rule written
     * from anywhere - this screen, a batch, the notification action, another profile - moves a row
     * out of the NO_RULE_IN_PROTECTED_UID or SIBLING_RULE_OVERRIDES_DEFAULT state.
     */
    private fun observeRuleUids() {
        firewallRepository.getAllRules()
            .onEach { rules ->
                // ENABLED rules only, grouped per uid, then the union of what they block - the
                // exact shape both uid backends enforce. IptablesFirewallBackend.applyRules builds
                // `rules.filter { it.enabled }.groupBy { it.uid }`, stops applying the Block All
                // default to a uid the moment that map has an entry for it, and takes
                // rulesForUid.any{} per network (plus a LAN pass and a screen-off pass). The union
                // carries every flag so unblockableReason and asEnforcedBy can paint a rule-less
                // sibling with the block that is REALLY on its uid, partial ones included.
                val aggregates = rules.filter { it.enabled }
                    .groupBy { it.uid }
                    // Kept per PACKAGE, not pre-unioned. The screen has to be able to take a
                    // union that EXCLUDES one package - see FirewallBackendType.enforcedVectorFor -
                    // and a union that has already been taken cannot be un-ORed.
                    .mapValues { (_, uidRules) ->
                        uidRules.associate { rule ->
                            rule.packageName to UidRuleAggregate(
                                wifiBlocked = rule.wifiBlocked,
                                mobileBlocked = rule.mobileBlocked,
                                // Blocking Mobile blocks Roaming with it. FirewallRule.isBlockedOn
                                // answers `blockWhenRoaming || mobileBlocked` for ROAMING, and
                                // GetNetworkPackagesUseCase patches every ROW the same way - so
                                // taking the raw column here made one rule mean two different
                                // things: its own row painted roaming blocked, a sibling's row
                                // painted it open over traffic iptables was dropping.
                                roamingBlocked = rule.blockWhenRoaming || rule.mobileBlocked,
                                lanBlocked = rule.lanBlocked,
                                backgroundBlocked = rule.blockWhenBackground,
                            )
                        }
                    }

                if (aggregates == cachedUidRules) return@onEach
                cachedUidRules = aggregates
                if (cachedPackages.isEmpty()) return@onEach
                val context = computeBlockingContext(cachedPackages)
                _uiState.value = _uiState.value.copy(
                    packages = filterPackages(cachedPackages, _uiState.value.filterState, context),
                    blockingContext = context
                )
            }
            .launchIn(viewModelScope)
    }

    private fun observeActiveBackendType() {
        firewallManager.activeBackendType
            .drop(1)
            .onEach {
                if (cachedPackages.isEmpty()) return@onEach
                val context = computeBlockingContext(cachedPackages)
                _uiState.value = _uiState.value.copy(
                    packages = filterPackages(cachedPackages, _uiState.value.filterState, context),
                    blockingContext = context
                )
            }
            .launchIn(viewModelScope)
    }

    /**
     * The pair of facts every backend consults, mirroring
     * IptablesFirewallBackend.applyRules (:409-423), its isUidExempted (:1266) and
     * NetworkPolicyManagerFirewallBackend.isUidExempted (:855).
     *
     * Costs nothing extra: isSystemCritical is a set lookup, isVpnApp is already on the model - put
     * there by the same hasVpnService check the backends run - and hasExplicitRule is `rule != null`
     * from the same query that built the row. Computed from the UNFILTERED list, because a
     * protected package the current chip hides still exempts its uid.
     */
    private fun computeBlockingContext(
        packages: List<NetworkPackage>,
    ): BlockingContext {
        // Taken from the ROWS, not from the preferences.
        //
        // Both settings decide how the scan PAINTS a row's blocking flags, and the painting happens
        // when the scan starts. Re-reading the preferences here looks equivalent and is not: a scan
        // already in flight when the user flips a setting paints with the old value and lands after
        // the new one is written, so the verdict would be drawn over rows that mean something else -
        // a critical app reading Allowed, with live switches and no banner, while the backend
        // enforced its rule. Asking the rows keeps the two in step whatever the timing, and an
        // empty list has no row to contradict.
        val sample = packages.firstOrNull()
        val prefs = getApplication<Application>()
            .getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = sample?.paintedAllowCritical ?: prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )
        val blockAllDefault = sample?.paintedBlockAllDefault ?: (prefs.getString(
            Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
            Constants.Settings.DEFAULT_FIREWALL_POLICY
        ) == Constants.Settings.POLICY_BLOCK_ALL)

        return BlockingContext(
            criticalOrVpnUids = packages
                .filter { it.isSystemCritical || it.isVpnApp }
                .map { it.uid }
                .toSet(),
            uidRules = cachedUidRules,
            allowCritical = allowCritical,
            blockAllDefault = blockAllDefault,
            ownUserId = android.os.Process.myUid() / 100000
        )
    }

    /**
     * Recompute after the "Allow Firewall Critical Packages" switch or the default policy moves.
     * Both live in SharedPreferences and are written from the Settings screen, which publishes
     * nothing this ViewModel collects, so the firewall screen calls this when it notices a change.
     */
    fun repaintForCriticalPackagesSetting() {
        // Nothing is published here, and no copy of the setting is kept.
        //
        // The rows are PAINTED with this setting at scan time - AndroidPackageDataSource reads the
        // same preference and paints a critical or VPN package all-allowed while still marking it
        // hasExplicitRule. So the ONE invariant that matters is that the context and the rows always
        // describe the same setting. Every context is therefore built from the preference at the
        // moment its rows arrive, and both move together or neither does.
        //
        // Two earlier attempts got this wrong in opposite ways. Publishing a context over the rows
        // already in hand put the two in direct contradiction: a critical package's own rule dropped
        // out of its uid's union, so its row read Allowed with live switches and no banner while the
        // backend enforced that rule. Caching the new value in a field to stop a stale preference
        // read then made that contradiction PERMANENT whenever the rescan did not happen - dropped
        // because another scan was already in flight, or abandoned because the scan threw.
        //
        // A rescan that does not happen now only means the change shows up late, on the next scan.
        // Late is survivable; a row that lies about what the firewall is doing is not.
        // Dispatchers.Main, NOT viewModelScope's default Main.immediate, and not yield().
        //
        // SettingsViewModel publishes to its state flow BEFORE calling apply(), and this screen
        // collects that flow on Main.immediate - so anything that runs inline starts one statement
        // before the preference it is meant to pick up is written. yield() looked like the guard for
        // that and is not: on Main.immediate isDispatchNeeded is false, so it returns without
        // suspending and the body still runs inside that same frame. A plain Main dispatch always
        // posts, so this runs after the settings write has landed.
        viewModelScope.launch(Dispatchers.Main) {
            getNetworkPackagesUseCase.invalidateCache()
            loadNetworkPackages(forceRefresh = true)
        }
    }

    /**
     * One package as the screen should show it, taken from the unfiltered cache.
     *
     * An open sheet must not lose its subject when a write moves the row out of the active chip:
     * the Blocked filter drops a row the moment its last block is lifted, and a sheet re-reading
     * the filtered list then found nothing and froze at its last painted values.
     */
    fun enforcedPackage(id: PackageId): NetworkPackage? =
        cachedPackages.find { it.id == id }
            ?.asEnforcedBy(firewallManager.activeBackendType.value, _uiState.value.blockingContext)

    /**
     * Which of [ids] the running backend cannot act on.
     *
     * Answers from the UNFILTERED list on purpose. The screen's own copy holds only the rows the
     * current chip and search leave visible, so asking it would treat every hidden row as unknown -
     * and a batch would then either drop rows it should keep or keep rows it should drop.
     */
    fun unreachableSelection(ids: Set<PackageId>): Set<PackageId> {
        val backend = firewallManager.activeBackendType.value
        val context = computeBlockingContext(cachedPackages)
        return cachedPackages
            .filter { it.id in ids && backend.blockingRefused(it, context) }
            .map { it.id }
            .toSet()
    }

    private fun observeFirewallState() {
        firewallManager.firewallState
            .onEach { state ->
                val enabled = state is FirewallState.Running || state is FirewallState.Starting
                _uiState.value = _uiState.value.copy(isFirewallEnabled = enabled)
            }
            .launchIn(viewModelScope)
    }

    fun refreshDefaultPolicy() {
        AppLogger.d(TAG, "refreshDefaultPolicy: Called - reloading policy and packages")
        // Dispatchers.Main for the same reason repaintForCriticalPackagesSetting uses it, and it
        // matters more here: loadDefaultPolicy reads the preference synchronously.
        viewModelScope.launch(Dispatchers.Main) {
            loadDefaultPolicy()
            // The scan paints every rule-less row from this policy, and forceRefresh alone only
            // bypasses THIS class's cache - the data source replayed the old-policy rows for
            // another second.
            getNetworkPackagesUseCase.invalidateCache()
            loadNetworkPackages(forceRefresh = true)
        }
    }

    fun loadNetworkPackages(forceRefresh: Boolean = false) {
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

        loadJob = getNetworkPackagesUseCase.invoke()
            .catch { error ->
                _uiState.value = _uiState.value.copy(
                    isLoadingData = false,
                    isRenderingUI = false,
                    error = error.message
                )
            }
            .onEach { packages ->
                cachedPackages = packages

                // Read the filter LIVE, not the `filterState` captured when this job was started.
                // The packages flow is a shared replay flow and emits again long after that - the
                // other screen collecting it is enough - so the captured value goes stale the
                // moment the user taps a chip. Using it here would re-filter, and worse write that
                // stale value back into the state, silently undoing the user's own selection.
                val activeFilter = _uiState.value.filterState

                val hasWorkProfile = packages.any { it.isWorkProfile }
                val hasCloneProfile = packages.any { it.isCloneProfile }

                // A restored profile filter can name a profile that no longer exists - the user
                // saved "Work", then removed the work profile. This screen HIDES the profile chips
                // when there is nothing to choose between (see rebuildFilterChips), so the list
                // would come up empty with no control on screen to undo it. Correct it here, where
                // the profiles are actually known.
                //
                // "Personal" needs no guard: it filters on `!isWorkProfile && !isCloneProfile`,
                // which on a device with no profiles keeps every package.
                val stranded =
                    (activeFilter.profileFilter.equals("work", true) && !hasWorkProfile) ||
                    (activeFilter.profileFilter.equals("clone", true) && !hasCloneProfile)

                val effectiveFilter = if (stranded) {
                    AppLogger.d(TAG, "Saved profile filter '${activeFilter.profileFilter}' matches no profile on this device - resetting to All")
                    activeFilter.copy(profileFilter = "All")
                } else {
                    activeFilter
                }

                val blockingContext = computeBlockingContext(packages)
                val filteredPackages = filterPackages(packages, effectiveFilter, blockingContext)

                _uiState.value = _uiState.value.copy(
                    packages = filteredPackages,
                    blockingContext = blockingContext,
                    isLoadingData = false,
                    isRenderingUI = true,
                    error = null,
                    hasWorkProfile = hasWorkProfile,
                    hasCloneProfile = hasCloneProfile
                )

                // Only after the list is drawn, and only when the guard actually changed something.
                // applyFilters both persists and republishes the filter, so calling it on the happy
                // path would be the very write-back this function must not do.
                if (stranded) {
                    applyFilters(effectiveFilter)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun applyFilters(filterState: FirewallFilterState) {
        io.github.dorumrr.de1984.utils.FilterPrefs.saveFirewall(getApplication(), filterState)

        _uiState.value = _uiState.value.copy(
            filterState = filterState
        )

        val context = computeBlockingContext(cachedPackages)
        val filteredPackages = filterPackages(cachedPackages, filterState, context)
        _uiState.value = _uiState.value.copy(
            packages = filteredPackages,
            blockingContext = context,
            isLoadingData = false,
            isRenderingUI = true,
            error = null
        )
    }

    private fun filterPackages(
        packages: List<NetworkPackage>,
        filterState: FirewallFilterState,
        blockingContext: BlockingContext,
    ): List<NetworkPackage> {
        // Masked HERE, once, so nothing downstream has to remember to. The rows, both sheets, the
        // multi-select sheet, the Blocked/Allowed chips, the counters and - the one that actually
        // bit - the quick toggle, which derives its write from what the row shows, all read the
        // enforced flags rather than the saved ones. cachedPackages keeps the raw truth.
        val backend = firewallManager.activeBackendType.value
        var result = packages.map { it.asEnforcedBy(backend, blockingContext) }

        result = when (filterState.packageType.lowercase()) {
            Constants.Packages.TYPE_USER.lowercase() ->
                result.filter { it.type == PackageType.USER }
            Constants.Packages.TYPE_SYSTEM.lowercase() ->
                result.filter { it.type == PackageType.SYSTEM }
            else -> result
        }

        result = when (filterState.profileFilter.lowercase()) {
            "personal" -> result.filter { !it.isWorkProfile && !it.isCloneProfile }
            "work" -> result.filter { it.isWorkProfile }
            "clone" -> result.filter { it.isCloneProfile }
            else -> result
        }

        if (filterState.internetOnly) {
            result = result.filter { it.hasInternetPermission }
        }

        if (filterState.networkState != null) {
            // The flags reaching here are already the enforced ones, so this classifies by what is
            // in force without repeating the test.
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
        )
        applyFilters(newFilterState)
    }

    fun setNetworkStateFilter(networkState: String?) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            networkState = networkState
        )
        applyFilters(newFilterState)
    }

    fun setInternetOnlyFilter(internetOnly: Boolean) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            internetOnly = internetOnly
        )
        applyFilters(newFilterState)
    }

    fun setProfileFilter(profileFilter: String) {
        val currentFilterState = _uiState.value.filterState
        val newFilterState = currentFilterState.copy(
            profileFilter = profileFilter
        )
        applyFilters(newFilterState)
    }

    fun setWifiBlocking(packageName: String, userId: Int = 0, blocked: Boolean) {
        viewModelScope.launch {
            updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = !blocked) { pkg ->
                AppLogger.d(TAG, "setWifiBlocking: BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                val updated = pkg.copy(wifiBlocked = blocked)
                AppLogger.d(TAG, "setWifiBlocking: AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                updated
            }

            manageNetworkAccessUseCase.setWifiBlocking(packageName, userId, blocked)
                .onSuccess {
                }
                .onFailure { error ->
                    revertOptimisticUpdate()
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    fun setMobileBlocking(packageName: String, userId: Int = 0, blocked: Boolean) {
        viewModelScope.launch {
            if (blocked) {
                updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = false) { pkg ->
                    AppLogger.d(TAG, "setMobileBlocking(blocked=true): BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                    val updated = pkg.copy(mobileBlocked = true, roamingBlocked = true)
                    AppLogger.d(TAG, "setMobileBlocking(blocked=true): AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                    updated
                }

                manageNetworkAccessUseCase.setMobileAndRoaming(packageName, userId, mobileBlocked = true, roamingBlocked = true)
                    .onSuccess {
                    }
                    .onFailure { error ->
                        revertOptimisticUpdate()
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
                updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = true) { pkg ->
                    AppLogger.d(TAG, "setMobileBlocking(blocked=false): BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                    val updated = pkg.copy(mobileBlocked = blocked)
                    AppLogger.d(TAG, "setMobileBlocking(blocked=false): AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                    updated
                }

                manageNetworkAccessUseCase.setMobileBlocking(packageName, userId, blocked)
                    .onSuccess {
                    }
                    .onFailure { error ->
                        revertOptimisticUpdate()
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
            updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = !blocked) { pkg ->
                AppLogger.d(TAG, "setRoamingBlocking(blocked=$blocked): BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                val updated = if (blocked) {
                    pkg.copy(mobileBlocked = true, roamingBlocked = true)
                } else {
                    pkg.copy(mobileBlocked = false, roamingBlocked = false)
                }
                AppLogger.d(TAG, "setRoamingBlocking(blocked=$blocked): AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                updated
            }

            manageNetworkAccessUseCase.setMobileAndRoaming(packageName, userId, mobileBlocked = blocked, roamingBlocked = blocked)
                .onSuccess {
                }
                .onFailure { error ->
                    revertOptimisticUpdate()
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
            // false in BOTH directions: the background insert writes the networks flat, never the
            // Block All default, because this toggle is only shown on a row that is not fully
            // blocked. See the comment on that insert in AndroidPackageDataSource.
            updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = false) { pkg ->
                AppLogger.d(TAG, "setBackgroundBlocking: BEFORE copy - pkg.backgroundBlocked=${pkg.backgroundBlocked}")
                val updated = pkg.copy(backgroundBlocked = blocked)
                AppLogger.d(TAG, "setBackgroundBlocking: AFTER copy - updated.backgroundBlocked=${updated.backgroundBlocked}")
                updated
            }

            manageNetworkAccessUseCase.setBackgroundBlocking(packageName, userId, blocked)
                .onSuccess {
                    AppLogger.d(TAG, "setBackgroundBlocking: SUCCESS - persisted to database")
                }
                .onFailure { error ->
                    AppLogger.e(TAG, "setBackgroundBlocking: FAILURE - ${error.message}")
                    revertOptimisticUpdate()
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
            updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = !blocked) { pkg ->
                AppLogger.d(TAG, "setLanBlocking: BEFORE copy - pkg.lanBlocked=${pkg.lanBlocked}")
                val updated = pkg.copy(lanBlocked = blocked)
                AppLogger.d(TAG, "setLanBlocking: AFTER copy - updated.lanBlocked=${updated.lanBlocked}")
                updated
            }

            manageNetworkAccessUseCase.setLanBlocking(packageName, userId, blocked)
                .onSuccess {
                    AppLogger.d(TAG, "setLanBlocking: SUCCESS - persisted to database")
                }
                .onFailure { error ->
                    AppLogger.e(TAG, "setLanBlocking: FAILURE - ${error.message}")
                    revertOptimisticUpdate()
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
            updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = !blocked) { pkg ->
                pkg.copy(
                    wifiBlocked = blocked,
                    mobileBlocked = blocked,
                    roamingBlocked = blocked
                )
            }
            AppLogger.d(TAG, "🔥 [TIMING] UI optimistic update done: +${System.currentTimeMillis() - startTime}ms")

            manageNetworkAccessUseCase.setAllNetworkBlocking(packageName, userId, blocked)
                .onSuccess {
                    AppLogger.d(TAG, "🔥 [TIMING] UseCase SUCCESS: +${System.currentTimeMillis() - startTime}ms - DB update complete")
                }
                .onFailure { error ->
                    revertOptimisticUpdate()
                    if (superuserBannerState.shouldShowBannerForError(error)) {
                        superuserBannerState.showSuperuserRequiredBanner()
                    }
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
        }
    }

    private fun updatePackageInList(
        packageName: String,
        userId: Int = 0,
        /**
         * Whether the FIRST rule this tap creates carries the Block All default onto the networks
         * the tap does not name. An UNBLOCK tap does, a BLOCK tap does not - see the six first-rule
         * inserts in AndroidPackageDataSource, which this mirrors. Ignored once the package has a
         * rule of its own, because the row is then painted from that rule.
         *
         * Keep the two in step. When they drifted, one tap on WiFi painted a block on all four
         * networks until the rescan landed, which is the very lie this state exists to remove.
         */
        firstRuleKeepsBlockAllDefault: Boolean = false,
        transform: (NetworkPackage) -> NetworkPackage,
    ) {
        // Every caller of this is writing a rule, so the package now HAS one. Recording that is
        // what lifts the NO_RULE_IN_PROTECTED_UID state: without it the row kept reporting "the
        // Block All default does not reach this app" after the user had just given it a rule, and
        // the switch they were told to turn on snapped straight back.
        fun applied(pkg: NetworkPackage): NetworkPackage {
            // A package with no rule yet is painted from the Block All default, not from anything
            // written down (AndroidPackageDataSource BlockingState). The rule about to be inserted
            // never carries that default onto LAN or background, and carries it onto the other
            // networks only when the tap is an unblock.
            val base = if (pkg.hasExplicitRule) pkg else pkg.copy(
                wifiBlocked = pkg.wifiBlocked && firstRuleKeepsBlockAllDefault,
                mobileBlocked = pkg.mobileBlocked && firstRuleKeepsBlockAllDefault,
                roamingBlocked = pkg.roamingBlocked && firstRuleKeepsBlockAllDefault,
                lanBlocked = false,
                backgroundBlocked = false,
            )
            val updated = transform(base).copy(hasExplicitRule = true)
            // Derived, not carried over: once a rule exists the read side computes this from the
            // rule (AndroidPackageDataSource:190), so deriving it here stops the optimistic row and
            // the row the repository emits a moment later from disagreeing about the Blocked chip.
            return updated.copy(isNetworkBlocked = updated.wifiBlocked || updated.mobileBlocked)
        }

        cachedPackages = cachedPackages.map { pkg ->
            if (pkg.packageName == packageName && pkg.userId == userId) applied(pkg) else pkg
        }

        // Republished from the raw cache so the masking re-evaluates. The uid rule map itself is
        // one beat behind - it comes from the repository flow, which has not emitted yet - which is
        // why unblockableReason answers from hasExplicitRule before it ever consults the map.
        val context = computeBlockingContext(cachedPackages)
        _uiState.value = _uiState.value.copy(
            packages = filterPackages(cachedPackages, _uiState.value.filterState, context),
            blockingContext = context
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun setUIReady() {
        _uiState.value = _uiState.value.copy(isRenderingUI = false)
    }

    /**
     * Undo an optimistic row update whose write failed.
     *
     * Must force a refresh. [updatePackageInList] writes the optimistic value into [cachedPackages]
     * and republishes from it, and a plain [loadNetworkPackages] returns early on a non-empty cache -
     * so the "revert" re-filtered the value it was supposed to undo and the row kept showing a block
     * that was never applied.
     */
    private fun revertOptimisticUpdate() {
        loadNetworkPackages(forceRefresh = true)
    }

    fun startFirewall(): Intent? {
        val mode = firewallManager.getCurrentMode()

        val planResult = runCatching {
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
        if (permissionManager.isBatteryOptimizationDisabled()) {
            return
        }

        _uiState.value = _uiState.value.copy(
            shouldRequestBatteryOptimization = true
        )
    }

    fun clearBatteryOptimizationRequest() {
        _uiState.value = _uiState.value.copy(
            shouldRequestBatteryOptimization = false
        )
    }

    private var stopInFlight = false

    fun stopFirewall() {
        if (stopInFlight) {
            AppLogger.d(TAG, "stopFirewall ignored - a stop is already running")
            return
        }
        stopInFlight = true
        viewModelScope.launch {
            saveFirewallState(false)

            firewallManager.stopFirewall().onFailure { error ->
                // The preference deliberately stays false. It records what the user wants, and they
                // asked for the firewall off; flipping it back would make boot restore start the
                // firewall again on the next reboot. What the teardown failure needs is to be
                // visible, and FirewallManager publishes it as FirewallHealth.StopFailed, which the
                // banner renders with a retry button.
                AppLogger.e(TAG, "stopFirewall failed - rules may still be enforced: ${error.message}", error)
            }

            stopInFlight = false
        }
    }

    fun onVpnPermissionGranted() {
        startFirewall()
    }

    fun onVpnPermissionDenied() {
        saveFirewallState(false)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun batchBlockPackages(packageIds: List<PackageId>) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchBlockPackages: Starting batch block for ${packageIds.size} packages")
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<String>()

            for ((index, packageId) in packageIds.withIndex()) {
                AppLogger.d(TAG, "🔥 batchBlockPackages: Processing ${index + 1}/${packageIds.size}: ${packageId.packageName} (userId=${packageId.userId})")

                _uiState.value = _uiState.value.copy(
                    batchProgress = BatchProgress(
                        current = index + 1,
                        total = packageIds.size,
                        isBlocking = true
                    )
                )

                // setNetworkAccess names all four networks itself, so nothing is left to inherit.
                updatePackageInList(packageId.packageName, packageId.userId, firstRuleKeepsBlockAllDefault = false) { pkg ->
                    pkg.copy(wifiBlocked = true, mobileBlocked = true, roamingBlocked = true, lanBlocked = true)
                }

                // Persist. setNetworkAccess, not setAllNetworkBlocking: the button says "Block All
                // Networks", and "all" was settled as WiFi + Mobile + Roaming + LAN.
                // setAllNetworkBlocking is the narrower "Internet Access" control and leaves LAN.
                //
                // The LAN row is only shown on the iptables backend, so on the other three this
                // writes a lanBlocked the user cannot see. It is recoverable - "Allow All Networks"
                // clears it through the same path - and those backends ignore lanBlocked entirely.
                // Hiding rather than disabling unenforceable controls is a settled decision that is
                // not implemented yet; see PLAN.md, product decision 4.
                manageNetworkAccessUseCase.setNetworkAccess(packageId.packageName, packageId.userId, allowed = false)
                    .onSuccess {
                        succeeded.add(packageId.packageName)
                    }
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchBlockPackages: Failed to block ${packageId.packageName}: ${error.message}")
                        failed.add(packageId.packageName)
                        revertOptimisticUpdate()
                    }
            }

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

    fun batchAllowPackages(packageIds: List<PackageId>) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchAllowPackages: Starting batch allow for ${packageIds.size} packages")
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<String>()

            for ((index, packageId) in packageIds.withIndex()) {
                AppLogger.d(TAG, "🔥 batchAllowPackages: Processing ${index + 1}/${packageIds.size}: ${packageId.packageName} (userId=${packageId.userId})")

                _uiState.value = _uiState.value.copy(
                    batchProgress = BatchProgress(
                        current = index + 1,
                        total = packageIds.size,
                        isBlocking = false
                    )
                )

                // setNetworkAccess names all four networks itself, so nothing is left to inherit.
                updatePackageInList(packageId.packageName, packageId.userId, firstRuleKeepsBlockAllDefault = false) { pkg ->
                    pkg.copy(wifiBlocked = false, mobileBlocked = false, roamingBlocked = false, lanBlocked = false)
                }

                // Persist
                // Mirrors batchBlockPackages: "Allow All Networks" must clear LAN too.
                manageNetworkAccessUseCase.setNetworkAccess(packageId.packageName, packageId.userId, allowed = true)
                    .onSuccess {
                        succeeded.add(packageId.packageName)
                    }
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchAllowPackages: Failed to allow ${packageId.packageName}: ${error.message}")
                        failed.add(packageId.packageName)
                        revertOptimisticUpdate()
                    }
            }

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

    /**
     * The multi-select equivalent of [setAllNetworkBlocking], for backends that report
     * `supportsGranularControl() == false`. They have one switch per app, so the sheet shows one
     * "Internet Access" toggle instead of three. See issue #72.
     */
    fun batchSetAllNetworkBlocking(packages: List<Pair<String, Int>>, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchSetAllNetworkBlocking: Setting all networks blocked=$blocked for ${packages.size} packages")
            for ((packageName, userId) in packages) {
                // See batchSetWifiBlocking. All three must already agree before this is a no-op,
                // the state must be the row's OWN - its rule, or a default no rule has touched -
                // and a row we cannot find is written, not skipped.
                val current = enforcedPackage(PackageId(packageName, userId))
                if (current != null &&
                    (current.hasExplicitRule || current.uid !in cachedUidRules) &&
                    current.ownVector.wifiBlocked == blocked &&
                    current.ownVector.mobileBlocked == blocked && current.ownVector.roamingBlocked == blocked
                ) continue
                updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = !blocked) { pkg ->
                    pkg.copy(
                        wifiBlocked = blocked,
                        mobileBlocked = blocked,
                        roamingBlocked = blocked
                    )
                }
                manageNetworkAccessUseCase.setAllNetworkBlocking(packageName, userId, blocked)
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchSetAllNetworkBlocking: Failed for $packageName (user=$userId): ${error.message}")
                    }
            }
            AppLogger.d(TAG, "🔥 batchSetAllNetworkBlocking: Complete")
        }
    }

    fun batchSetWifiBlocking(packages: List<Pair<String, Int>>, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchSetWifiBlocking: Setting WiFi blocked=$blocked for ${packages.size} packages")
            for ((packageName, userId) in packages) {
                // A batch reaches rows that are ALREADY in the requested state - the sheet renders a
                // mixed selection as unchecked, so one tap sends "block" to rows that are blocked
                // already. Writing a first rule for those replaces the Block All default with an
                // explicit rule, and the uid backends switch to rule-based the moment any rule
                // exists - silently ALLOWING the networks the new rule does not name. Skip them -
                // but only rows whose state is their OWN: their rule, or a default no rule has
                // touched. A rule-less row in a ruled uid displays its NEIGHBOURS' union
                // (SIBLING_RULE_OVERRIDES_DEFAULT), and skipping on that borrowed state swallowed
                // the user's explicit ask whenever a same-uid sibling earlier in the batch got its
                // rule written first. A row we cannot find is written, never skipped.
                //
                // ownVector, not the row's flags: a row with a rule of its own is masked too once a
                // neighbour blocks more than it does, and comparing that borrowed value skipped the
                // user's explicit ask on exactly the rows this masking exists to describe.
                val current = enforcedPackage(PackageId(packageName, userId))
                if (current != null &&
                    (current.hasExplicitRule || current.uid !in cachedUidRules) &&
                    current.ownVector.wifiBlocked == blocked
                ) continue
                updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = !blocked) { pkg ->
                    pkg.copy(wifiBlocked = blocked)
                }
                manageNetworkAccessUseCase.setWifiBlocking(packageName, userId, blocked)
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchSetWifiBlocking: Failed for $packageName (user=$userId): ${error.message}")
                    }
            }
            AppLogger.d(TAG, "🔥 batchSetWifiBlocking: Complete")
        }
    }

    fun batchSetMobileBlocking(packages: List<Pair<String, Int>>, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchSetMobileBlocking: Setting Mobile blocked=$blocked for ${packages.size} packages")
            for ((packageName, userId) in packages) {
                // Same skip rule as batchSetWifiBlocking: only a row whose state is its own - its
                // rule, or a default no rule has touched - and never on missing information.
                val current = enforcedPackage(PackageId(packageName, userId))
                if (current != null &&
                    (current.hasExplicitRule || current.uid !in cachedUidRules) &&
                    current.ownVector.mobileBlocked == blocked
                ) continue
                updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = !blocked) { pkg ->
                    if (blocked) {
                        pkg.copy(mobileBlocked = true, roamingBlocked = true)
                    } else {
                        pkg.copy(mobileBlocked = false)
                    }
                }
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

    fun batchSetRoamingBlocking(packages: List<Pair<String, Int>>, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchSetRoamingBlocking: Setting Roaming blocked=$blocked for ${packages.size} packages")
            for ((packageName, userId) in packages) {
                // Same skip rule as batchSetWifiBlocking: only a row whose state is its own - its
                // rule, or a default no rule has touched - and never on missing information.
                val current = enforcedPackage(PackageId(packageName, userId))
                if (current != null &&
                    (current.hasExplicitRule || current.uid !in cachedUidRules) &&
                    current.ownVector.roamingBlocked == blocked
                ) continue
                updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = !blocked) { pkg ->
                    if (blocked) {
                        pkg.copy(mobileBlocked = true, roamingBlocked = true)
                    } else {
                        pkg.copy(mobileBlocked = false, roamingBlocked = false)
                    }
                }
                manageNetworkAccessUseCase.setMobileAndRoaming(packageName, userId, mobileBlocked = blocked, roamingBlocked = blocked)
                    .onFailure { error ->
                        AppLogger.e(TAG, "🔥 batchSetRoamingBlocking: Failed for $packageName (user=$userId): ${error.message}")
                    }
            }
            AppLogger.d(TAG, "🔥 batchSetRoamingBlocking: Complete")
        }
    }

    fun batchSetLanBlocking(packages: List<Pair<String, Int>>, blocked: Boolean) {
        viewModelScope.launch {
            AppLogger.d(TAG, "🔥 batchSetLanBlocking: Setting LAN blocked=$blocked for ${packages.size} packages")
            for ((packageName, userId) in packages) {
                // Same skip rule as batchSetWifiBlocking: only a row whose state is its own - its
                // rule, or a default no rule has touched - and never on missing information.
                val current = enforcedPackage(PackageId(packageName, userId))
                if (current != null &&
                    (current.hasExplicitRule || current.uid !in cachedUidRules) &&
                    current.ownVector.lanBlocked == blocked
                ) continue
                updatePackageInList(packageName, userId, firstRuleKeepsBlockAllDefault = !blocked) { pkg ->
                    pkg.copy(lanBlocked = blocked)
                }
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
        private val firewallRepository: FirewallRepository,
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
                    firewallRepository,
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
    val hasCloneProfile: Boolean = false,

    /**
     * What the running backend will consult before deciding whether a rule is worth sending.
     * See FirewallViewModel.computeBlockingContext.
     */
    val blockingContext: BlockingContext = BlockingContext()
) {
    val isLoading: Boolean get() = isLoadingData || isRenderingUI
}

data class BatchProgress(
    val current: Int,
    val total: Int,
    val isBlocking: Boolean
)

data class BatchBlockResult(
    val succeeded: List<String>,
    val failed: List<String>,
    val wasBlocking: Boolean
)
