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
import kotlinx.coroutines.launch
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

    private lateinit var adapter: NetworkPackageAdapter
    private var currentTypeFilter: String? = null
    private var currentStateFilter: String? = null
    private var lastSubmittedPackages: List<NetworkPackage> = emptyList()

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentFirewallBinding.inflate(inflater, container, false)

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
        setupSearchBox()
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
                if (filter != currentTypeFilter) {
                    currentTypeFilter = filter
                    viewModel.setPackageTypeFilter(filter)
                }
            },
            onStateFilterSelected = { filter ->
                if (filter != currentStateFilter) {
                    currentStateFilter = filter
                    viewModel.setNetworkStateFilter(filter)
                }
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

    private fun updateFilterChips(
        packageTypeFilter: String,
        networkStateFilter: String?
    ) {
        // Only update if filters have changed
        if (packageTypeFilter == currentTypeFilter && networkStateFilter == currentStateFilter) {
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
            networkStateFilter = state.filterState.networkState
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

            isUpdatingProgrammatically = false
        }

        // Initial setup of toggles
        updateTogglesFromPackage(pkg)

        // Observe package changes to update UI when ViewModel makes cascading changes
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val updatedPkg = state.packages.find { it.packageName == pkg.packageName }
                if (updatedPkg != null && !isUpdatingProgrammatically) {
                    updateTogglesFromPackage(updatedPkg)
                }
            }
        }

        // Setup WiFi toggle
        setupNetworkToggle(
            binding = binding.wifiToggle,
            label = "WiFi",
            isBlocked = pkg.wifiBlocked,
            enabled = true,
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
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
                if (isUpdatingProgrammatically) return@setupNetworkToggle

                // ViewModel handles mobile+roaming dependency atomically
                viewModel.setMobileBlocking(pkg.packageName, blocked)
            }
        )

        // Setup Roaming toggle (only if device has cellular)
        if (hasCellular) {
            setupNetworkToggle(
                binding = binding.roamingToggle,
                label = "Roaming",
                isBlocked = pkg.roamingBlocked,
                enabled = true,
                onToggle = { blocked ->
                    if (isUpdatingProgrammatically) return@setupNetworkToggle

                    // ViewModel handles mobile+roaming dependency atomically
                    viewModel.setRoamingBlocking(pkg.packageName, blocked)
                }
            )
        }

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

        // Simple switch listener - only fires on user interaction
        binding.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColors(binding.toggleSwitch, isChecked)
            onToggle(isChecked)
        }
    }

    private fun updateSwitchColors(switch: SwitchMaterial, @Suppress("UNUSED_PARAMETER") isBlocked: Boolean) {
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

