package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receiver for package state changes from outside de1984: enable, disable and removal.
 *
 * When apps are enabled/disabled via external package managers (not de1984), Android sends
 * ACTION_PACKAGE_CHANGED. Removal sends ACTION_PACKAGE_REMOVED / ACTION_PACKAGE_FULLY_REMOVED. All
 * three change what is installed, so all three refresh the package list and drop the cached package
 * data - including the network-permission list the firewall backends apply from, which is the
 * expensive one to rebuild.
 *
 * Note: When de1984 enables/disables packages internally, it triggers
 * SharedFlow refresh directly without needing this broadcast.
 *
 * It also carries the firewall rule for a package that is still installed. That is a safety net for
 * PackageAddedReceiver: on TrebleDroid / Android 14, measured 2026-08-23, a real install delivers
 * ACTION_PACKAGE_ADDED to other apps but never to ours, while ACTION_PACKAGE_CHANGED arrives
 * reliably. Without this a reinstalled app kept the uid from its previous install - and the
 * privileged backends block by uid, so it was enforced against nothing while the UI read "Blocked".
 * Both receivers run the same use case, which is idempotent: whichever arrives first does the work.
 */
class PackageChangedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageChangedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action
            if (action != Intent.ACTION_PACKAGE_CHANGED &&
                action != Intent.ACTION_PACKAGE_REMOVED &&
                action != Intent.ACTION_PACKAGE_FULLY_REMOVED
            ) {
                AppLogger.d(TAG, "Ignoring unrelated action: $action")
                return
            }

            // Drop the cached package data first, before any filtering below can return early.
            // The set of installed packages has changed, and the firewall backends read a cached
            // network-permission list derived from it that takes seconds to rebuild - serving a
            // stale one would mean a newly installed app is not blocked.
            io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.clearInstalledAppsCache()

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

            if (Constants.App.isOwnApp(packageName)) {
                AppLogger.d(TAG, "Ignoring package change for de1984 itself")
                return
            }

            val uid = intent.getIntExtra(Intent.EXTRA_UID, -1).takeIf { it >= 0 }
            val userId = uid?.let { it / 100000 } ?: Constants.Firewall.ownUserId()

            AppLogger.i(TAG, "📦 Package $action externally: $packageName (userId=$userId) - triggering refresh")

            // Clear disabled packages cache to ensure fresh enabled/disabled state
            // This is critical for work profile apps where enabled state can change externally
            io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.clearDisabledPackagesCache()

            val app = context.applicationContext as De1984Application
            app.dependencies.notifyPackageDataChanged()

            // Only for a package that still exists. The two removal actions land here too, and there
            // is nothing to look up for a package that is gone.
            if (action == Intent.ACTION_PACKAGE_CHANGED) {
                val pendingResult = goAsync()
                app.dependencies.applicationScope.launch(Dispatchers.IO) {
                    try {
                        // Creates the rule if the package has none, and re-points an existing rule at
                        // the app's current uid and label. No notification is shown from here; that
                        // stays with PackageAddedReceiver, which owns the "new app" story.
                        app.dependencies.provideHandleNewAppInstallUseCase()
                            .execute(packageName, uid)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to refresh rule for $packageName", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error handling package changed broadcast", e)
        }
    }
}
