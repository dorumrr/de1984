package io.github.dorumrr.de1984.ui.packages

import io.github.dorumrr.de1984.utils.AppLogger
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.BottomSheetPackageActionsBinding
import io.github.dorumrr.de1984.databinding.FragmentPackagesBinding
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageCriticality
import io.github.dorumrr.de1984.domain.model.PackageId
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            shizukuManager = app.dependencies.shizukuManager,
            packageDataChanged = app.dependencies.packageDataChanged
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

    private lateinit var adapter: PackageAdapter
    private var currentTypeFilter: String? = null
    private var currentStateFilter: String? = null
    private var currentProfileFilter: String? = null
    private var lastSubmittedPackages: List<Package> = emptyList()

    // Dialog tracking to prevent dialogs stacking
    private var currentDialog: BottomSheetDialog? = null
    private var dialogOpenTimestamp: Long = 0
    private var pendingDialogPackageId: PackageId? = null

    // Selection mode state
    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<PackageId>()
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
                AppLogger.d(TAG, "🔘 USER ACTION: Package clicked: ${pkg.packageName}")
                showPackageActionSheet(pkg)
            },
            onPackageLongClick = { pkg ->
                AppLogger.d(TAG, "🔘 USER ACTION: Package long-clicked (entering selection mode): ${pkg.packageName}")
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
        // Get translated filter strings
        val packageTypeFilters = listOf(
            getString(io.github.dorumrr.de1984.R.string.packages_filter_all),
            getString(io.github.dorumrr.de1984.R.string.packages_filter_user),
            getString(io.github.dorumrr.de1984.R.string.packages_filter_system)
        )
        val packageStateFilters = listOf(
            getString(io.github.dorumrr.de1984.R.string.packages_filter_enabled),
            getString(io.github.dorumrr.de1984.R.string.packages_filter_disabled),
            getString(io.github.dorumrr.de1984.R.string.status_uninstalled)
        )
        val profileFilters = listOf(
            getString(io.github.dorumrr.de1984.R.string.filter_profile_all),
            getString(io.github.dorumrr.de1984.R.string.filter_profile_personal),
            getString(io.github.dorumrr.de1984.R.string.filter_profile_work),
            getString(io.github.dorumrr.de1984.R.string.filter_profile_clone)
        )

        // Initial setup - only called once
        currentTypeFilter = getString(io.github.dorumrr.de1984.R.string.packages_filter_all)
        currentStateFilter = null
        currentProfileFilter = getString(io.github.dorumrr.de1984.R.string.filter_profile_all)

        FilterChipsHelper.setupMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            typeFilters = packageTypeFilters,
            stateFilters = packageStateFilters,
            permissionFilters = emptyList(),  // No permission filters in Packages screen
            profileFilters = profileFilters,
            selectedTypeFilter = currentTypeFilter,
            selectedStateFilter = currentStateFilter,
            selectedPermissionFilter = false,  // Not used in Packages screen
            selectedProfileFilter = currentProfileFilter,
            onTypeFilterSelected = { filter ->
                // Only trigger if different from current
                if (filter != currentTypeFilter) {
                    // Don't clear adapter - let ViewModel handle the state transition
                    currentTypeFilter = filter
                    // Map translated string to internal constant
                    val internalFilter = mapTypeFilterToInternal(filter)
                    viewModel.setPackageTypeFilter(internalFilter)
                }
            },
            onStateFilterSelected = { filter ->
                // Only trigger if different from current
                if (filter != currentStateFilter) {
                    // Map translated string to internal constant BEFORE updating currentStateFilter
                    val internalFilter = filter?.let { mapStateFilterToInternal(it) }

                    // Exit selection mode if switching to Disabled or Uninstalled filter
                    val isRestrictedFilter = internalFilter?.lowercase() == Constants.Packages.STATE_DISABLED.lowercase() ||
                                              internalFilter?.lowercase() == Constants.Packages.STATE_UNINSTALLED.lowercase()
                    if (isSelectionMode && isRestrictedFilter) {
                        exitSelectionMode()
                    }

                    // Don't clear adapter - let ViewModel handle the state transition
                    currentStateFilter = filter
                    viewModel.setPackageStateFilter(internalFilter)
                }
            },
            onPermissionFilterSelected = { _ ->
                // Not used in Packages screen
            },
            onProfileFilterSelected = { filter ->
                if (filter != currentProfileFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Profile filter changed: $filter")
                    currentProfileFilter = filter
                    // Map translated string to internal constant
                    val internalFilter = mapProfileFilterToInternal(filter)
                    viewModel.setProfileFilter(internalFilter)
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
        packageStateFilter: String?,
        profileFilter: String
    ) {
        // Map internal constants to translated strings
        val translatedTypeFilter = mapInternalToTypeFilter(packageTypeFilter)
        val translatedStateFilter = packageStateFilter?.let { mapInternalToStateFilter(it) }
        val translatedProfileFilter = mapInternalToProfileFilter(profileFilter)

        // Only update if filters have changed
        if (translatedTypeFilter == currentTypeFilter &&
            translatedStateFilter == currentStateFilter &&
            translatedProfileFilter == currentProfileFilter) {
            return
        }

        currentTypeFilter = translatedTypeFilter
        currentStateFilter = translatedStateFilter
        currentProfileFilter = translatedProfileFilter

        // Update chip selection without recreating or triggering listeners
        FilterChipsHelper.updateMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            selectedTypeFilter = translatedTypeFilter,
            selectedStateFilter = translatedStateFilter,
            selectedPermissionFilter = false,  // Not used in Packages screen
            selectedProfileFilter = translatedProfileFilter
        )
    }

    private fun setupPermissionDialog() {
        AppLogger.d(TAG, "setupPermissionDialog called")
        // Observe privileged access status and show modal dialog when needed
        observePrivilegedAccessStatus()
    }

    private fun observePrivilegedAccessStatus() {
        AppLogger.d(TAG, "observePrivilegedAccessStatus called")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppLogger.d(TAG, "Starting privileged access status observation")
                // Combine both status flows to determine banner state
                launch {
                    viewModel.rootManager.rootStatus.collect { rootStatus ->
                        AppLogger.d(TAG, "Root status changed: $rootStatus")
                        updateBannerContent(rootStatus, viewModel.shizukuManager.shizukuStatus.value)
                    }
                }
                launch {
                    viewModel.shizukuManager.shizukuStatus.collect { shizukuStatus ->
                        AppLogger.d(TAG, "Shizuku status changed: $shizukuStatus")
                        updateBannerContent(viewModel.rootManager.rootStatus.value, shizukuStatus)
                    }
                }
            }
        }
    }

    private fun updateBannerContent(rootStatus: RootStatus, shizukuStatus: ShizukuStatus) {
        AppLogger.d(TAG, "updateBannerContent: rootStatus=$rootStatus, shizukuStatus=$shizukuStatus")
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
        AppLogger.d(TAG, "showPermissionSetupDialog called")

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
            packageStateFilter = state.filterState.packageState,
            profileFilter = state.filterState.profileFilter
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
        binding.packageCounter.text = if (count == 0 && state.searchQuery.isBlank()) {
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
     * Open the package action dialog for a specific app by package name and user ID.
     * Used for cross-navigation from other screens (e.g., Firewall -> Packages).
     *
     * This method handles cases where the package might not be in the current filtered list
     * by loading it directly from the repository and automatically switching filters if needed.
     *
     * @param packageName The package name of the app
     * @param userId Android user profile ID (0 = personal, 10+ = work/clone profiles)
     */
    fun openAppDialog(packageName: String, userId: Int = 0) {
        // Prevent multiple dialogs from stacking
        if (currentDialog?.isShowing == true) {
            AppLogger.w(TAG, "[PACKAGES] Dialog already open, dismissing before opening new one")
            currentDialog?.dismiss()
            currentDialog = null
        }

        // Find the package in the current list (match both packageName and userId)
        val pkg = viewModel.uiState.value.packages.find {
            it.packageName == packageName && it.userId == userId
        }

        val targetPackageId = PackageId(packageName, userId)

        if (pkg != null) {
            pendingDialogPackageId = null
            scrollToPackage(packageName)
            showPackageActionSheet(pkg)
        } else {
            // Package not in filtered list - need to load it and possibly change filter
            pendingDialogPackageId = targetPackageId

            // Package not in filtered list - try to get it directly from repository
            lifecycleScope.launch {
                try {
                    // Get package from repository (bypasses filter)
                    val app = requireActivity().application as De1984Application
                    val packageRepository = app.dependencies.packageRepository
                    val result = packageRepository.getPackage(packageName, userId)

                    result.onSuccess { foundPkg ->
                        // Check if this request is still valid
                        if (pendingDialogPackageId != targetPackageId) {
                            return@onSuccess
                        }

                        // Check if we need to change filter to show this package
                        val currentFilter = viewModel.uiState.value.filterState.packageType
                        val packageType = foundPkg.type.toString()

                        if (currentFilter.equals(packageType, ignoreCase = true)) {
                            // Filter already matches - just wait for data to load
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
                            // Need to change filter
                            viewModel.setPackageTypeFilter(packageType)

                            // Wait for filter change and data load
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

    private fun showPackageActionSheet(pkg: Package) {
        val dialog = BottomSheetDialog(requireContext())
        val binding = BottomSheetPackageActionsBinding.inflate(layoutInflater)

        currentDialog = dialog

        dialog.setOnDismissListener {
            if (currentDialog == dialog) {
                currentDialog = null
            }
        }

        // Set app info with async icon loading (prevents UI freeze for work profile apps)
        binding.actionSheetAppIcon.setImageResource(R.drawable.de1984_icon) // Placeholder
        binding.actionSheetAppName.text = pkg.name
        binding.actionSheetPackageName.text = pkg.packageName

        // Load icon asynchronously to prevent blocking main thread
        // Work profile apps require slow shell commands via HiddenApiHelper
        // IMPORTANT: Capture context BEFORE entering coroutine to avoid IllegalStateException
        val context = requireContext()
        lifecycleScope.launch {
            val icon = withContext(Dispatchers.IO) {
                PackageUtils.getPackageIcon(context, pkg.packageName, pkg.userId)
            }
            // Update icon if dialog is still showing and fragment is attached
            if (dialog.isShowing && isAdded) {
                icon?.let { binding.actionSheetAppIcon.setImageDrawable(it) }
            }
        }

        // ============================================================================
        // Click package name to copy to clipboard
        // ============================================================================
        binding.actionSheetPackageName.setOnClickListenerDebounced {
            requireContext().copyToClipboard(pkg.packageName, getString(io.github.dorumrr.de1984.R.string.action_sheet_package_name_label))
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
                    binding.actionSheetSafetyBadge.text = getString(R.string.action_sheet_safety_badge_essential)
                    binding.actionSheetSafetyBadge.setBackgroundResource(R.drawable.safety_badge_essential)
                    binding.actionSheetSafetyBadge.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.badge_essential_text)
                    )
                }
                PackageCriticality.IMPORTANT -> {
                    binding.actionSheetSafetyBadge.text = getString(R.string.action_sheet_safety_badge_important)
                    binding.actionSheetSafetyBadge.setBackgroundResource(R.drawable.safety_badge_important)
                    binding.actionSheetSafetyBadge.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.badge_important_text)
                    )
                }
                PackageCriticality.OPTIONAL -> {
                    binding.actionSheetSafetyBadge.text = getString(R.string.action_sheet_safety_badge_optional)
                    binding.actionSheetSafetyBadge.setBackgroundResource(R.drawable.safety_badge_optional)
                    binding.actionSheetSafetyBadge.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.badge_optional_text)
                    )
                }
                PackageCriticality.BLOATWARE -> {
                    binding.actionSheetSafetyBadge.text = getString(R.string.action_sheet_safety_badge_bloatware)
                    binding.actionSheetSafetyBadge.setBackgroundResource(R.drawable.safety_badge_bloatware)
                    binding.actionSheetSafetyBadge.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.badge_bloatware_text)
                    )
                }
                else -> {}
            }

            if (!pkg.category.isNullOrEmpty()) {
                binding.actionSheetCategoryBadge.visibility = View.VISIBLE
                binding.actionSheetCategoryBadge.text = getCategoryDisplayName(pkg.category)
            } else {
                binding.actionSheetCategoryBadge.visibility = View.GONE
            }
        } else {
            binding.actionSheetBadgesContainer.visibility = View.GONE
        }

        // Show affects list if available (English only - JSON data is not translated)
        val currentLocale = resources.configuration.locales[0]
        val isEnglish = currentLocale.language == "en"

        if (pkg.affects.isNotEmpty() && isEnglish) {
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
                (requireActivity() as? MainActivity)?.navigateToFirewallWithApp(pkg.packageName, pkg.userId)
            }
        } else {
            binding.firewallRulesAction.visibility = View.GONE
            binding.firewallRulesDivider.visibility = View.GONE
        }

        // Setup Force Stop action
        binding.forceStopDescription.text = if (pkg.isEnabled) {
            getString(io.github.dorumrr.de1984.R.string.action_sheet_force_stop_desc_running)
        } else {
            getString(io.github.dorumrr.de1984.R.string.action_sheet_force_stop_desc_not_running)
        }

        binding.forceStopAction.setOnClickListener {
            dialog.dismiss()
            showForceStopConfirmation(pkg)
        }

        // Setup Enable/Disable action
        if (pkg.isEnabled) {
            binding.enableDisableIcon.setImageResource(R.drawable.ic_block)
            binding.enableDisableTitle.text = getString(io.github.dorumrr.de1984.R.string.action_disable)
            binding.enableDisableDescription.text = getString(io.github.dorumrr.de1984.R.string.action_sheet_disable_desc)
        } else {
            binding.enableDisableIcon.setImageResource(R.drawable.ic_check_circle)
            binding.enableDisableTitle.text = getString(io.github.dorumrr.de1984.R.string.action_enable)
            binding.enableDisableDescription.text = getString(io.github.dorumrr.de1984.R.string.action_sheet_enable_desc)
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

        // Show protection warning banner if package is critical and protection is enabled
        if (isCriticalPackage && !allowCriticalUninstall && !isUninstalled) {
            binding.protectionWarningBanner.root.visibility = View.VISIBLE

            // Set banner message
            binding.protectionWarningBanner.bannerMessage.text = getString(R.string.protection_banner_message_uninstall)

            // Setup Settings button click listener
            binding.protectionWarningBanner.bannerSettingsButton.setOnClickListener {
                dialog.dismiss()
                (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToSettings()
            }

            // Show uninstall button but make it disabled
            binding.uninstallAction.visibility = View.VISIBLE
            binding.uninstallAction.isEnabled = false
            binding.uninstallAction.alpha = 0.5f

            binding.uninstallIcon.setImageResource(R.drawable.ic_delete)
            val redColor = ContextCompat.getColor(requireContext(), R.color.error_red)
            binding.uninstallIcon.setColorFilter(redColor)
            binding.uninstallIcon.alpha = 0.5f
            binding.uninstallTitle.text = getString(R.string.action_uninstall)
            binding.uninstallTitle.setTextColor(redColor)
            binding.uninstallTitle.alpha = 0.5f
            binding.uninstallDescription.text = getString(R.string.action_sheet_uninstall_desc)
            binding.uninstallDescription.alpha = 0.5f

            // Add click listener to show snackbar
            binding.uninstallAction.setOnClickListener {
                showUninstallProtectionSnackbar(dialog)
            }
        } else {
            binding.protectionWarningBanner.root.visibility = View.GONE
            binding.uninstallAction.visibility = View.VISIBLE
            binding.uninstallAction.isEnabled = true
            binding.uninstallAction.alpha = 1.0f

            if (isUninstalled) {
                // Show Reinstall action for uninstalled packages
                binding.uninstallIcon.setImageResource(R.drawable.ic_check_circle)
                val tealColor = ContextCompat.getColor(requireContext(), R.color.lineage_teal)
                binding.uninstallIcon.setColorFilter(tealColor)
                binding.uninstallIcon.alpha = 1.0f
                binding.uninstallTitle.text = getString(R.string.action_reinstall)
                binding.uninstallTitle.setTextColor(tealColor)
                binding.uninstallTitle.alpha = 1.0f
                binding.uninstallDescription.text = getString(R.string.action_sheet_reinstall_desc)
                binding.uninstallDescription.alpha = 1.0f

                binding.uninstallAction.setOnClickListener {
                    dialog.dismiss()
                    showReinstallConfirmation(pkg)
                }
            } else {
                // Show Uninstall action for installed packages
                binding.uninstallIcon.setImageResource(R.drawable.ic_delete)
                val redColor = ContextCompat.getColor(requireContext(), R.color.error_red)
                binding.uninstallIcon.setColorFilter(redColor)
                binding.uninstallIcon.alpha = 1.0f
                binding.uninstallTitle.text = getString(R.string.action_uninstall)
                binding.uninstallTitle.setTextColor(redColor)
                binding.uninstallTitle.alpha = 1.0f
                binding.uninstallDescription.text = getString(R.string.action_sheet_uninstall_desc)
                binding.uninstallDescription.alpha = 1.0f

                binding.uninstallAction.setOnClickListener {
                    dialog.dismiss()
                    showUninstallConfirmation(pkg)
                }
            }
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

    private fun showForceStopConfirmation(pkg: Package) {
        val isSystemPackage = pkg.type == PackageType.SYSTEM

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(io.github.dorumrr.de1984.R.string.dialog_force_stop_title, pkg.name),
            message = if (isSystemPackage) {
                getString(io.github.dorumrr.de1984.R.string.dialog_force_stop_system_message)
            } else {
                getString(io.github.dorumrr.de1984.R.string.dialog_force_stop_message, pkg.name)
            },
            confirmButtonText = getString(io.github.dorumrr.de1984.R.string.action_force_stop),
            onConfirm = {
                viewModel.forceStopPackage(pkg.packageName)
            }
        )
    }

    private fun showEnableDisableConfirmation(pkg: Package, enable: Boolean) {
        val action = if (enable) {
            getString(io.github.dorumrr.de1984.R.string.action_enable)
        } else {
            getString(io.github.dorumrr.de1984.R.string.action_disable)
        }
        val isSystemPackage = pkg.type == PackageType.SYSTEM

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(io.github.dorumrr.de1984.R.string.dialog_enable_disable_title, action, pkg.name),
            message = if (isSystemPackage && !enable) {
                getString(io.github.dorumrr.de1984.R.string.dialog_disable_system_message)
            } else if (enable) {
                getString(io.github.dorumrr.de1984.R.string.dialog_enable_message, pkg.name)
            } else {
                getString(io.github.dorumrr.de1984.R.string.dialog_disable_message, pkg.name)
            },
            confirmButtonText = action,
            onConfirm = {
                viewModel.setPackageEnabled(pkg.packageName, pkg.userId, enable)
            }
        )
    }

    private fun showUninstallConfirmation(pkg: Package) {
        // Show different dialogs based on package criticality
        when (pkg.criticality) {
            PackageCriticality.ESSENTIAL -> {
                // Type-to-confirm for Essential packages
                val affectsText = if (pkg.affects.isNotEmpty()) {
                    getString(R.string.uninstall_dialog_affects_prefix, pkg.affects.joinToString("\n") { "• $it" })
                } else ""

                StandardDialog.showTypeToConfirm(
                    context = requireContext(),
                    title = getString(R.string.uninstall_dialog_title_critical),
                    message = getString(R.string.uninstall_dialog_message_essential, pkg.name, affectsText),
                    confirmWord = getString(R.string.uninstall_dialog_confirm_word),
                    confirmButtonText = getString(R.string.uninstall_dialog_button_uninstall),
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.userId, pkg.name)
                    }
                )
            }
            PackageCriticality.IMPORTANT -> {
                // Strong warning for Important packages
                val affectsText = if (pkg.affects.isNotEmpty()) {
                    getString(R.string.uninstall_dialog_affects_prefix, pkg.affects.joinToString("\n") { "• $it" })
                } else ""

                StandardDialog.showConfirmation(
                    context = requireContext(),
                    title = getString(R.string.uninstall_dialog_title_important),
                    message = getString(R.string.uninstall_dialog_message_important, pkg.name, affectsText),
                    confirmButtonText = getString(R.string.uninstall_dialog_button_uninstall),
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.userId, pkg.name)
                    }
                )
            }
            PackageCriticality.OPTIONAL -> {
                // Informational for Optional packages
                StandardDialog.showConfirmation(
                    context = requireContext(),
                    title = getString(R.string.uninstall_dialog_title_optional, pkg.name),
                    message = if (pkg.affects.isNotEmpty()) {
                        getString(R.string.uninstall_dialog_message_optional_with_affects, pkg.affects.joinToString("\n") { "• $it" })
                    } else {
                        getString(R.string.uninstall_dialog_message_optional, pkg.name)
                    },
                    confirmButtonText = getString(R.string.uninstall_dialog_button_uninstall),
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.userId, pkg.name)
                    }
                )
            }
            PackageCriticality.BLOATWARE -> {
                // Positive message for Bloatware
                StandardDialog.showConfirmation(
                    context = requireContext(),
                    title = getString(R.string.uninstall_dialog_title_bloatware, pkg.name),
                    message = getString(R.string.uninstall_dialog_message_bloatware),
                    confirmButtonText = getString(R.string.uninstall_dialog_button_uninstall),
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.userId, pkg.name)
                    }
                )
            }
            else -> {
                // Default behavior for unknown packages (fallback to system/user check)
                val isSystemPackage = pkg.type == PackageType.SYSTEM
                StandardDialog.showConfirmation(
                    context = requireContext(),
                    title = getString(R.string.uninstall_dialog_title_unknown, pkg.name),
                    message = if (isSystemPackage) {
                        getString(R.string.uninstall_dialog_message_unknown_system)
                    } else {
                        getString(R.string.uninstall_dialog_message_unknown_user, pkg.name)
                    },
                    confirmButtonText = getString(R.string.uninstall_dialog_button_uninstall),
                    onConfirm = {
                        viewModel.uninstallPackage(pkg.packageName, pkg.userId, pkg.name)
                    }
                )
            }
        }
    }

    private fun showReinstallConfirmation(pkg: Package) {
        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.reinstall_dialog_title, pkg.name),
            message = getString(R.string.reinstall_dialog_message),
            confirmButtonText = getString(R.string.reinstall_dialog_button_reinstall),
            onConfirm = {
                viewModel.reinstallPackage(pkg.packageName, pkg.userId, pkg.name)
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

    /**
     * Check if multi-select mode is allowed for the current filter.
     * Multi-select is NOT allowed for Disabled or Uninstalled filters because:
     * - Disabled packages: can't be uninstalled without enabling first
     * - Uninstalled packages: already uninstalled, reinstall should be done individually
     */
    private fun isSelectionModeAllowedForCurrentFilter(): Boolean {
        val currentState = viewModel.uiState.value.filterState.packageState?.lowercase()
        return currentState != Constants.Packages.STATE_DISABLED.lowercase() &&
               currentState != Constants.Packages.STATE_UNINSTALLED.lowercase()
    }

    private fun enterSelectionMode(initialPackage: Package? = null) {
        // Block selection mode for Disabled and Uninstalled filters
        if (!isSelectionModeAllowedForCurrentFilter()) {
            val currentState = viewModel.uiState.value.filterState.packageState?.lowercase()
            val toastMessage = when (currentState) {
                Constants.Packages.STATE_DISABLED.lowercase() ->
                    getString(R.string.multiselect_toast_not_available_disabled)
                Constants.Packages.STATE_UNINSTALLED.lowercase() ->
                    getString(R.string.multiselect_toast_not_available_uninstalled)
                else -> return
            }
            android.widget.Toast.makeText(requireContext(), toastMessage, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        isSelectionMode = true
        adapter.setSelectionMode(true)

        // Auto-select the long-pressed package if provided and selectable
        initialPackage?.let { pkg ->
            if (adapter.canSelectPackage(pkg)) {
                adapter.selectPackage(pkg.id)
                selectedPackages.add(pkg.id)
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
        binding.selectionCount.text = getString(R.string.multiselect_toolbar_title_format, count)
        binding.uninstallButton.isEnabled = count > 0

        // Update button text based on filter
        val currentState = viewModel.uiState.value
        val isUninstalledFilter = currentState.filterState.packageState?.lowercase() == Constants.Packages.STATE_UNINSTALLED.lowercase()
        binding.uninstallButton.text = if (isUninstalledFilter) {
            getString(R.string.multiselect_toolbar_button_reinstall)
        } else {
            getString(R.string.multiselect_toolbar_button_uninstall)
        }
    }

    private fun showMultiUninstallConfirmation() {
        val packages = lastSubmittedPackages.filter { selectedPackages.contains(it.id) }

        // Group packages by type and criticality
        val userApps = packages.filter { it.type == PackageType.USER }
        val bloatware = packages.filter { it.criticality == PackageCriticality.BLOATWARE }
        val optional = packages.filter { it.criticality == PackageCriticality.OPTIONAL }

        val message = buildString {
            append(Constants.Packages.MultiSelect.DIALOG_MESSAGE_CANNOT_UNDO)
            append("\n\n")

            if (userApps.isNotEmpty()) {
                append(getString(R.string.batch_uninstall_dialog_category_user_apps, userApps.size))
                append("\n")
                userApps.take(3).forEach { append("• ${it.name}\n") }
                if (userApps.size > 3) append(getString(R.string.batch_uninstall_dialog_and_more, userApps.size - 3) + "\n")
                append("\n")
            }

            if (bloatware.isNotEmpty()) {
                append(getString(R.string.batch_uninstall_dialog_category_bloatware, bloatware.size))
                append("\n")
                bloatware.take(3).forEach { append("• ${it.name}\n") }
                if (bloatware.size > 3) append(getString(R.string.batch_uninstall_dialog_and_more, bloatware.size - 3) + "\n")
                append("\n")
            }

            if (optional.isNotEmpty()) {
                append(getString(R.string.batch_uninstall_dialog_category_optional, optional.size))
                append("\n")
                optional.take(3).forEach { append("• ${it.name}\n") }
                if (optional.size > 3) append(getString(R.string.batch_uninstall_dialog_and_more, optional.size - 3) + "\n")
                append("\n")
            }

            append(getString(R.string.batch_uninstall_dialog_message_question, packages.size))
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
                // Convert selected PackageIds to pairs with userId
                val packagesWithUserId = selectedPackages.map { it.packageName to it.userId }
                performBatchUninstall(packagesWithUserId)
            },
            cancelButtonText = Constants.Packages.MultiSelect.DIALOG_BUTTON_CANCEL
        )
    }

    private fun performBatchUninstall(packages: List<Pair<String, Int>>) {
        // Dismiss any existing progress dialog
        progressDialog?.dismiss()

        // Show progress dialog
        progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(Constants.Packages.MultiSelect.PROGRESS_DIALOG_TITLE)
            .setMessage(String.format(
                Constants.Packages.MultiSelect.PROGRESS_MESSAGE_FORMAT,
                0,
                packages.size
            ))
            .setCancelable(false)
            .create()

        progressDialog?.show()

        // Start batch uninstall
        // Result will be handled by the existing observer in observeUiState() -> updateUI()
        viewModel.uninstallMultiplePackages(packages)
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
                    append(getString(R.string.batch_uninstall_results_and_more, result.succeeded.size - 5) + "\n")
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
                    append(getString(R.string.batch_uninstall_results_error_prefix, error) + "\n")
                }
                if (result.failed.size > 5) {
                    append(getString(R.string.batch_uninstall_results_and_more, result.failed.size - 5) + "\n")
                }
            }
        }

        StandardDialog.show(
            context = requireContext(),
            title = Constants.Packages.MultiSelect.DIALOG_TITLE_RESULTS,
            message = message,
            positiveButtonText = getString(R.string.dialog_ok)
        )
    }

    private fun showMultiReinstallConfirmation() {
        val packages = lastSubmittedPackages.filter { selectedPackages.contains(it.id) }
        val count = packages.size

        val message = buildString {
            val pluralSuffix = if (count > 1) "s" else ""
            append(getString(R.string.batch_reinstall_dialog_message_prefix, count, pluralSuffix))
            packages.take(10).forEach { pkg ->
                append(getString(R.string.batch_reinstall_dialog_message_item, pkg.name))
            }
            if (count > 10) {
                append(getString(R.string.batch_reinstall_dialog_message_more, count - 10))
            }
        }

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.batch_reinstall_dialog_title, count),
            message = message,
            confirmButtonText = getString(R.string.batch_reinstall_dialog_button_reinstall_all),
            onConfirm = {
                // Convert selected PackageIds to pairs with userId
                val packagesWithUserId = selectedPackages.map { it.packageName to it.userId }
                performBatchReinstall(packagesWithUserId)
            },
            cancelButtonText = getString(R.string.dialog_cancel)
        )
    }

    private fun performBatchReinstall(packages: List<Pair<String, Int>>) {
        // Dismiss any existing progress dialog
        progressDialog?.dismiss()

        // Show progress dialog
        progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(Constants.Packages.MultiSelect.PROGRESS_DIALOG_TITLE_REINSTALL)
            .setMessage(String.format(
                Constants.Packages.MultiSelect.PROGRESS_MESSAGE_FORMAT_REINSTALL,
                0,
                packages.size
            ))
            .setCancelable(false)
            .create()

        progressDialog?.show()

        // Start batch reinstall
        // Result will be handled by the existing observer in observeUiState() -> updateUI()
        viewModel.reinstallMultiplePackages(packages)
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
                    append(getString(R.string.batch_uninstall_results_and_more, result.succeeded.size - 5) + "\n")
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
                    append(getString(R.string.batch_uninstall_results_error_prefix, error) + "\n")
                }
                if (result.failed.size > 5) {
                    append(getString(R.string.batch_uninstall_results_and_more, result.failed.size - 5) + "\n")
                }
            }
        }

        StandardDialog.show(
            context = requireContext(),
            title = Constants.Packages.MultiSelect.DIALOG_TITLE_REINSTALL_RESULTS,
            message = message,
            positiveButtonText = getString(R.string.dialog_ok)
        )
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
            getString(io.github.dorumrr.de1984.R.string.packages_filter_enabled) -> Constants.Packages.STATE_ENABLED
            getString(io.github.dorumrr.de1984.R.string.packages_filter_disabled) -> Constants.Packages.STATE_DISABLED
            getString(io.github.dorumrr.de1984.R.string.status_uninstalled) -> Constants.Packages.STATE_UNINSTALLED
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
            Constants.Packages.STATE_ENABLED -> getString(io.github.dorumrr.de1984.R.string.packages_filter_enabled)
            Constants.Packages.STATE_DISABLED -> getString(io.github.dorumrr.de1984.R.string.packages_filter_disabled)
            Constants.Packages.STATE_UNINSTALLED -> getString(io.github.dorumrr.de1984.R.string.status_uninstalled)
            else -> internalFilter // Fallback to original
        }
    }

    /**
     * Map translated profile filter string to internal constant
     */
    private fun mapProfileFilterToInternal(translatedFilter: String): String {
        return when (translatedFilter) {
            getString(io.github.dorumrr.de1984.R.string.filter_profile_all) -> "All"
            getString(io.github.dorumrr.de1984.R.string.filter_profile_personal) -> "Personal"
            getString(io.github.dorumrr.de1984.R.string.filter_profile_work) -> "Work"
            getString(io.github.dorumrr.de1984.R.string.filter_profile_clone) -> "Clone"
            else -> "All" // Default fallback
        }
    }

    /**
     * Map internal constant to translated profile filter string
     */
    private fun mapInternalToProfileFilter(internalFilter: String): String {
        return when (internalFilter) {
            "All" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_all)
            "Personal" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_personal)
            "Work" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_work)
            "Clone" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_clone)
            else -> getString(io.github.dorumrr.de1984.R.string.filter_profile_all) // Default fallback
        }
    }

    /**
     * Get translated display name for package category
     */
    private fun getCategoryDisplayName(category: String): String {
        val stringResId = when (category) {
            "system-core" -> R.string.category_system_core
            "system-ui" -> R.string.category_system_ui
            "google-services" -> R.string.category_google_services
            "connectivity" -> R.string.category_connectivity
            "telephony" -> R.string.category_telephony
            "media" -> R.string.category_media
            "social" -> R.string.category_social
            "assistant" -> R.string.category_assistant
            "vendor" -> R.string.category_vendor
            "unknown" -> R.string.category_unknown
            else -> {
                // Fallback: format the category string (capitalize words, replace dashes)
                return category.replace("-", " ").split(" ")
                    .joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }
            }
        }
        return getString(stringResId)
    }

    /**
     * Show snackbar informing user that package is protected from uninstall.
     * Provides action button to navigate to Settings.
     */
    private fun showUninstallProtectionSnackbar(dialog: BottomSheetDialog) {
        val parentView = dialog.window?.decorView ?: requireView()
        Snackbar.make(
            parentView,
            getString(R.string.snackbar_uninstall_protected),
            Snackbar.LENGTH_LONG
        ).setAction(getString(R.string.snackbar_action_settings)) {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToSettings()
        }.show()
    }
}

