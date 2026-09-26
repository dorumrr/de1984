package io.github.dorumrr.de1984.ui.firewall

import io.github.dorumrr.de1984.utils.setPackageCount
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.telephony.TelephonyManager
import io.github.dorumrr.de1984.utils.AppLogger
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.TextView
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.BottomSheetFirewallMultiselectBinding
import io.github.dorumrr.de1984.databinding.BottomSheetPackageActionGranularBinding
import io.github.dorumrr.de1984.databinding.BottomSheetPackageActionSimpleBinding
import io.github.dorumrr.de1984.databinding.FragmentFirewallBinding
import io.github.dorumrr.de1984.databinding.NetworkTypeToggleBinding
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.blockingRefused
import io.github.dorumrr.de1984.domain.firewall.UnblockableReason
import io.github.dorumrr.de1984.domain.firewall.unblockableReason
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.PackageId
import io.github.dorumrr.de1984.presentation.viewmodel.FirewallViewModel
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.ui.common.FilterChipsHelper
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.copyToClipboard
import io.github.dorumrr.de1984.utils.openAppSettings
import io.github.dorumrr.de1984.utils.setOnClickListenerDebounced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirewallFragmentViews : BaseFragment<FragmentFirewallBinding>() {

    private val TAG = "FirewallFragmentViews"

    private val viewModel: FirewallViewModel by activityViewModels {
        val app = requireActivity().application as De1984Application
        FirewallViewModel.Factory(
            app,
            app.dependencies.provideGetNetworkPackagesUseCase(),
            app.dependencies.provideManageNetworkAccessUseCase(),
            app.dependencies.superuserBannerState,
            app.dependencies.permissionManager,
            app.dependencies.firewallManager,
            app.dependencies.firewallRepository,
            app.dependencies.packageDataChanged
        )
    }

    private val settingsViewModel: SettingsViewModel by activityViewModels {
        val app = requireActivity().application as De1984Application
        SettingsViewModel.Factory(
            requireContext(),
            app.dependencies.permissionManager,
            app.dependencies.rootManager,
            app.dependencies.shizukuManager,
            app.dependencies.firewallManager,
            app.dependencies.firewallRepository,
            app.dependencies.captivePortalManager,
            app.dependencies.bootProtectionManager,
            app.dependencies.provideSmartPolicySwitchUseCase(),
            app.dependencies.packageRepository
        )
    }

    private lateinit var adapter: NetworkPackageAdapter
    private var currentTypeFilter: String? = null
    private var currentStateFilter: String? = null
    private var currentPermissionFilter: Boolean = false
    private var currentProfileFilter: String? = null

    private var lastHasWorkProfile: Boolean? = null
    private var lastHasCloneProfile: Boolean? = null

    private var previousObservedPolicy: String? = null

    /**
     * Last `showAppIcons` value the adapter was built for.
     *
     * The settings flow emits on EVERY settings change, and the adapter used to be rebuilt each
     * time - which drops the RecyclerView back to the top and reloads every visible icon from disk.
     * Only this one setting changes what the adapter is, so only this one should rebuild it.
     */
    private var previousObservedShowIcons: Boolean? = null

    /**
     * Last `allowCriticalPackageFirewall` value the rows were bound for.
     *
     * The adapter caches this flag and reads it only at bind time, to decide whether a
     * system-critical or VPN row is dimmed and whether its quick toggles respond. Before the
     * early-return below existed, the unconditional adapter rebuild happened to repaint those rows.
     * Nothing else does - refreshSettings() updates the cache with no notify, and updateUI bails out
     * when the package objects have not changed. So this has to be tracked explicitly.
     */
    private var previousObservedAllowCritical: Boolean? = null
    private var lastSubmittedPackages: List<NetworkPackage> = emptyList()

    private var currentDialog: BottomSheetDialog? = null

    /**
     * Dismiss anything still on screen before this view goes away.
     *
     * These dialogs are plain Dialog/BottomSheetDialog held in fields, not DialogFragments, so
     * nothing dismisses them for us. Without this the window leaks - `android.view.WindowLeaked` -
     * and worse, a long batch operation carries on behind a progress box the user can no longer
     * see, because the recreated fragment's field is null and its own dismiss is a no-op.
     *
     * Reachable on every activity rebuild: a language change (this app has a language switcher) or
     * a dark-mode change. Rotation no longer rebuilds, but those two still do.
     *
     * Wrapped: dismissing a dialog whose window has already gone throws, and there is nothing to do
     * about it here beyond not crashing on the way out.
     */
    override fun onDestroyView() {
        runCatching { currentDialog?.dismiss() }
        currentDialog = null
        super.onDestroyView()
    }

    private var dialogOpenTimestamp: Long = 0
    private var pendingDialogPackageId: PackageId? = null

    /** A selection read back from savedInstanceState, waiting for the adapter to settle. */
    private var pendingRestoredSelection: Set<PackageId>? = null

    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<PackageId>()
    private var backPressedCallback: OnBackPressedCallback? = null

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentFirewallBinding.inflate(inflater, container, false)

    override fun scrollToTop() {
        _binding?.packagesRecyclerView?.scrollToPosition(0)
    }

    private fun scrollToPackage(packageName: String) {
        _binding?.let { binding ->
            binding.packagesRecyclerView.post {
                val displayedPackages = viewModel.uiState.value.packages
                val index = displayedPackages.indexOfFirst { it.packageName == packageName }

                if (index >= 0) {
                    (binding.packagesRecyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 100)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.loadingState.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.packagesRecyclerView.visibility = View.GONE

        setupRecyclerView()
        setupFilterChips()
        setupSearchBox()
        setupSelectionToolbar()
        setupBackPressHandler()

        // Sync search query with EditText after restoration
        // Fix: EditText state is restored by Android before TextWatcher is attached,
        // so TextWatcher doesn't fire for restored text. Manually sync ViewModel.
        val currentSearchText = binding.searchInput.text?.toString() ?: ""
        if (currentSearchText.isNotEmpty()) {
            viewModel.setSearchQuery(currentSearchText)
            binding.searchLayout.isEndIconVisible = true
        }

        observeUiState()
        observeSettingsState()
        observeActiveBackend()
    
        // Last, so enterSelectionMode() finds a live adapter and toolbar.
        restoreSelection(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            adapter.refreshSettings(requireContext())
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        AppLogger.d(TAG, "onHiddenChanged: hidden=$hidden")

        if (!hidden) {
            AppLogger.d(TAG, "onHiddenChanged: Fragment became visible, checking for policy changes")
            val currentPolicy = settingsViewModel.uiState.value.defaultFirewallPolicy
            AppLogger.d(TAG, "onHiddenChanged: previousObservedPolicy=$previousObservedPolicy, currentPolicy=$currentPolicy")

            if (previousObservedPolicy != null && previousObservedPolicy != currentPolicy) {
                AppLogger.d(TAG, "onHiddenChanged: Policy changed while hidden! Refreshing...")
                previousObservedPolicy = currentPolicy
                viewModel.refreshDefaultPolicy()
            } else {
                AppLogger.d(TAG, "onHiddenChanged: No policy change detected")
            }

            if (::adapter.isInitialized) {
                adapter.refreshSettings(requireContext())
            }
        }
    }

    private fun setupRecyclerView() {
        // Build with the REAL setting, not a hardcoded true, and record what we built for.
        //
        // Issue #73. observeSettingsState computes iconsChanged as
        // `previousObservedShowIcons != settingsState.showAppIcons`. Left null, that is true on the
        // very first emission every single time, so the guard below it never fired on a fresh
        // fragment and the adapter was rebuilt and REASSIGNED to the RecyclerView at :545.
        // Reassigning an adapter throws away the layout manager's pending scroll state, which is the
        // state Android had just restored - so the list jumped to the top.
        //
        // A screen lock alone no longer does that (the fragment survives), but anything that
        // rebuilds the fragment did: a dark-mode flip or a language change, neither of which is in
        // this activity's configChanges. On a phone with scheduled dark mode that reproduces the
        // reporter's exact steps - scroll, lock, unlock at dusk, position gone.
        //
        // Seeding both values here makes the first emission a no-op, which is what the Packages
        // screen has effectively always done: it only ever calls adapter.updateShowIcons() and never
        // reassigns. That asymmetry is exactly what the reporter described.
        val initialShowIcons = settingsViewModel.uiState.value.showAppIcons
        previousObservedShowIcons = initialShowIcons

        adapter = NetworkPackageAdapter(
            showIcons = initialShowIcons,
            onPackageClick = { pkg ->
                showPackageActionSheet(pkg)
            },
            onPackageLongClick = { pkg ->
                onPackageLongClick(pkg)
            },
            onQuickToggle = { pkg, networkType ->
                handleQuickToggle(pkg, networkType)
            }
        )

        adapter.initialize(requireContext())

        adapter.setOnSelectionChangedListener { selected ->
            selectedPackages.clear()
            selectedPackages.addAll(selected)
            updateSelectionToolbar()
        }

        adapter.setOnSelectionLimitReachedListener {
            Toast.makeText(
                requireContext(),
                getString(R.string.multiselect_toast_limit_reached, Constants.Packages.MultiSelect.MAX_SELECTION_COUNT),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Reset last submitted packages when creating new adapter
        // This ensures the new adapter gets populated even if the list hasn't changed
        lastSubmittedPackages = emptyList()

        binding.packagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FirewallFragmentViews.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupFilterChips() {
        currentTypeFilter = getString(io.github.dorumrr.de1984.R.string.packages_filter_all)
        currentStateFilter = null
        currentPermissionFilter = true
        currentProfileFilter = getString(io.github.dorumrr.de1984.R.string.filter_profile_all)

        rebuildFilterChips(hasWorkProfile = false, hasCloneProfile = false)
    }

    private fun rebuildFilterChips(hasWorkProfile: Boolean, hasCloneProfile: Boolean) {
        val packageTypeFilters = listOf(
            getString(io.github.dorumrr.de1984.R.string.packages_filter_all),
            getString(io.github.dorumrr.de1984.R.string.packages_filter_user),
            getString(io.github.dorumrr.de1984.R.string.packages_filter_system)
        )
        val networkStateFilters = listOf(
            getString(io.github.dorumrr.de1984.R.string.firewall_state_allowed),
            getString(io.github.dorumrr.de1984.R.string.firewall_state_blocked)
        )
        val permissionFilters = listOf(
            getString(io.github.dorumrr.de1984.R.string.firewall_state_internet)
        )

        val profileFilters = mutableListOf<String>()
        if (hasWorkProfile || hasCloneProfile) {
            profileFilters.add(getString(io.github.dorumrr.de1984.R.string.filter_profile_all))
            profileFilters.add(getString(io.github.dorumrr.de1984.R.string.filter_profile_personal))
        }
        if (hasWorkProfile) {
            profileFilters.add(getString(io.github.dorumrr.de1984.R.string.filter_profile_work))
        }
        if (hasCloneProfile) {
            profileFilters.add(getString(io.github.dorumrr.de1984.R.string.filter_profile_clone))
        }

        // KEEP the chip for a saved filter whose profile produced no rows this scan, rather than
        // reassigning it. setProfileFilter PERSISTS, so this was a second copy of the reset the
        // ViewModel used to do - and it destroyed the same saved setting from the UI layer, one chip
        // rebuild later, whatever the ViewModel did.
        //
        // A profile filter that is still in force must always be visible and always be escapable.
        //
        // Two ways that broke. Appending the chip unguarded put a LONE chip on every profile-less
        // device - including the default "All Profiles" itself - and FilterChipsHelper treats a sole
        // entry as the default and re-checks it, pinning the filter with no control to move off it.
        // Guarding on "some other profile survived" then broke the opposite case: when the work
        // profile was the only one and stopped yielding rows, the whole profile row vanished while
        // "work" was still selected, so the list silently widened to every app with nothing on
        // screen saying so and no way to undo it.
        //
        // So: keep the chip whenever it is in force and not otherwise offered, and rebuild the rest
        // of the row beside it so it can always be deselected and the other profiles stay reachable.
        //
        // The list behind the kept chip is EMPTY, not full - the ViewModel used to widen an
        // unmatched filter to every row and that was removed, because a highlighted Work chip above
        // every personal app is worse than an honest "no apps in this profile". The chip is what
        // makes the empty list escapable; it is not decoration over a full list.
        //
        // A chip kept for a profile that is gone is not removed by stepping off it - a filter change
        // goes through applyFilters, which does not rebuild this row - so it lingers until the row is
        // rebuilt, on a profile-flag change or the next view recreation. It comes back whenever the
        // filter is still in force, which is the point. Untidy, escapable, and still far better than
        // overwriting what the user chose.
        val allProfilesLabel = getString(io.github.dorumrr.de1984.R.string.filter_profile_all)
        // The filter actually IN FORCE, read from the ViewModel - not `currentProfileFilter`, which
        // setupFilterChips has hardcoded to "All Profiles" at this point. rebuildFilterChips runs on
        // the first state emission, BEFORE updateFilterChips installs the saved value, so testing
        // the field meant this branch could never fire at screen start - the one moment it is for.
        val inForceProfileFilter =
            mapInternalToProfileFilter(viewModel.uiState.value.filterState.profileFilter)
        if (inForceProfileFilter != allProfilesLabel &&
            !profileFilters.contains(inForceProfileFilter)
        ) {
            if (profileFilters.isEmpty()) {
                // Both, in the order the normal path uses them. Adding only "All Profiles" left the
                // row as [All Profiles, Work] and quietly removed Personal until the profile came
                // back - a control the user lost without asking.
                profileFilters.add(allProfilesLabel)
                profileFilters.add(getString(io.github.dorumrr.de1984.R.string.filter_profile_personal))
            }
            // Re-checked: the insert above may have just added the very chip we are keeping. With a
            // persisted "Personal" filter on a device whose profiles vanished, adding it again gave
            // [All Profiles, Personal, Personal] with both highlighted - FilterChipsHelper matches
            // by tag, so the duplicate is selected too.
            if (!profileFilters.contains(inForceProfileFilter)) {
                profileFilters.add(inForceProfileFilter)
            }
        }

        FilterChipsHelper.setupMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            typeFilters = packageTypeFilters,
            stateFilters = networkStateFilters,
            permissionFilters = permissionFilters,
            profileFilters = profileFilters,
            selectedTypeFilter = currentTypeFilter,
            selectedStateFilter = currentStateFilter,
            selectedPermissionFilter = currentPermissionFilter,
            selectedProfileFilter = currentProfileFilter,
            onTypeFilterSelected = { filter ->
                if (filter != currentTypeFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Package type filter changed: $filter")
                    currentTypeFilter = filter
                    val internalFilter = mapTypeFilterToInternal(filter)
                    viewModel.setPackageTypeFilter(internalFilter)
                }
            },
            onStateFilterSelected = { filter ->
                if (filter != currentStateFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Network state filter changed: ${filter ?: "none"}")

                    if (isSelectionMode) {
                        AppLogger.d(TAG, "🔘 Exiting selection mode due to state filter change")
                        exitSelectionMode()
                    }

                    currentStateFilter = filter
                    val internalFilter = filter?.let { mapStateFilterToInternal(it) }
                    viewModel.setNetworkStateFilter(internalFilter)
                }
            },
            onPermissionFilterSelected = { enabled ->
                if (enabled != currentPermissionFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Internet-only filter changed: $enabled")
                    currentPermissionFilter = enabled
                    viewModel.setInternetOnlyFilter(enabled)
                }
            },
            onProfileFilterSelected = { filter ->
                if (filter != currentProfileFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Profile filter changed: $filter")
                    currentProfileFilter = filter
                    val internalFilter = mapProfileFilterToInternal(filter)
                    viewModel.setProfileFilter(internalFilter)
                }
            }
        )
    }

    private fun setupSearchBox() {
        binding.searchLayout.isEndIconVisible = false

        binding.searchInput.addTextChangedListener { text ->
            val query = text?.toString() ?: ""
            if (query.isNotEmpty()) {
                AppLogger.d(TAG, "🔍 USER ACTION: Search query changed: '$query'")
            }
            viewModel.setSearchQuery(query)

            binding.searchLayout.isEndIconVisible = query.isNotEmpty()
        }

        binding.searchLayout.setEndIconOnClickListener {
            AppLogger.d(TAG, "🔘 USER ACTION: Search cleared")
            binding.searchInput.text?.clear()
            binding.searchLayout.isEndIconVisible = false
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboardAndClearFocus()
                true
            } else {
                false
            }
        }

        binding.packagesRecyclerView.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (binding.searchInput.hasFocus()) {
                    hideKeyboardAndClearFocus()
                    view.requestFocus()
                }
            }
            false
        }

        binding.packagesRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (binding.searchInput.hasFocus()) {
                        hideKeyboardAndClearFocus()
                    }
                }
            }
        })

        binding.rootContainer.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val searchLayoutLocation = IntArray(2)
                binding.searchLayout.getLocationOnScreen(searchLayoutLocation)
                val searchLayoutRect = android.graphics.Rect(
                    searchLayoutLocation[0],
                    searchLayoutLocation[1],
                    searchLayoutLocation[0] + binding.searchLayout.width,
                    searchLayoutLocation[1] + binding.searchLayout.height
                )

                val touchX = event.rawX.toInt()
                val touchY = event.rawY.toInt()

                if (!searchLayoutRect.contains(touchX, touchY) && binding.searchInput.hasFocus()) {
                    hideKeyboardAndClearFocus()
                    view.requestFocus()
                }
            }
            false
        }
    }

    private fun hideKeyboardAndClearFocus() {
        binding.searchInput.clearFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun updateFilterChips(
        packageTypeFilter: String,
        networkStateFilter: String?,
        internetOnlyFilter: Boolean,
        profileFilter: String
    ) {
        val translatedTypeFilter = mapInternalToTypeFilter(packageTypeFilter)
        val translatedStateFilter = networkStateFilter?.let { mapInternalToStateFilter(it) }
        val translatedProfileFilter = mapInternalToProfileFilter(profileFilter)

        if (translatedTypeFilter == currentTypeFilter &&
            translatedStateFilter == currentStateFilter &&
            internetOnlyFilter == currentPermissionFilter &&
            translatedProfileFilter == currentProfileFilter) {
            return
        }

        currentTypeFilter = translatedTypeFilter
        currentStateFilter = translatedStateFilter
        currentPermissionFilter = internetOnlyFilter
        currentProfileFilter = translatedProfileFilter

        FilterChipsHelper.updateMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            selectedTypeFilter = translatedTypeFilter,
            selectedStateFilter = translatedStateFilter,
            selectedPermissionFilter = internetOnlyFilter,
            selectedProfileFilter = translatedProfileFilter
        )
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    /**
     * Which rows can be blocked at all depends on the running backend, and that changes without any
     * user action here: the firewall starts or stops, or a privilege gain promotes ConnectivityManager
     * to iptables. None of that touches the package data, so updateUI's diff sees nothing to submit
     * and the rows would keep whatever state they were bound with.
     */
    private fun observeActiveBackend() {
        val firewallManager = (requireActivity().application as De1984Application)
            .dependencies.firewallManager
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                firewallManager.activeBackendType.collect {
                    if (::adapter.isInitialized) {
                        adapter.refreshSettings(requireContext())
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun observeSettingsState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.uiState.collect { settingsState ->
                    AppLogger.d(TAG, "observeSettingsState: settingsState changed - showAppIcons=${settingsState.showAppIcons}, defaultFirewallPolicy=${settingsState.defaultFirewallPolicy}")
                    AppLogger.d(TAG, "observeSettingsState: previousObservedPolicy=$previousObservedPolicy, newPolicy=${settingsState.defaultFirewallPolicy}")

                    val iconsChanged = previousObservedShowIcons != settingsState.showAppIcons
                    val policyChanged = previousObservedPolicy != null &&
                        previousObservedPolicy != settingsState.defaultFirewallPolicy
                    val allowCriticalChanged = previousObservedAllowCritical != null &&
                        previousObservedAllowCritical != settingsState.allowCriticalPackageFirewall

                    if (allowCriticalChanged) {
                        // Changes what each row LOOKS like and whether its quick toggles respond.
                        // Refresh the adapter's cached copy and force a rebind, or critical and VPN
                        // rows stay dimmed with dead toggles until they scroll off screen and back.
                        AppLogger.d(TAG, "observeSettingsState: allowCriticalPackageFirewall changed - rebinding rows")
                        // It ALSO changes the package data, which this comment used to deny: the
                        // scan paints a critical or VPN package from this same setting. So the
                        // ViewModel is asked to rescan rather than to recompute - see
                        // repaintForCriticalPackagesSetting for why publishing without a rescan is
                        // the one thing that must not happen.
                        viewModel.repaintForCriticalPackagesSetting()
                        adapter.refreshSettings(requireContext())
                        adapter.notifyDataSetChanged()
                    }
                    previousObservedAllowCritical = settingsState.allowCriticalPackageFirewall

                    if (!iconsChanged && !policyChanged && previousObservedPolicy != null) {
                        // Nothing this screen renders has changed. Rebuilding here reset the scroll
                        // position and re-read every visible icon, on every unrelated settings write.
                        AppLogger.d(TAG, "observeSettingsState: nothing relevant changed - leaving the list alone")
                        return@collect
                    }

                    if (iconsChanged) {
                    if (isSelectionMode) {
                        AppLogger.d(TAG, "observeSettingsState: Exiting selection mode before adapter recreation")
                        exitSelectionMode()
                    }

                    adapter = NetworkPackageAdapter(
                        showIcons = settingsState.showAppIcons,
                        onPackageClick = { pkg ->
                            showPackageActionSheet(pkg)
                        },
                        onPackageLongClick = { pkg ->
                            onPackageLongClick(pkg)
                        },
                        onQuickToggle = { pkg, networkType ->
                            handleQuickToggle(pkg, networkType)
                        }
                    )

                    adapter.initialize(requireContext())

                    adapter.setOnSelectionChangedListener { selected ->
                        selectedPackages.clear()
                        selectedPackages.addAll(selected)
                        updateSelectionToolbar()
                    }

                    adapter.setOnSelectionLimitReachedListener {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.multiselect_toast_limit_reached, Constants.Packages.MultiSelect.MAX_SELECTION_COUNT),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    binding.packagesRecyclerView.adapter = adapter

                    lastSubmittedPackages = emptyList()

                    // The adapter is new, so anything selected is gone. If a restore was waiting for
                    // exactly this moment, apply it now.
                    applyPendingSelection()
                    }
                    previousObservedShowIcons = settingsState.showAppIcons

                    if (previousObservedPolicy != null && previousObservedPolicy != settingsState.defaultFirewallPolicy) {
                        AppLogger.d(TAG, "observeSettingsState: Policy changed! Refreshing packages...")
                        viewModel.refreshDefaultPolicy()
                    } else if (previousObservedPolicy == null) {
                        AppLogger.d(TAG, "observeSettingsState: First observation, skipping refresh")
                    } else {
                        AppLogger.d(TAG, "observeSettingsState: Policy unchanged, skipping refresh")
                    }

                    // Update previous policy for next comparison (persists across lifecycle)
                    previousObservedPolicy = settingsState.defaultFirewallPolicy

                    updateUI(viewModel.uiState.value)
                }
            }
        }
    }

    private fun updateUI(state: io.github.dorumrr.de1984.presentation.viewmodel.FirewallUiState) {
        // Twelve places set this and nothing ever read it, so a rule write that failed set an error
        // the user was never shown - the row simply stayed as it was with no explanation. Shown once,
        // then cleared, so a state emit that changes nothing else cannot repeat it.
        state.error?.let { message ->
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            viewModel.clearError()
        }

        val displayedPackages = if (state.searchQuery.isBlank()) {
            state.packages
        } else {
            val query = state.searchQuery.lowercase()
            state.packages.filter { pkg ->
                pkg.name.lowercase().contains(query, ignoreCase = false)
            }
        }

        // Computed BEFORE the visibility decision below, which used to test the unsearched list.
        // Testing the list actually shown is the right question either way.
        //
        // NOT CONFIRMED to fix the symptom it was written for: on hardware, a search matching
        // nothing still shows a blank list with no empty state. Three attempts found no
        // explanation, so something else is also holding that view down. Left in as the correct
        // test, not as a claimed fix.
        if (state.isLoadingData && state.packages.isEmpty()) {
            binding.packagesRecyclerView.visibility = View.INVISIBLE
            binding.loadingState.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        } else if (displayedPackages.isEmpty()) {
            binding.packagesRecyclerView.visibility = View.INVISIBLE
            binding.loadingState.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            // Say what actually happened. The Snackbar above is gone in three seconds, and the
            // generic subtitle then told a user whose scan had FAILED to adjust their filters.
            binding.emptyStateTitle.setText(
                if (state.scanFailed) R.string.error_package_scan_failed_title
                else R.string.firewall_empty_state_title
            )
            binding.emptyStateSubtitle.setText(
                // The hint, not the full sentence: the full one repeats the title word for word.
                if (state.scanFailed) R.string.error_package_scan_failed_hint
                else R.string.firewall_empty_state_subtitle
            )
        } else {
            binding.packagesRecyclerView.visibility = View.VISIBLE
            binding.loadingState.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }

        if (lastHasWorkProfile != state.hasWorkProfile || lastHasCloneProfile != state.hasCloneProfile) {
            lastHasWorkProfile = state.hasWorkProfile
            lastHasCloneProfile = state.hasCloneProfile
            AppLogger.d(TAG, "Profile availability changed: hasWork=${state.hasWorkProfile}, hasClone=${state.hasCloneProfile}")
            rebuildFilterChips(state.hasWorkProfile, state.hasCloneProfile)
        }

        updateFilterChips(
            packageTypeFilter = state.filterState.packageType,
            networkStateFilter = state.filterState.networkState,
            internetOnlyFilter = state.filterState.internetOnly,
            profileFilter = state.filterState.profileFilter
        )

        state.batchBlockResult?.let { result ->
            showBatchResultDialog(result)
            viewModel.clearBatchBlockResult()
        }

        // Before the early return below: the exempt set can change while the list does not - the
        // "Allow Firewall Critical Packages" switch moves, or the backend does - and the rows would
        // otherwise keep whatever they were bound with.
        adapter.setBlockingContext(state.blockingContext)

        val count = displayedPackages.size
        binding.packageCounter.setPackageCount(
            count = count,
            blank = count == 0 && state.searchQuery.isBlank(),
            searchInput = binding.searchInput
        )

        val listChanged = displayedPackages != lastSubmittedPackages
        if (!listChanged) {
            return
        }

        lastSubmittedPackages = displayedPackages
        adapter.submitList(displayedPackages)
        if (state.isRenderingUI) {
            viewModel.setUIReady()
        }
    }

    // ============================================================================
    // DO NOT REMOVE: This method is called from MainActivity for cross-navigation
    // ============================================================================
    fun openAppDialog(packageName: String, userId: Int = 0) {
        if (currentDialog?.isShowing == true) {
            AppLogger.w(TAG, "[FIREWALL] Dialog already open, dismissing before opening new one")
            currentDialog?.dismiss()
            currentDialog = null
        }

        val pkg = viewModel.uiState.value.packages.find {
            it.packageName == packageName && it.userId == userId
        }

        val targetPackageId = PackageId(packageName, userId)

        if (pkg != null) {
            pendingDialogPackageId = null
            scrollToPackage(packageName)
            showPackageActionSheet(pkg)
        } else {
            pendingDialogPackageId = targetPackageId

            lifecycleScope.launch {
                try {
                    val app = requireActivity().application as De1984Application
                    val networkPackageRepository = app.dependencies.networkPackageRepository
                    val result = networkPackageRepository.getNetworkPackage(packageName, userId)

                    result.onSuccess { foundPkg ->
                        if (pendingDialogPackageId != targetPackageId) {
                            return@onSuccess
                        }

                        val currentFilter = viewModel.uiState.value.filterState.packageType
                        // PackageType.toString() is the ENUM name ("USER"); the chips and this
                        // filter are keyed on Constants.Packages.TYPE_USER ("user"). filterPackages
                        // lowercases, so the LIST came out right while the chip showed nothing
                        // selected. Harmless while this path was only reachable from inside the
                        // app; the new-app notification now walks straight down it (issue #83).
                        val packageType = when (foundPkg.type) {
                            PackageType.USER -> Constants.Packages.TYPE_USER
                            PackageType.SYSTEM -> Constants.Packages.TYPE_SYSTEM
                        }

                        if (currentFilter.equals(packageType, ignoreCase = true)) {
                            viewModel.uiState.collect { state ->
                                if (pendingDialogPackageId != targetPackageId) {
                                    return@collect
                                }

                                val foundPackage = state.packages.find {
                                    it.packageName == packageName && it.userId == userId
                                }
                                if (foundPackage != null) {
                                    pendingDialogPackageId = null
                                    scrollToPackage(packageName)
                                    showPackageActionSheet(foundPackage)
                                    return@collect
                                }
                            }
                        } else {
                            viewModel.setPackageTypeFilter(packageType)

                            viewModel.uiState.collect { state ->
                                if (pendingDialogPackageId != targetPackageId) {
                                    return@collect
                                }

                                if (state.filterState.packageType.equals(packageType, ignoreCase = true) && !state.isLoading) {
                                    val foundPackage = state.packages.find {
                                        it.packageName == packageName && it.userId == userId
                                    }
                                    if (foundPackage != null) {
                                        pendingDialogPackageId = null
                                        scrollToPackage(packageName)
                                        showPackageActionSheet(foundPackage)
                                        return@collect
                                    }
                                }
                            }
                        }
                    }.onFailure { error ->
                        AppLogger.e(TAG, "Failed to load package for dialog: ${error.message}")
                        if (pendingDialogPackageId == targetPackageId) {
                            pendingDialogPackageId = null
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Exception opening dialog: ${e.message}")
                    if (pendingDialogPackageId == targetPackageId) {
                        pendingDialogPackageId = null
                    }
                }
            }
        }
    }

    private fun showPackageActionSheet(pkg: NetworkPackage) {
        val dialog = BottomSheetDialog(requireContext())
        currentDialog = dialog

        if (supportsGranularControl()) {
            showGranularControlSheet(dialog, pkg)
        } else {
            showSimpleControlSheet(dialog, pkg)
        }
    }

    private fun showGranularControlSheet(dialog: BottomSheetDialog, pkg: NetworkPackage) {
        AppLogger.d(TAG, "showGranularControlSheet: ENTRY - pkg=${pkg.packageName}, dialog=$dialog")
        val binding = BottomSheetPackageActionGranularBinding.inflate(layoutInflater)

        val telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasCellular = telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE

        binding.actionSheetAppIcon.setImageResource(R.drawable.de1984_icon)
        binding.actionSheetAppName.text = pkg.name
        binding.actionSheetPackageName.text = pkg.packageName

        // Load icon asynchronously to prevent blocking main thread
        // Work profile apps require slow shell commands via HiddenApiHelper
        // IMPORTANT: Capture context BEFORE entering coroutine to avoid IllegalStateException
        val context = requireContext()
        lifecycleScope.launch {
            val icon = withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val appInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                        context, pkg.packageName, 0, pkg.userId
                    )
                    if (appInfo != null) {
                        pm.getApplicationIcon(appInfo)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (dialog.isShowing && isAdded) {
                icon?.let { binding.actionSheetAppIcon.setImageDrawable(it) }
            }
        }

        binding.actionSheetPackageName.setOnClickListenerDebounced {
            requireContext().copyToClipboard(pkg.packageName, getString(R.string.clipboard_label_package_name))
        }

        binding.actionSheetSettingsIcon.setOnClickListener {
            requireContext().openAppSettings(pkg.packageName)
            dialog.dismiss()
        }

        if (hasCellular) {
            binding.roamingDivider.visibility = View.VISIBLE
            binding.roamingToggle.root.visibility = View.VISIBLE
        } else {
            binding.roamingDivider.visibility = View.GONE
            binding.roamingToggle.root.visibility = View.GONE
        }

        // Read once for the whole sheet. Two separate reads of this StateFlow used to sit in this
        // function - the LAN toggle and the switch colours - and they could disagree if the backend
        // changed between them.
        //
        // NOT firewallManager.getActiveBackendType(), which the info-message block below uses. That
        // one returns currentBackend?.getType(), which is deliberately KEPT across a stop; this flow
        // is nulled. "Is a backend enforcing rules right now" is the question here, so the flow is
        // the right source and a stopped firewall must answer null.
        val runningBackendType = (requireActivity().application as De1984Application)
            .dependencies.firewallManager.activeBackendType.value
        val isIptablesBackend = runningBackendType == FirewallBackendType.IPTABLES

        // Neither Shizuku backend can touch a uid outside the app range - "Android System" and the
        // rest of the platform. The rule would still be written and this sheet would still read
        // "Blocked" while the app kept its network, so say so instead.
        //
        // vars, not vals: renderEnforcementState below recomputes both on every uiState emission,
        // because the reason can END while the sheet is open - the switch this sheet's own message
        // tells the user to turn on writes the rule that lifts it, and a banner computed once kept
        // saying "turn on the switch" after the switch was on. Same for the firewall stopping or a
        // privilege change swapping the backend under an open sheet.
        var unblockableReason = runningBackendType.unblockableReason(
            pkg, viewModel.uiState.value.blockingContext
        )
        // Dead controls only when nothing here can fix it.
        var controlsRefused = unblockableReason?.fixableHere == false

        var isUpdatingProgrammatically = false

        fun updateTogglesFromPackage(currentPkg: NetworkPackage) {
            AppLogger.d(TAG, "updateTogglesFromPackage: pkg=${currentPkg.packageName}, wifi=${currentPkg.wifiBlocked}, mobile=${currentPkg.mobileBlocked}, roaming=${currentPkg.roamingBlocked}, background=${currentPkg.backgroundBlocked}, isFullyBlocked=${currentPkg.isFullyBlocked}")
            isUpdatingProgrammatically = true

            // Masked the same way the list row is. A disabled switch still keeps its checked
            // colour, so without this the sheet showed three red "Blocked" switches directly under
            // the banner saying the backend cannot block this - and disagreed with the row behind
            // it, which now paints the same three icons as allowed.
            // Not masked here. The list arrives with the ENFORCED flags already applied
            // (FirewallViewModel.filterPackages), and masking a second time is what made this sheet
            // disagree with its own row and with the LAN switch beside it.
            //
            // One exception, and only one: when a NEIGHBOUR in the same uid blocks more than this
            // app's own rule, the mask ADDS blocks. A switch must show the rule it actually moves,
            // or every tap writes the value already stored and the switch springs back looking
            // dead. The banner above it is what explains the neighbour. Every other mask REMOVES
            // blocks, and there the zeroed display is exactly what the switch should show - its
            // message asks the user to turn that switch on.
            val controlPkg = currentPkg.asSaved()
            val wifiBlocked = controlPkg.wifiBlocked
            val mobileBlocked = controlPkg.mobileBlocked
            val roamingBlocked = controlPkg.roamingBlocked

            binding.wifiToggle.toggleSwitch.isChecked = wifiBlocked
            updateSwitchColors(binding.wifiToggle.toggleSwitch, wifiBlocked)

            binding.mobileToggle.toggleSwitch.isChecked = mobileBlocked
            updateSwitchColors(binding.mobileToggle.toggleSwitch, mobileBlocked)

            if (hasCellular) {
                binding.roamingToggle.toggleSwitch.isChecked = roamingBlocked
                updateSwitchColors(binding.roamingToggle.toggleSwitch, roamingBlocked)
            }

            val lanBlocked = controlPkg.lanBlocked
            binding.lanToggle.toggleSwitch.isChecked = lanBlocked
            if (isIptablesBackend) {
                updateSwitchColors(binding.lanToggle.toggleSwitch, lanBlocked)
            }

            // The ROW's value, not the live preference: the row's flags were painted with it.
            bindScreenOffToggle(
                toggle = binding.foregroundOnlyToggle,
                divider = binding.foregroundOnlyDivider,
                saved = controlPkg,
                allowCritical = currentPkg.paintedAllowCritical,
                blockedEverywhere = controlPkg.isFullyBlocked,
                controlsRefused = controlsRefused,
                isUpdating = { isUpdatingProgrammatically },
            )

            isUpdatingProgrammatically = false
        }

        // The reason, the dimming, the subtitles and the info message are DERIVED state, and every
        // input can change while the sheet is open: the rule this sheet's own switches write, a
        // batch from another screen, the firewall stopping, a privilege change swapping the
        // backend. Rendered from scratch on every emission, exactly like the switch positions.
        fun renderEnforcementState(currentPkg: NetworkPackage) {
            val backendNow = (requireActivity().application as De1984Application)
                .dependencies.firewallManager.activeBackendType.value
            val iptablesNow = backendNow == FirewallBackendType.IPTABLES
            val allowCriticalNow = currentPkg.paintedAllowCritical

            unblockableReason = backendNow.unblockableReason(
                currentPkg, viewModel.uiState.value.blockingContext
            )
            controlsRefused = unblockableReason?.fixableHere == false

            val canToggle = (!currentPkg.isSystemCritical || allowCriticalNow) &&
                (!currentPkg.isVpnApp || allowCriticalNow) && !controlsRefused
            val refusedText = getString(refusedSubtitle(unblockableReason))

            listOfNotNull(
                binding.wifiToggle,
                binding.mobileToggle,
                binding.roamingToggle.takeIf { hasCellular },
            ).forEach { toggle ->
                toggle.toggleSwitch.isEnabled = canToggle
                if (controlsRefused) {
                    toggle.root.alpha = 0.6f
                    toggle.networkTypeSubtitle.visibility = View.VISIBLE
                    toggle.networkTypeSubtitle.text = refusedText
                } else {
                    toggle.root.alpha = 1f
                    toggle.networkTypeSubtitle.visibility = View.GONE
                }
            }

            // LAN keeps its stronger and still-correct answer when the backend cannot do LAN at
            // all; the refused subtitle only when LAN would otherwise be reachable.
            binding.lanToggle.toggleSwitch.isEnabled = iptablesNow && canToggle
            when {
                !iptablesNow -> {
                    binding.lanToggle.root.alpha = 0.6f
                    binding.lanToggle.networkTypeSubtitle.visibility = View.VISIBLE
                    binding.lanToggle.networkTypeSubtitle.text =
                        getString(R.string.firewall_lan_requires_root)
                }
                controlsRefused -> {
                    binding.lanToggle.root.alpha = 0.6f
                    binding.lanToggle.networkTypeSubtitle.visibility = View.VISIBLE
                    binding.lanToggle.networkTypeSubtitle.text = refusedText
                }
                else -> {
                    binding.lanToggle.root.alpha = 1f
                    binding.lanToggle.networkTypeSubtitle.visibility = View.GONE
                }
            }

            // Same ordering as the simple sheet: the protection setting first when it is what
            // bites, then the backend reason. This is the only explanation a root user gets.
            val reason = unblockableReason
            if ((currentPkg.isSystemCritical || currentPkg.isVpnApp) && !allowCriticalNow) {
                binding.infoMessage.visibility = View.VISIBLE
                binding.infoMessage.text = if (currentPkg.isSystemCritical) {
                    getString(R.string.firewall_system_critical_info)
                } else {
                    getString(R.string.firewall_vpn_app_info)
                }
            } else if (reason != null) {
                binding.infoMessage.visibility = View.VISIBLE
                binding.infoMessage.text = getString(
                    when (reason) {
                        UnblockableReason.UNKNOWN_UID -> R.string.firewall_unknown_uid_info
                        UnblockableReason.OTHER_PROFILE_UNREACHABLE -> R.string.firewall_other_profile_info
                        UnblockableReason.OTHER_PROFILE_FOLLOWS_OWN_PROFILE -> R.string.firewall_other_profile_follows_info
                        UnblockableReason.PLATFORM_REFUSES_SYSTEM_UID -> R.string.firewall_system_uid_info
                        UnblockableReason.SHARED_WITH_PROTECTED_PACKAGE -> R.string.firewall_shared_uid_info
                        UnblockableReason.NO_RULE_IN_PROTECTED_UID -> R.string.firewall_no_rule_protected_uid_info
                        UnblockableReason.SIBLING_RULE_OVERRIDES_DEFAULT -> R.string.firewall_sibling_rule_info
                    }
                )
            } else if (!currentPkg.hasInternetPermission) {
                binding.infoMessage.visibility = View.VISIBLE
                binding.infoMessage.text = getString(R.string.firewall_no_internet_info)
            } else if (currentPkg.isVpnApp) {
                binding.infoMessage.visibility = View.VISIBLE
                binding.infoMessage.text = getString(R.string.firewall_vpn_app_info)
            } else if ((requireActivity().application as De1984Application)
                    .dependencies.firewallManager.getActiveBackendType() == FirewallBackendType.VPN
            ) {
                binding.infoMessage.visibility = View.VISIBLE
                binding.infoMessage.text = getString(R.string.firewall_vpn_info_message)
            } else {
                binding.infoMessage.visibility = View.GONE
            }
        }

        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // Match the FULL identity, name AND userId. Issue #97.
                //
                // A package name is not unique: the same app in a work or cloned profile is a second
                // row with the same name and its own rules. Matching on name alone returned whichever
                // row came first, so this sheet repainted its switches from ANOTHER PROFILE's flags.
                //
                // It looked like the toggles simply did not update. What actually happened: the sheet
                // seeds correctly from `pkg` at setupNetworkToggle, this collector (Main.immediate,
                // so it runs synchronously) then repaints from the wrong row, and it wins because
                // emissions are guaranteed - the tap's own optimistic updatePackageInList, the
                // debounced reload a rule write triggers, the isRenderingUI round trip, and the
                // shared flow the Packages screen also collects. Corrected for one frame, wrong after.
                //
                // Only visible where the two rows DIFFER. Measured on hardware 2026-08-29: LAN blocked
                // in the work profile, allowed in the personal one, so LAN alone displayed wrong while
                // WiFi/Mobile/Roaming agreed by coincidence and looked fine. Because the switch never
                // showed "blocked", every further tap wrote "block" again and LAN could not be undone
                // from this sheet at all.
                //
                // The write path was never wrong - onToggle passes pkg.userId, and
                // updatePackageInList already matches on it. Only this read back was.
                // From the unfiltered cache, not state.packages: a write can move this row out of
                // the active Blocked/Allowed chip, and re-reading the filtered list then found
                // nothing and left the sheet frozen at its last values. The emission is still the
                // trigger; only the lookup changed. Issue #97's packageName+userId match is kept -
                // PackageId is exactly that pair.
                val updatedPkg = viewModel.enforcedPackage(pkg.id)
                AppLogger.d(TAG, "showGranularControlSheet: uiState collected - updatedPkg found=${updatedPkg != null}, isUpdatingProgrammatically=$isUpdatingProgrammatically")
                if (updatedPkg != null && !isUpdatingProgrammatically) {
                    AppLogger.d(TAG, "showGranularControlSheet: Calling updateTogglesFromPackage for ${updatedPkg.packageName}")
                    // Reason first, toggles second: updateTogglesFromPackage reads controlsRefused
                    // for the background-toggle visibility, and this call is what refreshes it.
                    renderEnforcementState(updatedPkg)
                    updateTogglesFromPackage(updatedPkg)
                } else if (updatedPkg != null) {
                    AppLogger.d(TAG, "showGranularControlSheet: Skipping update (isUpdatingProgrammatically=true)")
                }
            }
        }

        dialog.setOnDismissListener {
            AppLogger.d(TAG, "showGranularControlSheet: Dialog dismissed, cancelling observer for ${pkg.packageName}")
            observerJob.cancel()
            if (currentDialog == dialog) {
                currentDialog = null
            }
        }

        val allowCritical = pkg.paintedAllowCritical
        val isProtected = (pkg.isSystemCritical || pkg.isVpnApp) && !allowCritical
        // Deliberately NOT extended to controlsRefused. This banner is titled "Protected
        // Package", which such a row is not - it is reachable in principle and refused by the
        // backend. The per-toggle subtitles and the info message below carry that reason instead.
        //
        // This sheet IS reachable for such a row: iptables reports supportsGranularControl() ==
        // true and still refuses an exempt uid, which is the whole root case.
        if (isProtected) {
            binding.protectionWarningBanner.root.visibility = View.VISIBLE

            val bannerMessage = when {
                pkg.isSystemCritical -> getString(R.string.protection_banner_message_firewall_system)
                pkg.isVpnApp -> getString(R.string.protection_banner_message_firewall_vpn)
                else -> getString(R.string.protection_banner_message_firewall)
            }

            binding.protectionWarningBanner.bannerMessage.text = bannerMessage

            binding.protectionWarningBanner.bannerSettingsButton.setOnClickListener {
                dialog.dismiss()
                (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToSettings()
            }
        } else {
            binding.protectionWarningBanner.root.visibility = View.GONE
        }

        // asSaved for every switch position below. These one-time calls run AFTER the live
        // collector is registered, so at open they are the last writer - and a masked `pkg` put a
        // neighbour's block onto switches that move this app's OWN rule. Tapping then wrote the
        // value already stored and the switch sprang back, looking dead.
        val controlBase = pkg.asSaved()

        setupNetworkToggle(
            binding = binding.wifiToggle,
            label = getString(R.string.firewall_network_label_wifi),
            isBlocked = controlBase.wifiBlocked,
            enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical) && !controlsRefused,
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
                AppLogger.d(TAG, "🔘 USER ACTION: WiFi toggle changed for ${pkg.packageName} - blocked: $blocked")
                viewModel.setWifiBlocking(pkg.packageName, pkg.userId, blocked)
            }
        )

        setupNetworkToggle(
            binding = binding.mobileToggle,
            label = getString(R.string.firewall_network_label_mobile),
            isBlocked = controlBase.mobileBlocked,
            enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical) && !controlsRefused,
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
                AppLogger.d(TAG, "🔘 USER ACTION: Mobile toggle changed for ${pkg.packageName} - blocked: $blocked")

                viewModel.setMobileBlocking(pkg.packageName, pkg.userId, blocked)
            }
        )

        if (hasCellular) {
            setupNetworkToggle(
                binding = binding.roamingToggle,
                label = getString(R.string.firewall_network_label_roaming),
                isBlocked = controlBase.roamingBlocked,
                enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical) && !controlsRefused,
                onToggle = { blocked ->
                    if (isUpdatingProgrammatically) return@setupNetworkToggle
                    AppLogger.d(TAG, "🔘 USER ACTION: Roaming toggle changed for ${pkg.packageName} - blocked: $blocked")

                    viewModel.setRoamingBlocking(pkg.packageName, pkg.userId, blocked)
                }
            )
        }

        // Setup LAN toggle - always visible, disabled when not using iptables backend.
        // Its dimming and subtitle come from renderEnforcementState below, with everything else.
        binding.lanDivider.visibility = View.VISIBLE
        binding.lanToggle.root.visibility = View.VISIBLE

        setupNetworkToggle(
            binding = binding.lanToggle,
            label = getString(R.string.firewall_network_label_lan),
            isBlocked = controlBase.lanBlocked,
            // isIptablesBackend alone is not enough: an unresolved uid is out of reach for iptables
            // too, and that is the one case where both conditions can be true at once.
            enabled = isIptablesBackend && !controlsRefused &&
                (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
                viewModel.setLanBlocking(pkg.packageName, pkg.userId, blocked)
            }
        )

        if (isProtected) {
            binding.wifiToggle.root.setOnClickListener {
                if (!binding.wifiToggle.toggleSwitch.isEnabled) {
                    showProtectionSnackbar(dialog)
                }
            }

            binding.mobileToggle.root.setOnClickListener {
                if (!binding.mobileToggle.toggleSwitch.isEnabled) {
                    showProtectionSnackbar(dialog)
                }
            }

            if (hasCellular) {
                binding.roamingToggle.root.setOnClickListener {
                    if (!binding.roamingToggle.toggleSwitch.isEnabled) {
                        showProtectionSnackbar(dialog)
                    }
                }
            }

            binding.lanToggle.root.setOnClickListener {
                if (!binding.lanToggle.toggleSwitch.isEnabled && isIptablesBackend) {
                    showProtectionSnackbar(dialog)
                }
            }
        }

        bindScreenOffToggle(
            toggle = binding.foregroundOnlyToggle,
            divider = binding.foregroundOnlyDivider,
            saved = controlBase,
            allowCritical = allowCritical,
            blockedEverywhere = controlBase.isFullyBlocked,
            controlsRefused = controlsRefused,
            isUpdating = { isUpdatingProgrammatically },
        )

        // Dimming, subtitles, enabled-state and the info message, from the same single answer the
        // collector repaints with. One code path at open and on every later change.
        renderEnforcementState(pkg)

        binding.manageAppAction.setOnClickListener {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToPackagesWithApp(pkg.packageName, pkg.userId)
        }

        dialog.setContentView(binding.root)
        AppLogger.d(TAG, "showGranularControlSheet: EXIT - About to show dialog for ${pkg.packageName}")
        dialog.show()

        dialog.behavior.apply {
            isDraggable = true
            // Allow the sheet to be dragged, but nested scrolling will take priority
            // This ensures content scrolls first before the sheet starts dragging
        }
    }

    private fun showSimpleControlSheet(dialog: BottomSheetDialog, pkg: NetworkPackage) {
        val binding = BottomSheetPackageActionSimpleBinding.inflate(layoutInflater)

        binding.actionSheetAppIcon.setImageResource(R.drawable.de1984_icon)
        binding.actionSheetAppName.text = pkg.name
        binding.actionSheetPackageName.text = pkg.packageName

        // Load icon asynchronously to prevent blocking main thread
        // Work profile apps require slow shell commands via HiddenApiHelper
        // IMPORTANT: Capture context BEFORE entering coroutine to avoid IllegalStateException
        val context = requireContext()
        lifecycleScope.launch {
            val icon = withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val appInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                        context, pkg.packageName, 0, pkg.userId
                    )
                    if (appInfo != null) {
                        pm.getApplicationIcon(appInfo)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (dialog.isShowing && isAdded) {
                icon?.let { binding.actionSheetAppIcon.setImageDrawable(it) }
            }
        }

        binding.actionSheetPackageName.setOnClickListenerDebounced {
            requireContext().copyToClipboard(pkg.packageName, getString(R.string.clipboard_label_package_name))
        }

        // ============================================================================
        // IMPORTANT: Click settings icon to open Android system settings
        // User preference: Settings cog icon on the right opens Android app settings
        // DO NOT REMOVE THIS FUNCTIONALITY - it's a core feature!
        // ============================================================================
        binding.actionSheetSettingsIcon.setOnClickListener {
            requireContext().openAppSettings(pkg.packageName)
            dialog.dismiss()
        }

        val app = requireActivity().application as De1984Application
        val firewallManager = app.dependencies.firewallManager
        val backendType = firewallManager.getActiveBackendType()
        // See showGranularControlSheet: backendType above survives a stop; the running one, which
        // renderSimpleEnforcementState reads fresh on every pass, does not - and "cannot be
        // blocked" is a claim only a RUNNING backend earns.

        var controlsRefused = firewallManager.activeBackendType.value
            .unblockableReason(pkg, viewModel.uiState.value.blockingContext)?.fixableHere == false

        // A function, not a value computed once: it recomputes on every uiState emission. Computed
        // once, the banner froze at its open-time answer while the switch beside it - which reads
        // the row live - kept moving, so the two contradicted each other on the one backend that
        // uses this sheet AND decides by uid, NetworkPolicyManager. Everything it decides is applied
        // to the views here, so nothing outside needs to hold it.
        fun renderSimpleEnforcementState(currentPkg: NetworkPackage) {
            val runningNow = firewallManager.activeBackendType.value
            val allowCriticalNow = currentPkg.paintedAllowCritical

            val unblockableReason = runningNow.unblockableReason(
                currentPkg, viewModel.uiState.value.blockingContext
            )
            // Dead controls only when nothing here can fix it.
            controlsRefused = unblockableReason?.fixableHere == false

            // First, ahead of every other branch. The no-internet note promises that blocking now
            // "will take effect if the app gains internet permission in a future update" - untrue
            // for a uid this backend can never act on, and several platform components declare no
            // network permission of their own, so that branch would otherwise win for exactly these
            // rows. The critical-package branches are outranked too: the "Allow Firewall Critical
            // Packages" setting changes nothing here, and pointing at it would send the user to a
            // switch that leaves the app online either way.
            // The protection SETTING comes first when it is what bites. Otherwise a whitelisted app
            // or a VPN app was told it "shares a user ID with a protected app" - it IS the protected
            // app - and the message naming the switch that would unlock it never appeared.
            val protectedBySetting =
                (currentPkg.isSystemCritical || currentPkg.isVpnApp) && !allowCriticalNow

            val reason = unblockableReason
            val infoMessage: String? = if (protectedBySetting) {
                if (currentPkg.isSystemCritical) {
                    getString(R.string.firewall_system_critical_info)
                } else {
                    getString(R.string.firewall_vpn_app_info)
                }
            } else if (reason != null) {
                getString(
                    when (reason) {
                        UnblockableReason.UNKNOWN_UID -> R.string.firewall_unknown_uid_info
                        UnblockableReason.OTHER_PROFILE_UNREACHABLE -> R.string.firewall_other_profile_info
                        UnblockableReason.OTHER_PROFILE_FOLLOWS_OWN_PROFILE -> R.string.firewall_other_profile_follows_info
                        UnblockableReason.PLATFORM_REFUSES_SYSTEM_UID -> R.string.firewall_system_uid_info
                        UnblockableReason.SHARED_WITH_PROTECTED_PACKAGE -> R.string.firewall_shared_uid_info
                        UnblockableReason.NO_RULE_IN_PROTECTED_UID -> R.string.firewall_no_rule_protected_uid_info
                        UnblockableReason.SIBLING_RULE_OVERRIDES_DEFAULT -> R.string.firewall_sibling_rule_info
                    }
                )
            } else if (!currentPkg.hasInternetPermission) {
                getString(R.string.firewall_no_internet_info)
            } else if (currentPkg.isSystemCritical || currentPkg.isVpnApp) {
                // Only reachable with the setting ON - the OFF case is handled above.
                getString(R.string.firewall_critical_allowed_info)
            } else if (backendType == io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.CONNECTIVITY_MANAGER) {
                getString(R.string.firewall_connectivity_manager_info)
            } else {
                null
            }

            if (infoMessage != null) {
                binding.infoMessage.visibility = View.VISIBLE
                binding.infoMessage.text = infoMessage
            } else {
                binding.infoMessage.visibility = View.GONE
            }

            binding.internetToggle.toggleSwitch.isEnabled =
                (!currentPkg.isSystemCritical || allowCriticalNow) &&
                    (!currentPkg.isVpnApp || allowCriticalNow) &&
                    !controlsRefused
            binding.internetToggle.networkTypeSubtitle.visibility = View.VISIBLE
            binding.internetToggle.networkTypeSubtitle.text = if (controlsRefused) {
                getString(refusedSubtitle(unblockableReason))
            } else {
                getString(R.string.firewall_internet_access_subtitle)
            }
            binding.internetToggle.root.alpha = if (controlsRefused) 0.6f else 1f
        }

        var isUpdatingProgrammatically = false

        fun updateToggleFromPackage(currentPkg: NetworkPackage) {
            isUpdatingProgrammatically = true

            // asSaved: this switch writes this app's OWN rule, so it has to show that rule. A
            // neighbour in the same uid blocking more is explained by the banner, not by moving a
            // control the user cannot reach from here.
            val saved = currentPkg.asSaved()
            val isBlocked = saved.wifiBlocked || saved.mobileBlocked ||
                saved.roamingBlocked
            binding.internetToggle.toggleSwitch.isChecked = isBlocked
            updateSwitchColors(binding.internetToggle.toggleSwitch, isBlocked)
            bindScreenOffToggle(
                toggle = binding.foregroundOnlyToggle,
                divider = binding.foregroundOnlyDivider,
                saved = saved,
                allowCritical = currentPkg.paintedAllowCritical,
                blockedEverywhere = isBlocked,
                controlsRefused = controlsRefused,
                isUpdating = { isUpdatingProgrammatically },
            )

            isUpdatingProgrammatically = false
        }

        val simpleBase = pkg.asSaved()

        updateToggleFromPackage(pkg)

        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // Same fix as showGranularControlSheet - see the note there for the full mechanism.
                // This sheet's Internet Access switch drifts the same way on a multi-profile device;
                // fixing only one of the two leaves the other reporting another profile's state.
                // From the unfiltered cache, not state.packages: a write can move this row out of
                // the active Blocked/Allowed chip, and re-reading the filtered list then found
                // nothing and left the sheet frozen at its last values. The emission is still the
                // trigger; only the lookup changed. Issue #97's packageName+userId match is kept -
                // PackageId is exactly that pair.
                val updatedPkg = viewModel.enforcedPackage(pkg.id)
                if (updatedPkg != null && !isUpdatingProgrammatically) {
                    renderSimpleEnforcementState(updatedPkg)
                    updateToggleFromPackage(updatedPkg)
                }
            }
        }

        dialog.setOnDismissListener {
            AppLogger.d(TAG, "showSimpleControlSheet: Dialog dismissed, cancelling observer for ${pkg.packageName}")
            observerJob.cancel()
        }

        setupNetworkToggle(
            binding = binding.internetToggle,
            label = getString(R.string.firewall_network_label_internet_access),
            isBlocked = simpleBase.wifiBlocked || simpleBase.mobileBlocked || simpleBase.roamingBlocked,
            // Fail closed. renderSimpleEnforcementState below is what decides this, and it runs on
            // the very next line - but if the two are ever reordered, a control the backend refuses
            // must not come up live.
            enabled = false,
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle

                viewModel.setAllNetworkBlocking(pkg.packageName, pkg.userId, blocked)
            }
        )
        // After setupNetworkToggle, never before: these one-time calls run AFTER the collector is
        // registered, so at open they are the last writer and would undo the render.
        renderSimpleEnforcementState(pkg)

        setupNetworkToggle(
            binding = binding.lanToggle,
            label = getString(R.string.firewall_network_label_lan),
            isBlocked = simpleBase.lanBlocked,
            enabled = false,
            onToggle = { }
        )
        binding.lanToggle.root.alpha = 0.6f
        binding.lanToggle.networkTypeSubtitle.visibility = View.VISIBLE
        binding.lanToggle.networkTypeSubtitle.text = getString(R.string.firewall_lan_requires_root)

        binding.manageAppAction.setOnClickListener {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToPackagesWithApp(pkg.packageName, pkg.userId)
        }

        dialog.setContentView(binding.root)
        dialog.show()

        dialog.behavior.apply {
            isDraggable = true
            // Allow the sheet to be dragged, but nested scrolling will take priority
            // This ensures content scrolls first before the sheet starts dragging
        }
    }

    private fun refusedSubtitle(reason: UnblockableReason?): Int =
        if (reason == UnblockableReason.OTHER_PROFILE_FOLLOWS_OWN_PROFILE) R.string.firewall_other_profile_follows_subtitle
        else R.string.firewall_backend_cannot_block_uid

    // The only switch enabled unconditionally, so hiding it is what stops it writing a rule the
    // backend will skip. One copy for both sheets: they must hide and show it the same way.
    private fun bindScreenOffToggle(
        toggle: NetworkTypeToggleBinding,
        divider: View,
        saved: NetworkPackage,
        allowCritical: Boolean,
        blockedEverywhere: Boolean,
        controlsRefused: Boolean,
        isUpdating: () -> Boolean,
    ) {
        val show = (!saved.isSystemCritical || allowCritical) && (!saved.isVpnApp || allowCritical) &&
            !blockedEverywhere && !controlsRefused
        divider.visibility = if (show) View.VISIBLE else View.GONE
        toggle.root.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        // Detached first: setupNetworkToggle sets the position, and a still-attached listener would write it.
        toggle.toggleSwitch.setOnCheckedChangeListener(null)
        setupNetworkToggle(
            binding = toggle,
            label = getString(R.string.firewall_network_label_background_access),
            isBlocked = !saved.backgroundBlocked,
            enabled = true,
            invertLabels = true,
            onToggle = { allowed ->
                if (isUpdating()) return@setupNetworkToggle
                viewModel.setBackgroundBlocking(saved.packageName, saved.userId, !allowed)
            }
        )
    }

    private fun setupNetworkToggle(
        binding: NetworkTypeToggleBinding,
        label: String,
        isBlocked: Boolean,
        enabled: Boolean,
        invertLabels: Boolean = false,
        onToggle: (Boolean) -> Unit
    ) {
        AppLogger.d(TAG, "setupNetworkToggle: label=$label, isBlocked=$isBlocked, enabled=$enabled, binding=$binding")
        binding.networkTypeLabel.text = label

        if (invertLabels) {
            binding.labelLeft.text = getString(R.string.firewall_state_off)
            binding.labelRight.text = getString(R.string.firewall_state_on)
        } else {
            binding.labelLeft.text = getString(R.string.firewall_state_allowed)
            binding.labelRight.text = getString(R.string.firewall_state_blocked)
        }

        binding.toggleSwitch.isChecked = isBlocked
        binding.toggleSwitch.isEnabled = enabled

        updateSwitchColors(binding.toggleSwitch, isBlocked, invertColors = invertLabels)

        binding.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColors(binding.toggleSwitch, isChecked, invertColors = invertLabels)
            onToggle(isChecked)
        }
    }

    private fun updateSwitchColors(
        switch: SwitchMaterial,
        @Suppress("UNUSED_PARAMETER") isBlocked: Boolean,
        invertColors: Boolean = false
    ) {
        val context = switch.context

        val (checkedColor, uncheckedColor) = if (invertColors) {
            Pair(
                ContextCompat.getColor(context, R.color.lineage_teal),
                ContextCompat.getColor(context, R.color.error_red)
            )
        } else {
            Pair(
                ContextCompat.getColor(context, R.color.error_red),
                ContextCompat.getColor(context, R.color.lineage_teal)
            )
        }

        val thumbColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(checkedColor, uncheckedColor)
        )

        val trackColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                checkedColor and 0x80FFFFFF.toInt(),
                uncheckedColor and 0x80FFFFFF.toInt()
            )
        )

        switch.thumbTintList = thumbColorStateList
        switch.trackTintList = trackColorStateList
    }

    private fun mapTypeFilterToInternal(translatedFilter: String): String {
        return when (translatedFilter) {
            getString(io.github.dorumrr.de1984.R.string.packages_filter_all) -> Constants.Packages.TYPE_ALL
            getString(io.github.dorumrr.de1984.R.string.packages_filter_user) -> Constants.Packages.TYPE_USER
            getString(io.github.dorumrr.de1984.R.string.packages_filter_system) -> Constants.Packages.TYPE_SYSTEM
            else -> Constants.Packages.TYPE_ALL
        }
    }

    private fun mapStateFilterToInternal(translatedFilter: String): String {
        return when (translatedFilter) {
            getString(io.github.dorumrr.de1984.R.string.firewall_state_allowed) -> Constants.Firewall.STATE_ALLOWED
            getString(io.github.dorumrr.de1984.R.string.firewall_state_blocked) -> Constants.Firewall.STATE_BLOCKED
            else -> translatedFilter
        }
    }

    private fun mapInternalToTypeFilter(internalFilter: String): String {
        return when (internalFilter) {
            Constants.Packages.TYPE_ALL -> getString(io.github.dorumrr.de1984.R.string.packages_filter_all)
            Constants.Packages.TYPE_USER -> getString(io.github.dorumrr.de1984.R.string.packages_filter_user)
            Constants.Packages.TYPE_SYSTEM -> getString(io.github.dorumrr.de1984.R.string.packages_filter_system)
            else -> getString(io.github.dorumrr.de1984.R.string.packages_filter_all)
        }
    }

    private fun mapInternalToStateFilter(internalFilter: String): String {
        return when (internalFilter) {
            Constants.Firewall.STATE_ALLOWED -> getString(io.github.dorumrr.de1984.R.string.firewall_state_allowed)
            Constants.Firewall.STATE_BLOCKED -> getString(io.github.dorumrr.de1984.R.string.firewall_state_blocked)
            else -> internalFilter
        }
    }

    private fun mapProfileFilterToInternal(translatedFilter: String): String {
        return when (translatedFilter) {
            getString(io.github.dorumrr.de1984.R.string.filter_profile_all) -> "All"
            getString(io.github.dorumrr.de1984.R.string.filter_profile_personal) -> "Personal"
            getString(io.github.dorumrr.de1984.R.string.filter_profile_work) -> "Work"
            getString(io.github.dorumrr.de1984.R.string.filter_profile_clone) -> "Clone"
            else -> "All"
        }
    }

    private fun mapInternalToProfileFilter(internalFilter: String): String {
        return when (internalFilter) {
            "All" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_all)
            "Personal" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_personal)
            "Work" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_work)
            "Clone" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_clone)
            else -> getString(io.github.dorumrr.de1984.R.string.filter_profile_all)
        }
    }

    private fun showProtectionSnackbar(dialog: BottomSheetDialog) {
        val parentView = dialog.window?.decorView ?: requireView()
        Snackbar.make(
            parentView,
            getString(R.string.snackbar_firewall_protected),
            Snackbar.LENGTH_LONG
        ).setAction(getString(R.string.snackbar_action_settings)) {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToSettings()
        }.show()
    }


    private fun setupSelectionToolbar() {
        binding.selectionToolbar.setNavigationOnClickListener {
            exitSelectionMode()
        }

        binding.rulesButton.setOnClickListener {
            if (selectedPackages.isNotEmpty()) {
                showMultiSelectRulesSheet()
            }
        }
    }

    private fun setupBackPressHandler() {
        backPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    exitSelectionMode()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback!!)
    }

    private fun onPackageLongClick(pkg: NetworkPackage): Boolean {
        AppLogger.d(TAG, "🔘 Long click on package: ${pkg.packageName}")

        if (!adapter.canSelectPackage(pkg)) {
            // Long press is the ONLY way into selection mode, so this toast is the first thing the
            // user sees. It used to say "Critical/VPN packages cannot be selected" for a package
            // that carries neither badge, and pointed at a Settings switch that changes nothing for
            // a uid outside the app range. Pick the same reason the adapter picks on a tap.
            val backend = (requireActivity().application as De1984Application)
                .dependencies.firewallManager.activeBackendType.value
            val protectedBySetting = (pkg.isSystemCritical || pkg.isVpnApp) && !pkg.paintedAllowCritical
            val unblockable = !protectedBySetting && backend.blockingRefused(
                pkg, viewModel.uiState.value.blockingContext
            )
            val reason = if (unblockable) {
                R.string.firewall_multiselect_toast_cannot_select_system_uid
            } else {
                R.string.firewall_multiselect_toast_cannot_select_critical
            }
            Toast.makeText(requireContext(), getString(reason), Toast.LENGTH_SHORT).show()
            return true
        }

        if (!isSelectionMode) {
            enterSelectionMode()
        }

        adapter.selectPackage(pkg.id)
        return true
    }


    /**
     * Keep a multi-selection across an activity rebuild.
     *
     * Rotation no longer rebuilds (configChanges), but a LANGUAGE change does - and this app has a
     * language switcher - as does a dark-mode change. Selecting 40 apps for a batch action and
     * losing all of it to one of those is a real thing to lose.
     *
     * Stored as "packageName|userId" strings rather than making PackageId Parcelable: two fields,
     * and a Bundle of strings cannot drift out of step with a data class definition.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isSelectionMode) return
        outState.putBoolean(KEY_SELECTION_MODE, true)
        outState.putStringArrayList(
            KEY_SELECTED_PACKAGES,
            ArrayList(selectedPackages.map { "${it.packageName}|${it.userId}" })
        )
    }

    /** Rebuilds the selection saved by [onSaveInstanceState]. Bad entries are dropped, not fatal. */
    private fun restoreSelection(savedInstanceState: Bundle?) {
        if (savedInstanceState?.getBoolean(KEY_SELECTION_MODE) != true) return

        val restored = savedInstanceState.getStringArrayList(KEY_SELECTED_PACKAGES)
            .orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split("|")
                val userId = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 2 && parts[0].isNotEmpty() && userId != null) {
                    PackageId(parts[0], userId)
                } else {
                    null
                }
            }
            .toSet()

        if (restored.isEmpty()) return

        // Held, not applied here. observeSettingsState ALWAYS rebuilds the adapter on its first
        // emission - previousObservedShowIcons starts null, so iconsChanged is true - and that
        // branch calls exitSelectionMode() first. Applying the restore now means it is wiped about
        // 100 ms later; measured exactly that on hardware before this was held.
        // Held only. Do NOT apply here: observeSettingsState always rebuilds the adapter on its
        // first emission and calls exitSelectionMode() first, so anything applied now is wiped about
        // 100 ms later. Measured exactly that, twice, on hardware. The rebuild branch applies it.
        pendingRestoredSelection = restored
    }

    /**
     * Put a held restore onto the live adapter, once. Called from both places that can be "after the
     * adapter exists": here, and the end of the settings observer's rebuild branch. Whichever runs
     * second finds nothing left to do.
     */
    private fun applyPendingSelection() {
        val restored = pendingRestoredSelection ?: return
        if (!isAdded || _binding == null) return
        pendingRestoredSelection = null

        AppLogger.d(TAG, "Restoring ${restored.size} selected packages after a rebuild")
        enterSelectionMode()
        adapter.restoreSelection(restored)
    }

    private fun enterSelectionMode() {
        AppLogger.d(TAG, "🔘 Entering selection mode")
        isSelectionMode = true
        adapter.setSelectionMode(true)
        binding.selectionToolbar.visibility = View.VISIBLE
        backPressedCallback?.isEnabled = true
        updateSelectionToolbar()
    }

    private fun exitSelectionMode() {
        AppLogger.d(TAG, "🔘 Exiting selection mode")
        isSelectionMode = false
        selectedPackages.clear()
        adapter.setSelectionMode(false)
        binding.selectionToolbar.visibility = View.GONE
        backPressedCallback?.isEnabled = false
    }

    private fun updateSelectionToolbar() {
        val count = selectedPackages.size
        binding.selectionCount.text = getString(R.string.multiselect_toolbar_title_format, count)
    }

    private fun showBatchResultDialog(result: io.github.dorumrr.de1984.presentation.viewmodel.BatchBlockResult) {
        val actionName = if (result.wasBlocking) {
            getString(R.string.firewall_multiselect_toolbar_button_block).lowercase()
        } else {
            getString(R.string.firewall_multiselect_toolbar_button_allow).lowercase()
        }

        val message = if (result.failed.isEmpty()) {
            getString(R.string.firewall_multiselect_dialog_message_success_format, result.succeeded.size, actionName)
        } else {
            getString(R.string.firewall_multiselect_dialog_message_failed_format, result.succeeded.size, result.failed.size, actionName)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.firewall_multiselect_dialog_title_results))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }


    private enum class MultiSelectToggleState {
        ALL_BLOCKED,
        ALL_ALLOWED,
        MIXED
    }

    private fun calculateToggleState(
        packages: List<NetworkPackage>,
        getBlockedState: (NetworkPackage) -> Boolean
    ): MultiSelectToggleState {
        if (packages.isEmpty()) return MultiSelectToggleState.ALL_ALLOWED

        val blockedCount = packages.count { getBlockedState(it) }
        return when {
            blockedCount == packages.size -> MultiSelectToggleState.ALL_BLOCKED
            blockedCount == 0 -> MultiSelectToggleState.ALL_ALLOWED
            else -> MultiSelectToggleState.MIXED
        }
    }

    private fun showMultiSelectRulesSheet() {
        val dialog = BottomSheetDialog(requireContext())
        currentDialog = dialog

        val sheetBinding = BottomSheetFirewallMultiselectBinding.inflate(layoutInflater)

        val app = requireActivity().application as De1984Application
        val backendType = app.dependencies.firewallManager.activeBackendType.value
        val isIptablesBackend = backendType == FirewallBackendType.IPTABLES

        // Guarding entry into the selection is not enough. A package can be selected while it is
        // still reachable and become unreachable before the batch runs - the firewall starts, or a
        // privilege gain switches the backend - and restoreSelection() puts a saved selection back
        // without any check at all. So drop it here, where the batch is actually issued, and drop
        // exactly the unreachable ones: an earlier version intersected with the VISIBLE list
        // instead, which silently shrank every batch to whatever chip happened to be active.
        val unreachableIds = viewModel.unreachableSelection(selectedPackages)

        if (unreachableIds.isNotEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.firewall_multiselect_toast_cannot_select_system_uid),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Every batch action below reads THIS, never the raw selection.
        val actionableIds = selectedPackages.filterNot { it in unreachableIds }

        // Summarised over the set the taps actually WRITE to, from the unfiltered cache. The type,
        // profile and internet-only chips do not leave selection mode, so a selection made under
        // "All" outlives a switch to "System" - and the visible remnant was being counted and
        // summarised while every tap applied to the whole selection. Three different counts of one
        // selection were on screen at once.
        // asSaved: these drive switches that WRITE rules. Counting a neighbour's block as this
        // row's own left the sheet's switch already ON for a selection the user had not blocked,
        // and the batch guards then skipped those very rows - the sheet could neither block nor
        // unblock them.
        val selectedPkgs = actionableIds.mapNotNull { viewModel.enforcedPackage(it)?.asSaved() }

        if (selectedPkgs.isEmpty() || actionableIds.isEmpty()) {
            dialog.dismiss()
            return
        }

        sheetBinding.multiselectHeader.text = getString(R.string.firewall_multiselect_sheet_header_format, selectedPkgs.size)

        val telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasCellular = telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE
        val granular = supportsGranularControl()

        // One switch per app, not one per network. The WiFi row is reused as the single "Internet
        // Access" toggle and the rest are hidden, matching the single-app sheet and FIREWALL.md
        // section 3: "the UI should NOT show separate WiFi/Mobile/Roaming switches". See issue #72.
        if (!granular) {
            sheetBinding.mobileDivider.visibility = View.GONE
            sheetBinding.mobileToggle.root.visibility = View.GONE
        }

        val wifiState = if (!granular) {
            calculateToggleState(selectedPkgs) { it.wifiBlocked || it.mobileBlocked || it.roamingBlocked }
        } else {
            calculateToggleState(selectedPkgs) { it.wifiBlocked }
        }
        val mobileState = calculateToggleState(selectedPkgs) { it.mobileBlocked }
        val roamingState = calculateToggleState(selectedPkgs) { it.roamingBlocked }
        val lanState = calculateToggleState(selectedPkgs) { it.lanBlocked }

        setupMultiSelectToggleInitial(
            binding = sheetBinding.wifiToggle,
            label = if (granular) {
                getString(R.string.firewall_network_label_wifi)
            } else {
                getString(R.string.firewall_network_label_internet_access)
            },
            state = wifiState
        )

        if (granular) {
            setupMultiSelectToggleInitial(
                binding = sheetBinding.mobileToggle,
                label = getString(R.string.firewall_network_label_mobile),
                state = mobileState
            )
        }

        if (hasCellular && granular) {
            sheetBinding.roamingDivider.visibility = View.VISIBLE
            sheetBinding.roamingToggle.root.visibility = View.VISIBLE
            setupMultiSelectToggleInitial(
                binding = sheetBinding.roamingToggle,
                label = getString(R.string.firewall_network_label_roaming),
                state = roamingState
            )
        }

        if (isIptablesBackend) {
            sheetBinding.lanDivider.visibility = View.VISIBLE
            sheetBinding.lanToggle.root.visibility = View.VISIBLE
            setupMultiSelectToggleInitial(
                binding = sheetBinding.lanToggle,
                label = getString(R.string.firewall_network_label_lan),
                state = lanState
            )
        }

        sheetBinding.allowAllButton.setOnClickListener {
            viewModel.batchAllowPackages(actionableIds)
            dialog.dismiss()
            exitSelectionMode()
        }

        sheetBinding.blockAllButton.setOnClickListener {
            viewModel.batchBlockPackages(actionableIds)
            dialog.dismiss()
            exitSelectionMode()
        }

        var isUpdatingProgrammatically = false

        fun updateTogglesFromPackages(packages: List<NetworkPackage>) {
            if (packages.isEmpty()) return
            isUpdatingProgrammatically = true

            val newWifiState = if (!granular) {
                calculateToggleState(packages) { it.wifiBlocked || it.mobileBlocked || it.roamingBlocked }
            } else {
                calculateToggleState(packages) { it.wifiBlocked }
            }
            val newMobileState = calculateToggleState(packages) { it.mobileBlocked }
            val newRoamingState = calculateToggleState(packages) { it.roamingBlocked }
            val newLanState = calculateToggleState(packages) { it.lanBlocked }

            updateMultiSelectToggleState(sheetBinding.wifiToggle, newWifiState)

            if (granular) {
                updateMultiSelectToggleState(sheetBinding.mobileToggle, newMobileState)
            }

            if (hasCellular && granular) {
                updateMultiSelectToggleState(sheetBinding.roamingToggle, newRoamingState)
            }

            if (isIptablesBackend) {
                updateMultiSelectToggleState(sheetBinding.lanToggle, newLanState)
            }

            isUpdatingProgrammatically = false
        }

        fun getSelectedPackagePairs(): List<Pair<String, Int>> {
            return actionableIds.map { it.packageName to it.userId }
        }

        sheetBinding.wifiToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            sheetBinding.wifiToggle.networkTypeSubtitle.visibility = View.GONE
            updateSwitchColors(sheetBinding.wifiToggle.toggleSwitch, isChecked)
            if (granular) {
                viewModel.batchSetWifiBlocking(getSelectedPackagePairs(), isChecked)
            } else {
                viewModel.batchSetAllNetworkBlocking(getSelectedPackagePairs(), isChecked)
            }
        }

        if (granular) {
            sheetBinding.mobileToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
                sheetBinding.mobileToggle.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(sheetBinding.mobileToggle.toggleSwitch, isChecked)
                viewModel.batchSetMobileBlocking(getSelectedPackagePairs(), isChecked)
            }
        }

        if (hasCellular && granular) {
            sheetBinding.roamingToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
                sheetBinding.roamingToggle.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(sheetBinding.roamingToggle.toggleSwitch, isChecked)
                viewModel.batchSetRoamingBlocking(getSelectedPackagePairs(), isChecked)
            }
        }

        if (isIptablesBackend) {
            sheetBinding.lanToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
                sheetBinding.lanToggle.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(sheetBinding.lanToggle.toggleSwitch, isChecked)
                viewModel.batchSetLanBlocking(getSelectedPackagePairs(), isChecked)
            }
        }

        // The sheet's own record of the selection, seeded with everything the user picked.
        //
        // It is MERGED on each emit, never rebuilt by filtering. state.packages is the FILTERED
        // list, so a package that leaves the filter - which is exactly what happens when a toggle
        // in this sheet changes its blocked state under a Blocked/Allowed filter - vanishes from
        // it. Rebuilding by filter then recomputed the toggles from whatever survived, so the
        // switches showed the state of PART of the selection while every tap still applied to ALL
        // of it, via getSelectedPackagePairs().
        val trackedSelection = LinkedHashMap<PackageId, NetworkPackage>().apply {
            selectedPkgs.forEach { put(it.id, it) }
        }

        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                var changed = false
                val seenThisEmit = mutableSetOf<PackageId>()
                // asSaved, matching how trackedSelection was seeded. state.packages carries the
                // ENFORCED flags, so merging them raw repainted every switch from a neighbour's
                // block on the first emission - undoing the seeding one frame after the sheet
                // opened. These switches write rules, so they must show the rules they write.
                state.packages.forEach { masked ->
                    val pkg = masked.asSaved()
                    if (trackedSelection.containsKey(pkg.id)) {
                        if (trackedSelection[pkg.id] != pkg) changed = true
                        trackedSelection[pkg.id] = pkg
                        seenThisEmit.add(pkg.id)
                    }
                }

                // Aggregate over what we can still SEE. A selected app that has dropped out of the
                // filtered list - which is exactly what a toggle in this sheet does under a
                // Blocked/Allowed filter - would otherwise keep voting with its last-seen values and
                // drag the switch the user just moved back to "Mixed". Writes are unaffected: they go
                // through getSelectedPackagePairs(), which reads the full selection.
                val visible = trackedSelection.filterKeys { it in seenThisEmit }.values.toList()

                if (changed && visible.isNotEmpty() && !isUpdatingProgrammatically) {
                    AppLogger.d(TAG, "showMultiSelectRulesSheet: uiState collected - updating from ${visible.size} of ${trackedSelection.size} selected")
                    updateTogglesFromPackages(visible)
                }
            }
        }

        dialog.setOnDismissListener {
            AppLogger.d(TAG, "showMultiSelectRulesSheet: Dialog dismissed, cancelling observer")
            observerJob.cancel()
            if (currentDialog == dialog) {
                currentDialog = null
            }
        }

        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    private fun updateMultiSelectToggleState(
        binding: NetworkTypeToggleBinding,
        state: MultiSelectToggleState
    ) {
        when (state) {
            MultiSelectToggleState.ALL_BLOCKED -> {
                binding.toggleSwitch.isChecked = true
                binding.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(binding.toggleSwitch, true)
            }
            MultiSelectToggleState.ALL_ALLOWED -> {
                binding.toggleSwitch.isChecked = false
                binding.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(binding.toggleSwitch, false)
            }
            MultiSelectToggleState.MIXED -> {
                binding.toggleSwitch.isChecked = false
                binding.networkTypeSubtitle.visibility = View.VISIBLE
                binding.networkTypeSubtitle.text = getString(R.string.firewall_multiselect_sheet_state_mixed)
                updateSwitchColors(binding.toggleSwitch, false)
            }
        }
    }

    /**
     * Setup a network toggle for multi-select mode - initial state only (no listener).
     * Listener is added separately to support the isUpdatingProgrammatically flag.
     */
    private fun setupMultiSelectToggleInitial(
        binding: NetworkTypeToggleBinding,
        label: String,
        state: MultiSelectToggleState
    ) {
        binding.networkTypeLabel.text = label
        binding.labelLeft.text = getString(R.string.firewall_state_allowed)
        binding.labelRight.text = getString(R.string.firewall_state_blocked)
        binding.toggleSwitch.isEnabled = true

        when (state) {
            MultiSelectToggleState.ALL_BLOCKED -> {
                binding.toggleSwitch.isChecked = true
                binding.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(binding.toggleSwitch, true)
            }
            MultiSelectToggleState.ALL_ALLOWED -> {
                binding.toggleSwitch.isChecked = false
                binding.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(binding.toggleSwitch, false)
            }
            MultiSelectToggleState.MIXED -> {
                binding.toggleSwitch.isChecked = false
                binding.networkTypeSubtitle.visibility = View.VISIBLE
                binding.networkTypeSubtitle.text = getString(R.string.firewall_multiselect_sheet_state_mixed)
                updateSwitchColors(binding.toggleSwitch, false)
            }
        }
    }

    /**
     * ConnectivityManager and NetworkPolicyManager have one switch per app, not one per network.
     * On those the row's WiFi/Mobile/Roaming icons cannot mean three separate things, so a tap on
     * any of them has to act on all three - the same thing the bottom sheet's single "Internet
     * Access" switch does. See issue #72.
     */
    private fun supportsGranularControl(): Boolean {
        val app = requireActivity().application as De1984Application
        return app.dependencies.firewallManager.supportsGranularControl()
    }

    private fun handleQuickToggle(pkg: NetworkPackage, networkType: NetworkType) {
        val prefs = requireContext().getSharedPreferences(
            Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val confirmRuleChanges = prefs.getBoolean(
            Constants.Settings.KEY_CONFIRM_RULE_CHANGES,
            Constants.Settings.DEFAULT_CONFIRM_RULE_CHANGES
        )

        // Read once and pass it down. Evaluated again at each step, the confirmation dialog could
        // describe one action and the OK button perform another - the backend can change between
        // showing the dialog and pressing it.
        val granular = supportsGranularControl()

        // The row's OWN rule decides which way this tap goes. Reading the displayed flags on a row
        // a neighbour blocks made willBlock permanently false, so the toggle could only ever push
        // toward allow - and where the user's own rule also blocked, each tap silently cleared and
        // restored that block while the row never moved.
        val saved = pkg.asSaved()
        val isCurrentlyBlocked = if (!granular) {
            saved.wifiBlocked || saved.mobileBlocked || saved.roamingBlocked
        } else when (networkType) {
            NetworkType.WIFI -> saved.wifiBlocked
            NetworkType.MOBILE -> saved.mobileBlocked
            NetworkType.ROAMING -> saved.roamingBlocked
        }
        val willBlock = !isCurrentlyBlocked

        if (confirmRuleChanges) {
            showQuickToggleConfirmationDialog(pkg, networkType, willBlock, granular)
        } else {
            executeQuickToggle(pkg, networkType, willBlock, showSnackbar = true, granular = granular)
        }
    }

    private fun showQuickToggleConfirmationDialog(
        pkg: NetworkPackage,
        networkType: NetworkType,
        willBlock: Boolean,
        granular: Boolean
    ) {
        val networkTypeName = if (!granular) {
            getString(R.string.firewall_network_label_internet_access)
        } else when (networkType) {
            NetworkType.WIFI -> getString(R.string.firewall_network_label_wifi)
            NetworkType.MOBILE -> getString(R.string.firewall_network_label_mobile)
            NetworkType.ROAMING -> getString(R.string.firewall_network_label_roaming)
        }
        val title = if (willBlock) {
            getString(R.string.dialog_quick_toggle_block_title, networkTypeName)
        } else {
            getString(R.string.dialog_quick_toggle_allow_title, networkTypeName)
        }

        val action = if (willBlock) {
            getString(R.string.dialog_quick_toggle_action_block)
        } else {
            getString(R.string.dialog_quick_toggle_action_allow)
        }

        val message = if (!granular) {
            getString(R.string.dialog_quick_toggle_message_internet, action, pkg.name)
        } else when (networkType) {
            NetworkType.WIFI -> getString(R.string.dialog_quick_toggle_message_wifi, action, pkg.name)
            NetworkType.MOBILE -> getString(R.string.dialog_quick_toggle_message_mobile, action, pkg.name)
            NetworkType.ROAMING -> getString(R.string.dialog_quick_toggle_message_roaming, action, pkg.name)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                executeQuickToggle(pkg, networkType, willBlock, showSnackbar = false, granular = granular)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeQuickToggle(
        pkg: NetworkPackage,
        networkType: NetworkType,
        willBlock: Boolean,
        showSnackbar: Boolean,
        granular: Boolean
    ) {
        AppLogger.d(TAG, "🔘 QUICK TOGGLE: ${networkType.name} for ${pkg.packageName} - willBlock: $willBlock")

        if (!granular) {
            viewModel.setAllNetworkBlocking(pkg.packageName, pkg.userId, willBlock)
        } else when (networkType) {
            NetworkType.WIFI -> viewModel.setWifiBlocking(pkg.packageName, pkg.userId, willBlock)
            NetworkType.MOBILE -> viewModel.setMobileBlocking(pkg.packageName, pkg.userId, willBlock)
            NetworkType.ROAMING -> viewModel.setRoamingBlocking(pkg.packageName, pkg.userId, willBlock)
        }

        if (showSnackbar) {
            showQuickToggleSnackbar(pkg, networkType, willBlock, granular)
        }
    }

    private fun showQuickToggleSnackbar(
        pkg: NetworkPackage,
        networkType: NetworkType,
        wasBlocked: Boolean,
        granular: Boolean
    ) {
        val message = if (!granular) {
            if (wasBlocked) {
                getString(R.string.snackbar_internet_blocked, pkg.name)
            } else {
                getString(R.string.snackbar_internet_allowed, pkg.name)
            }
        } else when (networkType) {
            NetworkType.WIFI -> if (wasBlocked) {
                getString(R.string.snackbar_wifi_blocked, pkg.name)
            } else {
                getString(R.string.snackbar_wifi_allowed, pkg.name)
            }
            NetworkType.MOBILE -> if (wasBlocked) {
                getString(R.string.snackbar_mobile_blocked, pkg.name)
            } else {
                getString(R.string.snackbar_mobile_allowed, pkg.name)
            }
            NetworkType.ROAMING -> if (wasBlocked) {
                getString(R.string.snackbar_roaming_blocked, pkg.name)
            } else {
                getString(R.string.snackbar_roaming_allowed, pkg.name)
            }
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.snackbar_undo)) {
                AppLogger.d(TAG, "🔄 UNDO QUICK TOGGLE: ${networkType.name} for ${pkg.packageName}")
                if (!granular) {
                    viewModel.setAllNetworkBlocking(pkg.packageName, pkg.userId, !wasBlocked)
                } else when (networkType) {
                    NetworkType.WIFI -> viewModel.setWifiBlocking(pkg.packageName, pkg.userId, !wasBlocked)
                    NetworkType.MOBILE -> viewModel.setMobileBlocking(pkg.packageName, pkg.userId, !wasBlocked)
                    NetworkType.ROAMING -> viewModel.setRoamingBlocking(pkg.packageName, pkg.userId, !wasBlocked)
                }
            }
            .show()
    }

    companion object {
        private const val KEY_SELECTION_MODE = "selection_mode"
        private const val KEY_SELECTED_PACKAGES = "selected_packages"
        private const val TAG = "FirewallFragmentViews"
    }
}

