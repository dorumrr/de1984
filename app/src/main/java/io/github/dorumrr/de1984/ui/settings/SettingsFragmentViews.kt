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
import io.github.dorumrr.de1984.domain.model.UninstallBatchResult
import io.github.dorumrr.de1984.domain.model.CaptivePortalPreset
import io.github.dorumrr.de1984.databinding.PermissionTierSectionBinding
import io.github.dorumrr.de1984.presentation.viewmodel.ImportUninstalledPreview
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.ui.common.StandardDialog
import io.github.dorumrr.de1984.ui.logs.LogsActivity
import io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.ShellRunner
import io.github.dorumrr.de1984.utils.Constants
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            app.dependencies.provideSmartPolicySwitchUseCase(),
            app.dependencies.packageRepository
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionViewModel.markNotificationPermissionRequested()
        permissionViewModel.refreshPermissions()
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        permissionViewModel.refreshPermissions()
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val prepareIntent = android.net.VpnService.prepare(requireContext())
        if (prepareIntent == null) {
            permissionViewModel.refreshPermissions()
            
            if (viewModel.uiState.value.vpnPermissionRequired) {
                viewModel.clearVpnPermissionRequired()
                viewModel.onVpnPermissionGranted()
            }
        } else {
            permissionViewModel.refreshPermissions()
            
            if (viewModel.uiState.value.vpnPermissionRequired) {
                viewModel.clearVpnPermissionRequired()
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(io.github.dorumrr.de1984.R.string.vpn_permission_denied),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                val allBackends = getAllBackends()
                val autoIndex = allBackends.indexOfFirst { it.mode == io.github.dorumrr.de1984.domain.firewall.FirewallMode.AUTO }
                if (autoIndex >= 0) {
                    binding.backendSelectionDropdown.setText(allBackends[autoIndex].displayName, false)
                }
                viewModel.setFirewallMode(io.github.dorumrr.de1984.domain.firewall.FirewallMode.AUTO, forceEvenIfOtherVpnActive = true)
            }
        }
    }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.backupRules(it) }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { showRestorePreview(it) }
    }

    private val exportUninstalledLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { viewModel.exportUninstalledApps(it) }
    }

    private val importUninstalledLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importUninstalledApps(it) }
    }

    private var progressDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * Dismiss anything still on screen before this view goes away.
     *
     * These dialogs are plain Dialog/BottomSheetDialog held in fields, not DialogFragments, so
     * nothing dismisses them for us. Without this the window leaks - `android.view.WindowLeaked` -
     * and worse, a long batch operation carries on behind a progress box the user can no longer
     * see, because the recreated fragment's field is null and its own dismiss is a no-op.
     *
     * Reachable on every activity rebuild: a language change (this app has a language switcher) or
     * a dark-mode change. Rotation no longer rebuilds, but those two still do.
     *
     * Wrapped: dismissing a dialog whose window has already gone throws, and there is nothing to do
     * about it here beyond not crashing on the way out.
     */
    override fun onDestroyView() {
        runCatching { progressDialog?.dismiss() }
        progressDialog = null
        runCatching { rebootingDialog?.dismiss() }
        rebootingDialog = null
        super.onDestroyView()
    }


    private var lastRootTestTime = 0L

    private var isDynamicColorsSwitchListenerAttached = false

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentSettingsBinding.inflate(inflater, container, false)

    override fun scrollToTop() {
        _binding?.root?.scrollTo(0, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLogger.d(TAG, "onViewCreated: Settings fragment view created")

        setupViews()
        observeUiState()
        observePermissionState()
    }

    override fun onResume() {
        super.onResume()
        AppLogger.d(TAG, "onResume: Settings fragment resumed")
    }

    override fun onPause() {
        super.onPause()
        AppLogger.d(TAG, "onPause: Settings fragment paused")
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        AppLogger.d(TAG, "onHiddenChanged: hidden=$hidden")

        if (!hidden) {
            AppLogger.d(TAG, "onHiddenChanged: Fragment became visible, updating UI")
            updateUI(viewModel.uiState.value)
            permissionViewModel.refreshPermissions()
        }
    }

    private fun setupViews() {
        binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.donate_button)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.paypal.me/duoveselia"))
            startActivity(intent)
        }

        binding.contributeLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dorumrr/de1984"))
            startActivity(intent)
        }

        binding.firewallPolicySwitch.setOnCheckedChangeListener { _, isChecked ->
            val policy = if (isChecked) {
                Constants.Settings.POLICY_BLOCK_ALL
            } else {
                Constants.Settings.POLICY_ALLOW_ALL
            }
            viewModel.setDefaultFirewallPolicy(policy)
            updateFirewallPolicyDescription(isChecked)
        }

        setupBackendSelectionDropdown()

        setupLanguageSelectionDropdown()

        binding.appLogsItem.setOnClickListener {
            val intent = Intent(requireContext(), io.github.dorumrr.de1984.ui.logs.LogsActivity::class.java)
            startActivity(intent)
        }

        binding.showAppIconsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowAppIcons(isChecked)
        }

        // Use dynamic colors switch
        // Note: Don't attach listener here - it will be attached in updateUI() after the initial state is set
        // This prevents the listener from firing when the switch state is restored from instance state

        // Allow critical package uninstall switch - listener is set in updateUI() to avoid triggering during initialization

        binding.showFirewallStartPromptSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowFirewallStartPrompt(isChecked)
        }

        binding.newAppNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNewAppNotifications(isChecked)
        }

        // Boot protection switch - listener is set in updateUI() to handle confirmation dialog
        // (We need to show a warning dialog before enabling/disabling)

        binding.backupRulesButton.setOnClickListener {
            val filename = "de1984-firewall-backup-${viewModel.getCurrentDate()}.json"
            backupLauncher.launch(filename)
        }

        binding.restoreRulesButton.setOnClickListener {
            restoreLauncher.launch(arrayOf("application/json"))
        }

        binding.exportUninstalledAppsButton.setOnClickListener {
            val filename = "de1984-uninstalled-apps-${viewModel.getCurrentDate()}.txt"
            exportUninstalledLauncher.launch(filename)
        }

        binding.importUninstalledAppsButton.setOnClickListener {
            importUninstalledLauncher.launch(arrayOf("text/plain", "text/*"))
        }

        setupCaptivePortalSection()

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
                ds.isUnderlineText = false
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
                    if (!isHidden) {
                        AppLogger.d(TAG, "observeUiState: Fragment is visible, updating UI (requiresRestart=${state.requiresRestart})")
                        updateUI(state)

                        if (state.requiresRestart) {
                            AppLogger.d(TAG, "observeUiState: Showing restart dialog")
                            showRestartDialog()
                            viewModel.clearRestartPrompt()
                        }

                        if (state.showVpnConflictWarning && state.pendingModeChange != null) {
                            showVpnConflictWarning()
                        }

                        if (state.vpnPermissionRequired) {
                            val prepareIntent = viewModel.checkVpnPermissionNeeded()
                            if (prepareIntent != null) {
                                vpnPermissionLauncher.launch(prepareIntent)
                            } else {
                                viewModel.clearVpnPermissionRequired()
                                viewModel.onVpnPermissionGranted()
                            }
                        }
                    } else {
                        AppLogger.d(TAG, "observeUiState: Fragment is hidden, skipping UI update")
                    }
                }
            }
        }
    }

    private fun showVpnConflictWarning() {
        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(io.github.dorumrr.de1984.R.string.dialog_vpn_conflict_title),
            message = getString(io.github.dorumrr.de1984.R.string.dialog_vpn_conflict_message),
            confirmButtonText = getString(io.github.dorumrr.de1984.R.string.dialog_vpn_conflict_confirm),
            cancelButtonText = getString(io.github.dorumrr.de1984.R.string.dialog_cancel),
            onConfirm = {
                viewModel.confirmPendingModeChange()
            },
            onCancel = {
                viewModel.cancelPendingModeChange()
                val currentMode = viewModel.uiState.value.firewallMode
                val allBackends = getAllBackends()
                val currentIndex = allBackends.indexOfFirst { it.mode == currentMode }
                if (currentIndex >= 0) {
                    binding.backendSelectionDropdown.setText(allBackends[currentIndex].displayName, false)
                }
            }
        )
    }

    private fun updateUI(state: io.github.dorumrr.de1984.presentation.viewmodel.SettingsUiState) {
        binding.appVersion.text = getString(io.github.dorumrr.de1984.R.string.settings_app_version, state.appVersion)

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

        AppLogger.d(TAG, "updateUI: Setting useDynamicColorsSwitch.isChecked = ${state.useDynamicColors}")
        binding.useDynamicColorsSwitch.isChecked = state.useDynamicColors

        // Attach listener (only if not already attached, or re-attach after removing)
        binding.useDynamicColorsSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppLogger.d(TAG, "updateUI: Dynamic colors switch toggled to $isChecked by user")
            viewModel.setUseDynamicColors(isChecked, showRestartDialog = true)
        }
        isDynamicColorsSwitchListenerAttached = true

        binding.allowCriticalUninstallSwitch.setOnCheckedChangeListener(null)
        AppLogger.d(TAG, "updateUI: Setting allowCriticalUninstallSwitch.isChecked = ${state.allowCriticalPackageUninstall}")
        binding.allowCriticalUninstallSwitch.isChecked = state.allowCriticalPackageUninstall
        binding.allowCriticalUninstallSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppLogger.d(TAG, "allowCriticalUninstallSwitch listener (from updateUI) triggered: isChecked=$isChecked")
            if (isChecked) {
                AppLogger.d(TAG, "Showing critical uninstall warning dialog (from updateUI)")
                showCriticalUninstallWarning {
                    viewModel.setAllowCriticalPackageUninstall(true)
                }
            } else {
                AppLogger.d(TAG, "Disabling critical package uninstall (from updateUI)")
                viewModel.setAllowCriticalPackageUninstall(false)
            }
        }

        binding.allowCriticalFirewallSwitch.setOnCheckedChangeListener(null)
        AppLogger.d(TAG, "updateUI: Setting allowCriticalFirewallSwitch.isChecked = ${state.allowCriticalPackageFirewall}")
        binding.allowCriticalFirewallSwitch.isChecked = state.allowCriticalPackageFirewall
        binding.allowCriticalFirewallSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppLogger.d(TAG, "allowCriticalFirewallSwitch listener (from updateUI) triggered: isChecked=$isChecked")
            if (isChecked) {
                AppLogger.d(TAG, "Showing critical firewall warning dialog (from updateUI)")
                showCriticalFirewallWarning {
                    viewModel.setAllowCriticalPackageFirewall(true)
                }
            } else {
                AppLogger.d(TAG, "Disabling critical package firewall (from updateUI)")
                viewModel.setAllowCriticalPackageFirewall(false)
            }
        }

        binding.showFirewallStartPromptSwitch.setOnCheckedChangeListener(null)
        binding.showFirewallStartPromptSwitch.isChecked = state.showFirewallStartPrompt
        binding.showFirewallStartPromptSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowFirewallStartPrompt(isChecked)
        }

        binding.confirmRuleChangesSwitch.setOnCheckedChangeListener(null)
        binding.confirmRuleChangesSwitch.isChecked = state.confirmRuleChanges
        binding.confirmRuleChangesSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setConfirmRuleChanges(isChecked)
        }


        binding.confirmFirewallStopSwitch.setOnCheckedChangeListener(null)
        binding.confirmFirewallStopSwitch.isChecked = state.confirmFirewallStop
        binding.confirmFirewallStopSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setConfirmFirewallStop(isChecked)
        }
        binding.newAppNotificationsSwitch.setOnCheckedChangeListener(null)
        binding.newAppNotificationsSwitch.isChecked = state.newAppNotifications
        binding.newAppNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNewAppNotifications(isChecked)
        }

        binding.bootProtectionSwitch.setOnCheckedChangeListener(null)

        if (!state.bootProtectionAvailable) {
            binding.bootProtectionSwitch.isEnabled = false

            if (state.bootProtection) {
                // Lockout scenario 5. Boot protection was enabled and privileged access has since
                // been lost. The script is almost certainly still installed, and we cannot even
                // confirm it: /data/adb is root-only, so without root the app can neither read nor
                // remove it. Showing the switch as OFF here would be a lie.
                //
                // The button is worth offering even though the switch cannot work, because "lost
                // root" is usually "root not granted right now" - Magisk not awake, or one Deny -
                // rather than root genuinely gone. It asks again before giving up, and says so
                // plainly when the answer is still no. The old help text told the user to run `su`,
                // which is exactly what they had lost.
                binding.bootProtectionSwitch.isChecked = true
                binding.bootProtectionDescription.text =
                    getString(io.github.dorumrr.de1984.R.string.settings_boot_protection_stuck)
                binding.bootProtectionRetryRemove.visibility = View.VISIBLE
                binding.bootProtectionRetryRemove.isEnabled = !state.bootProtectionRemovalInProgress
            } else {
                binding.bootProtectionSwitch.isChecked = false
                binding.bootProtectionDescription.text =
                    getString(io.github.dorumrr.de1984.R.string.settings_boot_protection_unavailable)
                binding.bootProtectionRetryRemove.visibility = View.GONE
            }
        } else {
            binding.bootProtectionSwitch.isChecked = state.bootProtection
            binding.bootProtectionSwitch.isEnabled = true
            binding.bootProtectionDescription.text = getString(io.github.dorumrr.de1984.R.string.settings_boot_protection_description)
            // The switch works here, so the recovery button would be a second control for the same
            // job. Scenario 5 is the only state it belongs in.
            binding.bootProtectionRetryRemove.visibility = View.GONE
        }

        // Reuses the normal disable warning, which already says the device restarts immediately.
        // Writing a second near-identical dialog is how two warnings drift apart.
        binding.bootProtectionRetryRemove.setOnClickListener {
            AppLogger.d(TAG, "Boot protection retry-remove tapped - showing the disable warning")
            showBootProtectionWarning(false) {
                viewModel.retryRemoveBootProtection()
            }
        }

        renderRebootingScreen(state.isRebooting)

        binding.bootProtectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppLogger.d(TAG, "bootProtectionSwitch listener triggered: isChecked=$isChecked")
            if (isChecked) {
                AppLogger.d(TAG, "Showing boot protection enable warning dialog")
                showBootProtectionWarning(true) {
                    viewModel.setBootProtection(true)
                }
            } else {
                AppLogger.d(TAG, "Showing boot protection disable warning dialog")
                showBootProtectionWarning(false) {
                    viewModel.setBootProtection(false)
                }
            }
        }

        setupBackendSelectionDropdown()
        updateBackendStatus()

        setupLanguageSelectionDropdown()

        state.message?.let { message ->
            viewModel.clearMessage()
            StandardDialog.showInfo(
                context = requireContext(),
                title = getString(state.messageTitleRes ?: R.string.dialog_success),
                message = message
            )
        }

        state.error?.let { error ->
            viewModel.clearError()
            StandardDialog.showError(
                context = requireContext(),
                message = error
            )
        }

        state.importUninstalledPreview?.let { preview ->
            if (preview.packagesNotFound.isEmpty() && preview.packagesProtected.isEmpty()) {
                showImportPreviewDialog(preview)
            } else {
                showImportWarningDialog(preview)
            }
        }

        state.batchUninstallResult?.let { result ->
            progressDialog?.dismiss()
            showBatchUninstallResults(result)
            viewModel.clearBatchUninstallResult()
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
        val allBackends = getAllBackends()

        val adapter = BackendAdapter(requireContext(), allBackends)

        binding.backendSelectionDropdown.setAdapter(adapter)

        val currentMode = viewModel.uiState.value.firewallMode
        val currentIndex = allBackends.indexOfFirst { it.mode == currentMode }
        if (currentIndex >= 0) {
            binding.backendSelectionDropdown.setText(allBackends[currentIndex].displayName, false)
        }

        binding.backendSelectionDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedBackend = allBackends[position]
            if (selectedBackend.isAvailable) {
                AppLogger.i(TAG, "👤 User selected firewall mode: ${selectedBackend.mode} (${selectedBackend.displayName})")
                viewModel.setFirewallMode(selectedBackend.mode)
            } else {
                AppLogger.d(TAG, "👤 User tried to select unavailable backend: ${selectedBackend.displayName}")
                binding.backendSelectionDropdown.setText(allBackends[currentIndex].displayName, false)

                StandardDialog.showInfo(
                    context = requireContext(),
                    title = getString(R.string.backend_not_available_title, selectedBackend.displayName),
                    message = selectedBackend.requirementText ?: getString(R.string.backend_not_available_message)
                )
            }
        }

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
            LanguageOption(Constants.Settings.LANGUAGE_ITALIAN, getString(io.github.dorumrr.de1984.R.string.language_italian)),
            LanguageOption(Constants.Settings.LANGUAGE_FRENCH, getString(io.github.dorumrr.de1984.R.string.language_french)),
            LanguageOption(Constants.Settings.LANGUAGE_RUSSIAN, getString(io.github.dorumrr.de1984.R.string.language_russian))
        ).let { list ->
            listOf(list.first()) + list.drop(1).sortedBy { it.displayName }
        }

        val adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            languages.map { it.displayName }
        )

        binding.languageSelectionDropdown.setAdapter(adapter)

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

            viewModel.setAppLanguage(selectedLanguage.code)

            binding.languageSelectionDropdown.setText(selectedLanguage.displayName, false)

            binding.languageSelectionDropdown.dismissDropDown()
            binding.languageSelectionDropdown.clearFocus()

            val imm = requireContext().getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.languageSelectionDropdown.windowToken, 0)

            // Delay the locale change to allow the dropdown to fully dismiss
            // setApplicationLocales() triggers immediate activity recreation, so we need
            // to give the UI time to complete the dropdown dismissal animation
            binding.root.postDelayed({
                val localeList = when (selectedLanguage.code) {
                    Constants.Settings.LANGUAGE_SYSTEM_DEFAULT -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    Constants.Settings.LANGUAGE_ENGLISH -> androidx.core.os.LocaleListCompat.forLanguageTags("en")
                    Constants.Settings.LANGUAGE_ROMANIAN -> androidx.core.os.LocaleListCompat.forLanguageTags("ro")
                    Constants.Settings.LANGUAGE_PORTUGUESE -> androidx.core.os.LocaleListCompat.forLanguageTags("pt")
                    Constants.Settings.LANGUAGE_CHINESE -> androidx.core.os.LocaleListCompat.forLanguageTags("zh")
                    Constants.Settings.LANGUAGE_ITALIAN -> androidx.core.os.LocaleListCompat.forLanguageTags("it")
                    Constants.Settings.LANGUAGE_FRENCH -> androidx.core.os.LocaleListCompat.forLanguageTags("fr")
                    Constants.Settings.LANGUAGE_RUSSIAN -> androidx.core.os.LocaleListCompat.forLanguageTags("ru")
                    else -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                }
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)
            }, Constants.UI.DROPDOWN_DISMISSAL_DELAY_MS)
        }
    }

    private fun getAllBackends(): List<BackendOption> {
        val backends = mutableListOf<BackendOption>()

        val rootStatus = viewModel.rootStatus.value
        val shizukuStatus = viewModel.shizukuStatus.value
        val hasRoot = rootStatus == io.github.dorumrr.de1984.data.common.RootStatus.ROOTED_WITH_PERMISSION
        val hasShizukuRoot = shizukuStatus == io.github.dorumrr.de1984.data.common.ShizukuStatus.RUNNING_WITH_PERMISSION &&
                viewModel.isShizukuRootMode()
        val hasShizuku = shizukuStatus == io.github.dorumrr.de1984.data.common.ShizukuStatus.RUNNING_WITH_PERMISSION
        val isAndroid13Plus = android.os.Build.VERSION.SDK_INT >= 33

        backends.add(BackendOption(
            mode = io.github.dorumrr.de1984.domain.firewall.FirewallMode.AUTO,
            displayName = getString(R.string.backend_auto_name),
            description = getString(R.string.backend_auto_description),
            isAvailable = true
        ))

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

        // Everything above is a guess from privileges alone, and a privilege is not a capability.
        // ConnectivityManager needs Shizuku AND Android 13 AND a `cmd connectivity` that exposes
        // set-chain3-enabled; plenty of ROMs have the first two and not the third. The picker
        // offered it anyway, selecting it failed, and the firewall went down. So the backends get
        // the last word: anything the probe could not start is shown as unusable, with a reason.
        //
        // A null probe result means it has not answered yet - keep the guess rather than emptying
        // the list on first draw.
        val usable = viewModel.usableModes.value
        if (usable == null) return backends

        val failedToStart = viewModel.startFailedModes.value

        // Never grey out what is RUNNING. A probe is one `su` call and it can flicker; when it
        // does, the row would read "iptables (Not supported on this device)" directly above a
        // status line reading "Active: iptables", and the user could not re-select their own
        // running backend. Whatever is live is, by demonstration, supported.
        //
        // Only the active backend earns this, not the merely selected one. After a failed start
        // the failed backend IS the selected one, and exempting it would undo the whole point of
        // remembering the failure - the user could pick it again and fail again.
        val activeMode = when (viewModel.activeBackendType.value) {
            io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.IPTABLES ->
                io.github.dorumrr.de1984.domain.firewall.FirewallMode.IPTABLES
            io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.CONNECTIVITY_MANAGER ->
                io.github.dorumrr.de1984.domain.firewall.FirewallMode.CONNECTIVITY_MANAGER
            io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.NETWORK_POLICY_MANAGER ->
                io.github.dorumrr.de1984.domain.firewall.FirewallMode.NETWORK_POLICY_MANAGER
            io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.VPN ->
                io.github.dorumrr.de1984.domain.firewall.FirewallMode.VPN
            null -> null
        }
        val exempt = setOfNotNull(activeMode)

        return backends.map { option ->
            when {
                option.mode in exempt -> option
                option.isAvailable && option.mode !in usable -> option.copy(
                    isAvailable = false,
                    requirementText = getString(R.string.backend_not_supported_on_device)
                )
                // Passed its probe but would not actually start. The probe cannot see this, so the
                // failure is remembered instead - otherwise the user picks it, drops to AUTO, and
                // picks it again forever.
                option.isAvailable && option.mode in failedToStart -> option.copy(
                    isAvailable = false,
                    requirementText = getString(R.string.backend_not_supported_on_device)
                )
                else -> option
            }
        }
    }

    private fun updateBackendStatus() {
        val activeBackend = viewModel.activeBackendType.value

        val statusText = if (activeBackend != null) {
            // displayName(), not .name - .name printed the raw enum, e.g. "NETWORK_POLICY_MANAGER",
            // right under a picker that calls the same backend "NetworkPolicyManager (Legacy)".
            getString(
                io.github.dorumrr.de1984.R.string.settings_backend_active,
                activeBackend.displayName(requireContext())
            )
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

                        updatePermissionTiers(permissionViewModel.uiState.value)

                        if (rootStatus == RootStatus.ROOTED_WITH_PERMISSION) {
                            permissionViewModel.refreshPermissions()
                        }

                        setupBackendSelectionDropdown()
                    }
                }
                launch {
                    viewModel.shizukuStatus.collect { shizukuStatus ->
                        updateShizukuStatus(shizukuStatus)

                        updatePermissionTiers(permissionViewModel.uiState.value)

                        if (shizukuStatus == ShizukuStatus.RUNNING_WITH_PERMISSION) {
                            permissionViewModel.refreshPermissions()
                        }

                        setupBackendSelectionDropdown()
                    }
                }
                launch {
                    viewModel.activeBackendType.collect { _ ->
                        updateBackendStatus()
                    }
                }
                launch {
                    // The real availability probe answers after the first draw, so redraw the
                    // picker when it lands - otherwise a backend this device cannot run stays
                    // selectable until the next privilege change.
                    viewModel.usableModes.collect { _ ->
                        setupBackendSelectionDropdown()
                    }
                }
                launch {
                    viewModel.startFailedModes.collect { _ ->
                        setupBackendSelectionDropdown()
                    }
                }
            }
        }
    }

    private fun updatePermissionTiers(state: io.github.dorumrr.de1984.ui.permissions.PermissionSetupUiState) {
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

        val shizukuStatus = viewModel.shizukuStatus.value
        val rootStatus = viewModel.rootStatus.value

        val canActuallyGrantPermission = when {
            shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION -> true

            rootStatus == RootStatus.ROOTED_NO_PERMISSION -> true

            else -> false
        }

        val showRootButton = canActuallyGrantPermission && !state.hasAdvancedPermissions

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

        val vpnStatus = when {
            state.isUsingPrivilegedBackend -> getString(io.github.dorumrr.de1984.R.string.permission_status_not_required)
            state.hasVpnPermission -> getString(io.github.dorumrr.de1984.R.string.permission_status_completed)
            else -> getString(io.github.dorumrr.de1984.R.string.permission_status_required)
        }
        val vpnDescription = if (state.isUsingPrivilegedBackend) {
            getString(io.github.dorumrr.de1984.R.string.permission_tier_vpn_desc_privileged)
        } else {
            getString(io.github.dorumrr.de1984.R.string.permission_tier_vpn_desc)
        }
        setupPermissionTier(
            binding.permissionTierVpn,
            title = getString(io.github.dorumrr.de1984.R.string.permission_tier_vpn_title),
            description = vpnDescription,
            status = vpnStatus,
            isComplete = state.hasVpnPermission || state.isUsingPrivilegedBackend,
            permissions = state.vpnPermissionInfo,
            setupButtonText = getString(io.github.dorumrr.de1984.R.string.permission_button_grant_vpn),
            onSetupClick = if (!state.hasVpnPermission && !state.isUsingPrivilegedBackend) {
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
        tierBinding.tierTitle.text = title
        tierBinding.tierDescription.text = description

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

        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val iconColor = typedValue.data

        val shizukuStatus = viewModel.shizukuStatus.value
        val shizukuAvailable = shizukuStatus == ShizukuStatus.RUNNING_WITH_PERMISSION ||
                               shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION

        when (rootStatus) {
            RootStatus.ROOTED_WITH_PERMISSION -> {
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            RootStatus.ROOTED_NO_PERMISSION -> {
                if (shizukuAvailable) {
                    return
                }

                if (viewModel.hasRequestedRootPermission()) {
                    tierBinding.rootStatusContainer.visibility = View.VISIBLE
                    tierBinding.rootStatusIcon.setColorFilter(iconColor)
                    tierBinding.rootStatusTitle.text = getString(R.string.root_status_denied)
                    tierBinding.rootStatusDescription.text = getString(R.string.root_desc_denied)
                    tierBinding.rootStatusInstructions.visibility = View.VISIBLE
                    tierBinding.rootStatusInstructions.text = getString(R.string.root_grant_instructions_title) + "\n" + getString(R.string.root_grant_instructions_body)
                    tierBinding.rootingToolsContainer.visibility = View.GONE
                    tierBinding.setupButtonContainer.visibility = View.GONE
                } else {
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
                if (shizukuAvailable) {
                    return
                }

                tierBinding.rootStatusContainer.visibility = View.VISIBLE
                tierBinding.rootStatusIcon.setColorFilter(iconColor)
                tierBinding.rootStatusTitle.text = getString(R.string.root_status_not_available)
                tierBinding.rootStatusDescription.text = getString(R.string.root_desc_not_available)
                tierBinding.rootStatusInstructions.visibility = View.GONE

                tierBinding.rootingToolsContainer.visibility = View.VISIBLE
                tierBinding.rootingToolsTitle.text = getString(R.string.root_rooting_tools_title).replace("<b>", "").replace("</b>", "")
                tierBinding.rootingToolsBody.text = getString(R.string.root_rooting_tools_body)

                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            RootStatus.CHECKING -> {
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
        }
    }

    private fun updateShizukuStatus(shizukuStatus: ShizukuStatus) {
        val tierBinding = binding.permissionTierAdvanced

        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val iconColor = typedValue.data

        val rootStatus = viewModel.rootStatus.value
        if (rootStatus == RootStatus.ROOTED_WITH_PERMISSION) {
            return
        }

        when (shizukuStatus) {
            ShizukuStatus.RUNNING_WITH_PERMISSION -> {
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            ShizukuStatus.RUNNING_NO_PERMISSION -> {
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
        AppLogger.d(TAG, "handleVpnPermissionRequest() called")
        try {
            val prepareIntent = VpnService.prepare(requireContext())
            AppLogger.d(TAG, "VpnService.prepare() returned: $prepareIntent")
            if (prepareIntent != null) {
                AppLogger.d(TAG, "Launching VPN permission request dialog")
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                AppLogger.d(TAG, "VPN permission already granted, refreshing permissions")
                permissionViewModel.refreshPermissions()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to request VPN permission", e)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_vpn_permission_error_title))
                .setMessage(getString(R.string.dialog_vpn_permission_error_message, e.message))
                .setPositiveButton(getString(R.string.dialog_ok), null)
                .show()
        }
    }

    private fun handleRootAccessRequest() {
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

                if (resultMessage == "NO_PRIVILEGED_ACCESS") {
                    dialog.dismiss()
                    StandardDialog.showNoAccessDialog(requireContext())
                } else if (resultMessage == "ROOT_ACCESS_DENIED") {
                    dialog.dismiss()
                    StandardDialog.showRootDeniedDialog(requireContext()) {
                        permissionViewModel.refreshPermissions()
                        viewModel.requestRootPermission()
                    }
                } else if (dialog.isShowing) {
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
                        permissionViewModel.refreshPermissions()
                        viewModel.requestRootPermission()
                    }
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

    private suspend fun testRootAccess(): String {
        // The ceiling here could not fire before: the old guard sat round process.waitFor(), which
        // ignores cancellation, so the "Root Test Timeout" branch below was unreachable - including
        // for the one case its own text names, a user who never answers the grant dialog.
        //
        // It is also deliberately no longer the old 5 seconds. Destroying the su process while the
        // root manager's prompt is still on screen would land the user's "Grant" tap on a dead
        // process. 30 seconds is what De1984Application already allows libsu for this same wait,
        // for this same reason, so the two now agree.
        //
        // No withContext: nothing here blocks the caller any more, ShellRunner owns the IO.
        val result = ShellRunner.run("root test: su -c id") {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        }

        return when {
            result.timedOut ->
                "⏱️ Root Test Timeout\n\nThe root permission request timed out. This may happen if:\n• You didn't respond to the permission dialog\n• Your root manager is not responding\n\nPlease try again."

            result.exitCode == 0 ->
                "✅ Root Access Granted!\n\nYour device is rooted and De1984 has been granted superuser permission.\n\nOutput: ${result.stdout}"

            // -1 is never a real exit status - it means su could not be started at all. That is
            // "this device has no root", which is not the same answer as "root said no".
            result.exitCode == -1 -> "NO_PRIVILEGED_ACCESS"

            else -> "ROOT_ACCESS_DENIED"
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    private fun showRestorePreview(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val loadingDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.dialog_loading_backup_title))
                    .setMessage(getString(R.string.dialog_loading_backup_message))
                    .setCancelable(false)
                    .create()
                loadingDialog.show()

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

            textView.isEnabled = backend.isAvailable
            textView.alpha = if (backend.isAvailable) 1.0f else 0.5f

            return view
        }

        /**
         * Deliberately always true, while the row still LOOKS disabled (greyed, half alpha).
         *
         * Returning false made AbsListView swallow the tap before onItemClick ran, which quietly
         * killed the one thing that explains the greying: the click handler's "not available"
         * branch, with the reason dialog, could never fire. Tapping a greyed row did nothing at
         * all, and the reason survived only as a parenthetical the dropdown truncates.
         *
         * The handler already refuses to select an unavailable backend and restores the previous
         * text, so letting the tap through costs nothing and buys the user an answer.
         */
        override fun isEnabled(position: Int): Boolean = true
    }

    private fun showCriticalUninstallWarning(onConfirm: () -> Unit) {
        AppLogger.d(TAG, "showCriticalUninstallWarning: Displaying warning dialog")
        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.dialog_critical_uninstall_title),
            message = getString(R.string.dialog_critical_uninstall_message),
            confirmButtonText = getString(R.string.dialog_critical_uninstall_enable),
            onConfirm = {
                AppLogger.d(TAG, "showCriticalUninstallWarning: User confirmed")
                onConfirm()
            },
            cancelButtonText = getString(R.string.dialog_cancel),
            onCancel = {
                AppLogger.d(TAG, "showCriticalUninstallWarning: User cancelled, reverting switch")
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
        AppLogger.d(TAG, "showCriticalFirewallWarning: Displaying warning dialog")
        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.dialog_critical_firewall_title),
            message = getString(R.string.dialog_critical_firewall_message),
            confirmButtonText = getString(R.string.dialog_critical_firewall_enable),
            onConfirm = {
                AppLogger.d(TAG, "showCriticalFirewallWarning: User confirmed")
                onConfirm()
            },
            cancelButtonText = getString(R.string.dialog_cancel),
            onCancel = {
                AppLogger.d(TAG, "showCriticalFirewallWarning: User cancelled, reverting switch")
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
        AppLogger.d(TAG, "showBootProtectionWarning: enable=$enable, displaying warning dialog")

        val title = if (enable) {
            getString(R.string.boot_protection_enable_warning_title)
        } else {
            getString(R.string.boot_protection_disable_warning_title)
        }

        val message = if (enable) {
            getString(R.string.boot_protection_enable_warning_message, Constants.BootProtection.BOOT_SCRIPT_PATH)
        } else {
            getString(R.string.boot_protection_disable_warning_message)
        }

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = title,
            message = message,
            confirmButtonText = getString(R.string.dialog_continue),
            onConfirm = {
                AppLogger.d(TAG, "showBootProtectionWarning: User confirmed")
                onConfirm()
            },
            cancelButtonText = getString(R.string.dialog_cancel),
            onCancel = {
                AppLogger.d(TAG, "showBootProtectionWarning: User cancelled, reverting switch")
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
                AppLogger.d(TAG, "showRestartDialog: User confirmed restart")
                restartApp()
            },
            cancelButtonText = getString(R.string.dialog_cancel),
            onCancel = {
                AppLogger.d(TAG, "showRestartDialog: User cancelled restart")
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
                android.os.Process.killProcess(android.os.Process.myPid())
            } else {
                AppLogger.e(TAG, "Failed to get launch intent for restart")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to restart app: ${e.message}")
        }
    }


    private fun setupCaptivePortalSection() {
        viewModel.loadCaptivePortalSettings()

        val modeDropdown = binding.root.findViewById<AutoCompleteTextView>(R.id.captivePortalModeDropdown)
        val modeOptions = CaptivePortalMode.values().map { it.getDisplayName(requireContext()) }
        val modeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, modeOptions)
        modeDropdown?.setAdapter(modeAdapter)
        modeDropdown?.setOnItemClickListener { _, _, position, _ ->
            val selectedMode = CaptivePortalMode.values()[position]
            viewModel.setCaptivePortalDetectionMode(selectedMode)
        }

        val presetDropdown = binding.root.findViewById<AutoCompleteTextView>(R.id.captivePortalPresetDropdown)
        val presetOptions = CaptivePortalPreset.values().map { it.getDisplayName(requireContext()) }
        val presetAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, presetOptions)
        presetDropdown?.setAdapter(presetAdapter)
        presetDropdown?.setOnItemClickListener { _, _, position, _ ->
            val selectedPreset = CaptivePortalPreset.values()[position]

            if (selectedPreset == CaptivePortalPreset.CUSTOM) {
                binding.root.findViewById<LinearLayout>(R.id.captivePortalCustomUrlsSection)?.visibility = View.VISIBLE
            } else {
                binding.root.findViewById<LinearLayout>(R.id.captivePortalCustomUrlsSection)?.visibility = View.GONE
                viewModel.applyCaptivePortalPreset(selectedPreset)
            }
        }

        val customHttpUrl = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.captivePortalCustomHttpUrl)
        val customHttpsUrl = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.captivePortalCustomHttpsUrl)

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

        loadingIndicator?.visibility = if (state.captivePortalLoading) View.VISIBLE else View.GONE

        if (state.captivePortalError != null) {
            errorText?.text = state.captivePortalError
            errorText?.visibility = View.VISIBLE
        } else {
            errorText?.visibility = View.GONE
        }

        state.captivePortalSettings?.let { settings ->
            currentMode?.text = getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_detection_mode, settings.mode.getDisplayName(requireContext()))
            currentHttpUrl?.text = getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_http_url, settings.httpUrl ?: getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_not_set))
            currentHttpsUrl?.text = getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_https_url, settings.httpsUrl ?: getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_not_set))
            currentPreset?.text = getString(io.github.dorumrr.de1984.R.string.settings_captive_portal_preset, settings.getMatchingPreset().getDisplayName(requireContext()))

            modeDropdown?.setText(settings.mode.getDisplayName(requireContext()), false)
            presetDropdown?.setText(settings.getMatchingPreset().getDisplayName(requireContext()), false)
        }

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

        if (!hasPrivileges && state.captivePortalSettings != null) {
            errorText?.text = getString(R.string.settings_captive_portal_no_privileges)
            errorText?.visibility = View.VISIBLE
        }
    }


    private fun showImportPreviewDialog(preview: ImportUninstalledPreview) {
        val packageList = preview.packagesToUninstall.take(10).joinToString("\n") { pkg -> "• $pkg" }
        val moreText = if (preview.packagesToUninstall.size > 10) {
            "\n${getString(R.string.batch_uninstall_results_and_more, preview.packagesToUninstall.size - 10)}"
        } else {
            ""
        }

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.dialog_import_preview_title),
            message = getString(
                R.string.dialog_import_preview_message,
                preview.packagesToUninstall.size,
                packageList + moreText
            ),
            confirmButtonText = getString(R.string.dialog_import_confirm),
            cancelButtonText = getString(R.string.dialog_cancel),
            onConfirm = {
                performBatchUninstall(preview.packagesToUninstall.size)
                viewModel.confirmImportUninstall()
            },
            onCancel = {
                viewModel.clearImportPreview()
            }
        )
    }

    private fun showImportWarningDialog(preview: ImportUninstalledPreview) {
        val foundList = preview.packagesToUninstall.take(10).joinToString("\n") { pkg -> "• $pkg" }
        val foundMoreText = if (preview.packagesToUninstall.size > 10) {
            "\n${getString(R.string.batch_uninstall_results_and_more, preview.packagesToUninstall.size - 10)}"
        } else {
            ""
        }

        val notFoundList = preview.packagesNotFound.take(10).joinToString("\n") { pkg -> "• $pkg" }
        val notFoundMoreText = if (preview.packagesNotFound.size > 10) {
            "\n${getString(R.string.batch_uninstall_results_and_more, preview.packagesNotFound.size - 10)}"
        } else {
            ""
        }

        // Protected packages were refused, not missed. Saying so is the point of dropping them.
        val protectedText = if (preview.packagesProtected.isEmpty()) {
            ""
        } else {
            val list = preview.packagesProtected.take(10).joinToString("\n") { pkg -> "• $pkg" }
            val more = if (preview.packagesProtected.size > 10) {
                "\n${getString(R.string.batch_uninstall_results_and_more, preview.packagesProtected.size - 10)}"
            } else {
                ""
            }
            "\n\n" + getString(R.string.dialog_import_protected_section, list + more)
        }

        StandardDialog.showConfirmation(
            context = requireContext(),
            title = getString(R.string.dialog_import_warning_title),
            message = getString(
                R.string.dialog_import_warning_message,
                preview.packagesToUninstall.size,
                preview.totalPackages,
                foundList + foundMoreText,
                notFoundList + notFoundMoreText
            ) + protectedText,
            confirmButtonText = getString(R.string.dialog_import_confirm),
            cancelButtonText = getString(R.string.dialog_cancel),
            onConfirm = {
                performBatchUninstall(preview.packagesToUninstall.size)
                viewModel.confirmImportUninstall()
            },
            onCancel = {
                viewModel.clearImportPreview()
            }
        )
    }

    /**
     * The "Restarting..." screen shown after a scenario-5 removal succeeds.
     *
     * Not cancellable and with no buttons, because nothing the user does now can stop the restart -
     * offering Cancel would be a control that does nothing. It exists so the screen going black has
     * an explanation; the normal toggles do not need it, because their warning dialog says the
     * device will restart and the user has just read it.
     *
     * Deliberately NOT reusing [progressDialog]. That field belongs to the batch uninstall, and it
     * has to be dismissed here when the restart fails - `svc power reboot` can return non-zero, and
     * a non-cancellable dialog left on screen would trap the user over an error they cannot read.
     * Sharing the field would mean this dismissal killing a batch uninstall's progress dialog
     * instead. Two situations, two fields.
     */
    private var rebootingDialog: androidx.appcompat.app.AlertDialog? = null

    private fun renderRebootingScreen(isRebooting: Boolean) {
        if (!isRebooting) {
            rebootingDialog?.dismiss()
            rebootingDialog = null
            return
        }
        if (rebootingDialog?.isShowing == true) return

        rebootingDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_boot_protection_title))
            .setMessage(getString(R.string.boot_protection_rebooting))
            .setCancelable(false)
            .create()
        rebootingDialog?.show()
    }

    private fun performBatchUninstall(totalPackages: Int) {
        progressDialog?.dismiss()

        progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.batch_uninstall_progress_title))
            .setMessage(getString(R.string.batch_uninstall_progress_message, 0, totalPackages))
            .setCancelable(false)
            .create()

        progressDialog?.show()
    }

    private fun showBatchUninstallResults(result: UninstallBatchResult) {
        val message = buildString {
            if (result.succeeded.isNotEmpty()) {
                append(getString(R.string.batch_uninstall_results_success, result.succeeded.size))
                append("\n")
                result.succeeded.take(10).forEach { packageName ->
                    append("• $packageName\n")
                }
                if (result.succeeded.size > 10) {
                    append(getString(R.string.batch_uninstall_results_and_more, result.succeeded.size - 10) + "\n")
                }
            }

            if (result.failed.isNotEmpty()) {
                if (result.succeeded.isNotEmpty()) {
                    append("\n")
                }
                append(getString(R.string.batch_uninstall_results_failed, result.failed.size))
                append("\n")
                result.failed.take(10).forEach { (packageName, error) ->
                    append("• $packageName: $error\n")
                }
                if (result.failed.size > 10) {
                    append(getString(R.string.batch_uninstall_results_and_more, result.failed.size - 10))
                }
            }
        }

        StandardDialog.show(
            context = requireContext(),
            title = getString(R.string.batch_uninstall_results_title),
            message = message,
            positiveButtonText = getString(R.string.dialog_ok)
        )
    }
}

