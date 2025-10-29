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
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.databinding.FragmentSettingsBinding
import io.github.dorumrr.de1984.databinding.PermissionTierSectionBinding
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.ui.common.StandardDialog
import io.github.dorumrr.de1984.ui.permissions.PermissionSetupViewModel
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.launch

/**
 * Settings Fragment using XML Views
 */
class SettingsFragmentViews : BaseFragment<FragmentSettingsBinding>() {

    private val viewModel: SettingsViewModel by viewModels {
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

    private val permissionViewModel: PermissionSetupViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        PermissionSetupViewModel.Factory(app.dependencies.permissionManager)
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

    private var lastRootTestTime = 0L

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

        setupViews()
        observeUiState()
        observePermissionState()
    }

    private fun setupViews() {
        // Donate button (included layout - access via root view)
        binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.donate_button)?.setOnClickListener {
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
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: io.github.dorumrr.de1984.presentation.viewmodel.SettingsUiState) {
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

                        // Refresh permissions when root permission is granted
                        if (rootStatus == RootStatus.ROOTED_WITH_PERMISSION) {
                            permissionViewModel.refreshPermissions()
                        }
                    }
                }
                launch {
                    viewModel.shizukuStatus.collect { shizukuStatus ->
                        updateShizukuStatus(shizukuStatus)

                        // Refresh permissions when Shizuku permission is granted
                        if (shizukuStatus == ShizukuStatus.RUNNING_WITH_PERMISSION) {
                            permissionViewModel.refreshPermissions()
                        }
                    }
                }
            }
        }
    }

    private fun updatePermissionTiers(state: io.github.dorumrr.de1984.ui.permissions.PermissionSetupUiState) {
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

        // Determine button text based on what's available
        val shizukuStatus = viewModel.shizukuStatus.value
        val buttonText = if (shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION) {
            "Grant Shizuku Permission"
        } else {
            "Grant Privileged Access"
        }

        setupPermissionTier(
            binding.permissionTierAdvanced,
            title = "Advanced Operations",
            description = "Package management and system-level operations (via Shizuku or root)",
            status = if (state.hasAdvancedPermissions) "Completed" else "Shizuku or Root Required",
            isComplete = state.hasAdvancedPermissions,
            permissions = state.advancedPermissions,
            setupButtonText = buttonText,
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

        // Get theme-aware icon color (textColorSecondary)
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
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
                    tierBinding.rootStatusTitle.text = Constants.RootAccess.STATUS_DENIED
                    tierBinding.rootStatusDescription.text = Constants.RootAccess.DESC_DENIED
                    tierBinding.rootStatusInstructions.visibility = View.VISIBLE
                    tierBinding.rootStatusInstructions.text = Constants.RootAccess.GRANT_INSTRUCTIONS_TITLE + "\n" + Constants.RootAccess.GRANT_INSTRUCTIONS_BODY
                    tierBinding.rootingToolsContainer.visibility = View.GONE
                    tierBinding.setupButtonContainer.visibility = View.GONE
                } else {
                    // Never requested - show button, hide status
                    tierBinding.rootStatusContainer.visibility = View.GONE
                    tierBinding.rootingToolsContainer.visibility = View.GONE
                    tierBinding.setupButtonContainer.visibility = View.VISIBLE
                    tierBinding.setupButton.text = "Grant Privileged Access"
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
                tierBinding.rootStatusTitle.text = Constants.RootAccess.STATUS_NOT_AVAILABLE
                tierBinding.rootStatusDescription.text = Constants.RootAccess.DESC_NOT_AVAILABLE
                tierBinding.rootStatusInstructions.visibility = View.GONE

                // Show rooting tools in separate card
                tierBinding.rootingToolsContainer.visibility = View.VISIBLE
                tierBinding.rootingToolsTitle.text = Constants.RootAccess.ROOTING_TOOLS_TITLE.replace("<b>", "").replace("</b>", "")
                tierBinding.rootingToolsBody.text = Constants.RootAccess.ROOTING_TOOLS_BODY

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

        // Get theme-aware icon color
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
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
                tierBinding.rootStatusTitle.text = Constants.ShizukuAccess.STATUS_DENIED
                tierBinding.rootStatusDescription.text = Constants.ShizukuAccess.DESC_DENIED
                tierBinding.rootStatusInstructions.visibility = View.GONE
                tierBinding.rootingToolsContainer.visibility = View.GONE
                tierBinding.setupButtonContainer.visibility = View.VISIBLE
                tierBinding.setupButton.text = "Grant Shizuku Permission"
                tierBinding.setupButton.setOnClickListener {
                    viewModel.grantShizukuPermission()
                }
            }
            ShizukuStatus.INSTALLED_NOT_RUNNING -> {
                // Shizuku is installed but not running
                tierBinding.rootStatusContainer.visibility = View.VISIBLE
                tierBinding.rootStatusIcon.setColorFilter(iconColor)
                tierBinding.rootStatusTitle.text = Constants.ShizukuAccess.STATUS_NOT_RUNNING
                tierBinding.rootStatusDescription.text = Constants.ShizukuAccess.DESC_NOT_RUNNING
                tierBinding.rootStatusInstructions.visibility = View.VISIBLE
                tierBinding.rootStatusInstructions.text = "Start Shizuku app to enable package management"
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

        // Mark that we've requested root permission
        viewModel.markRootPermissionRequested()

        var resultMessage = "🔄 Testing privileged access...\n\nPlease grant permission in the popup dialog if prompted."
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Privileged Access")
            .setMessage(resultMessage)
            .setCancelable(true)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
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

    companion object {
        private const val TAG = "SettingsFragmentViews"
    }
}

