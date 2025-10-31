package io.github.dorumrr.de1984.ui.packages

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
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
            app.dependencies.firewallRepository
        )
    }

    private lateinit var adapter: PackageAdapter
    private var currentTypeFilter: String? = null
    private var currentStateFilter: String? = null
    private var lastSubmittedPackages: List<Package> = emptyList()



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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show loading state immediately until first state emission
        binding.loadingState.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.packagesRecyclerView.visibility = View.GONE

        setupRecyclerView()
        setupFilterChips()
        setupSearchBox()
        setupPermissionDialog()
        observeUiState()
        observeSettings()

        // Check root access
        viewModel.checkRootAccess()

        // Add layout change listener to track when RecyclerView actually renders
        binding.packagesRecyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // RecyclerView layout changed
        }
    }

    private fun setupRecyclerView() {
        adapter = PackageAdapter(
            showIcons = true, // Will be updated from settings
            onPackageClick = { pkg ->
                showPackageActionSheet(pkg)
            }
        )

        binding.packagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PackagesFragmentViews.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupFilterChips() {
        val packageTypeFilters = Constants.Packages.PACKAGE_TYPE_FILTERS
        val packageStateFilters = Constants.Packages.PACKAGE_STATE_FILTERS

        // Initial setup - only called once
        currentTypeFilter = "User"
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
        // Text change listener for real-time search
        binding.searchInput.addTextChangedListener { text ->
            val query = text?.toString() ?: ""
            viewModel.setSearchQuery(query)
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



        // Show error if any
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

    private fun showPackageActionSheet(pkg: Package) {
        val dialog = BottomSheetDialog(requireContext())
        val binding = BottomSheetPackageActionsBinding.inflate(layoutInflater)

        // Set app info
        binding.actionSheetAppName.text = pkg.name
        binding.actionSheetPackageName.text = pkg.packageName

        val realIcon = PackageUtils.getPackageIcon(requireContext(), pkg.packageName)
        if (realIcon != null) {
            binding.actionSheetAppIcon.setImageDrawable(realIcon)
        } else {
            binding.actionSheetAppIcon.setImageResource(R.drawable.de1984_icon)
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

        // Setup Uninstall action
        binding.uninstallDescription.text = if (pkg.type == PackageType.SYSTEM) {
            "Remove system package (DANGEROUS)"
        } else {
            "Remove this app from device"
        }

        binding.uninstallAction.setOnClickListener {
            dialog.dismiss()
            showUninstallConfirmation(pkg)
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
                viewModel.uninstallPackage(pkg.packageName)
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
}

