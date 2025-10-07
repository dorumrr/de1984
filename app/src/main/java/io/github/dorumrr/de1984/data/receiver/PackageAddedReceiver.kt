package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dorumrr.de1984.data.service.NewAppNotificationManager
import io.github.dorumrr.de1984.domain.usecase.HandleNewAppInstallUseCase
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


class PackageAddedReceiver : BroadcastReceiver() {
    
    
    lateinit var handleNewAppInstallUseCase: HandleNewAppInstallUseCase
    
    
    lateinit var newAppNotificationManager: NewAppNotificationManager
    
    companion object {
        private const val TAG = "PackageAddedReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val packageName = validateAndExtractPackageName(context, intent)
            if (packageName == null) {
                return
            }
            
            if (!areNewAppNotificationsEnabled(context)) {
                return
            }
            
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                try {
                    handleNewAppInstallUseCase.execute(packageName)
                        .onSuccess {
                            newAppNotificationManager.showNewAppNotification(packageName)
                        }
                        .onFailure { error ->
                            Log.e(TAG, "❌ Failed to process new app $packageName: ${error.message}")
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Unexpected error processing new app $packageName", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in PackageAddedReceiver", e)
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
