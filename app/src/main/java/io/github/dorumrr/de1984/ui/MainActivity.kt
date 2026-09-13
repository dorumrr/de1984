package io.github.dorumrr.de1984.ui

import io.github.dorumrr.de1984.utils.AppLogger
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
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
import io.github.dorumrr.de1984.domain.firewall.FirewallHealth
import io.github.dorumrr.de1984.domain.firewall.FirewallHealthAction
import io.github.dorumrr.de1984.domain.firewall.FirewallHealthPresenter
import io.github.dorumrr.de1984.ui.common.StandardDialog
import io.github.dorumrr.de1984.ui.firewall.FirewallFragmentViews
import io.github.dorumrr.de1984.ui.packages.PackagesFragmentViews
import io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel
import io.github.dorumrr.de1984.ui.settings.SettingsFragmentViews
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.openAppSettings
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_CURRENT_TAB = "current_tab"
        private const val KEY_VPN_PERMISSION_CONTEXT = "vpn_permission_context"
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

    private val firewallViewModel: FirewallViewModel by viewModels {
        val deps = (application as De1984Application).dependencies
        FirewallViewModel.Factory(
            application = application,
            getNetworkPackagesUseCase = deps.provideGetNetworkPackagesUseCase(),
            manageNetworkAccessUseCase = deps.provideManageNetworkAccessUseCase(),
            superuserBannerState = deps.superuserBannerState,
            permissionManager = deps.permissionManager,
            firewallManager = deps.firewallManager,
            firewallRepository = deps.firewallRepository,
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        onPermissionsComplete()
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            when (vpnPermissionContext) {
                VpnPermissionContext.FIREWALL_START -> {
                    firewallViewModel.onVpnPermissionGranted()
                }
                VpnPermissionContext.VPN_FALLBACK -> {
                    startVpnFallbackAfterPermission()
                }
                VpnPermissionContext.BOOT_FAILURE_RECOVERY -> {
                    startFirewallAfterBootFailure()
                }
            }
        } else {
            when (vpnPermissionContext) {
                VpnPermissionContext.FIREWALL_START -> {
                    firewallViewModel.onVpnPermissionDenied()
                }
                VpnPermissionContext.VPN_FALLBACK -> {
                    AppLogger.w(TAG, "User denied VPN permission for fallback")
                }
                VpnPermissionContext.BOOT_FAILURE_RECOVERY -> {
                    AppLogger.w(TAG, "User denied VPN permission for boot failure recovery")
                    Toast.makeText(this, "VPN permission required to start firewall", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
    }

    private var currentTab: Tab = Tab.FIREWALL
    private var permissionsCompleted = false
    private var vpnPermissionContext: VpnPermissionContext = VpnPermissionContext.FIREWALL_START
    private var shouldShowFirewallStartDialog = false

    private var firewallFragment: FirewallFragmentViews? = null
    private var packagesFragment: PackagesFragmentViews? = null
    private var settingsFragment: SettingsFragmentViews? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.d(TAG, "📱 MAINACTIVITY CREATED | savedInstanceState: ${if (savedInstanceState == null) "null (first launch)" else "present (restored)"}")

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setupMainUI(savedInstanceState)

        AppLogger.d(TAG, "🔄 Starting PackageMonitoringService")
        PackageMonitoringService.startMonitoring(this)

        if (!permissionManager.hasNotificationPermission()) {
            AppLogger.d(TAG, "⚠️  Notification permission not granted, requesting...")
            requestNotificationPermission()
        } else {
            AppLogger.d(TAG, "✅ Notification permission already granted")
            onPermissionsComplete()
        }

        // Only on a genuine first launch. On a rebuild - a language change, dark mode, low memory -
        // the original launch intent is still attached, and running it again re-fires whatever it
        // asked for: a second VPN consent prompt, or the stop-confirmation dialog appearing on its
        // own. A genuinely new intent arrives through onNewIntent, which is unaffected.
        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            AppLogger.d(TAG, "Rebuild - not replaying the launch intent (action: ${intent?.action ?: "null"})")
        }

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
                Constants.Notifications.ACTION_OPEN_FIREWALL -> {
                    val packageName = intent.getStringExtra(Constants.Notifications.EXTRA_PACKAGE_NAME)
                    // Absent on a notification posted before the user was sent; that version found the app in De1984's own profile.
                    val userId = intent.getIntExtra(Constants.Notifications.EXTRA_USER_ID, Constants.Firewall.ownUserId())
                    if (packageName.isNullOrBlank()) {
                        AppLogger.w(TAG, "Open-firewall request carried no package name")
                    } else {
                        AppLogger.d(TAG, "Opening network access controls for $packageName (userId=$userId) from its notification")
                        navigateToFirewallWithApp(packageName, userId)
                    }
                }
                Constants.Firewall.ACTION_REQUEST_VPN_PERMISSION -> {
                    AppLogger.d(TAG, "VPN permission request from widget/tile - starting firewall")
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
                        AppLogger.d(TAG, "Showing stop confirmation dialog")
                        showFirewallStopDialog()
                    } else {
                        AppLogger.d(TAG, "Firewall is OFF, no action needed - just showing app")
                    }
                }
                else -> {
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        AppLogger.d(TAG, "📱 MAINACTIVITY RESUMED - CHECKING PRIVILEGES")

        // Notification permission can change while we are away, and the banner's wording depends on
        // it. The health flow will not re-emit on its own, so redraw from its current value.
        if (::binding.isInitialized) {
            renderFirewallHealthBanner(firewallViewModel.firewallHealth.value)
        }

        // Clear disabled packages cache to detect external package state changes
        // This is critical for work profile apps where enabled/disabled state can change externally
        io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.clearDisabledPackagesCache()

        // Re-check privileges when app comes to foreground
        // This ensures we detect newly available Shizuku/root and request permissions
        // Force re-check even if previously had permission to detect privilege restoration
        // (e.g., root re-enabled in Magisk, Shizuku restarted)
        lifecycleScope.launch {
            val deps = (application as De1984Application).dependencies

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

        val isVpnFallbackRequest = intent?.action == Constants.Notifications.ACTION_ENABLE_VPN_FALLBACK
        if (isVpnFallbackRequest) {
            AppLogger.d(TAG, "onPermissionsComplete: Skipping firewall start dialog - handling VPN fallback request")
            return
        }

        val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val shouldShowPrompt = prefs.getBoolean(
            Constants.Settings.KEY_SHOW_FIREWALL_START_PROMPT,
            Constants.Settings.DEFAULT_SHOW_FIREWALL_START_PROMPT
        )

        // Check if firewall is running by checking SharedPreferences directly
        // Don't rely on ViewModel state which might not be initialized yet
        val isFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

        AppLogger.d(TAG, "onPermissionsComplete: shouldShowPrompt=$shouldShowPrompt, isFirewallEnabled=$isFirewallEnabled")

        shouldShowFirewallStartDialog = shouldShowPrompt && !isFirewallEnabled

        if (shouldShowFirewallStartDialog) {
            shouldShowFirewallStartDialog = false
            showFirewallStartDialog()
        }
    }

    private fun setupMainUI(savedInstanceState: Bundle?) {
        binding = ActivityMainViewsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.toolbar.setPadding(0, systemBars.top, 0, 0)

            // Left and right matter now that the app can rotate. On a phone with 3-button
            // navigation the bar moves to the SIDE in landscape, and with the root padded to zero it
            // sat on top of the toolbar and the right-hand edge of the app list. In portrait both
            // are 0, so this changes nothing there.
            //
            // Bottom is deliberately left alone: BottomNavigationView applies the bottom system
            // window inset itself, and padding the root as well would double it.
            view.setPadding(systemBars.left, 0, systemBars.right, 0)

            insets
        }

        setupToolbar()
        setupBottomNavigation()
        observeFirewallState()

        if (savedInstanceState == null) {
            loadFragment(Tab.FIREWALL)
        } else {
            val tabOrdinal = savedInstanceState.getInt(KEY_CURRENT_TAB, Tab.FIREWALL.ordinal)
            currentTab = Tab.values()[tabOrdinal]

            // Put the bar on the restored tab before anything is drawn. setupBottomNavigation()
            // above force-selects Firewall, which fires the listener and commits a show-Firewall
            // transaction - so a rebuild while on Settings showed Firewall, then corrected itself.
            // A visible flash of the wrong screen, and the firewall toggle group appearing with it,
            // on every language or dark-mode change.
            binding.bottomNavigation.selectedItemId = when (currentTab) {
                Tab.FIREWALL -> R.id.firewallFragment
                Tab.APPS -> R.id.packagesFragment
                Tab.SETTINGS -> R.id.settingsFragment
            }

            // Why this must survive: the system VPN consent dialog is a separate activity, so this
            // one can be rebuilt underneath it. ActivityResultRegistry still delivers the answer,
            // but a fresh field would say FIREWALL_START - so an "Enable VPN" tap would be answered
            // down the wrong branch and the fallback would never start, leaving the firewall down.
            val contextOrdinal = savedInstanceState.getInt(
                KEY_VPN_PERMISSION_CONTEXT,
                VpnPermissionContext.FIREWALL_START.ordinal
            )
            vpnPermissionContext = VpnPermissionContext.values()
                .getOrElse(contextOrdinal) { VpnPermissionContext.FIREWALL_START }

            firewallFragment = supportFragmentManager.findFragmentByTag("FIREWALL") as? FirewallFragmentViews
            packagesFragment = supportFragmentManager.findFragmentByTag("APPS") as? PackagesFragmentViews
            settingsFragment = supportFragmentManager.findFragmentByTag("SETTINGS") as? SettingsFragmentViews

            AppLogger.d(TAG, "setupMainUI: Restored fragments - firewall=${firewallFragment != null}, packages=${packagesFragment != null}, settings=${settingsFragment != null}")

            supportFragmentManager.commit {
                firewallFragment?.let { if (currentTab != Tab.FIREWALL) hide(it) else show(it) }
                packagesFragment?.let { if (currentTab != Tab.APPS) hide(it) else show(it) }
                settingsFragment?.let { if (currentTab != Tab.SETTINGS) hide(it) else show(it) }
            }

            updateToolbar()
            updateBottomNavigationSelection()
        }

        if (shouldShowFirewallStartDialog) {
            shouldShowFirewallStartDialog = false
            showFirewallStartDialog()
        }
    }

    private fun setupToolbar() {
        AppLogger.d(TAG, "📋 setupToolbar: Initializing toolbar")
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

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
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, typedValue, true)
            val onPrimaryContainer = typedValue.data

            val states = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            )
            val colors = intArrayOf(
                onPrimaryContainer,
                applyAlpha(onPrimaryContainer, 0.6f)
            )
            val colorStateList = android.content.res.ColorStateList(states, colors)
            binding.bottomNavigation.itemIconTintList = colorStateList
            binding.bottomNavigation.itemTextColor = colorStateList
        } else {
            val white = ContextCompat.getColor(this, R.color.text_white)
            val states = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            )
            val colors = intArrayOf(
                white,
                applyAlpha(white, 0.6f)
            )
            val colorStateList = android.content.res.ColorStateList(states, colors)
            binding.bottomNavigation.itemIconTintList = colorStateList
            binding.bottomNavigation.itemTextColor = colorStateList
        }
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val alphaInt = (alpha * 255).toInt()
        return (color and 0x00FFFFFF) or (alphaInt shl 24)
    }


    private fun loadFragment(tab: Tab) {
        AppLogger.d(TAG, "loadFragment: Switching to tab $tab")
        currentTab = tab

        supportFragmentManager.commit {
            firewallFragment?.let { hide(it) }
            packagesFragment?.let { hide(it) }
            settingsFragment?.let { hide(it) }

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
        val itemId = when (currentTab) {
            Tab.FIREWALL -> R.id.firewallFragment
            Tab.APPS -> R.id.packagesFragment
            Tab.SETTINGS -> R.id.settingsFragment
        }

        binding.bottomNavigation.setOnItemSelectedListener(null)
        binding.bottomNavigation.selectedItemId = itemId

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
            }
            Tab.APPS -> {
                binding.toolbarSectionName.text = getString(R.string.nav_packages).uppercase()
                binding.firewallToggleGroup.visibility = View.GONE
            }
            Tab.SETTINGS -> {
                binding.toolbarSectionName.text = getString(R.string.nav_settings).uppercase()
                binding.firewallToggleGroup.visibility = View.GONE
            }
        }
        updateFirewallBadges()
    }

    /**
     * Show exactly one status badge in the toolbar, or none.
     *
     * Single place for this. Badge visibility used to be written out in four separate blocks, which
     * is how a failed firewall ended up showing the same OFF badge as a deliberate stop - the user
     * could not tell "I turned it off" from "it broke".
     */
    private fun updateFirewallBadges() {
        val onFirewallTab = currentTab == Tab.FIREWALL
        val health = firewallViewModel.firewallHealth.value
        val isDown = FirewallHealthPresenter.isCritical(health)
        val isEnabled = firewallViewModel.uiState.value.isFirewallEnabled

        // A failed teardown leaves the toggle reading OFF, because FirewallState is Error and the
        // view model maps anything that is not Running or Starting to false. Showing the plain OFF
        // badge there is the very confusion this function exists to prevent: the user cannot tell
        // "I turned it off" from "it would not turn off and apps may still be blocked". Reuse the
        // attention badge with its own word rather than inventing a third control.
        val isStuck = health is FirewallHealth.StopFailed

        binding.firewallDownBadge.setText(
            if (isStuck) R.string.firewall_status_stuck else R.string.firewall_status_down
        )
        binding.firewallDownBadge.visibility =
            if (onFirewallTab && (isDown || isStuck)) View.VISIBLE else View.GONE
        binding.firewallActiveBadge.visibility =
            if (onFirewallTab && !isDown && !isStuck && isEnabled) View.VISIBLE else View.GONE
        binding.firewallOffBadge.visibility =
            if (onFirewallTab && !isDown && !isStuck && !isEnabled) View.VISIBLE else View.GONE
    }

    private fun observeFirewallState() {
        lifecycleScope.launch {
            firewallViewModel.firewallHealth.collect { health ->
                renderFirewallHealthBanner(health)
                updateFirewallBadges()
            }
        }

        lifecycleScope.launch {
            firewallViewModel.uiState.collect { state ->
                updateSwitchAppearance(state.isFirewallEnabled)

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

    /**
     * Draw the firewall health banner, or hide it when there is nothing to say.
     *
     * The banner is deliberately not dismissible. It describes a live condition - no app is being
     * blocked right now - so it must disappear only when that condition ends, never because the
     * user tapped it away.
     */
    private fun renderFirewallHealthBanner(health: FirewallHealth) {
        val banner = binding.firewallHealthBanner
        val title = FirewallHealthPresenter.title(this, health)
        val message = FirewallHealthPresenter.message(this, health)

        if (title == null || message == null) {
            banner.root.visibility = View.GONE
            return
        }

        val isCritical = FirewallHealthPresenter.isCritical(health)

        banner.root.setBackgroundResource(
            if (isCritical) R.drawable.firewall_down_banner_background
            else R.drawable.warning_banner_background
        )
        // The button must be coloured too. Left alone it inherits colorPrimary, which in dark mode
        // is teal on a dark red fill - 2.45:1, and it is the only control that recovers the firewall.
        val accent = ContextCompat.getColor(
            this,
            if (isCritical) R.color.firewall_down_text else R.color.firewall_switched_text
        )

        // A firewall usually fails while the app is closed, so the notification is the part that
        // actually reaches the user. If the OS is dropping it, say so here and offer the fix -
        // otherwise the one case the notification exists for is the one case nobody is told about.
        val alertsBlocked = FirewallHealthPresenter.reliesOnNotification(health) &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()

        banner.healthBannerTitle.text = title
        banner.healthBannerTitle.setTextColor(accent)
        banner.healthBannerMessage.text = if (alertsBlocked) {
            "$message\n\n${getString(R.string.firewall_down_notifications_off)}"
        } else {
            message
        }
        banner.healthBannerAction.setTextColor(accent)
        banner.healthBannerAction.iconTint = ColorStateList.valueOf(accent)

        banner.healthBannerNotifications.visibility = if (alertsBlocked) View.VISIBLE else View.GONE
        banner.healthBannerNotifications.setTextColor(accent)
        banner.healthBannerNotifications.setOnClickListener { openNotificationSettings() }

        val action = FirewallHealthPresenter.action(health)
        if (action == null) {
            banner.healthBannerAction.visibility = View.GONE
        } else {
            banner.healthBannerAction.visibility = View.VISIBLE
            banner.healthBannerAction.setText(action.label)
            banner.healthBannerAction.setOnClickListener { onFirewallHealthAction(action) }
        }

        banner.root.visibility = View.VISIBLE
        AppLogger.d(TAG, "Firewall health banner shown: $health")
    }

    private fun onFirewallHealthAction(action: FirewallHealthAction) {
        AppLogger.d(TAG, "Firewall health banner action tapped: $action")
        when (action) {
            FirewallHealthAction.CHOOSE_BACKEND -> navigateToSettings()

            FirewallHealthAction.RETRY -> {
                val prepareIntent = firewallViewModel.startFirewall()
                if (prepareIntent != null) {
                    // vpnPermissionContext is sticky and is never reset, so a previous "Enable VPN"
                    // tap would otherwise send this result down the VPN-fallback branch and the
                    // retry would silently never happen.
                    vpnPermissionContext = VpnPermissionContext.FIREWALL_START
                    vpnPermissionLauncher.launch(prepareIntent)
                }
            }

            // Straight to the stop, with no confirmation dialog: the user already confirmed the
            // stop that failed, and asking again would be asking them to confirm a retry.
            FirewallHealthAction.RETRY_STOP -> firewallViewModel.stopFirewall()

            FirewallHealthAction.ENABLE_VPN,
            FirewallHealthAction.REPLACE_VPN -> handleVpnFallbackRequest()
        }
    }

    private fun openNotificationSettings() {
        AppLogger.d(TAG, "Opening notification settings from the firewall health banner")
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "App notification settings unavailable, falling back to app details: ${e.message}")
            openAppSettings(packageName)
        }
    }

    private fun updateSwitchAppearance(isEnabled: Boolean) {
        binding.firewallToggle.setOnCheckedChangeListener(null)
        binding.firewallToggle.isChecked = isEnabled
        binding.firewallToggle.setOnCheckedChangeListener { _, isChecked ->
            onFirewallToggleChanged(isChecked)
        }

        updateFirewallBadges()
    }

    private fun onFirewallToggleChanged(enabled: Boolean) {
        if (enabled) {
            val prepareIntent = firewallViewModel.startFirewall()
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            }
        } else {
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

    fun navigateToFirewallWithApp(packageName: String, userId: Int = 0) {
        AppLogger.d(TAG, "🔘 USER ACTION: Navigate to Firewall with app: $packageName (userId=$userId)")
        loadFragment(Tab.FIREWALL)
        binding.root.postDelayed({
            firewallFragment?.openAppDialog(packageName, userId)
        }, 100)
    }

    fun navigateToPackagesWithApp(packageName: String, userId: Int = 0) {
        AppLogger.d(TAG, "🔘 USER ACTION: Navigate to Packages with app: $packageName (userId=$userId)")
        loadFragment(Tab.APPS)
        binding.root.postDelayed({
            packagesFragment?.openAppDialog(packageName, userId)
        }, 100)
    }

    fun navigateToSettings() {
        loadFragment(Tab.SETTINGS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTab.ordinal)
        outState.putInt(KEY_VPN_PERMISSION_CONTEXT, vpnPermissionContext.ordinal)
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

        val prepareIntent = try {
            android.net.VpnService.prepare(this)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check VPN permission", e)
            return
        }

        if (prepareIntent != null) {
            vpnPermissionContext = VpnPermissionContext.VPN_FALLBACK
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnFallbackAfterPermission()
        }
    }

    private fun handleBootFailureRecovery() {
        AppLogger.d(TAG, "Handling boot failure recovery from notification")

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(Constants.BootFailure.NOTIFICATION_ID)

        val firewallManager = (application as De1984Application).dependencies.firewallManager
        if (firewallManager.activeBackendType.value != null) {
            AppLogger.d(TAG, "Firewall already running, no recovery needed")
            Toast.makeText(this, "Firewall is already running", Toast.LENGTH_SHORT).show()
            return
        }

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
            AppLogger.d(TAG, "VPN permission not granted, requesting...")
            vpnPermissionContext = VpnPermissionContext.BOOT_FAILURE_RECOVERY
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
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

