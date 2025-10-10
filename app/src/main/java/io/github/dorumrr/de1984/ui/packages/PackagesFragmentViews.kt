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
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.PackageUtils
import kotlinx.coroutines.launch

class PackagesFragmentViews : BaseFragment<FragmentPackagesBinding>() {

    private val TAG = "PackagesFragmentViews"

    private val viewModel: PackagesViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        PackagesViewModel.Factory(
            getPackagesUseCase = app.dependencies.provideGetPackagesUseCase(),
            managePackageUseCase = app.dependencies.provideManagePackageUseCase(),
            superuserBannerState = app.dependencies.superuserBannerState,
            rootManager = app.dependencies.rootManager
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        SettingsViewModel.Factory(
            requireContext(),
            app.dependencies.permissionManager,
            app.dependencies.rootManager
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
        binding.packagesRecyclerView.scrollToPosition(0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show loading state immediately until first state emission
        binding.loadingState.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.packagesRecyclerView.visibility = View.GONE

        setupRecyclerView()
        setupFilterChips()
        setupRootBanner()
        observeUiState()
        observeSettings()

        // Check root access
        viewModel.checkRootAccess()

        // Add layout change listener to track when RecyclerView actually renders
        binding.packagesRecyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            Log.d(TAG, ">>> RecyclerView layout changed, adapter.itemCount=${adapter.itemCount}, childCount=${binding.packagesRecyclerView.childCount}")
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
            selectedTypeFilter = currentTypeFilter,
            selectedStateFilter = currentStateFilter,
            onTypeFilterSelected = { filter ->
                val timestamp = System.currentTimeMillis()
                Log.d(TAG, "════════════════════════════════════════════════════════════════")
                Log.d(TAG, "[$timestamp] 🔘 TYPE FILTER CLICKED: $filter")
                Log.d(TAG, "[$timestamp] Current type filter: $currentTypeFilter")
                Log.d(TAG, "[$timestamp] Thread: ${Thread.currentThread().name}")

                // Only trigger if different from current
                if (filter != currentTypeFilter) {
                    Log.d(TAG, "[$timestamp] Filter CHANGED from $currentTypeFilter to $filter")

                    // Don't clear adapter - let ViewModel handle the state transition
                    currentTypeFilter = filter
                    Log.d(TAG, "[$timestamp] Calling viewModel.setPackageTypeFilter($filter)...")
                    viewModel.setPackageTypeFilter(filter)
                    Log.d(TAG, "[$timestamp] viewModel.setPackageTypeFilter() returned")
                } else {
                    Log.d(TAG, "[$timestamp] Filter UNCHANGED, ignoring click")
                }
                Log.d(TAG, "════════════════════════════════════════════════════════════════")
            },
            onStateFilterSelected = { filter ->
                val timestamp = System.currentTimeMillis()
                Log.d(TAG, "════════════════════════════════════════════════════════════════")
                Log.d(TAG, "[$timestamp] 🔘 STATE FILTER CLICKED: $filter")
                Log.d(TAG, "[$timestamp] Current state filter: $currentStateFilter")
                Log.d(TAG, "[$timestamp] Thread: ${Thread.currentThread().name}")

                // Only trigger if different from current
                if (filter != currentStateFilter) {
                    Log.d(TAG, "[$timestamp] Filter CHANGED from $currentStateFilter to $filter")

                    // Don't clear adapter - let ViewModel handle the state transition
                    currentStateFilter = filter
                    Log.d(TAG, "[$timestamp] Calling viewModel.setPackageStateFilter($filter)...")
                    viewModel.setPackageStateFilter(filter)
                    Log.d(TAG, "[$timestamp] viewModel.setPackageStateFilter() returned")
                } else {
                    Log.d(TAG, "[$timestamp] Filter UNCHANGED, ignoring click")
                }
                Log.d(TAG, "════════════════════════════════════════════════════════════════")
            }
        )
    }

    private fun updateFilterChips(packageTypeFilter: String, packageStateFilter: String?) {
        Log.d(TAG, "updateFilterChips: type=$packageTypeFilter, state=$packageStateFilter, current type=$currentTypeFilter, current state=$currentStateFilter")

        // Only update if filters have changed
        if (packageTypeFilter == currentTypeFilter && packageStateFilter == currentStateFilter) {
            Log.d(TAG, "Filters unchanged, skipping update")
            return
        }

        currentTypeFilter = packageTypeFilter
        currentStateFilter = packageStateFilter

        // Update chip selection without recreating or triggering listeners
        FilterChipsHelper.updateMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            selectedTypeFilter = packageTypeFilter,
            selectedStateFilter = packageStateFilter
        )
    }

