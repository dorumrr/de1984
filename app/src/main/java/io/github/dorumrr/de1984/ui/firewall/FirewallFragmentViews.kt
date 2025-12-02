package io.github.dorumrr.de1984.ui.firewall

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
import androidx.fragment.app.viewModels
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
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.presentation.viewmodel.FirewallViewModel
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.ui.common.FilterChipsHelper
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.copyToClipboard
import io.github.dorumrr.de1984.utils.openAppSettings
import io.github.dorumrr.de1984.utils.setOnClickListenerDebounced
import kotlinx.coroutines.launch

/**
 * Firewall Fragment using XML Views
 * 
 * Features:
 * - Filter chips for package type and network state
 * - RecyclerView with network packages
 * - Click to toggle allow/block
 * - Empty and loading states
 */
class FirewallFragmentViews : BaseFragment<FragmentFirewallBinding>() {

    private val TAG = "FirewallFragmentViews"

    private val viewModel: FirewallViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        FirewallViewModel.Factory(
            app,
            app.dependencies.provideGetNetworkPackagesUseCase(),
            app.dependencies.provideManageNetworkAccessUseCase(),
            app.dependencies.superuserBannerState,
            app.dependencies.permissionManager,
            app.dependencies.firewallManager
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

    // Track previous policy to detect changes across lifecycle events
    private var previousObservedPolicy: String? = null
    private var lastSubmittedPackages: List<NetworkPackage> = emptyList()

    // Dialog tracking to prevent multiple dialogs from stacking
    private var currentDialog: BottomSheetDialog? = null
    private var dialogOpenTimestamp: Long = 0
    private var pendingDialogPackageName: String? = null  // Track which package we're waiting to show

    // Selection mode state
    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<String>()
    private var backPressedCallback: OnBackPressedCallback? = null

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentFirewallBinding.inflate(inflater, container, false)

    override fun scrollToTop() {
        // Only scroll if binding is available (fragment view is created)
        _binding?.packagesRecyclerView?.scrollToPosition(0)
    }

    /**
     * Scroll to a specific package in the list.
     * Used for cross-navigation to keep the same app in view.
     */
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

        // Show loading state immediately until first state emission
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

        // Refresh default policy on start
        // Note: Don't load packages here - let ViewModel's init{} handle first load
        viewModel.refreshDefaultPolicy()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        AppLogger.d(TAG, "onHiddenChanged: hidden=$hidden")

        if (!hidden) {
            // Fragment became visible - check if policy changed while we were hidden
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
        }
    }

    private fun setupRecyclerView() {
        adapter = NetworkPackageAdapter(
            showIcons = true, // Will be updated from settings
            onPackageClick = { pkg ->
                showPackageActionSheet(pkg)
            },
            onPackageLongClick = { pkg ->
                onPackageLongClick(pkg)
            }
        )

        // Setup selection listeners
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
        // Get translated filter strings
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

        // Initial setup - only called once
        currentTypeFilter = getString(io.github.dorumrr.de1984.R.string.packages_filter_all)
        currentStateFilter = null
        currentPermissionFilter = true

        FilterChipsHelper.setupMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            typeFilters = packageTypeFilters,
            stateFilters = networkStateFilters,
            permissionFilters = permissionFilters,
            selectedTypeFilter = currentTypeFilter,
            selectedStateFilter = currentStateFilter,
            selectedPermissionFilter = currentPermissionFilter,
            onTypeFilterSelected = { filter ->
                if (filter != currentTypeFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Package type filter changed: $filter")
                    currentTypeFilter = filter
                    // Map translated string to internal constant
                    val internalFilter = mapTypeFilterToInternal(filter)
                    viewModel.setPackageTypeFilter(internalFilter)
                }
            },
            onStateFilterSelected = { filter ->
                if (filter != currentStateFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Network state filter changed: ${filter ?: "none"}")

                    // Exit selection mode when state filter changes
                    if (isSelectionMode) {
                        AppLogger.d(TAG, "🔘 Exiting selection mode due to state filter change")
                        exitSelectionMode()
                    }

                    currentStateFilter = filter
                    // Map translated string to internal constant
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
            }
        )
    }

