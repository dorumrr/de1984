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

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentPackagesBinding {
        return FragmentPackagesBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFilterChips()
        setupRootBanner()
        observeUiState()
        observeSettings()

        // Check root access
        viewModel.checkRootAccess()
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
        // Initial setup
        updateFilterChips(
            packageTypeFilter = "User",
            packageStateFilter = null
        )
    }

    private fun updateFilterChips(packageTypeFilter: String, packageStateFilter: String?) {
        val packageTypeFilters = Constants.Packages.PACKAGE_TYPE_FILTERS
        val packageStateFilters = Constants.Packages.PACKAGE_STATE_FILTERS

        FilterChipsHelper.setupMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            typeFilters = packageTypeFilters,
            stateFilters = packageStateFilters,
            selectedTypeFilter = packageTypeFilter,
            selectedStateFilter = packageStateFilter,
            onTypeFilterSelected = { filter ->
                viewModel.setPackageTypeFilter(filter)
            },
            onStateFilterSelected = { filter ->
                viewModel.setPackageStateFilter(filter)
            }
        )
    }

    private fun setupRootBanner() {
        binding.dismissBannerButton.setOnClickListener {
            viewModel.dismissRootBanner()
            binding.rootBanner.visibility = View.GONE
        }
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

    private fun updateUI(state: PackagesUiState) {
        Log.d(TAG, "updateUI: ${state.packages.size} packages, " +
                "loading=${state.isLoading}, " +
                "isLoadingData=${state.isLoadingData}, " +
                "isRenderingUI=${state.isRenderingUI}, " +
                "typeFilter=${state.filterState.packageType}, " +
                "stateFilter=${state.filterState.packageState}")

        // Update filter chips
        updateFilterChips(
            packageTypeFilter = state.filterState.packageType,
            packageStateFilter = state.filterState.packageState
        )

        // Update RecyclerView
        adapter.submitList(state.packages) {
            // Called when list is submitted and rendered
            if (state.isRenderingUI) {
                // Mark UI as ready after RecyclerView has rendered
                binding.packagesRecyclerView.post {
                    viewModel.setUIReady()
                }
            }
        }

        // Show/hide states
        when {
            state.isLoading -> {
                binding.loadingState.visibility = View.VISIBLE
                binding.emptyState.visibility = View.GONE
                binding.packagesRecyclerView.visibility = View.GONE
            }
            state.packages.isEmpty() -> {
                binding.loadingState.visibility = View.GONE
                binding.emptyState.visibility = View.VISIBLE
                binding.packagesRecyclerView.visibility = View.GONE
            }
            else -> {
                binding.loadingState.visibility = View.GONE
                binding.emptyState.visibility = View.GONE
                binding.packagesRecyclerView.visibility = View.VISIBLE
            }
        }

        // Show root banner if needed
        binding.rootBanner.visibility = if (viewModel.showRootBanner) {
            View.VISIBLE
        } else {
            View.GONE
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
            showForceStopConfirmation(pkg)
        }

        // Setup Enable/Disable action
        if (pkg.isEnabled) {
            binding.enableDisableIcon.text = "🚫"
            binding.enableDisableTitle.text = "Disable"
            binding.enableDisableDescription.text = "Prevent this app from running"
        } else {
            binding.enableDisableIcon.text = "✅"
            binding.enableDisableTitle.text = "Enable"
            binding.enableDisableDescription.text = "Allow this app to run"
        }

        binding.enableDisableAction.setOnClickListener {
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

