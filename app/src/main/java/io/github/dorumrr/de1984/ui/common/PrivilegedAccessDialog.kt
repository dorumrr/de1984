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
}

