package io.github.dorumrr.de1984.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.PermissionInfo
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.databinding.FragmentSettingsBinding
import io.github.dorumrr.de1984.domain.model.CaptivePortalMode
import io.github.dorumrr.de1984.domain.model.CaptivePortalPreset
import io.github.dorumrr.de1984.databinding.PermissionTierSectionBinding
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.ui.common.StandardDialog
import io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel
import io.github.dorumrr.de1984.utils.Constants
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings Fragment using XML Views
 */
class SettingsFragmentViews : BaseFragment<FragmentSettingsBinding>() {

    companion object {
        private const val TAG = "SettingsFragment"
    }

    private val viewModel: SettingsViewModel by activityViewModels {
        val app = requireActivity().application as De1984Application
        SettingsViewModel.Factory(
            requireContext(),
            app.dependencies.permissionManager,
            app.dependencies.rootManager,
            app.dependencies.shizukuManager,
            app.dependencies.firewallManager,
            app.dependencies.firewallRepository,
            app.dependencies.captivePortalManager,
            app.dependencies.bootProtectionManager,
            app.dependencies.provideSmartPolicySwitchUseCase()
        )
    }

    private val permissionViewModel: PermissionSetupViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        PermissionSetupViewModel.Factory(
            context = requireContext(),
            permissionManager = app.dependencies.permissionManager,
            firewallManager = app.dependencies.firewallManager
        )
    }

    // Permission launcher for POST_NOTIFICATIONS
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionViewModel.markNotificationPermissionRequested()
        permissionViewModel.refreshPermissions()
    }

    // Activity result launcher for battery optimization settings
    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Refresh permissions when user returns from battery settings
        permissionViewModel.refreshPermissions()
    }

    // Activity result launcher for VPN permission
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // VPN permission granted
            permissionViewModel.refreshPermissions()
        } else {
            // VPN permission denied - refresh anyway to update UI
            permissionViewModel.refreshPermissions()
        }
    }

    // Backup launcher - creates a new JSON file
    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.backupRules(it) }
    }

    // Restore launcher - opens an existing JSON file
    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { showRestorePreview(it) }
    }

    private var lastRootTestTime = 0L

    // Flag to track if we've attached the dynamic colors switch listener
    private var isDynamicColorsSwitchListenerAttached = false

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentSettingsBinding.inflate(inflater, container, false)

    override fun scrollToTop() {
        // Only scroll if binding is available (fragment view is created)
        _binding?.root?.scrollTo(0, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: Settings fragment view created")

        setupViews()
        observeUiState()
        observePermissionState()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Settings fragment resumed")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Settings fragment paused")
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        Log.d(TAG, "onHiddenChanged: hidden=$hidden")

        // When fragment becomes visible, update UI with current state
        if (!hidden) {
            Log.d(TAG, "onHiddenChanged: Fragment became visible, updating UI")
            updateUI(viewModel.uiState.value)
            // Refresh permission state to show current status (fixes UI not updating after granting permissions)
            permissionViewModel.refreshPermissions()
        }
    }

    private fun setupViews() {
        // Donate button (included layout - access via root view)
        binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.donate_button)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://donate.stripe.com/5kQeV6cOgaxGcsf9iD3ZK01"))
            startActivity(intent)
        }

        // Contribute link
        binding.contributeLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dorumrr/de1984"))
            startActivity(intent)
        }

        // Firewall policy switch
        binding.firewallPolicySwitch.setOnCheckedChangeListener { _, isChecked ->
            val policy = if (isChecked) {
                Constants.Settings.POLICY_BLOCK_ALL
            } else {
                Constants.Settings.POLICY_ALLOW_ALL
            }
            viewModel.setDefaultFirewallPolicy(policy)
            updateFirewallPolicyDescription(isChecked)
        }

        // Backend selection dropdown
        setupBackendSelectionDropdown()

        // Language selection dropdown
        setupLanguageSelectionDropdown()

        // Show app icons switch
        binding.showAppIconsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowAppIcons(isChecked)
        }

        // Use dynamic colors switch
        // Note: Don't attach listener here - it will be attached in updateUI() after the initial state is set
        // This prevents the listener from firing when the switch state is restored from instance state

        // Allow critical package uninstall switch - listener is set in updateUI() to avoid triggering during initialization

        // Show firewall start prompt switch
        binding.showFirewallStartPromptSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowFirewallStartPrompt(isChecked)
        }

        // New app notifications switch
        binding.newAppNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNewAppNotifications(isChecked)
        }

        // Boot protection switch - listener is set in updateUI() to handle confirmation dialog
        // (We need to show a warning dialog before enabling/disabling)

        // Backup rules button
        binding.backupRulesButton.setOnClickListener {
            val filename = "de1984-firewall-backup-${viewModel.getCurrentDate()}.json"
            backupLauncher.launch(filename)
        }

        // Restore rules button
        binding.restoreRulesButton.setOnClickListener {
            restoreLauncher.launch(arrayOf("application/json"))
        }

        // Captive Portal Controller
        setupCaptivePortalSection()

        // Footer (author link) - make only "Doru Moraru" clickable
        setupFooterLink()
    }

    private fun setupFooterLink() {
        val fullText = getString(io.github.dorumrr.de1984.R.string.footer_tagline)
        val clickableText = getString(io.github.dorumrr.de1984.R.string.footer_author_name)
        val startIndex = fullText.indexOf(clickableText)
        val endIndex = startIndex + clickableText.length

        val spannableString = android.text.SpannableString(fullText)

        val clickableSpan = object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(io.github.dorumrr.de1984.R.string.footer_author_url)))
                startActivity(intent)
            }

            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                ds.color = ContextCompat.getColor(requireContext(), io.github.dorumrr.de1984.R.color.lineage_teal)
                ds.isUnderlineText = false  // No underline
            }
        }

        spannableString.setSpan(
            clickableSpan,
            startIndex,
            endIndex,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.footerText.text = spannableString
        binding.footerText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Only update UI if fragment is visible (not hidden)
                    if (!isHidden) {
                        Log.d(TAG, "observeUiState: Fragment is visible, updating UI (requiresRestart=${state.requiresRestart})")
                        updateUI(state)

                        // Show restart dialog if needed (only when user toggles the switch)
                        if (state.requiresRestart) {
                            Log.d(TAG, "observeUiState: Showing restart dialog")
                            showRestartDialog()
                            viewModel.clearRestartPrompt()
                        }
                    } else {
                        Log.d(TAG, "observeUiState: Fragment is hidden, skipping UI update")
                    }
                }
            }
        }
    }

    private fun updateUI(state: io.github.dorumrr.de1984.presentation.viewmodel.SettingsUiState) {
        // Update app version
        binding.appVersion.text = getString(io.github.dorumrr.de1984.R.string.settings_app_version, state.appVersion)

        // Update switches (without triggering listeners)
        binding.firewallPolicySwitch.setOnCheckedChangeListener(null)
        binding.firewallPolicySwitch.isChecked =
            state.defaultFirewallPolicy == Constants.Settings.POLICY_BLOCK_ALL
        binding.firewallPolicySwitch.setOnCheckedChangeListener { _, isChecked ->
            val policy = if (isChecked) {
                Constants.Settings.POLICY_BLOCK_ALL
            } else {
                Constants.Settings.POLICY_ALLOW_ALL
            }
            viewModel.setDefaultFirewallPolicy(policy)
            updateFirewallPolicyDescription(isChecked)
        }
        updateFirewallPolicyDescription(binding.firewallPolicySwitch.isChecked)

        binding.showAppIconsSwitch.setOnCheckedChangeListener(null)
        binding.showAppIconsSwitch.isChecked = state.showAppIcons
        binding.showAppIconsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowAppIcons(isChecked)
        }

        // Update dynamic colors switch state
        // First time: set the state and attach the listener
        // Subsequent times: temporarily remove listener, update state, re-attach listener
        val wasListenerAttached = isDynamicColorsSwitchListenerAttached
        if (wasListenerAttached) {
            binding.useDynamicColorsSwitch.setOnCheckedChangeListener(null)
        }

        Log.d(TAG, "updateUI: Setting useDynamicColorsSwitch.isChecked = ${state.useDynamicColors}")
        binding.useDynamicColorsSwitch.isChecked = state.useDynamicColors

        // Attach listener (only if not already attached, or re-attach after removing)
        binding.useDynamicColorsSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "updateUI: Dynamic colors switch toggled to $isChecked by user")
            viewModel.setUseDynamicColors(isChecked, showRestartDialog = true)
        }
        isDynamicColorsSwitchListenerAttached = true

        binding.allowCriticalUninstallSwitch.setOnCheckedChangeListener(null)
        Log.d(TAG, "updateUI: Setting allowCriticalUninstallSwitch.isChecked = ${state.allowCriticalPackageUninstall}")
        binding.allowCriticalUninstallSwitch.isChecked = state.allowCriticalPackageUninstall
        binding.allowCriticalUninstallSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "allowCriticalUninstallSwitch listener (from updateUI) triggered: isChecked=$isChecked")
            if (isChecked) {
                Log.d(TAG, "Showing critical uninstall warning dialog (from updateUI)")
                showCriticalUninstallWarning {
                    viewModel.setAllowCriticalPackageUninstall(true)
                }
            } else {
                Log.d(TAG, "Disabling critical package uninstall (from updateUI)")
                viewModel.setAllowCriticalPackageUninstall(false)
            }
        }

        binding.allowCriticalFirewallSwitch.setOnCheckedChangeListener(null)
        Log.d(TAG, "updateUI: Setting allowCriticalFirewallSwitch.isChecked = ${state.allowCriticalPackageFirewall}")
        binding.allowCriticalFirewallSwitch.isChecked = state.allowCriticalPackageFirewall
        binding.allowCriticalFirewallSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "allowCriticalFirewallSwitch listener (from updateUI) triggered: isChecked=$isChecked")
            if (isChecked) {
                Log.d(TAG, "Showing critical firewall warning dialog (from updateUI)")
                showCriticalFirewallWarning {
                    viewModel.setAllowCriticalPackageFirewall(true)
                }
            } else {
                Log.d(TAG, "Disabling critical package firewall (from updateUI)")
                viewModel.setAllowCriticalPackageFirewall(false)
            }
        }

        binding.showFirewallStartPromptSwitch.setOnCheckedChangeListener(null)
        binding.showFirewallStartPromptSwitch.isChecked = state.showFirewallStartPrompt
        binding.showFirewallStartPromptSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowFirewallStartPrompt(isChecked)
        }

        binding.newAppNotificationsSwitch.setOnCheckedChangeListener(null)
        binding.newAppNotificationsSwitch.isChecked = state.newAppNotifications
        binding.newAppNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNewAppNotifications(isChecked)
        }

        // Boot protection switch
        binding.bootProtectionSwitch.setOnCheckedChangeListener(null)

        // If boot protection is unavailable, force switch to OFF and disable it
        if (!state.bootProtectionAvailable) {
            binding.bootProtectionSwitch.isChecked = false
            binding.bootProtectionSwitch.isEnabled = false
            binding.bootProtectionDescription.text = getString(io.github.dorumrr.de1984.R.string.settings_boot_protection_unavailable)
        } else {
            binding.bootProtectionSwitch.isChecked = state.bootProtection
            binding.bootProtectionSwitch.isEnabled = true
            binding.bootProtectionDescription.text = getString(io.github.dorumrr.de1984.R.string.settings_boot_protection_description)
        }

        binding.bootProtectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "bootProtectionSwitch listener triggered: isChecked=$isChecked")
            if (isChecked) {
                Log.d(TAG, "Showing boot protection enable warning dialog")
                showBootProtectionWarning(true) {
                    viewModel.setBootProtection(true)
                }
            } else {
                Log.d(TAG, "Showing boot protection disable warning dialog")
                showBootProtectionWarning(false) {
                    viewModel.setBootProtection(false)
                }
            }
        }

        // Update backend selection dropdown
        setupBackendSelectionDropdown()
        updateBackendStatus()

        // Update language selection dropdown
        setupLanguageSelectionDropdown()

        // Show success message (clear immediately to prevent re-showing on every state update)
        state.message?.let { message ->
            viewModel.clearMessage()
            StandardDialog.showInfo(
                context = requireContext(),
                title = getString(R.string.dialog_success),
                message = message
            )
        }

        // Show error message (clear immediately to prevent re-showing on every state update)
        state.error?.let { error ->
            viewModel.clearError()
            StandardDialog.showError(
                context = requireContext(),
                message = error
            )
        }
    }

    private fun updateFirewallPolicyDescription(isBlockAll: Boolean) {
        binding.firewallPolicyDescription.text = if (isBlockAll) {
            getString(io.github.dorumrr.de1984.R.string.settings_firewall_policy_description_block_all)
        } else {
            getString(io.github.dorumrr.de1984.R.string.settings_firewall_policy_description_allow_all)
        }
    }

    private fun setupBackendSelectionDropdown() {
        // Get ALL backends (available and unavailable)
        val allBackends = getAllBackends()

        // Create custom adapter that shows disabled items
        val adapter = BackendAdapter(requireContext(), allBackends)

        binding.backendSelectionDropdown.setAdapter(adapter)

        // Set current selection
        val currentMode = viewModel.uiState.value.firewallMode
        val currentIndex = allBackends.indexOfFirst { it.mode == currentMode }
        if (currentIndex >= 0) {
            binding.backendSelectionDropdown.setText(allBackends[currentIndex].displayName, false)
        }

        // Handle selection changes - only allow selecting available backends
        binding.backendSelectionDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedBackend = allBackends[position]
            if (selectedBackend.isAvailable) {
                viewModel.setFirewallMode(selectedBackend.mode)
            } else {
                // Revert to current selection if unavailable backend was clicked
                binding.backendSelectionDropdown.setText(allBackends[currentIndex].displayName, false)

                // Show info about why it's not available
                StandardDialog.showInfo(
                    context = requireContext(),
                    title = getString(R.string.backend_not_available_title, selectedBackend.displayName),
                    message = selectedBackend.requirementText ?: getString(R.string.backend_not_available_message)
                )
            }
        }

        // Setup info icon click handler
        binding.backendInfoIcon.setOnClickListener {
            showBackendInfoDialog()
        }
    }

    private fun setupLanguageSelectionDropdown() {
        data class LanguageOption(val code: String, val displayName: String)

        val languages = listOf(
            LanguageOption(Constants.Settings.LANGUAGE_SYSTEM_DEFAULT, getString(io.github.dorumrr.de1984.R.string.language_system_default)),
            LanguageOption(Constants.Settings.LANGUAGE_ENGLISH, getString(io.github.dorumrr.de1984.R.string.language_english)),
            LanguageOption(Constants.Settings.LANGUAGE_ROMANIAN, getString(io.github.dorumrr.de1984.R.string.language_romanian)),
            LanguageOption(Constants.Settings.LANGUAGE_PORTUGUESE, getString(io.github.dorumrr.de1984.R.string.language_portuguese)),
            LanguageOption(Constants.Settings.LANGUAGE_CHINESE, getString(io.github.dorumrr.de1984.R.string.language_chinese)),
            LanguageOption(Constants.Settings.LANGUAGE_ITALIAN, getString(io.github.dorumrr.de1984.R.string.language_italian))
        ).let { list ->
            // Keep "System Default" first, sort the rest alphabetically by display name
            listOf(list.first()) + list.drop(1).sortedBy { it.displayName }
        }

        val adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            languages.map { it.displayName }
        )

        binding.languageSelectionDropdown.setAdapter(adapter)

        // Set current selection
        val currentLanguage = viewModel.uiState.value.appLanguage
        val currentIndex = languages.indexOfFirst { it.code == currentLanguage }
        if (currentIndex >= 0) {
            binding.languageSelectionDropdown.setText(languages[currentIndex].displayName, false)
        }

        // Handle selection changes
        // NOTE: Unlike other dropdowns, language selection requires special dismissal handling
        // because setApplicationLocales() immediately recreates the activity, which can
        // interrupt the dropdown dismissal animation if not delayed.
        binding.languageSelectionDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedLanguage = languages[position]

            // Save the language preference
            viewModel.setAppLanguage(selectedLanguage.code)

            // Update the displayed text immediately
            binding.languageSelectionDropdown.setText(selectedLanguage.displayName, false)

            // Aggressively dismiss the dropdown and clear focus
            binding.languageSelectionDropdown.dismissDropDown()
            binding.languageSelectionDropdown.clearFocus()

            // Hide the keyboard if it's showing
            val imm = requireContext().getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.languageSelectionDropdown.windowToken, 0)

            // Delay the locale change to allow the dropdown to fully dismiss
            // setApplicationLocales() triggers immediate activity recreation, so we need
            // to give the UI time to complete the dropdown dismissal animation
            binding.root.postDelayed({
                // Apply language change using AndroidX API (this will recreate the activity)
                val localeList = when (selectedLanguage.code) {
                    Constants.Settings.LANGUAGE_SYSTEM_DEFAULT -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    Constants.Settings.LANGUAGE_ENGLISH -> androidx.core.os.LocaleListCompat.forLanguageTags("en")
                    Constants.Settings.LANGUAGE_ROMANIAN -> androidx.core.os.LocaleListCompat.forLanguageTags("ro")
                    Constants.Settings.LANGUAGE_PORTUGUESE -> androidx.core.os.LocaleListCompat.forLanguageTags("pt")
                    Constants.Settings.LANGUAGE_CHINESE -> androidx.core.os.LocaleListCompat.forLanguageTags("zh")
                    Constants.Settings.LANGUAGE_ITALIAN -> androidx.core.os.LocaleListCompat.forLanguageTags("it")
                    else -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                }
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)
            }, Constants.UI.DROPDOWN_DISMISSAL_DELAY_MS)
        }
    }

    private fun getAllBackends(): List<BackendOption> {
        val backends = mutableListOf<BackendOption>()

        // Check current permissions
        val rootStatus = viewModel.rootStatus.value
        val shizukuStatus = viewModel.shizukuStatus.value
        val hasRoot = rootStatus == io.github.dorumrr.de1984.data.common.RootStatus.ROOTED_WITH_PERMISSION
        val hasShizukuRoot = shizukuStatus == io.github.dorumrr.de1984.data.common.ShizukuStatus.RUNNING_WITH_PERMISSION &&
                viewModel.isShizukuRootMode()
        val hasShizuku = shizukuStatus == io.github.dorumrr.de1984.data.common.ShizukuStatus.RUNNING_WITH_PERMISSION
        val isAndroid13Plus = android.os.Build.VERSION.SDK_INT >= 33

        // AUTO is always available
        backends.add(BackendOption(
            mode = io.github.dorumrr.de1984.domain.firewall.FirewallMode.AUTO,
            displayName = getString(R.string.backend_auto_name),
            description = getString(R.string.backend_auto_description),
            isAvailable = true
        ))

        // VPN is always available
        backends.add(BackendOption(
            mode = io.github.dorumrr.de1984.domain.firewall.FirewallMode.VPN,
            displayName = getString(R.string.backend_vpn_name),
            description = getString(R.string.backend_vpn_description),
            isAvailable = true
        ))

        // ConnectivityManager requires Shizuku + Android 13+
        val connectivityManagerAvailable = hasShizuku && isAndroid13Plus
        val connectivityManagerRequirement = when {
            !isAndroid13Plus -> getString(R.string.backend_requires_android_13)
            !hasShizuku -> getString(R.string.backend_requires_shizuku)
            else -> null
        }
        backends.add(BackendOption(
            mode = io.github.dorumrr.de1984.domain.firewall.FirewallMode.CONNECTIVITY_MANAGER,
            displayName = getString(R.string.backend_connectivity_manager_name),
            description = getString(R.string.backend_connectivity_manager_description),
            isAvailable = connectivityManagerAvailable,
            requirementText = connectivityManagerRequirement
        ))

        // iptables requires root or Shizuku in root mode
        val iptablesAvailable = hasRoot || hasShizukuRoot
        backends.add(BackendOption(
            mode = io.github.dorumrr.de1984.domain.firewall.FirewallMode.IPTABLES,
            displayName = getString(R.string.backend_iptables_name),
            description = getString(R.string.backend_iptables_description),
            isAvailable = iptablesAvailable,
            requirementText = if (!iptablesAvailable) getString(R.string.backend_iptables_requirement) else null
        ))

        // NetworkPolicyManager requires Shizuku (legacy backend for Android 12 and below)
        // Note: ConnectivityManager is preferred on Android 13+ as it blocks all networks reliably
        val networkPolicyManagerAvailable = hasShizuku
        backends.add(BackendOption(
            mode = io.github.dorumrr.de1984.domain.firewall.FirewallMode.NETWORK_POLICY_MANAGER,
            displayName = getString(R.string.backend_network_policy_manager_name),
            description = getString(R.string.backend_network_policy_manager_description),
            isAvailable = networkPolicyManagerAvailable,
            requirementText = if (!networkPolicyManagerAvailable) getString(R.string.backend_network_policy_manager_requirement) else null
        ))

        return backends
    }

    private fun updateBackendStatus() {
        val activeBackend = viewModel.activeBackendType.value

        val statusText = if (activeBackend != null) {
            getString(io.github.dorumrr.de1984.R.string.settings_backend_active, activeBackend.name)
        } else {
            getString(io.github.dorumrr.de1984.R.string.settings_backend_not_running)
        }

        binding.backendStatusText.text = statusText
    }

    private fun showBackendInfoDialog() {
        StandardDialog.showInfo(
            context = requireContext(),
            title = getString(R.string.dialog_firewall_backends_title),
            message = getString(R.string.dialog_firewall_backends_message)
        )
    }

    private data class BackendOption(
        val mode: io.github.dorumrr.de1984.domain.firewall.FirewallMode,
        val displayName: String,
        val description: String,
        val isAvailable: Boolean = true,
        val requirementText: String? = null
    ) {
        override fun toString(): String = displayName
    }



    private fun observePermissionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    permissionViewModel.uiState.collect { permissionState ->
                        updatePermissionTiers(permissionState)
                    }
                }
                launch {
                    viewModel.rootStatus.collect { rootStatus ->
                        updateRootStatus(rootStatus)

                        // Refresh permission tiers to update button visibility
                        updatePermissionTiers(permissionViewModel.uiState.value)

                        // Refresh permissions when root permission is granted
                        if (rootStatus == RootStatus.ROOTED_WITH_PERMISSION) {
                            permissionViewModel.refreshPermissions()
                        }

                        // Refresh backend dropdown when permissions change
                        setupBackendSelectionDropdown()
                    }
                }
                launch {
                    viewModel.shizukuStatus.collect { shizukuStatus ->
                        updateShizukuStatus(shizukuStatus)

                        // Refresh permission tiers to update button visibility
                        updatePermissionTiers(permissionViewModel.uiState.value)

                        // Refresh permissions when Shizuku permission is granted
                        if (shizukuStatus == ShizukuStatus.RUNNING_WITH_PERMISSION) {
                            permissionViewModel.refreshPermissions()
                        }

                        // Refresh backend dropdown when permissions change
                        setupBackendSelectionDropdown()
                    }
                }
                launch {
                    viewModel.activeBackendType.collect { _ ->
                        updateBackendStatus()
                    }
                }
            }
        }
    }

    private fun updatePermissionTiers(state: io.github.dorumrr.de1984.ui.permissions.PermissionSetupUiState) {
        // Setup Basic Tier
        setupPermissionTier(
            binding.permissionTierBasic,
            title = getString(io.github.dorumrr.de1984.R.string.permission_tier_basic_title),
            description = getString(io.github.dorumrr.de1984.R.string.permission_tier_basic_desc),
            status = if (state.hasBasicPermissions) getString(io.github.dorumrr.de1984.R.string.permission_status_completed) else getString(io.github.dorumrr.de1984.R.string.permission_status_required),
            isComplete = state.hasBasicPermissions,
            permissions = state.basicPermissions,
            setupButtonText = getString(io.github.dorumrr.de1984.R.string.permission_button_grant),
            onSetupClick = if (!state.hasBasicPermissions) {
                { handleBasicPermissionsRequest() }
            } else null
        )

        // Setup Battery Tier
        setupPermissionTier(
            binding.permissionTierBattery,
            title = getString(io.github.dorumrr.de1984.R.string.permission_tier_battery_title),
            description = getString(io.github.dorumrr.de1984.R.string.permission_tier_battery_desc),
            status = if (state.hasBatteryOptimizationExemption) getString(io.github.dorumrr.de1984.R.string.permission_status_completed) else getString(io.github.dorumrr.de1984.R.string.permission_status_required),
            isComplete = state.hasBatteryOptimizationExemption,
            permissions = state.batteryOptimizationInfo,
            setupButtonText = getString(io.github.dorumrr.de1984.R.string.permission_button_grant),
            onSetupClick = if (!state.hasBatteryOptimizationExemption) {
                { handleBatteryOptimizationRequest() }
            } else null
        )

        // Setup Advanced Tier
        // Only show button when there's actually something to grant
        val shizukuStatus = viewModel.shizukuStatus.value
        val rootStatus = viewModel.rootStatus.value

        val canActuallyGrantPermission = when {
            // Can grant Shizuku permission (Shizuku is running but no permission)
            shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION -> true

            // Can grant root permission (device is rooted but no permission)
            rootStatus == RootStatus.ROOTED_NO_PERMISSION -> true

            // Nothing to grant - either not installed/not running, or already has permission
            else -> false
        }

        val showRootButton = canActuallyGrantPermission && !state.hasAdvancedPermissions

        // Determine button text based on what's available
        val buttonText = if (shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION) {
            getString(io.github.dorumrr.de1984.R.string.permission_button_grant_shizuku)
        } else {
            getString(io.github.dorumrr.de1984.R.string.permission_button_grant_privileged)
        }

        setupPermissionTier(
            binding.permissionTierAdvanced,
            title = getString(io.github.dorumrr.de1984.R.string.permission_tier_advanced_title),
            description = getString(io.github.dorumrr.de1984.R.string.permission_tier_advanced_desc),
            status = if (state.hasAdvancedPermissions) getString(io.github.dorumrr.de1984.R.string.permission_status_completed) else getString(io.github.dorumrr.de1984.R.string.permission_status_shizuku_or_root_required),
            isComplete = state.hasAdvancedPermissions,
            permissions = state.advancedPermissions,
            setupButtonText = buttonText,
            onSetupClick = if (showRootButton) {
                { handleRootAccessRequest() }
            } else null
        )

        // Setup VPN Tier
        setupPermissionTier(
            binding.permissionTierVpn,
            title = getString(io.github.dorumrr.de1984.R.string.permission_tier_vpn_title),
            description = getString(io.github.dorumrr.de1984.R.string.permission_tier_vpn_desc),
            status = if (state.hasVpnPermission) getString(io.github.dorumrr.de1984.R.string.permission_status_completed) else getString(io.github.dorumrr.de1984.R.string.permission_status_required),
            isComplete = state.hasVpnPermission,
            permissions = state.vpnPermissionInfo,
            setupButtonText = getString(io.github.dorumrr.de1984.R.string.permission_button_grant_vpn),
            onSetupClick = if (!state.hasVpnPermission) {
                { handleVpnPermissionRequest() }
            } else null
        )
    }

    private fun setupPermissionTier(
        tierBinding: PermissionTierSectionBinding,
        title: String,
        description: String,
        status: String,
        isComplete: Boolean,
        permissions: List<PermissionInfo>,
        setupButtonText: String,
        onSetupClick: (() -> Unit)?
    ) {
        // Set title and description
        tierBinding.tierTitle.text = title
        tierBinding.tierDescription.text = description

        // Set status badge
        tierBinding.tierStatusBadge.text = status
        tierBinding.tierStatusBadge.setBackgroundResource(
            if (isComplete) R.drawable.status_badge_complete
            else R.drawable.status_badge_background
        )
        tierBinding.tierStatusBadge.setTextColor(
            requireContext().getColor(
                if (isComplete) R.color.badge_text_success
                else R.color.badge_text_error
            )
        )

        // Setup permissions list
        tierBinding.permissionsListContainer.removeAllViews()
        permissions.forEach { permission ->
            val permissionView = layoutInflater.inflate(
                R.layout.permission_item,
                tierBinding.permissionsListContainer,
                false
            )
            val icon = permissionView.findViewById<ImageView>(R.id.permission_icon)
            val name = permissionView.findViewById<TextView>(R.id.permission_name)

            name.text = permission.name
            if (permission.isGranted) {
                icon.setImageResource(R.drawable.ic_check)
                icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.lineage_teal))
            } else {
                icon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                icon.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            }

            tierBinding.permissionsListContainer.addView(permissionView)
        }

        // Setup button
        if (onSetupClick != null) {
            tierBinding.setupButtonContainer.visibility = View.VISIBLE
            tierBinding.setupButton.text = setupButtonText
            tierBinding.setupButton.setOnClickListener { onSetupClick() }
        } else {
            tierBinding.setupButtonContainer.visibility = View.GONE
        }

        // Root status visibility is controlled by updateRootStatus() based on actual root status
        // Don't override it here
    }

    private fun updateRootStatus(rootStatus: RootStatus) {
        val tierBinding = binding.permissionTierAdvanced

        // Get theme-aware icon color (textColorPrimary for maximum contrast)
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val iconColor = typedValue.data

        // Check if Shizuku is available - if so, prioritize Shizuku over root
        val shizukuStatus = viewModel.shizukuStatus.value
        val shizukuAvailable = shizukuStatus == ShizukuStatus.RUNNING_WITH_PERMISSION ||
                               shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION

        when (rootStatus) {
            RootStatus.ROOTED_WITH_PERMISSION -> {
                // Root is granted - hide root status section entirely
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            RootStatus.ROOTED_NO_PERMISSION -> {
                // Don't show root UI if Shizuku is available
                if (shizukuAvailable) {
                    return
                }

                // Device is rooted but permission not granted
                if (viewModel.hasRequestedRootPermission()) {
                    // User already tried and denied - show instructions, hide button
                    tierBinding.rootStatusContainer.visibility = View.VISIBLE
                    tierBinding.rootStatusIcon.setColorFilter(iconColor)
                    tierBinding.rootStatusTitle.text = getString(R.string.root_status_denied)
                    tierBinding.rootStatusDescription.text = getString(R.string.root_desc_denied)
                    tierBinding.rootStatusInstructions.visibility = View.VISIBLE
                    tierBinding.rootStatusInstructions.text = getString(R.string.root_grant_instructions_title) + "\n" + getString(R.string.root_grant_instructions_body)
                    tierBinding.rootingToolsContainer.visibility = View.GONE
                    tierBinding.setupButtonContainer.visibility = View.GONE
                } else {
                    // Never requested - show button, hide status
                    tierBinding.rootStatusContainer.visibility = View.GONE
                    tierBinding.rootingToolsContainer.visibility = View.GONE
                    tierBinding.setupButtonContainer.visibility = View.VISIBLE
                    tierBinding.setupButton.text = getString(io.github.dorumrr.de1984.R.string.permission_button_grant_privileged)
                    tierBinding.setupButton.setOnClickListener {
                        handleRootAccessRequest()
                    }
                }
            }
            RootStatus.NOT_ROOTED -> {
                // Don't show root UI if Shizuku is available
                if (shizukuAvailable) {
                    return
                }

                // Device is not rooted - show message and rooting tools
                tierBinding.rootStatusContainer.visibility = View.VISIBLE
                tierBinding.rootStatusIcon.setColorFilter(iconColor)
                tierBinding.rootStatusTitle.text = getString(R.string.root_status_not_available)
                tierBinding.rootStatusDescription.text = getString(R.string.root_desc_not_available)
                tierBinding.rootStatusInstructions.visibility = View.GONE

                // Show rooting tools in separate card
                tierBinding.rootingToolsContainer.visibility = View.VISIBLE
                tierBinding.rootingToolsTitle.text = getString(R.string.root_rooting_tools_title).replace("<b>", "").replace("</b>", "")
                tierBinding.rootingToolsBody.text = getString(R.string.root_rooting_tools_body)

                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            RootStatus.CHECKING -> {
                // Checking root status - hide everything during check
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
        }
    }

    private fun updateShizukuStatus(shizukuStatus: ShizukuStatus) {
        val tierBinding = binding.permissionTierAdvanced

        // Get theme-aware icon color (textColorPrimary for maximum contrast)
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val iconColor = typedValue.data

        // Only show Shizuku status if root is not available
        val rootStatus = viewModel.rootStatus.value
        if (rootStatus == RootStatus.ROOTED_WITH_PERMISSION) {
            // Root is granted, don't show Shizuku status
            return
        }

        when (shizukuStatus) {
            ShizukuStatus.RUNNING_WITH_PERMISSION -> {
                // Shizuku is granted - hide status section
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            ShizukuStatus.RUNNING_NO_PERMISSION -> {
                // Shizuku is running but permission not granted
                tierBinding.rootStatusContainer.visibility = View.VISIBLE
                tierBinding.rootStatusIcon.setColorFilter(iconColor)
                tierBinding.rootStatusTitle.text = getString(R.string.shizuku_status_denied)
                tierBinding.rootStatusDescription.text = getString(R.string.shizuku_desc_denied)
                tierBinding.rootStatusInstructions.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.VISIBLE
                tierBinding.setupButton.text = getString(io.github.dorumrr.de1984.R.string.permission_button_grant_shizuku)
                tierBinding.setupButton.setOnClickListener {
                    viewModel.grantShizukuPermission()
                }
            }
            ShizukuStatus.INSTALLED_NOT_RUNNING -> {
                // Shizuku is installed but not running
                tierBinding.rootStatusContainer.visibility = View.VISIBLE
                tierBinding.rootStatusIcon.setColorFilter(iconColor)
                tierBinding.rootStatusTitle.text = getString(R.string.shizuku_status_not_running)
                tierBinding.rootStatusDescription.text = getString(R.string.shizuku_desc_not_running)
                tierBinding.rootStatusInstructions.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            ShizukuStatus.NOT_INSTALLED -> {
                // Shizuku is not installed - hide card, user will learn about Shizuku when they tap "Grant Privileged Access"
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            ShizukuStatus.CHECKING -> {
                // Checking Shizuku status - hide everything during check
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
        }
    }

    private fun handleBasicPermissionsRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS

            val isGranted = ContextCompat.checkSelfPermission(
                requireContext(),
                permission
            ) == PackageManager.PERMISSION_GRANTED

            if (isGranted) {
                permissionViewModel.refreshPermissions()
            } else {
                val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                    requireActivity(),
                    permission
                )
                val hasRequestedPermission = permissionViewModel.hasRequestedNotificationPermission()
                val isFirstTime = !hasRequestedPermission
                val canShowDialog = shouldShowRationale || isFirstTime

                if (canShowDialog) {
                    try {
                        notificationPermissionLauncher.launch(permission)
                    } catch (e: Exception) {
                        openAppSettings()
                    }
                } else {
                    openAppSettings()
                }
            }
        } else {
            permissionViewModel.refreshPermissions()
        }
    }

    private fun handleBatteryOptimizationRequest() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${requireContext().packageName}")
                )
            } else {
                null
            }
            intent?.let { batteryOptimizationLauncher.launch(it) }
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                batteryOptimizationLauncher.launch(intent)
            } catch (e2: Exception) {
                openAppSettings()
            }
        }
    }

    private fun handleVpnPermissionRequest() {
        try {
            val prepareIntent = VpnService.prepare(requireContext())
            if (prepareIntent != null) {
                // VPN permission not granted - request it
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                // VPN permission already granted
                permissionViewModel.refreshPermissions()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request VPN permission", e)
            // Show error to user
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_vpn_permission_error_title))
                .setMessage(getString(R.string.dialog_vpn_permission_error_message, e.message))
                .setPositiveButton(getString(R.string.dialog_ok), null)
                .show()
        }
    }

    private fun handleRootAccessRequest() {
        // Check if Shizuku is available - if so, request Shizuku permission instead
        val shizukuStatus = viewModel.shizukuStatus.value
        if (shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION) {
            viewModel.grantShizukuPermission()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRootTestTime < 1000) {
            return
        }
        lastRootTestTime = currentTime

        viewModel.markRootPermissionRequested()

        var resultMessage = getString(R.string.dialog_privileged_access_testing)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_privileged_access_title))
            .setMessage(resultMessage)
            .setCancelable(true)
            .setNegativeButton(getString(R.string.dialog_cancel)) { d, _ -> d.dismiss() }
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                resultMessage = testRootAccess()

                // Check if we should show the reusable privileged access dialogs
                if (resultMessage == "NO_PRIVILEGED_ACCESS") {
                    dialog.dismiss()
                    StandardDialog.showNoAccessDialog(requireContext())
                } else if (resultMessage == "ROOT_ACCESS_DENIED") {
                    dialog.dismiss()
                    StandardDialog.showRootDeniedDialog(requireContext()) {
                        // Refresh permissions and root status after dismissing
                        permissionViewModel.refreshPermissions()
                        viewModel.requestRootPermission()
                    }
                } else if (dialog.isShowing) {
                    // Use Html.fromHtml to support bold text
                    val formattedMessage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        android.text.Html.fromHtml(resultMessage, android.text.Html.FROM_HTML_MODE_LEGACY)
                    } else {
                        @Suppress("DEPRECATION")
                        android.text.Html.fromHtml(resultMessage)
                    }
                    dialog.setMessage(formattedMessage)
                    dialog.setCancelable(true)
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dialog_ok)) { d, _ ->
                        d.dismiss()
                        // Refresh permissions and root status after dismissing
                        permissionViewModel.refreshPermissions()
                        viewModel.requestRootPermission()
                    }
                    // Remove cancel button
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
                }
            } catch (e: Exception) {
                resultMessage = getString(R.string.dialog_privileged_access_failed, e.message)
                if (dialog.isShowing) {
                    dialog.setMessage(resultMessage)
                    dialog.setCancelable(true)
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dialog_ok)) { d, _ -> d.dismiss() }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun testRootAccess(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        return@withContext try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))

            // Wait for process with timeout
            val completed = kotlinx.coroutines.withTimeoutOrNull(5000) {
                process.waitFor()
            }

            if (completed == null) {
                // Drain streams before destroying
                try {
                    process.inputStream.bufferedReader().use { it.readText() }
                    process.errorStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    // Ignore stream read errors on timeout
                }
                process.destroy()
                "⏱️ Root Test Timeout\n\nThe root permission request timed out. This may happen if:\n• You didn't respond to the permission dialog\n• Your root manager is not responding\n\nPlease try again."
            } else if (completed == 0) {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() } // Drain error stream
                process.destroy()
                "✅ Root Access Granted!\n\nYour device is rooted and De1984 has been granted superuser permission.\n\nOutput: $output"
            } else {
                // Drain streams before destroying
                process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }
                process.destroy()
                // Return a marker that we'll use to show the reusable dialog
                "ROOT_ACCESS_DENIED"
            }
        } catch (e: Exception) {
            // Return a marker that we'll use to show the reusable dialog
            "NO_PRIVILEGED_ACCESS"
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    /**
     * Show restore preview dialog - parses backup file and shows metadata.
     */
    private fun showRestorePreview(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Show loading dialog
                val loadingDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.dialog_loading_backup_title))
                    .setMessage(getString(R.string.dialog_loading_backup_message))
                    .setCancelable(false)
                    .create()
                loadingDialog.show()

                // Parse backup file
                val result = viewModel.parseBackupFile(uri)
                loadingDialog.dismiss()

                result.fold(
                    onSuccess = { backup ->
                        showRestoreOptions(uri, backup)
                    },
                    onFailure = { error ->
                        StandardDialog.showError(
                            context = requireContext(),
                            message = getString(R.string.dialog_backup_read_error, error.message)
                        )
                    }
                )
            } catch (e: Exception) {
                StandardDialog.showError(
                    context = requireContext(),
                    message = getString(R.string.dialog_backup_load_error, e.message)
                )
            }
        }
    }

    /**
     * Show restore options dialog with backup preview.
     */
    private fun showRestoreOptions(uri: Uri, backup: io.github.dorumrr.de1984.domain.model.FirewallRulesBackup) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val backupDate = dateFormat.format(Date(backup.exportDate))

        val message = getString(
            R.string.dialog_restore_rules_message,
            backupDate,
            backup.appVersion,
            backup.rulesCount
        )

        StandardDialog.show(
            context = requireContext(),
            title = getString(R.string.dialog_restore_rules_title),
            message = message,
            positiveButtonText = getString(R.string.dialog_restore_rules_merge),
            onPositiveClick = {
                viewModel.restoreRules(uri, replaceExisting = false)
            },
            negativeButtonText = getString(R.string.dialog_restore_rules_replace),
            onNegativeClick = {
                showReplaceConfirmation(uri)
            },
            cancelable = true
        )
    }

    /**
     * Show confirmation dialog for destructive "Replace All" operation.
     */
    private fun showReplaceConfirmation(uri: Uri) {
        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.dialog_replace_all_title),
            message = getString(R.string.dialog_replace_all_message),
            confirmButtonText = getString(R.string.dialog_replace_all_confirm),
            onConfirm = {
                viewModel.restoreRules(uri, replaceExisting = true)
            },
            cancelButtonText = getString(R.string.dialog_cancel)
        )
    }

    /**
     * Custom adapter for backend dropdown that shows disabled items with different styling.
     */
    private class BackendAdapter(
        context: android.content.Context,
        private val backends: List<BackendOption>
    ) : android.widget.ArrayAdapter<BackendOption>(context, R.layout.item_backend_dropdown, backends) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val backend = backends[position]

            val textView = view.findViewById<TextView>(android.R.id.text1)
            textView.text = if (backend.isAvailable) {
                backend.displayName
            } else {
                "${backend.displayName} (${backend.requirementText})"
            }

            // Disable unavailable backends
            textView.isEnabled = backend.isAvailable
            textView.alpha = if (backend.isAvailable) 1.0f else 0.5f

            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getDropDownView(position, convertView, parent)
            val backend = backends[position]

            val textView = view.findViewById<TextView>(android.R.id.text1)
            textView.text = if (backend.isAvailable) {
                backend.displayName
            } else {
                "${backend.displayName} (${backend.requirementText})"
            }

            // Disable unavailable backends
            textView.isEnabled = backend.isAvailable
            textView.alpha = if (backend.isAvailable) 1.0f else 0.5f

            return view
        }

        override fun isEnabled(position: Int): Boolean {
            return backends[position].isAvailable
        }
    }

    private fun showCriticalUninstallWarning(onConfirm: () -> Unit) {
        Log.d(TAG, "showCriticalUninstallWarning: Displaying warning dialog")
        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.dialog_critical_uninstall_title),
            message = getString(R.string.dialog_critical_uninstall_message),
            confirmButtonText = getString(R.string.dialog_critical_uninstall_enable),
            onConfirm = {
                Log.d(TAG, "showCriticalUninstallWarning: User confirmed")
                onConfirm()
            },
            cancelButtonText = getString(R.string.dialog_cancel),
            onCancel = {
                Log.d(TAG, "showCriticalUninstallWarning: User cancelled, reverting switch")
                // Revert switch state
                binding.allowCriticalUninstallSwitch.setOnCheckedChangeListener(null)
                binding.allowCriticalUninstallSwitch.isChecked = false
                binding.allowCriticalUninstallSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        showCriticalUninstallWarning {
                            viewModel.setAllowCriticalPackageUninstall(true)
                        }
                    } else {
                        viewModel.setAllowCriticalPackageUninstall(false)
                    }
                }
            }
        )
    }

    private fun showCriticalFirewallWarning(onConfirm: () -> Unit) {
        Log.d(TAG, "showCriticalFirewallWarning: Displaying warning dialog")
        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.dialog_critical_firewall_title),
            message = getString(R.string.dialog_critical_firewall_message),
            confirmButtonText = getString(R.string.dialog_critical_firewall_enable),
            onConfirm = {
                Log.d(TAG, "showCriticalFirewallWarning: User confirmed")
                onConfirm()
            },
            cancelButtonText = getString(R.string.dialog_cancel),
            onCancel = {
                Log.d(TAG, "showCriticalFirewallWarning: User cancelled, reverting switch")
                // Revert switch state
                binding.allowCriticalFirewallSwitch.setOnCheckedChangeListener(null)
                binding.allowCriticalFirewallSwitch.isChecked = false
                binding.allowCriticalFirewallSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        showCriticalFirewallWarning {
                            viewModel.setAllowCriticalPackageFirewall(true)
                        }
                    } else {
                        viewModel.setAllowCriticalPackageFirewall(false)
                    }
                }
            }
        )
    }

    private fun showBootProtectionWarning(enable: Boolean, onConfirm: () -> Unit) {
        Log.d(TAG, "showBootProtectionWarning: enable=$enable, displaying warning dialog")

        val title = if (enable) {
            getString(R.string.boot_protection_enable_warning_title)
        } else {
            getString(R.string.boot_protection_disable_warning_title)
        }

        val message = if (enable) {
            getString(R.string.boot_protection_enable_warning_message)
        } else {
            getString(R.string.boot_protection_disable_warning_message)
        }

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = title,
            message = message,
            confirmButtonText = getString(R.string.dialog_continue),
            onConfirm = {
                Log.d(TAG, "showBootProtectionWarning: User confirmed")
                onConfirm()
            },
            cancelButtonText = getString(R.string.dialog_cancel),
            onCancel = {
                Log.d(TAG, "showBootProtectionWarning: User cancelled, reverting switch")
                // Revert switch state
                binding.bootProtectionSwitch.setOnCheckedChangeListener(null)
                binding.bootProtectionSwitch.isChecked = !enable
                binding.bootProtectionSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        showBootProtectionWarning(true) {
                            viewModel.setBootProtection(true)
                        }
                    } else {
                        showBootProtectionWarning(false) {
                            viewModel.setBootProtection(false)
                        }
                    }
                }
            }
        )
    }

    private fun showRestartDialog() {
        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.dialog_restart_title),
            message = getString(R.string.dialog_restart_message),
            confirmButtonText = getString(R.string.dialog_restart_confirm),
            onConfirm = {
                Log.d(TAG, "showRestartDialog: User confirmed restart")
                restartApp()
            },
            cancelButtonText = getString(R.string.dialog_cancel),
            onCancel = {
                Log.d(TAG, "showRestartDialog: User cancelled restart")
            }
        )
    }

    private fun restartApp() {
        try {
            // To fully restart the app and trigger Application.onCreate() (which applies/removes dynamic colors),
            // we need to kill the process and start a new one
            val intent = requireActivity().packageManager.getLaunchIntentForPackage(requireActivity().packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                // Kill the current process to ensure a full restart
                android.os.Process.killProcess(android.os.Process.myPid())
            } else {
                Log.e(TAG, "Failed to get launch intent for restart")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart app: ${e.message}")
        }
    }

    // =============================================================================================
    // Captive Portal Controller
    // =============================================================================================

    private fun setupCaptivePortalSection() {
        // Load initial settings
        viewModel.loadCaptivePortalSettings()

        // Setup detection mode dropdown
        val modeDropdown = binding.root.findViewById<AutoCompleteTextView>(R.id.captivePortalModeDropdown)
        val modeOptions = CaptivePortalMode.values().map { it.getDisplayName(requireContext()) }
        val modeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, modeOptions)
        modeDropdown?.setAdapter(modeAdapter)
        modeDropdown?.setOnItemClickListener { _, _, position, _ ->
            val selectedMode = CaptivePortalMode.values()[position]
            viewModel.setCaptivePortalDetectionMode(selectedMode)
        }

        // Setup server preset dropdown
        val presetDropdown = binding.root.findViewById<AutoCompleteTextView>(R.id.captivePortalPresetDropdown)
        val presetOptions = CaptivePortalPreset.values().map { it.getDisplayName(requireContext()) }
        val presetAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, presetOptions)
        presetDropdown?.setAdapter(presetAdapter)
        presetDropdown?.setOnItemClickListener { _, _, position, _ ->
            val selectedPreset = CaptivePortalPreset.values()[position]

            if (selectedPreset == CaptivePortalPreset.CUSTOM) {
                // Show custom URLs section
                binding.root.findViewById<LinearLayout>(R.id.captivePortalCustomUrlsSection)?.visibility = View.VISIBLE
            } else {
                // Hide custom URLs section and apply preset
                binding.root.findViewById<LinearLayout>(R.id.captivePortalCustomUrlsSection)?.visibility = View.GONE
                viewModel.applyCaptivePortalPreset(selectedPreset)
            }
        }

        // Setup custom URL inputs
        val customHttpUrl = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.captivePortalCustomHttpUrl)
        val customHttpsUrl = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.captivePortalCustomHttpsUrl)

        // Apply custom URLs when text changes (with debounce would be better, but keeping it simple)
        customHttpUrl?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val httpUrl = customHttpUrl.text?.toString() ?: ""
                val httpsUrl = customHttpsUrl?.text?.toString() ?: ""
                if (httpUrl.isNotBlank() && httpsUrl.isNotBlank()) {
                    viewModel.setCustomCaptivePortalUrls(httpUrl, httpsUrl)
                }
            }
        }

        customHttpsUrl?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val httpUrl = customHttpUrl?.text?.toString() ?: ""
                val httpsUrl = customHttpsUrl.text?.toString() ?: ""
                if (httpUrl.isNotBlank() && httpsUrl.isNotBlank()) {
                    viewModel.setCustomCaptivePortalUrls(httpUrl, httpsUrl)
                }
            }
        }

        // Restore Original button
        binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.captivePortalRestoreButton)?.setOnClickListener {
            StandardDialog.showConfirmation(
                context = requireContext(),
                title = getString(R.string.dialog_captive_portal_restore_title),
                message = getString(R.string.dialog_captive_portal_restore_message),
                confirmButtonText = getString(R.string.dialog_captive_portal_restore_confirm),
                onConfirm = {
                    viewModel.restoreOriginalCaptivePortalSettings()
                }
            )
        }

        // Reset to Google button
        binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.captivePortalResetButton)?.setOnClickListener {
            StandardDialog.showConfirmation(
                context = requireContext(),
                title = getString(R.string.dialog_captive_portal_reset_title),
                message = getString(R.string.dialog_captive_portal_reset_message),
                confirmButtonText = getString(R.string.dialog_captive_portal_reset_confirm),
                onConfirm = {
                    viewModel.resetCaptivePortalToGoogleDefaults()
                }
            )
        }

        // Observe captive portal state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateCaptivePortalUI(state)
                }
            }
        }
    }

    private fun updateCaptivePortalUI(state: io.github.dorumrr.de1984.presentation.viewmodel.SettingsUiState) {
        val currentMode = binding.root.findViewById<TextView>(R.id.captivePortalCurrentMode)
        val currentHttpUrl = binding.root.findViewById<TextView>(R.id.captivePortalCurrentHttpUrl)
        val currentHttpsUrl = binding.root.findViewById<TextView>(R.id.captivePortalCurrentHttpsUrl)
        val currentPreset = binding.root.findViewById<TextView>(R.id.captivePortalCurrentPreset)
        val loadingIndicator = binding.root.findViewById<ProgressBar>(R.id.captivePortalLoadingIndicator)
        val errorText = binding.root.findViewById<TextView>(R.id.captivePortalErrorText)
        val modeDropdown = binding.root.findViewById<AutoCompleteTextView>(R.id.captivePortalModeDropdown)
        val presetDropdown = binding.root.findViewById<AutoCompleteTextView>(R.id.captivePortalPresetDropdown)
        val restoreButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.captivePortalRestoreButton)
        val resetButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.captivePortalResetButton)

        // Show/hide loading indicator
        loadingIndicator?.visibility = if (state.captivePortalLoading) View.VISIBLE else View.GONE

        // Show/hide error
        if (state.captivePortalError != null) {
            errorText?.text = state.captivePortalError
            errorText?.visibility = View.VISIBLE
        } else {
            errorText?.visibility = View.GONE
        }

        // Update current settings display
        state.captivePortalSettings?.let { settings ->
            currentMode?.text = getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_detection_mode, settings.mode.getDisplayName(requireContext()))
            currentHttpUrl?.text = getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_http_url, settings.httpUrl ?: getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_not_set))
            currentHttpsUrl?.text = getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_https_url, settings.httpsUrl ?: getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_not_set))
            currentPreset?.text = getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_preset, settings.getMatchingPreset().getDisplayName(requireContext()))

            // Update dropdown selections (without triggering listeners)
            modeDropdown?.setText(settings.mode.getDisplayName(requireContext()), false)
            presetDropdown?.setText(settings.getMatchingPreset().getDisplayName(requireContext()), false)
        }

        // Enable/disable controls based on privileges
        val hasPrivileges = state.captivePortalHasPrivileges
        val modeLayout = binding.root.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.captivePortalModeLayout)
        val presetLayout = binding.root.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.captivePortalPresetLayout)

        modeDropdown?.isEnabled = hasPrivileges
        modeDropdown?.isClickable = hasPrivileges
        modeLayout?.isEnabled = hasPrivileges

        presetDropdown?.isEnabled = hasPrivileges
        presetDropdown?.isClickable = hasPrivileges
        presetLayout?.isEnabled = hasPrivileges

        restoreButton?.isEnabled = hasPrivileges && state.captivePortalOriginalCaptured
        resetButton?.isEnabled = hasPrivileges

        // Show message if no privileges
        if (!hasPrivileges && state.captivePortalSettings != null) {
            errorText?.text = getString(R.string.settings_captive_portal_no_privileges)
            errorText?.visibility = View.VISIBLE
        }
    }
}

