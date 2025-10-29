package io.github.dorumrr.de1984.ui.common

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.PermissionInfo
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.databinding.DialogPermissionSetupBinding
import io.github.dorumrr.de1984.databinding.PermissionTierSectionBinding
import io.github.dorumrr.de1984.utils.Constants

/**
 * Reusable modal dialog for permission setup using the permission tier component.
 * Follows DRY principle by reusing the same component used in Settings.
 */
object PermissionSetupDialog {

    /**
     * Show a generic permission setup dialog.
     *
     * @param context The context to show the dialog in
     * @param title Dialog title (default: "Privileged Access")
     * @param tierTitle Title for the permission tier
     * @param description Description text
     * @param status Status badge text
     * @param isComplete Whether the setup is complete
     * @param buttonText Text for the action button
     * @param onButtonClick Callback when action button is clicked
     * @param onDismiss Callback when dialog is dismissed
     */
    fun show(
        context: Context,
        title: String = "Privileged Access",
        tierTitle: String,
        description: String,
        status: String,
        isComplete: Boolean = false,
        buttonText: String,
        onButtonClick: () -> Unit,
        onDismiss: () -> Unit = {}
    ) {
        // Inflate the dialog layout
        val dialogBinding = DialogPermissionSetupBinding.inflate(LayoutInflater.from(context))
        val binding = dialogBinding.permissionTierSection

        // Set dialog title
        dialogBinding.dialogTitle.text = title

        // Create the dialog first so we can reference it in the button click handler
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .setOnDismissListener { onDismiss() }
            .setCancelable(true)
            .create()

        // Configure the permission tier with dialog reference for dismissal
        setupPermissionTier(
            binding = binding,
            title = tierTitle,
            description = description,
            status = status,
            isComplete = isComplete,
            permissions = emptyList(),
            setupButtonText = buttonText,
            onSetupClick = {
                dialog.dismiss()
                onButtonClick()
            }
        )

        // Show the dialog
        dialog.show()
    }

    /**
     * Convenience method for showing package management permission dialog.
     * Automatically configures the dialog based on current permission status.
     */
    fun showPackageManagementDialog(
        context: Context,
        rootStatus: RootStatus,
        shizukuStatus: ShizukuStatus,
        onGrantClick: () -> Unit,
        onSettingsClick: () -> Unit,
        onDismiss: () -> Unit = {}
    ) {
        when {
            // No privileged access available at all
            shizukuStatus == ShizukuStatus.NOT_INSTALLED && rootStatus == RootStatus.NOT_ROOTED -> {
                show(
                    context = context,
                    tierTitle = "Package Management",
                    description = Constants.PrivilegedAccessBanner.MESSAGE_NO_ACCESS_AVAILABLE,
                    status = "Setup Required",
                    buttonText = Constants.PrivilegedAccessBanner.BUTTON_GO_TO_SETTINGS,
                    onButtonClick = onSettingsClick,
                    onDismiss = onDismiss
                )
            }
            // Shizuku installed but not running, no root
            shizukuStatus == ShizukuStatus.INSTALLED_NOT_RUNNING && rootStatus == RootStatus.NOT_ROOTED -> {
                show(
                    context = context,
                    tierTitle = "Package Management",
                    description = Constants.PrivilegedAccessBanner.MESSAGE_SHIZUKU_NOT_RUNNING,
                    status = "Setup Required",
                    buttonText = Constants.PrivilegedAccessBanner.BUTTON_GO_TO_SETTINGS,
                    onButtonClick = onSettingsClick,
                    onDismiss = onDismiss
                )
            }
            // Can actually grant permission (Shizuku running or root available)
            shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION ||
            rootStatus == RootStatus.ROOTED_NO_PERMISSION -> {
                show(
                    context = context,
                    tierTitle = "Package Management",
                    description = Constants.PrivilegedAccessBanner.MESSAGE_PERMISSION_REQUIRED,
                    status = "Permission Required",
                    buttonText = Constants.PrivilegedAccessBanner.BUTTON_GRANT,
                    onButtonClick = onGrantClick,
                    onDismiss = onDismiss
                )
            }
            // Default fallback
            else -> {
                show(
                    context = context,
                    tierTitle = "Package Management",
                    description = Constants.PrivilegedAccessBanner.MESSAGE_PERMISSION_REQUIRED,
                    status = "Setup Required",
                    buttonText = Constants.PrivilegedAccessBanner.BUTTON_GO_TO_SETTINGS,
                    onButtonClick = onSettingsClick,
                    onDismiss = onDismiss
                )
            }
        }
    }

    private fun setupPermissionTier(
        binding: PermissionTierSectionBinding,
        title: String,
        description: String,
        status: String,
        isComplete: Boolean,
        permissions: List<PermissionInfo>,
        setupButtonText: String,
        onSetupClick: () -> Unit
    ) {
        // Set title and description
        binding.tierTitle.text = title
        binding.tierDescription.text = description

        // Set status badge
        binding.tierStatusBadge.text = status
        binding.tierStatusBadge.setBackgroundResource(
            if (isComplete) R.drawable.status_badge_complete
            else R.drawable.status_badge_background
        )

        // Hide permissions list for package management dialog
        binding.permissionsListContainer.removeAllViews()
        binding.permissionsListContainer.visibility = android.view.View.GONE

        // Setup button
        binding.setupButtonContainer.visibility = android.view.View.VISIBLE
        binding.setupButton.text = setupButtonText
        binding.setupButton.setOnClickListener { onSetupClick() }

        // Hide root status sections for package management dialog
        binding.rootStatusContainer.visibility = android.view.View.GONE
        binding.rootingToolsContainer.visibility = android.view.View.GONE
    }
}
