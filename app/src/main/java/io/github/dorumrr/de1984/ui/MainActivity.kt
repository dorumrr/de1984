package io.github.dorumrr.de1984.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.service.PackageMonitoringService
import io.github.dorumrr.de1984.databinding.ActivityMainViewsBinding
import io.github.dorumrr.de1984.presentation.viewmodel.FirewallViewModel
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.firewall.FirewallFragmentViews
import io.github.dorumrr.de1984.ui.packages.PackagesFragmentViews
import io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel
import io.github.dorumrr.de1984.ui.settings.SettingsFragmentViews
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.launch

/**
 * Main activity for De1984 app
 * Manages navigation between Firewall, Apps, and Settings screens
 */
class MainActivity : AppCompatActivity() {

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
            permissionManager = deps.permissionManager
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        val deps = (application as De1984Application).dependencies
        SettingsViewModel.Factory(
            context = applicationContext,
            permissionManager = deps.permissionManager,
            rootManager = deps.rootManager
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
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted || permissionManager.hasNotificationPermission()) {
            onPermissionsComplete()
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val batteryOptIntent = firewallViewModel.onVpnPermissionGranted()
            if (batteryOptIntent != null) {
                batteryOptimizationLauncher.launch(batteryOptIntent)
            }
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

        // Start package monitoring service
        PackageMonitoringService.startMonitoring(this)

        // Check if we need to request permissions
        if (!permissionManager.hasNotificationPermission()) {
            requestNotificationPermission()
        } else {
            permissionsCompleted = true
            setupMainUI(savedInstanceState)
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
        shouldShowFirewallStartDialog = true
        setupMainUI(null)
    }

    private fun setupMainUI(savedInstanceState: Bundle?) {
        binding = ActivityMainViewsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets to handle edge-to-edge properly
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply top padding for status bar to the toolbar
            binding.toolbar.setPadding(0, systemBars.top, 0, 0)

            // Apply bottom padding for navigation bar to the bottom navigation
            binding.bottomNavigation.setPadding(0, 0, 0, systemBars.bottom)

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

        // Setup firewall toggle (custom switch view)
        binding.firewallToggle.setOnClickListener {
            val currentState = firewallViewModel.uiState.value.isFirewallEnabled
            onFirewallToggleChanged(!currentState)
        }

        updateToolbar()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_firewall -> {
                    loadFragment(FirewallFragmentViews(), Tab.FIREWALL)
                    true
                }
                R.id.nav_apps -> {
                    loadFragment(PackagesFragmentViews(), Tab.APPS)
                    true
                }
                R.id.nav_settings -> {
                    loadFragment(SettingsFragmentViews(), Tab.SETTINGS)
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: androidx.fragment.app.Fragment, tab: Tab) {
        currentTab = tab
        supportFragmentManager.commit {
            replace(R.id.fragment_container, fragment)
        }
        updateToolbar()
    }

    private fun updateToolbar() {
        when (currentTab) {
            Tab.FIREWALL -> {
                binding.toolbarTitle.text = "De1984 Firewall"
                binding.toolbarIcon.visibility = View.GONE
                binding.firewallToggle.visibility = View.VISIBLE
            }
            Tab.APPS -> {
                binding.toolbarTitle.text = "De1984 Apps"
                binding.toolbarIcon.visibility = View.VISIBLE
                binding.toolbarIcon.setImageResource(R.drawable.ic_grid_view)
                binding.firewallToggle.visibility = View.GONE
            }
            Tab.SETTINGS -> {
                binding.toolbarTitle.text = "De1984 Settings"
                binding.toolbarIcon.visibility = View.VISIBLE
                binding.toolbarIcon.setImageResource(R.drawable.ic_settings)
                binding.firewallToggle.visibility = View.GONE
            }
        }
    }

    private fun observeFirewallState() {
        lifecycleScope.launch {
            firewallViewModel.uiState.collect { state ->
                // Update custom switch appearance
                updateSwitchAppearance(state.isFirewallEnabled)
            }
        }
    }

    private fun updateSwitchAppearance(isEnabled: Boolean) {
        if (isEnabled) {
            binding.firewallToggle.text = "ON"
            binding.firewallToggle.setBackgroundResource(R.drawable.switch_background_on)
        } else {
            binding.firewallToggle.text = "OFF"
            binding.firewallToggle.setBackgroundResource(R.drawable.switch_background_off)
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
        MaterialAlertDialogBuilder(this)
            .setTitle(Constants.UI.Dialogs.FIREWALL_START_TITLE)
            .setMessage(Constants.UI.Dialogs.FIREWALL_START_MESSAGE)
            .setPositiveButton(Constants.UI.Dialogs.FIREWALL_START_CONFIRM) { _, _ ->
                val prepareIntent = firewallViewModel.startFirewall()
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                }
            }
            .setNegativeButton(Constants.UI.Dialogs.FIREWALL_START_SKIP, null)
            .setCancelable(true)
            .show()
    }

    enum class Tab {
        FIREWALL, APPS, SETTINGS
    }
}

