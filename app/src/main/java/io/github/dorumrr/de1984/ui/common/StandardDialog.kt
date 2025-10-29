package io.github.dorumrr.de1984.ui.common

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Standard reusable dialog component for simple dialogs across the app.
 * 
 * Use this for:
 * - Confirmation dialogs (two buttons)
 * - Error messages (one button)
 * - Information messages (one button)
 * - Welcome/onboarding dialogs (two buttons)
 * 
 * For complex permission setup flows with status badges and cards,
 * use PermissionSetupDialog instead.
 * 
 * This dialog follows Material Design 3 standards and matches the styling
 * of the firewall start dialog in MainActivity.
 */
object StandardDialog {

    /**
     * Show a standard dialog with customizable title, message, and buttons.
     * 
     * @param context The context to show the dialog in
     * @param title Dialog title
     * @param message Dialog message/description
     * @param positiveButtonText Text for the positive (confirm) button
     * @param onPositiveClick Callback when positive button is clicked
     * @param negativeButtonText Optional text for the negative (cancel) button. If null, no negative button is shown.
     * @param onNegativeClick Optional callback when negative button is clicked
     * @param cancelable Whether the dialog can be dismissed by tapping outside or pressing back (default: true)
     * @param onDismiss Optional callback when dialog is dismissed
     */
    fun show(
        context: Context,
        title: String,
        message: String,
        positiveButtonText: String,
        onPositiveClick: () -> Unit = {},
        negativeButtonText: String? = null,
        onNegativeClick: (() -> Unit)? = null,
        cancelable: Boolean = true,
        onDismiss: (() -> Unit)? = null
    ) {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButtonText) { _, _ -> onPositiveClick() }
            .setCancelable(cancelable)

        // Add negative button if text is provided
        if (negativeButtonText != null) {
            builder.setNegativeButton(negativeButtonText) { _, _ -> 
                onNegativeClick?.invoke()
            }
        }

        // Add dismiss listener if provided
        if (onDismiss != null) {
            builder.setOnDismissListener { onDismiss() }
        }

        builder.show()
    }

    /**
     * Convenience method for showing an error dialog with a single "OK" button.
     * 
     * @param context The context to show the dialog in
     * @param message Error message to display
     * @param title Dialog title (default: "Error")
     * @param onDismiss Optional callback when dialog is dismissed
     */
    fun showError(
        context: Context,
        message: String,
        title: String = "Error",
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveButtonText = "OK",
            onPositiveClick = { onDismiss?.invoke() },
            cancelable = true,
            onDismiss = onDismiss
        )
    }

    /**
     * Convenience method for showing an information dialog with a single "OK" button.
     * 
     * @param context The context to show the dialog in
     * @param title Dialog title
     * @param message Information message to display
     * @param onDismiss Optional callback when dialog is dismissed
     */
    fun showInfo(
        context: Context,
        title: String,
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveButtonText = "OK",
            onPositiveClick = { onDismiss?.invoke() },
            cancelable = true,
            onDismiss = onDismiss
        )
    }

    /**
     * Convenience method for showing a confirmation dialog with "Confirm" and "Cancel" buttons.
     * 
     * @param context The context to show the dialog in
     * @param title Dialog title
     * @param message Confirmation message to display
     * @param confirmButtonText Text for the confirm button (default: "Confirm")
     * @param onConfirm Callback when confirm button is clicked
     * @param cancelButtonText Text for the cancel button (default: "Cancel")
     * @param onCancel Optional callback when cancel button is clicked
     */
    fun showConfirmation(
        context: Context,
        title: String,
        message: String,
        confirmButtonText: String = "Confirm",
        onConfirm: () -> Unit,
        cancelButtonText: String = "Cancel",
        onCancel: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveButtonText = confirmButtonText,
            onPositiveClick = onConfirm,
            negativeButtonText = cancelButtonText,
            onNegativeClick = onCancel,
            cancelable = true
        )
    }

    /**
     * Convenience method for showing a "no privileged access" dialog.
     * This replaces the legacy PrivilegedAccessDialog.showRequiredDialog().
     * 
     * @param context The context to show the dialog in
     * @param onDismiss Optional callback when dialog is dismissed
     */
    fun showNoAccessDialog(
        context: Context,
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = "Privileged Access",
            message = "❌ No Privileged Access Available\n\n" +
                    "Your device does not appear to be rooted. To enable advanced package management features:\n" +
                    "• Install and configure Shizuku (recommended - no root required)\n" +
                    "• Or root your device using Magisk, KernelSU, or APatch",
            positiveButtonText = "OK",
            onPositiveClick = { onDismiss?.invoke() },
            cancelable = true,
            onDismiss = onDismiss
        )
    }

    /**
     * Convenience method for showing a "root access denied" dialog.
     * This replaces the legacy PrivilegedAccessDialog.showRootDeniedDialog().
     * 
     * @param context The context to show the dialog in
     * @param onOkClick Optional callback when OK button is clicked
     */
    fun showRootDeniedDialog(
        context: Context,
        onOkClick: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = "Privileged Access",
            message = "❌ Root Access Denied\n\n" +
                    "Your device is rooted but De1984 was denied superuser permission.\n\n" +
                    "To grant access:\n" +
                    "• Try clicking \"Grant Privileged Access\" again and approve the prompt\n" +
                    "• If the permission prompt doesn't appear, uninstall and reinstall the app, then grant permission when prompted at first launch\n" +
                    "• Or manually add De1984 to your superuser app (Magisk, KernelSU, etc.)",
            positiveButtonText = "OK",
            onPositiveClick = { onOkClick?.invoke() },
            cancelable = true,
            onDismiss = onOkClick
        )
    }
}

