package io.github.dorumrr.de1984.ui.common

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Utility for showing privileged access dialogs in a consistent way across the app.
 * Follows DRY and KISS principles.
 */
object PrivilegedAccessDialog {

    /**
     * Show a dialog informing the user that privileged access is required.
     * This is used when package management operations fail due to lack of permissions.
     *
     * @param context The context to show the dialog in
     */
    fun showRequiredDialog(context: Context) {
        val message = "❌ No Privileged Access Available\n\n" +
                "Your device does not appear to be rooted. To enable advanced package management features:\n" +
                "• Install and configure Shizuku (recommended - no root required)\n" +
                "• Or root your device using Magisk, KernelSU, or APatch"

        AlertDialog.Builder(context)
            .setTitle("Privileged Access")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show a dialog informing the user that root access was denied.
     * This is used when the device is rooted but the app was denied superuser permission.
     *
     * @param context The context to show the dialog in
     * @param onOkClick Optional callback when OK button is clicked
     */
    fun showRootDeniedDialog(context: Context, onOkClick: (() -> Unit)? = null) {
        val message = "❌ Root Access Denied\n\n" +
                "Your device is rooted but De1984 was denied superuser permission.\n\n" +
                "To grant access:\n" +
                "• Try clicking \"Grant Privileged Access\" again and approve the prompt\n" +
                "• If the permission prompt doesn't appear, uninstall and reinstall the app, then grant permission when prompted at first launch\n" +
                "• Or manually add De1984 to your superuser app (Magisk, KernelSU, etc.)"

        AlertDialog.Builder(context)
            .setTitle("Privileged Access")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onOkClick?.invoke() }
            .show()
    }
}

