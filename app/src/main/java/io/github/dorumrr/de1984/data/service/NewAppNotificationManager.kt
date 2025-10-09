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
import io.github.dorumrr.de1984.data.receiver.NewAppNotificationReceiver
import io.github.dorumrr.de1984.ui.MainActivityViews
import io.github.dorumrr.de1984.utils.Constants

class NewAppNotificationManager(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "NewAppNotificationManager"
        private const val CHANNEL_ID = "new_app_notifications"
        private const val CHANNEL_NAME = "New App Notifications"
        private const val CHANNEL_DESCRIPTION = "Notifications when new apps are installed"
        private const val NOTIFICATION_ID_BASE = 2000
        
        const val ACTION_BLOCK_ALL = "io.github.dorumrr.de1984.BLOCK_ALL"
        const val ACTION_BLOCK_WIFI = "io.github.dorumrr.de1984.BLOCK_WIFI"
        const val ACTION_BLOCK_MOBILE = "io.github.dorumrr.de1984.BLOCK_MOBILE"
        const val ACTION_ALLOW_ALL = "io.github.dorumrr.de1984.ALLOW_ALL"
        const val ACTION_ALLOW_WIFI = "io.github.dorumrr.de1984.ALLOW_WIFI"
        const val ACTION_ALLOW_MOBILE = "io.github.dorumrr.de1984.ALLOW_MOBILE"
        const val ACTION_OPEN_FIREWALL = "io.github.dorumrr.de1984.OPEN_FIREWALL"
        
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }
    
    fun showNewAppNotification(packageName: String) {
        try {
            if (!areNotificationsEnabled()) {
                return
            }
            
            val appInfo = getAppInfo(packageName) ?: return
            val appName = appInfo.name

            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

            val de1984Icon = ContextCompat.getDrawable(context, R.drawable.de1984_icon)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_de1984)
                .setLargeIcon(de1984Icon?.let { drawable -> drawableToBitmap(drawable) })
                .setContentTitle("De1984 detected a new app")
                .setContentText("$appName was installed. Configure firewall rules?")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("$appName was installed and has network permissions. Choose how to handle network access:"))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(createOpenFirewallIntent(packageName))
                .apply {
                    if (isBlockAllDefault) {
                        addAction(createAllowAllAction(packageName))
                        addAction(createAllowWifiAction(packageName))
                        addAction(createAllowMobileAction(packageName))
                    } else {
                        addAction(createBlockAllAction(packageName))
                        addAction(createBlockWifiAction(packageName))
                        addAction(createBlockMobileAction(packageName))
                    }
                }
                .build()
            
            val notificationId = NOTIFICATION_ID_BASE + packageName.hashCode()
            notificationManager.notify(notificationId, notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show notification for $packageName", e)
        }
    }
    
    private fun areNotificationsEnabled(): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(
            Constants.Settings.KEY_NEW_APP_NOTIFICATIONS,
            Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS
        )
    }
    
    private fun getAppInfo(packageName: String): AppInfo? {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(applicationInfo).toString()
            val appIcon = packageManager.getApplicationIcon(applicationInfo)
            
            AppInfo(appName, appIcon)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun createOpenFirewallIntent(packageName: String): PendingIntent {
        val intent = Intent(context, MainActivityViews::class.java).apply {
            action = ACTION_OPEN_FIREWALL
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        return PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private fun createBlockAllAction(packageName: String): NotificationCompat.Action {
        val intent = Intent(context, NewAppNotificationReceiver::class.java).apply {
            action = ACTION_BLOCK_ALL
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (packageName + ACTION_BLOCK_ALL).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_signal_cellular_off,
            "Block All",
            pendingIntent
        ).build()
    }

    private fun createBlockWifiAction(packageName: String): NotificationCompat.Action {
        val intent = Intent(context, NewAppNotificationReceiver::class.java).apply {
            action = ACTION_BLOCK_WIFI
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (packageName + ACTION_BLOCK_WIFI).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Action.Builder(
            R.drawable.ic_wifi_off,
            "Block WiFi",
            pendingIntent
        ).build()
    }
    
    private fun createBlockMobileAction(packageName: String): NotificationCompat.Action {
        val intent = Intent(context, NewAppNotificationReceiver::class.java).apply {
            action = ACTION_BLOCK_MOBILE
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (packageName + ACTION_BLOCK_MOBILE).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Action.Builder(
            R.drawable.ic_signal_cellular_off,
            "Block Mobile",
            pendingIntent
        ).build()
    }
    
    private fun createAllowAllAction(packageName: String): NotificationCompat.Action {
        val intent = Intent(context, NewAppNotificationReceiver::class.java).apply {
            action = ACTION_ALLOW_ALL
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (packageName + ACTION_ALLOW_ALL).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            "Allow All",
            pendingIntent
        ).build()
    }
    
    private fun createAllowWifiAction(packageName: String): NotificationCompat.Action {
        val intent = Intent(context, NewAppNotificationReceiver::class.java).apply {
            action = ACTION_ALLOW_WIFI
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (packageName + ACTION_ALLOW_WIFI).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            "Allow WiFi",
            pendingIntent
        ).build()
    }

    private fun createAllowMobileAction(packageName: String): NotificationCompat.Action {
        val intent = Intent(context, NewAppNotificationReceiver::class.java).apply {
            action = ACTION_ALLOW_MOBILE
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (packageName + ACTION_ALLOW_MOBILE).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            "Allow Mobile",
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
