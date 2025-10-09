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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.BottomSheetPackageActionBinding
import io.github.dorumrr.de1984.databinding.FragmentFirewallBinding
import io.github.dorumrr.de1984.databinding.NetworkTypeToggleBinding
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

    private val TAG = "FirewallFragmentViews"

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
    private var currentTypeFilter: String? = null
    private var currentStateFilter: String? = null
    private var lastSubmittedPackages: List<NetworkPackage> = emptyList()

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
        // Initial setup - only called once
        currentTypeFilter = "User"
        currentStateFilter = null

        FilterChipsHelper.setupMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            typeFilters = Constants.Firewall.PACKAGE_TYPE_FILTERS,
            stateFilters = Constants.Firewall.NETWORK_STATE_FILTERS,
            selectedTypeFilter = currentTypeFilter,
            selectedStateFilter = currentStateFilter,
            onTypeFilterSelected = { filter ->
                // Only trigger if different from current
                if (filter != currentTypeFilter) {
                    Log.d(TAG, "Type filter selected: $filter")
                    currentTypeFilter = filter
                    viewModel.setPackageTypeFilter(filter)
                }
            },
            onStateFilterSelected = { filter ->
                // Only trigger if different from current
                if (filter != currentStateFilter) {
                    Log.d(TAG, "State filter selected: $filter")
                    currentStateFilter = filter
                    viewModel.setNetworkStateFilter(filter)
                }
            }
        )
    }

    private fun updateFilterChips(
        packageTypeFilter: String,
        networkStateFilter: String?
    ) {
        Log.d(TAG, "updateFilterChips: type=$packageTypeFilter, state=$networkStateFilter, current type=$currentTypeFilter, current state=$currentStateFilter")

        // Only update if filters have changed
        if (packageTypeFilter == currentTypeFilter && networkStateFilter == currentStateFilter) {
            Log.d(TAG, "Filters unchanged, skipping update")
            return
        }

        currentTypeFilter = packageTypeFilter
        currentStateFilter = networkStateFilter

        // Update chip selection without recreating or triggering listeners
        FilterChipsHelper.updateMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            selectedTypeFilter = packageTypeFilter,
            selectedStateFilter = networkStateFilter
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
                "isLoadingData=${state.isLoadingData}, " +
                "isRenderingUI=${state.isRenderingUI}, " +
                "typeFilter=${state.filterState.packageType}, " +
                "stateFilter=${state.filterState.networkState}")

        // Update filter chips
        updateFilterChips(
            packageTypeFilter = state.filterState.packageType,
            networkStateFilter = state.filterState.networkState
        )

        // Update package count
        binding.packageCount.text = "${state.packages.size} packages"

        // Update RecyclerView - only if the list actually changed
        val listChanged = state.packages != lastSubmittedPackages

        if (listChanged) {
            lastSubmittedPackages = state.packages
            // Submit list with null to force immediate clear, then submit new list
            // This prevents showing old list while DiffUtil calculates
            adapter.submitList(null) {
                adapter.submitList(state.packages) {
                    // Called when list is submitted and rendered
                    if (state.isRenderingUI) {
                        // Mark UI as ready after RecyclerView has rendered
                        binding.packagesRecyclerView.post {
                            viewModel.setUIReady()
                        }
                    }
                }
            }
        } else {
            // Still need to call setUIReady if we're in rendering state
            if (state.isRenderingUI) {
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
    }

    private fun showPackageActionSheet(pkg: NetworkPackage) {
        val dialog = BottomSheetDialog(requireContext())
        val binding = BottomSheetPackageActionBinding.inflate(layoutInflater)

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

        // Track current mobile blocked state
        var currentMobileBlocked = pkg.mobileBlocked

        // Function to update roaming visibility
        fun updateRoamingVisibility(mobileBlocked: Boolean) {
            if (hasCellular && !mobileBlocked) {
                // Show roaming when mobile is allowed
                binding.roamingDivider.visibility = View.VISIBLE
                binding.roamingToggle.root.visibility = View.VISIBLE
            } else {
                // Hide roaming when mobile is blocked or no cellular
                binding.roamingDivider.visibility = View.GONE
                binding.roamingToggle.root.visibility = View.GONE
            }
        }

        // Setup WiFi toggle
        setupNetworkToggle(
            binding = binding.wifiToggle,
            label = "WiFi",
            isBlocked = pkg.wifiBlocked,
            enabled = true,
            onToggle = { blocked ->
                viewModel.setWifiBlocking(pkg.packageName, blocked)
            }
        )

        // Setup Mobile Data toggle
        setupNetworkToggle(
            binding = binding.mobileToggle,
            label = "Mobile Data",
            isBlocked = pkg.mobileBlocked,
            enabled = true,
            onToggle = { blocked ->
                currentMobileBlocked = blocked
                viewModel.setMobileBlocking(pkg.packageName, blocked)
                // Update roaming visibility when mobile state changes
                updateRoamingVisibility(blocked)
            }
        )

        // Setup Roaming toggle (only if device has cellular and mobile is allowed)
        if (hasCellular) {
            val roamingBinding = binding.roamingToggle
            setupNetworkToggle(
                binding = roamingBinding,
                label = "Roaming",
                isBlocked = pkg.roamingBlocked,
                enabled = true,
                onToggle = { blocked ->
                    viewModel.setRoamingBlocking(pkg.packageName, blocked)
                }
            )
        }

        // Set initial roaming visibility
        updateRoamingVisibility(currentMobileBlocked)

        dialog.setContentView(binding.root)
        dialog.show()
    }

    private fun setupNetworkToggle(
        binding: NetworkTypeToggleBinding,
        label: String,
        isBlocked: Boolean,
        enabled: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        binding.networkTypeLabel.text = label

        // Set initial state: switch ON = blocked, switch OFF = allowed
        binding.toggleSwitch.isChecked = isBlocked
        binding.toggleSwitch.isEnabled = enabled

        // Update colors based on state
        updateSwitchColors(binding.toggleSwitch, isBlocked)

        Log.d(TAG, "[$label] Initial state: isBlocked=$isBlocked, switchChecked=${binding.toggleSwitch.isChecked}")

        // Simple switch listener - only fires on user interaction
        binding.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "[$label] Switch changed: isChecked=$isChecked")
            updateSwitchColors(binding.toggleSwitch, isChecked)
            onToggle(isChecked)
        }
    }

    private fun updateSwitchColors(switch: SwitchMaterial, isBlocked: Boolean) {
        val context = switch.context

        // Create color state lists for checked (blocked/ON) and unchecked (allowed/OFF) states
        val thumbColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),  // When switch is ON (blocked)
                intArrayOf(-android.R.attr.state_checked)  // When switch is OFF (allowed)
            ),
            intArrayOf(
                ContextCompat.getColor(context, R.color.error_red),      // RED when blocked (ON)
                ContextCompat.getColor(context, R.color.success_green)   // GREEN when allowed (OFF)
            )
        )

        val trackColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),  // When switch is ON (blocked)
                intArrayOf(-android.R.attr.state_checked)  // When switch is OFF (allowed)
            ),
            intArrayOf(
                ContextCompat.getColor(context, R.color.error_red) and 0x80FFFFFF.toInt(),      // RED with 50% opacity when blocked (ON)
                ContextCompat.getColor(context, R.color.success_green) and 0x80FFFFFF.toInt()   // GREEN with 50% opacity when allowed (OFF)
            )
        )

        // Set thumb (the circle) and track (the background) colors
        switch.thumbTintList = thumbColorStateList
        switch.trackTintList = trackColorStateList
    }

    companion object {
        private const val TAG = "FirewallFragmentViews"
    }
}

