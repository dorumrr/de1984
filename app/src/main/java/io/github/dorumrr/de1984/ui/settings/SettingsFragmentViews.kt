package io.github.dorumrr.de1984.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.PermissionInfo
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.databinding.FragmentSettingsBinding
import io.github.dorumrr.de1984.databinding.PermissionTierSectionBinding
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.launch

/**
 * Settings Fragment using XML Views
 * 
 * Features:
 * - App info with version and donate button
 * - Options: Firewall policy, Show icons, New app notifications
 * - Permissions setup (placeholder for now)
 * - Acknowledgements navigation
 */
class SettingsFragmentViews : BaseFragment<FragmentSettingsBinding>() {

    private val viewModel: SettingsViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        SettingsViewModel.Factory(
            requireContext(),
            app.dependencies.permissionManager,
            app.dependencies.rootManager
        )
    }

    private val permissionViewModel: PermissionSetupViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        PermissionSetupViewModel.Factory(app.dependencies.permissionManager)
    }

    // Permission launcher for POST_NOTIFICATIONS
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Notification permission result: $isGranted")
        permissionViewModel.markNotificationPermissionRequested()
        permissionViewModel.refreshPermissions()
    }

    // Activity result launcher for battery optimization settings
    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Refresh permissions when user returns from battery settings
        Log.d(TAG, "Returned from battery optimization settings, refreshing permissions")
        permissionViewModel.refreshPermissions()
    }

    private var lastRootTestTime = 0L

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentSettingsBinding.inflate(inflater, container, false)

    override fun scrollToTop() {
        binding.root.scrollTo(0, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: SettingsFragmentViews initialized")

        setupViews()
        observeUiState()
        observePermissionState()
    }

    private fun setupViews() {
        // Donate button
        binding.donateButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/ossdev"))
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

        // Show app icons switch
        binding.showAppIconsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowAppIcons(isChecked)
        }

        // New app notifications switch
        binding.newAppNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNewAppNotifications(isChecked)
        }

        // Footer (author link) - make only "Doru Moraru" clickable
        setupFooterLink()
    }

    private fun setupFooterLink() {
        val fullText = "Giving Privacy its due, by Doru Moraru"
        val clickableText = "Doru Moraru"
        val startIndex = fullText.indexOf(clickableText)
        val endIndex = startIndex + clickableText.length

        val spannableString = android.text.SpannableString(fullText)

        val clickableSpan = object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dorumrr/de1984"))
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
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: io.github.dorumrr.de1984.presentation.viewmodel.SettingsUiState) {
        Log.d(TAG, "updateUI: version=${state.appVersion}, " +
                "showIcons=${state.showAppIcons}, " +
                "notifications=${state.newAppNotifications}, " +
                "policy=${state.defaultFirewallPolicy}")

        // Update app version
        binding.appVersion.text = "Version ${state.appVersion}"

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

        binding.newAppNotificationsSwitch.setOnCheckedChangeListener(null)
        binding.newAppNotificationsSwitch.isChecked = state.newAppNotifications
        binding.newAppNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNewAppNotifications(isChecked)
        }
    }

    private fun updateFirewallPolicyDescription(isBlockAll: Boolean) {
        binding.firewallPolicyDescription.text = if (isBlockAll) {
            "Block All, Allow Wanted"
        } else {
            "Allow All, Block Unwanted"
        }
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
                    }
                }
            }
        }
    }

    private fun updatePermissionTiers(state: io.github.dorumrr.de1984.ui.permissions.PermissionSetupUiState) {
        Log.d(TAG, "updatePermissionTiers: basic=${state.hasBasicPermissions}, " +
                "battery=${state.hasBatteryOptimizationExemption}, " +
                "advanced=${state.hasAdvancedPermissions}")

        // Setup Basic Tier
        setupPermissionTier(
            binding.permissionTierBasic,
            title = "Basic Functionality",
            description = "View installed packages and basic information",
            status = if (state.hasBasicPermissions) "Completed" else "Setup Required",
            isComplete = state.hasBasicPermissions,
            permissions = state.basicPermissions,
            setupButtonText = "Grant Permission",
            onSetupClick = if (!state.hasBasicPermissions) {
                { handleBasicPermissionsRequest() }
            } else null
        )

        // Setup Battery Tier
        setupPermissionTier(
            binding.permissionTierBattery,
            title = "Background Process",
            description = "Prevents Android from killing the firewall service to save battery. Critical for VPN reliability.",
            status = if (state.hasBatteryOptimizationExemption) "Completed" else "Setup Required",
            isComplete = state.hasBatteryOptimizationExemption,
            permissions = state.batteryOptimizationInfo,
            setupButtonText = "Grant Permission",
            onSetupClick = if (!state.hasBatteryOptimizationExemption) {
                { handleBatteryOptimizationRequest() }
            } else null
        )

        // Setup Advanced Tier
        // Show button only if root permission hasn't been requested yet
        val showRootButton = !viewModel.hasRequestedRootPermission() && !state.hasAdvancedPermissions

        setupPermissionTier(
            binding.permissionTierAdvanced,
            title = "Advanced Operations",
            description = "Package management and system-level operations",
            status = if (state.hasAdvancedPermissions) "Completed" else "Root Required",
            isComplete = state.hasAdvancedPermissions,
            permissions = state.advancedPermissions,
            setupButtonText = "Grant Root Access",
            onSetupClick = if (showRootButton) {
                { handleRootAccessRequest() }
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
                icon.setImageResource(android.R.drawable.checkbox_on_background)
                icon.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
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

        when (rootStatus) {
            RootStatus.ROOTED_WITH_PERMISSION -> {
                // Root is granted - hide root status section entirely
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootStatusInstructions.visibility = View.GONE
                tierBinding.rootingToolsTitle.visibility = View.GONE
                tierBinding.rootingToolsBody.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            RootStatus.ROOTED_NO_PERMISSION -> {
                // Device is rooted but permission not granted
                if (viewModel.hasRequestedRootPermission()) {
                    // User already tried and denied - show instructions, hide button
                    tierBinding.rootStatusContainer.visibility = View.VISIBLE
                    tierBinding.rootStatusTitle.text = Constants.RootAccess.STATUS_DENIED
                    tierBinding.rootStatusDescription.text = Constants.RootAccess.DESC_DENIED
                    tierBinding.rootStatusInstructions.visibility = View.VISIBLE
                    tierBinding.rootStatusInstructions.text = Constants.RootAccess.GRANT_INSTRUCTIONS_TITLE + "\n" + Constants.RootAccess.GRANT_INSTRUCTIONS_BODY
                    tierBinding.rootingToolsTitle.visibility = View.GONE
                    tierBinding.rootingToolsBody.visibility = View.GONE
                    tierBinding.setupButtonContainer.visibility = View.GONE
                } else {
                    // Never requested - show button, hide status
                    tierBinding.rootStatusContainer.visibility = View.GONE
                    tierBinding.rootStatusInstructions.visibility = View.GONE
                    tierBinding.rootingToolsTitle.visibility = View.GONE
                    tierBinding.rootingToolsBody.visibility = View.GONE
                    tierBinding.setupButtonContainer.visibility = View.VISIBLE
                    tierBinding.setupButton.text = "Grant Root Access"
                }
            }
            RootStatus.NOT_ROOTED -> {
                // Device is not rooted - show message, hide button
                tierBinding.rootStatusContainer.visibility = View.VISIBLE
                tierBinding.rootStatusTitle.text = Constants.RootAccess.STATUS_NOT_AVAILABLE
                tierBinding.rootStatusDescription.text = Constants.RootAccess.DESC_NOT_AVAILABLE

                // Hide the instructions text (not needed)
                tierBinding.rootStatusInstructions.visibility = View.GONE

                tierBinding.rootingToolsTitle.visibility = View.VISIBLE
                tierBinding.rootingToolsTitle.text = Constants.RootAccess.ROOTING_TOOLS_TITLE.replace("<b>", "").replace("</b>", "")

                tierBinding.rootingToolsBody.visibility = View.VISIBLE
                tierBinding.rootingToolsBody.text = Constants.RootAccess.ROOTING_TOOLS_BODY

                tierBinding.setupButtonContainer.visibility = View.GONE
            }
            RootStatus.CHECKING -> {
                // Checking root status - hide everything during check
                tierBinding.rootStatusContainer.visibility = View.GONE
                tierBinding.rootStatusInstructions.visibility = View.GONE
                tierBinding.rootingToolsTitle.visibility = View.GONE
                tierBinding.rootingToolsBody.visibility = View.GONE
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

    private fun handleRootAccessRequest() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRootTestTime < 1000) {
            Log.d(TAG, "Root test throttled - too soon")
            return
        }
        lastRootTestTime = currentTime

        // Mark that we've requested root permission
        viewModel.markRootPermissionRequested()

        var resultMessage = "🔄 Testing root access...\n\nPlease grant permission in the popup dialog."
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Root Access Test")
            .setMessage(resultMessage)
            .setCancelable(true)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                resultMessage = testRootAccess()
                if (dialog.isShowing) {
                    // Use Html.fromHtml to support bold text
                    val formattedMessage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        android.text.Html.fromHtml(resultMessage, android.text.Html.FROM_HTML_MODE_LEGACY)
                    } else {
                        @Suppress("DEPRECATION")
                        android.text.Html.fromHtml(resultMessage)
                    }
                    dialog.setMessage(formattedMessage)
                    dialog.setCancelable(true)
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK") { d, _ ->
                        d.dismiss()
                        // Refresh permissions and root status after dismissing
                        permissionViewModel.refreshPermissions()
                        viewModel.requestRootPermission()
                    }
                    // Remove cancel button
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
                }
            } catch (e: Exception) {
                resultMessage = "❌ Root test failed.\n\nError: ${e.message}"
                if (dialog.isShowing) {
                    dialog.setMessage(resultMessage)
                    dialog.setCancelable(true)
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK") { d, _ -> d.dismiss() }
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
                process.destroy()
                "⏱️ Root Test Timeout\n\nThe root permission request timed out. This may happen if:\n• You didn't respond to the permission dialog\n• Your root manager is not responding\n\nPlease try again."
            } else if (completed == 0) {
                val output = process.inputStream.bufferedReader().readText()
                "✅ Root Access Granted!\n\nYour device is rooted and De1984 has been granted superuser permission.\n\nOutput: $output"
            } else {
                "❌ Root Access Denied\n\nYour device is rooted but De1984 was denied superuser permission.\n\nTo grant access:\n• Try clicking \"Grant Root Access\" again and approve the prompt\n• If the permission prompt doesn't appear, uninstall and reinstall the app, then grant permission when prompted at first launch\n• Or manually add De1984 to your superuser app (Magisk, KernelSU, etc.)"
            }
        } catch (e: Exception) {
            "❌ Your device does not appear to be rooted. Root access is required for advanced package management features."
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    companion object {
        private const val TAG = "SettingsFragmentViews"
    }
}

