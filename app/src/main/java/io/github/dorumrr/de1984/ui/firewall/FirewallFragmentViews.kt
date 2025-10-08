package io.github.dorumrr.de1984.ui.firewall

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.FragmentFirewallBinding
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.presentation.viewmodel.FirewallViewModel
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.ui.common.FilterChipsHelper
import io.github.dorumrr.de1984.utils.Constants
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

    private val viewModel: FirewallViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        FirewallViewModel.Factory(
            app,
            app.dependencies.provideGetNetworkPackagesUseCase(),
            app.dependencies.provideManageNetworkAccessUseCase(),
            app.dependencies.superuserBannerState,
            app.dependencies.permissionManager
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

    private lateinit var adapter: NetworkPackageAdapter

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentFirewallBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: FirewallFragmentViews initialized")

        setupRecyclerView()
        setupFilterChips()
        observeUiState()
        observeSettingsState()

        // Refresh default policy on start
        viewModel.refreshDefaultPolicy()
    }

    private fun setupRecyclerView() {
        adapter = NetworkPackageAdapter(
            showIcons = true, // Will be updated from settings
            onPackageClick = { pkg ->
                showPackageActionSheet(pkg)
            }
        )

        binding.packagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FirewallFragmentViews.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupFilterChips() {
        // Initial setup - will be updated when state changes
        updateFilterChips(
            packageTypeFilter = "User",
            networkStateFilter = null
        )
    }

    private fun updateFilterChips(
        packageTypeFilter: String,
        networkStateFilter: String?
    ) {
        FilterChipsHelper.setupMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            typeFilters = Constants.Firewall.PACKAGE_TYPE_FILTERS,
            stateFilters = Constants.Firewall.NETWORK_STATE_FILTERS,
            selectedTypeFilter = packageTypeFilter,
            selectedStateFilter = networkStateFilter,
            onTypeFilterSelected = { filter ->
                Log.d(TAG, "Type filter selected: $filter")
                viewModel.setPackageTypeFilter(filter)
            },
            onStateFilterSelected = { filter ->
                Log.d(TAG, "State filter selected: $filter")
                viewModel.setNetworkStateFilter(filter)
            }
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
                    // Update adapter when showIcons setting changes
                    adapter = NetworkPackageAdapter(
                        showIcons = settingsState.showAppIcons,
                        onPackageClick = { pkg ->
                            showPackageActionSheet(pkg)
                        }
                    )
                    binding.packagesRecyclerView.adapter = adapter
                    // Re-submit current list
                    viewModel.uiState.value.packages.let { packages ->
                        adapter.submitList(packages)
                    }
                }
            }
        }
    }

    private fun updateUI(state: io.github.dorumrr.de1984.presentation.viewmodel.FirewallUiState) {
        Log.d(TAG, "updateUI: ${state.packages.size} packages, " +
                "loading=${state.isLoading}, " +
                "typeFilter=${state.filterState.packageType}, " +
                "stateFilter=${state.filterState.networkState}")

        // Update filter chips
        updateFilterChips(
            packageTypeFilter = state.filterState.packageType,
            networkStateFilter = state.filterState.networkState
        )

        // Update package count
        binding.packageCount.text = "${state.packages.size} packages"

        // Update RecyclerView
        adapter.submitList(state.packages)

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
    }

    private fun showPackageActionSheet(pkg: NetworkPackage) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_package_action, null)

        // TODO: Setup bottom sheet content
        // For now, just toggle WiFi and Mobile blocking
        val shouldBlock = pkg.isNetworkAllowed
        viewModel.setWifiBlocking(pkg.packageName, shouldBlock)
        viewModel.setMobileBlocking(pkg.packageName, shouldBlock)

        dialog.setContentView(view)
        dialog.show()
    }

    companion object {
        private const val TAG = "FirewallFragmentViews"
    }
}

