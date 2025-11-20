package io.github.dorumrr.de1984.ui.firewall

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
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
import androidx.core.widget.addTextChangedListener

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
            app.dependencies.captivePortalManager
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
        Log.d(TAG, "onHiddenChanged: hidden=$hidden")

        if (!hidden) {
            // Fragment became visible - check if policy changed while we were hidden
            Log.d(TAG, "onHiddenChanged: Fragment became visible, checking for policy changes")
            val currentPolicy = settingsViewModel.uiState.value.defaultFirewallPolicy
            Log.d(TAG, "onHiddenChanged: previousObservedPolicy=$previousObservedPolicy, currentPolicy=$currentPolicy")

            if (previousObservedPolicy != null && previousObservedPolicy != currentPolicy) {
                Log.d(TAG, "onHiddenChanged: Policy changed while hidden! Refreshing...")
                previousObservedPolicy = currentPolicy
                viewModel.refreshDefaultPolicy()
            } else {
                Log.d(TAG, "onHiddenChanged: No policy change detected")
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = NetworkPackageAdapter(
            showIcons = true, // Will be updated from settings
            onPackageClick = { pkg ->
                showPackageActionSheet(pkg)
            }
        )

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
                    currentTypeFilter = filter
                    // Map translated string to internal constant
                    val internalFilter = mapTypeFilterToInternal(filter)
                    viewModel.setPackageTypeFilter(internalFilter)
                }
            },
            onStateFilterSelected = { filter ->
                if (filter != currentStateFilter) {
                    currentStateFilter = filter
                    // Map translated string to internal constant
                    val internalFilter = filter?.let { mapStateFilterToInternal(it) }
                    viewModel.setNetworkStateFilter(internalFilter)
                }
            },
            onPermissionFilterSelected = { enabled ->
                if (enabled != currentPermissionFilter) {
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
            viewModel.setSearchQuery(query)

            // Show/hide clear icon based on text length
            binding.searchLayout.isEndIconVisible = query.isNotEmpty()
        }

        // Clear icon click listener
        binding.searchLayout.setEndIconOnClickListener {
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
                    Log.d(TAG, "observeSettingsState: settingsState changed - showAppIcons=${settingsState.showAppIcons}, defaultFirewallPolicy=${settingsState.defaultFirewallPolicy}")
                    Log.d(TAG, "observeSettingsState: previousObservedPolicy=$previousObservedPolicy, newPolicy=${settingsState.defaultFirewallPolicy}")

                    // Update adapter when showIcons setting changes
                    adapter = NetworkPackageAdapter(
                        showIcons = settingsState.showAppIcons,
                        onPackageClick = { pkg ->
                            showPackageActionSheet(pkg)
                        }
                    )
                    binding.packagesRecyclerView.adapter = adapter

                    // Reset last submitted packages when creating new adapter
                    lastSubmittedPackages = emptyList()

                    // If default policy changed, refresh packages to reflect new blocking states
                    if (previousObservedPolicy != null && previousObservedPolicy != settingsState.defaultFirewallPolicy) {
                        Log.d(TAG, "observeSettingsState: Policy changed! Refreshing packages...")
                        viewModel.refreshDefaultPolicy()
                    } else if (previousObservedPolicy == null) {
                        Log.d(TAG, "observeSettingsState: First observation, skipping refresh")
                    } else {
                        Log.d(TAG, "observeSettingsState: Policy unchanged, skipping refresh")
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
            Log.w(TAG, "[FIREWALL] Dialog already open, dismissing before opening new one")
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
                        Log.e(TAG, "Failed to load package for dialog: ${error.message}")
                        if (pendingDialogPackageName == packageName) {
                            pendingDialogPackageName = null
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception opening dialog: ${e.message}")
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
        Log.d(TAG, "showGranularControlSheet: ENTRY - pkg=${pkg.packageName}, dialog=$dialog")
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
            Log.d(TAG, "updateTogglesFromPackage: pkg=${currentPkg.packageName}, wifi=${currentPkg.wifiBlocked}, mobile=${currentPkg.mobileBlocked}, roaming=${currentPkg.roamingBlocked}, background=${currentPkg.backgroundBlocked}, isFullyBlocked=${currentPkg.isFullyBlocked}")
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

            // Update LAN toggle (if using iptables backend)
            val app = requireActivity().application as De1984Application
            val backendType = app.dependencies.firewallManager.activeBackendType.value
            if (backendType == FirewallBackendType.IPTABLES) {
                binding.lanToggle.toggleSwitch.isChecked = currentPkg.lanBlocked
                updateSwitchColors(binding.lanToggle.toggleSwitch, currentPkg.lanBlocked)
            }

            // Update Background toggle visibility based on current blocking state
            val allowCriticalForUpdate = settingsViewModel.uiState.value.allowCriticalPackageFirewall
            val shouldShowBackgroundAccess = (!currentPkg.isSystemCritical || allowCriticalForUpdate) && (!currentPkg.isVpnApp || allowCriticalForUpdate) && !currentPkg.isFullyBlocked
            val wasBackgroundToggleVisible = binding.foregroundOnlyToggle.root.visibility == View.VISIBLE
            Log.d(TAG, "updateTogglesFromPackage: shouldShowBackgroundAccess=$shouldShowBackgroundAccess, wasVisible=$wasBackgroundToggleVisible (isSystemCritical=${currentPkg.isSystemCritical}, isVpnApp=${currentPkg.isVpnApp}, isFullyBlocked=${currentPkg.isFullyBlocked})")

            binding.foregroundOnlyDivider.visibility = if (shouldShowBackgroundAccess) View.VISIBLE else View.GONE
            binding.foregroundOnlyToggle.root.visibility = if (shouldShowBackgroundAccess) View.VISIBLE else View.GONE

            // Update Background toggle state if visible
            if (shouldShowBackgroundAccess) {
                // If toggle just became visible, we need to set up the listener
                if (!wasBackgroundToggleVisible) {
                    Log.d(TAG, "updateTogglesFromPackage: Background toggle just became visible - setting up listener")
                    setupNetworkToggle(
                        binding = binding.foregroundOnlyToggle,
                        label = getString(R.string.firewall_network_label_background_access),
                        isBlocked = !currentPkg.backgroundBlocked,
                        enabled = true,
                        invertLabels = true,
                        onToggle = { isChecked ->
                            if (isUpdatingProgrammatically) return@setupNetworkToggle
                            Log.d(TAG, "updateTogglesFromPackage: Background toggle clicked - isChecked=$isChecked, setting backgroundBlocked=${!isChecked}")
                            viewModel.setBackgroundBlocking(currentPkg.packageName, !isChecked)
                        }
                    )
                } else {
                    // Toggle was already visible, just update the state
                    binding.foregroundOnlyToggle.toggleSwitch.isChecked = !currentPkg.backgroundBlocked
                    updateSwitchColors(binding.foregroundOnlyToggle.toggleSwitch, !currentPkg.backgroundBlocked, invertColors = true)
                }
                Log.d(TAG, "updateTogglesFromPackage: Background toggle updated - isChecked=${!currentPkg.backgroundBlocked}")
            }

            isUpdatingProgrammatically = false
        }

        // Observe package changes to update UI when ViewModel makes cascading changes
        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val updatedPkg = state.packages.find { it.packageName == pkg.packageName }
                Log.d(TAG, "showGranularControlSheet: uiState collected - updatedPkg found=${updatedPkg != null}, isUpdatingProgrammatically=$isUpdatingProgrammatically")
                if (updatedPkg != null && !isUpdatingProgrammatically) {
                    Log.d(TAG, "showGranularControlSheet: Calling updateTogglesFromPackage for ${updatedPkg.packageName}")
                    updateTogglesFromPackage(updatedPkg)
                } else if (updatedPkg != null) {
                    Log.d(TAG, "showGranularControlSheet: Skipping update (isUpdatingProgrammatically=true)")
                }
            }
        }

        // Cancel observer when dialog is dismissed
        dialog.setOnDismissListener {
            Log.d(TAG, "showGranularControlSheet: Dialog dismissed, cancelling observer for ${pkg.packageName}")
            observerJob.cancel()
            if (currentDialog == dialog) {
                currentDialog = null
            }
        }

        // Setup WiFi toggle
        val allowCritical = settingsViewModel.uiState.value.allowCriticalPackageFirewall
        setupNetworkToggle(
            binding = binding.wifiToggle,
            label = getString(R.string.firewall_network_label_wifi),
            isBlocked = pkg.wifiBlocked,
            enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
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

                    // ViewModel handles mobile+roaming dependency atomically
                    viewModel.setRoamingBlocking(pkg.packageName, blocked)
                }
            )
        }

        // Setup LAN toggle (only if using iptables backend)
        val appForLan = requireActivity().application as De1984Application
        val backendTypeForLan = appForLan.dependencies.firewallManager.activeBackendType.value
        if (backendTypeForLan == FirewallBackendType.IPTABLES) {
            binding.lanDivider.visibility = View.VISIBLE
            binding.lanToggle.root.visibility = View.VISIBLE

            setupNetworkToggle(
                binding = binding.lanToggle,
                label = getString(R.string.firewall_network_label_lan),
                isBlocked = pkg.lanBlocked,
                enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
                onToggle = { blocked ->
                    if (isUpdatingProgrammatically) return@setupNetworkToggle
                    viewModel.setLanBlocking(pkg.packageName, blocked)
                }
            )
        }

        // Setup Background Access toggle (only shown when app is allowed)
        val defaultPolicy = viewModel.uiState.value.defaultFirewallPolicy
        val isBlockAllMode = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL
        val shouldShowBackgroundAccess = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical) && !pkg.isFullyBlocked

        Log.d(TAG, "showGranularControlSheet: defaultPolicy=$defaultPolicy, isBlockAllMode=$isBlockAllMode, isFullyBlocked=${pkg.isFullyBlocked}, shouldShowBackgroundAccess=$shouldShowBackgroundAccess")

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

        // Show info message
        val app = requireActivity().application as De1984Application
        val firewallManager = app.dependencies.firewallManager
        val backendType = firewallManager.getActiveBackendType()

        if ((pkg.isSystemCritical || pkg.isVpnApp) && allowCritical) {
            // Show warning that critical protection is disabled
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = getString(R.string.firewall_critical_allowed_info)
        } else if (pkg.isSystemCritical) {
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = getString(R.string.firewall_system_critical_info)
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
        Log.d(TAG, "showGranularControlSheet: EXIT - About to show dialog for ${pkg.packageName}")
        dialog.show()
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

        val infoMessage = if ((pkg.isSystemCritical || pkg.isVpnApp) && allowCriticalSimple) {
            getString(R.string.firewall_critical_allowed_info)
        } else if (pkg.isSystemCritical) {
            getString(R.string.firewall_system_critical_info)
        } else if (pkg.isVpnApp) {
            getString(R.string.firewall_vpn_app_info)
        } else {
            when (backendType) {
                io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.CONNECTIVITY_MANAGER -> {
                    getString(R.string.firewall_connectivity_manager_info)
                }
                else -> {
                    getString(R.string.firewall_block_all_info)
                }
            }
        }
        binding.infoMessage.text = infoMessage

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
            Log.d(TAG, "showSimpleControlSheet: Dialog dismissed, cancelling observer for ${pkg.packageName}")
            observerJob.cancel()
        }

        // Setup single "Block Internet" toggle
        setupNetworkToggle(
            binding = binding.internetToggle,
            label = getString(R.string.firewall_network_label_block_internet),
            isBlocked = pkg.wifiBlocked || pkg.mobileBlocked || pkg.roamingBlocked,
            enabled = (!pkg.isSystemCritical || allowCriticalSimple) && (!pkg.isVpnApp || allowCriticalSimple),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle

                // Block/unblock ALL networks at once atomically - prevents race conditions
                viewModel.setAllNetworkBlocking(pkg.packageName, blocked)
            }
        )

        // Cross-navigation action to Packages screen
        binding.manageAppAction.setOnClickListener {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToPackagesWithApp(pkg.packageName)
        }

        dialog.setContentView(binding.root)
        dialog.show()
    }

    private fun setupNetworkToggle(
        binding: NetworkTypeToggleBinding,
        label: String,
        isBlocked: Boolean,
        enabled: Boolean,
        invertLabels: Boolean = false,
        onToggle: (Boolean) -> Unit
    ) {
        Log.d(TAG, "setupNetworkToggle: label=$label, isBlocked=$isBlocked, enabled=$enabled, binding=$binding")
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

    companion object {
        private const val TAG = "FirewallFragmentViews"
    }
}

