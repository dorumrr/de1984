package io.github.dorumrr.de1984.ui

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dorumrr.de1984.BuildConfig
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.service.PackageMonitoringService
import io.github.dorumrr.de1984.databinding.ActivityMainViewsBinding
import io.github.dorumrr.de1984.presentation.viewmodel.FirewallViewModel
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.common.StandardDialog
import io.github.dorumrr.de1984.ui.firewall.FirewallFragmentViews
import io.github.dorumrr.de1984.ui.packages.PackagesFragmentViews
import io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel
import io.github.dorumrr.de1984.ui.settings.SettingsFragmentViews
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.launch

/**
 * Main activity for De1984 app
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainViewsBinding
    
    private val permissionManager: PermissionManager by lazy {
        (application as De1984Application).dependencies.permissionManager
    }

    // Shared ViewModels
    private val firewallViewModel: FirewallViewModel by viewModels {
        val deps = (application as De1984Application).dependencies
        FirewallViewModel.Factory(
            application = application,
            getNetworkPackagesUseCase = deps.provideGetNetworkPackagesUseCase(),
            manageNetworkAccessUseCase = deps.provideManageNetworkAccessUseCase(),
            superuserBannerState = deps.superuserBannerState,
            permissionManager = deps.permissionManager,
            firewallManager = deps.firewallManager
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        val deps = (application as De1984Application).dependencies
        SettingsViewModel.Factory(
            context = applicationContext,
            permissionManager = deps.permissionManager,
            rootManager = deps.rootManager,
            shizukuManager = deps.shizukuManager,
            firewallManager = deps.firewallManager,
            firewallRepository = deps.firewallRepository
        )
    }

    private val permissionSetupViewModel: PermissionSetupViewModel by viewModels {
        val deps = (application as De1984Application).dependencies
        PermissionSetupViewModel.Factory(
            permissionManager = deps.permissionManager
        )
    }

    // Permission launchers
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Always setup UI regardless of permission result
        // The app works without notification permission (notifications just won't show)
        onPermissionsComplete()
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Start firewall after VPN permission is granted
            // Battery optimization will be requested automatically via shouldRequestBatteryOptimization flag
            firewallViewModel.onVpnPermissionGranted()
        } else {
            firewallViewModel.onVpnPermissionDenied()
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Battery optimization result - no action needed
    }

    // State
    private var currentTab: Tab = Tab.FIREWALL
    private var permissionsCompleted = false
    private var shouldShowFirewallStartDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Setup UI first (so user doesn't see white screen)
        setupMainUI(savedInstanceState)

        // Start package monitoring service
        PackageMonitoringService.startMonitoring(this)

        // Check if we need to request permissions (after UI is loaded)
        if (!permissionManager.hasNotificationPermission()) {
            requestNotificationPermission()
        } else {
            onPermissionsComplete()
        }
    }

    private fun requestNotificationPermission() {
        val permissions = permissionManager.getRuntimePermissions()
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            onPermissionsComplete()
        }
    }

    private fun onPermissionsComplete() {
        permissionsCompleted = true
        // Only show firewall start dialog if firewall is not already running
        val isFirewallRunning = firewallViewModel.uiState.value.isFirewallEnabled
        shouldShowFirewallStartDialog = !isFirewallRunning
        // UI is already setup in onCreate, just show dialog if needed
        if (shouldShowFirewallStartDialog) {
            shouldShowFirewallStartDialog = false
            showFirewallStartDialog()
        }
    }

    private fun setupMainUI(savedInstanceState: Bundle?) {
        binding = ActivityMainViewsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets to handle edge-to-edge properly
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply top padding for status bar to the toolbar
            binding.toolbar.setPadding(0, systemBars.top, 0, 0)

            // Don't apply padding to root view
            view.setPadding(0, 0, 0, 0)

            insets
        }

        setupToolbar()
        setupBottomNavigation()
        observeFirewallState()

        // Load initial fragment
        if (savedInstanceState == null) {
            loadFragment(FirewallFragmentViews(), Tab.FIREWALL)
        }

        // Show firewall start dialog if needed
        if (shouldShowFirewallStartDialog) {
            shouldShowFirewallStartDialog = false
            showFirewallStartDialog()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // No back button or home button needed for bottom navigation

        // Setup firewall toggle (Material Switch)
        binding.firewallToggle.setOnCheckedChangeListener { _, isChecked ->
            onFirewallToggleChanged(isChecked)
        }

        updateToolbar()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.firewallFragment -> {
                    loadFragment(FirewallFragmentViews(), Tab.FIREWALL)
                    true
                }
                R.id.packagesFragment -> {
                    loadFragment(PackagesFragmentViews(), Tab.APPS)
                    true
                }
                R.id.settingsFragment -> {
                    loadFragment(SettingsFragmentViews(), Tab.SETTINGS)
                    true
                }
                else -> false
            }
        }

        binding.bottomNavigation.selectedItemId = R.id.firewallFragment
        binding.bottomNavigation.post {
            customizeBottomNavSpacing()
        }
    }

    private fun customizeBottomNavSpacing() {
        fun customizeRecursively(view: android.view.View) {
            if (view.javaClass.simpleName == "BottomNavigationItemView") {
                val density = resources.displayMetrics.density
                view.setPadding(
                    view.paddingLeft,
                    (Constants.UI.BOTTOM_NAV_PADDING_TOP * density).toInt(),
                    view.paddingRight,
                    (Constants.UI.BOTTOM_NAV_PADDING_BOTTOM * density).toInt()
                )
            }

            if (view.javaClass.simpleName == "BaselineLayout") {
                val density = resources.displayMetrics.density
                view.translationY = Constants.UI.BOTTOM_NAV_TEXT_TRANSLATION_Y * density
            }

            if (view is android.widget.ImageView && view.parent?.javaClass?.simpleName == "FrameLayout") {
                val density = resources.displayMetrics.density
                val newSize = (Constants.UI.BOTTOM_NAV_ICON_SIZE_ENLARGED * density).toInt()
                val params = view.layoutParams
                params.width = newSize
                params.height = newSize
                view.layoutParams = params
            }

            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    customizeRecursively(view.getChildAt(i))
                }
            }
        }

        customizeRecursively(binding.bottomNavigation)
    }





    private fun loadFragment(fragment: androidx.fragment.app.Fragment, tab: Tab) {
        currentTab = tab
        supportFragmentManager.commit {
            replace(R.id.fragment_container, fragment)
        }
        updateToolbar()
        updateBottomNavigationSelection()

        // Scroll to top when fragment is loaded
        // Use post to ensure fragment view is created
        binding.root.post {
            (fragment as? io.github.dorumrr.de1984.ui.base.BaseFragment<*>)?.scrollToTop()
        }
    }

    private fun updateBottomNavigationSelection() {
        // Update bottom navigation selection to match current tab
        val itemId = when (currentTab) {
            Tab.FIREWALL -> R.id.firewallFragment
            Tab.APPS -> R.id.packagesFragment
            Tab.SETTINGS -> R.id.settingsFragment
        }

        // Temporarily remove listener to avoid triggering navigation
        binding.bottomNavigation.setOnItemSelectedListener(null)
        binding.bottomNavigation.selectedItemId = itemId

        // Restore listener
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.firewallFragment -> {
                    loadFragment(FirewallFragmentViews(), Tab.FIREWALL)
                    true
                }
                R.id.packagesFragment -> {
                    loadFragment(PackagesFragmentViews(), Tab.APPS)
                    true
                }
                R.id.settingsFragment -> {
                    loadFragment(SettingsFragmentViews(), Tab.SETTINGS)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateToolbar() {
        when (currentTab) {
            Tab.FIREWALL -> {
                binding.toolbarTitle.text = Constants.Navigation.TITLE_FIREWALL
                binding.toolbarIcon.visibility = View.GONE
                binding.firewallToggle.visibility = View.VISIBLE
                // Update badges based on current firewall state
                val isEnabled = firewallViewModel.uiState.value.isFirewallEnabled
                binding.firewallActiveBadge.visibility = if (isEnabled) View.VISIBLE else View.GONE
                binding.firewallOffBadge.visibility = if (isEnabled) View.GONE else View.VISIBLE
            }
            Tab.APPS -> {
                binding.toolbarTitle.text = Constants.Navigation.TITLE_PACKAGES
                binding.toolbarIcon.visibility = View.GONE
                binding.firewallToggle.visibility = View.GONE
                binding.firewallActiveBadge.visibility = View.GONE
                binding.firewallOffBadge.visibility = View.GONE
            }
            Tab.SETTINGS -> {
                binding.toolbarTitle.text = Constants.Navigation.TITLE_SETTINGS
                binding.toolbarIcon.visibility = View.GONE
                binding.firewallToggle.visibility = View.GONE
                binding.firewallActiveBadge.visibility = View.GONE
                binding.firewallOffBadge.visibility = View.GONE
            }
        }
    }

    private fun observeFirewallState() {
        lifecycleScope.launch {
            firewallViewModel.uiState.collect { state ->
                // Update custom switch appearance
                updateSwitchAppearance(state.isFirewallEnabled)

                // Request battery optimization if needed
                if (state.shouldRequestBatteryOptimization) {
                    firewallViewModel.clearBatteryOptimizationRequest()
                    val batteryOptIntent = permissionManager.createBatteryOptimizationIntent()
                    if (batteryOptIntent != null) {
                        batteryOptimizationLauncher.launch(batteryOptIntent)
                    }
                }
            }
        }
    }

    private fun updateSwitchAppearance(isEnabled: Boolean) {
        // Update switch state without triggering the listener
        binding.firewallToggle.setOnCheckedChangeListener(null)
        binding.firewallToggle.isChecked = isEnabled
        binding.firewallToggle.setOnCheckedChangeListener { _, isChecked ->
            onFirewallToggleChanged(isChecked)
        }

        // Update badge visibility (only show on Firewall tab)
        if (currentTab == Tab.FIREWALL) {
            binding.firewallActiveBadge.visibility = if (isEnabled) View.VISIBLE else View.GONE
            binding.firewallOffBadge.visibility = if (isEnabled) View.GONE else View.VISIBLE
        } else {
            binding.firewallActiveBadge.visibility = View.GONE
            binding.firewallOffBadge.visibility = View.GONE
        }
    }

    private fun onFirewallToggleChanged(enabled: Boolean) {
        if (enabled) {
            val prepareIntent = firewallViewModel.startFirewall()
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            }
        } else {
            firewallViewModel.stopFirewall()
        }
    }

    private fun showFirewallStartDialog() {
        StandardDialog.showConfirmation(
            context = this,
            title = Constants.UI.Dialogs.FIREWALL_START_TITLE,
            message = Constants.UI.Dialogs.FIREWALL_START_MESSAGE,
            confirmButtonText = Constants.UI.Dialogs.FIREWALL_START_CONFIRM,
            onConfirm = {
                val prepareIntent = firewallViewModel.startFirewall()
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                }
            },
            cancelButtonText = Constants.UI.Dialogs.FIREWALL_START_SKIP
        )
    }



    override fun onDestroy() {
        super.onDestroy()
        // Unregister Shizuku listeners to prevent memory leaks
        (application as De1984Application).dependencies.shizukuManager.unregisterListeners()
    }

    enum class Tab {
        FIREWALL, APPS, SETTINGS
    }
}

