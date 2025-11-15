package io.github.dorumrr.de1984.ui.packages

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.BottomSheetPackageActionsBinding
import io.github.dorumrr.de1984.databinding.FragmentPackagesBinding
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageCriticality
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.presentation.viewmodel.PackagesUiState
import io.github.dorumrr.de1984.presentation.viewmodel.PackagesViewModel
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.ui.common.FilterChipsHelper
import io.github.dorumrr.de1984.ui.common.PermissionSetupDialog
import io.github.dorumrr.de1984.ui.common.StandardDialog
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.PackageUtils
import io.github.dorumrr.de1984.utils.copyToClipboard
import io.github.dorumrr.de1984.utils.openAppSettings
import io.github.dorumrr.de1984.utils.setOnClickListenerDebounced
import kotlinx.coroutines.launch
import androidx.core.widget.addTextChangedListener
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.ui.MainActivity

class PackagesFragmentViews : BaseFragment<FragmentPackagesBinding>() {

    private val TAG = "PackagesFragmentViews"

    private val viewModel: PackagesViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        PackagesViewModel.Factory(
            getPackagesUseCase = app.dependencies.provideGetPackagesUseCase(),
            managePackageUseCase = app.dependencies.provideManagePackageUseCase(),
            superuserBannerState = app.dependencies.superuserBannerState,
            rootManager = app.dependencies.rootManager,
            shizukuManager = app.dependencies.shizukuManager
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
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

    private lateinit var adapter: PackageAdapter
    private var currentTypeFilter: String? = null
    private var currentStateFilter: String? = null
    private var lastSubmittedPackages: List<Package> = emptyList()

    // Dialog tracking to prevent dialogs stacking
    private var currentDialog: BottomSheetDialog? = null
    private var dialogOpenTimestamp: Long = 0
    private var pendingDialogPackageName: String? = null

    // Selection mode state
    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<String>()
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null



    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentPackagesBinding {
        return FragmentPackagesBinding.inflate(inflater, container, false)
    }

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

        setupPermissionDialog()
        observeUiState()
        observeSettings()
        setupBackPressHandler()

        // Check root access on start
        // Note: Don't load packages here - let ViewModel's init{} handle first load
        viewModel.checkRootAccess()

        // Add layout change listener to track when RecyclerView actually renders
        binding.packagesRecyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // RecyclerView layout changed
        }
    }

    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isSelectionMode) {
                        exitSelectionMode()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun setupRecyclerView() {
        adapter = PackageAdapter(
            showIcons = true, // Will be updated from settings
            onPackageClick = { pkg ->
                showPackageActionSheet(pkg)
            },
            onPackageLongClick = { pkg ->
                enterSelectionMode(pkg)
                true
            }
        )

        // Set selection change listener
        adapter.setOnSelectionChangedListener { selected ->
            selectedPackages.clear()
            selectedPackages.addAll(selected)
            updateSelectionToolbar()
        }

        // Set selection limit reached listener
        adapter.setOnSelectionLimitReachedListener {
            android.widget.Toast.makeText(
                requireContext(),
                Constants.Packages.MultiSelect.TOAST_SELECTION_LIMIT,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // Reset last submitted packages when creating new adapter
        // This ensures the new adapter gets populated even if the list hasn't changed
        lastSubmittedPackages = emptyList()

        binding.packagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PackagesFragmentViews.adapter
            setHasFixedSize(true)
        }

        // Setup selection toolbar
        setupSelectionToolbar()
    }

    private fun setupFilterChips() {
        val packageTypeFilters = Constants.Packages.PACKAGE_TYPE_FILTERS
        val packageStateFilters = Constants.Packages.PACKAGE_STATE_FILTERS

        // Initial setup - only called once
        currentTypeFilter = "All"
        currentStateFilter = null

        FilterChipsHelper.setupMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            typeFilters = packageTypeFilters,
            stateFilters = packageStateFilters,
            permissionFilters = emptyList(),  // No permission filters in Packages screen
            selectedTypeFilter = currentTypeFilter,
            selectedStateFilter = currentStateFilter,
            selectedPermissionFilter = false,  // Not used in Packages screen
            onTypeFilterSelected = { filter ->
                // Only trigger if different from current
                if (filter != currentTypeFilter) {
                    // Don't clear adapter - let ViewModel handle the state transition
                    currentTypeFilter = filter
                    viewModel.setPackageTypeFilter(filter)
                }
            },
            onStateFilterSelected = { filter ->
                // Only trigger if different from current
                if (filter != currentStateFilter) {
                    // Don't clear adapter - let ViewModel handle the state transition
                    currentStateFilter = filter
                    viewModel.setPackageStateFilter(filter)
                }
            },
            onPermissionFilterSelected = { _ ->
                // Not used in Packages screen
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

    private fun updateFilterChips(packageTypeFilter: String, packageStateFilter: String?) {
        // Only update if filters have changed
        if (packageTypeFilter == currentTypeFilter && packageStateFilter == currentStateFilter) {
            return
        }

        currentTypeFilter = packageTypeFilter
        currentStateFilter = packageStateFilter

        // Update chip selection without recreating or triggering listeners
        FilterChipsHelper.updateMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            selectedTypeFilter = packageTypeFilter,
            selectedStateFilter = packageStateFilter,
            selectedPermissionFilter = false  // Not used in Packages screen
        )
    }

    private fun setupPermissionDialog() {
        Log.d(TAG, "setupPermissionDialog called")
        // Observe privileged access status and show modal dialog when needed
        observePrivilegedAccessStatus()
    }

    private fun observePrivilegedAccessStatus() {
        Log.d(TAG, "observePrivilegedAccessStatus called")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.d(TAG, "Starting privileged access status observation")
                // Combine both status flows to determine banner state
                launch {
                    viewModel.rootManager.rootStatus.collect { rootStatus ->
                        Log.d(TAG, "Root status changed: $rootStatus")
                        updateBannerContent(rootStatus, viewModel.shizukuManager.shizukuStatus.value)
                    }
                }
                launch {
                    viewModel.shizukuManager.shizukuStatus.collect { shizukuStatus ->
                        Log.d(TAG, "Shizuku status changed: $shizukuStatus")
                        updateBannerContent(viewModel.rootManager.rootStatus.value, shizukuStatus)
                    }
                }
            }
        }
    }

    private fun updateBannerContent(rootStatus: RootStatus, shizukuStatus: ShizukuStatus) {
        Log.d(TAG, "updateBannerContent: rootStatus=$rootStatus, shizukuStatus=$shizukuStatus")
        // This method is now used to trigger the modal dialog when needed
        // The actual dialog showing is handled by observeUiState when showRootBanner becomes true
    }



    private fun navigateToSettings() {
        // Navigate to Settings screen using bottom navigation
        requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
            ?.selectedItemId = R.id.settingsFragment
    }

    private fun attemptPermissionGrant() {
        // Try Shizuku first, then root (existing logic)
        settingsViewModel.grantShizukuPermission()
        // If Shizuku is not available, request root
        settingsViewModel.requestRootPermission()
    }

    private fun showPermissionSetupDialog() {
        Log.d(TAG, "showPermissionSetupDialog called")

        val rootStatus = viewModel.rootManager.rootStatus.value
        val shizukuStatus = viewModel.shizukuManager.shizukuStatus.value

        PermissionSetupDialog.showPackageManagementDialog(
            context = requireContext(),
            rootStatus = rootStatus,
            shizukuStatus = shizukuStatus,
            onGrantClick = {
                attemptPermissionGrant()
            },
            onSettingsClick = {
                navigateToSettings()
            },
            onDismiss = {
                viewModel.dismissRootBanner()
            }
        )
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe UI state
                launch {
                    viewModel.uiState.collect { state ->
                        updateUI(state)
                    }
                }

                // Observe banner visibility and show modal dialog
                launch {
                    viewModel.showRootBanner.collect { showBanner ->
                        if (showBanner) {
                            showPermissionSetupDialog()
                        }
                    }
                }
            }
        }
    }

    private fun updateUI(state: PackagesUiState) {
        // Update visibility based on state
        if (state.isLoadingData && state.packages.isEmpty()) {
            binding.packagesRecyclerView.visibility = View.INVISIBLE  // INVISIBLE instead of GONE
            binding.loadingState.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        } else if (state.packages.isEmpty()) {
            binding.packagesRecyclerView.visibility = View.INVISIBLE
            binding.loadingState.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE

            // Update empty state message based on filter
            val emptyMessage = if (state.filterState.packageState?.lowercase() == Constants.Packages.STATE_UNINSTALLED.lowercase()) {
                Constants.Packages.EMPTY_STATE_NO_UNINSTALLED
            } else {
                "No packages found"
            }
            binding.emptyStateMessage.text = emptyMessage
        } else {
            binding.packagesRecyclerView.visibility = View.VISIBLE
            binding.loadingState.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }

        // Update filter chips
        updateFilterChips(
            packageTypeFilter = state.filterState.packageType,
            packageStateFilter = state.filterState.packageState
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
            // Even if list didn't change, still handle batch result and errors
            handleBatchUninstallResult(state)
            handleError(state)
            return
        }

        lastSubmittedPackages = displayedPackages
        adapter.submitList(displayedPackages)
        if (state.isRenderingUI) {
            viewModel.setUIReady()
        }

        // Handle batch uninstall result
        handleBatchUninstallResult(state)

        // Handle batch reinstall result
        handleBatchReinstallResult(state)

        // Handle success toasts
        handleSuccessToasts(state)

        // Show error if any
        handleError(state)
    }

    private fun handleBatchUninstallResult(state: PackagesUiState) {
        state.batchUninstallResult?.let { result ->
            progressDialog?.dismiss()
            progressDialog = null
            showBatchUninstallResults(result)
            viewModel.clearBatchUninstallResult()
            exitSelectionMode()
        }
    }

    private fun handleBatchReinstallResult(state: PackagesUiState) {
        state.batchReinstallResult?.let { result ->
            progressDialog?.dismiss()
            progressDialog = null
            showBatchReinstallResults(result)
            viewModel.clearBatchReinstallResult()
            exitSelectionMode()
        }
    }

    private fun handleSuccessToasts(state: PackagesUiState) {
        state.uninstallSuccess?.let { message ->
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            viewModel.clearUninstallSuccess()
        }

        state.reinstallSuccess?.let { message ->
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            viewModel.clearReinstallSuccess()
        }
    }

    private fun handleError(state: PackagesUiState) {
        state.error?.let { error ->
            showError(error)
            viewModel.clearError()
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.uiState.collect { state ->
                    adapter.updateShowIcons(state.showAppIcons)
                }
            }
        }
    }

    // ============================================================================
    // DO NOT REMOVE: This method is called from MainActivity for cross-navigation
    // ============================================================================
    /**
     * Open the package action dialog for a specific app by package name.
     * Used for cross-navigation from other screens (e.g., Firewall -> Packages).
     *
     * This method handles cases where the package might not be in the current filtered list
     * by loading it directly from the repository and automatically switching filters if needed.
     */
    fun openAppDialog(packageName: String) {
        // Prevent multiple dialogs from stacking
        if (currentDialog?.isShowing == true) {
            Log.w(TAG, "[PACKAGES] Dialog already open, dismissing before opening new one")
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
                    val packageRepository = app.dependencies.packageRepository
                    val result = packageRepository.getPackage(packageName)

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

    private fun showPackageActionSheet(pkg: Package) {
        val dialog = BottomSheetDialog(requireContext())
        val binding = BottomSheetPackageActionsBinding.inflate(layoutInflater)

        currentDialog = dialog

        dialog.setOnDismissListener {
            if (currentDialog == dialog) {
                currentDialog = null
            }
        }

        // Set app info
        binding.actionSheetAppName.text = pkg.name
        binding.actionSheetPackageName.text = pkg.packageName

        // ============================================================================
        // Click package name to copy to clipboard
        // ============================================================================
        binding.actionSheetPackageName.setOnClickListenerDebounced {
            requireContext().copyToClipboard(pkg.packageName, "Package Name")
        }

        val realIcon = PackageUtils.getPackageIcon(requireContext(), pkg.packageName)
        if (realIcon != null) {
            binding.actionSheetAppIcon.setImageDrawable(realIcon)
        } else {
            binding.actionSheetAppIcon.setImageResource(R.drawable.de1984_icon)
        }

        // ============================================================================
        // DO NOT REMOVE: Click settings icon to open Android system settings
        // User preference: Settings cog icon on the right opens Android app settings
        // ============================================================================
        binding.actionSheetSettingsIcon.setOnClickListener {
            requireContext().openAppSettings(pkg.packageName)
            dialog.dismiss()
        }

        // Set safety and category badges
        if (pkg.criticality != null && pkg.criticality != PackageCriticality.UNKNOWN) {
            binding.actionSheetBadgesContainer.visibility = View.VISIBLE

            when (pkg.criticality) {
                PackageCriticality.ESSENTIAL -> {
                    binding.actionSheetSafetyBadge.text = "Essential"
                    binding.actionSheetSafetyBadge.setBackgroundResource(R.drawable.safety_badge_essential)
                    binding.actionSheetSafetyBadge.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.badge_essential_text)
                    )
                }
                PackageCriticality.IMPORTANT -> {
                    binding.actionSheetSafetyBadge.text = "Important"
                    binding.actionSheetSafetyBadge.setBackgroundResource(R.drawable.safety_badge_important)
                    binding.actionSheetSafetyBadge.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.badge_important_text)
                    )
                }
                PackageCriticality.OPTIONAL -> {
                    binding.actionSheetSafetyBadge.text = "Optional"
                    binding.actionSheetSafetyBadge.setBackgroundResource(R.drawable.safety_badge_optional)
                    binding.actionSheetSafetyBadge.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.badge_optional_text)
                    )
                }
                PackageCriticality.BLOATWARE -> {
                    binding.actionSheetSafetyBadge.text = "Bloatware"
                    binding.actionSheetSafetyBadge.setBackgroundResource(R.drawable.safety_badge_bloatware)
                    binding.actionSheetSafetyBadge.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.badge_bloatware_text)
                    )
                }
                else -> {}
            }

            if (!pkg.category.isNullOrEmpty()) {
                binding.actionSheetCategoryBadge.visibility = View.VISIBLE
                binding.actionSheetCategoryBadge.text = pkg.category.replace("-", " ").split(" ")
                    .joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }
            } else {
                binding.actionSheetCategoryBadge.visibility = View.GONE
            }
        } else {
            binding.actionSheetBadgesContainer.visibility = View.GONE
        }

        // Show affects list if available
        if (pkg.affects.isNotEmpty()) {
            binding.affectsSection.visibility = View.VISIBLE
            binding.affectsList.removeAllViews()

            pkg.affects.forEach { affect ->
                val textView = TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    text = "• $affect"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.affects_info_box_text))
                    setPadding(0, 4, 0, 4)
                    setSingleLine(false)
                    maxLines = Integer.MAX_VALUE
                    ellipsize = null
                }
                binding.affectsList.addView(textView)
            }
        } else {
            binding.affectsSection.visibility = View.GONE
        }

        // ============================================================================
        // DO NOT REMOVE: Firewall Rules cross-navigation button
        // ============================================================================
        // Setup Firewall Rules action - only show if package has network access
        if (pkg.hasNetworkAccess) {
            binding.firewallRulesAction.visibility = View.VISIBLE
            binding.firewallRulesDivider.visibility = View.VISIBLE
            binding.firewallRulesAction.setOnClickListener {
                dialog.dismiss()
                (requireActivity() as? MainActivity)?.navigateToFirewallWithApp(pkg.packageName)
            }
        } else {
            binding.firewallRulesAction.visibility = View.GONE
            binding.firewallRulesDivider.visibility = View.GONE
        }

        // Setup Force Stop action
        binding.forceStopDescription.text = if (pkg.isEnabled) {
            "Immediately stop all processes"
        } else {
            "Force stop (if running)"
        }

        binding.forceStopAction.setOnClickListener {
            dialog.dismiss()
            showForceStopConfirmation(pkg)
        }

        // Setup Enable/Disable action
        if (pkg.isEnabled) {
            binding.enableDisableIcon.setImageResource(R.drawable.ic_block)
            binding.enableDisableTitle.text = "Disable"
            binding.enableDisableDescription.text = "Prevent this app from running"
        } else {
            binding.enableDisableIcon.setImageResource(R.drawable.ic_check_circle)
            binding.enableDisableTitle.text = "Enable"
            binding.enableDisableDescription.text = "Allow this app to run"
        }

        binding.enableDisableAction.setOnClickListener {
            dialog.dismiss()
            showEnableDisableConfirmation(pkg, !pkg.isEnabled)
        }

        // Setup Uninstall/Reinstall action - conditionally show based on criticality and settings
        val allowCriticalUninstall = settingsViewModel.uiState.value.allowCriticalPackageUninstall
        val isCriticalPackage = pkg.criticality == PackageCriticality.ESSENTIAL ||
                                pkg.criticality == PackageCriticality.IMPORTANT
        val isUninstalled = pkg.versionName == null && !pkg.isEnabled && pkg.type == PackageType.SYSTEM

        if (isCriticalPackage && !allowCriticalUninstall && !isUninstalled) {
            // Hide uninstall button for critical packages when setting is OFF
            binding.uninstallAction.visibility = View.GONE
        } else {
            binding.uninstallAction.visibility = View.VISIBLE

            if (isUninstalled) {
                // Show Reinstall action for uninstalled packages
                binding.uninstallIcon.setImageResource(R.drawable.ic_check_circle)
                val tealColor = ContextCompat.getColor(requireContext(), R.color.lineage_teal)
                binding.uninstallIcon.setColorFilter(tealColor)
                binding.uninstallTitle.text = getString(R.string.action_reinstall)
                binding.uninstallTitle.setTextColor(tealColor)
                binding.uninstallDescription.text = "Restore this system app"

                binding.uninstallAction.setOnClickListener {
                    dialog.dismiss()
                    showReinstallConfirmation(pkg)
                }
            } else {
                // Show Uninstall action for installed packages
                binding.uninstallIcon.setImageResource(R.drawable.ic_delete)
                val redColor = ContextCompat.getColor(requireContext(), R.color.error_red)
                binding.uninstallIcon.setColorFilter(redColor)
                binding.uninstallTitle.text = getString(R.string.action_uninstall)
                binding.uninstallTitle.setTextColor(redColor)
                binding.uninstallDescription.text = if (pkg.type == PackageType.SYSTEM) {
                    "Remove system package (DANGEROUS)"
                } else {
                    "Remove this app from device"
                }

                binding.uninstallAction.setOnClickListener {
                    dialog.dismiss()
                    showUninstallConfirmation(pkg)
                }
            }
        }

        dialog.setContentView(binding.root)
        dialog.show()
    }

    private fun showForceStopConfirmation(pkg: Package) {
        val isSystemPackage = pkg.type == PackageType.SYSTEM

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = "Force Stop ${pkg.name}?",
            message = if (isSystemPackage) {
                "⚠️ This is a system package. Force stopping it may cause system instability.\n\nAre you sure you want to continue?"
            } else {
                "This will immediately stop all processes for ${pkg.name}.\n\nAre you sure?"
            },
            confirmButtonText = "Force Stop",
            onConfirm = {
                viewModel.forceStopPackage(pkg.packageName)
            }
        )
    }

    private fun showEnableDisableConfirmation(pkg: Package, enable: Boolean) {
        val action = if (enable) "Enable" else "Disable"
        val isSystemPackage = pkg.type == PackageType.SYSTEM

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = "$action ${pkg.name}?",
            message = if (isSystemPackage && !enable) {
                "⚠️ This is a system package. Disabling it may cause system instability or prevent other apps from working.\n\nAre you sure you want to continue?"
            } else if (enable) {
                "This will allow ${pkg.name} to run normally.\n\nAre you sure?"
            } else {
                "This will prevent ${pkg.name} from running.\n\nAre you sure?"
            },
            confirmButtonText = action,
            onConfirm = {
                viewModel.setPackageEnabled(pkg.packageName, enable)
            }
        )
    }

    private fun showUninstallConfirmation(pkg: Package) {
        // Show different dialogs based on package criticality
        when (pkg.criticality) {
            PackageCriticality.ESSENTIAL -> {
                // Type-to-confirm for Essential packages
                StandardDialog.showTypeToConfirm(
                    context = requireContext(),
                    title = "🚨 CRITICAL WARNING",
                    message = "⚠️ ESSENTIAL SYSTEM PACKAGE ⚠️\n\n" +
                            "Uninstalling ${pkg.name} WILL BRICK YOUR DEVICE!\n\n" +
                            "This package is critical for system operation. Removing it will make your device unusable and may require a factory reset or reflashing.\n\n" +
                            if (pkg.affects.isNotEmpty()) {
                                "Affects:\n${pkg.affects.joinToString("\n") { "• $it" }}\n\n"
                            } else "" +
                            "Type \"UNINSTALL\" to confirm this dangerous action:",
                    confirmWord = "UNINSTALL",
                    confirmButtonText = "Uninstall",
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.name)
                    }
                )
            }
            PackageCriticality.IMPORTANT -> {
                // Strong warning for Important packages
                StandardDialog.showConfirmation(
                    context = requireContext(),
                    title = "⚠️ Warning: Important Package",
                    message = "Uninstalling ${pkg.name} will cause major system features to stop working.\n\n" +
                            if (pkg.affects.isNotEmpty()) {
                                "Affects:\n${pkg.affects.joinToString("\n") { "• $it" }}\n\n"
                            } else "" +
                            "This may require a factory reset to restore functionality.\n\nAre you sure?",
                    confirmButtonText = "Uninstall",
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.name)
                    }
                )
            }
            PackageCriticality.OPTIONAL -> {
                // Informational for Optional packages
                StandardDialog.showConfirmation(
                    context = requireContext(),
                    title = "Uninstall ${pkg.name}?",
                    message = if (pkg.affects.isNotEmpty()) {
                        "This may affect:\n${pkg.affects.joinToString("\n") { "• $it" }}\n\nAre you sure?"
                    } else {
                        "This will remove ${pkg.name} from your device.\n\nAre you sure?"
                    },
                    confirmButtonText = "Uninstall",
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.name)
                    }
                )
            }
            PackageCriticality.BLOATWARE -> {
                // Positive message for Bloatware
                StandardDialog.showConfirmation(
                    context = requireContext(),
                    title = "Uninstall ${pkg.name}?",
                    message = "✓ Safe to remove\n\nThis package is considered bloatware and can be safely uninstalled.\n\nAre you sure?",
                    confirmButtonText = "Uninstall",
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.name)
                    }
                )
            }
            else -> {
                // Default behavior for unknown packages (fallback to system/user check)
                val isSystemPackage = pkg.type == PackageType.SYSTEM
                StandardDialog.showConfirmation(
                    context = requireContext(),
                    title = "Uninstall ${pkg.name}?",
                    message = if (isSystemPackage) {
                        "⚠️ DANGER: This is a system package. Uninstalling it may brick your device or cause severe system instability.\n\nThis action cannot be easily undone.\n\nAre you absolutely sure?"
                    } else {
                        "This will remove ${pkg.name} from your device.\n\nAre you sure?"
                    },
                    confirmButtonText = "Uninstall",
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.name)
                    }
                )
            }
        }
    }

    private fun showReinstallConfirmation(pkg: Package) {
        StandardDialog.showConfirmation(
            context = requireContext(),
            title = "Reinstall ${pkg.name}?",
            message = "This will restore the system app to your device.\n\nAre you sure?",
            confirmButtonText = "Reinstall",
            onConfirm = {
                viewModel.reinstallPackage(pkg.packageName, pkg.name)
            }
        )
    }

    private fun showError(message: String) {
        // Check if this is a privileged access error
        if (message.contains("Shizuku or root access required", ignoreCase = true)) {
            StandardDialog.showNoAccessDialog(requireContext())
        } else {
            // Show generic error dialog
            StandardDialog.showError(
                context = requireContext(),
                message = message
            )
        }
    }

    // ========== MULTI-SELECT FUNCTIONALITY ==========

    private fun setupSelectionToolbar() {
        // Set background color to match Material 3 theme (supports Dynamic Colors)
        val primaryColor = com.google.android.material.color.MaterialColors.getColor(
            binding.selectionToolbar,
            com.google.android.material.R.attr.colorPrimaryContainer
        )
        binding.selectionToolbar.setBackgroundColor(primaryColor)

        binding.selectionToolbar.setNavigationOnClickListener {
            exitSelectionMode()
        }

        binding.uninstallButton.setOnClickListener {
            if (selectedPackages.isNotEmpty()) {
                val currentState = viewModel.uiState.value
                val isUninstalledFilter = currentState.filterState.packageState?.lowercase() == Constants.Packages.STATE_UNINSTALLED.lowercase()

                if (isUninstalledFilter) {
                    showMultiReinstallConfirmation()
                } else {
                    showMultiUninstallConfirmation()
                }
            }
        }
    }

    private fun enterSelectionMode(initialPackage: Package? = null) {
        isSelectionMode = true
        adapter.setSelectionMode(true)

        // Auto-select the long-pressed package if provided and selectable
        initialPackage?.let { pkg ->
            if (adapter.canSelectPackage(pkg)) {
                adapter.selectPackage(pkg.packageName)
                selectedPackages.add(pkg.packageName)
            }
        }

        binding.selectionToolbar.visibility = View.VISIBLE
        updateSelectionToolbar()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedPackages.clear()
        adapter.setSelectionMode(false)
        adapter.clearSelection()
        binding.selectionToolbar.visibility = View.GONE
    }

    private fun updateSelectionToolbar() {
        val count = selectedPackages.size
        binding.selectionCount.text = String.format(
            Constants.Packages.MultiSelect.TOOLBAR_TITLE_FORMAT,
            count
        )
        binding.uninstallButton.isEnabled = count > 0

        // Update button text based on filter
        val currentState = viewModel.uiState.value
        val isUninstalledFilter = currentState.filterState.packageState?.lowercase() == Constants.Packages.STATE_UNINSTALLED.lowercase()
        binding.uninstallButton.text = if (isUninstalledFilter) {
            Constants.Packages.MultiSelect.TOOLBAR_BUTTON_REINSTALL
        } else {
            Constants.Packages.MultiSelect.TOOLBAR_BUTTON_UNINSTALL
        }
    }

    private fun showMultiUninstallConfirmation() {
        val packages = lastSubmittedPackages.filter { selectedPackages.contains(it.packageName) }

        // Group packages by type and criticality
        val userApps = packages.filter { it.type == PackageType.USER }
        val bloatware = packages.filter { it.criticality == PackageCriticality.BLOATWARE }
        val optional = packages.filter { it.criticality == PackageCriticality.OPTIONAL }

        val message = buildString {
            append(Constants.Packages.MultiSelect.DIALOG_MESSAGE_CANNOT_UNDO)
            append("\n\n")

            if (userApps.isNotEmpty()) {
                append("📱 User Apps (${userApps.size}):\n")
                userApps.take(3).forEach { append("• ${it.name}\n") }
                if (userApps.size > 3) append("... and ${userApps.size - 3} more\n")
                append("\n")
            }

            if (bloatware.isNotEmpty()) {
                append("✓ Bloatware (${bloatware.size}):\n")
                bloatware.take(3).forEach { append("• ${it.name}\n") }
                if (bloatware.size > 3) append("... and ${bloatware.size - 3} more\n")
                append("\n")
            }

            if (optional.isNotEmpty()) {
                append("⚠️ Optional System Apps (${optional.size}):\n")
                optional.take(3).forEach { append("• ${it.name}\n") }
                if (optional.size > 3) append("... and ${optional.size - 3} more\n")
                append("\n")
            }

            append("Are you sure you want to uninstall these ${packages.size} apps?")
        }

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = String.format(
                Constants.Packages.MultiSelect.DIALOG_TITLE_UNINSTALL_MULTIPLE,
                packages.size
            ),
            message = message,
            confirmButtonText = Constants.Packages.MultiSelect.DIALOG_BUTTON_UNINSTALL_ALL,
            onConfirm = {
                performBatchUninstall(selectedPackages.toList())
            },
            cancelButtonText = Constants.Packages.MultiSelect.DIALOG_BUTTON_CANCEL
        )
    }

    private fun performBatchUninstall(packageNames: List<String>) {
        // Dismiss any existing progress dialog
        progressDialog?.dismiss()

        // Show progress dialog
        progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(Constants.Packages.MultiSelect.PROGRESS_DIALOG_TITLE)
            .setMessage(String.format(
                Constants.Packages.MultiSelect.PROGRESS_MESSAGE_FORMAT,
                0,
                packageNames.size
            ))
            .setCancelable(false)
            .create()

        progressDialog?.show()

        // Start batch uninstall
        // Result will be handled by the existing observer in observeUiState() -> updateUI()
        viewModel.uninstallMultiplePackages(packageNames)
    }

    private fun showBatchUninstallResults(result: io.github.dorumrr.de1984.domain.model.UninstallBatchResult) {
        val message = buildString {
            if (result.succeeded.isNotEmpty()) {
                append(String.format(
                    Constants.Packages.MultiSelect.DIALOG_MESSAGE_SUCCESS_FORMAT,
                    result.succeeded.size
                ))
                append("\n")
                result.succeeded.take(5).forEach { packageName ->
                    val pkg = lastSubmittedPackages.find { it.packageName == packageName }
                    append("• ${pkg?.name ?: packageName}\n")
                }
                if (result.succeeded.size > 5) {
                    append("... and ${result.succeeded.size - 5} more\n")
                }
                append("\n")
            }

            if (result.failed.isNotEmpty()) {
                append(String.format(
                    Constants.Packages.MultiSelect.DIALOG_MESSAGE_FAILED_FORMAT,
                    result.failed.size
                ))
                append("\n")
                result.failed.take(5).forEach { (packageName, error) ->
                    val pkg = lastSubmittedPackages.find { it.packageName == packageName }
                    append("• ${pkg?.name ?: packageName}\n")
                    append("  Error: $error\n")
                }
                if (result.failed.size > 5) {
                    append("... and ${result.failed.size - 5} more\n")
                }
            }
        }

        StandardDialog.show(
            context = requireContext(),
            title = Constants.Packages.MultiSelect.DIALOG_TITLE_RESULTS,
            message = message,
            positiveButtonText = Constants.Packages.MultiSelect.DIALOG_BUTTON_OK
        )
    }

    private fun showMultiReinstallConfirmation() {
        val packages = lastSubmittedPackages.filter { selectedPackages.contains(it.packageName) }
        val count = packages.size

        val message = buildString {
            append("Reinstall $count selected system app${if (count > 1) "s" else ""}?\n\n")
            append("The following apps will be reinstalled:\n\n")
            packages.take(10).forEach { pkg ->
                append("• ${pkg.name}\n")
            }
            if (count > 10) {
                append("... and ${count - 10} more\n")
            }
        }

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = "Reinstall $count Selected Apps?",
            message = message,
            confirmButtonText = "Reinstall All",
            onConfirm = {
                performBatchReinstall(selectedPackages.toList())
            },
            cancelButtonText = Constants.Packages.MultiSelect.DIALOG_BUTTON_CANCEL
        )
    }

    private fun performBatchReinstall(packageNames: List<String>) {
        // Dismiss any existing progress dialog
        progressDialog?.dismiss()

        // Show progress dialog
        progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(Constants.Packages.MultiSelect.PROGRESS_DIALOG_TITLE_REINSTALL)
            .setMessage(String.format(
                Constants.Packages.MultiSelect.PROGRESS_MESSAGE_FORMAT_REINSTALL,
                0,
                packageNames.size
            ))
            .setCancelable(false)
            .create()

        progressDialog?.show()

        // Start batch reinstall
        // Result will be handled by the existing observer in observeUiState() -> updateUI()
        viewModel.reinstallMultiplePackages(packageNames)
    }

    private fun showBatchReinstallResults(result: io.github.dorumrr.de1984.domain.model.ReinstallBatchResult) {
        val message = buildString {
            if (result.succeeded.isNotEmpty()) {
                append(String.format(
                    Constants.Packages.MultiSelect.DIALOG_MESSAGE_REINSTALL_SUCCESS_FORMAT,
                    result.succeeded.size
                ))
                append("\n")
                result.succeeded.take(5).forEach { packageName ->
                    val pkg = lastSubmittedPackages.find { it.packageName == packageName }
                    append("• ${pkg?.name ?: packageName}\n")
                }
                if (result.succeeded.size > 5) {
                    append("... and ${result.succeeded.size - 5} more\n")
                }
                append("\n")
            }

            if (result.failed.isNotEmpty()) {
                append(String.format(
                    Constants.Packages.MultiSelect.DIALOG_MESSAGE_REINSTALL_FAILED_FORMAT,
                    result.failed.size
                ))
                append("\n")
                result.failed.take(5).forEach { (packageName, error) ->
                    val pkg = lastSubmittedPackages.find { it.packageName == packageName }
                    append("• ${pkg?.name ?: packageName}\n")
                    append("  Error: $error\n")
                }
                if (result.failed.size > 5) {
                    append("... and ${result.failed.size - 5} more\n")
                }
            }
        }

        StandardDialog.show(
            context = requireContext(),
            title = Constants.Packages.MultiSelect.DIALOG_TITLE_REINSTALL_RESULTS,
            message = message,
            positiveButtonText = Constants.Packages.MultiSelect.DIALOG_BUTTON_OK
        )
    }
}