    private fun setupSearchBox() {
        // Initially hide clear icon
        binding.searchLayout.isEndIconVisible = false

        // Text change listener for real-time search
        binding.searchInput.addTextChangedListener { text ->
            val query = text?.toString() ?: ""
            if (query.isNotEmpty()) {
                AppLogger.d(TAG, "🔍 USER ACTION: Search query changed: '$query'")
            }
            viewModel.setSearchQuery(query)

            // Show/hide clear icon based on text length
            binding.searchLayout.isEndIconVisible = query.isNotEmpty()
        }

        // Clear icon click listener
        binding.searchLayout.setEndIconOnClickListener {
            AppLogger.d(TAG, "🔘 USER ACTION: Search cleared")
            binding.searchInput.text?.clear()
            binding.searchLayout.isEndIconVisible = false
        }

        // Handle keyboard "Search" or "Done" button
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboardAndClearFocus()
                true
            } else {
                false
            }
        }

        // Clear focus and hide keyboard when touching/scrolling RecyclerView
        binding.packagesRecyclerView.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (binding.searchInput.hasFocus()) {
                    hideKeyboardAndClearFocus()
                    view.requestFocus()
                }
            }
            false // Allow touch events to propagate for normal scrolling
        }

        // Clear focus when scrolling RecyclerView
        binding.packagesRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (binding.searchInput.hasFocus()) {
                        hideKeyboardAndClearFocus()
                    }
                }
            }
        })

        // Clear focus when clicking on root container (outside search box)
        binding.rootContainer.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                // Check if touch is outside the search layout
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
            false // Allow touch events to propagate
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
        internetOnlyFilter: Boolean
    ) {
        // Map internal constants to translated strings
        val translatedTypeFilter = mapInternalToTypeFilter(packageTypeFilter)
        val translatedStateFilter = networkStateFilter?.let { mapInternalToStateFilter(it) }

        // Only update if filters have changed
        if (translatedTypeFilter == currentTypeFilter &&
            translatedStateFilter == currentStateFilter &&
            internetOnlyFilter == currentPermissionFilter) {
            return
        }

        currentTypeFilter = translatedTypeFilter
        currentStateFilter = translatedStateFilter
        currentPermissionFilter = internetOnlyFilter

        // Update chip selection without recreating or triggering listeners
        FilterChipsHelper.updateMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            selectedTypeFilter = translatedTypeFilter,
            selectedStateFilter = translatedStateFilter,
            selectedPermissionFilter = internetOnlyFilter
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

    private fun observeSettingsState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.uiState.collect { settingsState ->
                    AppLogger.d(TAG, "observeSettingsState: settingsState changed - showAppIcons=${settingsState.showAppIcons}, defaultFirewallPolicy=${settingsState.defaultFirewallPolicy}")
                    AppLogger.d(TAG, "observeSettingsState: previousObservedPolicy=$previousObservedPolicy, newPolicy=${settingsState.defaultFirewallPolicy}")

                    // Exit selection mode before recreating adapter
                    if (isSelectionMode) {
                        AppLogger.d(TAG, "observeSettingsState: Exiting selection mode before adapter recreation")
                        exitSelectionMode()
                    }

                    // Update adapter when showIcons setting changes
                    adapter = NetworkPackageAdapter(
                        showIcons = settingsState.showAppIcons,
                        onPackageClick = { pkg ->
                            showPackageActionSheet(pkg)
                        },
                        onPackageLongClick = { pkg ->
                            onPackageLongClick(pkg)
                        }
                    )

                    // Setup selection listeners for new adapter
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

                    // Reset last submitted packages when creating new adapter
                    lastSubmittedPackages = emptyList()

                    // If default policy changed, refresh packages to reflect new blocking states
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

                    // Trigger updateUI to re-apply filters and submit to new adapter
                    updateUI(viewModel.uiState.value)
                }
            }
        }
    }

    private fun updateUI(state: io.github.dorumrr.de1984.presentation.viewmodel.FirewallUiState) {
        // Update visibility based on state
        if (state.isLoadingData && state.packages.isEmpty()) {
            binding.packagesRecyclerView.visibility = View.INVISIBLE  // INVISIBLE instead of GONE
            binding.loadingState.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        } else if (state.packages.isEmpty()) {
            binding.packagesRecyclerView.visibility = View.INVISIBLE
            binding.loadingState.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.packagesRecyclerView.visibility = View.VISIBLE
            binding.loadingState.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }

        // Update filter chips
        updateFilterChips(
            packageTypeFilter = state.filterState.packageType,
            networkStateFilter = state.filterState.networkState,
            internetOnlyFilter = state.filterState.internetOnly
        )

        // Handle batch operation results
        state.batchBlockResult?.let { result ->
            showBatchResultDialog(result)
            viewModel.clearBatchBlockResult()
        }

        // Apply search filtering with partial substring matching (app name only)
        val displayedPackages = if (state.searchQuery.isBlank()) {
            state.packages
        } else {
            val query = state.searchQuery.lowercase()
            state.packages.filter { pkg ->
                pkg.name.lowercase().contains(query, ignoreCase = false)
            }
        }

        // Update package count in search field
        // Hide count if 0 results AND no search query (empty state)
        val count = displayedPackages.size
        binding.searchLayout.suffixText = if (count == 0 && state.searchQuery.isBlank()) {
            ""
        } else {
            resources.getQuantityString(
                R.plurals.package_count,
                count,
                count
            )
        }

        // Update RecyclerView only if list changed
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
    /**
     * Open the firewall dialog for a specific app by package name.
     * Used for cross-navigation from other screens.
     */
    fun openAppDialog(packageName: String) {
        // Prevent multiple dialogs from stacking
        if (currentDialog?.isShowing == true) {
            AppLogger.w(TAG, "[FIREWALL] Dialog already open, dismissing before opening new one")
            currentDialog?.dismiss()
            currentDialog = null
        }

        // Find the package in the current list
        val pkg = viewModel.uiState.value.packages.find { it.packageName == packageName }

        if (pkg != null) {
            pendingDialogPackageName = null
            scrollToPackage(packageName)
            showPackageActionSheet(pkg)
        } else {
            // Package not in filtered list - need to load it and possibly change filter
            pendingDialogPackageName = packageName

            // Package not in filtered list - try to get it directly from repository
            lifecycleScope.launch {
                try {
                    // Get package from repository (bypasses filter)
                    val app = requireActivity().application as De1984Application
                    val networkPackageRepository = app.dependencies.networkPackageRepository
                    val result = networkPackageRepository.getNetworkPackage(packageName)

                    result.onSuccess { foundPkg ->
                        // Check if this request is still valid
                        if (pendingDialogPackageName != packageName) {
                            return@onSuccess
                        }

                        // Check if we need to change filter to show this package
                        val currentFilter = viewModel.uiState.value.filterState.packageType
                        val packageType = foundPkg.type.toString()

                        if (currentFilter.equals(packageType, ignoreCase = true)) {
                            // Filter already matches - just wait for data to load
                            viewModel.uiState.collect { state ->
                                if (pendingDialogPackageName != packageName) {
                                    return@collect
                                }

                                val foundPackage = state.packages.find { it.packageName == packageName }
                                if (foundPackage != null) {
                                    pendingDialogPackageName = null
                                    scrollToPackage(packageName)
                                    showPackageActionSheet(foundPackage)
                                    return@collect
                                }
                            }
                        } else {
                            // Need to change filter
                            viewModel.setPackageTypeFilter(packageType)

                            // Wait for filter change and data load
                            viewModel.uiState.collect { state ->
                                if (pendingDialogPackageName != packageName) {
                                    return@collect
                                }

                                if (state.filterState.packageType.equals(packageType, ignoreCase = true) && !state.isLoading) {
                                    val foundPackage = state.packages.find { it.packageName == packageName }
                                    if (foundPackage != null) {
                                        pendingDialogPackageName = null
                                        scrollToPackage(packageName)
                                        showPackageActionSheet(foundPackage)
                                        return@collect
                                    }
                                }
                            }
                        }
                    }.onFailure { error ->
                        AppLogger.e(TAG, "Failed to load package for dialog: ${error.message}")
                        if (pendingDialogPackageName == packageName) {
                            pendingDialogPackageName = null
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Exception opening dialog: ${e.message}")
                    if (pendingDialogPackageName == packageName) {
                        pendingDialogPackageName = null
                    }
                }
            }
        }
    }

    private fun showPackageActionSheet(pkg: NetworkPackage) {
        val dialog = BottomSheetDialog(requireContext())
        currentDialog = dialog

        // Get FirewallManager from application dependencies
        val app = requireActivity().application as De1984Application
        val firewallManager = app.dependencies.firewallManager

        // Check if current backend supports granular control
        val supportsGranular = firewallManager.supportsGranularControl()

        if (supportsGranular) {
            showGranularControlSheet(dialog, pkg)
        } else {
            showSimpleControlSheet(dialog, pkg)
        }
    }

    private fun showGranularControlSheet(dialog: BottomSheetDialog, pkg: NetworkPackage) {
        AppLogger.d(TAG, "showGranularControlSheet: ENTRY - pkg=${pkg.packageName}, dialog=$dialog")
        val binding = BottomSheetPackageActionGranularBinding.inflate(layoutInflater)

        // Check if device has cellular capability
        val telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasCellular = telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE

        // Setup header
        try {
            val pm = requireContext().packageManager
            val appInfo = pm.getApplicationInfo(pkg.packageName, 0)
            val icon = pm.getApplicationIcon(appInfo)
            binding.actionSheetAppIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            binding.actionSheetAppIcon.setImageResource(R.drawable.de1984_icon)
        }
        binding.actionSheetAppName.text = pkg.name
        binding.actionSheetPackageName.text = pkg.packageName

        // ============================================================================
        // Click package name to copy to clipboard
        // ============================================================================
        binding.actionSheetPackageName.setOnClickListenerDebounced {
            requireContext().copyToClipboard(pkg.packageName, getString(R.string.clipboard_label_package_name))
        }

        // ============================================================================
        // Click settings icon to open Android system settings
        // ============================================================================
        binding.actionSheetSettingsIcon.setOnClickListener {
            requireContext().openAppSettings(pkg.packageName)
            dialog.dismiss()
        }

        // Always show roaming toggle if device has cellular
        if (hasCellular) {
            binding.roamingDivider.visibility = View.VISIBLE
            binding.roamingToggle.root.visibility = View.VISIBLE
        } else {
            binding.roamingDivider.visibility = View.GONE
            binding.roamingToggle.root.visibility = View.GONE
        }

        // Flag to prevent infinite recursion when updating switches programmatically
        var isUpdatingProgrammatically = false

        // Function to update UI toggles based on current package state
        fun updateTogglesFromPackage(currentPkg: NetworkPackage) {
            AppLogger.d(TAG, "updateTogglesFromPackage: pkg=${currentPkg.packageName}, wifi=${currentPkg.wifiBlocked}, mobile=${currentPkg.mobileBlocked}, roaming=${currentPkg.roamingBlocked}, background=${currentPkg.backgroundBlocked}, isFullyBlocked=${currentPkg.isFullyBlocked}")
            isUpdatingProgrammatically = true

            // Update WiFi toggle
            binding.wifiToggle.toggleSwitch.isChecked = currentPkg.wifiBlocked
            updateSwitchColors(binding.wifiToggle.toggleSwitch, currentPkg.wifiBlocked)

            // Update Mobile toggle
            binding.mobileToggle.toggleSwitch.isChecked = currentPkg.mobileBlocked
            updateSwitchColors(binding.mobileToggle.toggleSwitch, currentPkg.mobileBlocked)

            // Update Roaming toggle (if device has cellular)
            if (hasCellular) {
                binding.roamingToggle.toggleSwitch.isChecked = currentPkg.roamingBlocked
                updateSwitchColors(binding.roamingToggle.toggleSwitch, currentPkg.roamingBlocked)
            }

            // Update LAN toggle - always update checked state, only update colors if enabled (iptables)
            val app = requireActivity().application as De1984Application
            val backendType = app.dependencies.firewallManager.activeBackendType.value
            binding.lanToggle.toggleSwitch.isChecked = currentPkg.lanBlocked
            if (backendType == FirewallBackendType.IPTABLES) {
                updateSwitchColors(binding.lanToggle.toggleSwitch, currentPkg.lanBlocked)
            }

            // Update Background toggle visibility based on current blocking state
            val allowCriticalForUpdate = settingsViewModel.uiState.value.allowCriticalPackageFirewall
            val shouldShowBackgroundAccess = (!currentPkg.isSystemCritical || allowCriticalForUpdate) && (!currentPkg.isVpnApp || allowCriticalForUpdate) && !currentPkg.isFullyBlocked
            val wasBackgroundToggleVisible = binding.foregroundOnlyToggle.root.visibility == View.VISIBLE
            AppLogger.d(TAG, "updateTogglesFromPackage: shouldShowBackgroundAccess=$shouldShowBackgroundAccess, wasVisible=$wasBackgroundToggleVisible (isSystemCritical=${currentPkg.isSystemCritical}, isVpnApp=${currentPkg.isVpnApp}, isFullyBlocked=${currentPkg.isFullyBlocked})")

            binding.foregroundOnlyDivider.visibility = if (shouldShowBackgroundAccess) View.VISIBLE else View.GONE
            binding.foregroundOnlyToggle.root.visibility = if (shouldShowBackgroundAccess) View.VISIBLE else View.GONE

            // Update Background toggle state if visible
            if (shouldShowBackgroundAccess) {
                // If toggle just became visible, we need to set up the listener
                if (!wasBackgroundToggleVisible) {
                    AppLogger.d(TAG, "updateTogglesFromPackage: Background toggle just became visible - setting up listener")
                    setupNetworkToggle(
                        binding = binding.foregroundOnlyToggle,
                        label = getString(R.string.firewall_network_label_background_access),
                        isBlocked = !currentPkg.backgroundBlocked,
                        enabled = true,
                        invertLabels = true,
                        onToggle = { isChecked ->
                            if (isUpdatingProgrammatically) return@setupNetworkToggle
                            AppLogger.d(TAG, "updateTogglesFromPackage: Background toggle clicked - isChecked=$isChecked, setting backgroundBlocked=${!isChecked}")
                            viewModel.setBackgroundBlocking(currentPkg.packageName, !isChecked)
                        }
                    )
                } else {
                    // Toggle was already visible, just update the state
                    binding.foregroundOnlyToggle.toggleSwitch.isChecked = !currentPkg.backgroundBlocked
                    updateSwitchColors(binding.foregroundOnlyToggle.toggleSwitch, !currentPkg.backgroundBlocked, invertColors = true)
                }
                AppLogger.d(TAG, "updateTogglesFromPackage: Background toggle updated - isChecked=${!currentPkg.backgroundBlocked}")
            }

            isUpdatingProgrammatically = false
        }

        // Observe package changes to update UI when ViewModel makes cascading changes
        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val updatedPkg = state.packages.find { it.packageName == pkg.packageName }
                AppLogger.d(TAG, "showGranularControlSheet: uiState collected - updatedPkg found=${updatedPkg != null}, isUpdatingProgrammatically=$isUpdatingProgrammatically")
                if (updatedPkg != null && !isUpdatingProgrammatically) {
                    AppLogger.d(TAG, "showGranularControlSheet: Calling updateTogglesFromPackage for ${updatedPkg.packageName}")
                    updateTogglesFromPackage(updatedPkg)
                } else if (updatedPkg != null) {
                    AppLogger.d(TAG, "showGranularControlSheet: Skipping update (isUpdatingProgrammatically=true)")
                }
            }
        }

        // Cancel observer when dialog is dismissed
        dialog.setOnDismissListener {
            AppLogger.d(TAG, "showGranularControlSheet: Dialog dismissed, cancelling observer for ${pkg.packageName}")
            observerJob.cancel()
            if (currentDialog == dialog) {
                currentDialog = null
            }
        }

        // Setup protection warning banner
        val allowCritical = settingsViewModel.uiState.value.allowCriticalPackageFirewall
        val isProtected = (pkg.isSystemCritical || pkg.isVpnApp) && !allowCritical
        if (isProtected) {
            binding.protectionWarningBanner.root.visibility = View.VISIBLE

            // Set banner message based on package type
            val bannerMessage = when {
                pkg.isSystemCritical -> getString(R.string.protection_banner_message_firewall_system)
                pkg.isVpnApp -> getString(R.string.protection_banner_message_firewall_vpn)
                else -> getString(R.string.protection_banner_message_firewall)
            }

            // Set the message text
            binding.protectionWarningBanner.bannerMessage.text = bannerMessage

            // Setup Settings button click listener
            binding.protectionWarningBanner.bannerSettingsButton.setOnClickListener {
                dialog.dismiss()
                (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToSettings()
            }
        } else {
            binding.protectionWarningBanner.root.visibility = View.GONE
        }

        // Setup WiFi toggle
        setupNetworkToggle(
            binding = binding.wifiToggle,
            label = getString(R.string.firewall_network_label_wifi),
            isBlocked = pkg.wifiBlocked,
            enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
                AppLogger.d(TAG, "🔘 USER ACTION: WiFi toggle changed for ${pkg.packageName} - blocked: $blocked")
                viewModel.setWifiBlocking(pkg.packageName, blocked)
            }
        )

        // Setup Mobile Data toggle
        setupNetworkToggle(
            binding = binding.mobileToggle,
            label = getString(R.string.firewall_network_label_mobile),
            isBlocked = pkg.mobileBlocked,
            enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
                AppLogger.d(TAG, "🔘 USER ACTION: Mobile toggle changed for ${pkg.packageName} - blocked: $blocked")

                // ViewModel handles mobile+roaming dependency atomically
                viewModel.setMobileBlocking(pkg.packageName, blocked)
            }
        )

        // Setup Roaming toggle (only if device has cellular)
        if (hasCellular) {
            setupNetworkToggle(
                binding = binding.roamingToggle,
                label = getString(R.string.firewall_network_label_roaming),
                isBlocked = pkg.roamingBlocked,
                enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
                onToggle = { blocked ->
                    if (isUpdatingProgrammatically) return@setupNetworkToggle
                    AppLogger.d(TAG, "🔘 USER ACTION: Roaming toggle changed for ${pkg.packageName} - blocked: $blocked")

                    // ViewModel handles mobile+roaming dependency atomically
                    viewModel.setRoamingBlocking(pkg.packageName, blocked)
                }
            )
        }

        // Setup LAN toggle - always visible, disabled when not using iptables backend
        val appForLan = requireActivity().application as De1984Application
        val backendTypeForLan = appForLan.dependencies.firewallManager.activeBackendType.value
        val isIptablesBackend = backendTypeForLan == FirewallBackendType.IPTABLES

        // Always show LAN toggle
        binding.lanDivider.visibility = View.VISIBLE
        binding.lanToggle.root.visibility = View.VISIBLE

        // Show "Requires root access" subtitle when LAN blocking is unavailable
        if (!isIptablesBackend) {
            binding.lanToggle.root.alpha = 0.6f
            binding.lanToggle.networkTypeSubtitle.visibility = View.VISIBLE
            binding.lanToggle.networkTypeSubtitle.text = getString(R.string.firewall_lan_requires_root)
        }

        setupNetworkToggle(
            binding = binding.lanToggle,
            label = getString(R.string.firewall_network_label_lan),
            isBlocked = pkg.lanBlocked,
            enabled = isIptablesBackend && (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
                viewModel.setLanBlocking(pkg.packageName, blocked)
            }
        )

        // Add click listeners to toggle containers for protected packages
        if (isProtected) {
            // WiFi toggle - click on entire row to show snackbar
            binding.wifiToggle.root.setOnClickListener {
                if (!binding.wifiToggle.toggleSwitch.isEnabled) {
                    showProtectionSnackbar(dialog)
                }
            }

            // Mobile toggle - click on entire row to show snackbar
            binding.mobileToggle.root.setOnClickListener {
                if (!binding.mobileToggle.toggleSwitch.isEnabled) {
                    showProtectionSnackbar(dialog)
                }
            }

            // Roaming toggle - click on entire row to show snackbar (if visible)
            if (hasCellular) {
                binding.roamingToggle.root.setOnClickListener {
                    if (!binding.roamingToggle.toggleSwitch.isEnabled) {
                        showProtectionSnackbar(dialog)
                    }
                }
            }

            // LAN toggle - show protection snackbar when protected and using iptables
            binding.lanToggle.root.setOnClickListener {
                if (!binding.lanToggle.toggleSwitch.isEnabled && isIptablesBackend) {
                    showProtectionSnackbar(dialog)
                }
            }
        }

        // Setup Background Access toggle (only shown when app is allowed)
        val defaultPolicy = viewModel.uiState.value.defaultFirewallPolicy
        val isBlockAllMode = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL
        val shouldShowBackgroundAccess = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical) && !pkg.isFullyBlocked

        AppLogger.d(TAG, "showGranularControlSheet: defaultPolicy=$defaultPolicy, isBlockAllMode=$isBlockAllMode, isFullyBlocked=${pkg.isFullyBlocked}, shouldShowBackgroundAccess=$shouldShowBackgroundAccess")

        if (shouldShowBackgroundAccess) {
            binding.foregroundOnlyDivider.visibility = View.VISIBLE
            binding.foregroundOnlyToggle.root.visibility = View.VISIBLE

            setupNetworkToggle(
                binding = binding.foregroundOnlyToggle,
                label = getString(R.string.firewall_network_label_background_access),
                isBlocked = !pkg.backgroundBlocked, // INVERTED: ON = allowed (not blocked), OFF = blocked
                enabled = true,
                invertLabels = true, // Swap labels so right side = Allowed, left side = Blocked
                onToggle = { isChecked ->
                    if (isUpdatingProgrammatically) return@setupNetworkToggle
                    // isChecked=true means switch is ON, which means "allowed" for this toggle
                    // So we need to set backgroundBlocked to the opposite: !isChecked
                    viewModel.setBackgroundBlocking(pkg.packageName, !isChecked)
                }
            )
        } else {
            binding.foregroundOnlyDivider.visibility = View.GONE
            binding.foregroundOnlyToggle.root.visibility = View.GONE
        }

        // Show info message (only for VPN-related info, not for system-critical packages)
        // System-critical packages now show the protection banner at the top instead
        val app = requireActivity().application as De1984Application
        val firewallManager = app.dependencies.firewallManager
        val backendType = firewallManager.getActiveBackendType()

        if (!pkg.hasInternetPermission) {
            // Show "No Internet Permission" info message
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = getString(R.string.firewall_no_internet_info)
        } else if (pkg.isVpnApp) {
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = getString(R.string.firewall_vpn_app_info)
        } else if (backendType == io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.VPN) {
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = getString(R.string.firewall_vpn_info_message)
        } else {
            binding.infoMessage.visibility = View.GONE
        }

        // Cross-navigation action to Packages screen
        binding.manageAppAction.setOnClickListener {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToPackagesWithApp(pkg.packageName)
        }

        dialog.setContentView(binding.root)
        AppLogger.d(TAG, "showGranularControlSheet: EXIT - About to show dialog for ${pkg.packageName}")
        dialog.show()

        // Configure BottomSheetBehavior to properly handle nested scrolling
        dialog.behavior.apply {
            isDraggable = true
            // Allow the sheet to be dragged, but nested scrolling will take priority
            // This ensures content scrolls first before the sheet starts dragging
        }
    }

    private fun showSimpleControlSheet(dialog: BottomSheetDialog, pkg: NetworkPackage) {
        val binding = BottomSheetPackageActionSimpleBinding.inflate(layoutInflater)

        // Setup header
        try {
            val pm = requireContext().packageManager
            val appInfo = pm.getApplicationInfo(pkg.packageName, 0)
            val icon = pm.getApplicationIcon(appInfo)
            binding.actionSheetAppIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            binding.actionSheetAppIcon.setImageResource(R.drawable.de1984_icon)
        }
        binding.actionSheetAppName.text = pkg.name
        binding.actionSheetPackageName.text = pkg.packageName

        // ============================================================================
        // Click package name to copy to clipboard
        // ============================================================================
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

        // Set appropriate info message based on package type and backend
        val app = requireActivity().application as De1984Application
        val firewallManager = app.dependencies.firewallManager
        val backendType = firewallManager.getActiveBackendType()
        val allowCriticalSimple = settingsViewModel.uiState.value.allowCriticalPackageFirewall

        // Only show info message for special cases - normal apps show info as subtitle under Internet Access
        val infoMessage: String? = if (!pkg.hasInternetPermission) {
            getString(R.string.firewall_no_internet_info)
        } else if ((pkg.isSystemCritical || pkg.isVpnApp) && allowCriticalSimple) {
            getString(R.string.firewall_critical_allowed_info)
        } else if (pkg.isSystemCritical) {
            getString(R.string.firewall_system_critical_info)
        } else if (pkg.isVpnApp) {
            getString(R.string.firewall_vpn_app_info)
        } else if (backendType == io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.CONNECTIVITY_MANAGER) {
            // Show ConnectivityManager info only (explains granular control requires root)
            getString(R.string.firewall_connectivity_manager_info)
        } else {
            null  // Hide info message for normal apps on VPN backend
        }

        if (infoMessage != null) {
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = infoMessage
        } else {
            binding.infoMessage.visibility = View.GONE
        }

        // Flag to prevent infinite recursion when updating switch programmatically
        var isUpdatingProgrammatically = false

        // Function to update UI toggle based on current package state
        fun updateToggleFromPackage(currentPkg: NetworkPackage) {
            isUpdatingProgrammatically = true

            // For all-or-nothing backends, check if ANY network is blocked
            val isBlocked = currentPkg.wifiBlocked || currentPkg.mobileBlocked || currentPkg.roamingBlocked
            binding.internetToggle.toggleSwitch.isChecked = isBlocked
            updateSwitchColors(binding.internetToggle.toggleSwitch, isBlocked)

            isUpdatingProgrammatically = false
        }

        // Initial setup of toggle
        updateToggleFromPackage(pkg)

        // Observe package changes to update UI when ViewModel makes changes
        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val updatedPkg = state.packages.find { it.packageName == pkg.packageName }
                if (updatedPkg != null && !isUpdatingProgrammatically) {
                    updateToggleFromPackage(updatedPkg)
                }
            }
        }

        // Cancel observer when dialog is dismissed
        dialog.setOnDismissListener {
            AppLogger.d(TAG, "showSimpleControlSheet: Dialog dismissed, cancelling observer for ${pkg.packageName}")
            observerJob.cancel()
        }

        // Setup single "Internet Access" toggle
        setupNetworkToggle(
            binding = binding.internetToggle,
            label = getString(R.string.firewall_network_label_internet_access),
            isBlocked = pkg.wifiBlocked || pkg.mobileBlocked || pkg.roamingBlocked,
            enabled = (!pkg.isSystemCritical || allowCriticalSimple) && (!pkg.isVpnApp || allowCriticalSimple),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle

                // Block/unblock ALL networks at once atomically - prevents race conditions
                viewModel.setAllNetworkBlocking(pkg.packageName, blocked)
            }
        )
        // Show "WiFi, Mobile, Roaming" subtitle under Internet Access
        binding.internetToggle.networkTypeSubtitle.visibility = View.VISIBLE
        binding.internetToggle.networkTypeSubtitle.text = getString(R.string.firewall_internet_access_subtitle)

        // Setup LAN toggle - always shown but disabled (requires root/iptables which isn't available in simple mode)
        setupNetworkToggle(
            binding = binding.lanToggle,
            label = getString(R.string.firewall_network_label_lan),
            isBlocked = pkg.lanBlocked,
            enabled = false,  // Always disabled in simple control sheet (requires iptables/root)
            onToggle = { /* No-op - disabled */ }
        )
        // Show "Requires root access" subtitle
        binding.lanToggle.root.alpha = 0.6f
        binding.lanToggle.networkTypeSubtitle.visibility = View.VISIBLE
        binding.lanToggle.networkTypeSubtitle.text = getString(R.string.firewall_lan_requires_root)

        // Cross-navigation action to Packages screen
        binding.manageAppAction.setOnClickListener {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToPackagesWithApp(pkg.packageName)
        }

        dialog.setContentView(binding.root)
        dialog.show()

        // Configure BottomSheetBehavior to properly handle nested scrolling
        dialog.behavior.apply {
            isDraggable = true
            // Allow the sheet to be dragged, but nested scrolling will take priority
            // This ensures content scrolls first before the sheet starts dragging
        }
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

        // Optionally use ON/OFF labels for "Allow in Background" toggle
        if (invertLabels) {
            // For inverted toggle: use OFF/ON instead of Allowed/Blocked
            binding.labelLeft.text = getString(R.string.firewall_state_off)
            binding.labelRight.text = getString(R.string.firewall_state_on)
        } else {
            // For normal toggle: left = Allowed, right = Blocked
            binding.labelLeft.text = getString(R.string.firewall_state_allowed)
            binding.labelRight.text = getString(R.string.firewall_state_blocked)
        }

        // Set initial state: switch ON = blocked, switch OFF = allowed
        binding.toggleSwitch.isChecked = isBlocked
        binding.toggleSwitch.isEnabled = enabled

        // Update colors based on state
        updateSwitchColors(binding.toggleSwitch, isBlocked, invertColors = invertLabels)

        // Simple switch listener - only fires on user interaction
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

        // Determine colors based on whether we're inverting
        val (checkedColor, uncheckedColor) = if (invertColors) {
            // For "Allow in Background": ON = TEAL (allowed), OFF = RED (blocked)
            Pair(
                ContextCompat.getColor(context, R.color.lineage_teal),
                ContextCompat.getColor(context, R.color.error_red)
            )
        } else {
            // For normal toggles: ON = RED (blocked), OFF = TEAL (allowed)
            Pair(
                ContextCompat.getColor(context, R.color.error_red),
                ContextCompat.getColor(context, R.color.lineage_teal)
            )
        }

        // Create color state lists for checked (ON) and unchecked (OFF) states
        val thumbColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),  // When switch is ON
                intArrayOf(-android.R.attr.state_checked)  // When switch is OFF
            ),
            intArrayOf(checkedColor, uncheckedColor)
        )

        val trackColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),  // When switch is ON
                intArrayOf(-android.R.attr.state_checked)  // When switch is OFF
            ),
            intArrayOf(
                checkedColor and 0x80FFFFFF.toInt(),      // 50% opacity when ON
                uncheckedColor and 0x80FFFFFF.toInt()     // 50% opacity when OFF
            )
        )

        // Set thumb (the circle) and track (the background) colors
        switch.thumbTintList = thumbColorStateList
        switch.trackTintList = trackColorStateList
    }

    /**
     * Map translated type filter string to internal constant
     */
    private fun mapTypeFilterToInternal(translatedFilter: String): String {
        return when (translatedFilter) {
            getString(io.github.dorumrr.de1984.R.string.packages_filter_all) -> Constants.Packages.TYPE_ALL
            getString(io.github.dorumrr.de1984.R.string.packages_filter_user) -> Constants.Packages.TYPE_USER
            getString(io.github.dorumrr.de1984.R.string.packages_filter_system) -> Constants.Packages.TYPE_SYSTEM
            else -> Constants.Packages.TYPE_ALL // Default fallback
        }
    }

    /**
     * Map translated state filter string to internal constant
     */
    private fun mapStateFilterToInternal(translatedFilter: String): String {
        return when (translatedFilter) {
            getString(io.github.dorumrr.de1984.R.string.firewall_state_allowed) -> Constants.Firewall.STATE_ALLOWED
            getString(io.github.dorumrr.de1984.R.string.firewall_state_blocked) -> Constants.Firewall.STATE_BLOCKED
            else -> translatedFilter // Fallback to original
        }
    }

    /**
     * Map internal constant to translated type filter string
     */
    private fun mapInternalToTypeFilter(internalFilter: String): String {
        return when (internalFilter) {
            Constants.Packages.TYPE_ALL -> getString(io.github.dorumrr.de1984.R.string.packages_filter_all)
            Constants.Packages.TYPE_USER -> getString(io.github.dorumrr.de1984.R.string.packages_filter_user)
            Constants.Packages.TYPE_SYSTEM -> getString(io.github.dorumrr.de1984.R.string.packages_filter_system)
            else -> getString(io.github.dorumrr.de1984.R.string.packages_filter_all) // Default fallback
        }
    }

    /**
     * Map internal constant to translated state filter string
     */
    private fun mapInternalToStateFilter(internalFilter: String): String {
        return when (internalFilter) {
            Constants.Firewall.STATE_ALLOWED -> getString(io.github.dorumrr.de1984.R.string.firewall_state_allowed)
            Constants.Firewall.STATE_BLOCKED -> getString(io.github.dorumrr.de1984.R.string.firewall_state_blocked)
            else -> internalFilter // Fallback to original
        }
    }

    /**
     * Show snackbar informing user that package is protected.
     * Provides action button to navigate to Settings.
     */
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

    // ========== SELECTION MODE METHODS ==========

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

    /**
     * Handle long click on a package to enter selection mode
     */
    private fun onPackageLongClick(pkg: NetworkPackage): Boolean {
        AppLogger.d(TAG, "🔘 Long click on package: ${pkg.packageName}")

        // Check if package can be selected
        if (!adapter.canSelectPackage(pkg, requireContext())) {
            Toast.makeText(
                requireContext(),
                getString(R.string.firewall_multiselect_toast_cannot_select_critical),
                Toast.LENGTH_SHORT
            ).show()
            return true
        }

        if (!isSelectionMode) {
            enterSelectionMode()
        }

        // Select the long-pressed package
        adapter.selectPackage(pkg.packageName)
        return true
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
        // Rules button is always visible - no need to toggle visibility based on filter
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

    // ========== MULTI-SELECT RULES SHEET ==========

    /**
     * Represents the aggregated state of a network toggle across multiple selected packages.
     */
    private enum class MultiSelectToggleState {
        ALL_BLOCKED,    // All selected packages have this network blocked
        ALL_ALLOWED,    // All selected packages have this network allowed
        MIXED           // Some blocked, some allowed
    }

    /**
     * Calculate the aggregated state for a specific network type across selected packages.
     */
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

    /**
     * Show the multi-select rules bottom sheet with granular network controls.
     */
    private fun showMultiSelectRulesSheet() {
        val dialog = BottomSheetDialog(requireContext())
        currentDialog = dialog

        val sheetBinding = BottomSheetFirewallMultiselectBinding.inflate(layoutInflater)

        // Get selected packages from current UI state
        val allPackages = viewModel.uiState.value.packages
        val selectedPkgs = allPackages.filter { selectedPackages.contains(it.packageName) }

        if (selectedPkgs.isEmpty()) {
            dialog.dismiss()
            return
        }

        // Setup header
        sheetBinding.multiselectHeader.text = getString(R.string.firewall_multiselect_sheet_header_format, selectedPkgs.size)

        // Check if device has cellular capability
        val telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasCellular = telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE

        // Check if using iptables backend for LAN toggle
        val app = requireActivity().application as De1984Application
        val backendType = app.dependencies.firewallManager.activeBackendType.value
        val isIptablesBackend = backendType == FirewallBackendType.IPTABLES

        // Calculate initial states
        val wifiState = calculateToggleState(selectedPkgs) { it.wifiBlocked }
        val mobileState = calculateToggleState(selectedPkgs) { it.mobileBlocked }
        val roamingState = calculateToggleState(selectedPkgs) { it.roamingBlocked }
        val lanState = calculateToggleState(selectedPkgs) { it.lanBlocked }

        // Setup WiFi toggle (initial state only, listener added below)
        setupMultiSelectToggleInitial(
            binding = sheetBinding.wifiToggle,
            label = getString(R.string.firewall_network_label_wifi),
            state = wifiState
        )

        // Setup Mobile toggle (initial state only, listener added below)
        setupMultiSelectToggleInitial(
            binding = sheetBinding.mobileToggle,
            label = getString(R.string.firewall_network_label_mobile),
            state = mobileState
        )

        // Setup Roaming toggle (only if device has cellular)
        if (hasCellular) {
            sheetBinding.roamingDivider.visibility = View.VISIBLE
            sheetBinding.roamingToggle.root.visibility = View.VISIBLE
            setupMultiSelectToggleInitial(
                binding = sheetBinding.roamingToggle,
                label = getString(R.string.firewall_network_label_roaming),
                state = roamingState
            )
        }

        // Setup LAN toggle (only if using iptables backend)
        if (isIptablesBackend) {
            sheetBinding.lanDivider.visibility = View.VISIBLE
            sheetBinding.lanToggle.root.visibility = View.VISIBLE
            setupMultiSelectToggleInitial(
                binding = sheetBinding.lanToggle,
                label = getString(R.string.firewall_network_label_lan),
                state = lanState
            )
        }

        // Setup Quick Action buttons
        sheetBinding.allowAllButton.setOnClickListener {
            viewModel.batchAllowPackages(selectedPackages.toList())
            dialog.dismiss()
            exitSelectionMode()
        }

        sheetBinding.blockAllButton.setOnClickListener {
            viewModel.batchBlockPackages(selectedPackages.toList())
            dialog.dismiss()
            exitSelectionMode()
        }

        // Flag to prevent infinite recursion when updating toggles programmatically
        var isUpdatingProgrammatically = false

        // Function to update all toggles based on current package states
        fun updateTogglesFromPackages(packages: List<NetworkPackage>) {
            if (packages.isEmpty()) return
            isUpdatingProgrammatically = true

            val newWifiState = calculateToggleState(packages) { it.wifiBlocked }
            val newMobileState = calculateToggleState(packages) { it.mobileBlocked }
            val newRoamingState = calculateToggleState(packages) { it.roamingBlocked }
            val newLanState = calculateToggleState(packages) { it.lanBlocked }

            // Update WiFi toggle
            updateMultiSelectToggleState(sheetBinding.wifiToggle, newWifiState)

            // Update Mobile toggle
            updateMultiSelectToggleState(sheetBinding.mobileToggle, newMobileState)

            // Update Roaming toggle (if visible)
            if (hasCellular) {
                updateMultiSelectToggleState(sheetBinding.roamingToggle, newRoamingState)
            }

            // Update LAN toggle (if visible)
            if (isIptablesBackend) {
                updateMultiSelectToggleState(sheetBinding.lanToggle, newLanState)
            }

            isUpdatingProgrammatically = false
        }

        // Wrap toggle callbacks to check the flag
        sheetBinding.wifiToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            sheetBinding.wifiToggle.networkTypeSubtitle.visibility = View.GONE
            updateSwitchColors(sheetBinding.wifiToggle.toggleSwitch, isChecked)
            viewModel.batchSetWifiBlocking(selectedPackages.toList(), isChecked)
        }

        sheetBinding.mobileToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            sheetBinding.mobileToggle.networkTypeSubtitle.visibility = View.GONE
            updateSwitchColors(sheetBinding.mobileToggle.toggleSwitch, isChecked)
            viewModel.batchSetMobileBlocking(selectedPackages.toList(), isChecked)
        }

        if (hasCellular) {
            sheetBinding.roamingToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
                sheetBinding.roamingToggle.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(sheetBinding.roamingToggle.toggleSwitch, isChecked)
                viewModel.batchSetRoamingBlocking(selectedPackages.toList(), isChecked)
            }
        }

        if (isIptablesBackend) {
            sheetBinding.lanToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
                sheetBinding.lanToggle.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(sheetBinding.lanToggle.toggleSwitch, isChecked)
                viewModel.batchSetLanBlocking(selectedPackages.toList(), isChecked)
            }
        }

        // Observe package changes to update UI when ViewModel makes cascading changes
        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val updatedPkgs = state.packages.filter { selectedPackages.contains(it.packageName) }
                if (updatedPkgs.isNotEmpty() && !isUpdatingProgrammatically) {
                    AppLogger.d(TAG, "showMultiSelectRulesSheet: uiState collected - updating ${updatedPkgs.size} packages")
                    updateTogglesFromPackages(updatedPkgs)
                }
            }
        }

        // Cancel observer when dialog is dismissed
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

    /**
     * Update a multi-select toggle's visual state without triggering the listener.
     */
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

        // Set initial state based on aggregated state
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
                // For mixed state, show as unchecked (allowed) but with "Mixed" subtitle
                binding.toggleSwitch.isChecked = false
                binding.networkTypeSubtitle.visibility = View.VISIBLE
                binding.networkTypeSubtitle.text = getString(R.string.firewall_multiselect_sheet_state_mixed)
                updateSwitchColors(binding.toggleSwitch, false)
            }
        }
    }

    companion object {
        private const val TAG = "FirewallFragmentViews"
    }
}

