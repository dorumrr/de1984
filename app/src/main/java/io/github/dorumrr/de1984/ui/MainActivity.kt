package io.github.dorumrr.de1984.ui

import android.Manifest
import android.content.Context
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
        private const val KEY_CURRENT_TAB = "current_tab"
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

    // Fragment cache to preserve scroll state
    private var firewallFragment: FirewallFragmentViews? = null
    private var packagesFragment: PackagesFragmentViews? = null
    private var settingsFragment: SettingsFragmentViews? = null

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

        // Check if we should show the firewall start prompt
        val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val shouldShowPrompt = prefs.getBoolean(
            Constants.Settings.KEY_SHOW_FIREWALL_START_PROMPT,
            Constants.Settings.DEFAULT_SHOW_FIREWALL_START_PROMPT
        )

        // Only show firewall start dialog if setting is enabled and firewall is not running
        val isFirewallRunning = firewallViewModel.uiState.value.isFirewallEnabled
        shouldShowFirewallStartDialog = shouldShowPrompt && !isFirewallRunning

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

        // Restore or load initial fragment
        if (savedInstanceState == null) {
            // First time - load Firewall fragment
            loadFragment(Tab.FIREWALL)
        } else {
            // Restoring from saved state - restore the current tab
            val tabOrdinal = savedInstanceState.getInt(KEY_CURRENT_TAB, Tab.FIREWALL.ordinal)
            currentTab = Tab.values()[tabOrdinal]

            // Restore fragment references (Firewall and Packages only need caching)
            firewallFragment = supportFragmentManager.findFragmentByTag("FIREWALL") as? FirewallFragmentViews
            packagesFragment = supportFragmentManager.findFragmentByTag("APPS") as? PackagesFragmentViews

            // Update UI to match restored tab
            updateToolbar()
            updateBottomNavigationSelection()
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
                    loadFragment(Tab.FIREWALL)
                    true
                }
                R.id.packagesFragment -> {
                    loadFragment(Tab.APPS)
                    true
                }
                R.id.settingsFragment -> {
                    loadFragment(Tab.SETTINGS)
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





    private fun loadFragment(tab: Tab) {
        currentTab = tab

        supportFragmentManager.commit {
            // Get or create cached fragments
            val firewall = firewallFragment ?: FirewallFragmentViews().also {
                firewallFragment = it
                add(R.id.fragment_container, it, "FIREWALL")
            }
            val packages = packagesFragment ?: PackagesFragmentViews().also {
                packagesFragment = it
                add(R.id.fragment_container, it, "APPS")
            }
            val settings = settingsFragment ?: SettingsFragmentViews().also {
                settingsFragment = it
                add(R.id.fragment_container, it, "SETTINGS")
            }

            // Hide all fragments
            hide(firewall)
            hide(packages)
            hide(settings)

            // Show the selected fragment
            when (tab) {
                Tab.FIREWALL -> show(firewall)
                Tab.APPS -> show(packages)
                Tab.SETTINGS -> show(settings)
            }
        }

        updateToolbar()
        updateBottomNavigationSelection()
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
                    loadFragment(Tab.FIREWALL)
                    true
                }
                R.id.packagesFragment -> {
                    loadFragment(Tab.APPS)
                    true
                }
                R.id.settingsFragment -> {
                    loadFragment(Tab.SETTINGS)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateToolbar() {
        when (currentTab) {
            Tab.FIREWALL -> {
                binding.toolbarSectionName.text = "FIREWALL"
                binding.firewallToggleGroup.visibility = View.VISIBLE
                // Update badges based on current firewall state
                val isEnabled = firewallViewModel.uiState.value.isFirewallEnabled
                binding.firewallActiveBadge.visibility = if (isEnabled) View.VISIBLE else View.GONE
                binding.firewallOffBadge.visibility = if (isEnabled) View.GONE else View.VISIBLE
            }
            Tab.APPS -> {
                binding.toolbarSectionName.text = "PACKAGES"
                binding.firewallToggleGroup.visibility = View.GONE
                binding.firewallActiveBadge.visibility = View.GONE
                binding.firewallOffBadge.visibility = View.GONE
            }
            Tab.SETTINGS -> {
                binding.toolbarSectionName.text = "SETTINGS"
                binding.firewallToggleGroup.visibility = View.GONE
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
        if (currentTab == Tab.FIREWALL && binding.firewallToggleGroup.visibility == View.VISIBLE) {
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

    /**
     * Navigate to Firewall screen and open dialog for specific app.
     * Used for cross-navigation from notifications and other screens.
     */
    fun navigateToFirewallWithApp(packageName: String) {
        loadFragment(Tab.FIREWALL)
        // Use postDelayed to ensure fragment is fully loaded before opening dialog
        binding.root.postDelayed({
            firewallFragment?.openAppDialog(packageName)
        }, 100)
    }

    /**
     * Navigate to Packages screen and open dialog for specific app.
     * Used for cross-navigation from Firewall screen.
     */
    fun navigateToPackagesWithApp(packageName: String) {
        loadFragment(Tab.APPS)
        // Use postDelayed to ensure fragment is fully loaded before opening dialog
        binding.root.postDelayed({
            packagesFragment?.openAppDialog(packageName)
        }, 100)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save current tab so it can be restored after configuration changes
        outState.putInt(KEY_CURRENT_TAB, currentTab.ordinal)
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

