package io.github.dorumrr.de1984.data.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.data.service.NewAppNotificationManager
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Constants.Notifications.ACTION_TOGGLE_NETWORK_ACCESS) {
                return
            }

            val packageName = intent.getStringExtra(Constants.Notifications.EXTRA_PACKAGE_NAME)
            val blocked = intent.getBooleanExtra(Constants.Notifications.EXTRA_BLOCKED, false)
            // Absent on a notification posted before the user was sent; that version found the app in De1984's own profile.
            val userId = intent.getIntExtra(Constants.Notifications.EXTRA_USER_ID, Constants.Firewall.ownUserId())

            if (packageName.isNullOrBlank()) {
                AppLogger.w(TAG, "Received action with null/blank package name")
                return
            }

            AppLogger.d(TAG, "Notification action: packageName=$packageName, userId=$userId, blocked=$blocked")

            val app = context.applicationContext as De1984Application
            val manageNetworkAccessUseCase = app.dependencies.provideManageNetworkAccessUseCase()

            val pendingResult = goAsync()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                try {
                    // Update firewall rule for all networks.
                    //
                    // setNetworkAccess, not setAllNetworkBlocking: these buttons say "Block All" and
                    // "Allow All", so they must cover all four dimensions including LAN. The narrow
                    // call could not clear the lanBlocked that a new app's own Block All rule sets,
                    // so tapping "Allow All" left the app's LAN blocked while the list read Allowed.
                    manageNetworkAccessUseCase.setNetworkAccess(packageName, userId = userId, allowed = !blocked)
                        .onSuccess {
                            AppLogger.d(TAG, "Successfully updated network access for $packageName: blocked=$blocked")

                            dismissNotification(context, packageName, userId)
                        }
                        .onFailure { error ->
                            AppLogger.e(TAG, "Failed to update network access for $packageName: ${error.message}")
                        }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error processing notification action", e)
                } finally {
                    pendingResult.finish()
                }
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in NotificationActionReceiver", e)
        }
    }

    private fun dismissNotification(context: Context, packageName: String, userId: Int) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NewAppNotificationManager.notificationId(packageName, userId))
            AppLogger.d(TAG, "Dismissed notification for $packageName (userId=$userId)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to dismiss notification for $packageName", e)
        }
    }
}
