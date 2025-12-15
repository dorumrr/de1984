package io.github.dorumrr.de1984.ui

import io.github.dorumrr.de1984.utils.AppLogger
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
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

    private enum class VpnPermissionContext {
        FIREWALL_START,
        VPN_FALLBACK,
        BOOT_FAILURE_RECOVERY
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
            firewallManager = deps.firewallManager,
            packageDataChanged = deps.packageDataChanged
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
            firewallRepository = deps.firewallRepository,
            captivePortalManager = deps.captivePortalManager,
            bootProtectionManager = deps.bootProtectionManager,
            smartPolicySwitchUseCase = deps.provideSmartPolicySwitchUseCase(),
            packageRepository = deps.packageRepository
        )
    }

    private val permissionSetupViewModel: PermissionSetupViewModel by viewModels {
        val deps = (application as De1984Application).dependencies
        PermissionSetupViewModel.Factory(
            context = applicationContext,
            permissionManager = deps.permissionManager,
            firewallManager = deps.firewallManager
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
            // Handle based on context
            when (vpnPermissionContext) {
                VpnPermissionContext.FIREWALL_START -> {
                    // Start firewall after VPN permission is granted
                    // Battery optimization will be requested automatically via shouldRequestBatteryOptimization flag
                    firewallViewModel.onVpnPermissionGranted()
                }
                VpnPermissionContext.VPN_FALLBACK -> {
                    // Start VPN fallback after permission granted
                    startVpnFallbackAfterPermission()
                }
                VpnPermissionContext.BOOT_FAILURE_RECOVERY -> {
                    // Start firewall after boot failure recovery
                    startFirewallAfterBootFailure()
                }
            }
        } else {
            // Handle based on context
            when (vpnPermissionContext) {
                VpnPermissionContext.FIREWALL_START -> {
                    firewallViewModel.onVpnPermissionDenied()
                }
                VpnPermissionContext.VPN_FALLBACK -> {
                    // User denied VPN permission for fallback - nothing to do
                    AppLogger.w(TAG, "User denied VPN permission for fallback")
                }
                VpnPermissionContext.BOOT_FAILURE_RECOVERY -> {
                    // User denied VPN permission for boot recovery
                    AppLogger.w(TAG, "User denied VPN permission for boot failure recovery")
                    Toast.makeText(this, "VPN permission required to start firewall", Toast.LENGTH_SHORT).show()
                }
            }
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
    private var vpnPermissionContext: VpnPermissionContext = VpnPermissionContext.FIREWALL_START
    private var shouldShowFirewallStartDialog = false

    // Fragment cache to preserve scroll state
    private var firewallFragment: FirewallFragmentViews? = null
    private var packagesFragment: PackagesFragmentViews? = null
    private var settingsFragment: SettingsFragmentViews? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.d(TAG, "📱 MAINACTIVITY CREATED | savedInstanceState: ${if (savedInstanceState == null) "null (first launch)" else "present (restored)"}")

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Setup UI first (so user doesn't see white screen)
        setupMainUI(savedInstanceState)

        // Start package monitoring service
        AppLogger.d(TAG, "🔄 Starting PackageMonitoringService")
        PackageMonitoringService.startMonitoring(this)

        // Check if we need to request permissions (after UI is loaded)
        if (!permissionManager.hasNotificationPermission()) {
            AppLogger.d(TAG, "⚠️  Notification permission not granted, requesting...")
            requestNotificationPermission()
        } else {
            AppLogger.d(TAG, "✅ Notification permission already granted")
            onPermissionsComplete()
        }

        // Handle intent (e.g., from notification)
        handleIntent(intent)

        AppLogger.d(TAG, "✅ MainActivity onCreate complete")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLogger.d(TAG, "📱 MAINACTIVITY NEW INTENT | Action: ${intent.action ?: "null"}")
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.action?.let { action ->
            when (action) {
                Constants.Notifications.ACTION_ENABLE_VPN_FALLBACK -> {
                    handleVpnFallbackRequest()
                }
                Constants.Notifications.ACTION_BOOT_FAILURE_RECOVERY -> {
                    handleBootFailureRecovery()
                }
                Constants.Firewall.ACTION_REQUEST_VPN_PERMISSION -> {
                    AppLogger.d(TAG, "VPN permission request from widget/tile - starting firewall")
                    // Start firewall, which will trigger VPN permission dialog if needed
                    firewallViewModel.startFirewall()
                }
                Constants.Firewall.ACTION_TOGGLE_FIREWALL -> {
                    AppLogger.d(TAG, "Firewall toggle request from tile/widget")
                    // This action is only sent when firewall is ON and user wants to stop
                    // (Widget starts directly when OFF, only opens app for stop confirmation)
                    val deps = (application as De1984Application).dependencies
                    val isActuallyActive = deps.firewallManager.isActive()
                    AppLogger.d(TAG, "Actual firewall state from manager: isActive=$isActuallyActive")
                    
                    if (isActuallyActive) {
                        // Firewall is ON - show stop confirmation dialog
                        AppLogger.d(TAG, "Showing stop confirmation dialog")
                        showFirewallStopDialog()
                    } else {
                        // Firewall is somehow OFF - this shouldn't happen normally from widget
                        // but handle it gracefully by just showing the app
                        AppLogger.d(TAG, "Firewall is OFF, no action needed - just showing app")
                    }
                }
                else -> {
                    // Ignore unknown actions
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        AppLogger.d(TAG, "📱 MAINACTIVITY RESUMED - CHECKING PRIVILEGES")

        // Clear disabled packages cache to detect external package state changes
        // This is critical for work profile apps where enabled/disabled state can change externally
        io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.clearDisabledPackagesCache()

        // Re-check privileges when app comes to foreground
        // This ensures we detect newly available Shizuku/root and request permissions
        // Force re-check even if previously had permission to detect privilege restoration
        // (e.g., root re-enabled in Magisk, Shizuku restarted)
        lifecycleScope.launch {
            val deps = (application as De1984Application).dependencies

            // Check Shizuku first (preferred method)
            AppLogger.d(TAG, "Checking Shizuku status...")
            deps.shizukuManager.checkShizukuStatus()

            // If Shizuku is available but permission not granted, request it
            // BUT: Skip if user already denied to prevent prompt spam (Issue #68)
            if (deps.shizukuManager.isShizukuAvailable() && !deps.shizukuManager.hasShizukuPermission) {
                if (deps.shizukuManager.hasUserDeniedPermission) {
                    AppLogger.d(TAG, "Shizuku available but user previously denied - skipping auto-request to prevent spam")
                } else {
                    AppLogger.d(TAG, "Shizuku available but permission not granted - requesting permission")
                    deps.shizukuManager.requestShizukuPermission()
                }
            }

            // Force re-check root status to detect privilege restoration
            // This is critical for detecting when root is re-enabled after being revoked
            // The regular checkRootStatus() caches ROOTED_WITH_PERMISSION, so we need force re-check
            AppLogger.d(TAG, "Force rechecking root status...")
            deps.rootManager.forceRecheckRootStatus()

            // IMPORTANT: Force FirewallManager to check if backend should change
            // This is necessary because StateFlow deduplicates - if root status was already
            // ROOTED_WITH_PERMISSION, the StateFlow won't emit again, and handlePrivilegeChange()
            // won't be triggered. We need to explicitly tell FirewallManager to check.
            AppLogger.d(TAG, "Checking if backend should switch...")
            deps.firewallManager.checkBackendShouldSwitch()

            AppLogger.d(TAG, "MainActivity.onResume() privilege checks complete")
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

        // Check if we're handling a VPN fallback request - if so, don't show the dialog
        val isVpnFallbackRequest = intent?.action == Constants.Notifications.ACTION_ENABLE_VPN_FALLBACK
        if (isVpnFallbackRequest) {
            AppLogger.d(TAG, "onPermissionsComplete: Skipping firewall start dialog - handling VPN fallback request")
            return
        }

        // Check if we should show the firewall start prompt
        val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val shouldShowPrompt = prefs.getBoolean(
            Constants.Settings.KEY_SHOW_FIREWALL_START_PROMPT,
            Constants.Settings.DEFAULT_SHOW_FIREWALL_START_PROMPT
        )

        // Check if firewall is running by checking SharedPreferences directly
        // Don't rely on ViewModel state which might not be initialized yet
        val isFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

        AppLogger.d(TAG, "onPermissionsComplete: shouldShowPrompt=$shouldShowPrompt, isFirewallEnabled=$isFirewallEnabled")

        // Only show firewall start dialog if setting is enabled and firewall is not running
        shouldShowFirewallStartDialog = shouldShowPrompt && !isFirewallEnabled

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

            // Restore fragment references from FragmentManager
            firewallFragment = supportFragmentManager.findFragmentByTag("FIREWALL") as? FirewallFragmentViews
            packagesFragment = supportFragmentManager.findFragmentByTag("APPS") as? PackagesFragmentViews
            settingsFragment = supportFragmentManager.findFragmentByTag("SETTINGS") as? SettingsFragmentViews

            AppLogger.d(TAG, "setupMainUI: Restored fragments - firewall=${firewallFragment != null}, packages=${packagesFragment != null}, settings=${settingsFragment != null}")

            // Ensure fragments are properly shown/hidden for current tab
            supportFragmentManager.commit {
                firewallFragment?.let { if (currentTab != Tab.FIREWALL) hide(it) else show(it) }
                packagesFragment?.let { if (currentTab != Tab.APPS) hide(it) else show(it) }
                settingsFragment?.let { if (currentTab != Tab.SETTINGS) hide(it) else show(it) }
            }

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
        AppLogger.d(TAG, "📋 setupToolbar: Initializing toolbar")
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // No back button or home button needed for bottom navigation

        // Setup firewall toggle (Material Switch)
        binding.firewallToggle.setOnCheckedChangeListener { _, isChecked ->
            AppLogger.d(TAG, "🔘 USER ACTION: Firewall toggle changed to: $isChecked")
            onFirewallToggleChanged(isChecked)
        }

        updateToolbar()
        AppLogger.d(TAG, "✅ setupToolbar: Toolbar initialized")
    }

    private fun setupBottomNavigation() {
        AppLogger.d(TAG, "📋 setupBottomNavigation: Initializing bottom navigation")
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val tabName = when (item.itemId) {
                R.id.firewallFragment -> "FIREWALL"
                R.id.packagesFragment -> "PACKAGES"
                R.id.settingsFragment -> "SETTINGS"
                else -> "UNKNOWN"
            }
            AppLogger.d(TAG, "🔘 USER ACTION: Bottom navigation item selected: $tabName")

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

        // Set icon colors based on dynamic colors setting
        applyBottomNavigationColors()
        AppLogger.d(TAG, "✅ setupBottomNavigation: Bottom navigation initialized")
    }

    private fun applyBottomNavigationColors() {
        val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val useDynamicColors = prefs.getBoolean(
            Constants.Settings.KEY_USE_DYNAMIC_COLORS,
            Constants.Settings.DEFAULT_USE_DYNAMIC_COLORS
        )

        if (useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Dynamic colors enabled - use colorOnPrimaryContainer from Material 3's dynamic theme
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, typedValue, true)
            val onPrimaryContainer = typedValue.data

            val states = arrayOf(
                intArrayOf(android.R.attr.state_checked),  // Selected
                intArrayOf()  // Unselected
            )
            val colors = intArrayOf(
                onPrimaryContainer,  // Selected - full opacity
                applyAlpha(onPrimaryContainer, 0.6f)  // Unselected - 60% opacity
            )
            val colorStateList = android.content.res.ColorStateList(states, colors)
            binding.bottomNavigation.itemIconTintList = colorStateList
            binding.bottomNavigation.itemTextColor = colorStateList
        } else {
            // Dynamic colors disabled - use white for teal background
            val white = ContextCompat.getColor(this, R.color.text_white)
            val states = arrayOf(
                intArrayOf(android.R.attr.state_checked),  // Selected
                intArrayOf()  // Unselected
            )
            val colors = intArrayOf(
                white,  // Selected - full opacity
                applyAlpha(white, 0.6f)  // Unselected - 60% opacity
            )
            val colorStateList = android.content.res.ColorStateList(states, colors)
            binding.bottomNavigation.itemIconTintList = colorStateList
            binding.bottomNavigation.itemTextColor = colorStateList
        }
    }

    /**
     * Apply alpha (opacity) to a color
     * @param color The original color
     * @param alpha Alpha value from 0.0 (transparent) to 1.0 (opaque)
     * @return Color with applied alpha
     */
    private fun applyAlpha(color: Int, alpha: Float): Int {
        val alphaInt = (alpha * 255).toInt()
        return (color and 0x00FFFFFF) or (alphaInt shl 24)
    }


    private fun loadFragment(tab: Tab) {
        AppLogger.d(TAG, "loadFragment: Switching to tab $tab")
        currentTab = tab

        supportFragmentManager.commit {
            // Hide all existing fragments first
            firewallFragment?.let { hide(it) }
            packagesFragment?.let { hide(it) }
            settingsFragment?.let { hide(it) }

            // Get or create only the fragment being shown (lazy creation)
            when (tab) {
                Tab.FIREWALL -> {
                    val fragment = firewallFragment
                        ?: (supportFragmentManager.findFragmentByTag("FIREWALL") as? FirewallFragmentViews)?.also {
                            firewallFragment = it
                            AppLogger.d(TAG, "loadFragment: Found existing Firewall fragment in FragmentManager")
                        }
                        ?: FirewallFragmentViews().also {
                            firewallFragment = it
                            add(R.id.fragment_container, it, "FIREWALL")
                            AppLogger.d(TAG, "loadFragment: Created new Firewall fragment")
                        }
                    show(fragment)
                    AppLogger.d(TAG, "loadFragment: Showing Firewall fragment")
                }
                Tab.APPS -> {
                    val fragment = packagesFragment
                        ?: (supportFragmentManager.findFragmentByTag("APPS") as? PackagesFragmentViews)?.also {
                            packagesFragment = it
                            AppLogger.d(TAG, "loadFragment: Found existing Packages fragment in FragmentManager")
                        }
                        ?: PackagesFragmentViews().also {
                            packagesFragment = it
                            add(R.id.fragment_container, it, "APPS")
                            AppLogger.d(TAG, "loadFragment: Created new Packages fragment")
                        }
                    show(fragment)
                    AppLogger.d(TAG, "loadFragment: Showing Packages fragment")
                }
                Tab.SETTINGS -> {
                    val fragment = settingsFragment
                        ?: (supportFragmentManager.findFragmentByTag("SETTINGS") as? SettingsFragmentViews)?.also {
                            settingsFragment = it
                            AppLogger.d(TAG, "loadFragment: Found existing Settings fragment in FragmentManager")
                        }
                        ?: SettingsFragmentViews().also {
                            settingsFragment = it
                            add(R.id.fragment_container, it, "SETTINGS")
                            AppLogger.d(TAG, "loadFragment: Created new Settings fragment")
                        }
                    show(fragment)
                    AppLogger.d(TAG, "loadFragment: Showing Settings fragment")
                }
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
                binding.toolbarSectionName.text = getString(R.string.nav_firewall).uppercase()
                binding.firewallToggleGroup.visibility = View.VISIBLE
                // Update badges based on current firewall state
                val isEnabled = firewallViewModel.uiState.value.isFirewallEnabled
                binding.firewallActiveBadge.visibility = if (isEnabled) View.VISIBLE else View.GONE
                binding.firewallOffBadge.visibility = if (isEnabled) View.GONE else View.VISIBLE
            }
            Tab.APPS -> {
                binding.toolbarSectionName.text = getString(R.string.nav_packages).uppercase()
                binding.firewallToggleGroup.visibility = View.GONE
                binding.firewallActiveBadge.visibility = View.GONE
                binding.firewallOffBadge.visibility = View.GONE
            }
            Tab.SETTINGS -> {
                binding.toolbarSectionName.text = getString(R.string.nav_settings).uppercase()
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
            // Show confirmation dialog before stopping firewall
            showFirewallStopDialog()
        }
    }

    private fun showFirewallStartDialog() {
        StandardDialog.showConfirmation(
            context = this,
            title = getString(R.string.dialog_firewall_start_title),
            message = getString(R.string.dialog_firewall_start_message),
            confirmButtonText = getString(R.string.dialog_firewall_start_confirm),
            onConfirm = {
                val prepareIntent = firewallViewModel.startFirewall()
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                }
            },
            cancelButtonText = getString(R.string.dialog_firewall_start_skip)
        )
    }

    private fun showFirewallStopDialog() {
        var confirmed = false

        StandardDialog.show(
            context = this,
            title = getString(R.string.dialog_firewall_stop_title),
            message = getString(R.string.dialog_firewall_stop_message),
            positiveButtonText = getString(R.string.dialog_firewall_stop_confirm),
            onPositiveClick = {
                AppLogger.d(TAG, "🔘 USER CONFIRMED: Stopping firewall")
                confirmed = true
                firewallViewModel.stopFirewall()
            },
            negativeButtonText = getString(R.string.dialog_cancel),
            onNegativeClick = {
                AppLogger.d(TAG, "🔘 USER CANCELLED: Firewall stop cancelled - reverting toggle")
                // User cancelled - revert the toggle back to ON
                binding.firewallToggle.isChecked = true
            },
            cancelable = true,
            onDismiss = {
                // Only revert toggle if dialog was dismissed without confirmation
                // This handles: tap outside, back button, swipe down
                if (!confirmed) {
                    AppLogger.d(TAG, "🔘 DIALOG DISMISSED: Reverting toggle to ON")
                    binding.firewallToggle.isChecked = true
                }
            }
        )
    }

    /**
     * Navigate to Firewall screen and open dialog for specific app.
     * Used for cross-navigation from notifications and other screens.
     *
     * @param packageName The package name of the app
     * @param userId Android user profile ID (0 = personal, 10+ = work/clone profiles)
     */
    fun navigateToFirewallWithApp(packageName: String, userId: Int = 0) {
        AppLogger.d(TAG, "🔘 USER ACTION: Navigate to Firewall with app: $packageName (userId=$userId)")
        loadFragment(Tab.FIREWALL)
        // Use postDelayed to ensure fragment is fully loaded before opening dialog
        binding.root.postDelayed({
            firewallFragment?.openAppDialog(packageName, userId)
        }, 100)
    }

    /**
     * Navigate to Packages screen and open dialog for specific app.
     * Used for cross-navigation from Firewall screen.
     *
     * @param packageName The package name of the app
     * @param userId Android user profile ID (0 = personal, 10+ = work/clone profiles)
     */
    fun navigateToPackagesWithApp(packageName: String, userId: Int = 0) {
        AppLogger.d(TAG, "🔘 USER ACTION: Navigate to Packages with app: $packageName (userId=$userId)")
        loadFragment(Tab.APPS)
        // Use postDelayed to ensure fragment is fully loaded before opening dialog
        binding.root.postDelayed({
            packagesFragment?.openAppDialog(packageName, userId)
        }, 100)
    }

    /**
     * Navigate to Settings screen.
     * Used for navigation from protection warning banners.
     */
    fun navigateToSettings() {
        loadFragment(Tab.SETTINGS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save current tab so it can be restored after configuration changes
        outState.putInt(KEY_CURRENT_TAB, currentTab.ordinal)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.d(TAG, "📱 MAINACTIVITY DESTROYED")
        // NOTE: We do NOT unregister Shizuku listeners here because they need to survive
        // for the entire application process lifetime to enable automatic backend switching
        // even when the app is not open. The listeners are registered in De1984Application.onCreate()
        // and will be cleaned up when the process is killed by Android.
    }

    private fun handleVpnFallbackRequest() {
        AppLogger.d(TAG, "Handling VPN fallback request from notification")

        // Check if VPN permission is already granted
        val prepareIntent = try {
            android.net.VpnService.prepare(this)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check VPN permission", e)
            return
        }

        if (prepareIntent != null) {
            // VPN permission not granted - request it
            vpnPermissionContext = VpnPermissionContext.VPN_FALLBACK
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // VPN permission already granted - start fallback immediately
            startVpnFallbackAfterPermission()
        }
    }

    private fun handleBootFailureRecovery() {
        AppLogger.d(TAG, "Handling boot failure recovery from notification")

        // Dismiss the boot failure notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(Constants.BootFailure.NOTIFICATION_ID)

        // Check if firewall is already running
        val firewallManager = (application as De1984Application).dependencies.firewallManager
        if (firewallManager.activeBackendType.value != null) {
            AppLogger.d(TAG, "Firewall already running, no recovery needed")
            Toast.makeText(this, "Firewall is already running", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if VPN permission is needed and request it if necessary
        val prepareIntent = try {
            android.net.VpnService.prepare(this)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check VPN permission", e)
            MaterialAlertDialogBuilder(this)
                .setTitle("Failed to check VPN permission")
                .setMessage("Could not check VPN permission: ${e.message}")
                .setPositiveButton(getString(R.string.dialog_ok), null)
                .show()
            return
        }

        if (prepareIntent != null) {
            // VPN permission not granted - request it
            AppLogger.d(TAG, "VPN permission not granted, requesting...")
            vpnPermissionContext = VpnPermissionContext.BOOT_FAILURE_RECOVERY
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // VPN permission already granted - start firewall immediately
            AppLogger.d(TAG, "VPN permission already granted, starting firewall...")
            startFirewallAfterBootFailure()
        }
    }

    private fun startFirewallAfterBootFailure() {
        AppLogger.d(TAG, "Starting firewall after boot failure recovery")

        val firewallManager = (application as De1984Application).dependencies.firewallManager

        lifecycleScope.launch {
            try {
                val result = firewallManager.startFirewall()

                result.onSuccess { backendType ->
                    AppLogger.d(TAG, "✅ Firewall started successfully with backend: $backendType")
                    Toast.makeText(
                        this@MainActivity,
                        "Firewall started successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
                    AppLogger.e(TAG, "❌ Failed to start firewall: ${error.message}")
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Failed to start firewall")
                        .setMessage("Could not start the firewall: ${error.message}")
                        .setPositiveButton(getString(R.string.dialog_ok), null)
                        .show()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception while starting firewall", e)
            }
        }
    }

    private fun startVpnFallbackAfterPermission() {
        AppLogger.d(TAG, "Starting VPN fallback after permission granted")

        val firewallManager = (application as De1984Application).dependencies.firewallManager

        lifecycleScope.launch {
            try {
                firewallManager.startVpnFallbackManually()
                AppLogger.d(TAG, "VPN fallback started successfully")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start VPN fallback", e)
                // Show error to user
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.vpn_fallback_failed_title))
                    .setMessage(getString(R.string.vpn_fallback_failed_message, e.message))
                    .setPositiveButton(getString(R.string.dialog_ok), null)
                    .show()
            }
        }
    }

    enum class Tab {
        FIREWALL, APPS, SETTINGS
    }
}

