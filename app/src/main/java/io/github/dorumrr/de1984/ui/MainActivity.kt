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

    private enum class VpnPermissionContext {
        FIREWALL_START,
        VPN_FALLBACK
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
            firewallRepository = deps.firewallRepository,
            captivePortalManager = deps.captivePortalManager
        )
    }

    private val permissionSetupViewModel: PermissionSetupViewModel by viewModels {
        val deps = (application as De1984Application).dependencies
        PermissionSetupViewModel.Factory(
            context = applicationContext,
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
            }
        } else {
            // Handle based on context
            when (vpnPermissionContext) {
                VpnPermissionContext.FIREWALL_START -> {
                    firewallViewModel.onVpnPermissionDenied()
                }
                VpnPermissionContext.VPN_FALLBACK -> {
                    // User denied VPN permission for fallback - nothing to do
                    Log.w(TAG, "User denied VPN permission for fallback")
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

        // Handle intent (e.g., from notification)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.action?.let { action ->
            when (action) {
                Constants.Notifications.ACTION_ENABLE_VPN_FALLBACK -> {
                    handleVpnFallbackRequest()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Re-check privileges when app comes to foreground
        // This ensures we detect newly available Shizuku/root and request permissions
        // Force re-check even if previously had permission to detect privilege restoration
        // (e.g., root re-enabled in Magisk, Shizuku restarted)
        lifecycleScope.launch {
            val deps = (application as De1984Application).dependencies

            // Check Shizuku first (preferred method)
            deps.shizukuManager.checkShizukuStatus()

            // If Shizuku is available but permission not granted, request it
            if (deps.shizukuManager.isShizukuAvailable() && !deps.shizukuManager.hasShizukuPermission) {
                deps.shizukuManager.requestShizukuPermission()
            }

            // Force re-check root status to detect privilege restoration
            // This is critical for detecting when root is re-enabled after being revoked
            // The regular checkRootStatus() caches ROOTED_WITH_PERMISSION, so we need force re-check
            deps.rootManager.forceRecheckRootStatus()

            // The StateFlow updates from these checks will trigger handlePrivilegeChange()
            // in FirewallManager, which will automatically switch backends if needed
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
            Log.d(TAG, "onPermissionsComplete: Skipping firewall start dialog - handling VPN fallback request")
            return
        }

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

            // Restore fragment references from FragmentManager
            firewallFragment = supportFragmentManager.findFragmentByTag("FIREWALL") as? FirewallFragmentViews
            packagesFragment = supportFragmentManager.findFragmentByTag("APPS") as? PackagesFragmentViews
            settingsFragment = supportFragmentManager.findFragmentByTag("SETTINGS") as? SettingsFragmentViews

            Log.d(TAG, "setupMainUI: Restored fragments - firewall=${firewallFragment != null}, packages=${packagesFragment != null}, settings=${settingsFragment != null}")

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

        // Set icon colors based on dynamic colors setting
        applyBottomNavigationColors()
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
        Log.d(TAG, "loadFragment: Switching to tab $tab")
        currentTab = tab

        supportFragmentManager.commit {
            // Get or create cached fragments - check FragmentManager first to avoid duplicates
            val firewall = firewallFragment
                ?: (supportFragmentManager.findFragmentByTag("FIREWALL") as? FirewallFragmentViews)?.also {
                    firewallFragment = it
                    Log.d(TAG, "loadFragment: Found existing Firewall fragment in FragmentManager")
                }
                ?: FirewallFragmentViews().also {
                    firewallFragment = it
                    add(R.id.fragment_container, it, "FIREWALL")
                    Log.d(TAG, "loadFragment: Created new Firewall fragment")
                }

            val packages = packagesFragment
                ?: (supportFragmentManager.findFragmentByTag("APPS") as? PackagesFragmentViews)?.also {
                    packagesFragment = it
                    Log.d(TAG, "loadFragment: Found existing Packages fragment in FragmentManager")
                }
                ?: PackagesFragmentViews().also {
                    packagesFragment = it
                    add(R.id.fragment_container, it, "APPS")
                    Log.d(TAG, "loadFragment: Created new Packages fragment")
                }

            val settings = settingsFragment
                ?: (supportFragmentManager.findFragmentByTag("SETTINGS") as? SettingsFragmentViews)?.also {
                    settingsFragment = it
                    Log.d(TAG, "loadFragment: Found existing Settings fragment in FragmentManager")
                }
                ?: SettingsFragmentViews().also {
                    settingsFragment = it
                    add(R.id.fragment_container, it, "SETTINGS")
                    Log.d(TAG, "loadFragment: Created new Settings fragment")
                }

            // Hide all fragments
            hide(firewall)
            hide(packages)
            hide(settings)
            Log.d(TAG, "loadFragment: Hidden all fragments")

            // Show the selected fragment
            when (tab) {
                Tab.FIREWALL -> {
                    show(firewall)
                    Log.d(TAG, "loadFragment: Showing Firewall fragment")
                }
                Tab.APPS -> {
                    show(packages)
                    Log.d(TAG, "loadFragment: Showing Packages fragment")
                }
                Tab.SETTINGS -> {
                    show(settings)
                    Log.d(TAG, "loadFragment: Showing Settings fragment")
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
            firewallViewModel.stopFirewall()
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
        // NOTE: We do NOT unregister Shizuku listeners here because they need to survive
        // for the entire application process lifetime to enable automatic backend switching
        // even when the app is not open. The listeners are registered in De1984Application.onCreate()
        // and will be cleaned up when the process is killed by Android.
    }

    private fun handleVpnFallbackRequest() {
        Log.d(TAG, "Handling VPN fallback request from notification")

        // Check if VPN permission is already granted
        val prepareIntent = try {
            android.net.VpnService.prepare(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check VPN permission", e)
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

    private fun startVpnFallbackAfterPermission() {
        Log.d(TAG, "Starting VPN fallback after permission granted")

        val firewallManager = (application as De1984Application).dependencies.firewallManager

        lifecycleScope.launch {
            try {
                firewallManager.startVpnFallbackManually()
                Log.d(TAG, "VPN fallback started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN fallback", e)
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

