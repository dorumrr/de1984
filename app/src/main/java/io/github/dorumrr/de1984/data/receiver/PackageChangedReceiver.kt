package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants

/**
 * Receiver for package state changes (enable/disable) from external apps.
 * 
 * When apps are enabled/disabled via external package managers (not de1984),
 * Android sends ACTION_PACKAGE_CHANGED broadcast. We listen for this to
 * refresh the package list so the UI stays in sync.
 * 
 * Note: When de1984 enables/disables packages internally, it triggers
 * SharedFlow refresh directly without needing this broadcast.
 */
class PackageChangedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageChangedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            // Verify this is a package changed event
            if (intent?.action != Intent.ACTION_PACKAGE_CHANGED) {
                AppLogger.d(TAG, "Ignoring non-PACKAGE_CHANGED action: ${intent?.action}")
                return
            }

            // Extract package name
            val data = intent.data
            if (data == null || data.scheme != "package") {
                AppLogger.d(TAG, "Invalid intent data: scheme=${data?.scheme}")
                return
            }

            val packageName = data.schemeSpecificPart
            if (packageName.isNullOrBlank()) {
                AppLogger.d(TAG, "Empty package name")
                return
            }

            // Ignore changes to de1984 itself
            if (Constants.App.isOwnApp(packageName)) {
                AppLogger.d(TAG, "Ignoring package change for de1984 itself")
                return
            }

            // Extract UID for multi-user support
            // UID format: userId * 100000 + appId
            val uid = intent.getIntExtra(Intent.EXTRA_UID, -1).takeIf { it >= 0 }
            val userId = uid?.let { it / 100000 } ?: 0

            AppLogger.i(TAG, "📦 Package changed externally: $packageName (userId=$userId) - triggering refresh")

            // Clear disabled packages cache to ensure fresh enabled/disabled state
            // This is critical for work profile apps where enabled state can change externally
            io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.clearDisabledPackagesCache()

            // Notify observers that package data changed
            val app = context.applicationContext as De1984Application
            app.dependencies.notifyPackageDataChanged()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error handling package changed broadcast", e)
        }
    }
}
