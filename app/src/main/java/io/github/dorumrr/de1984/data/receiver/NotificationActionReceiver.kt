package io.github.dorumrr.de1984.data.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles notification action button clicks for new app notifications.
 * 
 * This receiver handles the "Block All" or "Allow All" button click from
 * new app installation notifications. It updates the firewall rule for the
 * package and dismisses the notification.
 * 
 * The button shown is smart - it shows the opposite of the default policy:
 * - If default policy is "Block All" → shows "Allow All" button
 * - If default policy is "Allow All" → shows "Block All" button
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationActionReceiver"
        private const val NOTIFICATION_ID_BASE = 2000
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Constants.Notifications.ACTION_TOGGLE_NETWORK_ACCESS) {
                return
            }

            val packageName = intent.getStringExtra(Constants.Notifications.EXTRA_PACKAGE_NAME)
            val blocked = intent.getBooleanExtra(Constants.Notifications.EXTRA_BLOCKED, false)

            if (packageName.isNullOrBlank()) {
                Log.w(TAG, "Received action with null/blank package name")
                return
            }

            Log.d(TAG, "Notification action: packageName=$packageName, blocked=$blocked")

            // Initialize dependencies
            val app = context.applicationContext as De1984Application
            val manageNetworkAccessUseCase = app.dependencies.provideManageNetworkAccessUseCase()

            // Use goAsync() to keep receiver alive while coroutine runs
            val pendingResult = goAsync()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                try {
                    // Update firewall rule for all networks
                    manageNetworkAccessUseCase.setAllNetworkBlocking(packageName, blocked)
                        .onSuccess {
                            Log.d(TAG, "Successfully updated network access for $packageName: blocked=$blocked")
                            
                            // Dismiss the notification
                            dismissNotification(context, packageName)
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Failed to update network access for $packageName: ${error.message}")
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing notification action", e)
                } finally {
                    // Signal that async work is complete
                    pendingResult.finish()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in NotificationActionReceiver", e)
        }
    }

    private fun dismissNotification(context: Context, packageName: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = NOTIFICATION_ID_BASE + packageName.hashCode()
            notificationManager.cancel(notificationId)
            Log.d(TAG, "Dismissed notification for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss notification for $packageName", e)
        }
    }
}