    private fun setupRootBanner() {
        binding.dismissBannerButton.setOnClickListener {
            viewModel.dismissRootBanner()
            binding.rootBanner.visibility = View.GONE
        }
    }

    private fun observeUiState() {
        Log.d(TAG, "observeUiState() - Setting up StateFlow collection")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.d(TAG, "observeUiState() - Lifecycle STARTED, beginning to collect StateFlow")
                viewModel.uiState.collect { state ->
                    val timestamp = System.currentTimeMillis()
                    Log.d(TAG, "════════════════════════════════════════════════════════════════")
                    Log.d(TAG, "[$timestamp] 📥 StateFlow COLLECTED new state")
                    Log.d(TAG, "[$timestamp] Thread: ${Thread.currentThread().name}")
                    Log.d(TAG, "[$timestamp] State: packages.size=${state.packages.size}, isLoadingData=${state.isLoadingData}")
                    Log.d(TAG, "[$timestamp] Calling updateUI()...")
                    updateUI(state)
                    Log.d(TAG, "[$timestamp] updateUI() returned")
                    Log.d(TAG, "════════════════════════════════════════════════════════════════")
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

        // Update RecyclerView only if list changed
        val listChanged = state.packages != lastSubmittedPackages
        if (!listChanged) {
            return
        }

        lastSubmittedPackages = state.packages
        adapter.submitList(state.packages)
        if (state.isRenderingUI) {
            viewModel.setUIReady()
        }

        // Show root banner if needed - observe StateFlow
        lifecycleScope.launch {
            viewModel.showRootBanner.collect { showBanner ->
                binding.rootBanner.visibility = if (showBanner) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
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

        AlertDialog.Builder(requireContext())
            .setTitle("Force Stop ${pkg.name}?")
            .setMessage(
                if (isSystemPackage) {
                    "⚠️ This is a system package. Force stopping it may cause system instability.\n\nAre you sure you want to continue?"
                } else {
                    "This will immediately stop all processes for ${pkg.name}.\n\nAre you sure?"
                }
            )
            .setPositiveButton("Force Stop") { _, _ ->
                viewModel.forceStopPackage(pkg.packageName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEnableDisableConfirmation(pkg: Package, enable: Boolean) {
        val action = if (enable) "Enable" else "Disable"
        val isSystemPackage = pkg.type == PackageType.SYSTEM

        AlertDialog.Builder(requireContext())
            .setTitle("$action ${pkg.name}?")
            .setMessage(
                if (isSystemPackage && !enable) {
                    "⚠️ This is a system package. Disabling it may cause system instability or prevent other apps from working.\n\nAre you sure you want to continue?"
                } else if (enable) {
                    "This will allow ${pkg.name} to run normally.\n\nAre you sure?"
                } else {
                    "This will prevent ${pkg.name} from running.\n\nAre you sure?"
                }
            )
            .setPositiveButton(action) { _, _ ->
                viewModel.setPackageEnabled(pkg.packageName, enable)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUninstallConfirmation(pkg: Package) {
        val isSystemPackage = pkg.type == PackageType.SYSTEM

        AlertDialog.Builder(requireContext())
            .setTitle("Uninstall ${pkg.name}?")
            .setMessage(
                if (isSystemPackage) {
                    "⚠️ DANGER: This is a system package. Uninstalling it may brick your device or cause severe system instability.\n\nThis action cannot be easily undone.\n\nAre you absolutely sure?"
                } else {
                    "This will remove ${pkg.name} from your device.\n\nAre you sure?"
                }
            )
            .setPositiveButton("Uninstall") { _, _ ->
                viewModel.uninstallPackage(pkg.packageName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}

