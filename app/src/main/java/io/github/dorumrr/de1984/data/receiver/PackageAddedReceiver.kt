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
        try {
            // Initialize dependencies
            val app = context.applicationContext as De1984Application
            val handleNewAppInstallUseCase = app.dependencies.provideHandleNewAppInstallUseCase()
            val newAppNotificationManager = app.dependencies.newAppNotificationManager

            val packageName = validateAndExtractPackageName(context, intent)
            if (packageName == null) {
                return
            }

            if (!areNewAppNotificationsEnabled(context)) {
                return
            }

            // Use goAsync() to keep receiver alive while coroutine runs
            val pendingResult = goAsync()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                try {
                    handleNewAppInstallUseCase.execute(packageName)
                        .onSuccess {
                            newAppNotificationManager.showNewAppNotification(packageName)
                        }
                } catch (e: Exception) {
                    // Error processing new app
                } finally {
                    // Signal that async work is complete
                    pendingResult.finish()
                }
            }

        } catch (e: Exception) {
            // Error in PackageAddedReceiver
        }
    }
    
    private fun validateAndExtractPackageName(context: Context, intent: Intent?): String? {
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
        
        if (!isValidPackage(context, packageName)) {
            return null
        }
        
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        if (isReplacing) {
            return null
        }
        
        return packageName
    }
    
    private fun isValidPackage(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
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
