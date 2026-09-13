package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.data.service.NewAppNotificationManager
import io.github.dorumrr.de1984.domain.usecase.HandleNewAppInstallUseCase
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


class PackageAddedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageAddedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // A package appeared, so the cached package data is stale. Do this before the validation
        // below, which deliberately ignores cases - a reinstall, our own package - that still change
        // what is installed. The firewall backends read a cached network-permission list built from
        // this, and a stale one means a new app is not blocked.
        io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.clearInstalledAppsCache()

        try {
            val app = context.applicationContext as De1984Application
            val handleNewAppInstallUseCase = app.dependencies.provideHandleNewAppInstallUseCase()
            val newAppNotificationManager = app.dependencies.newAppNotificationManager

            val uid = intent?.getIntExtra(Intent.EXTRA_UID, -1)?.takeIf { it >= 0 }
            val userId = uid?.let { it / 100000 } ?: Constants.Firewall.ownUserId()

            val packageName = validateAndExtractPackageName(context, intent, userId)
            if (packageName == null) {
                return
            }

            // The notification preference used to return here, which also skipped the rule work.
            // That rule work is what re-points a reinstalled app's rule at its new uid, so with
            // notifications off a reinstalled app kept a uid that matched nothing and was never
            // actually blocked. Only the notification is optional; the rule is not.

            val pendingResult = goAsync()

            app.dependencies.applicationScope.launch(Dispatchers.IO) {
                try {
                    handleNewAppInstallUseCase.execute(packageName, uid)
                        .onSuccess {
                            if (areNewAppNotificationsEnabled(context)) {
                                newAppNotificationManager.showNewAppNotification(packageName, userId)
                            }
                        }
                } catch (e: Exception) {
                } finally {
                    pendingResult.finish()
                }
            }

        } catch (e: Exception) {
        }
    }
    
    private fun validateAndExtractPackageName(context: Context, intent: Intent?, userId: Int): String? {
        if (intent?.action != Intent.ACTION_PACKAGE_ADDED) {
            return null
        }

        val data = intent.data
        if (data == null || data.scheme != "package") {
            return null
        }

        val packageName = data.schemeSpecificPart
        if (packageName.isNullOrBlank()) {
            return null
        }

        if (!packageName.contains(".")) {
            return null
        }

        if (Constants.App.isOwnApp(packageName)) {
            return null
        }

        if (!isValidPackage(context, packageName, userId)) {
            return null
        }

        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        if (isReplacing) {
            return null
        }

        return packageName
    }

    /**
     * Check if package exists using HiddenApiHelper for multi-user support.
     * Work profile apps are only visible when queried with the correct userId.
     */
    private fun isValidPackage(context: Context, packageName: String, userId: Int): Boolean {
        return try {
            io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                context, packageName, 0, userId
            )
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun areNewAppNotificationsEnabled(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(
                Constants.Settings.KEY_NEW_APP_NOTIFICATIONS,
                Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS
            )
        } catch (e: Exception) {
            Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS
        }
    }
}
