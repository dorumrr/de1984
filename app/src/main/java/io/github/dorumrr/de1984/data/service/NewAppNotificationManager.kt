package io.github.dorumrr.de1984.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
import io.github.dorumrr.de1984.data.receiver.NotificationActionReceiver
import io.github.dorumrr.de1984.ui.MainActivity
import io.github.dorumrr.de1984.utils.Constants

class NewAppNotificationManager(
    private val context: Context,
    private val activeBackend: () -> io.github.dorumrr.de1984.domain.firewall.FirewallBackendType?
) {

    companion object {
        private const val TAG = "NewAppNotificationManager"
        private const val CHANNEL_ID = "new_app_notifications"
        private const val CHANNEL_NAME = "New App Notifications"
        private const val CHANNEL_DESCRIPTION = "Notifications when new apps are installed"
        private const val NOTIFICATION_ID_BASE = 2000

        // One per app per profile, or the same app in two profiles shares a notification and its intents' extras. User 0 keeps its old values.
        private fun perProfile(key: String, userId: Int): Int = key.hashCode() + 31 * userId

        fun notificationId(packageName: String, userId: Int): Int = NOTIFICATION_ID_BASE + perProfile(packageName, userId)
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION
            enableLights(true)
            enableVibration(true)
        }

        notificationManager.createNotificationChannel(channel)
    }

    fun showNewAppNotification(packageName: String, userId: Int) {
        try {
            if (!areNotificationsEnabled()) {
                return
            }

            val appInfo = getAppInfo(packageName, userId) ?: return
            val appName = appInfo.name

            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            val de1984Icon = ContextCompat.getDrawable(context, R.drawable.de1984_icon)

            val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_de1984)
                .setLargeIcon(de1984Icon?.let { drawable -> drawableToBitmap(drawable) })
                .setContentTitle(context.getString(R.string.new_app_notification_title))
                .setContentText(context.getString(R.string.new_app_notification_text, appName))
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.new_app_notification_text, appName)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(createOpenFirewallIntent(packageName, userId))

            // No button the running firewall cannot honour; with the firewall off it stays, as the firewall screen does.
            val backend = activeBackend()
            if (backend == null || backend.reachesUser(userId, Constants.Firewall.ownUserId())) {
                if (isBlockAllDefault) {
                    notificationBuilder.addAction(createAllowAllAction(packageName, userId))
                } else {
                    notificationBuilder.addAction(createBlockAllAction(packageName, userId))
                }
            }

            val notification = notificationBuilder.build()

            notificationManager.notify(notificationId(packageName, userId), notification)

        } catch (e: Exception) {
        }
    }

    private fun areNotificationsEnabled(): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(
            Constants.Settings.KEY_NEW_APP_NOTIFICATIONS,
            Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS
        )
    }

    private fun getAppInfo(packageName: String, userId: Int): AppInfo? {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = HiddenApiHelper.getApplicationInfoAsUser(context, packageName, 0, userId) ?: return null
            val appName = packageManager.getApplicationLabel(applicationInfo).toString()
            val appIcon = packageManager.getApplicationIcon(applicationInfo)

            AppInfo(appName, appIcon)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun createOpenFirewallIntent(packageName: String, userId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Constants.Notifications.ACTION_OPEN_FIREWALL
            putExtra(Constants.Notifications.EXTRA_PACKAGE_NAME, packageName)
            putExtra(Constants.Notifications.EXTRA_USER_ID, userId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context,
            perProfile(packageName, userId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createBlockAllAction(packageName: String, userId: Int): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = Constants.Notifications.ACTION_TOGGLE_NETWORK_ACCESS
            putExtra(Constants.Notifications.EXTRA_PACKAGE_NAME, packageName)
            putExtra(Constants.Notifications.EXTRA_BLOCKED, true)
            putExtra(Constants.Notifications.EXTRA_USER_ID, userId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            perProfile(packageName + "_block", userId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_signal_cellular_off,
            context.getString(R.string.new_app_notification_action_block),
            pendingIntent
        ).build()
    }

    private fun createAllowAllAction(packageName: String, userId: Int): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = Constants.Notifications.ACTION_TOGGLE_NETWORK_ACCESS
            putExtra(Constants.Notifications.EXTRA_PACKAGE_NAME, packageName)
            putExtra(Constants.Notifications.EXTRA_BLOCKED, false)
            putExtra(Constants.Notifications.EXTRA_USER_ID, userId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            perProfile(packageName + "_allow", userId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            context.getString(R.string.new_app_notification_action_allow),
            pendingIntent
        ).build()
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private data class AppInfo(
        val name: String,
        val icon: android.graphics.drawable.Drawable
    )
}
